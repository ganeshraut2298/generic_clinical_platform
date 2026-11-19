package com.sahaj.pharmacy.application;

import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.staff_preference}: the last working store of a member, for convenience only. */
public interface StaffPreferenceRepository {

    Optional<UUID> lastStore(UUID tenantId, UUID membershipId);

    /** Inserts or updates the member's one row. Not audited (a preference, not a master). */
    void save(UUID tenantId, UUID unitId, UUID membershipId, UUID storeId, UUID actorUserId);
}
