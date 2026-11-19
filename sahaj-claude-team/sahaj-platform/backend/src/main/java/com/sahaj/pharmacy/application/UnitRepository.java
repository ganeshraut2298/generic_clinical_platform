package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.Unit;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.unit}: one row per clinic, created by the first master write. Row level security scopes it. */
public interface UnitRepository {

    /** Empty until the first master write. */
    Optional<Unit> find(UUID tenantId);

    /**
     * Creates the clinic's unit row when it is absent (with {@code code}, defaults for the rest), then returns it locked
     * ({@code select ... for update}) until the transaction ends. Serialises every writer of the clinic's stores and
     * settings, so a following read is not stale.
     */
    Unit ensureAndLock(UUID tenantId, String code, UUID actorUserId);

    /** Saves the settings when the row's version is still {@code version}; false when it is not. Sets the saved marker. */
    boolean updateSettings(UUID tenantId, UUID unitId, long version, String gstin, String drugLicenceNo,
                           LocalDate drugLicenceValidTill, IntegrationMode integrationMode, UUID actorUserId);
}
