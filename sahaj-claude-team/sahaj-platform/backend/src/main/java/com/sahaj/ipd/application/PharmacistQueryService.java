package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineRules.Keyed;
import com.sahaj.ipd.application.MedicineViews.ActionOutcome;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.MedicineViews.QueryOrderInput;
import com.sahaj.ipd.application.MedicineViews.QueryReplyInput;
import com.sahaj.ipd.application.OrderLocking.Locked;
import com.sahaj.ipd.application.OrderQueryRepository.QueryRow;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-76-2 (ADR-027 D6): the pharmacist's query to the doctor and the doctor's reply. A query moves a PENDING order to
 * QUERIED (not givable, no Verify); the reply moves it back to PENDING; a change, stop or reject also closes it. No message
 * is sent anywhere (phase 1, BR-5): the doctor reads the question on the EMR row. The question and the reply are clinical
 * text: stored, shown to OWNER, DOCTOR and PHARMACIST only, never logged, audited or put in an event.
 *
 * <p>Both calls take the one admission row lock ({@link OrderLocking}) in one READ COMMITTED transaction and check the
 * version the caller saw ({@code order-changed}). Query is allowed in Read-only IPD (it makes an order not givable);
 * reply is refused there (it moves toward giving; Q6). A repeat by the same user with the same text answers 200 and
 * writes nothing (the database holds no client key for these actions, so the repeat is read from the state).
 */
@Service
public class PharmacistQueryService {

    static final String SELF_QUERY = "The doctor who ordered this cannot check it. Ask the pharmacist.";
    static final String SELF_CHANGED_QUERY = "The doctor who changed this order cannot check it. Ask the pharmacist.";

    private final MedicineOrderRepository orders;
    private final PatientAccessPort patientAccess;
    private final TeamMemberPort team;
    private final ClinicalEventRepository events;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final MedicineAssembler assembler;
    private final ScopeGuard guard;
    private final OrderQueryRepository queries;
    private final OrderLocking locking;
    private final OrderTimelineRepository timelines;

    PharmacistQueryService(MedicineOrderRepository orders, AdmissionRepository admissions,
                           PatientClinicalPort patients, PatientAccessPort patientAccess, TeamMemberPort team,
                           ClinicalEventRepository events, IpdAudit audit, IpdGate gate, MedicineAssembler assembler,
                           ScopeGuard guard, OrderQueryRepository queries, OrderTimelineRepository timelines) {
        this.timelines = timelines;
        this.orders = orders;
        this.patientAccess = patientAccess;
        this.team = team;
        this.events = events;
        this.audit = audit;
        this.gate = gate;
        this.assembler = assembler;
        this.guard = guard;
        this.queries = queries;
        this.locking = new OrderLocking(orders, admissions, patients);
    }

    /**
     * Query doctor. Check order: role (403), module Off (403), body (400), membership (403), the order and the lock (404,
     * not admitted), the orderer (403 self-verification), a repeat, status not PENDING (409 order-state), the version
     * seen (409 order-changed). Writes the open query on the current version and moves the order to QUERIED.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PharmacistActionResult query(UUID tenantId, UUID actor, String role, UUID orderId, QueryOrderInput input) {
        MedicineOrderService.requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        ModuleEntitlement ent = gate.check(tenantId, false);
        Keyed body = MedicineRules.query(input);
        MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES,
                MedicineRules.ONLY_PHARMACIST);
        Locked locked = locking.lock(tenantId, orderId, OrderLocking.NO_SCOPE_GUARD);
        OrderRow order = locked.order();
        Drug drug = assembler.drug(order.drugKey());
        if (actor.equals(order.orderedBy())) {
            throw new ForbiddenException("self-verification", "You cannot check your own order", SELF_QUERY);
        }
        // ADR-027 A9 (security M2): also the author of the current version (for version 1 the orderer, caught above).
        if (actor.equals(timelines.findTimeline(tenantId, order.admissionId(), orderId).current().createdBy())) {
            throw new ForbiddenException("self-verification", "You cannot check your own order", SELF_CHANGED_QUERY);
        }
        QueryRow open = queries.findOpen(tenantId, orderId).orElse(null);
        if (MedicineOrderRepository.QUERIED.equals(order.status()) && open != null && actor.equals(open.askedBy())
                && open.orderVersion() == body.orderVersion() && open.question().equals(body.text())) {
            return result(tenantId, actor, ent, order, drug, true);
        }
        if (!MedicineOrderRepository.PENDING.equals(order.status())) {
            throw OrderLocking.stateConflict("Order is not waiting", MedicineRules.NOT_WAITING);
        }
        if (body.orderVersion() != order.currentVersion()) {
            throw OrderLocking.orderChanged();
        }
        queries.insert(tenantId, orderId, order.currentVersion(), body.text(), actor);
        if (!orders.markQueried(tenantId, orderId)) {
            throw new IllegalStateException("The order was not queried");
        }
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_VERIFY,
                MedicineRules.queriedText(drug), actor);
        audit.record("IPD_MED_QUERIED", MedicineOrderService.ENTITY_ORDER, orderId, null, base(order));
        return result(tenantId, actor, ent, order, drug, false);
    }

    /**
     * Reply to a query. Check order: role (403), module Off (403), Read-only (409), body (400), membership (403), the
     * order and the lock (404, scope, not admitted), a repeat, status not QUERIED (409 order-state), the version seen
     * (409 order-changed). Stores the reply, closes the query (REPLY) and moves the order back to PENDING.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmrSheet reply(UUID tenantId, UUID actor, String role, UUID orderId, QueryReplyInput input) {
        MedicineOrderService.requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, true);
        Keyed body = MedicineRules.reply(input);
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES,
                MedicineRules.ONLY_DOCTOR);
        Locked locked = locking.lock(tenantId, orderId, admission -> guard.requireAdmissionLocked(tenantId, actor,
                role, memberRole, admission.id(), "medicine-reply"));
        OrderRow order = locked.order();
        QueryRow latest = queries.findLatest(tenantId, java.util.List.of(orderId)).get(orderId);
        if (MedicineOrderRepository.PENDING.equals(order.status()) && latest != null
                && OrderQueryRepository.CLOSED_BY_REPLY.equals(latest.closedBy())
                && actor.equals(latest.repliedBy()) && latest.orderVersion() == body.orderVersion()
                && body.text().equals(latest.reply())) {
            patientAccess.recordOpened(tenantId, locked.admission().patientId(), actor);
            return sheet(tenantId, role, memberRole, ent, locked);
        }
        if (!MedicineOrderRepository.QUERIED.equals(order.status())) {
            throw OrderLocking.stateConflict("Order has no open query", MedicineRules.NO_OPEN_QUERY);
        }
        if (body.orderVersion() != order.currentVersion()) {
            throw OrderLocking.orderChanged();
        }
        if (!queries.close(tenantId, orderId, OrderQueryRepository.CLOSED_BY_REPLY, body.text(), actor)) {
            throw new IllegalStateException("The order has no open query");
        }
        if (!orders.markReplied(tenantId, orderId)) {
            throw new IllegalStateException("The order was not moved back to the pharmacist");
        }
        Drug drug = assembler.drug(order.drugKey());
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_VERIFY,
                MedicineRules.queryRepliedText(drug), actor);
        audit.record("IPD_MED_QUERY_REPLIED", MedicineOrderService.ENTITY_ORDER, orderId, null, base(order));
        return sheet(tenantId, role, memberRole, ent, locked);
    }

    private static Map<String, Object> base(OrderRow order) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", order.id());
        after.put("admissionId", order.admissionId());
        after.put("drugKey", order.drugKey());
        after.put("orderVersion", order.currentVersion());
        return after;
    }

    private PharmacistActionResult result(UUID tenantId, UUID actor, ModuleEntitlement ent, OrderRow order, Drug drug,
                                          boolean replayed) {
        PharmacistChecks checks = assembler.queue(tenantId, actor, ent, MedicineRules.DEFAULT_LIMIT, null);
        return new PharmacistActionResult(new ActionOutcome(order.id(), drug.name(), "QUERIED", replayed,
                checks.verifierName(), null), checks);
    }

    private EmrSheet sheet(UUID tenantId, String role, String memberRole, ModuleEntitlement ent, Locked locked) {
        return assembler.sheet(tenantId, role, memberRole, ent, locked.admission(), locked.patient(),
                orders.findByAdmission(tenantId, locked.admission().id()), orders.databaseNow());
    }
}
