package com.sahaj.opd.application;

import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-9 Queue and tokens: issue a walk-in token, advance a visit's stage one step forward, or
 * (owner only) move it back to correct a mistake. {@code opd} never joins {@code patient.patient}
 * or {@code platform.membership} directly: the patient's name/age/sex and the doctor's name, role
 * and fee come through {@link PatientLookupPort} and {@link TeamMemberPort}.
 */
@Service
public class VisitService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String ENTITY_TYPE = "VISIT";

    static final String WAITING = "WAITING";
    static final String VITALS = "VITALS";
    static final String WITH_DOCTOR = "WITH_DOCTOR";
    static final String COUNSEL = "COUNSEL";
    static final String BILLING = "BILLING";
    static final String DONE = "DONE";

    /** Every stage the database allows, whether or not this clinic has it switched on today (AC2, AC3). */
    private static final Set<String> ALL_STAGES = Set.of(WAITING, VITALS, WITH_DOCTOR, COUNSEL, BILLING, DONE);

    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");
    private static final Set<String> CREATE_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE");
    private static final Set<String> MOVE_ANY_ROLES = Set.of("OWNER", "FRONT_DESK");

    private final VisitRepository visitRepository;
    private final WorkflowService workflowService;
    private final FeeSettingsRepository feeSettingsRepository;
    private final PatientLookupPort patientLookup;
    private final TeamMemberPort teamMemberPort;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final FollowUpService followUpService;
    private final Clock clock;

    public VisitService(VisitRepository visitRepository, WorkflowService workflowService,
                         FeeSettingsRepository feeSettingsRepository, PatientLookupPort patientLookup,
                         TeamMemberPort teamMemberPort, OwnerGuard ownerGuard, AuditLog auditLog,
                         FollowUpService followUpService, Clock clock) {
        this.visitRepository = visitRepository;
        this.workflowService = workflowService;
        this.feeSettingsRepository = feeSettingsRepository;
        this.patientLookup = patientLookup;
        this.teamMemberPort = teamMemberPort;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.followUpService = followUpService;
        this.clock = clock;
    }

    /** AC1, AC5, AC8: OWNER, FRONT_DESK and NURSE issue a token (role gate also enforced on the controller). */
    @Transactional
    public VisitView issueToken(UUID tenantId, UUID actorUserId, String actorRole, VisitCreateInput input) {
        if (!CREATE_ROLES.contains(actorRole)) {
            throw new AccessDeniedException("Only the owner, front desk or a nurse can give out a token.");
        }
        if (input.patientId() == null) {
            throw new InvalidInputException("patientId", "Choose a patient");
        }
        if (input.doctorMembershipId() == null) {
            throw new InvalidInputException("doctorMembershipId", "Choose a doctor");
        }
        PatientSummary patient = patientLookup.find(tenantId, input.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = teamMemberPort.find(tenantId, input.doctorMembershipId())
                .filter(member -> member.active() && DOCTOR_ROLES.contains(member.role()))
                .orElseThrow(() -> new NotFoundException("Doctor not found"));

        FeeRow feeSettings = feeSettingsRepository.find(tenantId).orElse(FeeSettingsService.DEFAULTS);
        // SAH-27 (ADR-004 D3): a discharge that added a follow-up for this doctor counts as being seen at that instant.
        String visitType = lastSeenAt(tenantId, patient.id(), doctor.membershipId())
                .filter(last -> !last.plus(Duration.ofDays(feeSettings.followUpDays())).isBefore(clock.instant()))
                .map(last -> "FOLLOW_UP")
                .orElse("NEW");
        long feePaise = feeFor(doctor.feePaise(), visitType, feeSettings.followUpFee());

        WorkflowVersionView workflow = workflowService.current(tenantId, actorUserId);
        String firstStage = enabledStages(workflow.steps()).get(1);

        UUID id = UUID.randomUUID();
        int tokenNo = visitRepository.nextTokenNo();
        visitRepository.insert(id, patient.id(), doctor.membershipId(), visitType, firstStage, tokenNo, feePaise,
                workflow.versionNo(), actorUserId);
        VisitRow created = findRow(tenantId, id);
        followUpService.closeOnNewVisit(tenantId, actorUserId, patient.id(), doctor.membershipId(), id);
        auditLog.record("VISIT_CREATED", ENTITY_TYPE, id, null, snapshot(created));
        return toView(created, patient, doctor);
    }

    /** AC9: today's visits, server-side (Asia/Kolkata calendar day), oldest token first. */
    @Transactional(readOnly = true)
    public List<VisitView> today(UUID tenantId, String actorRole, boolean mine) {
        Instant startOfToday = LocalDate.now(clock.withZone(INDIA)).atStartOfDay(INDIA).toInstant();
        List<VisitRow> rows = visitRepository.findSince(tenantId, startOfToday);
        // "Mine" follows each visit's own workflow version (the one it was issued under), like moveStage and the board.
        Map<Integer, Set<String>> ownedByVersion = new HashMap<>();

        Map<UUID, PatientSummary> patients = new HashMap<>();
        Map<UUID, TeamMember> doctors = new HashMap<>();
        List<VisitView> views = new ArrayList<>();
        for (VisitRow row : rows) {
            if (mine && !ownedByVersion.computeIfAbsent(row.workflowVersionNo(),
                    versionNo -> ownedStages(actorRole, workflowService.stepsForVersion(tenantId, versionNo)))
                    .contains(row.stage())) {
                continue;
            }
            PatientSummary patient = patients.computeIfAbsent(row.patientId(),
                    id -> patientLookup.find(tenantId, id).orElse(null));
            TeamMember doctor = doctors.computeIfAbsent(row.doctorMembershipId(),
                    id -> teamMemberPort.find(tenantId, id).orElse(null));
            if (patient == null || doctor == null) {
                continue;
            }
            views.add(toView(row, patient, doctor));
        }
        return views;
    }

    /**
     * AC3: one step forward (own stage, or OWNER/FRONT_DESK for any card) or, owner only, an
     * explicit earlier stage to correct a mistake. The tenant and actor always come from the
     * signed-in principal (never from the request), checked by the caller before this is reached.
     */
    @Transactional
    public VisitView moveStage(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId, String targetStage) {
        if (targetStage == null || !ALL_STAGES.contains(targetStage)) {
            throw new InvalidInputException("stage", "Choose a valid stage");
        }
        VisitRow visit = findRow(tenantId, visitId);
        List<WorkflowStep> steps = workflowService.stepsForVersion(tenantId, visit.workflowVersionNo());
        List<String> enabled = enabledStages(steps);
        int currentIndex = enabled.indexOf(visit.stage());
        int targetIndex = enabled.indexOf(targetStage);
        if (targetIndex < 0) {
            throw new InvalidInputException("stage", "That stage is not available for this clinic");
        }
        if (targetIndex == currentIndex) {
            throw new InvalidInputException("stage", "Choose a different stage");
        }

        if (targetIndex > currentIndex) {
            if (targetIndex != currentIndex + 1) {
                throw new InvalidInputException("stage", "Move one stage at a time");
            }
            if (!canMoveForward(actorRole, visit.stage(), steps)) {
                throw new AccessDeniedException("You cannot move this card.");
            }
        } else {
            ownerGuard.requireOwner(tenantId, actorUserId);
        }

        visitRepository.updateStage(visitId, targetStage, actorUserId);
        VisitRow after = findRow(tenantId, visitId);
        auditLog.record("VISIT_STAGE_MOVED", ENTITY_TYPE, visitId,
                Map.of("stage", visit.stage()), Map.of("stage", after.stage()));
        if (targetIndex > currentIndex && WITH_DOCTOR.equals(visit.stage()) && isOn(steps, "followup")) {
            followUpService.createForConsultDone(tenantId, actorUserId, after);
        }

        PatientSummary patient = patientLookup.find(tenantId, after.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        TeamMember doctor = teamMemberPort.find(tenantId, after.doctorMembershipId())
                .orElseThrow(() -> new NotFoundException("Doctor not found"));
        return toView(after, patient, doctor);
    }

    private boolean canMoveForward(String actorRole, String currentStage, List<WorkflowStep> steps) {
        return ownedStages(actorRole, steps).contains(currentStage);
    }

    /**
     * AC4: the stages this role owns on the board; OWNER/FRONT_DESK see everyone, so {@code mine} does not
     * filter them. Vitals and counselling ownership comes from the workflow steps (SAH-12); the doctor
     * always owns the consultation.
     */
    private Set<String> ownedStages(String actorRole, List<WorkflowStep> steps) {
        if (MOVE_ANY_ROLES.contains(actorRole)) {
            return ALL_STAGES;
        }
        Set<String> owned = new HashSet<>();
        if (DOCTOR_ROLES.contains(actorRole)) {
            owned.add(WITH_DOCTOR);
        }
        addIfOwnedBy(owned, steps, "vitals", VITALS, actorRole);
        addIfOwnedBy(owned, steps, "counsel", COUNSEL, actorRole);
        return owned;
    }

    private static void addIfOwnedBy(Set<String> owned, List<WorkflowStep> steps, String key, String stage,
                                      String actorRole) {
        for (WorkflowStep step : steps) {
            if (step.key().equals(key) && step.owner() != null) {
                boolean matches = step.owner().equals(actorRole)
                        || ("DOCTOR".equals(step.owner()) && DOCTOR_ROLES.contains(actorRole));
                if (matches) {
                    owned.add(stage);
                }
            }
        }
    }

    /** WAITING -> [VITALS] -> WITH_DOCTOR -> [COUNSEL] -> BILLING -> DONE; bracketed stages only when that step is on (SAH-12). */
    static List<String> enabledStages(List<WorkflowStep> steps) {
        List<String> stages = new ArrayList<>();
        stages.add(WAITING);
        if (isOn(steps, "vitals")) {
            stages.add(VITALS);
        }
        stages.add(WITH_DOCTOR);
        if (isOn(steps, "counsel")) {
            stages.add(COUNSEL);
        }
        stages.add(BILLING);
        stages.add(DONE);
        return stages;
    }

    private static boolean isOn(List<WorkflowStep> steps, String key) {
        return steps.stream().anyMatch(step -> step.key().equals(key) && step.on());
    }

    /** The later of the patient's last visit with the doctor and the last discharge follow-up for the same doctor. */
    private Optional<Instant> lastSeenAt(UUID tenantId, UUID patientId, UUID doctorMembershipId) {
        Optional<Instant> visit = visitRepository.lastVisitAt(tenantId, patientId, doctorMembershipId);
        Optional<Instant> discharge = followUpService.lastDischargeAt(tenantId, patientId, doctorMembershipId);
        if (visit.isEmpty()) {
            return discharge;
        }
        if (discharge.isPresent() && discharge.get().isAfter(visit.get())) {
            return discharge;
        }
        return visit;
    }

    private static long feeFor(long doctorFeePaise, String visitType, String followUpFee) {
        if (!"FOLLOW_UP".equals(visitType)) {
            return doctorFeePaise;
        }
        return switch (followUpFee) {
            case "free" -> 0L;
            case "half" -> doctorFeePaise / 2;
            default -> doctorFeePaise;
        };
    }

    private VisitRow findRow(UUID tenantId, UUID id) {
        return visitRepository.findById(tenantId, id).orElseThrow(() -> new NotFoundException("Visit not found"));
    }

    private static VisitView toView(VisitRow row, PatientSummary patient, TeamMember doctor) {
        return new VisitView(row.id(), row.tokenNo(), patient.id(), patient.name(), patient.age(), patient.sex(),
                doctor.membershipId(), doctor.name(), row.visitType(), row.stage(), row.feePaise(), row.createdAt(),
                row.workflowVersionNo());
    }

    /** Never the patient's or doctor's name (SAH-8/SAH-9 audit rule): only ids and business facts. */
    private static Map<String, Object> snapshot(VisitRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("patientId", row.patientId());
        values.put("doctorMembershipId", row.doctorMembershipId());
        values.put("visitType", row.visitType());
        values.put("stage", row.stage());
        values.put("tokenNo", row.tokenNo());
        values.put("feePaise", row.feePaise());
        return values;
    }
}
