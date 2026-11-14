package com.sahaj.opd.application.emr.specialty;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port over the global, read-only reference tables of the specialty screens (opd.emr_immunization_group,
 * emr_immunization_vaccine, emr_growth_band, emr_milestone, emr_joint_item, emr_chemo_protocol) and the medicine master
 * rows they name. No clinic column: every clinic reads the same rows. The tables change by migration only, so the adapter
 * may keep the answer in memory.
 */
public interface SpecialtyReferenceRepository {

    /** A master medicine (opd.medicine) a reference list names, so a row added from the list keeps the allergy check. */
    record MedicineRef(UUID id, String name, String form) {
    }

    SpecialtyReferenceData load();

    /**
     * The master medicines with these names (ignoring case and spaces), keyed by the normalised name (lower case, single
     * spaces). A name with no master row is absent. The default answers none (for a test double).
     */
    default Map<String, MedicineRef> medicinesByName(Collection<String> names) {
        return Map.of();
    }
}
