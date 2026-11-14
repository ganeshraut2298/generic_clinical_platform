package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyViews.Level;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-46-4 and SAH-52 (ADR-020 D9): implements {@link EmrEncounterSource} so that the Doctor EMR of {@code opd} can open
 * an admitted patient or an Emergency case without {@code opd} depending on {@code ipd} (that would be a module cycle).
 * It acts for the signed-in user inside the caller's transaction and answers only facts: ids, fixed words, and for an
 * Emergency case the case's own display name, age and sex (screen only, never logged here).
 *
 * <p>Who may reach what (ADR-020 D9, ADR-017):
 * <ul>
 *   <li>An admission: the caller's membership role is re-read. A visiting doctor is FORBIDDEN (IPD answers 403 to
 *       visiting doctors until story 34D), as is any role that is not OWNER or DOCTOR. For the others
 *       {@link ScopeGuard#requireAdmission} runs exactly as an IPD by-id read: a caller whose scope does not reach the
 *       admission (ward scope, no live break-glass grant) gets OUTSIDE_SCOPE, and the guard logs that refusal as it
 *       does today. An active grant makes the answer ALLOWED; the grant itself is audited by the IPD path.</li>
 *   <li>An Emergency case: the same roles as the Emergency queue read (OWNER, DOCTOR); a visiting doctor is
 *       FORBIDDEN. An Emergency case has no ward, so there is no scope check.</li>
 * </ul>
 * The clinic is bound by the caller, so another clinic's id is absent here. Nothing is written.
 */
@Component
class EmrEncounterSourceAdapter implements EmrEncounterSource {

    private static final Set<String> ALLOWED_ROLES = Set.of("OWNER", "DOCTOR");
    private static final String ROUTE = "emr-open";

    private final AdmissionRepository admissions;
    private final EmergencyCaseRepository cases;
    private final ScopeGuard guard;
    private final AdmissionService admissionService;
    private final IpdCatalog catalog;
    private final TeamMemberPort team;

    EmrEncounterSourceAdapter(AdmissionRepository admissions, EmergencyCaseRepository cases, ScopeGuard guard,
                              AdmissionService admissionService, IpdCatalog catalog, TeamMemberPort team) {
        this.admissions = admissions;
        this.cases = cases;
        this.guard = guard;
        this.admissionService = admissionService;
        this.catalog = catalog;
        this.team = team;
    }

    @Override
    public Optional<AdmissionFacts> admission(UUID tenantId, UUID admissionId) {
        Optional<AdmissionRow> found = admissions.find(tenantId, admissionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdmissionRow row = found.get();
        ClinicUser user = ClinicUser.current();
        Access access = admissionAccess(tenantId, user, admissionId);
        int dayNumber = access == Access.ALLOWED ? admissionService.emrHeader(tenantId, row).dayNumber() : 1;
        return Optional.of(new AdmissionFacts(row.id(), row.patientId(), row.ipNumber(), row.status(), dayNumber,
                row.doctorMembershipId(), access));
    }

    @Override
    public Optional<EmergencyCaseFacts> emergencyCase(UUID tenantId, UUID caseId) {
        Optional<CaseRow> found = cases.find(tenantId, caseId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CaseRow row = found.get();
        Access access = roleAccess(tenantId, ClinicUser.current());
        String levelName = "Level " + row.level();
        if (access == Access.ALLOWED) {
            levelName = EmergencyRules.levels(catalog.emergencySetup(tenantId)).stream()
                    .filter(level -> level.level() == row.level()).map(Level::name).findFirst().orElse(levelName);
        }
        return Optional.of(new EmergencyCaseFacts(row.id(), row.patientId(), row.status(), row.level(), levelName,
                row.displayName(), row.age(), row.sex(), access, row.arrivedAt()));
    }

    @Override
    public Optional<AdmissionRef> openAdmissionOf(UUID tenantId, UUID patientId) {
        return admissions.findAdmittedOf(tenantId, patientId)
                .map(ref -> new AdmissionRef(ref.admissionId(), ref.ipNumber()));
    }

    private Access admissionAccess(UUID tenantId, ClinicUser user, UUID admissionId) {
        Access byRole = roleAccess(tenantId, user);
        if (byRole != Access.ALLOWED) {
            return byRole;
        }
        try {
            guard.requireAdmission(tenantId, user.userId(), user.role(), memberRole(tenantId, user), admissionId, ROUTE);
            return Access.ALLOWED;
        } catch (OutsideScopeException e) {
            return Access.OUTSIDE_SCOPE;
        }
    }

    /** OWNER and DOCTOR, by the membership read now and by the role in the token; everything else is FORBIDDEN. */
    private Access roleAccess(UUID tenantId, ClinicUser user) {
        String member = memberRole(tenantId, user);
        boolean allowed = member != null && ALLOWED_ROLES.contains(member)
                && (user.role() == null || ALLOWED_ROLES.contains(user.role()));
        return allowed ? Access.ALLOWED : Access.FORBIDDEN;
    }

    private String memberRole(UUID tenantId, ClinicUser user) {
        return team.findActiveByUserId(tenantId, user.userId()).filter(TeamMember::active).map(TeamMember::role)
                .orElse(null);
    }
}
