package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.opd.api.PatientIpdHistorySource;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-50 (ADR-022 D10): implements {@link PatientIpdHistorySource} so the patient history of {@code opd} can list a
 * patient's admissions. It acts for the signed-in user in the caller's transaction. Only OWNER and DOCTOR have IPD
 * access through the EMR (a visiting doctor is FORBIDDEN until story 34D, as in {@link EmrEncounterSourceAdapter}); for
 * them the patient scope is applied to every admission as an IPD by-id read does. Nothing is written here, and no
 * break-glass grant is used or created.
 */
@Component
class PatientIpdHistoryAdapter implements PatientIpdHistorySource {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Set<String> ALLOWED_ROLES = Set.of("OWNER", "DOCTOR");
    private static final String ROUTE = "emr-history";

    private final AdmissionRepository admissions;
    private final ScopeGuard guard;
    private final TeamMemberPort team;

    PatientIpdHistoryAdapter(AdmissionRepository admissions, ScopeGuard guard, TeamMemberPort team) {
        this.admissions = admissions;
        this.guard = guard;
        this.team = team;
    }

    @Override
    public List<AdmissionHistory> admissionsOf(UUID tenantId, UUID patientId, int limit) {
        ClinicUser user = ClinicUser.current();
        String memberRole = memberRole(tenantId, user);
        List<AdmissionHistory> result = new ArrayList<>();
        for (AdmissionRow row : admissions.findByPatient(tenantId, patientId, limit)) {
            result.add(history(row, access(tenantId, user, memberRole, row.id())));
        }
        return result;
    }

    @Override
    public Optional<AdmissionHistory> admission(UUID tenantId, UUID patientId, UUID admissionId) {
        ClinicUser user = ClinicUser.current();
        String memberRole = memberRole(tenantId, user);
        return admissions.find(tenantId, admissionId).filter(row -> patientId.equals(row.patientId()))
                .map(row -> history(row, access(tenantId, user, memberRole, row.id())));
    }

    private String memberRole(UUID tenantId, ClinicUser user) {
        return team.findActiveByUserId(tenantId, user.userId()).filter(TeamMember::active).map(TeamMember::role)
                .orElse(null);
    }

    private Access access(UUID tenantId, ClinicUser user, String memberRole, UUID admissionId) {
        boolean allowed = memberRole != null && ALLOWED_ROLES.contains(memberRole)
                && (user.role() == null || ALLOWED_ROLES.contains(user.role()));
        if (!allowed) {
            return Access.FORBIDDEN;
        }
        try {
            guard.requireAdmission(tenantId, user.userId(), user.role(), memberRole, admissionId, ROUTE);
            return Access.ALLOWED;
        } catch (OutsideScopeException e) {
            return Access.OUTSIDE_SCOPE;
        }
    }

    private static AdmissionHistory history(AdmissionRow row, Access access) {
        Instant discharged = row.dischargedAt();
        return new AdmissionHistory(row.id(), row.admittedAt().atZone(INDIA).toLocalDate(),
                discharged == null ? null : discharged.atZone(INDIA).toLocalDate(), row.status(), row.reason(),
                "daycare".equals(row.admissionType()), row.doctorMembershipId(), row.wardName(), row.bedCode(), access);
    }
}
