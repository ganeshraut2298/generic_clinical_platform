package com.sahaj.opd.application.emr;

import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The two fixed EMR capabilities of ADR-019 D8, decided in code and not by cells of the "Who can do what" grid.
 * Both read the membership again instead of trusting the role in the access token, so a person demoted a moment ago
 * is refused at once. Must be called inside the caller's transaction (the clinic is bound there).
 *
 * <ul>
 * <li>{@value #CONSULTATION_WRITE}: an active DOCTOR or VISITING_DOCTOR, or an active OWNER who has doctor details.</li>
 * <li>{@value #EMR_SETUP_EDIT}: an active OWNER.</li>
 * </ul>
 */
@Component
public class EmrAccess {

    public static final String CONSULTATION_WRITE = "clinic.consultation.write";
    public static final String EMR_SETUP_EDIT = "clinic.emr_setup.edit";

    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "VISITING_DOCTOR");

    private final TeamMemberPort teamMembers;
    private final OwnerGuard ownerGuard;

    public EmrAccess(TeamMemberPort teamMembers, OwnerGuard ownerGuard) {
        this.teamMembers = teamMembers;
        this.ownerGuard = ownerGuard;
    }

    /**
     * Returns the caller's own active membership. Refusal: {@link AccessDeniedException} (403 forbidden) for every
     * other role, and {@link ForbiddenException} (403 doctor-details-missing) for an owner with no doctor details.
     */
    public TeamMember requireConsultationWrite(ClinicUser user) {
        TeamMember member = teamMembers.findActiveByUserId(user.tenantId(), user.userId())
                .orElseThrow(() -> new AccessDeniedException("Not an active member of this clinic."));
        if (DOCTOR_ROLES.contains(member.role())) {
            return member;
        }
        if ("OWNER".equals(member.role())) {
            if (teamMembers.doctorDetails(user.tenantId(), member.membershipId()).isEmpty()) {
                throw new ForbiddenException("doctor-details-missing", "Doctor details are missing",
                        "Add your doctor details in Team and access to write consultations.");
            }
            return member;
        }
        throw new AccessDeniedException("Only a doctor can write consultations.");
    }

    /** Only an active owner changes EMR setup. Refusal is a 403 forbidden. */
    public void requireSetupEdit(ClinicUser user) {
        ownerGuard.requireOwner(user.tenantId(), user.userId());
    }

    /** The "canEdit" flag of the setup reads: true only for an active owner, read fresh. */
    public boolean canEditSetup(ClinicUser user) {
        return teamMembers.findActiveByUserId(user.tenantId(), user.userId())
                .map(member -> "OWNER".equals(member.role()))
                .orElse(false);
    }
}
