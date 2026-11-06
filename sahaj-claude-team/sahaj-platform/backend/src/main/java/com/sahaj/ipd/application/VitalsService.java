package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EscalationRepository.EscalationRow;
import com.sahaj.ipd.application.VitalsRepository.NewVitals;
import com.sahaj.ipd.application.VitalsRepository.NoteRow;
import com.sahaj.ipd.application.VitalsRepository.Stored;
import com.sahaj.ipd.application.VitalsRepository.VitalsRow;
import com.sahaj.ipd.application.VitalsRules.Valid;
import com.sahaj.ipd.application.VitalsViews.EscalationResult;
import com.sahaj.ipd.application.VitalsViews.LatestVitals;
import com.sahaj.ipd.application.VitalsViews.NursingNote;
import com.sahaj.ipd.application.VitalsViews.OpenEscalation;
import com.sahaj.ipd.application.VitalsViews.Outcome;
import com.sahaj.ipd.application.VitalsViews.Saved;
import com.sahaj.ipd.application.VitalsViews.Sheet;
import com.sahaj.ipd.application.VitalsViews.VitalsInput;
import com.sahaj.ipd.application.VitalsViews.VitalsSaved;
import com.sahaj.ipd.domain.EarlyWarningScore;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.EscalationState.VitalsEffect;
import com.sahaj.ipd.domain.VitalsDue;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-30 Nursing sheet, vitals and nursing notes, early warning score and escalation (ADR-006). Every call first
 * re-checks the role in the token, then the IPD state, then reads the role again from the caller's active
 * membership (defence in depth: a role change applies at once, not when the access token expires).
 *
 * <p>A save runs in one READ COMMITTED transaction (REPEATABLE READ would break the escalation read) and
 * takes exactly one lock: the admission row, {@code for no key update}. It takes no IPD tenant advisory lock and no
 * bed lock, so a ward's saves for different patients never wait for each other or for admits. Under that lock it
 * looks for a repeat of the client key, checks the status, stores the vitals, the optional note, raises or updates
 * the escalation and writes the clinical events and the audit row. A unique-index clash on the escalation or on the
 * client key is never swallowed: the whole save rolls back and the caller sees a 500.
 *
 * <p>Vitals and notes are clinical data: they are returned to the screen only and never logged, audited or put in a
 * clinical event. The audit row and the events carry ids, the score, the risk and fixed words.
 */
@Service
public class VitalsService {

    static final String ENTITY_VITALS = "IPD_VITALS";
    static final String ENTITY_ESCALATION = "IPD_EWS_ESCALATION";
    static final String ESCALATION_CONSTRAINT = "ux_ews_escalation_tenant_admission_open";
    static final String CLIENT_KEY_CONSTRAINT = "uq_vitals_tenant_client_key";
    static final String CLIENT_KEY_REUSED_MESSAGE =
            "This reading was already saved with different values. Reload the page and try again.";
    static final int RECENT_NOTES = 3;
    private static final String NAME_FALLBACK = "Team member";

    private static final Logger log = LoggerFactory.getLogger(VitalsService.class);

    private final AdmissionRepository admissions;
    private final VitalsRepository vitals;
    private final EscalationRepository escalations;
    private final ClinicalEventRepository events;
    private final PatientLookupPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final IpdGate gate;
    private final MarAssembler mar;

    VitalsService(AdmissionRepository admissions, VitalsRepository vitals, EscalationRepository escalations,
                  ClinicalEventRepository events, PatientLookupPort patients, PatientAccessPort patientAccess,
                  ActorDirectoryPort actors, TeamMemberPort team, AuditLog auditLog, IpdGate gate,
                  MarAssembler mar) {
        this.admissions = admissions;
        this.vitals = vitals;
        this.escalations = escalations;
        this.events = events;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.team = team;
        this.auditLog = auditLog;
        this.gate = gate;
        this.mar = mar;
    }

    // ---- the sheet ----

    /**
     * Opens the Nursing sheet. Check order of the contract: role, module Off, the role read again from the active
     * membership (403), admission (404), its patient (404). Allowed in Read-only and for a discharged admission.
     * Changes no IPD data and takes no lock. The answer carries the patient's name and clinical data, so the open is
     * written to the record-access log after the 404 checks (ADR-006 D6; DEC-013); a refused call writes nothing.
     * {@code asOf} is the database clock at the read (ADR-006 D1), never the application clock.
     */
    @Transactional
    public Sheet sheet(UUID tenantId, UUID actor, String role, UUID admissionId) {
        requireRole(role, VitalsRules.READ_ROLES, "You cannot open the nursing sheet.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        String memberRole = membershipRole(tenantId, actor, VitalsRules.READ_ROLES,
                "You cannot open the nursing sheet.");
        AdmissionRow row = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        PatientSummary patient = patient(tenantId, row);
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        Names names = new Names(tenantId);
        VitalsRow latest = vitals.findLatest(tenantId, admissionId).orElse(null);
        return buildSheet(tenantId, role, memberRole, ent, row, patient, vitals.databaseNow(), latest, names);
    }

    // ---- the save ----

    /**
     * Saves one set of vitals and an optional note. Check order of the contract: role (403), module Off (403),
     * Read-only (409), client key (400), body (400); then, in the transaction and before any lock, the role read
     * again from the active membership (403); under the admission lock: not found (404), patient not found (404),
     * repeat of the client key (200 and one record-access row when every value is the same, 409 when any differs, 400
     * for another admission), not admitted (409).
     *
     * <p>READ COMMITTED is declared on purpose: ADR-006 D4 needs each statement to see what a save that held the
     * admission lock before this one has committed, which REPEATABLE READ would hide.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VitalsSaved record(UUID tenantId, UUID actor, String role, UUID admissionId, VitalsInput input) {
        requireRole(role, VitalsRules.RECORD_ROLES, "You cannot record vitals.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        UUID clientKey = VitalsRules.clientKey(input);
        Valid valid = VitalsRules.validate(input);
        String memberRole = membershipRole(tenantId, actor, VitalsRules.RECORD_ROLES, "You cannot record vitals.");

        AdmissionRow row = admissions.findForNoKeyUpdate(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        PatientSummary patient = patient(tenantId, row);

        VitalsRow first = vitals.findByClientKey(tenantId, clientKey).orElse(null);
        if (first != null) {
            if (!first.admissionId().equals(admissionId)) {
                throw new InvalidInputException("clientKey", VitalsRules.CLIENT_KEY_MESSAGE);
            }
            if (!sameReading(first, valid)) {
                throw new ConflictException("client-key-reused", "Client key already used",
                        CLIENT_KEY_REUSED_MESSAGE);
            }
            return replay(tenantId, actor, role, memberRole, ent, row, patient, first);
        }
        if (!"ADMITTED".equals(row.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted",
                    "This patient is not admitted any more. Vitals cannot be saved.");
        }

        EarlyWarningScore score = EarlyWarningScore.of(valid.systolic(), valid.pulse(), valid.spo2(),
                valid.tempTenths(), valid.rr());
        int total = score.total();
        Risk risk = score.risk();
        boolean noteAdded = valid.note() != null;
        BigDecimal tempC = BigDecimal.valueOf(valid.tempTenths(), 1);

        Stored stored;
        try {
            stored = vitals.insert(tenantId, new NewVitals(admissionId, clientKey, valid.systolic(),
                    valid.diastolic(), valid.pulse(), valid.spo2(), tempC, valid.rr(), total, risk, noteAdded, actor));
        } catch (DuplicateKeyException e) {
            // Under the admission lock a repeat key was looked up above, so a clash means the state is wrong.
            log.error("Vitals insert clash tenantId={} admissionId={} constraint={}", tenantId, admissionId,
                    CLIENT_KEY_CONSTRAINT);
            throw new IllegalStateException("The vitals could not be stored", e);
        }
        if (noteAdded) {
            vitals.insertNote(tenantId, admissionId, valid.note(), actor);
        }
        events.insert(tenantId, admissionId, ClinicalEventRepository.KIND_INFO,
                VitalsRules.recordedText(total, risk), actor);

        EscalationResult escalation = applyEscalation(tenantId, admissionId, actor, total, risk, stored.recordedAt());
        OpenEscalation open = escalation.open();

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", admissionId);
        after.put("vitalsId", stored.id());
        after.put("ewsScore", total);
        after.put("ewsRisk", risk.name());
        after.put("noteAdded", noteAdded);
        after.put("escalationId", open == null ? null : open.id());
        after.put("escalationOutcome", escalation.outcome().name());
        auditLog.record("IPD_VITALS_RECORDED", ENTITY_VITALS, stored.id(), null, after);

        VitalsRow made = new VitalsRow(stored.id(), admissionId, valid.systolic(), valid.diastolic(), valid.pulse(),
                valid.spo2(), tempC, valid.rr(), total, risk, noteAdded, stored.recordedAt(), actor);
        // The clock is read after the writes, so asOf is never before the recorded time (ADR-006 D1).
        Sheet sheet = buildSheet(tenantId, role, memberRole, ent, row, patient, vitals.databaseNow(), made,
                new Names(tenantId));
        return VitalsSaved.of(sheet, new Saved(stored.id(), total, risk, stored.recordedAt(), noteAdded, false),
                escalation);
    }

    /**
     * The escalation rule of AC8 and AC12, under the admission lock. A Low score changes nothing; Medium or High with no
     * open escalation raises one; with a strictly higher score it updates the open one, or re-opens an Acknowledged
     * MEDIUM one when the new risk is HIGH (SAH-33, ADR-007 D4); otherwise it changes nothing. The open
     * escalation is built from what was just written (a new row has the transaction time as its raise time), so it
     * is not read a second time.
     */
    private EscalationResult applyEscalation(UUID tenantId, UUID admissionId, UUID actor, int total, Risk risk,
                                             Instant at) {
        EscalationRow existing = escalations.findOpen(tenantId, admissionId).orElse(null);
        if (risk == Risk.LOW) {
            return new EscalationResult(Outcome.NONE, toOpen(existing));
        }
        int target = EarlyWarningScore.targetMinutes(risk).getAsInt();
        if (existing == null) {
            UUID id;
            try {
                id = escalations.insert(tenantId, admissionId, total, risk, target, actor);
            } catch (DuplicateKeyException e) {
                // The lock makes this impossible; the index is the last defence. Roll the whole save back.
                log.error("Escalation insert clash tenantId={} admissionId={} constraint={}", tenantId, admissionId,
                        ESCALATION_CONSTRAINT);
                throw new IllegalStateException("The escalation could not be stored", e);
            }
            events.insert(tenantId, admissionId, ClinicalEventRepository.KIND_ESC,
                    VitalsRules.raisedText(total, risk, target), actor);
            return new EscalationResult(Outcome.RAISED, new OpenEscalation(id, total, risk, target, "OPEN", at));
        }
        VitalsEffect effect = EscalationState.onVitals(Status.of(existing.status()), existing.ewsScore(),
                existing.ewsRisk(), total, risk);
        if (effect == VitalsEffect.REOPENED) {
            // SAH-33 AC12: an Acknowledged MEDIUM escalation meets a HIGH reading. Back to OPEN with the new score,
            // the acknowledgement cleared and the response clock restarted by the database; raisedAt is kept.
            if (!escalations.reopen(tenantId, existing.id(), total)) {
                throw new IllegalStateException("The escalation was not re-opened");
            }
            events.insert(tenantId, admissionId, ClinicalEventRepository.KIND_ESC, VitalsRules.reopenedText(total),
                    actor);
            Map<String, Object> reopened = new LinkedHashMap<>();
            reopened.put("escalationId", existing.id());
            reopened.put("admissionId", admissionId);
            reopened.put("ewsScore", total);
            reopened.put("ewsRisk", risk.name());
            reopened.put("previousScore", existing.ewsScore());
            reopened.put("previousRisk", existing.ewsRisk().name());
            auditLog.record("IPD_ESCALATION_REOPENED", ENTITY_ESCALATION, existing.id(), null, reopened);
            return new EscalationResult(Outcome.REOPENED, new OpenEscalation(existing.id(), total, risk, target,
                    "OPEN", existing.raisedAt()));
        }
        if (effect == VitalsEffect.UPDATED) {
            if (!escalations.raiseScore(tenantId, existing.id(), total, risk, target)) {
                throw new IllegalStateException("The escalation was not changed");
            }
            events.insert(tenantId, admissionId, ClinicalEventRepository.KIND_ESC,
                    VitalsRules.updatedText(total, risk), actor);
            return new EscalationResult(Outcome.UPDATED, new OpenEscalation(existing.id(), total, risk, target,
                    existing.status(), existing.raisedAt()));
        }
        return new EscalationResult(Outcome.NONE, toOpen(existing));
    }

    /** The five values and whether a note is sent, against the stored record (security review SAH-30 L1). */
    private static boolean sameReading(VitalsRow stored, Valid v) {
        return stored.bpSystolic() == v.systolic() && stored.bpDiastolic() == v.diastolic()
                && stored.pulse() == v.pulse() && stored.spo2() == v.spo2() && stored.rr() == v.rr()
                && stored.tempC().compareTo(BigDecimal.valueOf(v.tempTenths(), 1)) == 0
                && stored.noteAdded() == (v.note() != null);
    }

    /**
     * A repeat of a client key with the same values: the sheet as it is now and the first record. Nothing is written
     * or audited, but the answer is the full sheet, so one record-access row is written as on the GET (security
     * review SAH-30 L2; ADR-006 D5, D6).
     */
    private VitalsSaved replay(UUID tenantId, UUID actor, String role, String memberRole, ModuleEntitlement ent,
                               AdmissionRow row, PatientSummary patient, VitalsRow first) {
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        VitalsRow latest = vitals.findLatest(tenantId, row.id()).orElse(first);
        OpenEscalation open = toOpen(escalations.findOpen(tenantId, row.id()).orElse(null));
        Sheet sheet = buildSheet(tenantId, role, memberRole, ent, row, patient, vitals.databaseNow(), latest,
                new Names(tenantId));
        return VitalsSaved.of(sheet,
                new Saved(first.id(), first.ewsScore(), first.ewsRisk(), first.recordedAt(), first.noteAdded(), true),
                new EscalationResult(Outcome.NONE, open));
    }

    // ---- building the sheet ----

    /**
     * {@code canRecord} needs the role in the token and the role read from the membership to be OWNER or NURSE;
     * {@code asOf} is the database clock and the base of {@code vitalsDue}.
     */
    private Sheet buildSheet(UUID tenantId, String role, String memberRole, ModuleEntitlement ent, AdmissionRow row,
                             PatientSummary patient, Instant asOf, VitalsRow latest, Names names) {
        boolean admitted = "ADMITTED".equals(row.status());
        String state = ent.ipdState();
        boolean writableState = "trial".equals(state) || "on".equals(state);
        boolean roleCanRecord = VitalsRules.RECORD_ROLES.contains(role)
                && VitalsRules.RECORD_ROLES.contains(memberRole);
        boolean canRecord = roleCanRecord && admitted && writableState;
        String reason = null;
        if (!canRecord) {
            if (!roleCanRecord) {
                reason = "ROLE";
            } else if (!writableState) {
                reason = "IPD_READONLY";
            } else {
                reason = "NOT_ADMITTED";
            }
        }
        LatestVitals latestView = latest == null ? null : toLatest(latest, names);
        List<NursingNote> notes = new ArrayList<>();
        for (NoteRow note : vitals.findRecentNotes(tenantId, row.id(), RECENT_NOTES)) {
            notes.add(new NursingNote(note.id(), note.note(), note.recordedAt(), names.of(note.recordedBy())));
        }
        return new Sheet(row.id(), patient.name(), row.bedCode(), row.status(), state, canRecord, reason, asOf,
                latestView, VitalsDue.isDue(admitted, latest == null ? null : latest.recordedAt(), asOf),
                List.copyOf(notes), mar.doseCount(tenantId, row.id(), admitted, asOf));
    }

    /** The admission's patient; a missing one is a 404 and never a placeholder name (code review N6). */
    private PatientSummary patient(UUID tenantId, AdmissionRow row) {
        return patients.find(tenantId, row.patientId()).orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    /**
     * The caller's role read again from the active membership (security review SAH-30 M1; the {@code OwnerGuard}
     * pattern), so a role change or a deactivation applies at once and not when the 15-minute token expires. No
     * active membership, or a role outside {@code allowed}, is a 403.
     */
    private String membershipRole(UUID tenantId, UUID actor, Set<String> allowed, String message) {
        return team.findActiveByUserId(tenantId, actor).filter(TeamMember::active).map(TeamMember::role)
                .filter(allowed::contains).orElseThrow(() -> new AccessDeniedException(message));
    }

    private static LatestVitals toLatest(VitalsRow v, Names names) {
        return new LatestVitals(v.id(), v.bpSystolic() + "/" + v.bpDiastolic(), v.bpSystolic(), v.bpDiastolic(),
                v.pulse(), v.spo2(), v.tempC().setScale(1), v.rr(), v.ewsScore(), v.ewsRisk(), v.recordedAt(),
                names.of(v.recordedBy()));
    }

    private static OpenEscalation toOpen(EscalationRow row) {
        return row == null ? null : new OpenEscalation(row.id(), row.ewsScore(), row.ewsRisk(), row.targetMinutes(),
                row.status(), row.raisedAt());
    }

    /** Display names of the people who recorded things, looked up once each. */
    private final class Names {
        private final UUID tenantId;
        private final Map<UUID, String> known = new HashMap<>();

        Names(UUID tenantId) {
            this.tenantId = tenantId;
        }

        String of(UUID userId) {
            return known.computeIfAbsent(userId, id -> {
                Optional<String> name = actors.displayName(tenantId, id);
                return name.orElse(NAME_FALLBACK);
            });
        }
    }

    private static void requireRole(String role, Set<String> allowed, String message) {
        if (role == null || !allowed.contains(role)) {
            throw new AccessDeniedException(message);
        }
    }
}
