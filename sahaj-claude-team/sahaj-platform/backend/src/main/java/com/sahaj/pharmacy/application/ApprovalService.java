package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ApprovalRepository.Decision;
import com.sahaj.pharmacy.application.ApprovalRepository.Level;
import com.sahaj.pharmacy.application.ApprovalRepository.Request;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApproverResolver;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-2: the approvals inbox, one approval request, and the one decision endpoint of every transaction type
 * (docs/api/pharmacy.yaml 0.3.0, ADR-029 P6). The decision runs in one transaction with the request row locked first (after
 * the client key); the type's {@link ApprovalSubjectHandler} then lets the document proceed or returns it. Escalation and
 * "Needs another approver" are worked out here on read. Nobody approves their own request (also a database check, BR-13), and
 * nobody approves both levels (OQ-79-28). Audit {@code PHARMACY_APPROVAL_DECIDED} holds ids and the reason key only; reasons
 * and notes typed by a person are never logged.
 */
@Service
public class ApprovalService {

    static final String OPERATION = "pharmacyDecideApproval";
    static final String SELF_APPROVAL = "You cannot approve your own request.";
    static final String SECOND_LEVEL = "Another person must approve the second level.";
    static final String CHOOSE_REASON = "Choose a reason";
    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final Set<TransactionType> SALES = Set.of(TransactionType.SALE_DISCOUNT, TransactionType.SALES_RETURN);
    private static final Set<TransactionType> PURCHASE = Set.of(TransactionType.PURCHASE_ORDER,
            TransactionType.GRN_RATE_RISE, TransactionType.VENDOR_RETURN);
    private static final Set<TransactionType> STOCK = Set.of(TransactionType.WRITE_OFF, TransactionType.STOCK_ADJUSTMENT,
            TransactionType.STOCK_TRANSFER);

    private final PharmacyGate gate;
    private final ApprovalEngine engine;
    private final ApprovalRepository requests;
    private final Map<String, ApprovalSubjectHandler> handlers;
    private final StoreRepository stores;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public ApprovalService(PharmacyGate gate, ApprovalEngine engine, ApprovalRepository requests,
                           List<ApprovalSubjectHandler> handlers, StoreRepository stores, ClientRequestRepository clients,
                           DocumentEventRepository events, ActiveReasonService reasons, TeamMemberPort team,
                           AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.engine = engine;
        this.requests = requests;
        this.handlers = handlers.stream().collect(Collectors.toMap(ApprovalSubjectHandler::subjectType, Function.identity()));
        this.stores = stores;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- inbox ----

    @Transactional(readOnly = true)
    public StockViews.InboxPage inbox(UUID tenantId, UUID userId, String rawType, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        boolean approver = caller.isOwner() || caller.mappings().stream()
                .anyMatch(m -> m.effective() == StoreRole.APPROVER || m.effective() == StoreRole.DELEGATE);
        if (!approver) {
            throw new ForbiddenException("forbidden", "Not allowed", "You do not have access to this.");
        }
        Chip type = PharmacyParams.optionalEnum("type", rawType, Chip.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);

        List<Row> mine = new ArrayList<>();
        for (Request request : requests.pending(tenantId)) {
            if (SALES.contains(request.type())) {
                continue;
            }
            List<Level> levels = requests.levels(tenantId, request.id());
            List<Decision> decisions = requests.decisions(tenantId, request.id());
            ApproverResolver.Result approvers = engine.approvers(tenantId, request, levels, decisions);
            if (approvers.canDecide(caller.actorId())) {
                mine.add(new Row(request, levels, approvers));
            }
        }
        mine.sort(Comparator.<Row, Boolean>comparing(r -> !r.request.urgent()).thenComparing(r -> !r.approvers.escalated())
                .thenComparing(r -> r.request.levelEnteredAt()));
        int indents = (int) mine.stream().filter(r -> r.request.type() == TransactionType.INDENT).count();
        int purchase = (int) mine.stream().filter(r -> PURCHASE.contains(r.request.type())).count();
        int stock = (int) mine.stream().filter(r -> STOCK.contains(r.request.type())).count();
        List<Row> filtered = mine.stream().filter(r -> type == null || type.matches(r.request.type())).toList();

        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        Map<UUID, TeamMemberPort.TeamMember> members = team.findAll(tenantId,
                filtered.subList(from, to).stream().map(r -> r.request.raisedBy()).collect(Collectors.toSet()));
        Map<UUID, Store> storeById = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        Instant now = time.now();
        List<StockViews.InboxRow> items = new ArrayList<>();
        for (Row row : filtered.subList(from, to)) {
            items.add(inboxRow(tenantId, row, members, storeById, now));
        }
        return new StockViews.InboxPage(items, filtered.size(), page, size,
                new StockViews.InboxCounts(indents + purchase + stock, indents, purchase, stock), signedInAs(caller));
    }

    private StockViews.InboxRow inboxRow(UUID tenantId, Row row, Map<UUID, TeamMemberPort.TeamMember> members,
                                        Map<UUID, Store> storeById, Instant now) {
        Request r = row.request;
        ApprovalSubjectHandler handler = handlers.get(r.subjectType());
        ApprovalSubjectHandler.Summary summary = handler == null ? null : handler.summary(tenantId, r);
        UUID subjectStoreId = summary == null ? r.ruleStoreId() : summary.subjectStoreId();
        Store store = storeById.get(subjectStoreId);
        long minutes = Math.max(0, Duration.between(r.raisedAt(), now).toMinutes());
        String ruleText = row.levels.stream().filter(l -> l.levelNo() == r.currentLevel()).map(Level::ruleText).findFirst()
                .orElse("");
        return new StockViews.InboxRow(r.id(), r.type().name(), summary == null ? r.type().name() : summary.chip(),
                r.subjectType(), r.subjectId(), subjectStoreId, summary == null ? r.subjectNo() : summary.what(),
                r.amountPaise(), person(r.raisedBy(), members),
                store == null ? null : OpeningStockPostService.storeRef(store), r.raisedAt(), minutes, ruleText,
                r.currentLevel(), r.urgent(), row.approvers.escalated(), row.approvers.needsAnotherApprover(),
                summary == null ? List.of("REVIEW", "REJECT") : summary.actions());
    }

    // ---- one request ----

    @Transactional(readOnly = true)
    public StockViews.ApprovalRequest get(UUID tenantId, UUID userId, String rawRequestId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID requestId = PharmacyParams.pathId(rawRequestId);
        Request request = requests.find(tenantId, requestId).orElseThrow(ApprovalService::notFound);
        List<Level> levels = requests.levels(tenantId, requestId);
        List<Decision> decisions = requests.decisions(tenantId, requestId);
        ApproverResolver.Result approvers = engine.approvers(tenantId, request, levels, decisions);
        if (!canSee(caller, request, decisions, approvers)) {
            throw notFound();
        }
        return view(caller, request, levels, decisions, approvers);
    }

    private boolean canSee(PharmacyCaller caller, Request request, List<Decision> decisions, ApproverResolver.Result approvers) {
        UUID me = caller.actorId();
        return caller.isOwner() || me.equals(request.raisedBy()) || approvers.canDecide(me)
                || decisions.stream().anyMatch(d -> d.decidedBy().equals(me))
                || handlerCanRead(caller, request);
    }

    private boolean handlerCanRead(PharmacyCaller caller, Request request) {
        ApprovalSubjectHandler handler = handlers.get(request.subjectType());
        return handler != null && handler.canRead(caller, request);
    }

    // ---- decision ----

    /** Approves or rejects the current level; a replay of the same key answers the request as it is now. */
    @Transactional
    public StockViews.ApprovalRequest decide(UUID tenantId, UUID userId, String rawRequestId,
                                             PharmacyInputs.Body<PharmacyInputs.ApprovalDecisionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID requestId = PharmacyParams.pathId(rawRequestId);
        PharmacyInputs.ApprovalDecisionInput in = body.read();
        Shape shape = shape(in);

        // lock order: the client key, then the request row
        ClientRequestRepository.Taken own = clients.own(tenantId, caller.actorId(), in.clientKey()).orElse(null);
        boolean replay = false;
        if (own != null) {
            if (!OPERATION.equals(own.operation()) || !requestId.equals(own.subjectId())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            replay = true;
        } else if (clients.heldByAnyone(tenantId, in.clientKey())) {
            throw PharmacyProblemException.clientKeyReused();
        }
        Request request = requests.findForUpdate(tenantId, requestId).orElseThrow(ApprovalService::notFound);
        List<Level> levels = requests.levels(tenantId, requestId);
        List<Decision> decisions = requests.decisions(tenantId, requestId);
        if (!replay) {
            // L15: a tap of the same key that waited for the request lock has found the first one committed; read the key again
            ClientRequestRepository.Taken late = clients.own(tenantId, caller.actorId(), in.clientKey()).orElse(null);
            if (late != null) {
                if (!OPERATION.equals(late.operation()) || !requestId.equals(late.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                replay = true;
            }
        }
        if (replay) {
            return view(caller, request, levels, decisions, engine.approvers(tenantId, request, levels, decisions));
        }
        UUID me = caller.actorId();
        ApproverResolver.Result approvers = engine.approvers(tenantId, request, levels, decisions);
        boolean raiser = me.equals(request.raisedBy());
        boolean knows = raiser || caller.isOwner() || approvers.canDecide(me)
                || decisions.stream().anyMatch(d -> d.decidedBy().equals(me));
        if (!knows) {
            throw notFound();
        }
        if (!request.pending()) {
            // n-4: the contract answers 409 already-decided before anything else, for the raiser too
            throw PharmacyProblemException.alreadyDecided(view(caller, request, levels, decisions, approvers));
        }
        if (raiser) {
            throw new ForbiddenException("self-approval", "Not allowed", SELF_APPROVAL);
        }
        if (request.currentLevel() == 2 && decisions.stream().anyMatch(d -> d.levelNo() == 1
                && "APPROVED".equals(d.decision()) && d.decidedBy().equals(me))) {
            throw new ForbiddenException("self-approval", "Not allowed", SECOND_LEVEL);
        }
        if (shape.level != request.currentLevel() || shape.requestVersion != request.version()) {
            throw PharmacyProblemException.alreadyDecided(view(caller, request, levels, decisions, approvers));
        }
        if (!approvers.canDecide(me)) {
            throw notFound();
        }

        ApprovalSubjectHandler handler = handlers.get(request.subjectType());
        validateBody(tenantId, request, in, shape, handler, caller, approvers);
        if (ClientKeys.claim(clients, tenantId, request.unitId(), me, in.clientKey(), OPERATION, requestId, true) != null) {
            return decide(tenantId, userId, rawRequestId, body); // L15: the same key committed first, answer as its replay
        }

        String actingAs = approvers.actingAs(me);
        boolean approve = "APPROVED".equals(in.decision());
        requests.insertDecision(tenantId, request.unitId(), requestId, shape.level, in.decision(), me, request.raisedBy(),
                actingAs, approve ? null : in.reasonKey(), blank(in.note()));
        int nextLevel = approve ? nextApplyingLevel(levels, shape.level) : 0;
        boolean finalApproval = approve && nextLevel == 0;
        if (approve && nextLevel > 0) {
            requests.advanceToLevel(tenantId, requestId, request.version(), nextLevel, me);
        } else {
            requests.finish(tenantId, requestId, request.version(), approve ? "APPROVED" : "REJECTED", me);
        }
        Request after = requests.find(tenantId, requestId).orElseThrow();
        ApprovalSubjectHandler.Decision decision = new ApprovalSubjectHandler.Decision(tenantId, caller, after, actingAs, in);
        if (handler != null) {
            if (finalApproval) {
                handler.onApproved(decision);
            } else if (!approve) {
                handler.onRejected(decision);
            } else {
                handler.onLevelApproved(decision);
            }
        }
        events.insert(tenantId, request.unitId(), request.subjectType(), request.subjectId(), "DECIDED",
                request.ruleStoreId(), me, actingAs, null, null, null, approve ? null : in.reasonKey());
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("requestId", requestId.toString());
        afterValues.put("level", shape.level);
        afterValues.put("decision", in.decision());
        afterValues.put("actingAs", actingAs);
        if (!approve) {
            afterValues.put("reasonKey", in.reasonKey());
        }
        auditLog.record("PHARMACY_APPROVAL_DECIDED", "PHARMACY_APPROVAL_REQUEST", requestId,
                Map.of("status", "PENDING", "level", request.currentLevel()), afterValues);
        log.info("Approval decided requestId={} level={} decision={}", requestId, shape.level, in.decision());
        List<Decision> nowDecisions = requests.decisions(tenantId, requestId);
        return view(caller, after, levels, nowDecisions, engine.approvers(tenantId, after, levels, nowDecisions));
    }

    /** The checks of the body that need no data: the key, level, version and decision are present and in range. */
    private record Shape(int level, long requestVersion) {
    }

    private static Shape shape(PharmacyInputs.ApprovalDecisionInput in) {
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer level = PharmacyParams.wholeNumber(in.level(), 1, 2);
        if (level == null) {
            errors.add(new FieldMessage("level", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.requestVersion(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("requestVersion", UnitService.RELOAD));
        }
        if (!"APPROVED".equals(in.decision()) && !"REJECTED".equals(in.decision())) {
            errors.add(new FieldMessage("decision", "Choose approve or reject"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Shape(level, version);
    }

    private void validateBody(UUID tenantId, Request request, PharmacyInputs.ApprovalDecisionInput in, Shape shape,
                              ApprovalSubjectHandler handler, PharmacyCaller caller, ApproverResolver.Result approvers) {
        List<FieldMessage> errors = new ArrayList<>();
        if ("REJECTED".equals(in.decision()) && !reasons.isActive(tenantId, ReasonList.APPROVAL_REJECT, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", CHOOSE_REASON));
        }
        if (in.note() != null && in.note().length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        if (in.indentLines() != null && request.type() != TransactionType.INDENT) {
            errors.add(new FieldMessage("indentLines", "Quantities are only for an indent"));
        }
        if (in.damageRejectOutcome() != null && !(request.type() == TransactionType.WRITE_OFF
                && "DAMAGE_REPORT".equals(request.subjectType()) && "REJECTED".equals(in.decision()))) {
            errors.add(new FieldMessage("damageRejectOutcome", "The outcome is only for a rejected damage report"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (handler != null) {
            handler.validateDecision(new ApprovalSubjectHandler.Decision(tenantId, caller, request,
                    approvers.actingAs(caller.actorId()), in));
        }
    }

    private static int nextApplyingLevel(List<Level> levels, int current) {
        return levels.stream().filter(l -> l.levelNo() > current && l.applies()).map(Level::levelNo).findFirst().orElse(0);
    }

    // ---- views ----

    private StockViews.ApprovalRequest view(PharmacyCaller caller, Request request, List<Level> levels,
                                            List<Decision> decisions, ApproverResolver.Result approvers) {
        UUID tenantId = caller.tenantId();
        Set<UUID> people = new java.util.HashSet<>();
        people.add(request.raisedBy());
        decisions.forEach(d -> people.add(d.decidedBy()));
        Map<UUID, TeamMemberPort.TeamMember> members = team.findAll(tenantId, people);
        Store ruleStore = stores.find(tenantId, request.ruleStoreId()).orElse(null);
        ApprovalSubjectHandler handler = handlers.get(request.subjectType());
        UUID subjectStoreId = handler == null ? request.ruleStoreId() : handler.summary(tenantId, request).subjectStoreId();
        String lock = engine.decideLockReason(caller.actorId(), request, decisions, approvers);
        return new StockViews.ApprovalRequest(request.id(), request.type().name(), request.subjectType(),
                request.subjectId(), request.subjectNo(), subjectStoreId,
                ruleStore == null ? null : OpeningStockPostService.storeRef(ruleStore), person(request.raisedBy(), members),
                request.raisedAt(), request.amountPaise(),
                request.ruleVersionId() == null ? 0 : requests.versionNo(tenantId, request.ruleVersionId()),
                request.status(), request.currentLevel(),
                levels.stream().map(l -> new StockViews.LevelView(l.levelNo(), l.approver().name(), l.ruleText(), l.applies()))
                        .toList(),
                decisions.stream().map(d -> new StockViews.DecisionView(d.levelNo(), d.decision(), person(d.decidedBy(), members),
                        d.actingAs(), d.decidedAt(), engine.reasonRef(tenantId, d.reasonKey(), d.note()))).toList(),
                approvers.escalated(), approvers.needsAnotherApprover(), lock == null, lock, request.version());
    }

    private static String signedInAs(PharmacyCaller caller) {
        if (caller.isOwner()) {
            return "Owner";
        }
        return caller.mappings().stream()
                .filter(m -> m.effective() == StoreRole.APPROVER || m.effective() == StoreRole.DELEGATE)
                .map(m -> (m.effective() == StoreRole.APPROVER ? "HOD" : "Delegate") + " · " + m.store().name())
                .findFirst().orElse("Approver");
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

    private record Row(Request request, List<Level> levels, ApproverResolver.Result approvers) {
    }

    /** The inbox chips: ALL, INDENTS, PURCHASE, STOCK. */
    enum Chip {
        ALL(null), INDENTS(Set.of(TransactionType.INDENT)), PURCHASE(PURCHASE_TYPES()), STOCK(STOCK_TYPES());

        private final Set<TransactionType> types;

        Chip(Set<TransactionType> types) {
            this.types = types;
        }

        boolean matches(TransactionType type) {
            return types == null || types.contains(type);
        }

        private static Set<TransactionType> PURCHASE_TYPES() {
            return Set.of(TransactionType.PURCHASE_ORDER, TransactionType.GRN_RATE_RISE, TransactionType.VENDOR_RETURN);
        }

        private static Set<TransactionType> STOCK_TYPES() {
            return Set.of(TransactionType.WRITE_OFF, TransactionType.STOCK_ADJUSTMENT, TransactionType.STOCK_TRANSFER);
        }
    }
}
