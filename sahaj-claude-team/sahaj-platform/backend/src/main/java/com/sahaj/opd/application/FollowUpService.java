package com.sahaj.opd.application;

import com.sahaj.opd.api.FollowUpPort;
import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.opd.application.FollowUpRepository.FollowUpRow;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-14 Follow-up lists. A follow-up is created when a visit leaves the doctor step (see
 * {@link VisitService#moveStage}) and closed when the same patient gets a new token with the same doctor
 * ({@link VisitService#issueToken}). Status is computed from today's Asia/Kolkata date, never stored. Audit rows
 * carry ids only: never the patient's name, mobile or the reason.
 */
@Service
public class FollowUpService {

    static final String ENTITY_TYPE = "FOLLOW_UP";
    static final String DEFAULT_REASON = "Review after treatment";
    /** The longest reason the table keeps (char_length 200, counted in code points). */
    static final int MAX_REASON = 200;
    static final String DUE = "DUE";
    static final String MISSED = "MISSED";
    static final String UPCOMING = "UPCOMING";
    static final String RETURNED = "RETURNED";
    private static final Set<String> STATUSES = Set.of(DUE, MISSED, UPCOMING, RETURNED);
    private static final ZoneId INDIA = VisitService.INDIA;
    /** Same roles as PatientRoles.READ: only these may see a patient mobile; others get null (SAH-14 security review M1). */
    private static final Set<String> MOBILE_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR");
    /**
     * SAH-27 security review M1: who may read the clinical tail of a discharge follow-up reason. Other roles get
     * {@link #MASKED_DISCHARGE_REASON}.
     */
    private static final Set<String> REASON_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR");
    static final String MASKED_DISCHARGE_REASON = "Review after discharge";

    private final FollowUpRepository repository;
    private final FeeSettingsRepository feeSettingsRepository;
    private final PatientLookupPort patientLookup;
    private final TeamMemberPort teamMemberPort;
    private final AuditLog auditLog;
    private final Clock clock;

    public FollowUpService(FollowUpRepository repository, FeeSettingsRepository feeSettingsRepository,
                            PatientLookupPort patientLookup, TeamMemberPort teamMemberPort, AuditLog auditLog,
                            Clock clock) {
        this.repository = repository;
        this.feeSettingsRepository = feeSettingsRepository;
        this.patientLookup = patientLookup;
        this.teamMemberPort = teamMemberPort;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** AC6: any clinic role reads (the controller gates the role). A bad or missing status is a 400. */
    @Transactional(readOnly = true)
    public FollowUpView list(UUID tenantId, String actorRole, String status) {
        if (status == null || !STATUSES.contains(status)) {
            throw new InvalidInputException("status", "Choose DUE, MISSED, UPCOMING or RETURNED");
        }
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        FollowUpRepository.Counts counts = repository.counts(tenantId, today);
        List<FollowUpRow> rows = repository.findByStatus(tenantId, status, today);

        boolean showMobile = actorRole != null && MOBILE_ROLES.contains(actorRole);
        boolean showReason = actorRole != null && REASON_ROLES.contains(actorRole);
        Map<UUID, PatientSummary> patients = new HashMap<>();
        Map<UUID, TeamMember> doctors = new HashMap<>();
        List<FollowUpView.Item> items = rows.stream().map(row -> {
            PatientSummary patient = patients.computeIfAbsent(row.patientId(),
                    id -> patientLookup.find(tenantId, id).orElse(null));
            TeamMember doctor = doctors.computeIfAbsent(row.doctorMembershipId(),
                    id -> teamMemberPort.find(tenantId, id).orElse(null));
            String computed = statusOf(row, today);
            int daysLate = MISSED.equals(computed) ? (int) ChronoUnit.DAYS.between(row.dueDate(), today) : 0;
            return new FollowUpView.Item(row.id(), row.patientId(), patient == null ? "" : patient.name(),
                    patient == null || !showMobile ? null : patient.mobile(), row.doctorMembershipId(),
                    doctor == null ? "" : doctor.name(), row.dueDate(),
                    row.sourceAdmissionId() != null && !showReason ? MASKED_DISCHARGE_REASON : row.reason(),
                    computed, daysLate,
                    row.sourceVisitId(), row.sourceAdmissionId(), row.returnedVisitId());
        }).toList();
        return new FollowUpView(new FollowUpView.Counts(counts.due(), counts.missed(), counts.upcoming(),
                counts.returned()), items);
    }

    /** SAH-15: the four tab counts relative to today (Asia/Kolkata), the single source for the Today figures. */
    @Transactional(readOnly = true)
    public FollowUpView.Counts counts(UUID tenantId) {
        FollowUpRepository.Counts counts = repository.counts(tenantId, LocalDate.now(clock.withZone(INDIA)));
        return new FollowUpView.Counts(counts.due(), counts.missed(), counts.upcoming(), counts.returned());
    }

    /** SAH-15: follow-ups whose patient came back since the start of today (Asia/Kolkata). */
    @Transactional(readOnly = true)
    public int returnedToday(UUID tenantId) {
        Instant startOfToday = LocalDate.now(clock.withZone(INDIA)).atStartOfDay(INDIA).toInstant();
        return repository.countReturnedSince(tenantId, startOfToday);
    }

    /** RETURNED if a return visit is linked; otherwise by today's IST date against the due date. */
    static String statusOf(FollowUpRow row, LocalDate today) {
        if (row.returnedVisitId() != null) {
            return RETURNED;
        }
        int cmp = row.dueDate().compareTo(today);
        if (cmp == 0) {
            return DUE;
        }
        return cmp < 0 ? MISSED : UPCOMING;
    }

    /**
     * AC1: called in the move transaction when a visit leaves WITH_DOCTOR with its own workflow version's
     * follow-up step on. A retried move hits the unique source visit and creates nothing.
     */
    public void createForConsultDone(UUID tenantId, UUID actorUserId, VisitRow visit) {
        FeeRow fees = feeSettingsRepository.find(tenantId).orElse(FeeSettingsService.DEFAULTS);
        LocalDate visitDay = visit.createdAt().atZone(INDIA).toLocalDate();
        LocalDate dueDate = visitDay.plusDays(fees.followUpDays());
        UUID id = UUID.randomUUID();
        boolean inserted = repository.insertIfAbsent(id, visit.patientId(), visit.doctorMembershipId(), visit.id(),
                dueDate, DEFAULT_REASON, actorUserId);
        if (inserted) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("patientId", visit.patientId());
            after.put("doctorMembershipId", visit.doctorMembershipId());
            after.put("sourceVisitId", visit.id());
            after.put("dueDate", dueDate.toString());
            auditLog.record("FOLLOW_UP_CREATED", ENTITY_TYPE, id, null, after);
        }
    }

    /**
     * SAH-27 (ADR-004): adds the follow-up of an IPD discharge in the caller's transaction. Idempotent per admission:
     * a second call writes and audits nothing and returns the first id. A null field or a blank or too long reason
     * (more than 200 code points) is a caller bug and throws IllegalArgumentException.
     */
    public UUID createForDischarge(UUID tenantId, UUID actorUserId, FollowUpPort.DischargeFollowUp followUp) {
        if (tenantId == null || actorUserId == null || followUp == null || followUp.sourceAdmissionId() == null
                || followUp.patientId() == null || followUp.doctorMembershipId() == null
                || followUp.dueDate() == null || followUp.reason() == null) {
            throw new IllegalArgumentException("A follow-up from a discharge needs every field");
        }
        String reason = followUp.reason();
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > MAX_REASON) {
            throw new IllegalArgumentException("The follow-up reason must be 1 to 200 characters");
        }
        UUID id = UUID.randomUUID();
        boolean inserted = repository.insertForAdmissionIfAbsent(id, followUp.patientId(), followUp.doctorMembershipId(),
                followUp.sourceAdmissionId(), followUp.dueDate(), reason, actorUserId);
        if (!inserted) {
            return repository.findIdBySourceAdmission(tenantId, followUp.sourceAdmissionId())
                    .orElseThrow(() -> new IllegalStateException("The follow-up of the admission was not found"));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("patientId", followUp.patientId());
        after.put("doctorMembershipId", followUp.doctorMembershipId());
        after.put("sourceAdmissionId", followUp.sourceAdmissionId());
        after.put("dueDate", followUp.dueDate().toString());
        auditLog.record("FOLLOW_UP_CREATED", ENTITY_TYPE, id, null, after);
        return id;
    }

    /** SAH-27: the OPD fee rule (follow-up window and fee) with the SAH-7 defaults when never saved. */
    public FollowUpPort.FeeRule feeRule(UUID tenantId) {
        FeeRow fees = feeSettingsRepository.find(tenantId).orElse(FeeSettingsService.DEFAULTS);
        return new FollowUpPort.FeeRule(fees.followUpDays(), fees.followUpFee());
    }

    /** SAH-27: when a discharge last added a follow-up for this patient and doctor; the fee rule counts it as a visit. */
    public Optional<Instant> lastDischargeAt(UUID tenantId, UUID patientId, UUID doctorMembershipId) {
        return repository.lastDischargeAt(tenantId, patientId, doctorMembershipId);
    }

    /** AC3: called after a new visit is inserted; closes the oldest open follow-up of the same patient and doctor. */
    public void closeOnNewVisit(UUID tenantId, UUID actorUserId, UUID patientId, UUID doctorMembershipId,
                                 UUID newVisitId) {
        repository.closeOldestOpen(tenantId, patientId, doctorMembershipId, newVisitId, actorUserId)
                .ifPresent(id -> auditLog.record("FOLLOW_UP_RETURNED", ENTITY_TYPE, id, null,
                        Map.of("returnedVisitId", newVisitId)));
    }
}
