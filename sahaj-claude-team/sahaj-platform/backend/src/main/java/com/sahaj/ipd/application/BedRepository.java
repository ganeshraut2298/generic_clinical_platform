package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ipd.bed} (SAH-22). Every method is scoped to the tenant. */
public interface BedRepository {

    /** All beds of the clinic in creation order. */
    List<BedRow> findAll(UUID tenantId);

    Optional<BedRow> find(UUID tenantId, UUID bedId);

    void insert(UUID tenantId, UUID id, UUID wardId, UUID roomId, String code, String categoryKey,
                UUID actorUserId, Instant now);

    void updateCategory(UUID tenantId, UUID bedId, String categoryKey);

    void delete(UUID tenantId, UUID bedId);

    /** SAH-23: the bed row locked ({@code select ... for update}) until the end of the transaction. */
    Optional<BedRow> findForUpdate(UUID tenantId, UUID bedId);

    /** SAH-23: sets the status to {@code occ}. */
    void markOccupied(UUID tenantId, UUID bedId);

    /** SAH-24: sets the status (the caller has locked the row and checked the old status). */
    void updateStatus(UUID tenantId, UUID bedId, String status);

    /** SAH-23: true when any admission, past or present, refers to the bed. */
    boolean hasAdmissionHistory(UUID tenantId, UUID bedId);

    record BedRow(UUID id, UUID wardId, UUID roomId, String code, String categoryKey, String status) {
    }
}
