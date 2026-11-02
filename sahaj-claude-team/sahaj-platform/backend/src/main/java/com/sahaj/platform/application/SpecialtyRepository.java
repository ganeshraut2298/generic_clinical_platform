package com.sahaj.platform.application;

import java.util.List;
import java.util.Optional;

/** Port over the global, read-only {@code platform.specialty} and {@code platform.specialty_template} (SAH-7). */
public interface SpecialtyRepository {

    /** All specialties in the order of the Specialty template page. */
    List<SpecialtyRow> findAll();

    Optional<SpecialtyRow> find(String code);

    /** {@code templateJson} is the stored JSON text: {@code schema_version}, {@code features}, {@code services}. */
    record SpecialtyRow(String code, String name, int phase, String templateJson) {
    }
}
