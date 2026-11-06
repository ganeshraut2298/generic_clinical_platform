package com.sahaj.platform.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.module_entitlement} (SAH-19): at most one row per clinic, created on first write. */
public interface ModuleEntitlementRepository {

    /** Empty until the first write. */
    Optional<EntitlementRow> find(UUID tenantId);

    /**
     * Serialises writers of this clinic (L3): creates the row from {@code defaults} when absent, then locks it
     * ({@code select ... for update}) until the transaction ends, so a following {@link #find} is not stale.
     */
    void lockForWrite(UUID tenantId, EntitlementRow defaults, UUID actorUserId);

    /** Inserts the clinic's single row or overwrites it ({@code on conflict (tenant_id)}). */
    void upsert(UUID tenantId, EntitlementRow row, UUID actorUserId);

    record EntitlementRow(String ipdState, Instant ipdTrialEndsAt, boolean hraOn, int bedLimit,
                           Instant ipdRequestedAt) {
    }
}
