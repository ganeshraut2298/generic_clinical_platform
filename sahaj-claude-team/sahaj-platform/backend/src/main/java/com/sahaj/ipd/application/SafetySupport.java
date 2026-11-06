package com.sahaj.ipd.application;

import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/** SAH-33 the two role checks every Safety board call makes, shared by the read and the write service. */
final class SafetySupport {

    private SafetySupport() {
    }

    /** The role in the token must be one of {@code allowed} (403 forbidden). */
    static void requireRole(String role, Set<String> allowed, String message) {
        if (role == null || !allowed.contains(role)) {
            throw new AccessDeniedException(message);
        }
    }

    /**
     * The caller's role read again from the active membership (security review SAH-30 M1; the {@code OwnerGuard}
     * pattern), so a role change or a deactivation applies at once and not when the 15-minute token expires. No
     * active membership, or a role outside {@code allowed}, is a 403.
     */
    static String membershipRole(TeamMemberPort team, UUID tenantId, UUID actor, Set<String> allowed,
                                 String message) {
        return team.findActiveByUserId(tenantId, actor).filter(TeamMember::active).map(TeamMember::role)
                .filter(allowed::contains).orElseThrow(() -> new AccessDeniedException(message));
    }

    /** The board shows the nurse's view (second tile, notice ending) for a NURSE membership, the doctor's otherwise. */
    static boolean isNurseView(String memberRole) {
        return EscalationRules.NURSE_ROLES.contains(memberRole);
    }
}
