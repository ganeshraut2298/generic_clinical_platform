package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.MedicineOrderRepository.NewOrder;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineRules.Order;
import com.sahaj.ipd.application.MedicineRules.Verify;
import com.sahaj.ipd.application.MedicineViews.ActionOutcome;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import com.sahaj.ipd.application.MedicineViews.OrderCheckView;
import com.sahaj.ipd.application.MedicineViews.PharmacistActionResult;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.MedicineViews.RejectOrderInput;
import com.sahaj.ipd.application.MedicineViews.VerifyOrderInput;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Finding;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import com.sahaj.ipd.domain.OverrideReason;
import com.sahaj.ipd.domain.RejectReason;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-31 the EMR sheet and the medicine order lifecycle: place, stop, verify and reject (ADR-009 D1, D4, D5, D10).
 * Every call first re-checks the role in the token, then the IPD state, then the body, then reads the role again from
 * the caller's active membership (a role change applies at once, not when the access token expires).
 *
 * <p>Every write runs in one explicit READ COMMITTED transaction and takes exactly one lock: the admission row,
 * {@code for no key update}, the bare row first (no IPD advisory lock, no bed lock). Discharge and transfer take the
 * same row {@code for update}, so a discharge and a medicine action never interleave. The safety check runs on the
 * server on every request through {@link com.sahaj.ipd.domain.MedicineSafetyCheck}; a hard stop is never trusted to the
 * client. A refusal by the partial unique index, a database check or the guard trigger is a code fault: it is never
 * swallowed, the whole transaction rolls back and the caller sees a generic 500.
 *
 * <p>Verify has two transactions with the PIN check between them (see {@link PharmacistCheckService#verify}): the PIN
 * must be checked outside any transaction, and its own platform transaction must commit before the IPD transaction
 * starts, so a wrong try is counted and no platform lock is held with the admission lock.
 *
 * <p>The allergy text, names, warnings and the PIN are never logged, audited or put in a clinical event; the audit
 * rows carry ids and fixed keys, the events carry fixed templates with medicine names (ADR-009 D7).
 */
@Service
public class MedicineOrderService {

    static final String ENTITY_ORDER = "IPD_MEDICINE_ORDER";
    static final String ENTITY_ADMISSION = "IPD_ADMISSION";
    static final String STATE_PROBLEM = "order-state";
    static final String CLIENT_KEY_CONSTRAINT = "uq_medicine_order_tenant_client_key";
    static final String LIVE_CONSTRAINT = "ux_medicine_order_tenant_admission_drug_live";
    private static final String NAME_FALLBACK = "Team member";
    private static final Logger log = LoggerFactory.getLogger(MedicineOrderService.class);

    private final MedicineOrderRepository orders;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final IpdGate gate;
    private final MedicineCatalogue catalogue;
    private final MedicineAssembler assembler;
    private final ScopeGuard guard;

    MedicineOrderService(MedicineOrderRepository orders, AdmissionRepository admissions,
                         ClinicalEventRepository events, PatientClinicalPort patients,
                         PatientAccessPort patientAccess, ActorDirectoryPort actors, TeamMemberPort team,
                         AuditLog auditLog, IpdGate gate, MedicineCatalogue catalogue, MedicineAssembler assembler,
                         ScopeGuard guard) {
        this.guard = guard;
        this.orders = orders;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.team = team;
        this.auditLog = auditLog;
        this.gate = gate;
        this.catalogue = catalogue;
        this.assembler = assembler;
    }

    // ---- the EMR sheet ----

    /**
     * Opens the EMR sheet. Check order: role (403), module Off (403), then in the transaction the role read again from
     * the membership (403), admission (404), patient (404); then one clinical event (kind view) and one
     * record-access row. Allowed in Read-only and for a discharged admission. Takes no lock. A refused call writes
     * nothing. The read-write transaction is on purpose: opening the record writes.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmrSheet emr(UUID tenantId, UUID actor, String role, UUID admissionId) {
        requireRole(role, MedicineRules.EMR_ROLES, MedicineRules.EMR_HIDDEN);
        ModuleEntitlement ent = gate.check(tenantId, false);
        String memberRole = membershipRole(team, tenantId, actor, MedicineRules.EMR_ROLES, MedicineRules.EMR_HIDDEN);
        AdmissionRow admission = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        PatientClinical patient = patient(tenantId, admission);
        // SAH-34: after the 404 checks, before the record-access row and the view entry (a refusal writes nothing).
        guard.requireAdmission(tenantId, actor, role, memberRole, admissionId, "emr-sheet");
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        events.insert(tenantId, admissionId, ClinicalEventRepository.KIND_VIEW, MedicineRules.OPENED_TEXT, actor);
        return sheetNow(tenantId, role, memberRole, ent, admission, patient);
    }

    // ---- place an order ----

    /**
     * Check and order, and Order with this reason (ADR-009 D4). Check order: role, module Off, Read-only, body; then
     * the transaction: membership, the admission lock (404), patient (404), repeat of the client key, not admitted
     * (409), the safety check. A hard stop answers 200 BLOCKED (event and audit written); warnings without the reason
     * and the current token answer 200 NEEDS_REASON (one record-access row); otherwise the order is stored PENDING.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MedicineOrderAnswer order(UUID tenantId, UUID actor, String role, UUID admissionId,
                                     MedicineOrderInput input) {
        requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, true);
        Order body = MedicineRules.order(catalogue.catalogue(), input);
        String memberRole = membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);

        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        PatientClinical patient = patient(tenantId, admission);
        // SAH-34: under the admission lock, after the 404 checks and before the repeat and state checks.
        guard.requireAdmissionLocked(tenantId, actor, role, memberRole, admissionId, "medicine-order");

        OrderRow first = orders.findByClientKey(tenantId, body.clientKey()).orElse(null);
        if (first != null) {
            return replay(tenantId, actor, role, memberRole, ent, admission, patient, body, first);
        }
        if (!"ADMITTED".equals(admission.status())) {
            throw notAdmitted();
        }
        List<OrderRow> chart = orders.findByAdmission(tenantId, admissionId);
        OrderCheck check = assembler.safety().check(body.drug(), patient.allergyText(),
                MedicineAssembler.liveKeys(chart, null));

        OrderCall call = new OrderCall(tenantId, actor, role, memberRole, ent, admission, patient);
        if (check.blocked()) {
            return blocked(call, body, check, chart);
        }
        if (check.needsReason() && (body.reason() == null || !check.checkToken().equals(body.checkToken()))) {
            return needsReason(call, check, chart);
        }
        return place(call, body, check);
    }

    /**
     * What the EMR's "Check before completing" needs (SAH-67-1, ADR-025 D8): the same checks as {@link #order}, with
     * SAH-31's own handling of a hard stop (deny event and audit row) and of a warning (record-access row), but nothing is
     * ordered. {@code outcome} is PASS, NEEDS_REASON, BLOCKED or ORDERED (the client key already placed an order).
     */
    record CheckOnly(String outcome, OrderCheck check, Drug drug, Frequency frequency) {

        @Override
        public String toString() {
            return "CheckOnly[outcome=" + outcome + "]";
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    CheckOnly checkOnly(UUID tenantId, UUID actor, String role, UUID admissionId, MedicineOrderInput input) {
        return checkOnlyNow(tenantId, actor, role, admissionId, input);
    }

    private CheckOnly checkOnlyNow(UUID tenantId, UUID actor, String role, UUID admissionId, MedicineOrderInput input) {
        requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, true);
        Order body = MedicineRules.order(catalogue.catalogue(), input);
        String memberRole = membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        AdmissionRow admission = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        PatientClinical patient = patient(tenantId, admission);
        guard.requireAdmission(tenantId, actor, role, memberRole, admissionId, "medicine-order");
        if (orders.findByClientKey(tenantId, body.clientKey()).isPresent()) {
            return new CheckOnly("ORDERED", null, body.drug(), body.frequency());
        }
        if (!"ADMITTED".equals(admission.status())) {
            throw notAdmitted();
        }
        List<OrderRow> chart = orders.findByAdmission(tenantId, admissionId);
        OrderCheck check = assembler.safety().check(body.drug(), patient.allergyText(),
                MedicineAssembler.liveKeys(chart, null));
        OrderCall call = new OrderCall(tenantId, actor, role, memberRole, ent, admission, patient);
        if (check.blocked()) {
            recordBlocked(call, body, check);
            return new CheckOnly("BLOCKED", check, body.drug(), body.frequency());
        }
        if (check.needsReason()) {
            patientAccess.recordOpened(tenantId, admission.patientId(), actor);
            return new CheckOnly("NEEDS_REASON", check, body.drug(), body.frequency());
        }
        return new CheckOnly("PASS", check, body.drug(), body.frequency());
    }

    /** The values every step of {@link #order} needs; the patient is clinical data and leaves {@code toString}. */
    private record OrderCall(UUID tenantId, UUID actor, String role, String memberRole, ModuleEntitlement ent,
                             AdmissionRow admission, PatientClinical patient) {

        @Override
        public String toString() {
            return "OrderCall[admissionId=" + admission.id() + "]";
        }
    }

    /** Step 1, a hard stop: nothing is stored; one denied event and one audit row; the answer is 200 BLOCKED. */
    private MedicineOrderAnswer blocked(OrderCall call, Order body, OrderCheck check, List<OrderRow> chart) {
        recordBlocked(call, body, check);
        return answer("BLOCKED", false, check, null, sheet(call, chart));
    }

    /** The deny clinical event and the IPD_MED_ORDER_BLOCKED audit row of a hard stop (also used by the EMR check). */
    private void recordBlocked(OrderCall call, Order body, OrderCheck check) {
        UUID admissionId = call.admission().id();
        events.insert(call.tenantId(), admissionId, ClinicalEventRepository.KIND_DENY,
                MedicineRules.blockedText(body.drug(), check.firstHardStopLogged()), call.actor());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", admissionId);
        after.put("drugKey", body.drug().key());
        after.put("frequencyKey", body.frequency().key());
        after.put("status", "BLOCKED");
        auditLog.record("IPD_MED_ORDER_BLOCKED", ENTITY_ADMISSION, admissionId, null, after);
    }

    /** Step 2, warnings without the reason or with a stale token: nothing is stored; one record-access row. */
    private MedicineOrderAnswer needsReason(OrderCall call, OrderCheck check, List<OrderRow> chart) {
        patientAccess.recordOpened(call.tenantId(), call.admission().patientId(), call.actor());
        return answer("NEEDS_REASON", false, check, null, sheet(call, chart));
    }

    /** Step 3, the order is stored PENDING with its warning lines, one event and one audit row. */
    private MedicineOrderAnswer place(OrderCall call, Order body, OrderCheck check) {
        UUID tenantId = call.tenantId();
        UUID admissionId = call.admission().id();
        OverrideReason reason = check.warnings().isEmpty() ? null : body.reason();
        List<String> lines = new ArrayList<>();
        for (Finding warning : check.warnings()) {
            lines.add(warning.warningLine());
        }
        OrderRow stored;
        try {
            stored = orders.insert(tenantId, new NewOrder(admissionId, body.clientKey(), body.drug().key(),
                    body.frequency().key(), call.actor(), reason == null ? null : reason.key(), lines));
        } catch (DuplicateKeyException e) {
            // The admission lock and the duplicate rule answered first; a clash means the state is wrong.
            log.error("Medicine order insert clash tenantId={} admissionId={} constraints={},{}", tenantId,
                    admissionId, LIVE_CONSTRAINT, CLIENT_KEY_CONSTRAINT);
            throw new IllegalStateException("The order could not be stored", e);
        }
        events.insert(tenantId, admissionId, ClinicalEventRepository.KIND_ORDER,
                MedicineRules.orderedText(body.drug(), body.frequency(), reason), call.actor());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", stored.id());
        after.put("admissionId", admissionId);
        after.put("drugKey", stored.drugKey());
        after.put("frequencyKey", stored.frequencyKey());
        after.put("status", MedicineOrderRepository.PENDING);
        after.put("overrideReasonKey", stored.overrideReasonKey());
        after.put("warningCount", lines.size());
        auditLog.record("IPD_MED_ORDERED", ENTITY_ORDER, stored.id(), null, after);

        EmrSheet sheet = sheetNow(tenantId, call.role(), call.memberRole(), call.ent(), call.admission(),
                call.patient());
        return answer("PLACED", false, check, orderRow(sheet, stored.id()), sheet);
    }

    private EmrSheet sheet(OrderCall call, List<OrderRow> chart) {
        return sheet(call.tenantId(), call.role(), call.memberRole(), call.ent(), call.admission(), call.patient(),
                chart);
    }

    /**
     * A repeat of a client key. Another admission's key is a 400; a different medicine, frequency or reason is a 409.
     * The reason is compared only when the first request stored one: a reason sent with no warning is ignored and not
     * stored (ADR-009 D4), so an identical retry of that request must not be refused. The answer is the sheet, so one
     * record-access row is written and nothing else.
     */
    private MedicineOrderAnswer replay(UUID tenantId, UUID actor, String role, String memberRole,
                                       ModuleEntitlement ent, AdmissionRow admission, PatientClinical patient,
                                       Order body, OrderRow first) {
        if (!first.admissionId().equals(admission.id())) {
            throw new InvalidInputException("clientKey", MedicineRules.RELOAD_MESSAGE);
        }
        boolean sameReason = first.overrideReasonKey() == null
                || (body.reason() != null && first.overrideReasonKey().equals(body.reason().key()));
        if (!first.drugKey().equals(body.drug().key()) || !first.frequencyKey().equals(body.frequency().key())
                || !sameReason) {
            throw new ConflictException("client-key-reused", "Client key already used",
                    MedicineRules.CLIENT_KEY_REUSED);
        }
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        List<OrderRow> chart = orders.findByAdmission(tenantId, admission.id());
        OrderCheck check = assembler.safety().check(assembler.drug(first.drugKey()), patient.allergyText(),
                MedicineAssembler.liveKeys(chart, first.id()));
        EmrSheet sheet = sheet(tenantId, role, memberRole, ent, admission, patient, chart);
        return answer("PLACED", true, check, orderRow(sheet, first.id()), sheet);
    }

    // ---- stop ----

    /**
     * Stop (ADR-009 D1, D5): PENDING or ACTIVE to STOPPED by OWNER or DOCTOR, allowed in Read-only. A repeat by the
     * same user answers 200 with one record-access row; stopped by someone else or rejected is 409 order-state.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmrSheet stop(UUID tenantId, UUID actor, String role, UUID orderId) {
        requireRole(role, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        ModuleEntitlement ent = gate.check(tenantId, false);
        String memberRole = membershipRole(team, tenantId, actor, MedicineRules.ORDER_ROLES, MedicineRules.ONLY_DOCTOR);
        // SAH-34: the order's admission is found first (another clinic's id is 404) and checked under its lock, before
        // the "not admitted" answer.
        Locked locked = lock(tenantId, orderId, admission -> guard.requireAdmissionLocked(tenantId, actor, role,
                memberRole, admission.id(), "medicine-stop"));
        OrderRow order = locked.order();
        if (order.live()) {
            if (!orders.markStopped(tenantId, orderId, actor)) {
                throw new IllegalStateException("The order was not stopped");
            }
            Drug drug = assembler.drug(order.drugKey());
            events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_STOP,
                    MedicineRules.stoppedText(drug), actor);
            Map<String, Object> after = auditBase(order);
            after.put("statusBefore", order.status());
            after.put("status", MedicineOrderRepository.STOPPED);
            auditLog.record("IPD_MED_STOPPED", ENTITY_ORDER, orderId, null, after);
            return sheetNow(tenantId, role, memberRole, ent, locked.admission(), locked.patient());
        }
        if (MedicineOrderRepository.STOPPED.equals(order.status()) && actor.equals(order.stoppedBy())) {
            patientAccess.recordOpened(tenantId, locked.admission().patientId(), actor);
            return sheetNow(tenantId, role, memberRole, ent, locked.admission(), locked.patient());
        }
        throw MedicineOrderRepository.STOPPED.equals(order.status())
                ? stateConflict("Order already stopped", MedicineRules.ALREADY_STOPPED)
                : stateConflict("Order rejected", MedicineRules.REJECTED_CANNOT_STOP);
    }

    // ---- reject ----

    /**
     * Reject order (ADR-009 D5): PENDING to REJECTED with a reason by PHARMACIST or OWNER, whatever the findings.
     * Allowed in Read-only. No PIN and no self rule. A repeat by the same user with the same reason answers 200.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PharmacistActionResult reject(UUID tenantId, UUID actor, String role, UUID orderId,
                                         RejectOrderInput input) {
        requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        ModuleEntitlement ent = gate.check(tenantId, false);
        RejectReason reason = MedicineRules.reject(input);
        membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        Locked locked = lock(tenantId, orderId, NO_SCOPE_GUARD);
        OrderRow order = locked.order();
        KeyLabel label = new KeyLabel(reason.key(), reason.label());
        Drug drug = assembler.drug(order.drugKey());
        if (MedicineOrderRepository.PENDING.equals(order.status())) {
            if (!orders.markRejected(tenantId, orderId, actor, reason.key())) {
                throw new IllegalStateException("The order was not rejected");
            }
            events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_VERIFY,
                    MedicineRules.rejectedText(drug, reason), actor);
            Map<String, Object> after = auditBase(order);
            after.put("status", MedicineOrderRepository.REJECTED);
            after.put("rejectReasonKey", reason.key());
            auditLog.record("IPD_MED_REJECTED", ENTITY_ORDER, orderId, null, after);
            return result(tenantId, actor, ent, order, drug, "REJECTED", false, label);
        }
        if (MedicineOrderRepository.REJECTED.equals(order.status()) && actor.equals(order.rejectedBy())
                && reason.key().equals(order.rejectReasonKey())) {
            return result(tenantId, actor, ent, order, drug, "REJECTED", true, label);
        }
        throw notPending(tenantId, order);
    }

    // ---- verify (two transactions with the PIN check between them) ----

    /**
     * The first transaction of verify, before the PIN (ADR-009 D5 steps 1 and 2). Check order: role (403), module Off
     * (403), Read-only (409), body (400), the role read again from the membership (403), the order (404) and the
     * self-verification rule (403), which comes before the PIN so the orderer never spends PIN tries. Read-only; it
     * writes nothing and commits before the PIN is checked.
     *
     * @return the PIN to check
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public String prepareVerify(UUID tenantId, UUID actor, String role, UUID orderId, VerifyOrderInput input) {
        requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        gate.check(tenantId, true);
        Verify body = MedicineRules.verify(input);
        membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        OrderRow order = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException(
                "Order not found"));
        if (actor.equals(order.orderedBy())) {
            throw selfVerification();
        }
        return body.pin();
    }

    /**
     * The second transaction of verify, after the PIN was accepted: membership, the admission lock, state, the safety
     * check again (the order itself excluded) and PENDING to ACTIVE. The PIN is not an argument and is never seen here.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PharmacistActionResult verifyLocked(UUID tenantId, UUID actor, String role, UUID orderId,
                                               VerifyOrderInput input) {
        requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        ModuleEntitlement ent = gate.check(tenantId, true);
        Verify body = MedicineRules.verify(input);
        membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        Locked locked = lock(tenantId, orderId, NO_SCOPE_GUARD);
        OrderRow order = locked.order();
        Drug drug = assembler.drug(order.drugKey());
        if (MedicineOrderRepository.ACTIVE.equals(order.status()) && actor.equals(order.verifiedBy())) {
            return result(tenantId, actor, ent, order, drug, "VERIFIED", true, null);
        }
        if (!MedicineOrderRepository.PENDING.equals(order.status())) {
            throw notPending(tenantId, order);
        }
        if (actor.equals(order.orderedBy())) {
            throw selfVerification();
        }
        List<OrderRow> chart = orders.findByAdmission(tenantId, order.admissionId());
        OrderCheck check = assembler.safety().check(drug, locked.patient().allergyText(),
                MedicineAssembler.liveKeys(chart, order.id()));
        if (check.blocked()) {
            throw new ConflictException("order-blocked", "Order is blocked", MedicineRules.HARD_STOP_ON_ORDER);
        }
        if (!check.checkToken().equals(body.checkToken())) {
            throw new ConflictException("order-checks-changed", "The checks changed", MedicineRules.CHECKS_CHANGED);
        }
        if (!orders.markActive(tenantId, orderId, actor)) {
            throw new IllegalStateException("The order was not verified");
        }
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_VERIFY,
                MedicineRules.verifiedText(drug, order.overrideReasonKey() != null), actor);
        Map<String, Object> after = auditBase(order);
        after.put("status", MedicineOrderRepository.ACTIVE);
        after.put("overrideReasonKey", order.overrideReasonKey());
        auditLog.record("IPD_MED_VERIFIED", ENTITY_ORDER, orderId, null, after);
        return result(tenantId, actor, ent, order, drug, "VERIFIED", false, null);
    }

    // ---- the lock (ADR-009 D10) ----

    /** The order as read under the admission lock, with the admitted admission and its patient. */
    private record Locked(OrderRow order, AdmissionRow admission, PatientClinical patient) {

        @Override
        public String toString() {
            return "Locked[orderId=" + order.id() + "]";
        }
    }

    /** Pharmacist paths: the role is always scope ALL, so there is nothing to guard. */
    private static final Consumer<AdmissionRow> NO_SCOPE_GUARD = admission -> { };

    /**
     * A plain read of the order gives its admission (404 when missing or another clinic's); the bare admission row is
     * locked {@code for no key update} and read joined in a new statement (404); the scope guard runs on that admission
     * next (SAH-34: a scoped nurse out of scope gets 403 {@code outside-scope} before any state answer, also for a
     * discharged admission); the order is read again, because it may have changed while the lock was awaited; the
     * admission must be ADMITTED (409); the patient must be found (404, never a placeholder in the answer to an action).
     */
    private Locked lock(UUID tenantId, UUID orderId, Consumer<AdmissionRow> scopeGuard) {
        OrderRow first = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        scopeGuard.accept(admission);
        OrderRow current = orders.findById(tenantId, orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!"ADMITTED".equals(admission.status())) {
            throw notAdmitted();
        }
        return new Locked(current, admission, patient(tenantId, admission));
    }

    // ---- answers ----

    private MedicineOrderAnswer answer(String outcome, boolean replayed, OrderCheck check, MedicineOrderRow order,
                                       EmrSheet sheet) {
        OrderCheckView view = MedicineAssembler.view(check);
        return new MedicineOrderAnswer(outcome, replayed, view, order, sheet);
    }

    private static MedicineOrderRow orderRow(EmrSheet sheet, UUID orderId) {
        for (MedicineOrderRow row : sheet.orders()) {
            if (row.id().equals(orderId)) {
                return row;
            }
        }
        throw new IllegalStateException("The order is not on the sheet");
    }

    /** The sheet from the orders as they are now, with the clock read after the writes. */
    private EmrSheet sheetNow(UUID tenantId, String role, String memberRole, ModuleEntitlement ent,
                              AdmissionRow admission, PatientClinical patient) {
        return sheet(tenantId, role, memberRole, ent, admission, patient,
                orders.findByAdmission(tenantId, admission.id()));
    }

    private EmrSheet sheet(UUID tenantId, String role, String memberRole, ModuleEntitlement ent,
                           AdmissionRow admission, PatientClinical patient, List<OrderRow> chart) {
        return assembler.sheet(tenantId, role, memberRole, ent, admission, patient, chart, orders.databaseNow());
    }

    /** The answer of verify and reject: what happened and the first page of the queue now (same transaction). */
    private PharmacistActionResult result(UUID tenantId, UUID actor, ModuleEntitlement ent, OrderRow order,
                                          Drug drug, String outcome, boolean replayed, KeyLabel rejectReason) {
        PharmacistChecks checks = assembler.queue(tenantId, actor, ent, MedicineRules.DEFAULT_LIMIT, null);
        return new PharmacistActionResult(new ActionOutcome(order.id(), drug.name(), outcome, replayed,
                checks.verifierName(), rejectReason), checks);
    }

    /** 409 for an order that is not waiting any more, naming the person who verified it (a staff name). */
    private ConflictException notPending(UUID tenantId, OrderRow order) {
        if (MedicineOrderRepository.ACTIVE.equals(order.status())) {
            String name = order.verifiedBy() == null ? NAME_FALLBACK : actors.displayName(tenantId, order.verifiedBy())
                    .orElse(NAME_FALLBACK);
            return stateConflict("Order already verified", name + " already verified this order.");
        }
        if (MedicineOrderRepository.REJECTED.equals(order.status())) {
            return stateConflict("Order already rejected", MedicineRules.ALREADY_REJECTED);
        }
        return stateConflict("Order stopped", MedicineRules.STOPPED_BY_DOCTOR);
    }

    private static ConflictException stateConflict(String title, String detail) {
        return new ConflictException(STATE_PROBLEM, title, detail);
    }

    private static ConflictException notAdmitted() {
        return new ConflictException("not-admitted", "Patient is not admitted", MedicineRules.NOT_ADMITTED);
    }

    private static ForbiddenException selfVerification() {
        return new ForbiddenException("self-verification", "You cannot verify your own order",
                MedicineRules.SELF_VERIFICATION);
    }

    private static Map<String, Object> auditBase(OrderRow order) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", order.id());
        after.put("admissionId", order.admissionId());
        after.put("drugKey", order.drugKey());
        after.put("frequencyKey", order.frequencyKey());
        return after;
    }

    // ---- helpers ----

    /** The admission's patient; a missing one is a 404 and never a placeholder name. */
    private PatientClinical patient(UUID tenantId, AdmissionRow admission) {
        return patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    /** The role in the token must be one of {@code allowed}: 403 forbidden with the contract's detail. */
    static void requireRole(String role, Set<String> allowed, String detail) {
        if (role == null || !allowed.contains(role)) {
            throw forbidden(detail);
        }
    }

    /**
     * The caller's role read again from the active membership (the {@code OwnerGuard} pattern), so a role change or a
     * deactivation applies at once. No active membership, or a role outside {@code allowed}, is a 403.
     */
    static String membershipRole(TeamMemberPort team, UUID tenantId, UUID actor, Set<String> allowed,
                                 String detail) {
        return team.findActiveByUserId(tenantId, actor).filter(TeamMember::active).map(TeamMember::role)
                .filter(allowed::contains).orElseThrow(() -> forbidden(detail));
    }

    static ForbiddenException forbidden(String detail) {
        return new ForbiddenException("forbidden", "Not allowed", detail);
    }

}
