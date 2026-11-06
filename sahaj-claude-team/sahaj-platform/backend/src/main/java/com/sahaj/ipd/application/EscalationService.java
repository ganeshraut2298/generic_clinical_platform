package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.EscalationRules.Acknowledge;
import com.sahaj.ipd.application.EscalationRules.CallSenior;
import com.sahaj.ipd.application.EscalationRules.Respond;
import com.sahaj.ipd.application.SafetyRows.Access;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.application.SafetyViews.ActionResult;
import com.sahaj.ipd.application.SafetyViews.ResponseInput;
import com.sahaj.ipd.application.SafetyViews.Row;
import com.sahaj.ipd.application.SafetyViews.SeniorCallInput;
import com.sahaj.ipd.application.SafetyViews.VersionInput;
import com.sahaj.ipd.domain.EscalationClock;
import com.sahaj.ipd.domain.EscalationState;
import com.sahaj.ipd.domain.EscalationState.Answer;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-33 the three actions on an early warning escalation: Acknowledge and Close escalation (OWNER, DOCTOR) and Call
 * senior (NURSE). The rules are in {@link EscalationState}; this class takes the locks, reads the row, asks the rules
 * what the request means, writes, and records the clinical event and the audit row (ADR-007 D3, D7, D10, D11).
 *
 * <p>Every action runs in one explicit READ COMMITTED transaction (ADR-007 D3): the role in the token, the IPD state
 * (Read-only is allowed, DEC-015) and the body are checked first; then, inside the transaction and before any lock, the
 * role is read again from the active membership; then the escalation is read by id for its admission, the bare
 * admission row is locked {@code for no key update} (the SAH-30 lock), and the escalation is read again with the
 * admission, bed and patient. A state that cannot take the action answers 409 escalation-state before a stale version
 * answers 409 escalation-changed. A repeat of the same user's own earlier request answers 200 with
 * {@code replayed: true} and writes nothing. A failure rolls back the update, the event and the audit row together.
 *
 * <p>The plan and the names are clinical and personal: they go to the screen only. The event log carries fixed
 * wording and numbers; the audit row carries ids, the score, the risk, the action code and counts.
 */
@Service
public class EscalationService {

    static final String ENTITY = "IPD_EWS_ESCALATION";
    static final String STATE_PROBLEM = "escalation-state";
    static final String CHANGED_PROBLEM = "escalation-changed";
    static final String ALREADY_CLOSED = "This escalation is already closed.";
    static final String CALL_LIMIT_MESSAGE =
            "This escalation cannot record more senior calls. Phone the senior doctor now.";
    private static final String NOT_FOUND = "Escalation not found";

    private final EscalationBoardRepository board;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientLookupPort patients;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final IpdGate gate;
    private final SeniorCallRepository seniorCalls;

    EscalationService(EscalationBoardRepository board, AdmissionRepository admissions, ClinicalEventRepository events,
                      PatientLookupPort patients, ActorDirectoryPort actors, TeamMemberPort team, AuditLog auditLog,
                      IpdGate gate, SeniorCallRepository seniorCalls) {
        this.seniorCalls = seniorCalls;
        this.board = board;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.actors = actors;
        this.team = team;
        this.auditLog = auditLog;
        this.gate = gate;
    }

    /**
     * Acknowledge: OPEN to ACK by the caller. The event is "Acknowledged escalation after N min", N the whole minutes
     * from the start of the response clock to the time the database stamped.
     *
     * <p>READ COMMITTED on purpose (ADR-007 D3): each statement must see what an action that held the admission lock
     * before this one has committed, which REPEATABLE READ would hide.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActionResult acknowledge(UUID tenantId, UUID actor, String role, UUID escalationId, VersionInput input) {
        SafetySupport.requireRole(role, EscalationRules.DOCTOR_ROLES, "You cannot acknowledge an escalation.");
        gate.check(tenantId, false);
        Acknowledge body = EscalationRules.acknowledge(input);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, EscalationRules.DOCTOR_ROLES,
                "You cannot acknowledge an escalation.");
        Locked locked = lock(tenantId, escalationId);
        Detail before = locked.escalation();
        Answer answer = EscalationState.acknowledge(before.snapshot(), body.version(), actor);
        if (answer.isReplay()) {
            return result(tenantId, locked, before, role, memberRole, true);
        }
        refuse(tenantId, answer, before);
        if (!board.acknowledge(tenantId, escalationId, actor, body.version())) {
            throw new IllegalStateException("The escalation was not acknowledged");
        }
        Detail after = reread(tenantId, escalationId);
        int minutes = EscalationClock.minutes(EscalationClock.start(after.raisedAt(), after.reopenedAt()),
                after.acknowledgedAt());
        events.insert(tenantId, after.admissionId(), ClinicalEventRepository.KIND_ESC,
                VitalsRules.acknowledgedText(minutes), actor);
        Map<String, Object> audit = auditBase(after);
        audit.put("minutesToAcknowledge", minutes);
        auditLog.record("IPD_ESCALATION_ACKNOWLEDGED", ENTITY, escalationId, null, audit);
        return result(tenantId, locked, after, role, memberRole, false);
    }

    /**
     * Close escalation: OPEN or ACK to DONE with the chosen action and the plan. From OPEN the caller is also stored
     * as the acknowledging doctor, with no "Acknowledged" event (mockup). The event holds the action only; the plan
     * is stored with the escalation and shown on the board to clinical roles.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActionResult respond(UUID tenantId, UUID actor, String role, UUID escalationId, ResponseInput input) {
        SafetySupport.requireRole(role, EscalationRules.DOCTOR_ROLES, "You cannot record a response.");
        gate.check(tenantId, false);
        Respond body = EscalationRules.respond(input);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, EscalationRules.DOCTOR_ROLES,
                "You cannot record a response.");
        Locked locked = lock(tenantId, escalationId);
        Detail before = locked.escalation();
        Answer answer = EscalationState.respond(before.snapshot(), body.version(), actor, body.action(), body.note());
        if (answer.isReplay()) {
            return result(tenantId, locked, before, role, memberRole, true);
        }
        refuse(tenantId, answer, before);
        if (!board.close(tenantId, escalationId, before.status(), actor, body.action(), body.note(),
                body.version())) {
            throw new IllegalStateException("The escalation was not closed");
        }
        Detail after = reread(tenantId, escalationId);
        events.insert(tenantId, after.admissionId(), ClinicalEventRepository.KIND_ESC,
                VitalsRules.closedText(body.action()), actor);
        Map<String, Object> audit = auditBase(after);
        audit.put("action", body.action().name());
        audit.put("statusBefore", before.status().name());
        auditLog.record("IPD_ESCALATION_CLOSED", ENTITY, escalationId, null, audit);
        return result(tenantId, locked, after, role, memberRole, false);
    }

    /**
     * Call senior: adds one to the senior calls of an OPEN escalation. Nothing is sent to anyone in phase 1; the call
     * is only recorded. The nurse sends the count she saw; the rules of ADR-007 R4 decide by the stored count and by
     * what she has already entered on this escalation (her own call, an entry in the last 60 seconds). A call writes
     * one ipd.ews_senior_call entry, the event and the audit row; seeing another member's call writes an
     * ALREADY_CALLED entry and an event only. The version is not changed by a call.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ActionResult callSenior(UUID tenantId, UUID actor, String role, UUID escalationId,
                                   SeniorCallInput input) {
        SafetySupport.requireRole(role, EscalationRules.NURSE_ROLES, "You cannot call the senior doctor.");
        gate.check(tenantId, false);
        CallSenior body = EscalationRules.callSenior(input);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, EscalationRules.NURSE_ROLES,
                "You cannot call the senior doctor.");
        Locked locked = lock(tenantId, escalationId);
        Detail before = locked.escalation();
        CallHistory history = new CallHistory(tenantId, escalationId, actor);
        Answer answer = EscalationState.callSenior(before.snapshot(), body.version(), body.seniorCalls(), history);
        if (answer.isReplay()) {
            return result(tenantId, locked, before, role, memberRole, true);
        }
        if (answer == Answer.SEEN_OTHER_CALL) {
            seniorCalls.insertAlreadyCalled(tenantId, escalationId, actor);
            events.insert(tenantId, before.admissionId(), ClinicalEventRepository.KIND_ESC,
                    VitalsRules.seniorAlreadyCalledText(), actor);
            return result(tenantId, locked, before, role, memberRole, true);
        }
        refuse(tenantId, answer, before);
        if (!board.callSenior(tenantId, escalationId, body.version(), body.seniorCalls())) {
            throw new IllegalStateException("The senior call was not recorded");
        }
        Detail after = reread(tenantId, escalationId);
        seniorCalls.insertCalled(tenantId, escalationId, actor, after.seniorCalls());
        Instant now = board.databaseNow();
        int minutes = EscalationClock.waitingMinutes(EscalationClock.start(after.raisedAt(), after.reopenedAt()), now);
        events.insert(tenantId, after.admissionId(), ClinicalEventRepository.KIND_ESC,
                VitalsRules.seniorCalledText(minutes), actor);
        Map<String, Object> audit = auditBase(after);
        audit.put("seniorCalls", after.seniorCalls());
        auditLog.record("IPD_ESCALATION_SENIOR_CALLED", ENTITY, escalationId, null, audit);
        return result(tenantId, locked, after, role, memberRole, false, now);
    }

    /** What the caller has entered on this escalation, read from the database only when the rules ask. */
    private final class CallHistory implements EscalationState.CallHistory {
        private final UUID tenantId;
        private final UUID escalationId;
        private final UUID actor;
        private Boolean recent;

        CallHistory(UUID tenantId, UUID escalationId, UUID actor) {
            this.tenantId = tenantId;
            this.escalationId = escalationId;
            this.actor = actor;
        }

        @Override
        public boolean ownCallAbove(int sentCalls) {
            return seniorCalls.hasCalledAbove(tenantId, escalationId, actor, sentCalls);
        }

        @Override
        public boolean enteredRecently() {
            if (recent == null) {
                recent = seniorCalls.hasEnteredWithin(tenantId, escalationId, actor,
                        EscalationState.CALL_THROTTLE_SECONDS);
            }
            return recent;
        }
    }

    // ---- the lock (ADR-007 D3 steps 3 to 5) ----

    /** The escalation as read under the admission lock, with the admitted admission and its patient. */
    private record Locked(Detail escalation, AdmissionRow admission, PatientSummary patient) {

        @Override
        public String toString() {
            return "Locked[escalationId=" + escalation.id() + "]";
        }
    }

    /**
     * A plain read of the escalation gives its admission (404 when missing or another clinic's); the bare admission row
     * is locked {@code for no key update} and read joined with its bed in a new statement (404 unless ADMITTED); the
     * escalation is read again, because it may have changed while the lock was awaited; the patient is looked up (404
     * when missing, never a placeholder in an answer to an action).
     */
    private Locked lock(UUID tenantId, UUID escalationId) {
        Detail first = board.findById(tenantId, escalationId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        Detail current = board.findById(tenantId, escalationId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        if (!"ADMITTED".equals(admission.status())) {
            throw new NotFoundException("This patient is not admitted any more.");
        }
        PatientSummary patient = patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        return new Locked(current, admission, patient);
    }

    private Detail reread(UUID tenantId, UUID escalationId) {
        return board.findById(tenantId, escalationId)
                .orElseThrow(() -> new IllegalStateException("The escalation was not found"));
    }

    // ---- answers ----

    /**
     * 409 for an answer that is not a write: escalation-state before escalation-changed (the rules decide the order),
     * with the exact messages of the contract. A doctor's name is a staff name; a patient name is never in a problem.
     */
    private void refuse(UUID tenantId, Answer answer, Detail current) {
        if (answer.isWrite()) {
            return;
        }
        switch (answer) {
            case ALREADY_CLOSED:
                throw new ConflictException(STATE_PROBLEM, "Escalation is closed", ALREADY_CLOSED);
            case CALL_LIMIT:
                throw new ConflictException(STATE_PROBLEM, "Senior calls are at the limit", CALL_LIMIT_MESSAGE);
            case ALREADY_ACKNOWLEDGED:
                throw new ConflictException(STATE_PROBLEM, "Escalation is acknowledged",
                        acknowledgedMessage(tenantId, current));
            case CHANGED_ACKNOWLEDGED:
                throw new ConflictException(CHANGED_PROBLEM, "Escalation changed",
                        acknowledgedMessage(tenantId, current));
            case CHANGED_SCORE:
                throw new ConflictException(CHANGED_PROBLEM, "Escalation changed",
                        "The score is now EWS " + current.ewsScore() + " (" + current.ewsRisk().word()
                                + "). Review it and try again.");
            default:
                throw new IllegalStateException("Unexpected answer " + answer);
        }
    }

    private String acknowledgedMessage(UUID tenantId, Detail current) {
        UUID by = current.acknowledgedBy();
        ActorNames names = ActorNames.forIds(actors, tenantId, by == null ? Set.of() : Set.of(by));
        return names.of(by) + " already acknowledged this escalation.";
    }

    private ActionResult result(UUID tenantId, Locked locked, Detail escalation, String role, String memberRole,
                                boolean replayed) {
        return result(tenantId, locked, escalation, role, memberRole, replayed, board.databaseNow());
    }

    /** The row after the action, with the clock read after the writes ({@code asOf}) and one batch of staff names. */
    private ActionResult result(UUID tenantId, Locked locked, Detail escalation, String role, String memberRole,
                                boolean replayed, Instant asOf) {
        ActorNames names = ActorNames.forEscalations(actors, tenantId, List.of(escalation));
        Row row = SafetyRows.row(escalation, locked.patient().name(), locked.admission().bedCode(), names, asOf,
                new Access(role, memberRole));
        return new ActionResult(asOf, replayed, row);
    }

    private static Map<String, Object> auditBase(Detail d) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("escalationId", d.id());
        after.put("admissionId", d.admissionId());
        after.put("ewsScore", d.ewsScore());
        after.put("ewsRisk", d.ewsRisk().name());
        return after;
    }
}
