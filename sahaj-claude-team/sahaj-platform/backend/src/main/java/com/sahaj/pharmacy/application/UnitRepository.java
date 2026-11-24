package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.Unit;
import java.time.LocalDate;
import java.util.List;
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

    /** Saves the five original settings and leaves the sale settings as they are. Same rules as the full overload. */
    default boolean updateSettings(UUID tenantId, UUID unitId, long version, String gstin, String drugLicenceNo,
                                   LocalDate drugLicenceValidTill, IntegrationMode integrationMode, UUID actorUserId) {
        return updateSettings(tenantId, unitId, version, gstin, drugLicenceNo, drugLicenceValidTill, integrationMode,
                actorUserId, null);
    }

    /**
     * Saves the settings when the row's version is still {@code version}; false when it is not. Sets the saved marker.
     * {@code sale} is the SAH-80-1 sale settings to store, or null to leave them as they are.
     */
    boolean updateSettings(UUID tenantId, UUID unitId, long version, String gstin, String drugLicenceNo,
                           LocalDate drugLicenceValidTill, IntegrationMode integrationMode, UUID actorUserId,
                           SaleSettings sale);

    /** The sale settings of a unit (SAH-80-1): return window, refund modes and the Schedule X licence. */
    record SaleSettings(Integer returnWindowDays, List<String> refundModes, String scheduleXLicenceNo,
                        LocalDate scheduleXLicenceValidTill) {
    }
}
