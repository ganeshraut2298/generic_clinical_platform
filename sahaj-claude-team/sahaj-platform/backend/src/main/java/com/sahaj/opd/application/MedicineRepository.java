package com.sahaj.opd.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over the global, read-only {@code opd.medicine} and this clinic's {@code opd.medicine_favourite}
 * (SAH-7). Needs {@code app.tenant_id} bound, so only this clinic's favourites are seen.
 */
public interface MedicineRepository {

    /**
     * Medicines whose name starts with {@code prefix} (blank means all), the clinic's favourites first,
     * then by name. {@code favouritesOnly} keeps just the favourites.
     */
    MedicinePage search(UUID tenantId, String prefix, boolean favouritesOnly, int limit, int offset);

    Optional<String> findName(UUID medicineId);

    /** Returns 1 when newly marked, 0 when it already was. */
    int addFavourite(UUID medicineId, UUID actorUserId);

    /** Returns 1 when removed, 0 when it was not a favourite of this clinic. */
    int removeFavourite(UUID tenantId, UUID medicineId);

    record MedicineRow(UUID id, String name, String form, String schedule, String usualDose, boolean favourite) {
    }

    record MedicinePage(List<MedicineRow> items, long total) {
    }
}
