package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockAuditRepository.Audit;
import com.sahaj.pharmacy.application.StockAuditRepository.Line;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalConditions;
import com.sahaj.pharmacy.domain.ApproverResolver;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 * SAH-79-17: the stock audit of a store, the blind count (docs/api/pharmacy.yaml 0.3.6, ADR-029 P12, BR-30, rule 24). Ten
 * operations: list, start, get, count sheet, save counts, submit, variance review, variance reasons, recount, and "Approve and
 * post adjustment". It is also the approval handler of the subject {@code STOCK_AUDIT} (rule type STOCK_ADJUSTMENT).
 *
 * <p>The counter never sees a system quantity: the count sheet and the saved counts are built from {@link StockAuditViews.CountSheetLine},
 * which has no such field, and the book quantity of a line is read only by the review operations. Submit fixes the book quantity of
 * each counted line to the AVAILABLE balance at that moment, with the last ledger line of that store and batch. Approve and post
 * raises a STOCK_ADJUSTMENT request whose raiser is the person who submitted the count (so that person can never approve it); a
 * reviewer who is a current approver of the level also records their approval in the same transaction (one button), and a final
 * approval posts one STOCK_AUDIT_ADJUSTMENT ledger line per difference, with the submitter as the actor and the approver on the line.
 *
 * <p>Every write of the audit raises {@code stock_audit.version} by one (the count sheet shares that number). Lock order: the client
 * key, the store row, the AUD number, the audit row (an approval request first, when the operation withdraws it), then the stock rows
 * inside {@link StockPosting}. Audit rows and logs hold ids and counts only; reasons, notes and quantities are never logged.
 */
@Service
public class StockAuditService implements ApprovalSubjectHandler {

    static final String DOC_TYPE = "STOCK_AUDIT";
    static final String OP_START = "pharmacyStartStockAudit";
    static final String OP_SUBMIT = "pharmacySubmitCount";
    static final String OP_POST = "pharmacySubmitVarianceForApproval";
    static final String SELF_APPROVAL = "You cannot approve a count you submitted.";
    static final String CONSUMPTION_REASON = "stock-adjustment.consumption-not-entered";
    private static final Logger log = LoggerFactory.getLogger(StockAuditService.class);
    private static final String ENTITY_TYPE = "PHARMACY_STOCK_AUDIT";
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;
    private static final int LOCK_CHUNK = 500;
    private static final Set<String> AFTER_SUBMIT = Set.of("WAITING_REVIEW", "WAITING_APPROVAL", "CLOSED");

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final StockAuditRepository audits;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final DocumentViews documentViews;
    private final ActiveReasonService reasons;
    private final ScheduleXSignature signature;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public StockAuditService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                             StockRepository stock, StockPosting posting, StockAuditRepository audits, ApprovalEngine engine,
                             ApprovalRepository approvals, DocumentSeriesRepository series, DocumentNumberPort numbers,
                             ClientRequestRepository clients, DocumentEventRepository events, DocumentViews documentViews,
                             ActiveReasonService reasons, ScheduleXSignature signature, TeamMemberPort team, AuditLog auditLog,
                             PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.audits = audits;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.documentViews = documentViews;
        this.reasons = reasons;
        this.signature = signature;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** The list filter. */
    enum Status { COUNTING, RECOUNT, WAITING_REVIEW, WAITING_APPROVAL, CLOSED }

    // ======================================================================================================================
    // reads
    // ======================================================================================================================

    /** Newest first; rows carry the counts, and the variance figures only for a caller with reviewCount after submit. */
    @Transactional(readOnly = true)
    public StockAuditViews.Page list(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawPage,
                                     String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.COUNT);
        Status status = PharmacyParams.optionalEnum("status", rawStatus, Status.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        StockAuditRepository.Slice slice = audits.list(tenantId, storeId, status == null ? null : status.name(), size, page * size);
        List<StockAuditViews.StockAudit> items = slice.items().stream().map(a -> view(caller, a)).toList();
        return new StockAuditViews.Page(items, slice.total(), page, size);
    }

    /** The header and tiles. A current approver of its request reads it without the store capability. */
    @Transactional(readOnly = true)
    public StockAuditViews.StockAudit get(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        Audit audit = audits.find(tenantId, storeId, auditId).orElse(null);
        if (audit != null && isCurrentApprover(caller, audit)) {
            return view(caller, audit);
        }
        guard.require(caller, storeId, StoreCapability.COUNT);
        return view(caller, audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound));
    }

    /** The blind count sheet: while RECOUNT only the lines to count are answered. Never a system quantity, for any caller. */
    @Transactional(readOnly = true)
    public StockAuditViews.CountSheet countSheet(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId, String rawPage,
                                                 String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        guard.require(caller, storeId, StoreCapability.COUNT);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 200, 1, 200);
        Audit audit = audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        boolean onlyRecount = "RECOUNT".equals(audit.status());
        List<Line> lines = audits.lines(tenantId, auditId, onlyRecount, size, page * size);
        return sheet(caller, audit, lines, audits.countLines(tenantId, auditId, onlyRecount), page, size);
    }

    /** "Variance review": the lines with a difference. A current approver of the request reads it without reviewCount. */
    @Transactional(readOnly = true)
    public StockAuditViews.VarianceReview variance(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        Audit audit = reviewAccess(caller, storeId, auditId);
        if (!AFTER_SUBMIT.contains(audit.status())) {
            throw PharmacyProblemException.docState("This count has not been submitted yet.");
        }
        return varianceView(caller, audit);
    }

    // ======================================================================================================================
    // start
    // ======================================================================================================================

    /** "Start stock count": ALL_AVAILABLE or PRODUCTS; one open audit per store. 201, or 200 for a replay of the key. */
    @Transactional
    public PharmacyViews.Outcome<StockAuditViews.StockAudit> start(UUID tenantId, UUID userId, String rawStoreId,
                                                                   PharmacyInputs.Body<PharmacyInputs.StockAuditCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.StockAuditCreate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.START_COUNT);
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_START.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Audit first = audits.find(tenantId, storeId, own.subjectId()).orElseThrow(StockAuditService::notFound);
                return new PharmacyViews.Outcome<>(view(caller, first), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        boolean chosen = "PRODUCTS".equals(in.scope());
        if (!chosen && !"ALL_AVAILABLE".equals(in.scope())) {
            errors.add(new FieldMessage("scope", "Choose the whole store or some products"));
        }
        Set<UUID> productIds = new LinkedHashSet<>();
        if (chosen) {
            List<UUID> given = in.productIds() == null ? List.of() : in.productIds();
            if (given.isEmpty() || given.size() > MAX_LINES || given.stream().anyMatch(java.util.Objects::isNull)) {
                errors.add(new FieldMessage("productIds", "Choose 1 to 200 products"));
            } else {
                productIds.addAll(given);
                if (audits.knownProducts(tenantId, productIds).size() != productIds.size()) {
                    errors.add(new FieldMessage("productIds", "Choose active products"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (audits.openOf(tenantId, storeId).isPresent()) {
            throw PharmacyProblemException.auditOpen();
        }

        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_START, id, false) != null) {
            return start(tenantId, userId, rawStoreId, body); // L15: the same key committed first, answer as its replay
        }
        // lock order: the client key, the store row, the AUD number, then the header and the lines
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(StockAuditService::notFound);
        if (audits.openOf(tenantId, storeId).isPresent()) {
            throw PharmacyProblemException.auditOpen();
        }
        List<StockAuditRepository.Seed> seeds = audits.availableBatches(tenantId, storeId, chosen ? productIds : null);
        if (seeds.isEmpty()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("scope", "Nothing to count")));
        }
        long seq = numbers.next(tenantId, DocType.STOCK_AUDIT);
        String docNo = StoreUseSupport.render(series, tenantId, DocType.STOCK_AUDIT, seq, time.today());
        try {
            audits.insert(tenantId, id, store.unitId(), seq, docNo, storeId, in.scope(), me);
        } catch (DuplicateKeyException open) {
            throw PharmacyProblemException.auditOpen();
        }
        audits.insertLines(tenantId, store.unitId(), id, seeds, me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "STARTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("scope", in.scope());
        after.put("lineCount", seeds.size());
        after.put("status", "COUNTING");
        auditLog.record("PHARMACY_STOCK_AUDIT_STARTED", ENTITY_TYPE, id, null, after);
        log.info("Stock audit started auditId={} lines={}", id, seeds.size());
        return new PharmacyViews.Outcome<>(view(caller, audits.find(tenantId, storeId, id).orElseThrow()), true);
    }

    // ======================================================================================================================
    // counting
    // ======================================================================================================================

    /** Saves the counted values as the counter goes. Answers the saved lines, still blind. */
    @Transactional
    public StockAuditViews.CountSheet saveCounts(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                                 PharmacyInputs.Body<PharmacyInputs.CountsInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.CountsInput in = body.read();
        guard.require(caller, storeId, StoreCapability.COUNT);
        audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        UUID me = caller.actorId();

        List<FieldMessage> errors = new ArrayList<>();
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        List<PharmacyInputs.CountLineInput> given = in.lines() == null ? List.of() : in.lines();
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", "Send 1 to 200 lines"));
        }
        Set<UUID> ids = new LinkedHashSet<>();
        Map<UUID, Integer> counted = new HashMap<>();
        Map<UUID, String> notCountedReason = new HashMap<>();
        for (int i = 0; i < given.size() && given.size() <= MAX_LINES; i++) {
            PharmacyInputs.CountLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null || line.auditLineId() == null || !ids.add(line.auditLineId())) {
                errors.add(new FieldMessage(at + ".auditLineId", "Choose a line of this count"));
                continue;
            }
            boolean notCounted = Boolean.TRUE.equals(line.notCounted());
            if (notCounted) {
                if (line.counted() != null) {
                    errors.add(new FieldMessage(at + ".counted", "Leave the count empty when the item is not counted"));
                }
                if (!reasons.isActive(tenantId, ReasonList.STOCK_ADJUSTMENT, line.notCountedReasonKey())) {
                    errors.add(new FieldMessage(at + ".notCountedReasonKey", "Choose a reason"));
                } else {
                    notCountedReason.put(line.auditLineId(), line.notCountedReasonKey());
                }
            } else {
                Integer value = PharmacyParams.wholeNumber(line.counted(), 0, MAX_QTY);
                if (value == null) {
                    errors.add(new FieldMessage(at + ".counted", "Enter a whole number from 0 to 999999"));
                } else {
                    counted.put(line.auditLineId(), value);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        // lock order: the audit row, then its lines
        Audit audit = audits.findForUpdate(tenantId, auditId).filter(a -> a.storeId().equals(storeId))
                .orElseThrow(StockAuditService::notFound);
        if (audit.version() != version) {
            throw PharmacyProblemException.changed(sheet(caller, audit, firstLines(tenantId, audit), 0, 0, 0));
        }
        requireCounting(audit);
        List<Line> lines = audits.linesForUpdate(tenantId, auditId, ids);
        if (lines.size() != ids.size()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", "Choose a line of this count")));
        }
        if ("RECOUNT".equals(audit.status()) && lines.stream().anyMatch(l -> !l.recount())) {
            throw PharmacyProblemException.docState("This item is not open for recount.");
        }
        if (!caller.isPharmacist() && scheduleXAmong(tenantId, lines.stream().map(Line::productId).toList())) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        for (Line line : lines) {
            Integer value = counted.get(line.id());
            String reason = notCountedReason.get(line.id());
            audits.saveCount(tenantId, line.id(), value, value == null, reason, me);
        }
        if (!audits.bump(tenantId, auditId, audit.version(), me)) {
            throw new IllegalStateException("A stock audit changed under its lock");
        }
        List<Line> saved = new ArrayList<>();
        for (UUID id : ids) {
            saved.add(audits.findLine(tenantId, id).orElseThrow());
        }
        Audit now = audits.findById(tenantId, auditId).orElseThrow();
        return sheet(caller, now, saved, saved.size(), 0, saved.size());
    }

    /**
     * The second signer to verify before the submit transaction: only when the lines to submit hold a Schedule X product, only for a
     * pharmacist, and only when the key has not been used. A caller who is not a pharmacist answers 403 schedule-x-pharmacist here,
     * before any PIN is checked.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckSubmit(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                                           PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.PostWithSigner in = body.read();
        guard.require(caller, storeId, StoreCapability.COUNT);
        Audit audit = audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if (!isCounting(audit)) {
            return null;
        }
        List<Line> toFix = linesToFix(audits.allLines(tenantId, auditId));
        if (!scheduleXAmong(tenantId, toFix.stream().map(Line::productId).toList())) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** "Submit count": fixes the book quantity of each counted line now and moves the audit to WAITING_REVIEW. */
    @Transactional
    public StockAuditViews.StockAudit submit(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                             PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body,
                                             UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.PostWithSigner in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.COUNT);
        UUID me = caller.actorId();
        Audit peek = audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_SUBMIT.equals(own.operation()) || !auditId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return view(caller, peek);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (peek.version() != version) {
            throw PharmacyProblemException.changed(view(caller, peek));
        }
        requireCounting(peek);
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_SUBMIT, auditId, true) != null) {
            return submit(tenantId, userId, rawStoreId, rawAuditId, body, verifiedSignerMembershipId); // L15
        }

        // lock order: the client key, the store row, the audit row, then the balances
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(StockAuditService::notFound);
        Audit audit = audits.findForUpdate(tenantId, auditId).orElseThrow(StockAuditService::notFound);
        if (audit.version() != version) {
            throw PharmacyProblemException.changed(view(caller, audit));
        }
        requireCounting(audit);
        List<Line> all = audits.allLines(tenantId, auditId);
        long missing = all.stream().filter(l -> !l.answered()).count();
        if (missing > 0) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", missing == 1
                    ? "Count the last item or mark it not counted" : "Count the " + missing + " items left or mark them not counted")));
        }
        // the book side is cleared whenever a count is saved or sent back, so the lines to fix are the counted ones without it
        List<Line> toFix = linesToFix(all);
        if (scheduleXAmong(tenantId, toFix.stream().map(Line::productId).toList())) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
        }

        // the book quantity of each counted line is the AVAILABLE balance now, with the last ledger line of that store and batch
        Map<UUID, Integer> held = availableNow(tenantId, storeId, toFix.stream().map(Line::productId).distinct().toList());
        Instant now = time.now();
        for (Line line : toFix) {
            StockAuditRepository.LedgerMark mark = audits.lastLedger(tenantId, storeId, line.batchId()).orElse(null);
            audits.fixBook(tenantId, line.id(), held.getOrDefault(line.batchId(), 0), mark == null ? null : mark.id(),
                    mark == null ? now : mark.occurredAt(), me);
        }
        audits.moveTo(tenantId, auditId, "WAITING_REVIEW", me, me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, auditId, "SUBMITTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "WAITING_REVIEW");
        after.put("lineCount", all.size());
        after.put("counted", all.stream().filter(l -> l.countedQty() != null).count());
        after.put("notCounted", all.stream().filter(Line::notCounted).count());
        auditLog.record("PHARMACY_STOCK_AUDIT_SUBMITTED", ENTITY_TYPE, auditId, Map.of("status", audit.status()), after);
        log.info("Stock audit submitted auditId={} lines={}", auditId, all.size());
        return view(caller, audits.findById(tenantId, auditId).orElseThrow());
    }

    // ======================================================================================================================
    // review
    // ======================================================================================================================

    /** Gives each difference a reason chip (WAITING_REVIEW only). */
    @Transactional
    public StockAuditViews.VarianceReview saveVarianceReasons(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                                              PharmacyInputs.Body<PharmacyInputs.VarianceReasonsInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.VarianceReasonsInput in = body.read();
        guard.require(caller, storeId, StoreCapability.REVIEW_COUNT);
        audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        UUID me = caller.actorId();

        List<FieldMessage> errors = new ArrayList<>();
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        List<PharmacyInputs.VarianceReasonLineInput> given = in.lines() == null ? List.of() : in.lines();
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", "Send 1 to 200 lines"));
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (int i = 0; i < given.size() && given.size() <= MAX_LINES; i++) {
            PharmacyInputs.VarianceReasonLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null || line.auditLineId() == null || !ids.add(line.auditLineId())) {
                errors.add(new FieldMessage(at + ".auditLineId", "Choose a line of this count"));
                continue;
            }
            String note = StoreUseSupport.blank(line.note());
            if (!reasons.isActive(tenantId, ReasonList.STOCK_ADJUSTMENT, line.reasonKey())) {
                errors.add(new FieldMessage(at + ".reasonKey", "Choose a reason"));
            } else if (line.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
                errors.add(new FieldMessage(at + ".note", "Enter a note of at least 4 characters"));
            }
            if (note != null && note.length() > 300) {
                errors.add(new FieldMessage(at + ".note", "Keep the note to 300 characters"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        Audit audit = audits.findForUpdate(tenantId, auditId).filter(a -> a.storeId().equals(storeId))
                .orElseThrow(StockAuditService::notFound);
        if (audit.version() != version) {
            throw PharmacyProblemException.changed(varianceView(caller, audit));
        }
        if (!"WAITING_REVIEW".equals(audit.status())) {
            throw PharmacyProblemException.docState("Reasons can be given only while the count waits for review.");
        }
        List<Line> lines = audits.linesForUpdate(tenantId, auditId, ids);
        if (lines.size() != ids.size() || lines.stream().anyMatch(l -> l.difference() == null || l.difference() == 0)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", "Choose a line with a difference")));
        }
        for (PharmacyInputs.VarianceReasonLineInput line : given) {
            audits.saveVariance(tenantId, line.auditLineId(), line.reasonKey(), StoreUseSupport.blank(line.note()), me);
        }
        if (!audits.bump(tenantId, auditId, audit.version(), me)) {
            throw new IllegalStateException("A stock audit changed under its lock");
        }
        return varianceView(caller, audits.findById(tenantId, auditId).orElseThrow());
    }

    /** "Recount": sends lines back to the counter blind; a waiting approval request is withdrawn. */
    @Transactional
    public StockAuditViews.StockAudit recount(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                              PharmacyInputs.Body<PharmacyInputs.RecountInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.RecountInput in = body.read();
        Audit peek = reviewAccess(caller, storeId, auditId);
        UUID me = caller.actorId();

        List<FieldMessage> errors = new ArrayList<>();
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        List<UUID> given = in.auditLineIds() == null ? List.of() : in.auditLineIds();
        Set<UUID> ids = new LinkedHashSet<>(given);
        if (given.isEmpty() || given.size() > MAX_LINES || given.stream().anyMatch(java.util.Objects::isNull)
                || ids.size() != given.size()) {
            errors.add(new FieldMessage("auditLineIds", "Choose 1 to 200 lines of this count"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        // lock order: the approval request, then the audit row
        ApprovalRepository.Request request = peek.approvalRequestId() == null ? null
                : approvals.findForUpdate(tenantId, peek.approvalRequestId()).orElse(null);
        Audit audit = audits.findForUpdate(tenantId, auditId).orElseThrow(StockAuditService::notFound);
        if (audit.version() != version) {
            throw PharmacyProblemException.changed(view(caller, audit));
        }
        if (!"WAITING_REVIEW".equals(audit.status()) && !"WAITING_APPROVAL".equals(audit.status())) {
            throw PharmacyProblemException.docState("Only a count that waits for review or approval can be recounted.");
        }
        List<Line> lines = audits.linesForUpdate(tenantId, auditId, ids);
        if (lines.size() != ids.size()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("auditLineIds", "Choose 1 to 200 lines of this count")));
        }
        sendToRecount(tenantId, audit, request, ids, me);
        events.insert(tenantId, audit.unitId(), DOC_TYPE, auditId, "RECOUNT", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "RECOUNT");
        after.put("lineCount", ids.size());
        auditLog.record("PHARMACY_STOCK_AUDIT_RECOUNT", ENTITY_TYPE, auditId, Map.of("status", audit.status()), after);
        log.info("Stock audit sent for recount auditId={} lines={}", auditId, ids.size());
        return view(caller, audits.findById(tenantId, auditId).orElseThrow());
    }

    /** Withdraws a pending request, puts the audit in RECOUNT and returns the lines to the counter blind (the guard needs this order). */
    private void sendToRecount(UUID tenantId, Audit audit, ApprovalRepository.Request request, Collection<UUID> lineIds, UUID me) {
        if (request != null && request.pending()) {
            approvals.finish(tenantId, request.id(), request.version(), "WITHDRAWN", me);
        }
        audits.moveTo(tenantId, audit.id(), "RECOUNT", null, me);
        audits.requestRecount(tenantId, lineIds, me);
    }

    /**
     * The second signer to verify before "Approve and post": only when a difference is on a Schedule X product, only for a
     * pharmacist, and only when the key has not been used. The person who submitted the count is refused here (403 self-approval)
     * before any PIN is checked.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckPost(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                                         PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.PostWithSigner in = body.read();
        guard.require(caller, storeId, StoreCapability.REVIEW_COUNT);
        Audit audit = audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if (!"WAITING_REVIEW".equals(audit.status())) {
            return null;
        }
        if (caller.actorId().equals(audit.submittedBy())) {
            throw new ForbiddenException("self-approval", "Not allowed", SELF_APPROVAL);
        }
        List<Diff> diffs = differences(tenantId, audits.allLines(tenantId, auditId), true);
        if (diffs.stream().noneMatch(Diff::scheduleX)) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /**
     * "Approve and post adjustment": the STOCK_ADJUSTMENT rule decides (subject STOCK_AUDIT). Level 0 posts now; a reviewer who is a
     * current approver of the first level also records their approval here; otherwise the audit waits for the approver. A decrease
     * above what the batch holds now is refused (409 insufficient-stock) and sent to recount, and that change is kept.
     */
    @Transactional(noRollbackFor = PharmacyProblemException.StockShort.class)
    public StockAuditViews.StockAudit approveAndPost(UUID tenantId, UUID userId, String rawStoreId, String rawAuditId,
                                                     PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body,
                                                     UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID auditId = PharmacyParams.pathId(rawAuditId);
        PharmacyInputs.PostWithSigner in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.REVIEW_COUNT);
        UUID me = caller.actorId();
        Audit peek = audits.find(tenantId, storeId, auditId).orElseThrow(StockAuditService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_POST.equals(own.operation()) || !auditId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return view(caller, peek);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (peek.version() != version) {
            throw PharmacyProblemException.changed(view(caller, peek));
        }
        if (!"WAITING_REVIEW".equals(peek.status())) {
            throw PharmacyProblemException.docState("Only a count that waits for review can be posted.");
        }
        if (me.equals(peek.submittedBy())) {
            throw new ForbiddenException("self-approval", "Not allowed", SELF_APPROVAL);
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_POST, auditId, true) != null) {
            return approveAndPost(tenantId, userId, rawStoreId, rawAuditId, body, verifiedSignerMembershipId); // L15
        }

        // lock order: the client key, the store row, the audit row, then the stock rows
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(StockAuditService::notFound);
        Audit audit = audits.findForUpdate(tenantId, auditId).orElseThrow(StockAuditService::notFound);
        if (audit.version() != version) {
            throw PharmacyProblemException.changed(view(caller, audit));
        }
        if (!"WAITING_REVIEW".equals(audit.status())) {
            throw PharmacyProblemException.docState("Only a count that waits for review can be posted.");
        }
        List<Line> all = audits.allLines(tenantId, auditId);
        List<Diff> diffs = differences(tenantId, all, true);
        if (diffs.isEmpty()) {
            // nothing to post: every difference is zero or already posted
            audits.moveTo(tenantId, auditId, "CLOSED", null, me);
            events.insert(tenantId, audit.unitId(), DOC_TYPE, auditId, "POSTED", storeId, me, null, null, null, null, null);
            closedAudit(auditId, 0, 0L);
            return view(caller, audits.findById(tenantId, auditId).orElseThrow());
        }
        if (diffs.stream().anyMatch(d -> d.line().varianceReasonKey() == null)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", "Give every difference a reason")));
        }
        List<FailingLine> moved = new ArrayList<>();
        int rowNo = 0;
        for (Diff d : diffs) {
            rowNo++;
            if (audits.movedSince(tenantId, storeId, d.line().batchId(), d.line().bookOccurredAt(), d.line().id())) {
                moved.add(new FailingLine(d.line().id(), rowNo, d.line().productId(), null));
            }
        }
        if (!moved.isEmpty()) {
            throw PharmacyProblemException.stockMoved(moved);
        }
        boolean scheduleX = diffs.stream().anyMatch(Diff::scheduleX);
        UUID signer = audit.secondSignerMembershipId();
        if (scheduleX) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (signer == null) {
                signer = verifiedSignerMembershipId;
            }
            if (signer == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            requireSigner(tenantId, audit, all, signer);
        }
        failShortLines(tenantId, audit, diffs, null, me);

        long amount = diffs.stream().mapToLong(d -> Math.abs(d.valuePaise())).sum();
        long maxLine = diffs.stream().mapToLong(d -> Math.abs(d.valuePaise())).max().orElse(0);
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.STOCK_ADJUSTMENT, DOC_TYPE, auditId,
                audit.docNo(), storeId, amount, scheduleX, maxLine, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        if (scheduleX && audit.secondSignerMembershipId() == null) {
            audits.setSigner(tenantId, auditId, signer, me);
        }
        boolean post = plan == null;
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, audit.unitId(), audit.submittedBy(), subject, plan);
            audits.attachRequest(tenantId, auditId, requestId, null, me);
            events.insert(tenantId, audit.unitId(), DOC_TYPE, auditId, "SUBMITTED", storeId, audit.submittedBy(), null, null,
                    null, null, null);
            post = decideInline(tenantId, audit, requestId, me);
        }
        if (post) {
            postDifferences(tenantId, audit, diffs, signer, me);
            audits.moveTo(tenantId, auditId, "CLOSED", null, me);
            events.insert(tenantId, audit.unitId(), DOC_TYPE, auditId, "POSTED", storeId, me, null, null, null, null, null);
            closedAudit(auditId, diffs.size(), amount);
        } else {
            audits.moveTo(tenantId, auditId, "WAITING_APPROVAL", null, me);
        }
        log.info("Stock audit variance handled auditId={} lines={} posted={}", auditId, diffs.size(), post);
        return view(caller, audits.findById(tenantId, auditId).orElseThrow());
    }

    /**
     * The reviewer's own approval of the first level, when the reviewer is a current approver of it (the HOD, the current delegate,
     * or an owner where the rule says so): one button, as the mockup. True when that was the last level and the audit posts now.
     */
    private boolean decideInline(UUID tenantId, Audit audit, UUID requestId, UUID me) {
        ApprovalRepository.Request request = approvals.find(tenantId, requestId).orElseThrow();
        List<ApprovalRepository.Level> levels = approvals.levels(tenantId, requestId);
        List<ApprovalRepository.Decision> decisions = approvals.decisions(tenantId, requestId);
        ApproverResolver.Result approvers = engine.approvers(tenantId, request, levels, decisions);
        if (!approvers.canDecide(me)) {
            return false;
        }
        String actingAs = approvers.actingAs(me);
        int level = request.currentLevel();
        approvals.insertDecision(tenantId, audit.unitId(), requestId, level, "APPROVED", me, request.raisedBy(), actingAs, null,
                null);
        int next = levels.stream().filter(l -> l.levelNo() > level && l.applies()).map(ApprovalRepository.Level::levelNo)
                .findFirst().orElse(0);
        if (next > 0) {
            approvals.advanceToLevel(tenantId, requestId, request.version(), next, me);
        } else {
            approvals.finish(tenantId, requestId, request.version(), "APPROVED", me);
        }
        events.insert(tenantId, audit.unitId(), DOC_TYPE, audit.id(), "DECIDED", audit.storeId(), me, actingAs, null, null, null,
                null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", requestId.toString());
        after.put("level", level);
        after.put("decision", "APPROVED");
        after.put("actingAs", actingAs);
        auditLog.record("PHARMACY_APPROVAL_DECIDED", "PHARMACY_APPROVAL_REQUEST", requestId,
                Map.of("status", "PENDING", "level", level), after);
        return next == 0;
    }

    private void closedAudit(UUID auditId, int lines, long amountPaise) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CLOSED");
        after.put("lineCount", lines);
        after.put("valuePaise", amountPaise);
        auditLog.record("PHARMACY_STOCK_AUDIT_POSTED", ENTITY_TYPE, auditId, null, after);
    }

    /** The signer is another ACTIVE pharmacist or owner, not the submitter, and no one who counted a line of this audit. */
    private void requireSigner(UUID tenantId, Audit audit, List<Line> all, UUID signer) {
        signature.recheck(tenantId, audit.submittedBy(), signer);
        boolean counter = all.stream().anyMatch(l -> signer.equals(l.countedBy()));
        if (counter) {
            throw ScheduleXSignature.invalid();
        }
    }

    // ======================================================================================================================
    // posting
    // ======================================================================================================================

    /**
     * Before anything is written: a decrease above what the batch holds now cannot be posted. Those lines go back to the counter
     * blind in this same transaction (kept through {@code noRollbackFor}) and the caller is answered 409 insufficient-stock.
     */
    private void failShortLines(UUID tenantId, Audit audit, List<Diff> diffs, ApprovalRepository.Request request, UUID actor) {
        Map<UUID, Integer> held = availableNow(tenantId, audit.storeId(), diffs.stream().map(d -> d.line().productId()).distinct()
                .toList());
        List<FailingLine> failing = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>();
        int rowNo = 0;
        int firstAvailable = -1;
        for (Diff d : diffs) {
            rowNo++;
            int have = held.getOrDefault(d.line().batchId(), 0);
            if (d.diff() < 0 && -d.diff() > have) {
                failing.add(new FailingLine(d.line().id(), rowNo, d.line().productId(), have));
                ids.add(d.line().id());
                if (firstAvailable < 0) {
                    firstAvailable = have;
                }
            }
        }
        if (failing.isEmpty()) {
            return;
        }
        sendToRecount(tenantId, audit, request, ids, actor);
        events.insert(tenantId, audit.unitId(), DOC_TYPE, audit.id(), "RECOUNT", audit.storeId(), actor, null, null, null, null,
                null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "RECOUNT");
        after.put("lineCount", ids.size());
        auditLog.record("PHARMACY_STOCK_AUDIT_RECOUNT", ENTITY_TYPE, audit.id(), Map.of("status", audit.status()), after);
        throw new PharmacyProblemException.StockShort(Math.max(firstAvailable, 0), failing);
    }

    /** One STOCK_AUDIT_ADJUSTMENT line per difference, the submitter as the actor and the approver on the line. */
    private void postDifferences(UUID tenantId, Audit audit, List<Diff> diffs, UUID signer, UUID approver) {
        List<StockPosting.Line> moves = new ArrayList<>();
        int rowNo = 0;
        for (Diff d : diffs) {
            rowNo++;
            StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, audit.id(), audit.docNo(), d.line().id());
            moves.add(new StockPosting.Line(audit.storeId(), d.line().productId(), d.line().batchId(), StockState.AVAILABLE,
                    d.diff(), "STOCK_AUDIT_ADJUSTMENT", d.line().varianceReasonKey(), UUID.randomUUID(), null, doc, rowNo,
                    d.scheduleX() ? signer : null));
        }
        posting.post(new StockPosting.Actor(tenantId, audit.unitId(), audit.submittedBy(), signer, approver), moves);
        for (Diff d : diffs) {
            audits.markPosted(tenantId, d.line().id(), approver);
        }
    }

    // ======================================================================================================================
    // approval handler (79-2 engine, type STOCK_ADJUSTMENT, subject STOCK_AUDIT)
    // ======================================================================================================================

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Audit audit = audits.findById(tenantId, request.subjectId()).orElse(null);
        if (audit == null) {
            return new Summary(request.subjectNo(), "AUDIT_VARIANCE", request.ruleStoreId(), List.of("RECOUNT", "REVIEW"));
        }
        String storeName = stores.find(tenantId, audit.storeId()).map(Store::name).orElse("");
        return new Summary(audit.docNo() + " · " + storeName + " · ₹ " + ApprovalConditions.rupees(request.amountPaise()),
                "AUDIT_VARIANCE", audit.storeId(), List.of("RECOUNT", "REVIEW"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.REVIEW_COUNT);
    }

    /** The final approval posts the differences now: the submitter is the actor, the approver is on the ledger lines. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Audit audit = audits.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(StockAuditService::notFound);
        if (!"WAITING_APPROVAL".equals(audit.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        List<Line> all = audits.allLines(tenantId, audit.id());
        List<Diff> diffs = differences(tenantId, all, true);
        UUID signer = audit.secondSignerMembershipId();
        if (diffs.stream().anyMatch(Diff::scheduleX)) {
            if (signer == null) {
                throw PharmacyProblemException.docState(ScheduleXSignature.NEEDED);
            }
            // m-5 (ADR-029 P7): a refusal here rolls the whole decision back, so nothing is approved and nothing is posted
            requireSigner(tenantId, audit, all, signer);
        }
        failShortLines(tenantId, audit, diffs, null, approver);
        if (!diffs.isEmpty()) {
            postDifferences(tenantId, audit, diffs, signer, approver);
        }
        audits.setStatusFromApproval(tenantId, audit.id(), "CLOSED", approver);
        events.insert(tenantId, audit.unitId(), DOC_TYPE, audit.id(), "POSTED", audit.storeId(), approver, decision.actingAs(),
                null, null, null, null);
        closedAudit(audit.id(), diffs.size(), diffs.stream().mapToLong(d -> Math.abs(d.valuePaise())).sum());
    }

    /** A rejection returns the audit to counting; nothing is posted. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Audit audit = audits.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(StockAuditService::notFound);
        if (!"WAITING_APPROVAL".equals(audit.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        audits.setStatusFromApproval(tenantId, audit.id(), "COUNTING", approver);
        events.insert(tenantId, audit.unitId(), DOC_TYPE, audit.id(), "REJECTED", audit.storeId(), approver, decision.actingAs(),
                null, null, null, decision.input() == null ? null : decision.input().reasonKey());
        auditLog.record("PHARMACY_STOCK_AUDIT_REJECTED", ENTITY_TYPE, audit.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "COUNTING"));
    }

    // ======================================================================================================================
    // links from other documents
    // ======================================================================================================================

    /**
     * "Post as consumption instead" (SAH-79-16 with SAH-79-17): the audit line a consumption entry corrects. It must be a line of an
     * audit of this store that waits for review (its book quantity is fixed and nothing is posted yet) and not posted. The caller
     * holds the store lock. Returns the line, or null when it is not one.
     */
    Line linkableLine(UUID tenantId, UUID storeId, UUID auditLineId) {
        Line line = audits.findLine(tenantId, auditLineId).orElse(null);
        if (line == null || line.bookQty() == null || line.postedAt() != null) {
            return null;
        }
        Audit audit = audits.find(tenantId, storeId, line.auditId()).orElse(null);
        return audit != null && "WAITING_REVIEW".equals(audit.status()) ? line : null;
    }

    /** Locks the audit row of a linked line, after the store row and before the stock rows (lock order), and checks it still waits. */
    Audit lockLinked(UUID tenantId, Line line) {
        Audit audit = audits.findForUpdate(tenantId, line.auditId()).orElseThrow(StockAuditService::notFound);
        if (!"WAITING_REVIEW".equals(audit.status())) {
            throw PharmacyProblemException.docState("This count is no longer waiting for review.");
        }
        return audit;
    }

    /**
     * After the consumption posted: the units that left the audited batch lower its book quantity, and the audit's version goes up by
     * one (every write of the audit does).
     */
    void lowerBookForConsumption(UUID tenantId, Audit locked, Line line, int consumedFromBatch, UUID actor) {
        if (consumedFromBatch > 0) {
            audits.lowerBook(tenantId, line.id(), consumedFromBatch, actor);
        }
        if (!audits.bump(tenantId, locked.id(), locked.version(), actor)) {
            throw new IllegalStateException("A stock audit changed under its lock");
        }
    }

    // ======================================================================================================================
    // helpers
    // ======================================================================================================================

    /** One difference of the review: the line, its product and batch, counted minus book, and the signed value (no GST). */
    record Diff(Line line, ProductRepository.Row product, BatchRow batch, int diff, long valuePaise, boolean scheduleX) {
    }

    /** The lines with a counted quantity that differs from the book quantity; {@code unpostedOnly} leaves the posted lines out. */
    private List<Diff> differences(UUID tenantId, List<Line> lines, boolean unpostedOnly) {
        Map<UUID, ProductRepository.Row> productOf = new HashMap<>();
        Map<UUID, BatchRow> batchOf = new HashMap<>();
        List<Diff> diffs = new ArrayList<>();
        for (Line line : lines) {
            Integer difference = line.difference();
            if (difference == null || difference == 0 || unpostedOnly && line.postedAt() != null) {
                continue;
            }
            ProductRepository.Row product = productOf.computeIfAbsent(line.productId(),
                    p -> products.find(tenantId, p).map(ProductRepository.Full::row).orElseThrow());
            BatchRow batch = batchOf.computeIfAbsent(line.batchId(), b -> stock.findBatch(tenantId, b).orElseThrow());
            long value = StockMath.valuePaise(Math.abs(difference), batch.purchaseRate());
            diffs.add(new Diff(line, product, batch, difference, difference < 0 ? -value : value,
                    GoodsReceiptService.isScheduleX(product)));
        }
        return diffs;
    }

    private boolean scheduleXAmong(UUID tenantId, Collection<UUID> productIds) {
        for (UUID productId : new LinkedHashSet<>(productIds)) {
            if (products.find(tenantId, productId).map(f -> GoodsReceiptService.isScheduleX(f.row())).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** The AVAILABLE quantity per batch, read under lock (the chunks keep the statement small). */
    private Map<UUID, Integer> availableNow(UUID tenantId, UUID storeId, List<UUID> productIds) {
        Map<UUID, Integer> held = new HashMap<>();
        for (int from = 0; from < productIds.size(); from += LOCK_CHUNK) {
            List<UUID> chunk = productIds.subList(from, Math.min(from + LOCK_CHUNK, productIds.size()));
            LocalDate today = time.today();
            for (Bal bal : stock.lockBalances(tenantId, storeId, chunk)) {
                if (bal.state() == StockState.AVAILABLE) {
                    // an expired batch moves to quarantine inside the posting, so it holds nothing to decrease from
                    held.put(bal.batchId(), bal.expiryDate().isBefore(today) ? 0 : bal.qty());
                }
            }
        }
        return held;
    }

    private static boolean isCounting(Audit audit) {
        return "COUNTING".equals(audit.status()) || "RECOUNT".equals(audit.status());
    }

    private static void requireCounting(Audit audit) {
        if (!isCounting(audit)) {
            throw PharmacyProblemException.docState("This count is no longer open for counting.");
        }
    }

    /** The counted lines whose book quantity is not fixed yet: all of them at the first submit, the recounted ones after a recount. */
    private static List<Line> linesToFix(List<Line> all) {
        return all.stream().filter(l -> l.countedQty() != null && l.bookQty() == null).toList();
    }

    private List<Line> firstLines(UUID tenantId, Audit audit) {
        return audits.lines(tenantId, audit.id(), "RECOUNT".equals(audit.status()), 200, 0);
    }

    private boolean isCurrentApprover(PharmacyCaller caller, Audit audit) {
        if (audit.approvalRequestId() == null) {
            return false;
        }
        ApprovalRepository.Request request = approvals.find(caller.tenantId(), audit.approvalRequestId()).orElse(null);
        if (request == null || !request.pending()) {
            return false;
        }
        return engine.approvers(caller.tenantId(), request, approvals.levels(caller.tenantId(), request.id()),
                approvals.decisions(caller.tenantId(), request.id())).canDecide(caller.actorId());
    }

    /** reviewCount at the store, or a current approver of the audit's request; 404 for anyone else. */
    private Audit reviewAccess(PharmacyCaller caller, UUID storeId, UUID auditId) {
        Audit audit = audits.find(caller.tenantId(), storeId, auditId).orElse(null);
        if (audit != null && isCurrentApprover(caller, audit)) {
            return audit;
        }
        guard.require(caller, storeId, StoreCapability.REVIEW_COUNT);
        return audits.find(caller.tenantId(), storeId, auditId).orElseThrow(StockAuditService::notFound);
    }

    private boolean mayReadVariance(PharmacyCaller caller, Audit audit) {
        return AFTER_SUBMIT.contains(audit.status())
                && (StoreAccessGuard.holds(caller, audit.storeId(), StoreCapability.REVIEW_COUNT) || isCurrentApprover(caller, audit));
    }

    // ---- views ----

    private StockAuditViews.StockAudit view(PharmacyCaller caller, Audit audit) {
        UUID tenantId = caller.tenantId();
        Store store = stores.find(tenantId, audit.storeId()).orElse(null);
        DocumentViews.Loaded loaded = documentViews.load(tenantId, DOC_TYPE, audit.id(),
                java.util.Arrays.asList(audit.createdBy(), audit.submittedBy()));
        Integer withVariance = null;
        Long varianceValue = null;
        if (mayReadVariance(caller, audit)) {
            List<Diff> diffs = differences(tenantId, audits.allLines(tenantId, audit.id()), false);
            withVariance = diffs.size();
            varianceValue = diffs.stream().mapToLong(Diff::valuePaise).sum();
        }
        return new StockAuditViews.StockAudit(audit.id(), audit.docNo(), store == null ? null : OpeningStockPostService.storeRef(store),
                audit.status(), loaded.person(audit.createdBy()), audit.startedAt(), loaded.person(audit.submittedBy()),
                (int) audits.countLines(tenantId, audit.id(), false), (int) audits.countAnswered(tenantId, audit.id()), withVariance,
                varianceValue, engine.summary(caller, DOC_TYPE, audit.id()), documentViews.history(tenantId, loaded),
                audit.version());
    }

    /** The blind sheet of the given lines. Reads the product and batch of each line; never the book quantity. */
    private StockAuditViews.CountSheet sheet(PharmacyCaller caller, Audit audit, List<Line> lines, long total, int page, int size) {
        UUID tenantId = caller.tenantId();
        Store store = stores.find(tenantId, audit.storeId()).orElse(null);
        Map<UUID, ProductRepository.Full> productOf = new HashMap<>();
        Map<UUID, String> rackOf = new HashMap<>();
        Map<UUID, BatchRow> batchOf = new HashMap<>();
        List<StockAuditViews.CountSheetLine> items = new ArrayList<>();
        for (Line line : lines) {
            ProductRepository.Full full = productOf.computeIfAbsent(line.productId(),
                    p -> products.find(tenantId, p).orElseThrow());
            String rack = rackOf.computeIfAbsent(line.productId(), p -> products.storeSettings(tenantId, p).stream()
                    .filter(s -> s.storeId().equals(audit.storeId())).map(ProductRepository.StoreSettingRow::rack).findFirst()
                    .orElse(""));
            BatchRow batch = batchOf.computeIfAbsent(line.batchId(), b -> stock.findBatch(tenantId, b).orElseThrow());
            items.add(new StockAuditViews.CountSheetLine(line.id(), IndentService.productRef(full.row()),
                    new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), rack == null || rack.isEmpty() ? null : rack,
                    full.barcode(), line.countedQty(), line.notCounted(),
                    engine.reasonRef(tenantId, line.notCountedReasonKey(), null), line.recount()));
        }
        return new StockAuditViews.CountSheet(items, total, page, size, audit.docNo(), store == null ? "" : store.name(),
                audit.status(), (int) audits.countAnswered(tenantId, audit.id()), (int) audits.countLines(tenantId, audit.id(), false),
                audit.version());
    }

    private StockAuditViews.VarianceReview varianceView(PharmacyCaller caller, Audit audit) {
        UUID tenantId = caller.tenantId();
        Store store = stores.find(tenantId, audit.storeId()).orElse(null);
        List<Diff> diffs = differences(tenantId, audits.allLines(tenantId, audit.id()), false);
        Set<UUID> people = new LinkedHashSet<>();
        diffs.forEach(d -> {
            if (d.line().countedBy() != null) {
                people.add(d.line().countedBy());
            }
        });
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of() : team.findAll(tenantId, people);
        List<StockAuditViews.VarianceLine> lines = new ArrayList<>();
        for (Diff d : diffs) {
            Line l = d.line();
            boolean moved = l.postedAt() == null && l.bookOccurredAt() != null && !"CLOSED".equals(audit.status())
                    && audits.movedSince(tenantId, audit.storeId(), l.batchId(), l.bookOccurredAt(), l.id());
            lines.add(new StockAuditViews.VarianceLine(l.id(), IndentService.productRef(d.product()),
                    new StockViews.BatchRef(d.batch().id(), d.batch().batchNo(), d.batch().expiryDate()), l.bookQty(),
                    l.countedQty(), d.diff(), d.valuePaise(), engine.reasonRef(tenantId, l.varianceReasonKey(), l.varianceNote()),
                    StoreUseSupport.person(l.countedBy(), members), moved,
                    CONSUMPTION_REASON.equals(l.varianceReasonKey()) && d.diff() < 0, d.scheduleX()));
        }
        return new StockAuditViews.VarianceReview(audit.id(), audit.docNo(), store == null ? null : OpeningStockPostService.storeRef(store),
                audit.status(), diffs.stream().mapToLong(Diff::valuePaise).sum(), lines, audit.version());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
