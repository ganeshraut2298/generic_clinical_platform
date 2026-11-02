package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.membership} joined with the person and doctor details (SAH-6). Needs {@code app.tenant_id} bound. */
public interface TeamRepository {

    /** Everyone on the bound clinic's team in any status, owners first. */
    List<TeamRow> findAll(UUID tenantId);

    /** The member with this membership id in the bound clinic; another clinic's id is simply absent. */
    Optional<TeamRow> findById(UUID tenantId, UUID membershipId);

    /** The member row of this person in the bound clinic. */
    Optional<TeamRow> findByUser(UUID tenantId, UUID userId);

    /** Locks the bound clinic's row until the transaction ends, so two adds cannot both pass the plan limit. */
    void lockTenant();

    int countActive(UUID tenantId);

    /** Active owners other than this membership. */
    int countOtherActiveOwners(UUID tenantId, UUID membershipId);

    void insert(UUID id, UUID userId, MembershipRole role, String displayName, UUID actorUserId);

    /** Null {@code role} or {@code displayName} leaves that column as it is. */
    void update(UUID id, MembershipRole role, String displayName, UUID actorUserId);

    void updateStatus(UUID id, MembershipStatus status, UUID actorUserId);

    /** One row of the team. {@code mobile} is the full number: mask it before it leaves the application layer. */
    record TeamRow(UUID id, UUID userId, String name, String mobile, MembershipRole role, MembershipStatus status,
                   Instant lastLoginAt, boolean hasDoctorProfile, String specialty, String degree,
                   String registrationNo, long feePaise) {
    }
}
