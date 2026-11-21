package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.TransferRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * SAH-79-15, store-to-store transfers (docs/api/pharmacy.yaml 0.3.6 pharmacyListTransfers, pharmacyCreateTransfer,
 * pharmacyListTransferStores, pharmacyGetTransfer, pharmacyCancelTransfer; dispatch is {@link TransferDispatchService}). A transfer
 * is requested and submitted in one step: the TRF number is taken and the STOCK_TRANSFER rule (rule store: the sending store)
 * decides, level 0 giving APPROVED and otherwise WAITING_APPROVAL for the sending store's HOD or current delegate
 * ({@link ApprovalSubjectHandler}). Nothing moves until dispatch. A transfer never goes to the Central Store.
 *
 * <p>Order of every operation: the gate, the path ids, the body, the store guard, the document checks, the client key, the
 * validation (all field errors together), then the locks: the client key, the sending store row, the TRF number, then the
 * request (cancel) and the header. The actor of every writer is the caller's membership id. Logs and audit rows hold ids, counts
 * and statuses only.
 */
@Service
public class TransferService implements ApprovalSubjectHandler {

    static final String DOC_TYPE = "TRANSFER";
    static final String OP_CREATE = "pharmacyCreateTransfer";
    static final String MAX_LINES_MESSAGE = "Add 1 to 200 items";
    static final String DIFFERENT = "Choose two different stores";
    static final String NOT_TO_CENTRAL = "Use Return to Central Store";
    /** The default escalation of an unreceived transfer, in hours (OQ-79-29), when the rule has none of its own. */
    static final int NOT_RECEIVED_HOURS = 24;
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String ENTITY_TYPE = "PHARMACY_TRANSFER";
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final TransferRepository transfers;
    private final DispatchRepository dispatches;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final DocumentViews documentViews;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final BlindCount blind;

    public TransferService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                           StockRepository stock, StockPosting posting, TransferRepository transfers,
                           DispatchRepository dispatches, ApprovalEngine engine, ApprovalRepository approvals,
                           DocumentSeriesRepository series, DocumentNumberPort numbers, ClientRequestRepository clients,
                           DocumentEventRepository events, ActiveReasonService reasons, DocumentViews documentViews,
                           AuditLog auditLog, PharmacyTime time, BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.transfers = transfers;
        this.dispatches = dispatches;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.documentViews = documentViews;
        this.auditLog = auditLog;
        this.time = time;
        this.blind = blind;
    }

    // ---- reads ----

    /** "Open transfers": from or to this store; open (default true) keeps WAITING_APPROVAL, APPROVED and IN_TRANSIT. Newest first. */
    @Transactional(readOnly = true)
    public TransferViews.Page list(UUID tenantId, UUID userId, String rawStoreId, String rawOpen, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.READ_STOCK);
        boolean open = PharmacyParams.bool("open", rawOpen, true);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        TransferRepository.Slice slice = transfers.listOfStore(tenantId, storeId, open, size, page * size);
        Map<UUID, Store> storeById = storeMap(tenantId);
        Instant now = time.now();
        List<TransferViews.Item> items = new ArrayList<>();
        for (TransferRepository.ListRow r : slice.items()) {
            items.add(new TransferViews.Item(r.id(), r.docNo(), storeRef(storeById.get(r.fromStoreId())),
                    storeRef(storeById.get(r.toStoreId())), r.itemCount(), r.status(),
                    Math.max(0, Duration.between(r.createdAt(), now).toMinutes()),
                    notReceivedHours("IN_TRANSIT".equals(r.status()) ? r.dispatchedAt() : null, now)));
        }
        return new TransferViews.Page(items, slice.total(), page, size);
    }

    /** The other end of a new transfer: every ACTIVE store of the unit except the path store, the Central Store first. */
    @Transactional(readOnly = true)
    public TransferViews.StoreList transferStores(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store path = guard.require(caller, storeId, StoreCapability.RAISE_INDENT);
        List<Store> others = stores.list(tenantId, true).stream()
                .filter(s -> s.active() && s.unitId().equals(path.unitId()) && !s.id().equals(storeId))
                .sorted(Comparator.comparing((Store s) -> s.type() == StoreType.CENTRAL ? 0 : 1).thenComparing(Store::name))
                .limit(500).toList();
        return new TransferViews.StoreList(others.stream().map(TransferService::storeRef).toList());
    }

    /** The sending or receiving store (readStock) or a current approver of its request; for anyone else it does not exist. */
    @Transactional(readOnly = true)
    public TransferViews.Transfer get(UUID tenantId, UUID userId, String rawStoreId, String rawTransferId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID transferId = PharmacyParams.pathId(rawTransferId);
        Row row = transfers.findById(tenantId, transferId).orElse(null);
        if (row != null && isCurrentApprover(caller, row)) {
            return view(caller, row, storeId);
        }
        guard.require(caller, storeId, StoreCapability.READ_STOCK);
        if (row == null || !storeId.equals(row.fromStoreId()) && !storeId.equals(row.toStoreId())) {
            throw notFound();
        }
        return view(caller, row, storeId);
    }

    // ---- create ----

    @Transactional
    public PharmacyViews.Outcome<TransferViews.Transfer> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                PharmacyInputs.Body<PharmacyInputs.TransferCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.TransferCreate in = body.read();
        Store pathStore = guard.require(caller, storeId, StoreCapability.RAISE_INDENT);
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = transfers.findById(tenantId, own.subjectId()).orElseThrow(TransferService::notFound);
                return new PharmacyViews.Outcome<>(view(caller, first, storeId), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Store from = in.fromStoreId() == null ? null : stores.find(tenantId, in.fromStoreId()).filter(Store::active).orElse(null);
        Store to = in.toStoreId() == null ? null : stores.find(tenantId, in.toStoreId()).filter(Store::active).orElse(null);
        if (from == null || !from.unitId().equals(pathStore.unitId())) {
            errors.add(new FieldMessage("fromStoreId", "Choose a store"));
            from = null;
        }
        if (to == null || !to.unitId().equals(pathStore.unitId())) {
            errors.add(new FieldMessage("toStoreId", "Choose a store"));
            to = null;
        }
        if (from != null && to != null) {
            if (from.id().equals(to.id())) {
                errors.add(new FieldMessage("toStoreId", DIFFERENT));
            } else if (to.type() == StoreType.CENTRAL) {
                errors.add(new FieldMessage("toStoreId", NOT_TO_CENTRAL));
            }
            if (!storeId.equals(from.id()) && !storeId.equals(to.id())) {
                errors.add(new FieldMessage("fromStoreId", "This store must be one end of the transfer"));
            }
        }
        String note = StoreUseSupport.blank(in.note());
        if (!reasons.isActive(tenantId, ReasonList.TRANSFER, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        checkLines(tenantId, from, in.lines(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, pathStore.unitId(), me, in.clientKey(), OP_CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body); // L15: the same key committed first, answer as its replay
        }
        // lock order: the client key, the sending store row, the TRF number, then the header and lines
        stores.findForUpdate(tenantId, from.id()).filter(Store::active).orElseThrow(TransferService::notFound);
        // M1: the lines are checked again under the lock; a line that no longer passes refuses the transfer, it is never dropped
        List<FieldMessage> recheck = new ArrayList<>();
        List<Checked> priced = checkLines(tenantId, from, in.lines(), recheck);
        if (!recheck.isEmpty()) {
            throw new InvalidFieldsException(recheck);
        }
        long seq = numbers.next(tenantId, DocType.TRANSFER);
        String docNo = StoreUseSupport.render(series, tenantId, DocType.TRANSFER, seq, time.today());
        long total = priced.stream().mapToLong(Checked::valuePaise).sum();
        long maxLine = priced.stream().mapToLong(Checked::valuePaise).max().orElse(0);
        boolean scheduleX = priced.stream().anyMatch(Checked::scheduleX);
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.STOCK_TRANSFER, DOC_TYPE, id, docNo, from.id(),
                total, scheduleX, maxLine, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        String status = plan == null ? "APPROVED" : "WAITING_APPROVAL";
        transfers.insert(tenantId, new TransferRepository.NewRow(id, pathStore.unitId(), seq, docNo, from.id(), to.id(),
                in.reasonKey(), note, status, total, me));
        for (Checked c : priced) {
            transfers.insertLine(tenantId, new TransferRepository.NewLine(UUID.randomUUID(), pathStore.unitId(), id, c.productId(),
                    c.quantity(), c.valuePaise(), me));
        }
        events.insert(tenantId, pathStore.unitId(), DOC_TYPE, id, "REQUESTED", storeId, me, null, null, null, null, in.reasonKey());
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, pathStore.unitId(), me, subject, plan);
            transfers.attachRequest(tenantId, id, requestId, me);
            events.insert(tenantId, pathStore.unitId(), DOC_TYPE, id, "SUBMITTED", from.id(), me, null, null, null, null, null);
        } else {
            events.insert(tenantId, pathStore.unitId(), DOC_TYPE, id, "APPROVED", from.id(), me, null, null, null, null, null);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("number", docNo);
        after.put("status", status);
        after.put("lineCount", priced.size());
        after.put("fromStoreId", from.id().toString());
        after.put("toStoreId", to.id().toString());
        auditLog.record("PHARMACY_TRANSFER_CREATED", ENTITY_TYPE, id, null, after);
        log.info("Transfer created transferId={} status={} lines={}", id, status, priced.size());
        return new PharmacyViews.Outcome<>(view(caller, transfers.findById(tenantId, id).orElseThrow(), storeId), true);
    }

    // ---- cancel ----

    /** The requester, or a person with dispatchTransfer at the sending store, before the transfer is dispatched. */
    @Transactional
    public TransferViews.Transfer cancel(UUID tenantId, UUID userId, String rawStoreId, String rawTransferId,
                                         PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID transferId = PharmacyParams.pathId(rawTransferId);
        PharmacyInputs.VersionReasonInput in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        UUID me = caller.actorId();
        Row peek = transfers.findById(tenantId, transferId).orElseThrow(TransferService::notFound);
        if (!storeId.equals(peek.fromStoreId()) && !storeId.equals(peek.toStoreId())) {
            throw notFound();
        }
        if (!peek.createdBy().equals(me) && !StoreAccessGuard.holds(caller, peek.fromStoreId(), StoreCapability.DISPATCH_TRANSFER)) {
            throw guard.refuse(caller, peek.fromStoreId(), StoreCapability.DISPATCH_TRANSFER);
        }
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        String note = StoreUseSupport.blank(in.note());
        if (!reasons.isActive(tenantId, ReasonList.APPROVAL_REJECT, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        // lock order: the approval request, then the header
        ApprovalRepository.Request request = peek.approvalRequestId() == null ? null
                : approvals.findForUpdate(tenantId, peek.approvalRequestId()).orElse(null);
        Row row = transfers.findForUpdate(tenantId, transferId).orElseThrow(TransferService::notFound);
        if (version == null || row.version() != version) {
            throw PharmacyProblemException.changed(view(caller, row, storeId));
        }
        if (!"WAITING_APPROVAL".equals(row.status()) && !"APPROVED".equals(row.status())) {
            throw PharmacyProblemException.docState("Only a transfer that is not dispatched yet can be cancelled.");
        }
        if (request != null && request.pending()) {
            approvals.finish(tenantId, request.id(), request.version(), "WITHDRAWN", me);
        }
        if (!transfers.setStatus(tenantId, transferId, row.version(), "CANCELLED", me)) {
            throw new IllegalStateException("A transfer changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, transferId, "CANCELLED", storeId, me, null, null, null, null,
                in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CANCELLED");
        after.put("reasonKey", in.reasonKey());
        auditLog.record("PHARMACY_TRANSFER_CANCELLED", ENTITY_TYPE, transferId, Map.of("status", row.status()), after);
        log.info("Transfer cancelled transferId={}", transferId);
        return view(caller, transfers.findById(tenantId, transferId).orElseThrow(), storeId);
    }

    // ---- approval handler (type STOCK_TRANSFER, subject TRANSFER) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = transfers.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "TRANSFER", request.ruleStoreId(), List.of("APPROVE", "REJECT"));
        }
        Store from = stores.find(tenantId, row.fromStoreId()).orElse(null);
        Store to = stores.find(tenantId, row.toStoreId()).orElse(null);
        int count = transfers.lines(tenantId, row.id()).size();
        String what = row.docNo() + " · " + (from == null ? "" : from.name()) + " → " + (to == null ? "" : to.name()) + " · "
                + count + (count == 1 ? " item" : " items");
        return new Summary(what, "TRANSFER", row.fromStoreId(), List.of("APPROVE", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        Row row = transfers.findById(caller.tenantId(), request.subjectId()).orElse(null);
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.READ_STOCK)
                || row != null && StoreAccessGuard.holds(caller, row.toStoreId(), StoreCapability.READ_STOCK);
    }

    /** The final approval: APPROVED, ready for the sending store to dispatch. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = transfers.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(TransferService::notFound);
        if (!"WAITING_APPROVAL".equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        if (!transfers.setStatus(tenantId, row.id(), row.version(), "APPROVED", approver)) {
            throw new IllegalStateException("A transfer changed under its lock");
        }
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "APPROVED", row.fromStoreId(), approver, decision.actingAs(),
                null, null, null, null);
        auditLog.record("PHARMACY_TRANSFER_APPROVED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "APPROVED"));
    }

    /** A rejection at any level finishes the transfer as REJECTED. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = transfers.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(TransferService::notFound);
        if (!"WAITING_APPROVAL".equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        if (!transfers.setStatus(tenantId, row.id(), row.version(), "REJECTED", approver)) {
            throw new IllegalStateException("A transfer changed under its lock");
        }
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "REJECTED", row.fromStoreId(), approver, decision.actingAs(),
                null, null, null, decision.input() == null ? null : decision.input().reasonKey());
        auditLog.record("PHARMACY_TRANSFER_REJECTED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "REJECTED"));
    }

    // ---- checks and views ----

    /** One line after the checks: its FEFO preview from the sending store and its value (quantity x purchase rate, no GST). */
    record Checked(UUID productId, int quantity, long valuePaise, boolean scheduleX, List<Fefo.Pick> picks) {
    }

    /**
     * All the line errors together (rule 21): 1 to 200 lines; the product ACTIVE, once; the quantity a whole number from 1 to
     * 999999 and not above the sending store's AVAILABLE unexpired stock ("Enter a whole number from 1 to n").
     */
    List<Checked> checkLines(UUID tenantId, Store from, List<PharmacyInputs.TransferLineInput> lines, List<FieldMessage> errors) {
        List<PharmacyInputs.TransferLineInput> given = lines == null ? List.of() : lines;
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", MAX_LINES_MESSAGE));
            return List.of();
        }
        List<Checked> checked = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < given.size(); i++) {
            PharmacyInputs.TransferLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            ProductRepository.Row product = line.productId() == null ? null
                    : products.find(tenantId, line.productId()).map(ProductRepository.Full::row).orElse(null);
            if (product == null) {
                errors.add(new FieldMessage(at + ".productId", "Choose an active product"));
                continue;
            }
            if (!seen.add(line.productId())) {
                errors.add(new FieldMessage(at + ".productId", "Already on this transfer"));
                continue;
            }
            Integer qty = PharmacyParams.wholeNumber(line.quantity(), 1, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".quantity", "Enter a whole number from 1 to " + MAX_QTY));
                continue;
            }
            if (from == null) {
                continue;
            }
            List<Bal> rows = stock.availableOfProduct(tenantId, from.id(), line.productId());
            Fefo.Result result = posting.pick(rows, qty);
            if (result.shortBy() > 0) {
                errors.add(new FieldMessage(at + ".quantity", "Enter a whole number from 1 to " + result.available()));
                continue;
            }
            Map<UUID, BigDecimal> rateOf = new HashMap<>();
            rows.forEach(b -> rateOf.put(b.batchId(), b.purchaseRate()));
            long value = 0;
            for (Fefo.Pick p : result.picks()) {
                BigDecimal rate = rateOf.get(p.batchId());
                value += rate == null ? 0 : StockMath.valuePaise(p.quantity(), rate);
            }
            checked.add(new Checked(line.productId(), qty, value, GoodsReceiptService.isScheduleX(product), result.picks()));
        }
        return checked;
    }

    TransferViews.Transfer view(PharmacyCaller caller, Row row, UUID pathStoreId) {
        UUID tenantId = caller.tenantId();
        Map<UUID, Store> storeById = storeMap(tenantId);
        Store from = storeById.get(row.fromStoreId());
        List<TransferRepository.Line> lines = transfers.lines(tenantId, row.id());
        DispatchRepository.Row dispatch = dispatches.byTransfer(tenantId, row.id()).orElse(null);
        Map<UUID, List<DispatchRepository.Line>> sent = new HashMap<>();
        if (dispatch != null) {
            dispatches.lines(tenantId, dispatch.id()).forEach(l -> sent.computeIfAbsent(l.productId(), k -> new ArrayList<>()).add(l));
        }
        LocalDate today = time.today();
        boolean live = "WAITING_APPROVAL".equals(row.status()) || "APPROVED".equals(row.status());
        List<TransferViews.Line> views = new ArrayList<>();
        for (TransferRepository.Line l : lines) {
            ProductRepository.Row product = products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElseThrow();
            List<Bal> rows = stock.availableOfProduct(tenantId, row.fromStoreId(), l.productId());
            int available = rows.stream().filter(b -> !b.expiryDate().isBefore(today)).mapToInt(Bal::qty).sum();
            List<TransferViews.PreviewBatch> preview = new ArrayList<>();
            if (live) {
                for (Fefo.Pick p : posting.pick(rows, l.quantity()).picks()) {
                    preview.add(previewOf(tenantId, p.batchId(), p.quantity()));
                }
            } else {
                for (DispatchRepository.Line d : sent.getOrDefault(l.productId(), List.of())) {
                    preview.add(previewOf(tenantId, d.batchId(), d.dispatchedQty()));
                }
            }
            views.add(new TransferViews.Line(l.id(), IndentService.productRef(product), l.quantity(), preview,
                    blind.available(caller, row.fromStoreId(), l.productId(), available), // rule 24: null for a blind caller
                    l.valuePaise()));
        }
        DocumentViews.Loaded loaded = documentViews.load(tenantId, DOC_TYPE, row.id(), List.of(row.createdBy()));
        Instant now = time.now();
        boolean inTransit = dispatch != null && !"RECEIVED".equals(dispatch.status()) && "IN_TRANSIT".equals(row.status());
        TransferViews.DispatchRef dispatchRef = dispatch == null ? null
                : new TransferViews.DispatchRef(dispatch.id(), dispatch.status());
        return new TransferViews.Transfer(row.id(), row.docNo(), storeRef(from), storeRef(storeById.get(row.toStoreId())),
                engine.reasonRef(tenantId, row.reasonKey(), row.note()), row.status(), step(row.status()),
                inTransit ? notReceivedHours(dispatch.dispatchedAt(), now) : null, views, row.totalValuePaise(),
                engine.summary(caller, DOC_TYPE, row.id()), dispatchRef, loaded.person(row.createdBy()), row.createdAt(),
                documentViews.history(tenantId, loaded), row.version());
    }

    private TransferViews.PreviewBatch previewOf(UUID tenantId, UUID batchId, int quantity) {
        StockRepository.BatchRow batch = stock.findBatch(tenantId, batchId).orElseThrow();
        return new TransferViews.PreviewBatch(new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), quantity);
    }

    static String step(String status) {
        switch (status) {
            case "APPROVED":
                return "APPROVED";
            case "IN_TRANSIT":
                return "DISPATCHED";
            case "RECEIVED":
                return "RECEIVED";
            default:
                return "REQUESTED";
        }
    }

    /** "Not received · n h": whole hours in transit once past the escalation hours, otherwise null. */
    static Integer notReceivedHours(Instant dispatchedAt, Instant now) {
        if (dispatchedAt == null) {
            return null;
        }
        long hours = Duration.between(dispatchedAt, now).toHours();
        return hours >= NOT_RECEIVED_HOURS ? (int) Math.min(hours, Integer.MAX_VALUE) : null;
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

    private Map<UUID, Store> storeMap(UUID tenantId) {
        Map<UUID, Store> byId = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> byId.put(s.id(), s));
        return byId;
    }

    private static StockViews.StoreRef storeRef(Store store) {
        return store == null ? null : OpeningStockPostService.storeRef(store);
    }

    private static Long version(BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
            return null;
        }
        return whole.longValue();
    }

    static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
