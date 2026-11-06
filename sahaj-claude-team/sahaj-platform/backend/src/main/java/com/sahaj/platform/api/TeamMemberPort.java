package com.sahaj.platform.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** SAH-24: many members (active or not) in one call, keyed by membership id. Unknown ids are absent. */
    Map<UUID, TeamMember> findAll(UUID tenantId, Collection<UUID> membershipIds);

    /** Every active DOCTOR/VISITING_DOCTOR on the bound clinic's team (SAH-11: one column per doctor). */
    List<TeamMember> findActiveDoctors(UUID tenantId);

    /** SAH-20: every active member of the bound clinic's team (any role), owners first, with display name and role. */
    List<TeamMember> findActiveMembers(UUID tenantId);

    /** SAH-21: the signed-in user's own active membership in the bound clinic, if any. */
    Optional<TeamMember> findActiveByUserId(UUID tenantId, UUID userId);

    /** SAH-27: the doctor profile's specialty, empty when not set, not a doctor, or another clinic's id. */
    Optional<String> doctorSpecialty(UUID tenantId, UUID membershipId);
}
