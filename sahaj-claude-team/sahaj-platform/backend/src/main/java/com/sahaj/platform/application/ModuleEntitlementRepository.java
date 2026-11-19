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

    /**
     * SAH-78 (ADR-028 D11): {@code pharmacy_on} of the clinic's row; false when there is no row yet. One statement for
     * the pharmacy gate. Nothing writes the column here: the Sahaj admin switch is not built yet (DEC-064 follow-up), so a
     * test fixture or the dev setup sets it. {@code GET /me} does not call this: it reads {@link EntitlementRow#pharmacyOn}
     * from the row it already loads (one statement, code review F-6).
     */
    default boolean pharmacyOn(UUID tenantId) {
        return false;
    }

    /**
     * {@code pharmacyOn} is the sixth component (SAH-78, F-6): it is read with the row and never written by {@link #upsert}
     * or {@link #lockForWrite} (the column keeps its value). The five-argument constructor stays, with pharmacy off, so
     * every existing caller compiles and behaves as before.
     */
    record EntitlementRow(String ipdState, Instant ipdTrialEndsAt, boolean hraOn, int bedLimit,
                           Instant ipdRequestedAt, boolean pharmacyOn) {

        public EntitlementRow(String ipdState, Instant ipdTrialEndsAt, boolean hraOn, int bedLimit,
                              Instant ipdRequestedAt) {
            this(ipdState, ipdTrialEndsAt, hraOn, bedLimit, ipdRequestedAt, false);
        }
    }
}
