package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DebitNoteRepository.Line;
import com.sahaj.pharmacy.application.DebitNoteRepository.NewLine;
import com.sahaj.pharmacy.application.DebitNoteRepository.Row;
import com.sahaj.pharmacy.application.DebitNoteRepository.StockCandidate;
import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalConditions;
import com.sahaj.pharmacy.domain.DebitNoteStatus;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.PrintDocType;
import com.sahaj.pharmacy.domain.PurchaseMath;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.ReceiptMath;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-8: vendor returns and debit notes of the Central Store (docs/api/pharmacy.yaml 0.3.2 and 0.3.3, ADR-029 P5, P6, P7,
 * P10, design note sections 13.3 to 13.5). A debit note is a DRAFT of STOCK_RETURN lines (units of the vendor's batches in
 * Quarantine or near expiry) and SHORT_SUPPLY lines (billed but not received; no stock). Submit takes the DN number; the
 * VENDOR_RETURN rule decides: no level moves the units to PENDING_RETURN at once (ledger {@code VENDOR_RETURN_HOLD}, a system
 * move that is not on the person-driven list), otherwise the approval does it ({@link ApprovalSubjectHandler}). Hand-over takes
 * the units out of stock ({@code VENDOR_RETURN}, person-driven: a Schedule X line needs a pharmacist and a second signer); the
 * credit note reduces the vendor payables, the receipt of the note first and then the oldest due; cancel puts the held units back
 * ({@code VENDOR_RETURN_RELEASE}).
 *
 * <p>Lock order, as in {@link GoodsReceiptService}: client key, store (ACTIVE again, DEC-077 L9), approval request, header,
 * number, stock balances, vendor payables in {@code due_date, id} order, then the inserts. The approval handler takes no store
 * lock (the request is locked first there, so the store would be out of order). Lines are written before the header leaves
 * DRAFT. The actor of every writer is {@link PharmacyCaller#actorId()}. Logs and audit rows hold ids, counts and totals; the
 * vendor representative's name is in the audit row only, never in a log.
 */
@Service
public class DebitNoteService implements ApprovalSubjectHandler {

    static final String DOC_TYPE = "DEBIT_NOTE";
    static final String ENTITY_TYPE = "PHARMACY_DEBIT_NOTE";
    static final String OP_CREATE = "pharmacyCreateDebitNote";
    static final String OP_SUBMIT = "pharmacySubmitDebitNote";
    static final String OP_HAND_OVER = "pharmacyHandOverDebitNote";
    static final String OP_CREDIT = "pharmacyRecordCreditNote";
    static final String CENTRAL_ONLY = "Vendor returns are made at the Central Store.";
    static final String NOT_EDITABLE = "Only a draft debit note can be edited.";
    private static final Logger log = LoggerFactory.getLogger(DebitNoteService.class);
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;
    private static final long MAX_CREDIT_PAISE = 99_999_999_900L;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final VendorRepository vendors;
    private final GoodsReceiptRepository receipts;
    private final DebitNoteRepository notes;
    private final VendorPayableRepository payables;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final ActiveReasonService reasons;
    private final DocumentViews views;
    private final PrintTemplateRepository templates;
    private final UnitRepository unitRows;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public DebitNoteService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                            VendorRepository vendors, GoodsReceiptRepository receipts, DebitNoteRepository notes,
                            VendorPayableRepository payables, ApprovalEngine engine, ApprovalRepository approvals,
                            DocumentSeriesRepository series, DocumentNumberPort numbers, ClientRequestRepository clients,
                            DocumentEventRepository events, StockRepository stock, StockPosting posting,
                            ScheduleXSignature signature, ActiveReasonService reasons, DocumentViews views,
                            PrintTemplateRepository templates, UnitRepository unitRows, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.vendors = vendors;
        this.receipts = receipts;
        this.notes = notes;
        this.payables = payables;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.stock = stock;
        this.posting = posting;
        this.signature = signature;
        this.reasons = reasons;
        this.views = views;
        this.templates = templates;
        this.unitRows = unitRows;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- reads ----

    /**
     * The items eligible to return to a vendor. A read that writes: expired AVAILABLE stock of the Central Store is first moved
     * to QUARANTINE (rule 17), so it is offered as expired Quarantine.
     */
    @Transactional
    public PurchaseViews.VendorReturnEligible eligible(UUID tenantId, UUID userId, String rawStoreId, String rawVendorId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store store = centralStore(caller, storeId); // the store guard first (m-1)
        UUID vendorId = PharmacyParams.optionalUuid("vendorId", rawVendorId, "Choose a vendor");
        if (vendorId == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("vendorId", "Choose a vendor")));
        }
        Vendor vendor = vendors.find(tenantId, vendorId).orElseThrow(DebitNoteService::notFound);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.actorId());
        LocalDate today = time.today();
        List<PurchaseViews.EligibleRow> items = new ArrayList<>();
        for (Eligible e : eligibleRows(tenantId, storeId, vendor, null)) {
            StockViews.BatchRef batch = e.batchId() == null ? null : new StockViews.BatchRef(e.batchId(), e.batchNo(), e.expiry());
            items.add(new PurchaseViews.EligibleRow(e.source(), productRef(e.product()), batch, e.state(), e.grnLineId(),
                    e.receiptNo(), e.availableQty(), PurchaseMath.rateText(e.rate()),
                    PurchaseMath.percentText(e.defaultCredit()), e.daysToExpiry()));
        }
        PurchaseViews.ReturnPolicy policy = new PurchaseViews.ReturnPolicy(vendor.returnDaysBeforeExpiry(),
                PurchaseMath.percentText(vendor.returnCreditPercent()));
        return new PurchaseViews.VendorReturnEligible(vendorRef(vendor, today), policy, items);
    }

    @Transactional(readOnly = true)
    public PurchaseViews.DnPage list(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawVendorId,
                                     String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        centralStore(caller, storeId); // the store guard first (m-1)
        DebitNoteStatus status = PharmacyParams.optionalEnum("status", rawStatus, DebitNoteStatus.class);
        UUID vendorId = PharmacyParams.optionalUuid("vendorId", rawVendorId, "Choose a vendor");
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        if (status == DebitNoteStatus.DISCARDED) {
            return new PurchaseViews.DnPage(List.of(), 0, page, size);
        }
        DebitNoteRepository.Slice slice = notes.list(tenantId, storeId, status == null ? null : status.name(), vendorId, size,
                page * size);
        List<PurchaseViews.DnRow> items = slice.items().stream().map(r -> new PurchaseViews.DnRow(r.id(), r.docNo(),
                r.vendorName(), r.creditTotalPaise(), r.status(), r.createdAt())).toList();
        return new PurchaseViews.DnPage(items, slice.total(), page, size);
    }

    /** A current approver of the note's request reads it without the purchase capability. */
    @Transactional(readOnly = true)
    public PurchaseViews.DebitNote get(UUID tenantId, UUID userId, String rawStoreId, String rawDnId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        Row row = notes.find(tenantId, storeId, dnId).orElse(null);
        if (row != null && isCurrentApprover(caller, row)) {
            return view(caller, row);
        }
        guard.require(caller, storeId, StoreCapability.PURCHASE);
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound));
    }

    // ---- create, update, discard ----

    @Transactional
    public PharmacyViews.Outcome<PurchaseViews.DebitNote> create(UUID tenantId, UUID userId, String rawStoreId,
            PharmacyInputs.Body<PharmacyInputs.DebitNoteCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.DebitNoteCreate in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return new PharmacyViews.Outcome<>(view(caller, notes.find(tenantId, storeId, own.subjectId())
                        .orElseThrow(DebitNoteService::notFound)), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Vendor vendor = in.vendorId() == null ? null : vendors.find(tenantId, in.vendorId()).orElse(null);
        if (vendor == null) {
            if (in.vendorId() != null) {
                throw notFound();
            }
            errors.add(new FieldMessage("vendorId", "Choose a vendor"));
        }
        String note = header(tenantId, in.reasonKey(), in.note(), errors);
        Checked checked = vendor == null ? null : check(tenantId, storeId, vendor, null, in.lines(), List.of(), errors);
        throwIfAny(errors);
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body); // L15: the same key committed first, answer as its replay
        }
        notes.insert(tenantId, store.unitId(), id, storeId, vendor.id(), null, in.reasonKey(), note, me);
        for (Built b : checked.lines()) {
            notes.insertLine(tenantId, store.unitId(), id, b.line(), me);
        }
        if (!notes.updateDraft(tenantId, id, 0L, in.reasonKey(), note, checked.creditTotal(), checked.gstReversal(), me)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, null, in.reasonKey());
        auditLog.record("PHARMACY_DEBIT_NOTE_CREATED", ENTITY_TYPE, id, null, values(storeId, vendor.id(),
                checked.lines().size(), checked.creditTotal(), "DRAFT"));
        log.info("Debit note created debitNoteId={} lines={}", id, checked.lines().size());
        return new PharmacyViews.Outcome<>(view(caller, notes.find(tenantId, storeId, id).orElseThrow()), true);
    }

    @Transactional
    public PurchaseViews.DebitNote update(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                          PharmacyInputs.Body<PharmacyInputs.DebitNoteUpdate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.DebitNoteUpdate in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        Row peek = notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        Vendor vendor = vendors.find(tenantId, peek.vendorId()).orElseThrow(DebitNoteService::notFound);
        String note = header(tenantId, in.reasonKey(), in.note(), errors);
        List<Line> live = notes.lines(tenantId, dnId);
        Checked checked = check(tenantId, storeId, vendor, dnId, in.lines(), live, errors);
        throwIfAny(errors);
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        if (!DebitNoteStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState(NOT_EDITABLE);
        }
        int keep = Math.min(live.size(), checked.lines().size());
        for (int i = 0; i < keep; i++) {
            notes.updateLine(tenantId, live.get(i).id(), checked.lines().get(i).line(), me);
        }
        for (int i = keep; i < live.size(); i++) {
            notes.removeLine(tenantId, live.get(i).id(), me);
        }
        for (int i = keep; i < checked.lines().size(); i++) {
            notes.insertLine(tenantId, store.unitId(), dnId, checked.lines().get(i).line(), me);
        }
        if (!notes.updateDraft(tenantId, dnId, row.version(), in.reasonKey(), note, checked.creditTotal(),
                checked.gstReversal(), me)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "CHANGED", storeId, me, null, null, null, null, in.reasonKey());
        auditLog.record("PHARMACY_DEBIT_NOTE_CHANGED", ENTITY_TYPE, dnId, Map.of("version", row.version()),
                values(storeId, vendor.id(), checked.lines().size(), checked.creditTotal(), "DRAFT"));
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    @Transactional
    public PurchaseViews.DebitNote discard(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                           PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = centralStore(caller, storeId);
        notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        if (!DebitNoteStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState("Only a draft debit note can be discarded.");
        }
        UUID me = caller.actorId();
        notes.setStatus(tenantId, dnId, row.version(), DebitNoteStatus.DISCARDED.name(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "DISCARDED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_DEBIT_NOTE_DISCARDED", ENTITY_TYPE, dnId, Map.of("status", "DRAFT"),
                Map.of("status", "DISCARDED"));
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    // ---- submit ----

    @Transactional
    public PurchaseViews.DebitNote submit(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                          PharmacyInputs.Body<PharmacyInputs.VersionClientKeyInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.VersionClientKeyInput in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        if (replayed(tenantId, me, in.clientKey(), OP_SUBMIT, dnId)) {
            return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        throwIfAny(errors);

        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_SUBMIT, dnId, true) != null) {
            return submit(tenantId, userId, rawStoreId, rawDnId, body); // L15
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(DebitNoteService::notFound);
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        if (!DebitNoteStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState("Only a draft can be submitted.");
        }
        Vendor vendor = vendors.find(tenantId, row.vendorId()).orElseThrow(DebitNoteService::notFound);
        List<Line> lines = notes.lines(tenantId, dnId);
        if (lines.isEmpty()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", "Add 1 to 200 items")));
        }
        recheck(tenantId, storeId, vendor, dnId, lines);

        long seq = numbers.next(tenantId, DocType.DEBIT_NOTE);
        String docNo = render(tenantId, seq, time.today());
        boolean scheduleX = lines.stream().anyMatch(l -> isScheduleX(tenantId, l.productId()));
        long maxLine = lines.stream().mapToLong(Line::creditPaise).max().orElse(0L);
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.VENDOR_RETURN, DOC_TYPE, dnId, docNo,
                storeId, row.creditTotalPaise(), scheduleX, maxLine, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        String status = plan == null ? DebitNoteStatus.CREATED.name() : DebitNoteStatus.WAITING_APPROVAL.name();
        if (plan == null) {
            hold(tenantId, store.unitId(), storeId, dnId, docNo, lines, me, null);
        }
        if (!notes.submit(tenantId, dnId, row.version(), status, seq, docNo, me)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
            notes.attachRequest(tenantId, dnId, requestId, me);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "SUBMITTED", storeId, me, null, null, null, null, null);
        if (plan == null) {
            events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "ISSUED", storeId, me, null, null, null, null, null);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("number", docNo);
        after.put("status", status);
        after.put("creditTotalPaise", row.creditTotalPaise());
        after.put("lineCount", lines.size());
        auditLog.record("PHARMACY_DEBIT_NOTE_SUBMITTED", ENTITY_TYPE, dnId, Map.of("status", "DRAFT"), after);
        log.info("Debit note submitted debitNoteId={} status={}", dnId, status);
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    // ---- picked ----

    @Transactional
    public PurchaseViews.DebitNote picked(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                          PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = centralStore(caller, storeId);
        notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        if (!DebitNoteStatus.CREATED.name().equals(row.status())) {
            throw PharmacyProblemException.docState("Only a created debit note can be marked as picked and packed.");
        }
        UUID me = caller.actorId();
        notes.markPicked(tenantId, dnId, row.version(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "PICKED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_DEBIT_NOTE_PICKED", ENTITY_TYPE, dnId, Map.of("status", "CREATED"),
                Map.of("status", "PICKED"));
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    // ---- hand-over ----

    /** As {@link GoodsReceiptService#precheck}: the second signer to verify, only for a Schedule X note and a pharmacist. */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                                     PharmacyInputs.Body<PharmacyInputs.HandOver> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.HandOver in = body.read();
        centralStore(caller, storeId);
        Row peek = notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        // m-6: only a debit note that can be handed over makes a PIN attempt; the hand over answers 409 for any other state
        if (!DebitNoteStatus.CREATED.name().equals(peek.status()) && !DebitNoteStatus.PICKED.name().equals(peek.status())) {
            return null;
        }
        boolean scheduleX = notes.lines(tenantId, dnId).stream().anyMatch(l -> l.stockReturn() && isScheduleX(tenantId,
                l.productId()));
        if (!scheduleX) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    @Transactional
    public PurchaseViews.DebitNote handOver(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                            PharmacyInputs.Body<PharmacyInputs.HandOver> body, UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.HandOver in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        if (replayed(tenantId, me, in.clientKey(), OP_HAND_OVER, dnId)) {
            return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        String representative = in.representativeName() == null ? "" : in.representativeName().trim();
        if (representative.isEmpty() || representative.length() > 80) {
            errors.add(new FieldMessage("representativeName", "Enter 1 to 80 characters"));
        }
        throwIfAny(errors);

        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_HAND_OVER, dnId, true) != null) {
            return handOver(tenantId, userId, rawStoreId, rawDnId, body, verifiedSignerMembershipId); // L15
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(DebitNoteService::notFound);
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        boolean open = DebitNoteStatus.CREATED.name().equals(row.status()) || DebitNoteStatus.PICKED.name().equals(row.status());
        List<Line> lines = notes.lines(tenantId, dnId);
        List<Line> stockLines = lines.stream().filter(Line::stockReturn).toList();
        if (!open || stockLines.isEmpty()) {
            throw PharmacyProblemException.docState(open ? "This debit note has no goods to hand over."
                    : "Only a created or picked debit note can be handed over.");
        }
        boolean scheduleX = stockLines.stream().anyMatch(l -> isScheduleX(tenantId, l.productId()));
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
        List<StockPosting.Line> moves = new ArrayList<>();
        for (Line l : stockLines) {
            boolean x = isScheduleX(tenantId, l.productId());
            moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.PENDING_RETURN, -l.qty(),
                    "VENDOR_RETURN", null, UUID.randomUUID(), null, new StockPosting.Doc(DOC_TYPE, dnId, row.docNo(), l.id()),
                    null, x ? signer : null));
        }
        posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        if (!notes.markHandedOver(tenantId, dnId, row.version(), representative, me, signer)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "HANDED_OVER", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", DebitNoteStatus.HANDED_OVER.name());
        after.put("lineCount", stockLines.size());
        after.put("unitsOut", stockLines.stream().mapToInt(Line::qty).sum());
        after.put("representativeName", representative);
        auditLog.record("PHARMACY_DEBIT_NOTE_HANDED_OVER", ENTITY_TYPE, dnId, Map.of("status", row.status()), after);
        log.info("Debit note handed over debitNoteId={} lines={}", dnId, stockLines.size());
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    // ---- credit note ----

    @Transactional
    public PurchaseViews.DebitNote recordCreditNote(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                                    PharmacyInputs.Body<PharmacyInputs.CreditNote> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.CreditNote in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        if (replayed(tenantId, me, in.clientKey(), OP_CREDIT, dnId)) {
            return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        String no = in.creditNoteNo() == null ? "" : in.creditNoteNo().trim();
        if (no.isEmpty() || no.length() > 30) {
            errors.add(new FieldMessage("creditNoteNo", "Enter 1 to 30 characters"));
        }
        LocalDate today = time.today();
        if (in.creditNoteDate() == null || in.creditNoteDate().isAfter(today)) {
            errors.add(new FieldMessage("creditNoteDate", "Enter a date up to today"));
        }
        Long amount = wholePaise(in.amountPaise());
        if (amount == null || amount < 1) {
            errors.add(new FieldMessage("amountPaise", "Enter an amount above 0, up to ₹ 9,99,99,999"));
        }
        throwIfAny(errors);

        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_CREDIT, dnId, true) != null) {
            return recordCreditNote(tenantId, userId, rawStoreId, rawDnId, body); // L15
        }
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        List<Line> lines = notes.lines(tenantId, dnId);
        boolean shortOnly = !lines.isEmpty() && lines.stream().noneMatch(Line::stockReturn);
        boolean handedOver = DebitNoteStatus.HANDED_OVER.name().equals(row.status());
        boolean shortOpen = shortOnly && (DebitNoteStatus.CREATED.name().equals(row.status())
                || DebitNoteStatus.PICKED.name().equals(row.status()));
        if (!handedOver && !shortOpen) {
            throw PharmacyProblemException.docState("A credit note can be recorded after the goods are handed over.");
        }

        // lock order step 7a: the vendor's open payables in due date and id order; the note's own receipt is applied first
        List<VendorPayableRepository.Row> open = new ArrayList<>(payables.lockOpenOfVendor(tenantId, row.vendorId()));
        List<VendorPayableRepository.Row> ordered = new ArrayList<>();
        open.stream().filter(p -> p.goodsReceiptId().equals(row.preparedFromGrnId())).forEach(ordered::add);
        open.stream().filter(p -> !p.goodsReceiptId().equals(row.preparedFromGrnId())).forEach(ordered::add);
        long remaining = amount;
        for (VendorPayableRepository.Row payable : ordered) {
            long take = Math.min(payable.openPaise(), remaining);
            if (take > 0) {
                payables.applyCredit(tenantId, store.unitId(), payable, take, dnId, me);
                remaining -= take;
            }
        }
        long applied = amount - remaining;
        long difference = amount - (row.creditTotalPaise() + row.gstReversalPaise());
        notes.insertCredit(tenantId, store.unitId(), dnId, no, in.creditNoteDate(), amount, difference, applied, remaining, me);
        if (!notes.setStatus(tenantId, dnId, row.version(), DebitNoteStatus.CREDIT_NOTE_RECEIVED.name(), me)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "CREDIT_NOTE_RECEIVED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", DebitNoteStatus.CREDIT_NOTE_RECEIVED.name());
        after.put("amountPaise", amount);
        after.put("differencePaise", difference);
        after.put("appliedPaise", applied);
        after.put("unappliedCreditPaise", remaining);
        auditLog.record("PHARMACY_CREDIT_NOTE_RECORDED", ENTITY_TYPE, dnId, Map.of("status", row.status()), after);
        log.info("Credit note recorded debitNoteId={} payables={}", dnId, ordered.size());
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    // ---- cancel ----

    @Transactional
    public PurchaseViews.DebitNote cancel(UUID tenantId, UUID userId, String rawStoreId, String rawDnId,
                                          PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dnId = PharmacyParams.pathId(rawDnId);
        PharmacyInputs.VersionReasonInput in = body.read();
        centralStore(caller, storeId);
        Row peek = notes.find(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        UUID me = caller.actorId();
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!reasons.isActive(tenantId, ReasonList.APPROVAL_REJECT, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        throwIfAny(errors);

        // lock order: the store, the approval request, the header, then the balances
        Store store = stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(DebitNoteService::notFound);
        ApprovalRepository.Request request = peek.approvalRequestId() == null ? null
                : approvals.findForUpdate(tenantId, peek.approvalRequestId()).orElse(null);
        Row row = notes.findForUpdate(tenantId, storeId, dnId).orElseThrow(DebitNoteService::notFound);
        requireVersion(caller, row, version);
        boolean waiting = DebitNoteStatus.WAITING_APPROVAL.name().equals(row.status());
        boolean held = DebitNoteStatus.CREATED.name().equals(row.status()) || DebitNoteStatus.PICKED.name().equals(row.status());
        if (!waiting && !held) {
            throw PharmacyProblemException.docState("Only a debit note that is not handed over can be cancelled.");
        }
        if (waiting && request != null && request.pending()) {
            approvals.finish(tenantId, request.id(), request.version(), "WITHDRAWN", me);
        }
        if (held) {
            release(tenantId, store.unitId(), storeId, row, notes.lines(tenantId, dnId), me);
        }
        if (!notes.setStatus(tenantId, dnId, row.version(), DebitNoteStatus.CANCELLED.name(), me)) {
            throw new IllegalStateException("A debit note changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, dnId, "CANCELLED", storeId, me, null, null, null, null, in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", DebitNoteStatus.CANCELLED.name());
        after.put("reasonKey", in.reasonKey());
        auditLog.record("PHARMACY_DEBIT_NOTE_CANCELLED", ENTITY_TYPE, dnId, Map.of("status", row.status()), after);
        return view(caller, notes.find(tenantId, storeId, dnId).orElseThrow());
    }

    // ---- approval handler (79-2 engine, type DEBIT_NOTE) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = notes.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "VENDOR_RETURN", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        String vendor = vendors.find(tenantId, row.vendorId()).map(Vendor::name).orElse("");
        return new Summary(row.docNo() + " · " + vendor + " · ₹ " + ApprovalConditions.rupees(row.creditTotalPaise()),
                "VENDOR_RETURN", row.storeId(), List.of("REVIEW", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.PURCHASE);
    }

    /**
     * The final approval moves the units to PENDING_RETURN and makes the note CREATED. The raiser is the actor of the hold lines
     * and the approver is recorded as the approver; when the units are gone the refusal (409 insufficient-stock) rolls the whole
     * decision back. No store lock here: the request was locked first, so a store lock would be out of order.
     */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = notes.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(DebitNoteService::notFound);
        if (!DebitNoteStatus.WAITING_APPROVAL.name().equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        hold(tenantId, row.unitId(), row.storeId(), row.id(), row.docNo(), notes.lines(tenantId, row.id()),
                decision.request().raisedBy(), approver);
        notes.setStatusLocked(tenantId, row.id(), DebitNoteStatus.WAITING_APPROVAL.name(), DebitNoteStatus.CREATED.name(), approver);
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "DECIDED", row.storeId(), approver, decision.actingAs(), null,
                null, null, null);
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "ISSUED", row.storeId(), approver, decision.actingAs(), null,
                null, null, null);
        auditLog.record("PHARMACY_DEBIT_NOTE_APPROVED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "CREATED"));
    }

    /** A rejection returns the note as REJECTED (final); nothing was held. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = notes.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(DebitNoteService::notFound);
        if (DebitNoteStatus.WAITING_APPROVAL.name().equals(row.status())) {
            UUID approver = decision.approver().actorId();
            notes.setStatusLocked(tenantId, row.id(), DebitNoteStatus.WAITING_APPROVAL.name(), DebitNoteStatus.REJECTED.name(),
                    approver);
            events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "DECIDED", row.storeId(), approver, decision.actingAs(),
                    null, null, null, decision.input() == null ? null : decision.input().reasonKey());
            auditLog.record("PHARMACY_DEBIT_NOTE_REJECTED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                    Map.of("status", "REJECTED"));
        }
    }

    // ---- the hold and the release ----

    /**
     * Moves the STOCK_RETURN units from their source state to PENDING_RETURN at the Central Store, two ledger lines per line
     * (one movement). A unit that is gone is 409 insufficient-stock naming the debit note line; nothing is held.
     */
    private void hold(UUID tenantId, UUID unitId, UUID storeId, UUID dnId, String docNo, List<Line> lines, UUID actor,
                      UUID approver) {
        List<StockPosting.Line> moves = new ArrayList<>();
        for (Line l : lines) {
            if (!l.stockReturn()) {
                continue;
            }
            UUID movement = UUID.randomUUID();
            StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, dnId, docNo, l.id());
            moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.valueOf(l.sourceState()), -l.qty(),
                    "VENDOR_RETURN_HOLD", null, movement, null, doc, null));
            moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.PENDING_RETURN, l.qty(),
                    "VENDOR_RETURN_HOLD", null, movement, null, doc, null));
        }
        if (!moves.isEmpty()) {
            posting.post(new StockPosting.Actor(tenantId, unitId, actor, null, approver), moves);
        }
    }

    /** Puts the held units back to the state they came from, or to QUARANTINE when the batch has expired since. */
    private void release(UUID tenantId, UUID unitId, UUID storeId, Row row, List<Line> lines, UUID me) {
        LocalDate today = time.today();
        List<StockPosting.Line> moves = new ArrayList<>();
        for (Line l : lines) {
            if (!l.stockReturn()) {
                continue;
            }
            BatchRow batch = stock.findBatch(tenantId, l.batchId()).orElseThrow();
            StockState target = batch.expiryDate().isBefore(today) ? StockState.QUARANTINE : StockState.valueOf(l.sourceState());
            UUID movement = UUID.randomUUID();
            StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, row.id(), row.docNo(), l.id());
            moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.PENDING_RETURN, -l.qty(),
                    "VENDOR_RETURN_RELEASE", null, movement, null, doc, null));
            moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), target, l.qty(), "VENDOR_RETURN_RELEASE", null,
                    movement, null, doc, null));
        }
        if (!moves.isEmpty()) {
            posting.post(new StockPosting.Actor(tenantId, unitId, me, null, null), moves);
        }
    }

    // ---- what can be returned ----

    /** One row of the eligible list with what the debit note lines need. */
    record Eligible(String source, ProductRepository.Row product, UUID batchId, String batchNo, LocalDate expiry, String state,
                    UUID grnLineId, String receiptNo, int availableQty, BigDecimal rate, BigDecimal defaultCredit,
                    Integer daysToExpiry, BigDecimal purchaseRate, int conversion) {

        /** A stock row: it has no purchase-unit rate. */
        Eligible(String source, ProductRepository.Row product, UUID batchId, String batchNo, LocalDate expiry, String state,
                 UUID grnLineId, String receiptNo, int availableQty, BigDecimal rate, BigDecimal defaultCredit,
                 Integer daysToExpiry) {
            this(source, product, batchId, batchNo, expiry, state, grnLineId, receiptNo, availableQty, rate, defaultCredit,
                    daysToExpiry, null, 1);
        }
    }

    /**
     * The rows the vendor may be sent now: Quarantine stock of the vendor's batches, Available stock inside the vendor's return
     * days, and short supply of posted receipts not yet on a debit note. Units named by other notes that wait for approval
     * and quantities already claimed are left out; {@code exceptNoteId} is the note being edited.
     */
    private List<Eligible> eligibleRows(UUID tenantId, UUID storeId, Vendor vendor, UUID exceptNoteId) {
        LocalDate today = time.today();
        List<Eligible> rows = new ArrayList<>();
        for (StockCandidate c : notes.stockCandidates(tenantId, storeId, vendor.id(), today, vendor.returnDaysBeforeExpiry(),
                exceptNoteId)) {
            if (c.availableQty() < 1) {
                continue;
            }
            ProductRepository.Row product = products.find(tenantId, c.productId()).map(ProductRepository.Full::row).orElse(null);
            if (product == null) {
                continue;
            }
            boolean expired = c.expiryDate().isBefore(today);
            BigDecimal rate = c.purchaseRate() == null ? BigDecimal.ZERO : c.purchaseRate();
            if ("QUARANTINE".equals(c.state())) {
                boolean damaged = "DAMAGE_QUARANTINE".equals(c.causeTxn()) || (c.causeReason() != null
                        && c.causeReason().startsWith("damage.") && !"damage.expired".equals(c.causeReason()));
                boolean indent = "INDENT_RECEIPT_REJECTED".equals(c.causeTxn());
                String source = expired ? "QUARANTINE_EXPIRED" : damaged ? "QUARANTINE_DAMAGED"
                        : indent ? "QUARANTINE_INDENT_REJECTION" : "QUARANTINE_OTHER";
                BigDecimal credit = ReceiptMath.startingCredit(vendor.returnCreditPercent(), vendor.damagedCreditPercent(),
                        vendor.expiredCreditPercent(), damaged, expired);
                rows.add(new Eligible(source, product, c.batchId(), c.batchNo(), c.expiryDate(), "QUARANTINE", null, null,
                        c.availableQty(), rate, credit, (int) ChronoUnit.DAYS.between(today, c.expiryDate())));
            } else {
                BigDecimal credit = ReceiptMath.startingCredit(vendor.returnCreditPercent(), null, null, false, false);
                rows.add(new Eligible("NEAR_EXPIRY", product, c.batchId(), c.batchNo(), c.expiryDate(), "AVAILABLE", null, null,
                        c.availableQty(), rate, credit, (int) ChronoUnit.DAYS.between(today, c.expiryDate())));
            }
        }
        BigDecimal standard = ReceiptMath.startingCredit(vendor.returnCreditPercent(), null, null, false, false);
        for (GoodsReceiptRepository.ShortLine s : receipts.shortLinesOfVendor(tenantId, vendor.id(), exceptNoteId)) {
            if (s.unclaimedIssueUnits() < 1) {
                continue;
            }
            ProductRepository.Row product = products.find(tenantId, s.productId()).map(ProductRepository.Full::row).orElse(null);
            if (product == null) {
                continue;
            }
            BatchRow batch = s.batchId() == null ? null : stock.findBatch(tenantId, s.batchId()).orElse(null);
            BigDecimal rate = s.netRate().divide(BigDecimal.valueOf(s.conversion()), 4, RoundingMode.HALF_UP);
            rows.add(new Eligible("GRN_SHORT", product, batch == null ? null : batch.id(), batch == null ? null : batch.batchNo(),
                    batch == null ? null : batch.expiryDate(), null, s.lineId(), s.receiptNo(), s.unclaimedIssueUnits(), rate,
                    standard, null, s.netRate(), s.conversion()));
        }
        return rows;
    }

    /** At submit: every line is still offered and its quantity is still there; a stock line that is not is 409 insufficient-stock. */
    private void recheck(UUID tenantId, UUID storeId, Vendor vendor, UUID dnId, List<Line> lines) {
        List<Eligible> rows = eligibleRows(tenantId, storeId, vendor, dnId);
        Map<String, Eligible> byStock = new HashMap<>();
        Map<UUID, Eligible> byShort = new HashMap<>();
        for (Eligible e : rows) {
            if (e.grnLineId() != null) {
                byShort.put(e.grnLineId(), e);
            } else {
                byStock.put(e.batchId() + "|" + e.state(), e);
            }
        }
        List<FailingLine> failing = new ArrayList<>();
        List<FieldMessage> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.stockReturn()) {
                Eligible e = byStock.get(l.batchId() + "|" + l.sourceState());
                int available = e == null ? 0 : e.availableQty();
                if (l.qty() > available) {
                    failing.add(new FailingLine(l.id(), i + 1, l.productId(), available));
                }
            } else {
                Eligible e = byShort.get(l.goodsReceiptLineId());
                if (e == null || l.qty() > e.availableQty()) {
                    errors.add(new FieldMessage("lines[" + i + "].qty", "This short supply is already claimed"));
                }
            }
        }
        if (!failing.isEmpty()) {
            throw PharmacyProblemException.insufficientStock(failing.get(0).available(), failing);
        }
        throwIfAny(errors);
    }

    // ---- checks ----

    private record Built(NewLine line, ProductRepository.Row product) {
    }

    private record Checked(List<Built> lines, long creditTotal, long gstReversal) {
    }

    private String header(UUID tenantId, String reasonKey, String noteIn, List<FieldMessage> errors) {
        String note = noteIn == null || noteIn.isBlank() ? null : noteIn.trim();
        if (reasonKey != null) {
            if (!reasons.isActive(tenantId, ReasonList.VENDOR_RETURN, reasonKey)) {
                errors.add(new FieldMessage("reasonKey", "Choose a reason"));
            } else if (reasonKey.endsWith(".other") && (note == null || note.length() < 4)) {
                errors.add(new FieldMessage("note", "Write at least 4 characters"));
            }
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        return note;
    }

    /**
     * All the line errors together (rule 21). A stock line names a batch and a source state of an eligible row and a quantity
     * from 1 to what is available ("Enter 1 to n"); a short supply line names a receipt line and a quantity up to what is not
     * yet claimed. Credit percent is 0 to 100 with up to two decimals; a value other than the row's starting credit is marked
     * changed. {@code ownLines} are the lines of the note being edited: a line of a prepared draft that is no longer in the
     * eligible list (a rate difference) may be kept up to its own quantity.
     */
    private Checked check(UUID tenantId, UUID storeId, Vendor vendor, UUID exceptNoteId,
                          List<PharmacyInputs.DebitNoteLineInput> lines, List<Line> ownLines, List<FieldMessage> errors) {
        List<PharmacyInputs.DebitNoteLineInput> given = lines == null ? List.of() : lines;
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", "Add 1 to 200 items"));
            return new Checked(List.of(), 0, 0);
        }
        Map<String, Eligible> byStock = new HashMap<>();
        Map<UUID, Eligible> byShort = new HashMap<>();
        for (Eligible e : eligibleRows(tenantId, storeId, vendor, exceptNoteId)) {
            if (e.grnLineId() != null) {
                byShort.put(e.grnLineId(), e);
            } else {
                byStock.put(e.batchId() + "|" + e.state(), e);
            }
        }
        Map<UUID, Line> ownShort = new HashMap<>();
        ownLines.stream().filter(l -> !l.stockReturn()).forEach(l -> ownShort.put(l.goodsReceiptLineId(), l));
        List<Built> built = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        long credit = 0;
        long gst = 0;
        for (int i = 0; i < given.size(); i++) {
            PharmacyInputs.DebitNoteLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            int before = errors.size();
            boolean stockReturn = "STOCK_RETURN".equals(line.type());
            if (!stockReturn && !"SHORT_SUPPLY".equals(line.type())) {
                errors.add(new FieldMessage(at + ".type", "Choose stock return or short supply"));
                continue;
            }
            Integer qty = PharmacyParams.wholeNumber(line.qty(), 1, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".qty", "Enter a whole number from 1 to 999999"));
            }
            if (!ReceiptMath.validPercent(line.creditPercent())) {
                errors.add(new FieldMessage(at + ".creditPercent", "Enter 0 to 100, up to 2 decimals"));
            }
            Eligible row;
            BigDecimal rate;
            BigDecimal start;
            int available;
            Line own = null;
            if (stockReturn) {
                String state = line.sourceState();
                row = line.batchId() == null || state == null ? null : byStock.get(line.batchId() + "|" + state);
                if (row == null) {
                    errors.add(new FieldMessage(at + ".batchId", "Choose an item from the list"));
                    continue;
                }
                if (!seen.add(line.batchId() + "|" + state)) {
                    errors.add(new FieldMessage(at, "Already on this debit note"));
                }
                rate = row.rate();
                start = row.defaultCredit();
                available = row.availableQty();
            } else {
                row = line.goodsReceiptLineId() == null ? null : byShort.get(line.goodsReceiptLineId());
                own = line.goodsReceiptLineId() == null ? null : ownShort.get(line.goodsReceiptLineId());
                if (row == null && own == null) {
                    errors.add(new FieldMessage(at + ".goodsReceiptLineId", "Choose an item from the list"));
                    continue;
                }
                if (!seen.add("S|" + line.goodsReceiptLineId())) {
                    errors.add(new FieldMessage(at, "Already on this debit note"));
                }
                if (row != null) {
                    rate = row.rate();
                    start = row.defaultCredit();
                    available = row.availableQty();
                } else {
                    rate = own.rate();
                    start = own.creditPercentChanged() ? null : own.creditPercent();
                    available = own.qty();
                }
            }
            if (qty != null && qty > available) {
                errors.add(new FieldMessage(at + ".qty", "Enter 1 to " + available));
            }
            if (errors.size() > before) {
                continue;
            }
            ProductRepository.Row product = row != null ? row.product()
                    : products.find(tenantId, ownShortProduct(ownShort, line.goodsReceiptLineId())).map(ProductRepository.Full::row)
                            .orElseThrow();
            BigDecimal gstRate = product.gstRate() == null ? BigDecimal.ZERO : product.gstRate();
            long creditPaise = stockReturn ? ReceiptMath.creditPaise(qty, rate, line.creditPercent())
                    : ReceiptMath.shortSupplyCreditPaise(qty, row == null ? 1 : row.conversion(),
                            row == null ? null : row.purchaseRate(), rate, line.creditPercent());
            if (!stockReturn && row == null && own != null && qty.intValue() == own.qty()
                    && line.creditPercent().compareTo(own.creditPercent()) == 0) {
                creditPaise = own.creditPaise(); // a prepared line nobody changed keeps the credit it was prepared with
            }
            long gstReversal = PurchaseMath.gstPaise(creditPaise, gstRate);
            boolean changed = start != null && line.creditPercent().compareTo(start) != 0;
            built.add(new Built(new NewLine(stockReturn ? "STOCK_RETURN" : "SHORT_SUPPLY", product.productId(), built.size() + 1,
                    stockReturn ? line.batchId() : null, stockReturn ? line.sourceState() : null,
                    stockReturn ? null : line.goodsReceiptLineId(), qty, rate, line.creditPercent(), changed, creditPaise, gstRate,
                    gstReversal), product));
            credit += creditPaise;
            gst += gstReversal;
        }
        if (credit > MAX_CREDIT_PAISE) {
            errors.add(new FieldMessage("lines", "The credit is too large"));
        }
        return new Checked(built, credit, gst);
    }

    private UUID ownShortProduct(Map<UUID, Line> ownShort, UUID grnLineId) {
        return ownShort.get(grnLineId).productId();
    }

    private boolean isScheduleX(UUID tenantId, UUID productId) {
        return GoodsReceiptService.isScheduleX(products.find(tenantId, productId).map(ProductRepository.Full::row).orElse(null));
    }

    private boolean replayed(UUID tenantId, UUID me, UUID clientKey, String operation, UUID subjectId) {
        if (clientKey == null) {
            return false;
        }
        ClientRequestRepository.Taken own = clients.own(tenantId, me, clientKey).orElse(null);
        if (own != null) {
            if (!operation.equals(own.operation()) || !subjectId.equals(own.subjectId())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            return true;
        }
        if (clients.heldByAnyone(tenantId, clientKey)) {
            throw PharmacyProblemException.clientKeyReused();
        }
        return false;
    }

    private static Long wholePaise(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.compareTo(BigDecimal.valueOf(MAX_CREDIT_PAISE)) > 0) {
            return null;
        }
        return value.longValueExact();
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
            throw PharmacyProblemException.changed(view(caller, row));
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
        DocumentSeriesRepository.Row saved = series.find(tenantId, DocType.DEBIT_NOTE).orElse(null);
        if (saved == null) {
            return DocumentNumbers.render(DocType.DEBIT_NOTE.defaultPrefix(), DocType.DEBIT_NOTE.defaultFinancialYearPart(),
                    DocType.DEBIT_NOTE.defaultSequenceLength(), seq, today);
        }
        return DocumentNumbers.render(saved.prefix(), saved.financialYearPart(), saved.sequenceLength(), seq, today);
    }

    private static Map<String, Object> values(UUID storeId, UUID vendorId, int lines, long creditTotalPaise, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("vendorId", vendorId.toString());
        values.put("lineCount", lines);
        values.put("creditTotalPaise", creditTotalPaise);
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

    PurchaseViews.DebitNote view(PharmacyCaller caller, Row row) {
        UUID tenantId = caller.tenantId();
        LocalDate today = time.today();
        Vendor vendor = vendors.find(tenantId, row.vendorId()).orElseThrow();
        Store store = stores.find(tenantId, row.storeId()).orElseThrow();
        List<Line> lines = notes.lines(tenantId, row.id());
        DebitNoteRepository.Credit credit = notes.credit(tenantId, row.id()).orElse(null);
        List<UUID> people = new ArrayList<>();
        people.add(row.createdBy());
        people.add(row.pickedBy());
        people.add(row.handedOverBy());
        if (credit != null) {
            people.add(credit.createdBy());
        }
        DocumentViews.Loaded loaded = views.load(tenantId, DOC_TYPE, row.id(), people);

        List<PurchaseViews.DnLine> lineViews = new ArrayList<>();
        List<PurchaseViews.PickRow> pick = new ArrayList<>();
        Map<UUID, String> racks = new HashMap<>();
        for (Line l : lines) {
            ProductRepository.Row p = products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElseThrow();
            BatchRow batch = l.batchId() == null ? null : stock.findBatch(tenantId, l.batchId()).orElse(null);
            StockViews.BatchRef batchRef = batch == null ? null : new StockViews.BatchRef(batch.id(), batch.batchNo(),
                    batch.expiryDate());
            lineViews.add(new PurchaseViews.DnLine(l.id(), l.lineType(), productRef(p), batchRef, l.sourceState(),
                    l.goodsReceiptLineId(), l.qty(), PurchaseMath.rateText(l.rate()), PurchaseMath.percentText(l.creditPercent()),
                    l.creditPercentChanged(), l.creditPaise(), PurchaseMath.percentText(l.gstRate()), l.gstReversalPaise(),
                    GoodsReceiptService.isScheduleX(p)));
            if (l.stockReturn() && batchRef != null) {
                String rack = racks.computeIfAbsent(l.productId(), id -> products.storeSettings(tenantId, id).stream()
                        .filter(s -> s.storeId().equals(row.storeId())).map(ProductRepository.StoreSettingRow::rack)
                        .filter(r -> r != null).findFirst().orElse(""));
                pick.add(new PurchaseViews.PickRow(p.name(), batchRef, l.qty(), rack.isEmpty() ? null : rack));
            }
        }
        DocumentEventRepository.Event issued = loaded.events().stream().filter(e -> "ISSUED".equals(e.event())).findFirst()
                .orElse(null);
        List<PurchaseViews.DnStep> steps = List.of(
                new PurchaseViews.DnStep("CREATED", issued == null ? null : issued.occurredAt(),
                        issued == null ? null : loaded.person(issued.actorMembershipId())),
                new PurchaseViews.DnStep("PICKED", row.pickedAt(), loaded.person(row.pickedBy())),
                new PurchaseViews.DnStep("HANDED_OVER", row.handedOverAt(), loaded.person(row.handedOverBy())),
                new PurchaseViews.DnStep("CREDIT_NOTE_RECEIVED", credit == null ? null : credit.createdAt(),
                        credit == null ? null : loaded.person(credit.createdBy())),
                new PurchaseViews.DnStep("ADJUSTED_IN_PAYABLE", credit == null ? null : credit.createdAt(),
                        credit == null ? null : loaded.person(credit.createdBy())));
        PurchaseViews.CreditNoteView creditView = credit == null ? null : new PurchaseViews.CreditNoteView(credit.creditNoteNo(),
                credit.creditNoteDate(), credit.amountPaise(), credit.differencePaise());
        PurchaseViews.PayableEffect effect = credit == null ? null
                : new PurchaseViews.PayableEffect(credit.appliedPaise(), credit.unappliedCreditPaise());
        return new PurchaseViews.DebitNote(row.id(), row.docNo(), OpeningStockPostService.storeRef(store),
                vendorRef(vendor, today), row.status(), row.preparedFromGrnId(), engine.reasonRef(tenantId, row.reasonKey(), row.note()),
                lineViews, row.creditTotalPaise(), row.gstReversalPaise(), engine.summary(caller, DOC_TYPE, row.id()), steps,
                row.representativeName(), creditView, effect, pick, printHeader(tenantId), views.history(tenantId, loaded),
                row.version());
    }

    private PurchaseViews.PrintHeader printHeader(UUID tenantId) {
        PrintTemplateRepository.Row template = templates.find(tenantId, PrintDocType.DEBIT_NOTE).orElse(null);
        com.sahaj.pharmacy.domain.Unit unit = unitRows.find(tenantId).orElse(null);
        return new PurchaseViews.PrintHeader("DEBIT_NOTE", template == null ? List.of() : template.headerLines(),
                template == null ? null : template.footerNote(), template == null ? "A4" : template.paperSize(),
                unit == null ? null : unit.gstin(), unit == null ? null : unit.drugLicenceNo());
    }
}
