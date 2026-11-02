package com.sahaj.platform.application;

import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.membership}. */
public interface MembershipRepository {

    /** Every clinic this user belongs to, in any status. Needs {@code app.user_id} bound (migration 1011). */
    List<Membership> findByUserId(UUID userId);

    /** The one membership row for this user in the bound tenant. Needs {@code app.tenant_id} bound. */
    Optional<Membership> findByTenantAndUser(UUID tenantId, UUID userId);

    void insert(UUID id, UUID tenantId, UUID userId, MembershipRole role, Instant lastLoginAt);

    void touchLastLogin(UUID membershipId, Instant at);
}
