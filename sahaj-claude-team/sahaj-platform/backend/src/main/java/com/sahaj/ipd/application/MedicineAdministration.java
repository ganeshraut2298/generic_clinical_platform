package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.DoseRepository.NewDose;
import com.sahaj.ipd.application.DoseRepository.Stored;
import com.sahaj.ipd.application.DoseRules.Body;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DoseOutcome;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.DoseSlots.Stage;
import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.ipd.domain.WristbandCheck;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-32 the two IPD transactions of giving a dose or recording that it was not given (ADR-011 D4 to D9). The
 * non-transactional {@link DoseService} calls them with the second nurse's PIN check between them, the shape of
 * verify (ADR-009 B1): the PIN is checked outside any transaction and never with an admission lock held.
 *
 * <ul>
 *   <li>{@link #prepare}: every check, no lock. Read-write only so that it can write the deny entry of a wristband
 *       mismatch and the record-access row of a replay; every other refusal writes nothing.</li>
 *   <li>{@link #recordLocked}: the role, state and membership again, then the bare admission row
 *       {@code for no key update} (the only lock), the database clock read after the lock, every check again, the
 *       dose, one clinical event and one audit row.</li>
 * </ul>
 *
 * <p>A refusal by the unique index, a database check, the guard trigger or a foreign key on the insert is a code
 * fault: it is never swallowed, the whole transaction rolls back and the caller sees a generic 500. The scanned text,
 * the PIN, names and allergy text are never logged or audited; the audit rows hold ids and fixed keys.
 */
@Service
public class MedicineAdministration {

    static final String ENTITY_DOSE = "IPD_DOSE";
    static final String ENTITY_ORDER = "IPD_MEDICINE_ORDER";
    static final String NURSE_ROLE = "NURSE";
    private static final Logger log = LoggerFactory.getLogger(MedicineAdministration.class);

    private final MedicineOrderRepository orders;
    private final DoseRepository doses;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final IpdGate gate;
    private final MedicineAssembler medicines;
    private final MarAssembler marAssembler;

    MedicineAdministration(MedicineOrderRepository orders, DoseRepository doses, AdmissionRepository admissions,
                           ClinicalEventRepository events, PatientClinicalPort patients,
                           PatientAccessPort patientAccess, ActorDirectoryPort actors, TeamMemberPort team,
                           AuditLog auditLog, IpdGate gate, MedicineAssembler medicines, MarAssembler marAssembler) {
        this.orders = orders;
        this.doses = doses;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.team = team;
        this.auditLog = auditLog;
        this.gate = gate;
        this.medicines = medicines;
        this.marAssembler = marAssembler;
    }

    // ---- results ----

    /** What the first transaction decided. */
    public enum PrepareKind {
        /** Every check passed; the dose can be recorded (after the PIN check, for a high-alert GIVEN). */
        READY,
        /** A repeat of a recorded dose: the answer is {@link Prepared#replay()}, 200. */
        REPLAY,
        /** The wristband did not match: the deny entry is written and committed; the caller answers 409. */
        MISMATCH
    }

    /**
     * The first transaction's answer. {@code pin} is the second nurse's PIN to check and {@code secondNurseId} the
     * membership to check it for, both null unless this is a high-alert GIVEN. The PIN is hidden from toString.
     */
    public record Prepared(PrepareKind kind, DoseRecorded replay, UUID secondNurseId, String pin) {

        public boolean pinRequired() {
            return pin != null;
        }

        @Override
        public String toString() {
            return "Prepared[kind=" + kind + "]";
        }
    }

    /** What the locked transaction decided: a new dose (201), a replay (200) or a mismatch (409, entry committed). */
    public enum RecordKind { CREATED, REPLAYED, MISMATCH }

    public record Recorded(RecordKind kind, DoseRecorded answer) {

        @Override
        public String toString() {
            return "Recorded[kind=" + kind + "]";
        }
    }

    // ---- the first transaction ----

    /**
     * Check order of the contract: token role (403), module Off (403), Read-only (409), body format (400), the role
     * read again from the membership (403), order and patient (404), then {@link #decide}. Takes no lock.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Prepared prepare(UUID tenantId, UUID actor, String role, UUID orderId, DoseInput input) {
        Caller caller = caller(tenantId, actor, role);
        Body body = DoseRules.body(input);
        caller = member(tenantId, actor, caller);
        OrderRow order = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        AdmissionRow admission = admissions.find(tenantId, order.admissionId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        PatientClinical patient = patient(tenantId, admission);
        Instant asOf = orders.databaseNow();

        Decision decision = decide(tenantId, actor, caller, order, admission, patient, body, input, asOf, false);
        if (decision.replay != null) {
            return new Prepared(PrepareKind.REPLAY, replayAnswer(tenantId, actor, caller, admission, patient,
                    decision.replay), null, null);
        }
        if (decision.mismatch) {
            return new Prepared(PrepareKind.MISMATCH, null, null, null);
        }
        boolean pin = decision.highAlertGiven;
        return new Prepared(PrepareKind.READY, null, pin ? decision.secondNurse.membershipId() : null,
                pin ? input.secondNursePin() : null);
    }

    // ---- the locked transaction ----

    /**
     * The second transaction, after the second nurse's PIN was accepted (or none was needed). The request is the
     * original one without the PIN. {@code colleagueUserId} is the second nurse's user id from the PIN check, null
     * unless this is a high-alert GIVEN.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Recorded recordLocked(UUID tenantId, UUID actor, String role, UUID orderId, DoseInput input,
                                 UUID colleagueUserId) {
        Caller caller = caller(tenantId, actor, role);
        Body body = DoseRules.body(input);
        caller = member(tenantId, actor, caller);

        OrderRow first = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        OrderRow order = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        PatientClinical patient = patient(tenantId, admission);
        // The clock is read after the lock: the due rule uses the time the dose is really recorded.
        Instant asOf = orders.databaseNow();

        Decision decision = decide(tenantId, actor, caller, order, admission, patient, body, input, asOf, true);
        if (decision.replay != null) {
            return new Recorded(RecordKind.REPLAYED, replayAnswer(tenantId, actor, caller, admission, patient,
                    decision.replay));
        }
        if (decision.mismatch) {
            return new Recorded(RecordKind.MISMATCH, null);
        }
        if (decision.highAlertGiven) {
            if (colleagueUserId == null) {
                throw new IllegalStateException("A high-alert dose reached the lock without a second check");
            }
            if (colleagueUserId.equals(actor)) {
                throw new InvalidInputException("secondNurseId", DoseRules.DIFFERENT_NURSE);
            }
        }
        return new Recorded(RecordKind.CREATED, store(tenantId, actor, caller, order, admission, patient, body,
                input, decision, colleagueUserId));
    }

    // ---- the checks (the same code in both transactions) ----

    /** The outcome of the checks: exactly one of a replay, a mismatch or a go-ahead with the values to store. */
    private static final class Decision {
        DoseRow replay;
        boolean mismatch;
        boolean highAlertGiven;
        Drug drug;
        TeamMember secondNurse;
        NotGivenReason reason;
        String scan;
    }

    /**
     * D5 steps 5 to 11 and, for GIVEN, the bedside checks of D4 up to the second nurse's membership; the PIN itself
     * is checked by the caller between the two transactions ({@code pinChecked} is true under the lock, where the
     * PIN is not available any more). A wristband mismatch writes the deny entry and the audit row and returns, so the
     * surrounding transaction commits them.
     */
    private Decision decide(UUID tenantId, UUID actor, Caller caller, OrderRow order, AdmissionRow admission,
                            PatientClinical patient, Body body, DoseInput input, Instant asOf, boolean pinChecked) {
        Decision decision = new Decision();
        Drug drug = medicines.drug(order.drugKey());
        decision.drug = drug;

        DoseRow repeat = doses.findByClientKey(tenantId, body.clientKey()).orElse(null);
        if (repeat != null) {
            decision.replay = checkRepeat(tenantId, repeat, order, body, input);
            return decision;
        }
        if (!"ADMITTED".equals(admission.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted", DoseRules.NOT_ADMITTED);
        }
        if (MedicineOrderRepository.PENDING.equals(order.status())) {
            throw new ConflictException("order-not-verified", "Order not checked", DoseRules.NOT_VERIFIED);
        }
        if (MedicineOrderRepository.STOPPED.equals(order.status())) {
            throw new ConflictException(MedicineOrderService.STATE_PROBLEM, "Order stopped", DoseRules.STOPPED);
        }
        if (MedicineOrderRepository.REJECTED.equals(order.status())) {
            throw new ConflictException(MedicineOrderService.STATE_PROBLEM, "Order rejected", DoseRules.REJECTED);
        }
        Frequency frequency = Frequency.ofKey(order.frequencyKey())
                .orElseThrow(() -> new IllegalStateException("An order has an unknown frequency"));
        if (!DoseSlots.times(frequency, asOf).contains(body.slotAt())) {
            throw new InvalidInputException("slotAt", DoseRules.NOT_A_DOSE_TIME);
        }
        State clock = DoseSlots.clockState(body.slotAt(), Stage.ACTIVE, order.verifiedAt(), true, asOf);
        if (clock == State.BEFORE_START) {
            throw new ConflictException("dose-before-start", "Dose before the order started", DoseRules.BEFORE_START);
        }
        if (clock == State.LATER) {
            throw new ConflictException("dose-not-due", "Dose not due yet", DoseRules.NOT_DUE);
        }
        if (doses.findByOrderAndSlot(tenantId, order.id(), body.slotAt()).isPresent()) {
            throw recorded();
        }

        if (!body.given()) {
            decision.reason = DoseRules.reason(input);
            if (decision.reason == null) {
                throw new InvalidInputException("reasonKey", DoseRules.CHOOSE_REASON);
            }
            return decision;
        }

        // The bedside checks, one message at a time, in the order of AC6.
        WristbandCheck.Result wristband = WristbandCheck.check(input.wristband(), patient.uhid());
        if (wristband == WristbandCheck.Result.EMPTY) {
            throw new InvalidInputException("wristband", DoseRules.SCAN_FIRST);
        }
        if (wristband == WristbandCheck.Result.MISMATCH) {
            denyAtWristband(tenantId, actor, order, admission, patient, drug, body, input);
            decision.mismatch = true;
            return decision;
        }
        decision.scan = WristbandCheck.normalise(input.wristband());
        if (!DoseRules.confirmed(input)) {
            throw new InvalidInputException("identityConfirmed", DoseRules.CONFIRM);
        }
        if (drug.highAlert() != null) {
            decision.highAlertGiven = true;
            decision.secondNurse = checkSecondNurse(tenantId, caller, input, pinChecked);
        }
        return decision;
    }

    /**
     * High-alert GIVEN: the second nurse and the PIN format (400), the same person (400), not an active NURSE of the
     * clinic (400). The PIN is not read when {@code pinChecked}.
     */
    private TeamMember checkSecondNurse(UUID tenantId, Caller caller, DoseInput input, boolean pinChecked) {
        UUID secondId = DoseRules.secondNurse(input);
        boolean missing = input.secondNurseId() == null || input.secondNurseId().isBlank();
        boolean pinBad = !pinChecked && !DoseRules.pinFormat(input.secondNursePin());
        if (missing || pinBad) {
            List<FieldMessage> errors = new ArrayList<>();
            if (missing) {
                errors.add(new FieldMessage("secondNurseId", DoseRules.HIGH_ALERT));
            }
            if (pinBad) {
                errors.add(new FieldMessage("secondNursePin", DoseRules.HIGH_ALERT));
            }
            throw new InvalidFieldsException(errors, true);
        }
        if (secondId == null) {
            throw new InvalidInputException("secondNurseId", DoseRules.CHOOSE_NURSE);
        }
        if (secondId.equals(caller.membershipId())) {
            throw new InvalidInputException("secondNurseId", DoseRules.DIFFERENT_NURSE);
        }
        Optional<TeamMember> second = team.find(tenantId, secondId);
        if (second.isEmpty() || !second.get().active() || !NURSE_ROLE.equals(second.get().role())) {
            throw new InvalidInputException("secondNurseId", DoseRules.CHOOSE_NURSE);
        }
        return second.get();
    }

    /**
     * A repeat of a client key (ADR-011 D8): another order's key is a 400; any different value is a 409; the same
     * values are a replay. The second nurse of a high-alert dose is compared by display name, the one thing the
     * stored user id and the membership id sent have in common through {@code platform.api}.
     */
    private DoseRow checkRepeat(UUID tenantId, DoseRow stored, OrderRow order, Body body, DoseInput input) {
        if (!stored.orderId().equals(order.id())) {
            throw new InvalidInputException("clientKey", MedicineRules.RELOAD_MESSAGE);
        }
        boolean same = stored.slotAt().equals(body.slotAt());
        if (same && body.given()) {
            same = DoseRepository.GIVEN.equals(stored.outcome());
            if (same && stored.highAlert()) {
                same = sameSecondNurse(tenantId, stored, input);
            }
        } else if (same) {
            NotGivenReason reason = DoseRules.reason(input);
            same = !DoseRepository.GIVEN.equals(stored.outcome()) && reason != null
                    && reason.key().equals(stored.reasonKey());
        }
        if (!same) {
            throw new ConflictException("client-key-reused", "Client key already used", DoseRules.KEY_REUSED);
        }
        return stored;
    }

    private boolean sameSecondNurse(UUID tenantId, DoseRow stored, DoseInput input) {
        UUID sent = DoseRules.secondNurse(input);
        if (sent == null || stored.secondCheckBy() == null) {
            return false;
        }
        Optional<String> sentName = team.find(tenantId, sent).map(TeamMember::name);
        Optional<String> storedName = actors.displayName(tenantId, stored.secondCheckBy());
        return sentName.isPresent() && sentName.equals(storedName);
    }

    /** The deny entry and the audit row of a wristband mismatch (D4). Neither holds a name or the typed text. */
    private void denyAtWristband(UUID tenantId, UUID actor, OrderRow order, AdmissionRow admission,
                                 PatientClinical patient, Drug drug, Body body, DoseInput input) {
        events.insert(tenantId, admission.id(), ClinicalEventRepository.KIND_DENY,
                DoseRules.deniedText(WristbandCheck.scanForEvent(input.wristband()), patient.uhid(), drug), actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", order.id());
        after.put("admissionId", admission.id());
        after.put("drugKey", drug.key());
        after.put("slotAt", body.slotAt().toString());
        auditLog.record("IPD_DOSE_WRISTBAND_MISMATCH", ENTITY_ORDER, order.id(), null, after);
    }

    // ---- the write ----

    private DoseRecorded store(UUID tenantId, UUID actor, Caller caller, OrderRow order, AdmissionRow admission,
                               PatientClinical patient, Body body, DoseInput input, Decision decision,
                               UUID colleagueUserId) {
        Drug drug = decision.drug;
        boolean given = body.given();
        boolean highAlert = drug.highAlert() != null;
        UUID secondCheckBy = given && highAlert ? colleagueUserId : null;
        DoseOutcome outcome = given ? DoseOutcome.GIVEN : decision.reason.outcome();
        NewDose dose = new NewDose(admission.id(), order.id(), body.clientKey(), body.slotAt(), outcome.name(),
                given ? null : decision.reason.key(), actor, secondCheckBy, highAlert, given ? decision.scan : null,
                given ? Boolean.TRUE : null);
        Stored stored;
        try {
            stored = doses.insert(tenantId, dose);
        } catch (DataAccessException e) {
            // The admission lock and the checks above answered first; a refusal means the state or the values are
            // wrong. Only the SQL state is logged: the message can quote the row.
            log.error("Dose insert refused tenantId={} orderId={} sqlState={}", tenantId, order.id(), sqlState(e));
            throw new IllegalStateException("The dose could not be stored", e);
        }

        String text = given
                ? DoseRules.gaveText(drug, body.slotAt(), highAlert ? decision.secondNurse.name() : null)
                : DoseRules.notGivenText(drug, body.slotAt(), decision.reason);
        events.insert(tenantId, admission.id(), ClinicalEventRepository.KIND_GIVE, text, actor);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("doseId", stored.id());
        after.put("orderId", order.id());
        after.put("admissionId", admission.id());
        after.put("drugKey", drug.key());
        after.put("slotAt", body.slotAt().toString());
        after.put("outcome", outcome.name());
        if (given) {
            after.put("highAlert", highAlert);
            after.put("secondCheckUserId", secondCheckBy);
        } else {
            after.put("reasonKey", decision.reason.key());
        }
        auditLog.record(given ? "IPD_DOSE_GIVEN" : "IPD_DOSE_NOT_GIVEN", ENTITY_DOSE, stored.id(), null, after);

        DoseRow row = new DoseRow(stored.id(), admission.id(), order.id(), body.clientKey(), body.slotAt(),
                outcome.name(), dose.reasonKey(), actor, secondCheckBy, highAlert, stored.recordedAt());
        DoseView view = marAssembler.view(tenantId, row);
        // The clock is read again after the writes, so asOf is never before the recorded time.
        Mar mar = marNow(tenantId, caller, admission, patient);
        return new DoseRecorded(false, view, mar);
    }

    /** A replay answers the stored dose and the MAR now; it writes one record-access row and nothing else. */
    private DoseRecorded replayAnswer(UUID tenantId, UUID actor, Caller caller, AdmissionRow admission,
                                      PatientClinical patient, DoseRow stored) {
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        return new DoseRecorded(true, marAssembler.view(tenantId, stored), marNow(tenantId, caller, admission, patient));
    }

    private Mar marNow(UUID tenantId, Caller caller, AdmissionRow admission, PatientClinical patient) {
        return marAssembler.mar(tenantId, caller.membershipId(), caller.tokenRole(), caller.memberRole(),
                caller.entitlement(), admission, patient, orders.databaseNow());
    }

    // ---- the caller ----

    /** Who is asking: the token role, then (after the body check) the role and membership read again. */
    private record Caller(String tokenRole, ModuleEntitlement entitlement, String memberRole, UUID membershipId) {
    }

    /** Steps 1: token role (403), module Off (403), Read-only (409). */
    private Caller caller(UUID tenantId, UUID actor, String role) {
        MedicineOrderService.requireRole(role, DoseRules.GIVE_ROLES, DoseRules.ONLY_GIVERS);
        ModuleEntitlement ent = gate.check(tenantId, true);
        return new Caller(role, ent, null, null);
    }

    /** Step 3: the role and membership read again from the active membership (403). */
    private Caller member(UUID tenantId, UUID actor, Caller caller) {
        TeamMember member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active)
                .filter(m -> DoseRules.GIVE_ROLES.contains(m.role()))
                .orElseThrow(() -> MedicineOrderService.forbidden(DoseRules.ONLY_GIVERS));
        return new Caller(caller.tokenRole(), caller.entitlement(), member.role(), member.membershipId());
    }

    private PatientClinical patient(UUID tenantId, AdmissionRow admission) {
        return patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    private static ConflictException recorded() {
        return new ConflictException("dose-recorded", "Dose already recorded", DoseRules.RECORDED);
    }

    private static String sqlState(DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        return root instanceof java.sql.SQLException sql ? sql.getSQLState() : "-";
    }
}
