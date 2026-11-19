package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.StockAdjustmentRepository.NewRow;
import com.sahaj.pharmacy.application.StockAdjustmentRepository.Row;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalConditions;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.PurchaseMath;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-5: a stock adjustment, "book to physical", of one AVAILABLE batch of a store (docs/api/pharmacy.yaml 0.3.0). It is
 * saved with an ADJ number and then either posted at once (no rule applies, level 0) or left WAITING_APPROVAL and posted by the
 * approval ({@link ApprovalSubjectHandler}); the decrease is refused with 409 {@code insufficient-stock} if it no longer fits.
 *
 * <p>Lock order (ADR-029 P5): the client key, the store, the ADJ number, then the balances inside {@link StockPosting}; the
 * adjustment is inserted WAITING_APPROVAL, the approval request submitted, and only then is the adjustment updated with the
 * request id (the table allows exactly that). A Schedule X batch needs a pharmacist caller and a second signer verified by the
 * web layer before this transaction ({@link ScheduleXSignature}); the signer is rechecked here. Audit
 * {@code PHARMACY_STOCK_ADJUSTMENT_RAISED} and {@code _POSTED} hold ids, quantities and the value; notes are never logged.
 */
@Service
public class StockAdjustmentService implements ApprovalSubjectHandler {

    static final String OPERATION = "pharmacyCreateStockAdjustment";
    static final String DOC_TYPE = "STOCK_ADJUSTMENT";
    static final String NO_CHANGE = "No change to post";
    private static final Logger log = LoggerFactory.getLogger(StockAdjustmentService.class);
    private static final String ENTITY_TYPE = "PHARMACY_STOCK_ADJUSTMENT";
    private static final int MAX_QTY = 999_999;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final StockAdjustmentRepository adjustments;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final ScheduleXSignature signature;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public StockAdjustmentService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                                  StockRepository stock, StockPosting posting, StockAdjustmentRepository adjustments,
                                  ApprovalEngine engine, ApprovalRepository approvals, DocumentSeriesRepository series,
                                  DocumentNumberPort numbers, ClientRequestRepository clients, DocumentEventRepository events,
                                  ActiveReasonService reasons, ScheduleXSignature signature, TeamMemberPort team,
                                  AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.adjustments = adjustments;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.signature = signature;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public StockViews.StockAdjustmentPage list(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawPage,
                                               String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.ADJUST_STOCK);
        Status status = PharmacyParams.optionalEnum("status", rawStatus, Status.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        StockAdjustmentRepository.Slice slice = adjustments.list(tenantId, storeId, status == null ? null : status.name(), size,
                page * size);
        List<StockViews.StockAdjustment> items = slice.items().stream().map(r -> view(caller, r)).toList();
        return new StockViews.StockAdjustmentPage(items, slice.total(), page, size);
    }

    /** The adjustment with its approval and history. A current approver of its request reads it without the store capability. */
    @Transactional(readOnly = true)
    public StockViews.StockAdjustment get(UUID tenantId, UUID userId, String rawStoreId, String rawAdjustmentId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID adjustmentId = PharmacyParams.pathId(rawAdjustmentId);
        Row row = adjustments.find(tenantId, storeId, adjustmentId).orElse(null);
        if (row != null && isCurrentApprover(caller, row)) {
            return view(caller, row);
        }
        guard.require(caller, storeId, StoreCapability.ADJUST_STOCK);
        return view(caller, adjustments.find(tenantId, storeId, adjustmentId).orElseThrow(StockAdjustmentService::notFound));
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

    // ---- create ----

    /**
     * The first step of a create, before any PIN is checked: the gate, the path id, the body and the store guard, in that order.
     * Returns the second signer the body names (or null), so the web layer can verify that colleague's PIN outside any
     * transaction and only for a caller who is allowed to adjust stock here.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId,
                                                     PharmacyInputs.Body<PharmacyInputs.StockAdjustmentCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.StockAdjustmentCreate in = body.read();
        guard.require(caller, storeId, StoreCapability.ADJUST_STOCK);
        // M-2 (security M2): only a pharmacist adjusting a Schedule X batch can make a PIN attempt against a colleague. Any
        // other caller, batch or body gets no PIN call; create then answers its own 400 or 403.
        if (in.secondSigner() == null || !caller.isPharmacist() || in.batchId() == null) {
            return null;
        }
        BatchRow batch = stock.findBatch(tenantId, in.batchId()).orElse(null);
        ProductRepository.Row product = batch == null ? null
                : products.find(tenantId, batch.productId()).map(ProductRepository.Full::row).orElse(null);
        return product != null && "X".equals(product.schedule()) ? in.secondSigner() : null;
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<StockViews.StockAdjustment> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                    PharmacyInputs.Body<PharmacyInputs.StockAdjustmentCreate> body,
                                                                    UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.StockAdjustmentCreate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.ADJUST_STOCK);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OPERATION.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = adjustments.find(tenantId, storeId, own.subjectId()).orElseThrow(StockAdjustmentService::notFound);
                return new PharmacyViews.Outcome<>(view(caller, first), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        // validation, all together (rule 21): what needs no lock
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer physical = PharmacyParams.wholeNumber(in.physicalQty(), 0, MAX_QTY);
        if (physical == null) {
            errors.add(new FieldMessage("physicalQty", "Enter a whole number from 0 to 999999"));
        }
        Integer seen = PharmacyParams.wholeNumber(in.bookQtySeen(), 0, MAX_QTY);
        if (seen == null) {
            errors.add(new FieldMessage("bookQtySeen", UnitService.RELOAD));
        }
        if (!reasons.isActive(tenantId, ReasonList.STOCK_ADJUSTMENT, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (in.note() == null || in.note().trim().length() < 4)) {
            errors.add(new FieldMessage("note", "Enter a note of at least 4 characters"));
        }
        if (in.note() != null && in.note().length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        LocalDate today = time.today();
        BatchRow batch = in.batchId() == null ? null : stock.findBatch(tenantId, in.batchId()).orElse(null);
        Integer book = batch == null ? null : stock.availableQty(tenantId, storeId, batch.id()).orElse(null);
        if (batch == null || book == null || batch.expiryDate().isBefore(today)) {
            errors.add(new FieldMessage("batchId", "Choose a batch that is available in this store"));
        }
        ProductRepository.Row product = batch == null ? null
                : products.find(tenantId, batch.productId()).map(ProductRepository.Full::row).orElse(null);
        boolean scheduleX = product != null && "X".equals(product.schedule());
        if (physical != null && book != null && physical.intValue() == book.intValue()) {
            errors.add(new FieldMessage("physicalQty", NO_CHANGE));
        }
        if (in.correctsLedgerId() != null) {
            StockRepository.LedgerRow ledger = stock.findLedger(tenantId, in.correctsLedgerId()).orElse(null);
            if (ledger == null || !ledger.storeId().equals(storeId) || batch == null || !ledger.batchId().equals(batch.id())) {
                errors.add(new FieldMessage("correctsLedgerId", "Choose a stock movement of this batch"));
            }
        }
        if (scheduleX && caller.isPharmacist() && verifiedSignerMembershipId == null) {
            errors.add(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (seen.intValue() != book.intValue()) {
            throw PharmacyProblemException.changed(currentBook(batch, book));
        }
        if (adjustments.waitingFor(tenantId, storeId, batch.id())) {
            throw PharmacyProblemException.adjustmentWaiting();
        }
        if (scheduleX && !caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        UUID signer = null;
        if (scheduleX) {
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }

        // lock order: the client key, the store, the ADJ number, then the balances
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OPERATION, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body, verifiedSignerMembershipId); // L15: answer as the replay
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(StockAdjustmentService::notFound);
        long seq = numbers.next(tenantId, DocType.STOCK_ADJUSTMENT);
        String docNo = render(tenantId, seq, today);
        int lockedBook = stock.lockBalances(tenantId, storeId, List.of(batch.productId())).stream()
                .filter(b -> b.batchId().equals(batch.id()) && b.state() == StockState.AVAILABLE).mapToInt(b -> b.qty())
                .findFirst().orElse(0);
        if (lockedBook != seen.intValue()) {
            throw PharmacyProblemException.changed(currentBook(batch, lockedBook));
        }

        int difference = physical - lockedBook;
        long value = StockMath.valuePaise(Math.abs(difference), batch.purchaseRate());
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.STOCK_ADJUSTMENT, DOC_TYPE, id, docNo,
                storeId, value, scheduleX, value, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        String status = plan == null ? "POSTED" : "WAITING_APPROVAL";
        try {
            adjustments.insert(tenantId, new NewRow(id, store.unitId(), seq, docNo, storeId, batch.productId(), batch.id(),
                    lockedBook, physical, value, in.reasonKey(), blank(in.note()), in.correctsLedgerId(), signer, status, me));
        } catch (DuplicateKeyException waiting) {
            throw PharmacyProblemException.adjustmentWaiting();
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, lockedBook, physical,
                in.reasonKey());
        auditLog.record("PHARMACY_STOCK_ADJUSTMENT_RAISED", ENTITY_TYPE, id, null, values(storeId, batch.id(), lockedBook,
                physical, value, "WAITING_APPROVAL".equals(status)));
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
            adjustments.attachRequest(tenantId, id, requestId, me);
            events.insert(tenantId, store.unitId(), DOC_TYPE, id, "SUBMITTED", storeId, me, null, null, null, null, null);
        } else {
            postMovement(tenantId, store.unitId(), me, signer, null, id, docNo, storeId, batch, difference, in.reasonKey(),
                    in.correctsLedgerId());
            events.insert(tenantId, store.unitId(), DOC_TYPE, id, "POSTED", storeId, me, null, null, lockedBook, physical,
                    in.reasonKey());
            auditLog.record("PHARMACY_STOCK_ADJUSTMENT_POSTED", ENTITY_TYPE, id, null, values(storeId, batch.id(), lockedBook,
                    physical, value, false));
        }
        log.info("Stock adjustment saved adjustmentId={} status={}", id, status);
        return new PharmacyViews.Outcome<>(view(caller, adjustments.find(tenantId, storeId, id).orElseThrow()), true);
    }

    // ---- approval handler (79-2 engine, type STOCK_ADJUSTMENT) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = adjustments.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "STOCK_ADJUSTMENT", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        String storeName = stores.find(tenantId, row.storeId()).map(Store::name).orElse("");
        String sign = row.difference() < 0 ? "− " : "+ ";
        return new Summary(row.docNo() + " · " + storeName + " · " + sign + "₹ "
                + ApprovalConditions.rupees(row.valuePaise()), "STOCK_ADJUSTMENT", row.storeId(), List.of("REVIEW", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.ADJUST_STOCK);
    }

    /** The final approval posts the difference now, with the raiser as the actor and the approver on the ledger lines. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = adjustments.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(StockAdjustmentService::notFound);
        if (!"WAITING_APPROVAL".equals(row.status())) {
            return;
        }
        BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow();
        if (row.secondSignerMembershipId() != null) {
            // m-5 (ADR-029 P7): the signer must still be an ACTIVE pharmacist or owner who is not the raiser; a refusal here
            // rolls the whole decision back, so nothing is approved and nothing is posted
            signature.recheck(tenantId, row.createdBy(), row.secondSignerMembershipId());
        }
        UUID approver = decision.approver().actorId();
        postMovement(tenantId, row.unitId(), row.createdBy(), row.secondSignerMembershipId(), approver, row.id(), row.docNo(),
                row.storeId(), batch, row.difference(), row.reasonKey(), row.correctsLedgerId());
        adjustments.setStatus(tenantId, row.id(), "POSTED", approver);
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "POSTED", row.storeId(), approver, decision.actingAs(),
                null, row.bookQty(), row.physicalQty(), row.reasonKey());
        auditLog.record("PHARMACY_STOCK_ADJUSTMENT_POSTED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                values(row.storeId(), row.batchId(), row.bookQty(), row.physicalQty(), row.valuePaise(), false));
    }

    /** A rejection returns the adjustment to its raiser as REJECTED; no stock moves. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = adjustments.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(StockAdjustmentService::notFound);
        if ("WAITING_APPROVAL".equals(row.status())) {
            adjustments.setStatus(tenantId, row.id(), "REJECTED", decision.approver().actorId());
        }
    }

    // ---- helpers ----

    private void postMovement(UUID tenantId, UUID unitId, UUID actor, UUID signer, UUID approver, UUID id, String docNo,
                              UUID storeId, BatchRow batch, int difference, String reasonKey, UUID reverses) {
        posting.post(new StockPosting.Actor(tenantId, unitId, actor, signer, approver), List.of(new StockPosting.Line(storeId,
                batch.productId(), batch.id(), StockState.AVAILABLE, difference, "STOCK_ADJUSTMENT", reasonKey,
                UUID.randomUUID(), reverses, new StockPosting.Doc(DOC_TYPE, id, docNo, null), null)));
    }

    private String render(UUID tenantId, long seq, LocalDate today) {
        DocumentSeriesRepository.Row saved = series.find(tenantId, DocType.STOCK_ADJUSTMENT).orElse(null);
        if (saved == null) {
            return DocumentNumbers.render(DocType.STOCK_ADJUSTMENT.defaultPrefix(), DocType.STOCK_ADJUSTMENT.defaultFinancialYearPart(),
                    DocType.STOCK_ADJUSTMENT.defaultSequenceLength(), seq, today);
        }
        return DocumentNumbers.render(saved.prefix(), saved.financialYearPart(), saved.sequenceLength(), seq, today);
    }

    private static Map<String, Object> currentBook(BatchRow batch, int availableQty) {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("batchId", batch.id().toString());
        current.put("availableQty", availableQty);
        return current;
    }

    private static Map<String, Object> values(UUID storeId, UUID batchId, int book, int physical, long valuePaise, boolean waiting) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("batchId", batchId.toString());
        values.put("bookQty", book);
        values.put("physicalQty", physical);
        values.put("difference", physical - book);
        values.put("valuePaise", valuePaise);
        values.put("status", waiting ? "WAITING_APPROVAL" : "POSTED");
        return values;
    }

    private StockViews.StockAdjustment view(PharmacyCaller caller, Row row) {
        UUID tenantId = caller.tenantId();
        Set<UUID> people = new LinkedHashSet<>();
        people.add(row.createdBy());
        if (row.secondSignerMembershipId() != null) {
            people.add(row.secondSignerMembershipId());
        }
        List<DocumentEventRepository.Event> history = events.forDocument(tenantId, DOC_TYPE, row.id());
        history.forEach(e -> {
            if (e.actorMembershipId() != null) {
                people.add(e.actorMembershipId());
            }
        });
        Map<UUID, TeamMemberPort.TeamMember> members = team.findAll(tenantId, people);
        Map<UUID, Store> storeById = new java.util.HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        Store store = storeById.get(row.storeId());
        ProductRepository.Row p = products.find(tenantId, row.productId()).map(ProductRepository.Full::row).orElseThrow();
        BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow();
        List<StockViews.HistoryEvent> historyViews = new ArrayList<>();
        for (DocumentEventRepository.Event e : history) {
            Store eventStore = storeById.get(e.storeId());
            historyViews.add(new StockViews.HistoryEvent(e.event(), e.occurredAt(),
                    e.actorMembershipId() == null ? null : person(e.actorMembershipId(), members), e.actingAs(),
                    eventStore == null ? null : OpeningStockPostService.storeRef(eventStore), null, e.qtyBefore(),
                    e.qtyAfter(), engine.reasonRef(tenantId, e.reasonKey(), null)));
        }
        return new StockViews.StockAdjustment(row.id(), row.docNo(), store == null ? null : OpeningStockPostService.storeRef(store),
                new StockViews.ProductRef(p.productId(), p.code(), p.name(), p.strength(), p.form(), p.schedule(),
                        p.purchaseUnit(), p.issueUnit(), p.conversion(), PurchaseMath.percentText(p.gstRate())),
                new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), row.bookQty(), row.physicalQty(),
                row.difference(), row.valuePaise(), engine.reasonRef(tenantId, row.reasonKey(), row.note()),
                person(row.createdBy(), members), row.createdAt(),
                row.secondSignerMembershipId() == null ? null : person(row.secondSignerMembershipId(), members), row.status(),
                engine.summary(caller, DOC_TYPE, row.id()), historyViews, row.version());
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }

    /** The list filter. */
    enum Status { WAITING_APPROVAL, POSTED, REJECTED }
}
