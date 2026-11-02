package com.sahaj.platform.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@code opd} needs to know about a clinic team member to issue a token or move a visit's
 * stage (SAH-9): their display name, role, whether their membership is still active, and - for a
 * doctor - the consultation fee from their doctor profile. Implemented by {@code platform}, the
 * owner of {@code platform.membership} and {@code platform.doctor_profile}; {@code opd} never
 * queries those tables directly, the same module-boundary rule as {@link OwnerGuard} and
 * {@link AuditLog}. Runs in the caller's transaction (tenant already bound).
 */
public interface TeamMemberPort {

    /** {@code feePaise} is 0 for a member with no doctor profile. */
    record TeamMember(UUID membershipId, String name, String role, boolean active, long feePaise) {
    }

    /** Another clinic's membership id, or any id at all, is simply absent. */
    Optional<TeamMember> find(UUID tenantId, UUID membershipId);

    /** Every active DOCTOR/VISITING_DOCTOR on the bound clinic's team (SAH-11: one column per doctor). */
    List<TeamMember> findActiveDoctors(UUID tenantId);
}
