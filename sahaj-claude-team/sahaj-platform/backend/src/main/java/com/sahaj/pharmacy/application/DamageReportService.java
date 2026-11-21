package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DamageReportRepository.NewRow;
import com.sahaj.pharmacy.application.DamageReportRepository.Row;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-14, "Report damaged / expired" (docs/api/pharmacy.yaml 0.3.5 pharmacyCreateDamageReport, pharmacyGetDamageReport;
 * ADR-029 P12). In one transaction the DMG number is taken and the units go AVAILABLE to QUARANTINE at once (ledger
 * DAMAGE_QUARANTINE; units already in QUARANTINE, such as an expired batch the system moved, are reported from there and do not
 * move). The WRITE_OFF rule then decides: with no level the wish ({@code afterApproval}) applies now, otherwise the report waits
 * for the HOD ({@link ApprovalSubjectHandler}). Approved, the units are written off out of QUARANTINE (ledger WRITE_OFF, no
 * signer) or go QUARANTINE to PENDING_RETURN with a return item for the Central Store (ledger DAMAGE_SEND_TO_CS). Rejected, the
 * decider chooses RELEASE_TO_AVAILABLE (never for an expired batch; ledger DAMAGE_RELEASE, no signer) or KEEP_IN_QUARANTINE. The
 * Central Store can only write off. A Schedule X batch needs a pharmacist and the verified second signer, who signs the
 * quarantine and the send to the Central Store.
 *
 * <p>Lock order: the client key, the store row, the DMG number, the stock rows inside {@link StockPosting}; through the approval
 * the request first, then the report. The actor of every writer is the caller's membership id (the raiser, with the approver on
 * the ledger line when it is approved). Logs and audit rows hold ids and counts only.
 */
@Service
public class DamageReportService implements ApprovalSubjectHandler {

    static final String OPERATION = "pharmacyCreateDamageReport";
    static final String DOC_TYPE = "DAMAGE_REPORT";
    static final String RELEASE = "RELEASE_TO_AVAILABLE";
    static final String KEEP = "KEEP_IN_QUARANTINE";
    static final String OUTCOME_NEEDED = "Choose what happens to the units.";
    static final String NO_RELEASE_EXPIRED = "An expired batch cannot be released to Available.";
    private static final Logger log = LoggerFactory.getLogger(DamageReportService.class);
    private static final String ENTITY_TYPE = "PHARMACY_DAMAGE_REPORT";
    private static final int MAX_QTY = 999_999;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final DamageReportRepository reports;
    private final ReturnItemRepository returnItems;
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
    private final BlindCount blind;

    public DamageReportService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                               StockRepository stock, StockPosting posting, DamageReportRepository reports,
                               ReturnItemRepository returnItems, ApprovalEngine engine, ApprovalRepository approvals,
                               DocumentSeriesRepository series, DocumentNumberPort numbers, ClientRequestRepository clients,
                               DocumentEventRepository events, ActiveReasonService reasons, ScheduleXSignature signature,
                               TeamMemberPort team, AuditLog auditLog, PharmacyTime time, BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.reports = reports;
        this.returnItems = returnItems;
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
        this.blind = blind;
    }

    // ---- reads ----

    /** The reporting store (returnDamage) or a current approver of the report's request; for anyone else it does not exist. */
    @Transactional(readOnly = true)
    public StoreUseViews.DamageReport get(UUID tenantId, UUID userId, String rawStoreId, String rawReportId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID reportId = PharmacyParams.pathId(rawReportId);
        Row row = reports.find(tenantId, storeId, reportId).orElse(null);
        if (row != null && isCurrentApprover(caller, row)) {
            return view(caller, row);
        }
        guard.require(caller, storeId, StoreCapability.RETURN_DAMAGE);
        return view(caller, reports.find(tenantId, storeId, reportId).orElseThrow(DamageReportService::notFound));
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

    /** The second signer to verify before the create transaction: a pharmacist reporting a Schedule X batch, key not used yet. */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId,
                                                     PharmacyInputs.Body<PharmacyInputs.DamageReportCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.DamageReportCreate in = body.read();
        guard.require(caller, storeId, StoreCapability.RETURN_DAMAGE);
        if (in.secondSigner() == null || !caller.isPharmacist() || in.batchId() == null) {
            return null;
        }
        BatchRow batch = stock.findBatch(tenantId, in.batchId()).orElse(null);
        return batch != null && isScheduleX(tenantId, batch.productId()) ? in.secondSigner() : null;
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<StoreUseViews.DamageReport> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                    PharmacyInputs.Body<PharmacyInputs.DamageReportCreate> body,
                                                                    UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.DamageReportCreate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RETURN_DAMAGE);
        if (store.type() == StoreType.CENTRAL) {
            guard.require(caller, storeId, StoreCapability.PURCHASE); // the Central Store: a Central Store user or the owner
        }
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OPERATION.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = reports.find(tenantId, storeId, own.subjectId()).orElseThrow(DamageReportService::notFound);
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
        BatchRow batch = in.batchId() == null ? null : stock.findBatch(tenantId, in.batchId()).orElse(null);
        if (batch == null) {
            errors.add(new FieldMessage("batchId", "Choose a batch of this store"));
        }
        String fromState = in.fromState();
        if (!"AVAILABLE".equals(fromState) && !"QUARANTINE".equals(fromState)) {
            errors.add(new FieldMessage("fromState", "Choose available or quarantine"));
        }
        Integer quantity = PharmacyParams.wholeNumber(in.quantity(), 1, MAX_QTY);
        if (quantity == null) {
            errors.add(new FieldMessage("quantity", "Enter a whole number from 1 to " + MAX_QTY));
        }
        String note = StoreUseSupport.blank(in.note());
        if (!reasons.isActive(tenantId, ReasonList.DAMAGE, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Enter a note of at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        String after = in.afterApproval();
        if (!"WRITE_OFF".equals(after) && !"SEND_TO_CS".equals(after)) {
            errors.add(new FieldMessage("afterApproval", "Choose write off or send to the Central Store"));
        } else if ("SEND_TO_CS".equals(after) && store.type() == StoreType.CENTRAL) {
            errors.add(new FieldMessage("afterApproval", "The Central Store can only write off damaged stock"));
        }
        boolean scheduleX = batch != null && isScheduleX(tenantId, batch.productId());
        if (scheduleX && caller.isPharmacist() && verifiedSignerMembershipId == null) {
            errors.add(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (scheduleX && !caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        UUID signer = null;
        if (scheduleX) {
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }
        LocalDate today = time.today();
        Store central = "SEND_TO_CS".equals(after) ? StoreUseSupport.centralStoreOf(stores, tenantId, store) : null;

        // lock order: the client key, the store, the DMG number, then the stock rows
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OPERATION, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body, verifiedSignerMembershipId); // L15: answer as the replay
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(DamageReportService::notFound);
        long seq = numbers.next(tenantId, DocType.DAMAGE_REPORT);
        String docNo = StoreUseSupport.render(series, tenantId, DocType.DAMAGE_REPORT, seq, today);
        StockState from = StockState.valueOf(fromState);
        int held = stock.lockBalances(tenantId, storeId, List.of(batch.productId())).stream()
                .filter(b -> b.batchId().equals(batch.id()) && b.state() == from).mapToInt(StockRepository.Bal::qty).sum();
        if (quantity > held) {
            // rule 24: no number for a caller blind at this store on a product of its open audit (0.3.6 wording)
            String message = blind.hides(caller, storeId, batch.productId()) ? BlindCount.MORE_THAN_IN_STOCK
                    : "Enter a whole number from 1 to " + held;
            throw new InvalidFieldsException(List.of(new FieldMessage("quantity", message)));
        }

        long value = batch.purchaseRate() == null ? 0 : StockMath.valuePaise(quantity, batch.purchaseRate());
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.WRITE_OFF, DOC_TYPE, id, docNo, storeId, value,
                scheduleX, value, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, id, docNo, null);
        List<StockPosting.Line> moves = new ArrayList<>();
        if (from == StockState.AVAILABLE) {
            UUID movement = UUID.randomUUID();
            moves.add(new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.AVAILABLE, -quantity,
                    "DAMAGE_QUARANTINE", in.reasonKey(), movement, null, doc, null, signer));
            moves.add(new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.QUARANTINE, quantity,
                    "DAMAGE_QUARANTINE", in.reasonKey(), movement, null, doc, null, signer));
        }
        String status = plan != null ? "WAITING_APPROVAL" : "WRITE_OFF".equals(after) ? "WRITTEN_OFF" : "SENT_TO_CS";
        if (plan == null) {
            moves.addAll(applyMoves(batch, storeId, quantity, after, in.reasonKey(), doc, signer));
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }

        reports.insert(tenantId, new NewRow(id, store.unitId(), seq, docNo, storeId, batch.productId(), batch.id(), fromState,
                quantity, in.reasonKey(), note, after, status, signer, plan == null, me));
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "REPORTED", storeId, me, null, null, null, quantity, in.reasonKey());
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
            reports.attachRequest(tenantId, id, requestId, me);
            events.insert(tenantId, store.unitId(), DOC_TYPE, id, "SUBMITTED", storeId, me, null, null, null, null, null);
        } else {
            finish(tenantId, store.unitId(), id, docNo, storeId, batch, quantity, after, in.reasonKey(), signer, me, central, null);
        }
        auditLog.record("PHARMACY_DAMAGE_REPORTED", ENTITY_TYPE, id, null, values(storeId, batch.id(), quantity, status));
        log.info("Damage reported reportId={} status={}", id, status);
        return new PharmacyViews.Outcome<>(view(caller, reports.find(tenantId, storeId, id).orElseThrow()), true);
    }

    // ---- approval handler (type WRITE_OFF, subject DAMAGE_REPORT) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = reports.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "WRITE_OFF", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        String storeName = stores.find(tenantId, row.storeId()).map(Store::name).orElse("");
        return new Summary("Damage · " + row.docNo() + " · " + storeName + " · " + row.quantity() + " units", "WRITE_OFF",
                row.storeId(), List.of("REVIEW", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.RETURN_DAMAGE);
    }

    /** A rejection needs the outcome the decider chose; an expired batch is never released to Available. */
    @Override
    public void validateDecision(Decision decision) {
        if (!"REJECTED".equals(decision.input().decision())) {
            return;
        }
        String outcome = decision.input().damageRejectOutcome();
        if (!RELEASE.equals(outcome) && !KEEP.equals(outcome)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("damageRejectOutcome", OUTCOME_NEEDED)));
        }
        if (RELEASE.equals(outcome)) {
            Row row = reports.findById(decision.tenantId(), decision.request().subjectId()).orElse(null);
            BatchRow batch = row == null ? null : stock.findBatch(decision.tenantId(), row.batchId()).orElse(null);
            if (batch != null && batch.expiryDate().isBefore(time.today())) {
                throw new InvalidFieldsException(List.of(new FieldMessage("damageRejectOutcome", NO_RELEASE_EXPIRED)));
            }
        }
    }

    /** The approval applies the wish: write off out of QUARANTINE, or send to the Central Store. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = reports.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(DamageReportService::notFound);
        if (!"WAITING_APPROVAL".equals(row.status())) {
            return;
        }
        BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow();
        if (row.secondSignerMembershipId() != null) {
            signature.recheck(tenantId, row.createdBy(), row.secondSignerMembershipId());
        }
        UUID approver = decision.approver().actorId();
        Store store = stores.find(tenantId, row.storeId()).orElseThrow(DamageReportService::notFound);
        Store central = "SEND_TO_CS".equals(row.afterApproval()) ? StoreUseSupport.centralStoreOf(stores, tenantId, store) : null;
        stock.lockBalances(tenantId, row.storeId(), List.of(row.productId()));
        StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, row.id(), row.docNo(), null);
        posting.post(new StockPosting.Actor(tenantId, row.unitId(), row.createdBy(), null, approver),
                applyMoves(batch, row.storeId(), row.quantity(), row.afterApproval(), row.reasonKey(), doc,
                        row.secondSignerMembershipId()));
        String status = "WRITE_OFF".equals(row.afterApproval()) ? "WRITTEN_OFF" : "SENT_TO_CS";
        if (!reports.decide(tenantId, row.id(), status, null, approver)) {
            throw new IllegalStateException("A damage report changed under its lock");
        }
        finish(tenantId, row.unitId(), row.id(), row.docNo(), row.storeId(), batch, row.quantity(), row.afterApproval(),
                row.reasonKey(), row.secondSignerMembershipId(), row.createdBy(), central, approver);
        auditLog.record("PHARMACY_DAMAGE_DECIDED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                values(row.storeId(), row.batchId(), row.quantity(), status));
    }

    /** A rejection releases the units to Available or keeps them in QUARANTINE, as the decider chose. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = reports.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(DamageReportService::notFound);
        if (!"WAITING_APPROVAL".equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        String outcome = decision.input().damageRejectOutcome();
        boolean release = RELEASE.equals(outcome);
        if (release) {
            BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow();
            stock.lockBalances(tenantId, row.storeId(), List.of(row.productId()));
            UUID movement = UUID.randomUUID();
            StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, row.id(), row.docNo(), null);
            posting.post(new StockPosting.Actor(tenantId, row.unitId(), row.createdBy(), null, approver), List.of(
                    new StockPosting.Line(row.storeId(), row.productId(), batch.id(), StockState.QUARANTINE, -row.quantity(),
                            "DAMAGE_RELEASE", row.reasonKey(), movement, null, doc, null),
                    new StockPosting.Line(row.storeId(), row.productId(), batch.id(), StockState.AVAILABLE, row.quantity(),
                            "DAMAGE_RELEASE", row.reasonKey(), movement, null, doc, null)));
        }
        String status = release ? "RELEASED" : "KEPT_IN_QUARANTINE";
        if (!reports.decide(tenantId, row.id(), status, release ? RELEASE : KEEP, approver)) {
            throw new IllegalStateException("A damage report changed under its lock");
        }
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), release ? "RELEASED" : "KEPT_IN_QUARANTINE", row.storeId(),
                approver, decision.actingAs(), null, null, row.quantity(), row.reasonKey());
        auditLog.record("PHARMACY_DAMAGE_DECIDED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                values(row.storeId(), row.batchId(), row.quantity(), status));
    }

    // ---- helpers ----

    /** The stock lines of the wish, out of QUARANTINE: a write-off (no signer) or a send to the Central Store (signer). */
    private List<StockPosting.Line> applyMoves(BatchRow batch, UUID storeId, int quantity, String after, String reasonKey,
                                               StockPosting.Doc doc, UUID signer) {
        if ("WRITE_OFF".equals(after)) {
            return List.of(new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.QUARANTINE, -quantity,
                    "WRITE_OFF", reasonKey, UUID.randomUUID(), null, doc, null));
        }
        UUID movement = UUID.randomUUID();
        return List.of(
                new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.QUARANTINE, -quantity,
                        "DAMAGE_SEND_TO_CS", reasonKey, movement, null, doc, null, signer),
                new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.PENDING_RETURN, quantity,
                        "DAMAGE_SEND_TO_CS", reasonKey, movement, null, doc, null, signer));
    }

    /** The return item of a send to the Central Store, and the history line of the decided report. */
    private void finish(UUID tenantId, UUID unitId, UUID id, String docNo, UUID storeId, BatchRow batch, int quantity,
                        String after, String reasonKey, UUID signer, UUID raiser, Store central, UUID approver) {
        boolean x = isScheduleX(tenantId, batch.productId());
        if ("SEND_TO_CS".equals(after)) {
            UUID itemId = UUID.randomUUID();
            returnItems.insert(tenantId, new ReturnItemRepository.NewItem(itemId, unitId, docNo, "DAMAGED", null, null, id,
                    storeId, central.id(), batch.productId(), batch.id(), quantity, reasonKey, "QUARANTINE", x, raiser));
            events.insert(tenantId, unitId, ReturnShortageService.RETURN_DOC, itemId, "RAISED", storeId, raiser, null, null, null,
                    null, reasonKey);
        }
        events.insert(tenantId, unitId, DOC_TYPE, id, "WRITE_OFF".equals(after) ? "WRITTEN_OFF" : "SENT_TO_CS", storeId,
                approver == null ? raiser : approver, null, null, null, quantity, reasonKey);
    }

    private boolean isScheduleX(UUID tenantId, UUID productId) {
        return products.find(tenantId, productId).map(f -> GoodsReceiptService.isScheduleX(f.row())).orElse(false);
    }

    private StoreUseViews.DamageReport view(PharmacyCaller caller, Row row) {
        UUID tenantId = caller.tenantId();
        Set<UUID> people = new LinkedHashSet<>();
        List<DocumentEventRepository.Event> history = events.forDocument(tenantId, DOC_TYPE, row.id());
        history.forEach(e -> {
            if (e.actorMembershipId() != null) {
                people.add(e.actorMembershipId());
            }
        });
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of() : team.findAll(tenantId, people);
        Map<UUID, Store> storeById = new java.util.HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        ProductRepository.Row p = products.find(tenantId, row.productId()).map(ProductRepository.Full::row).orElseThrow();
        BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow();
        List<StockViews.HistoryEvent> historyViews = new ArrayList<>();
        for (DocumentEventRepository.Event e : history) {
            Store eventStore = storeById.get(e.storeId());
            historyViews.add(new StockViews.HistoryEvent(e.event(), e.occurredAt(),
                    e.actorMembershipId() == null ? null : StoreUseSupport.person(e.actorMembershipId(), members), e.actingAs(),
                    eventStore == null ? null : OpeningStockPostService.storeRef(eventStore), null, e.qtyBefore(), e.qtyAfter(),
                    engine.reasonRef(tenantId, e.reasonKey(), null)));
        }
        Store store = storeById.get(row.storeId());
        return new StoreUseViews.DamageReport(row.id(), row.docNo(),
                store == null ? null : OpeningStockPostService.storeRef(store), IndentService.productRef(p),
                new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), row.quantity(),
                engine.reasonRef(tenantId, row.reasonKey(), row.note()), row.afterApproval(), row.status(), outcome(row, store),
                engine.summary(caller, DOC_TYPE, row.id()), historyViews, row.version());
    }

    private static String outcome(Row row, Store store) {
        String name = store == null ? "the store" : store.name();
        switch (row.status()) {
            case "WRITTEN_OFF":
                return "Written off at " + name;
            case "SENT_TO_CS":
                return "Sent to the Central Store";
            case "RELEASED":
                return "Released to Available";
            case "KEPT_IN_QUARANTINE":
                return "Kept in quarantine";
            default:
                return null;
        }
    }

    private static Map<String, Object> values(UUID storeId, UUID batchId, int units, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("batchId", batchId.toString());
        values.put("units", units);
        values.put("status", status);
        return values;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
