package com.sahaj.opd.application.emr;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over the global SAH-7 medicine master with the phase 3 columns (allergy class and row defaults, ADR-022 D4, D5),
 * the clinic's favourites and the allergy class reference. Reads only; needs {@code app.tenant_id} bound for the
 * favourites and the doctor's use counts.
 */
public interface EmrMedicineRepository {

    /** {@code useCount} is this doctor's signed uses (0 until SAH-51). Defaults are null when not seeded. */
    record MedicineRow(UUID id, String name, String form, String schedule, String allergyClass,
                       String defaultFrequency, Integer defaultDays, String defaultWhen, boolean favourite,
                       int useCount) {
    }

    record MedicinePage(List<MedicineRow> items, long total) {
    }

    record AllergyClassRow(String classKey, String label) {
    }

    Optional<MedicineRow> find(UUID tenantId, UUID medicineId);

    /** Prefix match on the name: the doctor's use count desc, then favourites, then name. */
    MedicinePage search(UUID tenantId, UUID doctorMembershipId, String prefix, int limit, int offset);

    /** The clinic's favourites, name A to Z. */
    List<MedicineRow> favourites(UUID tenantId);

    List<AllergyClassRow> allergyClasses();
}
