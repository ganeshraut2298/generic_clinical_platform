package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.GoodsReceiptRepository.Line;
import com.sahaj.pharmacy.application.GoodsReceiptRepository.NewLine;
import com.sahaj.pharmacy.application.GoodsReceiptRepository.Row;
import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.BatchLockOrder;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.GoodsReceiptStatus;
import com.sahaj.pharmacy.domain.PurchaseMath;
import com.sahaj.pharmacy.domain.ReceiptMath;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.pharmacy.domain.Vendor;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-7: receiving stock at the Central Store (docs/api/pharmacy.yaml 0.3.2 and 0.3.3, ADR-029 P5, P6, P7, P10, design note
 * sections 13.2 and 13.5). A receipt is a DRAFT without a number or a stock effect; posting takes the GRN number, adds
 * {@code (received + free) x conversion} issue units to the Central Store AVAILABLE through {@link StockPosting}, creates or
 * tops up the batches, moves the purchase order, writes the vendor payable and prepares the debit note of the short supply, all
 * in one transaction. A rate rise that the GRN_RATE_RISE rule catches sends the receipt for approval instead; the final approval
 * makes it APPROVED and a Central Store user posts it ({@link ApprovalSubjectHandler}). Schedule X lines need a pharmacist and a
 * second signer (rule 19, ADR-029 P7).
 *
 * <p>Lock order of the posting (every step only when needed, never backwards): the client key, the store row (ACTIVE re-read,
 * DEC-077 L9), the receipt header, the purchase order header and its lines, the GRN number, the batches in key order, the stock
 * balances ({@link StockPosting}), then the inserts and updates. A transaction that has taken a number never locks the unit row
 * {@code FOR UPDATE} afterwards (migration 1720, L11). Lines are written before the header leaves DRAFT and every read of lines is
 * {@code not removed}. The actor of every writer is {@link PharmacyCaller#actorId()}. Audit rows hold ids, counts and totals only;
 * logs hold ids and counts, never a batch number, a name or an amount.
 */
@Service
public class GoodsReceiptService implements ApprovalSubjectHandler {

    static final String DOC_TYPE = "GOODS_RECEIPT";
    static final String OP_CREATE = "pharmacyCreateGoodsReceipt";
    static final String OP_POST = "pharmacyPostGoodsReceipt";
    static final String CENTRAL_ONLY = "Receipts are made at the Central Store.";
    static final String NOT_EDITABLE = "Only a draft receipt can be edited.";
    static final String EXPIRY_MESSAGE = "Expiry must be in the future";
    static final String TOTAL_MISMATCH = "Invoice total does not match the lines.";
    static final String PO_NOT_OPEN = "Only a sent or partly received purchase order can be received.";
    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptService.class);
    private static final String ENTITY_TYPE = "PHARMACY_GOODS_RECEIPT";
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;
    private static final long MAX_ISSUE_UNITS = 99_999_999L;
    private static final long MAX_TOTAL_PAISE = 99_999_999_900L;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final VendorRepository vendors;
    private final PurchaseOrderRepository orders;
    private final GoodsReceiptRepository receipts;
    private final VendorPayableRepository payables;
    private final DebitNoteRepository debitNotes;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final DocumentViews views;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public GoodsReceiptService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                               VendorRepository vendors, PurchaseOrderRepository orders, GoodsReceiptRepository receipts,
                               VendorPayableRepository payables, DebitNoteRepository debitNotes, ApprovalEngine engine,
                               ApprovalRepository approvals, DocumentSeriesRepository series, DocumentNumberPort numbers,
                               ClientRequestRepository clients, DocumentEventRepository events, StockRepository stock,
                               StockPosting posting, ScheduleXSignature signature, DocumentViews views, AuditLog auditLog,
                               PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.vendors = vendors;
        this.orders = orders;
        this.receipts = receipts;
        this.payables = payables;
        this.debitNotes = debitNotes;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.stock = stock;
        this.posting = posting;
        this.signature = signature;
        this.views = views;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public PurchaseViews.GrnPage list(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawPage,
                                      String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        centralStore(caller, storeId); // the store guard first (m-1): a caller with no access learns nothing from field errors
        GoodsReceiptStatus status = PharmacyParams.optionalEnum("status", rawStatus, GoodsReceiptStatus.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        if (status == GoodsReceiptStatus.DISCARDED) {
            return new PurchaseViews.GrnPage(List.of(), 0, page, size);
        }
        GoodsReceiptRepository.Slice slice = receipts.list(tenantId, storeId, status == null ? null : status.name(), size,
                page * size);
        List<PurchaseViews.GrnRow> items = slice.items().stream().map(r -> new PurchaseViews.GrnRow(r.id(), r.docNo(),
                r.vendorName(), r.invoiceNo(), r.invoiceTotalPaise(), r.status(), r.createdAt())).toList();
        return new PurchaseViews.GrnPage(items, slice.total(), page, size);
    }

    /** A current approver of the receipt's rate-rise request reads it without the purchase capability. */
    @Transactional(readOnly = true)
    public PurchaseViews.GoodsReceipt get(UUID tenantId, UUID userId, String rawStoreId, String rawGrnId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID grnId = PharmacyParams.pathId(rawGrnId);
        Row row = receipts.find(tenantId, storeId, grnId).orElse(null);
        if (row != null && isCurrentApprover(caller, row)) {
            return view(caller, row, null);
        }
        guard.require(caller, storeId, StoreCapability.PURCHASE);
        return view(caller, receipts.find(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound), null);
    }

    // ---- create, update, discard, reopen ----

    @Transactional
    public PharmacyViews.Outcome<PurchaseViews.GoodsReceipt> create(UUID tenantId, UUID userId, String rawStoreId,
            PharmacyInputs.Body<PharmacyInputs.GoodsReceiptCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.GoodsReceiptCreate in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return new PharmacyViews.Outcome<>(view(caller, receipts.find(tenantId, storeId, own.subjectId())
                        .orElseThrow(GoodsReceiptService::notFound), null), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        PurchaseOrderRepository.Row po = null;
        if (in.purchaseOrderId() != null) {
            po = orders.find(tenantId, storeId, in.purchaseOrderId()).orElseThrow(GoodsReceiptService::notFound);
            if (!openForReceiving(po)) {
                throw PharmacyProblemException.docState(PO_NOT_OPEN);
            }
        }
        Checked checked = check(tenantId, storeId, po, in.vendorId(), in.invoiceNo(), in.invoiceDate(), in.creditDays(),
                in.invoiceTotalPaise(), in.lines(), errors);
        throwIfAny(errors);
        if (po == null && checked.vendor().blocked(time.today())) {
            throw PharmacyProblemException.vendorBlocked();
        }
        if (receipts.invoiceTaken(tenantId, checked.vendor().id(), checked.invoiceNo(), null)) {
            throw PharmacyProblemException.duplicateInvoice();
        }
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body); // L15: the same key committed first, answer as its replay
        }
        try {
            receipts.insert(tenantId, store.unitId(), id, storeId, po == null ? null : po.id(), checked.vendor().id(),
                    checked.invoiceNo(), checked.invoiceDate(), checked.creditDays(), checked.invoiceTotalPaise(), me);
        } catch (DuplicateKeyException duplicate) {
            throw PharmacyProblemException.duplicateInvoice();
        }
        for (LineCtx ctx : checked.lines()) {
            receipts.insertLine(tenantId, store.unitId(), id, ctx.line(), me);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_GRN_CREATED", ENTITY_TYPE, id, null, values(storeId, checked.vendor().id(),
                checked.lines().size(), checked.computedTotal(), "DRAFT"));
        log.info("Goods receipt created goodsReceiptId={} lines={}", id, checked.lines().size());
        return new PharmacyViews.Outcome<>(view(caller, receipts.find(tenantId, storeId, id).orElseThrow(), null), true);
    }

    @Transactional
    public PurchaseViews.GoodsReceipt update(UUID tenantId, UUID userId, String rawStoreId, String rawGrnId,
                                             PharmacyInputs.Body<PharmacyInputs.GoodsReceiptUpdate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID grnId = PharmacyParams.pathId(rawGrnId);
        PharmacyInputs.GoodsReceiptUpdate in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        Row peek = receipts.find(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        PurchaseOrderRepository.Row po = peek.purchaseOrderId() == null ? null
                : orders.find(tenantId, storeId, peek.purchaseOrderId()).orElseThrow(GoodsReceiptService::notFound);
        Checked checked = check(tenantId, storeId, po, po == null ? in.vendorId() : null, in.invoiceNo(), in.invoiceDate(),
                in.creditDays(), in.invoiceTotalPaise(), in.lines(), errors);
        throwIfAny(errors);
        Row row = receipts.findForUpdate(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        requireVersion(caller, row, version);
        if (!GoodsReceiptStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState(NOT_EDITABLE);
        }
        if (po == null && checked.vendor().blocked(time.today())) {
            throw PharmacyProblemException.vendorBlocked();
        }
        if (receipts.invoiceTaken(tenantId, checked.vendor().id(), checked.invoiceNo(), grnId)) {
            throw PharmacyProblemException.duplicateInvoice();
        }
        List<Line> live = receipts.lines(tenantId, grnId);
        int keep = Math.min(live.size(), checked.lines().size());
        for (int i = 0; i < keep; i++) {
            receipts.updateLine(tenantId, live.get(i).id(), checked.lines().get(i).line(), me);
        }
        for (int i = keep; i < live.size(); i++) {
            receipts.removeLine(tenantId, live.get(i).id(), me);
        }
        for (int i = keep; i < checked.lines().size(); i++) {
            receipts.insertLine(tenantId, store.unitId(), grnId, checked.lines().get(i).line(), me);
        }
        try {
            if (!receipts.updateDraft(tenantId, grnId, row.version(), checked.vendor().id(), checked.invoiceNo(),
                    checked.invoiceDate(), checked.creditDays(), checked.invoiceTotalPaise(), me)) {
                throw new IllegalStateException("A goods receipt changed under its lock");
            }
        } catch (DuplicateKeyException duplicate) {
            throw PharmacyProblemException.duplicateInvoice();
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, grnId, "CHANGED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_GRN_CHANGED", ENTITY_TYPE, grnId, Map.of("version", row.version()), values(storeId,
                checked.vendor().id(), checked.lines().size(), checked.computedTotal(), "DRAFT"));
        return view(caller, receipts.find(tenantId, storeId, grnId).orElseThrow(), null);
    }

    @Transactional
    public PurchaseViews.GoodsReceipt discard(UUID tenantId, UUID userId, String rawStoreId, String rawGrnId,
                                              PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID grnId = PharmacyParams.pathId(rawGrnId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = centralStore(caller, storeId);
        receipts.find(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        Row row = receipts.findForUpdate(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        requireVersion(caller, row, version);
        boolean draft = GoodsReceiptStatus.DRAFT.name().equals(row.status());
        boolean approved = GoodsReceiptStatus.APPROVED.name().equals(row.status());
        if (!draft && !approved) {
            throw PharmacyProblemException.docState("Only a draft or an approved receipt that is not posted can be discarded.");
        }
        UUID me = caller.actorId();
        long current = row.version();
        if (approved) {
            // the guard allows APPROVED only to POSTED or DRAFT: the discard goes through DRAFT in the same transaction
            receipts.setStatus(tenantId, grnId, current, GoodsReceiptStatus.DRAFT.name(), me);
            current++;
        }
        receipts.setStatus(tenantId, grnId, current, GoodsReceiptStatus.DISCARDED.name(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, grnId, "DISCARDED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_GRN_DISCARDED", ENTITY_TYPE, grnId, Map.of("status", row.status()),
                Map.of("status", "DISCARDED"));
        return view(caller, receipts.find(tenantId, storeId, grnId).orElseThrow(), null);
    }

    @Transactional
    public PurchaseViews.GoodsReceipt reopen(UUID tenantId, UUID userId, String rawStoreId, String rawGrnId,
                                             PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID grnId = PharmacyParams.pathId(rawGrnId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = centralStore(caller, storeId);
        receipts.find(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        Row row = receipts.findForUpdate(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        requireVersion(caller, row, version);
        if (!GoodsReceiptStatus.APPROVED.name().equals(row.status())) {
            throw PharmacyProblemException.docState("Only an approved receipt that is not posted can be changed.");
        }
        UUID me = caller.actorId();
        receipts.setStatus(tenantId, grnId, row.version(), GoodsReceiptStatus.DRAFT.name(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, grnId, "REOPENED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_GRN_REOPENED", ENTITY_TYPE, grnId, Map.of("status", "APPROVED"), Map.of("status", "DRAFT"));
        return view(caller, receipts.find(tenantId, storeId, grnId).orElseThrow(), null);
    }

    // ---- post ----

    /**
     * The first step of a post, before any PIN is checked: the gate, the path ids, the body and the store guard, in that
     * order. Returns the second signer the body names when the receipt has Schedule X lines and the caller is a pharmacist
     * (so only an authorised caller can make a PIN attempt, and only when a PIN is needed); a replay needs no PIN.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId, String rawGrnId,
                                                     PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID grnId = PharmacyParams.pathId(rawGrnId);
        PharmacyInputs.PostWithSigner in = body.read();
        centralStore(caller, storeId);
        Row peek = receipts.find(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        // m-6: only a receipt that can be posted makes a PIN attempt; the post answers 409 for any other state
        if (!GoodsReceiptStatus.DRAFT.name().equals(peek.status()) && !GoodsReceiptStatus.APPROVED.name().equals(peek.status())) {
            return null;
        }
        boolean scheduleX = false;
        for (Line l : receipts.lines(tenantId, grnId)) {
            scheduleX |= isScheduleX(products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElse(null));
        }
        if (!scheduleX) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PurchaseViews.GoodsReceipt post(UUID tenantId, UUID userId, String rawStoreId, String rawGrnId,
                                           PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body,
                                           UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID grnId = PharmacyParams.pathId(rawGrnId);
        PharmacyInputs.PostWithSigner in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        Row peek = receipts.find(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_POST.equals(own.operation()) || !grnId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return view(caller, peek, outcomeOf(peek.status()));
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        throwIfAny(errors);

        // lock order: the client key, the store (ACTIVE again), the receipt
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_POST, grnId, true) != null) {
            return post(tenantId, userId, rawStoreId, rawGrnId, body, verifiedSignerMembershipId); // L15
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(GoodsReceiptService::notFound);
        Row row = receipts.findForUpdate(tenantId, storeId, grnId).orElseThrow(GoodsReceiptService::notFound);
        requireVersion(caller, row, version);
        boolean fromDraft = GoodsReceiptStatus.DRAFT.name().equals(row.status());
        if (!fromDraft && !GoodsReceiptStatus.APPROVED.name().equals(row.status())) {
            throw PharmacyProblemException.docState("This receipt cannot be posted now.");
        }

        // every create rule again, then the rules of a post
        PurchaseOrderRepository.Row po = row.purchaseOrderId() == null ? null
                : orders.find(tenantId, storeId, row.purchaseOrderId()).orElseThrow(GoodsReceiptService::notFound);
        List<Line> stored = receipts.lines(tenantId, grnId);
        Checked checked = check(tenantId, storeId, po, po == null ? row.vendorId() : null, row.invoiceNo(), row.invoiceDate(),
                BigDecimal.valueOf(row.creditDays()), BigDecimal.valueOf(row.invoiceTotalPaise()), asInputs(stored), errors);
        throwIfAny(errors);
        if (po == null && checked.vendor().blocked(time.today())) {
            throw PharmacyProblemException.vendorBlocked();
        }
        if (receipts.invoiceTaken(tenantId, row.vendorId(), row.invoiceNo(), grnId)) {
            throw PharmacyProblemException.duplicateInvoice();
        }
        if (checked.lines().isEmpty()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", "Add at least one item")));
        }
        if (Math.abs(row.invoiceTotalPaise() - checked.computedTotal()) > ReceiptMath.TOTAL_TOLERANCE_PAISE) {
            throw invalidTotal();
        }
        List<FieldMessage> shortErrors = new ArrayList<>();
        for (int i = 0; i < checked.lines().size(); i++) {
            NewLine l = checked.lines().get(i).line();
            if (checked.lines().get(i).poLine() != null && l.shortQty() != null && l.shortQty() > 0 && l.shortAction() == null) {
                shortErrors.add(new FieldMessage("lines[" + i + "].shortAction", "Choose balance to come or close short"));
            }
        }
        throwIfAny(shortErrors);

        // draft lines take the values worked out now (the last posted rate may have moved); an approved receipt keeps its own
        List<Line> lines = stored;
        if (fromDraft) {
            for (int i = 0; i < stored.size(); i++) {
                receipts.updateLine(tenantId, stored.get(i).id(), checked.lines().get(i).line(), me);
            }
            lines = receipts.lines(tenantId, grnId);
            ApprovalEngine.Plan plan = ratePlan(tenantId, storeId, grnId, lines, checked);
            if (plan != null) {
                return sendForApproval(caller, store, row, lines, checked, plan);
            }
        }

        // Schedule X: a pharmacist and a second signer who is another active pharmacist or owner
        boolean scheduleX = checked.lines().stream().anyMatch(c -> isScheduleX(c.product()));
        UUID signer = null;
        if (scheduleX) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }
        return doPost(caller, store, row, lines, checked, po, signer);
    }

    private PurchaseViews.GoodsReceipt sendForApproval(PharmacyCaller caller, Store store, Row row, List<Line> lines,
                                                       Checked checked, ApprovalEngine.Plan plan) {
        UUID tenantId = caller.tenantId();
        UUID me = caller.actorId();
        ApprovalEngine.Subject subject = ratePlanSubject(row.storeId(), row.id(), lines, checked);
        UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
        if (!receipts.sendForApproval(tenantId, row.id(), row.version(), requestId, me)) {
            throw new IllegalStateException("A goods receipt changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, row.id(), "SUBMITTED", row.storeId(), me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", GoodsReceiptStatus.WAITING_APPROVAL.name());
        after.put("lineCount", lines.size());
        after.put("riseLineCount", lines.stream().filter(l -> l.ratePercent() != null && l.ratePercent().signum() > 0).count());
        auditLog.record("PHARMACY_GRN_SENT_FOR_APPROVAL", ENTITY_TYPE, row.id(), Map.of("status", "DRAFT"), after);
        log.info("Goods receipt sent for approval goodsReceiptId={}", row.id());
        return view(caller, receipts.find(tenantId, row.storeId(), row.id()).orElseThrow(), "SENT_FOR_APPROVAL");
    }

    private PurchaseViews.GoodsReceipt doPost(PharmacyCaller caller, Store store, Row row, List<Line> lines, Checked checked,
                                              PurchaseOrderRepository.Row po, UUID signer) {
        UUID tenantId = caller.tenantId();
        UUID me = caller.actorId();
        UUID grnId = row.id();
        UUID storeId = row.storeId();
        LocalDate today = time.today();

        // step 3a: the purchase order header, then its lines (a receipt locks its own header first)
        PurchaseOrderRepository.Row lockedPo = null;
        Map<UUID, Integer> shortNow = Map.of();
        if (po != null) {
            lockedPo = orders.findForUpdateById(tenantId, po.id()).orElseThrow(GoodsReceiptService::notFound);
            if (!openForReceiving(lockedPo)) {
                throw PharmacyProblemException.docState(PO_NOT_OPEN);
            }
            // m-9: a parallel receipt may have moved received_qty since check() read the lines, so the short quantity is worked
            // out again on the locked lines; the stored column is a draft-time figure (content is frozen after DRAFT)
            shortNow = shortQuantitiesNow(lines, orders.linesForUpdate(tenantId, po.id()));
        }

        // step 4: the GRN number
        long seq = numbers.next(tenantId, DocType.GRN);
        String docNo = render(tenantId, seq, today);

        // step 6: batches in product, batch key and expiry order
        Map<UUID, Integer> conversionOf = new HashMap<>();
        checked.lines().forEach(c -> conversionOf.put(c.line().productId(), c.product().conversion()));
        Map<BatchLockOrder.Key, List<Line>> groups = new TreeMap<>(BatchLockOrder.ORDER);
        for (Line l : lines) {
            groups.computeIfAbsent(BatchLockOrder.key(l.productId(), l.batchNo(), l.expiryDate()), k -> new ArrayList<>()).add(l);
        }
        Map<UUID, BigDecimal> landed = new HashMap<>();
        Map<UUID, UUID> batchOfLine = new HashMap<>();
        List<FailingLine> mismatches = new ArrayList<>();
        int rowNo = 0;
        Map<UUID, Integer> rowNoOfLine = new HashMap<>();
        for (Line l : lines) {
            rowNoOfLine.put(l.id(), ++rowNo);
        }
        for (List<Line> group : groups.values()) {
            Line first = group.get(0);
            for (Line l : group) {
                landed.put(l.id(), ReceiptMath.landedRate(l.billedQty(), l.ptr(), l.discountPercent(), l.qty(), l.freeQty(),
                        conversionOf.get(l.productId())));
            }
            Line latest = group.get(group.size() - 1);
            stock.ensureBatch(tenantId, store.unitId(), first.productId(), first.batchNo(), first.expiryDate(), first.mrp(),
                    landed.get(latest.id()), "GRN", me);
            BatchRow batch = stock.findBatchForUpdate(tenantId, first.productId(), first.batchNo(), first.expiryDate())
                    .orElseThrow();
            boolean clash = false;
            for (Line l : group) {
                if (batch.mrp().compareTo(l.mrp()) != 0) {
                    clash = true;
                    if (mismatches.size() < 100) {
                        mismatches.add(new FailingLine(l.id(), rowNoOfLine.get(l.id()), l.productId(), null));
                    }
                }
            }
            if (clash) {
                continue;
            }
            stock.setBatchSupply(tenantId, batch.id(), landed.get(latest.id()), row.vendorId(), me);
            group.forEach(l -> batchOfLine.put(l.id(), batch.id()));
        }
        if (!mismatches.isEmpty()) {
            throw PharmacyProblemException.batchMrpMismatch(mismatches);
        }

        // step 7 and 8: the stock, all or nothing
        Map<UUID, LineCtx> ctxOfLine = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ctxOfLine.put(lines.get(i).id(), checked.lines().get(i));
        }
        List<StockPosting.Line> moves = new ArrayList<>();
        for (Line l : lines) {
            boolean x = isScheduleX(ctxOfLine.get(l.id()).product());
            moves.add(new StockPosting.Line(storeId, l.productId(), batchOfLine.get(l.id()), StockState.AVAILABLE,
                    ReceiptMath.issueUnits(l.qty(), l.freeQty(), conversionOf.get(l.productId())), "GRN", null,
                    UUID.randomUUID(), null, new StockPosting.Doc(DOC_TYPE, grnId, docNo, l.id()), rowNoOfLine.get(l.id()),
                    x ? signer : null));
        }
        posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        for (Line l : lines) {
            boolean x = isScheduleX(ctxOfLine.get(l.id()).product());
            receipts.setLinePosting(tenantId, l.id(), batchOfLine.get(l.id()), landed.get(l.id()), x ? signer : null, me);
        }

        // the purchase order: received quantities, lines closed short, the status
        if (lockedPo != null) {
            movePurchaseOrder(tenantId, me, lockedPo, lines, shortNow);
        }

        // the vendor payable (invoice total as typed; due = invoice date + credit days)
        payables.insertWithInvoiceEntry(tenantId, store.unitId(), UUID.randomUUID(), row.vendorId(), grnId, row.invoiceNo(),
                row.invoiceDate(), row.invoiceTotalPaise(), ReceiptMath.dueDate(row.invoiceDate(), row.creditDays()), me);

        // the receipt itself: number, time and poster in one statement
        if (!receipts.markPosted(tenantId, grnId, row.version(), seq, docNo, me)) {
            throw new IllegalStateException("A goods receipt changed under its lock");
        }
        int prepared = prepareDebitNote(tenantId, store, row, lines, ctxOfLine, checked.vendor(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, grnId, "POSTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = values(storeId, row.vendorId(), lines.size(), checked.computedTotal(), "POSTED");
        after.put("number", docNo);
        after.put("invoiceTotalPaise", row.invoiceTotalPaise());
        after.put("debitNoteLineCount", prepared);
        auditLog.record("PHARMACY_GRN_POSTED", ENTITY_TYPE, grnId, Map.of("status", row.status()), after);
        log.info("Goods receipt posted goodsReceiptId={} lines={}", grnId, lines.size());
        return view(caller, receipts.find(tenantId, storeId, grnId).orElseThrow(), "POSTED");
    }

    /**
     * m-9: the short quantity of each receipt line against the locked purchase order lines, by receipt line id. A line that is
     * short now and has no chosen action (it was not short when the person chose) is refused 400 like every create rule, so
     * nothing is closed or left open by guesswork.
     */
    Map<UUID, Integer> shortQuantitiesNow(List<Line> lines, List<PurchaseOrderRepository.Line> locked) {
        Map<UUID, PurchaseOrderRepository.Line> byId = new HashMap<>();
        locked.forEach(l -> byId.put(l.id(), l));
        Map<UUID, Integer> now = new HashMap<>();
        List<FieldMessage> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            PurchaseOrderRepository.Line poLine = l.purchaseOrderLineId() == null ? null : byId.get(l.purchaseOrderLineId());
            if (poLine == null) {
                continue;
            }
            int remaining = poLine.closedShort() ? 0 : Math.max(0, poLine.qty() - poLine.receivedQty());
            int fresh = ReceiptMath.shortQty(remaining, l.qty(), l.billedQty());
            now.put(l.id(), fresh);
            if (fresh > 0 && l.shortAction() == null) {
                errors.add(new FieldMessage("lines[" + i + "].shortAction", "Choose balance to come or close short"));
            }
        }
        throwIfAny(errors);
        return now;
    }

    /** Adds the received quantities, closes the lines the person closed short and works out the order's status. */
    private void movePurchaseOrder(UUID tenantId, UUID me, PurchaseOrderRepository.Row po, List<Line> lines,
                                   Map<UUID, Integer> shortNow) {
        for (Line l : lines) {
            if (l.purchaseOrderLineId() == null) {
                continue;
            }
            if (l.qty() > 0) {
                orders.addReceived(tenantId, l.purchaseOrderLineId(), l.qty(), me);
            }
            if ("CLOSE_SHORT".equals(l.shortAction()) && shortNow.getOrDefault(l.id(), 0) > 0) {
                orders.closeLineShort(tenantId, l.purchaseOrderLineId(), me);
            }
        }
        List<PurchaseOrderRepository.Line> now = orders.lines(tenantId, po.id());
        boolean done = now.stream().allMatch(l -> l.closedShort() || l.receivedQty() >= l.qty());
        boolean anyShort = now.stream().anyMatch(l -> l.closedShort() && l.receivedQty() < l.qty());
        String current = po.status();
        if (!done) {
            if (!"PARTLY_RECEIVED".equals(current)) {
                orders.setStatusLocked(tenantId, po.id(), current, "PARTLY_RECEIVED", me);
            }
        } else if (anyShort) {
            if (!"PARTLY_RECEIVED".equals(current)) {
                // the guard allows CLOSED_SHORT only from PARTLY_RECEIVED
                orders.setStatusLocked(tenantId, po.id(), current, "PARTLY_RECEIVED", me);
            }
            orders.setStatusLocked(tenantId, po.id(), "PARTLY_RECEIVED", "CLOSED_SHORT", me);
        } else {
            orders.setStatusLocked(tenantId, po.id(), current, "RECEIVED", me);
        }
    }

    /**
     * The draft debit note of the short supply (billed above received, OQ-79-9a) and of ticked rate differences (OQ-79-9c).
     * Returns the number of lines prepared. A rate difference is carried as a SHORT_SUPPLY line on a receipt line that is not
     * short (the debit note has no other line type that needs no batch); a line that is short and ticked carries the short only.
     */
    private int prepareDebitNote(UUID tenantId, Store store, Row row, List<Line> lines, Map<UUID, LineCtx> ctxOfLine,
                                 Vendor vendor, UUID me) {
        BigDecimal credit = vendor.returnCreditPercent() == null ? BigDecimal.valueOf(100) : vendor.returnCreditPercent();
        List<DebitNoteRepository.NewLine> prepared = new ArrayList<>();
        long creditTotal = 0;
        long gstTotal = 0;
        for (Line l : lines) {
            ProductRepository.Row product = ctxOfLine.get(l.id()).product();
            int conversion = product.conversion();
            int shortUnits = ReceiptMath.shortBilled(l.billedQty(), l.qty());
            long qty;
            BigDecimal rate;
            BigDecimal percent;
            if (shortUnits > 0) {
                qty = (long) shortUnits * conversion;
                rate = l.netRate().divide(BigDecimal.valueOf(conversion), 4, RoundingMode.HALF_UP);
                percent = credit;
            } else if (l.claimRateDifference() && l.ratePercent() != null && l.ratePercent().signum() > 0
                    && l.referenceRate() != null) {
                qty = (long) l.billedQty() * conversion;
                rate = l.netRate().subtract(l.referenceRate()).divide(BigDecimal.valueOf(conversion), 4, RoundingMode.HALF_UP);
                percent = BigDecimal.valueOf(100);
            } else {
                continue;
            }
            if (qty < 1 || qty > MAX_QTY || rate.signum() <= 0) {
                log.warn("A debit note line was not prepared because its quantity or rate is out of range goodsReceiptLineId={}",
                        l.id());
                continue;
            }
            // m-4: a short supply is credited in purchase units from the invoice rate; the per-unit rate below is for display
            long creditPaise = shortUnits > 0
                    ? ReceiptMath.creditPaise(shortUnits, l.netRate(), percent)
                    : ReceiptMath.creditPaise((int) qty, rate, percent);
            long gstReversal = PurchaseMath.gstPaise(creditPaise, l.gstRate());
            prepared.add(new DebitNoteRepository.NewLine("SHORT_SUPPLY", l.productId(), prepared.size() + 1, null, null, l.id(),
                    (int) qty, rate, percent, false, creditPaise, l.gstRate(), gstReversal));
            creditTotal += creditPaise;
            gstTotal += gstReversal;
        }
        if (prepared.isEmpty()) {
            return 0;
        }
        UUID dnId = UUID.randomUUID();
        debitNotes.insert(tenantId, store.unitId(), dnId, store.id(), row.vendorId(), row.id(), null, null, me);
        for (DebitNoteRepository.NewLine line : prepared) {
            debitNotes.insertLine(tenantId, store.unitId(), dnId, line, me);
        }
        if (!debitNotes.updateDraft(tenantId, dnId, 0L, null, null, creditTotal, gstTotal, me)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DebitNoteService.DOC_TYPE, dnId, "CREATED", store.id(), me, null, null, null,
                null, null);
        auditLog.record("PHARMACY_DEBIT_NOTE_CREATED", DebitNoteService.ENTITY_TYPE, dnId, null,
                Map.of("storeId", store.id().toString(), "vendorId", row.vendorId().toString(), "lineCount", prepared.size(),
                        "creditTotalPaise", creditTotal, "status", "DRAFT", "preparedFromGrnId", row.id().toString()));
        return prepared.size();
    }

    // ---- the rate-rise rule ----

    /** The levels of the GRN_RATE_RISE rule that apply to the receipt's rising lines; null when no line rose or no level applies. */
    private ApprovalEngine.Plan ratePlan(UUID tenantId, UUID storeId, UUID grnId, List<Line> lines, Checked checked) {
        if (lines.stream().noneMatch(l -> l.ratePercent() != null && l.ratePercent().signum() > 0)) {
            return null;
        }
        return engine.plan(tenantId, ratePlanSubject(storeId, grnId, lines, checked));
    }

    private ApprovalEngine.Subject ratePlanSubject(UUID storeId, UUID grnId, List<Line> lines, Checked checked) {
        BigDecimal maxRise = lines.stream().map(Line::ratePercent).filter(p -> p != null && p.signum() > 0)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        long riseValue = lines.stream().filter(l -> l.ratePercent() != null && l.ratePercent().signum() > 0)
                .mapToLong(Line::taxablePaise).sum();
        long maxLine = lines.stream().mapToLong(l -> l.taxablePaise() + l.gstPaise()).max().orElse(0L);
        boolean x = checked.lines().stream().anyMatch(c -> isScheduleX(c.product()));
        return new ApprovalEngine.Subject(TransactionType.GRN_RATE_RISE, DOC_TYPE, grnId, null, storeId, riseValue, x, maxLine,
                false, maxRise);
    }

    // ---- approval handler (79-2 engine, type GOODS_RECEIPT) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = receipts.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary("Goods receipt", "RATE_INCREASE", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        List<Line> rising = receipts.lines(tenantId, row.id()).stream()
                .filter(l -> l.ratePercent() != null && l.ratePercent().signum() > 0)
                .sorted(Comparator.comparing(Line::ratePercent).reversed()).toList();
        String what = "GRN";
        if (!rising.isEmpty()) {
            Line top = rising.get(0);
            String name = products.find(tenantId, top.productId()).map(f -> f.row().name()).orElse("");
            what = name + " · ₹ " + PurchaseMath.rateText(top.referenceRate()) + " → ₹ " + PurchaseMath.rateText(top.netRate())
                    + " (" + ReceiptMath.ratePercentText(top.ratePercent()) + ")"
                    + (rising.size() > 1 ? " and " + (rising.size() - 1) + " more" : "");
        }
        String vendor = vendors.find(tenantId, row.vendorId()).map(Vendor::name).orElse("");
        return new Summary("GRN · " + vendor + " · " + what, "RATE_INCREASE", row.storeId(), List.of("REVIEW", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.PURCHASE);
    }

    /** The final approval makes the receipt APPROVED; a Central Store user then posts it. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = receipts.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(GoodsReceiptService::notFound);
        if (!GoodsReceiptStatus.WAITING_APPROVAL.name().equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        receipts.setStatusLocked(tenantId, row.id(), GoodsReceiptStatus.WAITING_APPROVAL.name(),
                GoodsReceiptStatus.APPROVED.name(), approver);
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "DECIDED", row.storeId(), approver, decision.actingAs(), null,
                null, null, null);
        auditLog.record("PHARMACY_GRN_APPROVED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "APPROVED"));
    }

    /** A rejection returns the receipt to DRAFT with the reason, so the rate can be corrected. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = receipts.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(GoodsReceiptService::notFound);
        if (GoodsReceiptStatus.WAITING_APPROVAL.name().equals(row.status())) {
            UUID approver = decision.approver().actorId();
            receipts.setStatusLocked(tenantId, row.id(), GoodsReceiptStatus.WAITING_APPROVAL.name(),
                    GoodsReceiptStatus.DRAFT.name(), approver);
            events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "DECIDED", row.storeId(), approver, decision.actingAs(),
                    null, null, null, decision.input() == null ? null : decision.input().reasonKey());
            auditLog.record("PHARMACY_GRN_REJECTED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                    Map.of("status", "DRAFT"));
        }
    }

    // ---- checks ----

    /** One checked line: what is stored, the product it names and the purchase order line it receives against. */
    record LineCtx(NewLine line, ProductRepository.Row product, PurchaseOrderRepository.Line poLine) {
    }

    /** One checked receipt: the vendor, the typed header and the lines with their computations. */
    record Checked(Vendor vendor, String invoiceNo, LocalDate invoiceDate, int creditDays, long invoiceTotalPaise,
                   List<LineCtx> lines, long computedTotal) {
    }

    /**
     * All the field errors together (rule 21). The vendor of a receipt against a purchase order is the order's; a direct
     * receipt names an ACTIVE vendor. Lines follow the rules of GoodsReceiptLineInput; a PTR above the MRP is a warning (a
     * check chip), never an error.
     */
    private Checked check(UUID tenantId, UUID storeId, PurchaseOrderRepository.Row po, UUID vendorIdIn, String invoiceNoIn,
                          LocalDate invoiceDate, BigDecimal creditDaysIn, BigDecimal totalIn,
                          List<PharmacyInputs.GrnLineInput> lines, List<FieldMessage> errors) {
        LocalDate today = time.today();
        UUID vendorId = po != null ? po.vendorId() : vendorIdIn;
        Vendor vendor = vendorId == null ? null : vendors.find(tenantId, vendorId).orElse(null);
        if (vendor == null || (po == null && !vendor.active())) {
            errors.add(new FieldMessage("vendorId", "Choose an active vendor"));
            vendor = null;
        }
        String invoiceNo = invoiceNoIn == null ? "" : invoiceNoIn.trim();
        if (invoiceNo.isEmpty() || invoiceNo.length() > 30) {
            errors.add(new FieldMessage("invoiceNo", "Enter 1 to 30 characters"));
        }
        if (invoiceDate == null || invoiceDate.isAfter(today)) {
            errors.add(new FieldMessage("invoiceDate", "Enter an invoice date up to today"));
        }
        Integer creditDays = PharmacyParams.wholeNumber(creditDaysIn, 0, 365);
        if (creditDays == null) {
            errors.add(new FieldMessage("creditDays", "Enter 0 to 365 days"));
        }
        Long total = wholePaise(totalIn);
        if (total == null) {
            errors.add(new FieldMessage("invoiceTotalPaise", "Enter the invoice total, 0 to ₹ 9,99,99,999"));
        }
        List<PharmacyInputs.GrnLineInput> given = lines == null ? List.of() : lines;
        if (given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", "Add up to 200 items"));
        }
        Map<UUID, PurchaseOrderRepository.Line> poLines = new HashMap<>();
        if (po != null) {
            orders.lines(tenantId, po.id()).forEach(l -> poLines.put(l.id(), l));
        }
        List<LineCtx> built = new ArrayList<>();
        long computed = 0;
        for (int i = 0; i < given.size() && given.size() <= MAX_LINES; i++) {
            PharmacyInputs.GrnLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            LineCtx ctx = checkLine(tenantId, vendorId, po, poLines, line, at, today, i + 1, errors);
            if (ctx != null) {
                built.add(ctx);
                computed += ctx.line().taxablePaise() + ctx.line().gstPaise();
            }
        }
        return new Checked(vendor, invoiceNo, invoiceDate, creditDays == null ? 0 : creditDays, total == null ? 0L : total, built,
                computed);
    }

    private LineCtx checkLine(UUID tenantId, UUID vendorId, PurchaseOrderRepository.Row po,
                              Map<UUID, PurchaseOrderRepository.Line> poLines, PharmacyInputs.GrnLineInput line, String at,
                              LocalDate today, int sort, List<FieldMessage> errors) {
        int before = errors.size();
        ProductRepository.Full full = line.productId() == null ? null : products.find(tenantId, line.productId()).orElse(null);
        if (full == null || !full.active() || full.row().conversion() < 1 || blank(full.row().purchaseUnit())
                || blank(full.row().issueUnit())) {
            errors.add(new FieldMessage(at + ".productId", "Choose an active product with a purchase unit"));
        }
        String batchNo = line.batchNo() == null ? "" : line.batchNo().trim();
        if (batchNo.isEmpty() || batchNo.length() > 20) {
            errors.add(new FieldMessage(at + ".batchNo", "Enter 1 to 20 characters"));
        }
        LocalDate expiry = ReceiptMath.lastDayOf(line.expiryMonth());
        if (!ReceiptMath.notInThePast(expiry, today)) {
            errors.add(new FieldMessage(at + ".expiryMonth", EXPIRY_MESSAGE));
        }
        Integer qty = PharmacyParams.wholeNumber(line.qty(), 0, MAX_QTY);
        Integer billed = PharmacyParams.wholeNumber(line.billedQty(), 0, MAX_QTY);
        Integer free = line.freeQty() == null ? Integer.valueOf(0) : PharmacyParams.wholeNumber(line.freeQty(), 0, MAX_QTY);
        if (qty == null) {
            errors.add(new FieldMessage(at + ".qty", "Enter a whole number from 0 to 999999"));
        }
        if (billed == null) {
            errors.add(new FieldMessage(at + ".billedQty", "Enter a whole number from 0 to 999999"));
        }
        if (free == null) {
            errors.add(new FieldMessage(at + ".freeQty", "Enter a whole number from 0 to 999999"));
        }
        if (qty != null && free != null && qty + free == 0) {
            errors.add(new FieldMessage(at + ".qty", "Enter a quantity received or a free quantity"));
        }
        if (!PurchaseMath.validRate(line.ptr())) {
            errors.add(new FieldMessage(at + ".ptr", "Enter a rate above 0, up to 4 decimals"));
        }
        if (!PurchaseMath.validRate(line.mrp())) {
            errors.add(new FieldMessage(at + ".mrp", "Enter an MRP above 0, up to 4 decimals"));
        }
        BigDecimal discount = line.discountPercent();
        if (!ReceiptMath.validPercent(discount)) {
            errors.add(new FieldMessage(at + ".discountPercent", "Enter 0 to 100, up to 2 decimals"));
        }
        String shortAction = line.shortAction();
        if (shortAction != null && !"BALANCE_TO_COME".equals(shortAction) && !"CLOSE_SHORT".equals(shortAction)) {
            errors.add(new FieldMessage(at + ".shortAction", "Choose balance to come or close short"));
        }
        PurchaseOrderRepository.Line poLine = null;
        if (line.purchaseOrderLineId() != null) {
            poLine = po == null ? null : poLines.get(line.purchaseOrderLineId());
            if (poLine == null || (line.productId() != null && !poLine.productId().equals(line.productId()))) {
                errors.add(new FieldMessage(at + ".purchaseOrderLineId", "Choose an item of this purchase order"));
            }
        }
        if (full != null && qty != null && free != null && (long) (qty + free) * full.row().conversion() > MAX_ISSUE_UNITS) {
            errors.add(new FieldMessage(at + ".qty", "This quantity is too large"));
        }
        if (errors.size() > before) {
            return null;
        }
        ProductRepository.Row product = full.row();
        BigDecimal gstRate = product.gstRate() == null ? BigDecimal.ZERO : product.gstRate();
        BigDecimal net = ReceiptMath.netRate(line.ptr(), discount);
        BigDecimal reference = null;
        String basis = null;
        if (poLine != null) {
            reference = poLine.rate();
            basis = "PO";
        } else if (vendorId != null) {
            reference = orders.lastPostedRate(tenantId, vendorId, product.productId()).orElse(null);
            basis = reference == null ? null : "LAST";
        }
        BigDecimal ratePercent = ReceiptMath.ratePercent(net, reference);
        Integer shortQty = null;
        if (poLine != null) {
            int remaining = poLine.closedShort() ? 0 : Math.max(0, poLine.qty() - poLine.receivedQty());
            shortQty = ReceiptMath.shortQty(remaining, qty, billed);
        }
        String action = shortQty != null && shortQty > 0 ? shortAction : null;
        long taxable = ReceiptMath.taxablePaise(billed, line.ptr(), discount);
        long gst = PurchaseMath.gstPaise(taxable, gstRate);
        NewLine stored = new NewLine(line.purchaseOrderLineId(), product.productId(), sort, batchNo, expiry, qty, billed, free,
                line.ptr(), line.mrp(), discount, gstRate, taxable, gst, net, reference, basis, ratePercent, shortQty, action,
                Boolean.TRUE.equals(line.claimRateDifference()));
        return new LineCtx(stored, product, poLine);
    }

    private static List<PharmacyInputs.GrnLineInput> asInputs(List<Line> stored) {
        List<PharmacyInputs.GrnLineInput> inputs = new ArrayList<>();
        for (Line l : stored) {
            inputs.add(new PharmacyInputs.GrnLineInput(l.purchaseOrderLineId(), l.productId(), l.batchNo(),
                    YearMonth.from(l.expiryDate()).toString(), BigDecimal.valueOf(l.qty()), BigDecimal.valueOf(l.billedQty()),
                    BigDecimal.valueOf(l.freeQty()), l.ptr(), l.mrp(), l.discountPercent(), l.claimRateDifference(),
                    l.shortAction()));
        }
        return inputs;
    }

    private static boolean openForReceiving(PurchaseOrderRepository.Row po) {
        return "SENT".equals(po.status()) || "PARTLY_RECEIVED".equals(po.status());
    }

    static boolean isScheduleX(ProductRepository.Row product) {
        return product != null && "X".equals(product.schedule());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Long wholePaise(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.compareTo(BigDecimal.valueOf(MAX_TOTAL_PAISE)) > 0) {
            return null;
        }
        return value.longValueExact();
    }

    private static PharmacyProblemException invalidTotal() {
        return PharmacyProblemException.invoiceTotalMismatch();
    }

    private static Long version(BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
            return null;
        }
        return whole.longValue();
    }

    private void requireVersion(PharmacyCaller caller, Row row, Long version) {
        if (version == null || row.version() != version) {
            throw PharmacyProblemException.changed(view(caller, row, null));
        }
    }

    private static void throwIfAny(List<FieldMessage> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private Store centralStore(PharmacyCaller caller, UUID storeId) {
        Store store = guard.require(caller, storeId, StoreCapability.PURCHASE);
        if (!store.type().isCentral()) {
            throw PharmacyProblemException.docState(CENTRAL_ONLY);
        }
        return store;
    }

    private static String outcomeOf(String status) {
        if (GoodsReceiptStatus.POSTED.name().equals(status)) {
            return "POSTED";
        }
        return GoodsReceiptStatus.WAITING_APPROVAL.name().equals(status) ? "SENT_FOR_APPROVAL" : null;
    }

    private boolean isCurrentApprover(PharmacyCaller caller, Row row) {
        if (row.approvalRequestId() == null) {
            return false;
        }
        ApprovalRepository.Request request = approvals.find(caller.tenantId(), row.approvalRequestId()).orElse(null);
        if (request == null || !request.pending()) {
            return false;
        }
        return engine.approvers(caller.tenantId(), request, approvals.levels(caller.tenantId(), request.id()),
                approvals.decisions(caller.tenantId(), request.id())).canDecide(caller.actorId());
    }

    private String render(UUID tenantId, long seq, LocalDate today) {
        DocumentSeriesRepository.Row saved = series.find(tenantId, DocType.GRN).orElse(null);
        if (saved == null) {
            return DocumentNumbers.render(DocType.GRN.defaultPrefix(), DocType.GRN.defaultFinancialYearPart(),
                    DocType.GRN.defaultSequenceLength(), seq, today);
        }
        return DocumentNumbers.render(saved.prefix(), saved.financialYearPart(), saved.sequenceLength(), seq, today);
    }

    private static Map<String, Object> values(UUID storeId, UUID vendorId, int lines, long computedTotalPaise, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("vendorId", vendorId.toString());
        values.put("lineCount", lines);
        values.put("computedTotalPaise", computedTotalPaise);
        values.put("status", status);
        return values;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }

    // ---- views ----

    private PurchaseViews.VendorRef vendorRef(Vendor v, LocalDate today) {
        return new PurchaseViews.VendorRef(v.id(), v.name(), v.gstin(), v.blocked(today), false);
    }

    private static StockViews.ProductRef productRef(ProductRepository.Row p) {
        return new StockViews.ProductRef(p.productId(), p.code(), p.name(), p.strength(), p.form(), p.schedule(),
                p.purchaseUnit(), p.issueUnit(), p.conversion(), PurchaseMath.percentText(p.gstRate()));
    }

    PurchaseViews.GoodsReceipt view(PharmacyCaller caller, Row row, String outcome) {
        UUID tenantId = caller.tenantId();
        LocalDate today = time.today();
        Vendor vendor = vendors.find(tenantId, row.vendorId()).orElseThrow();
        List<Line> lines = receipts.lines(tenantId, row.id());
        PurchaseOrderRepository.Row po = row.purchaseOrderId() == null ? null
                : orders.find(tenantId, row.storeId(), row.purchaseOrderId()).orElse(null);
        Map<UUID, PurchaseOrderRepository.Line> poLines = new HashMap<>();
        if (po != null) {
            orders.lines(tenantId, po.id()).forEach(l -> poLines.put(l.id(), l));
        }
        boolean posted = GoodsReceiptStatus.POSTED.name().equals(row.status());
        List<UUID> people = new ArrayList<>();
        lines.forEach(l -> people.add(l.secondSignerMembershipId()));
        DocumentViews.Loaded loaded = views.load(tenantId, DOC_TYPE, row.id(), people);

        List<PurchaseViews.GrnLine> lineViews = new ArrayList<>();
        List<PurchaseViews.ThreeWayRow> threeWay = new ArrayList<>();
        long computed = 0;
        for (Line l : lines) {
            ProductRepository.Row p = products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElseThrow();
            computed += l.taxablePaise() + l.gstPaise();
            BigDecimal landed = l.landedRate() != null ? l.landedRate()
                    : ReceiptMath.landedRate(l.billedQty(), l.ptr(), l.discountPercent(), l.qty(), l.freeQty(), p.conversion());
            List<String> checks = new ArrayList<>();
            if (l.ratePercent() != null && l.ratePercent().signum() > 0) {
                checks.add("RATE_UP");
            }
            if (l.shortQty() != null && l.shortQty() > 0) {
                checks.add("SHORT");
            }
            if (!posted) {
                BigDecimal last = stock.latestBatchMrp(tenantId, l.productId()).orElse(null);
                if (last != null && last.compareTo(l.mrp()) != 0) {
                    checks.add("MRP_CHANGED");
                }
            }
            if (ReceiptMath.ptrAboveMrp(l.ptr(), p.conversion(), l.mrp())) {
                checks.add("PTR_ABOVE_MRP");
            }
            if (checks.isEmpty()) {
                checks.add("OK");
            }
            lineViews.add(new PurchaseViews.GrnLine(l.id(), l.purchaseOrderLineId(), productRef(p), l.batchNo(), l.expiryDate(),
                    l.qty(), l.billedQty(), l.freeQty(), PurchaseMath.rateText(l.ptr()), PurchaseMath.rateText(l.mrp()),
                    PurchaseMath.percentText(l.discountPercent()), PurchaseMath.percentText(l.gstRate()), l.taxablePaise(),
                    l.gstPaise(), PurchaseMath.rateText(landed), StockMath.marginPercent(l.mrp(), landed), checks,
                    ReceiptMath.ratePercentText(l.ratePercent()), l.rateBasis(), l.shortQty(), l.shortAction(),
                    l.claimRateDifference(), isScheduleX(p), loaded.person(l.secondSignerMembershipId())));
            PurchaseOrderRepository.Line poLine = l.purchaseOrderLineId() == null ? null : poLines.get(l.purchaseOrderLineId());
            boolean differs = l.billedQty() != l.qty() || (poLine != null && (poLine.qty() != l.billedQty()
                    || poLine.rate().compareTo(l.netRate()) != 0));
            if (differs) {
                threeWay.add(new PurchaseViews.ThreeWayRow(l.id(), p.name(), poLine == null ? null : poLine.qty(), l.billedQty(),
                        l.qty(), poLine == null ? null : PurchaseMath.rateText(poLine.rate()),
                        PurchaseMath.rateText(l.netRate())));
            }
        }
        VendorPayableRepository.Row payableRow = posted ? payables.findByReceipt(tenantId, row.id()).orElse(null) : null;
        PurchaseViews.VendorPayable payable = payableRow == null ? null : new PurchaseViews.VendorPayable(payableRow.id(),
                vendorRef(vendor, today), payableRow.invoiceNo(), payableRow.invoiceDate(), payableRow.amountPaise(),
                payableRow.creditedPaise(), payableRow.openPaise(), payableRow.dueDate(), payableRow.status());
        DebitNoteRepository.Row draft = debitNotes.findPreparedFromReceipt(tenantId, row.id()).orElse(null);
        PurchaseViews.DebitNoteDraftRef draftRef = draft == null ? null : new PurchaseViews.DebitNoteDraftRef(draft.id(),
                debitNotes.lines(tenantId, draft.id()).size(), draft.creditTotalPaise());
        boolean needsApproval = false;
        if (GoodsReceiptStatus.DRAFT.name().equals(row.status()) && lines.stream()
                .anyMatch(l -> l.ratePercent() != null && l.ratePercent().signum() > 0)) {
            boolean x = lines.stream().anyMatch(l -> isScheduleX(products.find(tenantId, l.productId())
                    .map(ProductRepository.Full::row).orElse(null)));
            needsApproval = engine.plan(tenantId, new ApprovalEngine.Subject(TransactionType.GRN_RATE_RISE, DOC_TYPE, row.id(),
                    null, row.storeId(), 0L, x, 0L, false,
                    lines.stream().map(Line::ratePercent).filter(r -> r != null && r.signum() > 0)
                            .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO))) != null;
        }
        List<String> warnings = po != null && !posted && vendor.blocked(today) ? List.of("VENDOR_LICENCE_EXPIRED") : List.of();
        PurchaseViews.GrnPoRef poRef = po == null ? null : new PurchaseViews.GrnPoRef(po.id(), po.docNo());
        return new PurchaseViews.GoodsReceipt(row.id(), row.docNo(), row.status(), outcome, step(row, lines), poRef,
                vendorRef(vendor, today), row.invoiceNo(), row.invoiceDate(), row.creditDays(), row.invoiceTotalPaise(), computed,
                row.invoiceTotalPaise() - computed, warnings, lineViews, threeWay, engine.summary(caller, DOC_TYPE, row.id()),
                payable, draftRef, views.history(tenantId, loaded), needsApproval, row.version());
    }

    private static String step(Row row, List<Line> lines) {
        switch (GoodsReceiptStatus.valueOf(row.status())) {
            case POSTED:
                return "PAYABLE";
            case WAITING_APPROVAL:
            case APPROVED:
                return "POST";
            case DRAFT:
                return lines.isEmpty() ? "ENTER_INVOICE" : "VERIFY";
            default:
                return "ENTER_INVOICE";
        }
    }

    /** Used by the debit note service for the receipt number of a short supply line. */
    Optional<String> receiptNoOf(UUID tenantId, UUID lineId) {
        return receipts.receiptNoOfLine(tenantId, lineId);
    }
}
