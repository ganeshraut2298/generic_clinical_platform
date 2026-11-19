package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.IndentRepository.Line;
import com.sahaj.pharmacy.application.IndentRepository.NewLine;
import com.sahaj.pharmacy.application.IndentRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.IndentStatus;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * SAH-79-9 and 79-10: indents (docs/api/pharmacy.yaml 0.3.4, ADR-029 P12). A store asks the store that issues to it for stock. The
 * draft has no number and no effect; submit takes the IND number and asks the INDENT rule (level 0 is APPROVED at once, otherwise
 * WAITING_APPROVAL and the approval engine finishes it through {@link ApprovalSubjectHandler}: the approver sets the approved
 * quantity of each line, with a remark when it is reduced). Dispatch and receipt are SAH-79-11 and 79-12.
 *
 * <p>Order of every operation: the gate, the path ids, the body, the store guard (capability {@code raiseIndent} at the requesting
 * store, {@code purchase} at the issuing store), then the document checks (409 doc-state), the client key, the validation (all
 * field errors together), and then the locks: the client key, the approval request (when one exists), the header
 * {@code FOR UPDATE} (checked against its version), the number. Lines are written before the header leaves DRAFT; every read of
 * lines is {@code not removed}. The actor of every writer is the caller's membership id ({@link PharmacyCaller#actorId()}).
 * Audit rows hold ids, counts and status only. A draft is visible to its raiser alone (404 for anyone else).
 */
@Service
public class IndentService implements ApprovalSubjectHandler {

    static final String DOC_TYPE = "INDENT";
    static final String OP_CREATE = "pharmacyCreateIndent";
    static final String OP_SUBMIT = "pharmacySubmitIndent";
    static final String NO_SUPPLIER = "This store has no supplying store. Ask your owner.";
    static final String CENTRAL_BUYS = "The Central Store buys by purchase order.";
    static final String NOT_EDITABLE = "A submitted indent cannot be edited.";
    static final String MAX_LINES_MESSAGE = "Add 1 to 200 items";
    static final String ALL_ZERO = "Every quantity is 0. Reject the indent with a reason instead.";
    private static final Logger log = LoggerFactory.getLogger(IndentService.class);
    private static final String ENTITY_TYPE = "PHARMACY_INDENT";
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;

    /** The views of the list. */
    enum View { RAISED, TO_DISPATCH }

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final IndentRepository indents;
    private final StockRepository stock;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final DocumentViews documentViews;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final IndentFlow flow;

    public IndentService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                         IndentRepository indents, StockRepository stock, ApprovalEngine engine, ApprovalRepository approvals,
                         DocumentSeriesRepository series, DocumentNumberPort numbers, ClientRequestRepository clients,
                         DocumentEventRepository events, ActiveReasonService reasons, DocumentViews documentViews,
                         TeamMemberPort team, AuditLog auditLog, PharmacyTime time, IndentFlow flow) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.indents = indents;
        this.stock = stock;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.documentViews = documentViews;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
        this.flow = flow;
    }

    // ---- list ----

    @Transactional(readOnly = true)
    public IndentViews.Page list(UUID tenantId, UUID userId, String rawStoreId, String rawView, String rawStatus, String rawPage,
                                 String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        View view = rawView == null || rawView.isEmpty() ? View.RAISED : PharmacyParams.optionalEnum("view", rawView, View.class);
        // the store guard first (contract rule 2): raised needs raiseIndent or receive at the store, to dispatch needs purchase
        Store store = view == View.TO_DISPATCH ? centralStore(caller, storeId)
                : guard.require(caller, storeId, StoreCapability.RECEIVE);
        IndentStatus status = PharmacyParams.optionalEnum("status", rawStatus, IndentStatus.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        String statusName = status == null ? null : status.name();
        IndentRepository.Slice slice = view == View.TO_DISPATCH
                ? indents.listToDispatch(tenantId, store.id(), statusName, size, page * size)
                : indents.listRaised(tenantId, store.id(), caller.actorId(), statusName, size, page * size);
        Map<UUID, Store> storeById = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        Set<UUID> people = new LinkedHashSet<>();
        slice.items().forEach(r -> people.add(r.createdBy()));
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of() : team.findAll(tenantId, people);
        Instant now = time.now();
        List<IndentViews.ListItem> items = new ArrayList<>();
        for (IndentRepository.ListRow r : slice.items()) {
            Integer age = r.raisedAt() == null ? null : (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0, Duration.between(r.raisedAt(), now).toMinutes()));
            items.add(new IndentViews.ListItem(r.id(), r.docNo(), storeRef(storeById.get(r.fromStoreId())),
                    storeRef(storeById.get(r.toStoreId())), r.itemCount(), r.priority(), r.status(),
                    person(r.createdBy(), members), r.raisedAt(), age));
        }
        return new IndentViews.Page(items, slice.total(), page, size);
    }

    // ---- get ----

    /** A current approver of the indent's request reads it without a store capability; a draft is its raiser's alone. */
    @Transactional(readOnly = true)
    public IndentViews.Indent get(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        Row row = indents.findById(tenantId, indentId).orElse(null);
        boolean onPath = row != null && (row.fromStoreId().equals(storeId) || row.toStoreId().equals(storeId));
        if (onPath && isCurrentApprover(caller, row)) {
            return view(caller, row, storeId);
        }
        // the store guard decides what the path store may do: the requesting store (any capability there) or the issuing store (purchase)
        Store pathStore = stores.find(tenantId, storeId).filter(Store::active).orElse(null);
        if (pathStore != null && row != null && row.toStoreId().equals(storeId)) {
            centralStore(caller, storeId);
        } else {
            guard.require(caller, storeId, StoreCapability.RECEIVE);
        }
        if (!onPath || IndentStatus.DRAFT.name().equals(row.status()) && !row.createdBy().equals(caller.actorId())) {
            throw notFound();
        }
        return view(caller, row, storeId);
    }

    // ---- create ----

    @Transactional
    public PharmacyViews.Outcome<IndentViews.Indent> create(UUID tenantId, UUID userId, String rawStoreId,
                                                            PharmacyInputs.Body<PharmacyInputs.IndentCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.IndentCreate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RAISE_INDENT);
        Store to = supplier(tenantId, store);
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = indents.findById(tenantId, own.subjectId()).orElseThrow(IndentService::notFound);
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
        String priority = priority(in.priority(), errors);
        List<NewLine> lines = checkLines(tenantId, in.lines(), errors);
        throwIfAny(errors);
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body); // L15: the same key committed first, answer as its replay
        }
        indents.insert(tenantId, store.unitId(), id, storeId, to.id(), priority, me);
        for (NewLine line : lines) {
            indents.insertLine(tenantId, store.unitId(), id, line, me);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_INDENT_CREATED", ENTITY_TYPE, id, null, values(storeId, to.id(), lines.size(), priority, "DRAFT"));
        log.info("Indent created indentId={} lines={}", id, lines.size());
        return new PharmacyViews.Outcome<>(view(caller, indents.findById(tenantId, id).orElseThrow(), storeId), true);
    }

    // ---- update, discard ----

    @Transactional
    public IndentViews.Indent update(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId,
                                     PharmacyInputs.Body<PharmacyInputs.IndentUpdate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        PharmacyInputs.IndentUpdate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RAISE_INDENT);
        UUID me = caller.actorId();
        ownDraftPeek(tenantId, storeId, indentId, me);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        String priority = priority(in.priority(), errors);
        List<NewLine> lines = checkLines(tenantId, in.lines(), errors);
        throwIfAny(errors);
        Row row = indents.findForUpdateById(tenantId, indentId).orElseThrow(IndentService::notFound);
        requireVersion(caller, row, version, storeId);
        if (!IndentStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState(NOT_EDITABLE);
        }
        Map<UUID, Line> live = new LinkedHashMap<>();
        indents.lines(tenantId, indentId).forEach(l -> live.put(l.productId(), l));
        Set<UUID> kept = new LinkedHashSet<>();
        for (NewLine line : lines) {
            Line existing = live.get(line.productId());
            if (existing != null) {
                indents.updateLine(tenantId, existing.id(), line, me);
                kept.add(line.productId());
            }
        }
        live.forEach((productId, line) -> {
            if (!kept.contains(productId)) {
                indents.removeLine(tenantId, line.id(), me);
            }
        });
        for (NewLine line : lines) {
            if (!live.containsKey(line.productId())) {
                indents.insertLine(tenantId, store.unitId(), indentId, line, me);
            }
        }
        if (!indents.updateDraft(tenantId, indentId, row.version(), priority, me)) {
            throw new IllegalStateException("An indent changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, indentId, "CHANGED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_INDENT_CHANGED", ENTITY_TYPE, indentId, Map.of("version", row.version()),
                values(storeId, row.toStoreId(), lines.size(), priority, "DRAFT"));
        return view(caller, indents.findById(tenantId, indentId).orElseThrow(), storeId);
    }

    @Transactional
    public IndentViews.Indent discard(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId,
                                      PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RAISE_INDENT);
        UUID me = caller.actorId();
        ownDraftPeek(tenantId, storeId, indentId, me);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        Row row = indents.findForUpdateById(tenantId, indentId).orElseThrow(IndentService::notFound);
        requireVersion(caller, row, version, storeId);
        if (!IndentStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState("Only a draft can be discarded.");
        }
        indents.setStatus(tenantId, indentId, row.version(), IndentStatus.DISCARDED.name(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, indentId, "DISCARDED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_INDENT_DISCARDED", ENTITY_TYPE, indentId, Map.of("status", "DRAFT"),
                Map.of("status", "DISCARDED"));
        return view(caller, indents.findById(tenantId, indentId).orElseThrow(), storeId);
    }

    // ---- submit ----

    @Transactional
    public IndentViews.Indent submit(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId,
                                     PharmacyInputs.Body<PharmacyInputs.VersionClientKeyInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        PharmacyInputs.VersionClientKeyInput in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RAISE_INDENT);
        UUID me = caller.actorId();
        ownDraftPeek(tenantId, storeId, indentId, me);
        ClientRequestRepository.Taken own = in.clientKey() == null ? null : clients.own(tenantId, me, in.clientKey()).orElse(null);
        if (own != null) {
            if (!OP_SUBMIT.equals(own.operation()) || !indentId.equals(own.subjectId())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            return view(caller, indents.findById(tenantId, indentId).orElseThrow(), storeId);
        }
        if (in.clientKey() != null && clients.heldByAnyone(tenantId, in.clientKey())) {
            throw PharmacyProblemException.clientKeyReused();
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_SUBMIT, indentId, true) != null) {
            return submit(tenantId, userId, rawStoreId, rawIndentId, body); // L15
        }
        Row row = indents.findForUpdateById(tenantId, indentId).orElseThrow(IndentService::notFound);
        requireVersion(caller, row, version, storeId);
        if (!IndentStatus.DRAFT.name().equals(row.status())) {
            throw PharmacyProblemException.docState("Only a draft can be submitted.");
        }
        supplier(tenantId, store);
        List<Line> lines = indents.lines(tenantId, indentId);
        List<FieldMessage> recheck = new ArrayList<>();
        checkLines(tenantId, lines.stream().map(l -> new PharmacyInputs.IndentLineInput(l.productId(),
                BigDecimal.valueOf(l.requestedQty()))).toList(), recheck);
        throwIfAny(recheck);

        long seq = numbers.next(tenantId, DocType.INDENT);
        String docNo = render(tenantId, seq, time.today());
        long amount = 0;
        long maxLine = 0;
        boolean scheduleX = false;
        for (Line l : lines) {
            ProductRepository.Row product = products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElseThrow();
            BigDecimal rate = indents.latestPurchaseRate(tenantId, l.productId()).orElse(BigDecimal.ZERO);
            long value = StockMath.valuePaise(l.requestedQty(), rate);
            amount += value;
            maxLine = Math.max(maxLine, value);
            scheduleX |= GoodsReceiptService.isScheduleX(product);
        }
        boolean urgent = "URGENT".equals(row.priority());
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.INDENT, DOC_TYPE, indentId, docNo, storeId,
                amount, scheduleX, maxLine, urgent, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        String status = plan == null ? IndentStatus.APPROVED.name() : IndentStatus.WAITING_APPROVAL.name();
        if (plan == null) {
            // level 0: the approved quantity is the requested one; lines are written before the header leaves DRAFT
            for (Line l : lines) {
                indents.setApproved(tenantId, l.id(), l.requestedQty(), null, me);
            }
        }
        if (!indents.submit(tenantId, indentId, row.version(), status, seq, docNo, me)) {
            throw new IllegalStateException("An indent changed under its lock");
        }
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
            indents.attachRequest(tenantId, indentId, requestId, me);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, indentId, "SUBMITTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("number", docNo);
        after.put("status", status);
        after.put("lineCount", lines.size());
        after.put("priority", row.priority());
        auditLog.record("PHARMACY_INDENT_SUBMITTED", ENTITY_TYPE, indentId, Map.of("status", "DRAFT"), after);
        log.info("Indent submitted indentId={} status={}", indentId, status);
        return view(caller, indents.findById(tenantId, indentId).orElseThrow(), storeId);
    }

    // ---- cancel ----

    /** The raiser at the requesting store, or a Central Store user at the issuing store, before anything is dispatched. */
    @Transactional
    public IndentViews.Indent cancel(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId,
                                     PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        PharmacyInputs.VersionReasonInput in = body.read();
        Store pathStore = stores.find(tenantId, storeId).filter(Store::active).orElseThrow(IndentService::notFound);
        boolean issuing = pathStore.type().isCentral();
        Store store = guard.require(caller, storeId, issuing ? StoreCapability.PURCHASE : StoreCapability.RAISE_INDENT);
        UUID me = caller.actorId();
        Row peek = indents.findById(tenantId, indentId).orElseThrow(IndentService::notFound);
        if (issuing ? !peek.toStoreId().equals(storeId) : !peek.fromStoreId().equals(storeId)) {
            throw notFound();
        }
        if (!issuing && !peek.createdBy().equals(me)) {
            throw guard.refuse(caller, storeId, StoreCapability.RAISE_INDENT);
        }
        if (IndentStatus.DRAFT.name().equals(peek.status()) && !peek.createdBy().equals(me)) {
            throw notFound();
        }
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        String note = reason(tenantId, in.reasonKey(), in.note(), errors);
        throwIfAny(errors);
        // lock order: the approval request (step 2) before the header (step 3)
        ApprovalRepository.Request request = peek.approvalRequestId() == null ? null
                : approvals.findForUpdate(tenantId, peek.approvalRequestId()).orElse(null);
        Row row = indents.findForUpdateById(tenantId, indentId).orElseThrow(IndentService::notFound);
        requireVersion(caller, row, version, storeId);
        boolean cancellable = IndentStatus.WAITING_APPROVAL.name().equals(row.status())
                || IndentStatus.APPROVED.name().equals(row.status());
        if (!cancellable || indents.lines(tenantId, indentId).stream().anyMatch(l -> l.dispatchedQty() > 0)) {
            throw PharmacyProblemException.docState("Only an indent with nothing dispatched can be cancelled.");
        }
        if (request != null && request.pending()) {
            approvals.finish(tenantId, request.id(), request.version(), "WITHDRAWN", me);
        }
        if (!indents.cancel(tenantId, indentId, row.version(), in.reasonKey(), note, me)) {
            throw new IllegalStateException("An indent changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, indentId, "CANCELLED", storeId, me, null, null, null, null,
                in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CANCELLED");
        after.put("reasonKey", in.reasonKey());
        auditLog.record("PHARMACY_INDENT_CANCELLED", ENTITY_TYPE, indentId, Map.of("status", row.status()), after);
        return view(caller, indents.findById(tenantId, indentId).orElseThrow(), storeId);
    }

    // ---- approval handler (79-2 engine, type INDENT; story 79-10) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = indents.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "INDENT", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        Store from = stores.find(tenantId, row.fromStoreId()).orElse(null);
        Store to = stores.find(tenantId, row.toStoreId()).orElse(null);
        int count = indents.lines(tenantId, row.id()).size();
        String what = row.docNo() + " · " + (from == null ? "" : from.name()) + " → " + (to == null ? "" : to.name()) + " · "
                + count + (count == 1 ? " item" : " items") + ("URGENT".equals(row.priority()) ? " · Urgent" : "");
        return new Summary(what, "INDENT", row.fromStoreId(), List.of("REVIEW", "REJECT"));
    }

    /** The requesting store's people (any capability there) and the issuing store's purchase capability. */
    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        if (StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.RECEIVE)) {
            return true;
        }
        Row row = indents.findById(caller.tenantId(), request.subjectId()).orElse(null);
        return row != null && StoreAccessGuard.holds(caller, row.toStoreId(), StoreCapability.PURCHASE);
    }

    /**
     * Story 79-10: the quantities of the decision. An approval may list every line once with its approved quantity (a whole number,
     * 0 or more; an increase is allowed); a reduced line, or a line set to 0, needs a remark from the approval-reject list
     * ("Other" needs a note of 4 or more characters). All lines 0 is refused: the person rejects the indent with a reason.
     */
    @Override
    public void validateDecision(Decision decision) {
        List<PharmacyInputs.IndentLineDecision> given = decision.input() == null ? null : decision.input().indentLines();
        if (given == null || !"APPROVED".equals(decision.input().decision())) {
            return;
        }
        UUID tenantId = decision.tenantId();
        List<Line> live = indents.lines(tenantId, decision.request().subjectId());
        Map<UUID, Line> byId = new HashMap<>();
        live.forEach(l -> byId.put(l.id(), l));
        List<FieldMessage> errors = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        boolean anyAbove = false;
        for (int i = 0; i < given.size(); i++) {
            PharmacyInputs.IndentLineDecision d = given.get(i);
            String at = "indentLines[" + i + "]";
            if (d == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            Line line = d.indentLineId() == null ? null : byId.get(d.indentLineId());
            if (line == null || !seen.add(d.indentLineId())) {
                errors.add(new FieldMessage(at + ".indentLineId", "Choose a line of this indent"));
                continue;
            }
            Integer qty = PharmacyParams.wholeNumber(d.approvedQty(), 0, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".approvedQty", "Enter a whole number from 0 to 999999"));
                continue;
            }
            anyAbove |= qty > 0;
            String note = d.remarkNote() == null || d.remarkNote().isBlank() ? null : d.remarkNote().trim();
            if (note != null && note.length() > 300) {
                errors.add(new FieldMessage(at + ".remarkNote", "Keep the note to 300 characters"));
            }
            if (qty < line.requestedQty()) {
                if (!reasons.isActive(tenantId, ReasonList.APPROVAL_REJECT, d.remarkReasonKey())) {
                    errors.add(new FieldMessage(at + ".remarkReasonKey", "Choose a reason"));
                } else if (d.remarkReasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
                    errors.add(new FieldMessage(at + ".remarkNote", "Write at least 4 characters"));
                }
            }
        }
        if (seen.size() != live.size() || given.size() != live.size()) {
            errors.add(new FieldMessage("indentLines", "List every line once"));
        } else if (errors.isEmpty() && !anyAbove) {
            errors.add(new FieldMessage("indentLines", ALL_ZERO));
        }
        throwIfAny(errors);
    }

    /** A first level approval with a second level to come: the quantities the approver set are saved for the next approver. */
    @Override
    public void onLevelApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = indents.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(IndentService::notFound);
        if (IndentStatus.WAITING_APPROVAL.name().equals(row.status())) {
            applyQuantities(tenantId, row, decision, false);
        }
    }

    /** The final approval makes the indent APPROVED, with the Central Store: every line has its approved quantity. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = indents.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(IndentService::notFound);
        if (!IndentStatus.WAITING_APPROVAL.name().equals(row.status())) {
            return;
        }
        applyQuantities(tenantId, row, decision, true);
        UUID approver = decision.approver().actorId();
        indents.setStatusLocked(tenantId, row.id(), IndentStatus.WAITING_APPROVAL.name(), IndentStatus.APPROVED.name(), approver);
        auditLog.record("PHARMACY_INDENT_APPROVED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "APPROVED"));
    }

    /** A rejection returns the indent to its raiser as REJECTED with the reason; no stock moves. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = indents.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(IndentService::notFound);
        if (IndentStatus.WAITING_APPROVAL.name().equals(row.status())) {
            indents.setStatusLocked(tenantId, row.id(), IndentStatus.WAITING_APPROVAL.name(), IndentStatus.REJECTED.name(),
                    decision.approver().actorId());
            auditLog.record("PHARMACY_INDENT_REJECTED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                    Map.of("status", "REJECTED"));
        }
    }

    /**
     * Writes the approved quantity of the lines the decision listed (with the remark, and the before and after quantities in the
     * history). When {@code finalApproval} every line that still has no approved quantity takes its requested one.
     */
    private void applyQuantities(UUID tenantId, Row row, Decision decision, boolean finalApproval) {
        UUID approver = decision.approver().actorId();
        List<PharmacyInputs.IndentLineDecision> given = decision.input() == null ? null : decision.input().indentLines();
        Map<UUID, PharmacyInputs.IndentLineDecision> byId = new HashMap<>();
        if (given != null) {
            given.forEach(d -> byId.put(d.indentLineId(), d));
        }
        for (Line line : indents.lines(tenantId, row.id())) {
            PharmacyInputs.IndentLineDecision d = byId.get(line.id());
            if (d != null) {
                int qty = PharmacyParams.wholeNumber(d.approvedQty(), 0, MAX_QTY);
                int before = line.approvedQty() == null ? line.requestedQty() : line.approvedQty();
                String remark = qty < line.requestedQty() ? d.remarkReasonKey() : null;
                indents.setApproved(tenantId, line.id(), qty, remark, approver);
                if (qty != before) {
                    events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "LINE_CHANGED", row.fromStoreId(), approver,
                            decision.actingAs(), line.id(), before, qty, remark);
                }
            } else if (finalApproval && line.approvedQty() == null) {
                indents.setApproved(tenantId, line.id(), line.requestedQty(), null, approver);
            }
        }
    }

    // ---- checks ----

    private String priority(String raw, List<FieldMessage> errors) {
        if ("ROUTINE".equals(raw) || "URGENT".equals(raw)) {
            return raw;
        }
        errors.add(new FieldMessage("priority", "Choose routine or urgent"));
        return "ROUTINE";
    }

    /** All the line errors together (rule 21). The quantity is a whole number of issue units from 1 to 999999. */
    private List<NewLine> checkLines(UUID tenantId, List<PharmacyInputs.IndentLineInput> lines, List<FieldMessage> errors) {
        List<PharmacyInputs.IndentLineInput> given = lines == null ? List.of() : lines;
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", MAX_LINES_MESSAGE));
        }
        List<NewLine> built = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < given.size() && given.size() <= MAX_LINES; i++) {
            PharmacyInputs.IndentLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            ProductRepository.Full full = line.productId() == null ? null : products.find(tenantId, line.productId()).orElse(null);
            boolean ok = true;
            if (full == null || !full.active()) {
                errors.add(new FieldMessage(at + ".productId", "Choose an active product"));
                ok = false;
            } else if (!seen.add(line.productId())) {
                errors.add(new FieldMessage(at + ".productId", "Already on this indent"));
                ok = false;
            }
            Integer qty = PharmacyParams.wholeNumber(line.requestedQty(), 1, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".requestedQty", "Enter a whole number from 1 to 999999"));
                ok = false;
            }
            if (ok) {
                built.add(new NewLine(line.productId(), built.size() + 1, qty));
            }
        }
        return built;
    }

    /** The store that issues to the requesting store; the Central Store itself buys by purchase order. */
    private Store supplier(UUID tenantId, Store store) {
        if (store.type().isCentral()) {
            throw PharmacyProblemException.docState(CENTRAL_BUYS);
        }
        if (store.issuesFromStoreId() == null) {
            throw PharmacyProblemException.docState(NO_SUPPLIER);
        }
        return stores.find(tenantId, store.issuesFromStoreId()).filter(Store::active)
                .orElseThrow(() -> PharmacyProblemException.docState(NO_SUPPLIER));
    }

    /** The caller's own draft or submitted indent of this requesting store; anything else is 404 (a draft is its raiser's alone). */
    private Row ownDraftPeek(UUID tenantId, UUID storeId, UUID indentId, UUID me) {
        Row row = indents.findById(tenantId, indentId).orElseThrow(IndentService::notFound);
        if (!row.fromStoreId().equals(storeId) || !row.createdBy().equals(me)) {
            throw notFound();
        }
        return row;
    }

    private String reason(UUID tenantId, String reasonKey, String note, List<FieldMessage> errors) {
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (!reasons.isActive(tenantId, ReasonList.APPROVAL_REJECT, reasonKey)) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (reasonKey.endsWith(".other") && (trimmed == null || trimmed.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (trimmed != null && trimmed.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        return trimmed;
    }

    private static Long version(BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
            return null;
        }
        return whole.longValue();
    }

    private void requireVersion(PharmacyCaller caller, Row row, Long version, UUID pathStoreId) {
        if (version == null || row.version() != version) {
            throw PharmacyProblemException.changed(view(caller, row, pathStoreId));
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
            throw PharmacyProblemException.docState("Indents are dispatched from the Central Store.");
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
        DocumentSeriesRepository.Row saved = series.find(tenantId, DocType.INDENT).orElse(null);
        if (saved == null) {
            return DocumentNumbers.render(DocType.INDENT.defaultPrefix(), DocType.INDENT.defaultFinancialYearPart(),
                    DocType.INDENT.defaultSequenceLength(), seq, today);
        }
        return DocumentNumbers.render(saved.prefix(), saved.financialYearPart(), saved.sequenceLength(), seq, today);
    }

    private static Map<String, Object> values(UUID storeId, UUID toStoreId, int lines, String priority, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("toStoreId", toStoreId.toString());
        values.put("lineCount", lines);
        values.put("priority", priority);
        values.put("status", status);
        return values;
    }

    // ---- views ----

    static StockViews.ProductRef productRef(ProductRepository.Row p) {
        return new StockViews.ProductRef(p.productId(), p.code(), p.name(), p.strength(), p.form(), p.schedule(),
                p.purchaseUnit(), p.issueUnit(), p.conversion(),
                com.sahaj.pharmacy.domain.PurchaseMath.percentText(p.gstRate()));
    }

    private static StockViews.StoreRef storeRef(Store store) {
        return store == null ? null : OpeningStockPostService.storeRef(store);
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    static String capacity(PharmacyCaller caller, Store store) {
        if (caller.isOwner()) {
            return "Owner";
        }
        StoreRole role = caller.mappingFor(store.id()).map(PharmacyCaller.Mapping::effective).orElse(null);
        if (role == null) {
            return com.sahaj.platform.api.RoleLabels.of(caller.teamRole());
        }
        switch (role) {
            case APPROVER:
                return "HOD";
            case DELEGATE:
                return "Delegate";
            case CENTRAL_STORE_USER:
                return "Central Store user";
            case STORE_USER:
            default:
                return "Store user";
        }
    }

    private IndentViews.Indent view(PharmacyCaller caller, Row row, UUID pathStoreId) {
        UUID tenantId = caller.tenantId();
        Store from = stores.find(tenantId, row.fromStoreId()).orElseThrow();
        Store to = stores.find(tenantId, row.toStoreId()).orElseThrow();
        Store acting = pathStoreId.equals(to.id()) ? to : from;
        List<Line> lines = indents.lines(tenantId, row.id());
        Map<UUID, ProductRepository.Row> productOf = new HashMap<>();
        Set<UUID> productIds = new LinkedHashSet<>();
        lines.forEach(l -> {
            productIds.add(l.productId());
            if (l.replacementProductId() != null) {
                productIds.add(l.replacementProductId());
            }
        });
        productIds.forEach(id -> products.find(tenantId, id).ifPresent(f -> productOf.put(id, f.row())));
        List<IndentViews.Line> lineViews = new ArrayList<>();
        boolean backOrder = false;
        for (Line l : lines) {
            StockRepository.StateRow fromState = null;
            StockRepository.StateRow toState = null;
            for (StockRepository.StateRow s : stock.statesOfProduct(tenantId, l.productId(), List.of(from.id(), to.id()))) {
                if (s.storeId().equals(from.id())) {
                    fromState = s;
                } else if (s.storeId().equals(to.id())) {
                    toState = s;
                }
            }
            int approved = l.approvedQty() == null ? l.requestedQty() : l.approvedQty();
            int remaining = Math.max(0, approved - l.dispatchedQty() - l.closedQty());
            boolean open = "BACK_ORDER".equals(l.balanceAction()) && remaining > 0;
            backOrder |= open;
            int inTransit = Math.max(0, l.dispatchedQty() - l.receivedQty() - l.rejectedQty() - l.shortQty());
            IndentViews.StockImpact impact = new IndentViews.StockImpact(toState == null ? 0 : toState.available(), inTransit,
                    fromState == null ? 0 : fromState.available(), fromState == null ? 0 : fromState.pendingReturn(),
                    open ? remaining : 0, l.shortQty());
            Integer hodChange = l.approvedQty() == null || l.approvedQty() == l.requestedQty() ? null
                    : l.approvedQty() - l.requestedQty();
            IndentViews.Replacement replacement = l.replacementProductId() == null || !productOf.containsKey(l.replacementProductId())
                    ? null : new IndentViews.Replacement(productRef(productOf.get(l.replacementProductId())),
                            engine.reasonRef(tenantId, l.replacementReasonKey(), null));
            lineViews.add(new IndentViews.Line(l.id(), productRef(productOf.get(l.productId())), l.requestedQty(),
                    l.approvedQty(), hodChange, engine.reasonRef(tenantId, l.hodRemarkReasonKey(), null), remaining,
                    l.dispatchedQty(), l.receivedQty(), l.rejectedQty(), l.shortQty(), l.balanceAction(),
                    engine.reasonRef(tenantId, l.closeReasonKey(), null), replacement,
                    fromState == null ? 0 : fromState.available(), toState == null ? 0 : toState.available(), impact));
        }
        DocumentViews.Loaded loaded = documentViews.load(tenantId, DOC_TYPE, row.id(), List.of(row.createdBy()));
        boolean waiting = IndentStatus.PARTLY_DISPATCHED.name().equals(row.status()) && backOrder;
        IndentViews.Chips chips = new IndentViews.Chips(flow.rejectedWaitingCs(tenantId, row.id()),
                flow.shortInvestigating(tenantId, row.id()), waiting, flow.scheduleXWaiting(tenantId, row.id()));
        return new IndentViews.Indent(row.id(), row.docNo(), storeRef(from), storeRef(to), row.priority(), row.status(), chips,
                loaded.person(row.createdBy()), row.raisedAt(),
                "Acting as " + caller.name() + " · " + capacity(caller, acting) + " · " + acting.name(), lineViews,
                engine.summary(caller, DOC_TYPE, row.id()), flow.dispatchRefs(tenantId, row.id()),
                row.cancelReasonKey() == null ? null : engine.reasonRef(tenantId, row.cancelReasonKey(), row.cancelNote()),
                documentViews.history(tenantId, loaded), row.version());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
