package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineRules.Change;
import com.sahaj.ipd.application.MedicineRules.Keyed;
import com.sahaj.ipd.application.MedicineViews.ChangeOrderInput;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.HoldOrderInput;
import com.sahaj.ipd.application.MedicineViews.ResumeOrderInput;
import com.sahaj.ipd.application.OrderLocking.Locked;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderAction;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderLifecycle;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.ipd.domain.order.OrderVersionDiff;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-76-1 (ADR-027 D1, D4): change (a new order version), hold and resume. Same shape as stop and verify in
 * {@link MedicineOrderService}: role, module and Read-only, body, membership, then the one admission row lock
 * ({@link OrderLocking}), the not-admitted answer, the state, the version the doctor saw ({@code order-changed}: first
 * writer wins) and the writes, all in one READ COMMITTED transaction. Change and resume are refused in Read-only IPD;
 * hold is allowed (it removes a medicine, ADR-027 D12).
 *
 * <p>The database holds no client key for these three actions (docs/database/SAH-76.md), so a repeat is recognised from
 * the state: the same caller, the same version and the same values answer 200 with the sheet and write nothing but one
 * record-access row. The reason and the indication are clinical text: stored, never logged, audited or put in an event.
 */
@Service
public class OrderChangeService {

    private final MedicineOrderRepository orders;
    private final PatientAccessPort patientAccess;
    private final TeamMemberPort team;
    private final ClinicalEventRepository events;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final MedicineAssembler assembler;
    private final ScopeGuard guard;
    private final OrderVersionRepository versions;
    private final OrderTimelineRepository timelines;
    private final OrderQueryRepository queries;
    private final OrderNotices notices;
    private final NursingSettingsReader settings;
    private final OrderLocking locking;

    OrderChangeService(MedicineOrderRepository orders, AdmissionRepository admissions, PatientClinicalPort patients,
                       PatientAccessPort patientAccess, TeamMemberPort team, ClinicalEventRepository events,
                       IpdAudit audit, IpdGate gate, MedicineAssembler assembler, ScopeGuard guard,
                       OrderVersionRepository versions, OrderTimelineRepository timelines,
                       OrderQueryRepository queries, OrderNotices notices, NursingSettingsReader settings) {
        this.orders = orders;
        this.patientAccess = patientAccess;
        this.team = team;
        this.events = events;
        this.audit = audit;
        this.gate = gate;
        this.assembler = assembler;
        this.guard = guard;
        this.versions = versions;
        this.timelines = timelines;
        this.queries = queries;
        this.notices = notices;
        this.settings = settings;
        this.locking = new OrderLocking(orders, admissions, patients);
    }

    // ---- change ----

    /**
     * Change an order (new version n + 1). Check order: role, module Off, Read-only, body (400, every field error
     * together), membership, the order and the lock (404, scope, not admitted); a repeat; ON_HOLD, STOPPED or REJECTED
     * (409 order-state); the version seen ({@code order-changed}); the unit of the medicine (400); nothing changed (400).
     * Writes version n + 1 (start = the later of the sent start and the database clock; verification from the ward
     * profile in force), stamps the old version replaced, moves the head (PENDING or ACTIVE, priority, current version),
     * closes an open pharmacist query (CHANGE), and writes the notice CHANGED, the event and the audit row.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmrSheet change(UUID tenantId, UUID actor, String role, UUID orderId, ChangeOrderInput input) {
        MedicineOrderService.requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, true);
        Change body = MedicineRules.change(null, input, gate.now());
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES,
                MedicineRules.ONLY_DOCTOR);
        Locked locked = locking.lock(tenantId, orderId, admission -> guard.requireAdmissionLocked(tenantId, actor,
                role, memberRole, admission.id(), "medicine-change"));
        OrderRow order = locked.order();
        OrderTimeline timeline = timelines.findTimeline(tenantId, order.admissionId(), orderId);
        MedicineOrderVersion current = timeline.current();
        OrderValues values = body.values();

        if (isRepeatOfChange(order, current, body, actor)) {
            patientAccess.recordOpened(tenantId, locked.admission().patientId(), actor);
            return sheet(tenantId, role, memberRole, ent, locked);
        }
        OrderStatus status = OrderStatus.valueOf(order.status());
        if (status == OrderStatus.ON_HOLD) {
            throw OrderLocking.stateConflict("Order is on hold", MedicineRules.RESUME_FIRST);
        }
        if (OrderLifecycle.next(status, OrderAction.CHANGE, true).isEmpty()) {
            throw OrderLocking.stateConflict("Order is stopped", MedicineRules.ORDER_STOPPED);
        }
        if (body.orderVersion() != order.currentVersion()) {
            throw OrderLocking.orderChanged();
        }
        Drug drug = assembler.drug(order.drugKey());
        requireUnitOfDrug(drug, values);

        Instant now = orders.databaseNow();
        boolean stat = values.priority() == OrderPriority.STAT;
        MedicineOrderVersion unchangedProbe = asVersion(order, current.versionNo() + 1, values,
                values.startAt() == null ? current.startAt() : values.startAt(), body.reason(), actor, null);
        if (OrderVersionDiff.isUnchanged(current, unchangedProbe)
                && Objects.equals(OrderViews.durationDays(current), stat ? null : values.durationDays())) {
            throw new InvalidFieldsException(List.of(new FieldMessage("reason", MedicineRules.NOTHING_CHANGED)), true);
        }
        EffectiveNursingSettings nursing = settings.effectiveFor(tenantId, order.admissionId());
        Verification verification = nursing.verificationRequired() ? Verification.REQUIRED : Verification.NOT_REQUIRED;
        OrderStatus newStatus = OrderLifecycle.next(status, OrderAction.CHANGE, verification == Verification.REQUIRED)
                .orElseThrow(() -> new IllegalStateException("A change must give a status"));
        Instant requested = values.startAt() == null ? now : values.startAt();
        Instant start = stat || requested.isBefore(now) ? now : requested;
        int newNo = current.versionNo() + 1;

        versions.insert(tenantId, values.toVersion(orderId, newNo, start, body.reason(), actor, verification));
        versions.markSuperseded(tenantId, orderId, current.versionNo());
        if (!orders.markChanged(tenantId, orderId, newNo, newStatus.name(), values.priority().name())) {
            throw new IllegalStateException("The order was not changed");
        }
        queries.close(tenantId, orderId, OrderQueryRepository.CLOSED_BY_CHANGE, null, null);
        notices.record(tenantId, order.admissionId(), OrderRef.medicine(orderId, newNo), NoticeKind.CHANGED,
                values.priority(), actor);
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_ORDER,
                MedicineRules.changedText(drug, newNo), actor);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("orderVersion", current.versionNo());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", orderId);
        after.put("admissionId", order.admissionId());
        after.put("drugKey", order.drugKey());
        after.put("orderVersion", newNo);
        after.put("statusBefore", order.status());
        after.put("status", newStatus.name());
        after.put("reasonPresent", true);
        MedicineOrderVersion created = asVersion(order, newNo, values, start, body.reason(), actor, verification);
        for (OrderVersionDiff.Line line : OrderVersionDiff.diff(current, created)) {
            String key = auditKey(line.field());
            if (key != null) {
                before.put(key, line.before());
                after.put(key, line.after());
            }
        }
        after.put("verification", verification.name());
        audit.record("IPD_MED_CHANGED", MedicineOrderService.ENTITY_ORDER, orderId, before, after);
        return sheet(tenantId, role, memberRole, ent, locked);
    }

    /** The unit must be one of the medicine's units (the body check could not know the medicine yet). */
    private static void requireUnitOfDrug(Drug drug, OrderValues values) {
        if (values.doseUnit() != null && !drug.allowsUnit(values.doseUnit())) {
            throw new InvalidFieldsException(List.of(new FieldMessage("doseUnit", OrderValues.UNIT_MESSAGE)), true);
        }
    }

    /** The caller made the current version from this version with these values: a double tap or a retry. */
    private boolean isRepeatOfChange(OrderRow order, MedicineOrderVersion current, Change body, UUID actor) {
        if (current.versionNo() != body.orderVersion() + 1 || !actor.equals(current.createdBy())
                || !Objects.equals(current.changeReason(), body.reason())) {
            return false;
        }
        OrderValues v = body.values();
        MedicineOrderVersion probe = asVersion(order, current.versionNo(), v, current.startAt(), body.reason(), actor,
                current.verification());
        boolean stat = v.priority() == OrderPriority.STAT;
        return OrderVersionDiff.isUnchanged(current, probe)
                && Objects.equals(OrderViews.durationDays(current), stat ? null : v.durationDays());
    }

    /** A version value built from the checked values, for comparing with the stored one. */
    private static MedicineOrderVersion asVersion(OrderRow order, int versionNo, OrderValues v, Instant start,
                                                  String reason, UUID actor, Verification verification) {
        return new MedicineOrderVersion(order.id(), versionNo, v.kind(), v.frequency() == null ? null
                : v.frequency().key(), v.doseAmount(), v.doseUnit() == null ? null : v.doseUnit().key(),
                v.route().key(), start, v.endFor(start), v.indication(), v.priority(), v.minIntervalMin(),
                v.maxPer24h(), reason, actor, start, null, verification, null, null);
    }

    private static String auditKey(OrderVersionDiff.Field field) {
        switch (field) {
            case DOSE_AMOUNT:
                return "doseAmount";
            case DOSE_UNIT:
                return "doseUnit";
            case ROUTE:
                return "routeKey";
            case FREQUENCY:
                return "frequencyKey";
            case KIND:
                return "kind";
            case MIN_INTERVAL:
                return "prnMinIntervalMinutes";
            case MAX_PER_24H:
                return "prnMaxPer24h";
            case PRIORITY:
                return "priority";
            case START:
                return "startAt";
            case END:
                return "endAt";
            default:
                return null;
        }
    }

    // ---- hold ----

    /**
     * Hold an ACTIVE order (allowed in Read-only): an open hold row, status ON_HOLD; the slots from that moment are on
     * hold. A repeat by the same user on the same version answers 200. Notice HELD, event "Held <drug>", audit
     * IPD_MED_HELD.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmrSheet hold(UUID tenantId, UUID actor, String role, UUID orderId, HoldOrderInput input) {
        MedicineOrderService.requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, false);
        Keyed body = MedicineRules.hold(input);
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES,
                MedicineRules.ONLY_DOCTOR);
        Locked locked = locking.lock(tenantId, orderId, admission -> guard.requireAdmissionLocked(tenantId, actor,
                role, memberRole, admission.id(), "medicine-hold"));
        OrderRow order = locked.order();
        OrderTimeline timeline = timelines.findTimeline(tenantId, order.admissionId(), orderId);
        if (MedicineOrderRepository.ON_HOLD.equals(order.status()) && body.orderVersion() == order.currentVersion()
                && lastHold(timeline) != null && lastHold(timeline).isOpen()
                && actor.equals(lastHold(timeline).heldBy())) {
            patientAccess.recordOpened(tenantId, locked.admission().patientId(), actor);
            return sheet(tenantId, role, memberRole, ent, locked);
        }
        if (!MedicineOrderRepository.ACTIVE.equals(order.status())) {
            throw OrderLocking.stateConflict("Order is not active", MedicineRules.NOT_ACTIVE);
        }
        if (body.orderVersion() != order.currentVersion()) {
            throw OrderLocking.orderChanged();
        }
        versions.openHold(tenantId, orderId, actor, body.text());
        if (!orders.markHeld(tenantId, orderId)) {
            throw new IllegalStateException("The order was not put on hold");
        }
        Drug drug = assembler.drug(order.drugKey());
        notices.record(tenantId, order.admissionId(), OrderRef.medicine(orderId, order.currentVersion()),
                NoticeKind.HELD, OrderPriority.valueOf(order.priority()), actor);
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_STOP, MedicineRules.heldText(drug),
                actor);
        Map<String, Object> after = base(order);
        after.put("statusBefore", MedicineOrderRepository.ACTIVE);
        after.put("status", MedicineOrderRepository.ON_HOLD);
        after.put("reasonPresent", true);
        audit.record("IPD_MED_HELD", MedicineOrderService.ENTITY_ORDER, orderId, null, after);
        return sheet(tenantId, role, memberRole, ent, locked);
    }

    // ---- resume ----

    /**
     * Resume an ON_HOLD order (refused in Read-only): the open hold is closed, status ACTIVE, no new pharmacist check.
     * A repeat by the user who resumed it, on the same version, answers 200. Notice RESUMED, event "Resumed <drug>",
     * audit IPD_MED_RESUMED.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmrSheet resume(UUID tenantId, UUID actor, String role, UUID orderId, ResumeOrderInput input) {
        MedicineOrderService.requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, true);
        Keyed body = MedicineRules.resume(input);
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES,
                MedicineRules.ONLY_DOCTOR);
        Locked locked = locking.lock(tenantId, orderId, admission -> guard.requireAdmissionLocked(tenantId, actor,
                role, memberRole, admission.id(), "medicine-resume"));
        OrderRow order = locked.order();
        OrderTimeline timeline = timelines.findTimeline(tenantId, order.admissionId(), orderId);
        OrderHoldInterval last = lastHold(timeline);
        if (MedicineOrderRepository.ACTIVE.equals(order.status()) && body.orderVersion() == order.currentVersion()
                && last != null && !last.isOpen() && actor.equals(last.resumedBy())) {
            patientAccess.recordOpened(tenantId, locked.admission().patientId(), actor);
            return sheet(tenantId, role, memberRole, ent, locked);
        }
        if (!MedicineOrderRepository.ON_HOLD.equals(order.status())) {
            throw OrderLocking.stateConflict("Order is not on hold", MedicineRules.NOT_ON_HOLD);
        }
        if (body.orderVersion() != order.currentVersion()) {
            throw OrderLocking.orderChanged();
        }
        if (!versions.closeHold(tenantId, orderId, actor)) {
            throw new IllegalStateException("The order has no open hold");
        }
        if (!orders.markResumed(tenantId, orderId)) {
            throw new IllegalStateException("The order was not resumed");
        }
        Drug drug = assembler.drug(order.drugKey());
        notices.record(tenantId, order.admissionId(), OrderRef.medicine(orderId, order.currentVersion()),
                NoticeKind.RESUMED, OrderPriority.valueOf(order.priority()), actor);
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_ORDER,
                MedicineRules.resumedText(drug), actor);
        Map<String, Object> after = base(order);
        after.put("status", MedicineOrderRepository.ACTIVE);
        audit.record("IPD_MED_RESUMED", MedicineOrderService.ENTITY_ORDER, orderId, null, after);
        return sheet(tenantId, role, memberRole, ent, locked);
    }

    // ---- helpers ----

    private static OrderHoldInterval lastHold(OrderTimeline timeline) {
        List<OrderHoldInterval> holds = timeline.holds();
        return holds.isEmpty() ? null : holds.get(holds.size() - 1);
    }

    private static Map<String, Object> base(OrderRow order) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", order.id());
        after.put("admissionId", order.admissionId());
        after.put("drugKey", order.drugKey());
        after.put("orderVersion", order.currentVersion());
        return after;
    }

    private EmrSheet sheet(UUID tenantId, String role, String memberRole, ModuleEntitlement ent, Locked locked) {
        AdmissionRow admission = locked.admission();
        PatientClinical patient = locked.patient();
        return assembler.sheet(tenantId, role, memberRole, ent, admission, patient,
                orders.findByAdmission(tenantId, admission.id()), orders.databaseNow());
    }

}
