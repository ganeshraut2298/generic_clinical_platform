package com.sahaj.patient.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@code opd} needs to know about a patient to issue a token (SAH-9): name, age and sex.
 * Implemented by {@code patient}, the owner of {@code patient.patient}; {@code opd} never joins
 * that table directly from a query (same module-boundary rule as {@code platform.api}'s ports).
 * Runs in the caller's transaction (tenant already bound).
 */
public interface PatientLookupPort {

    record PatientSummary(UUID id, String name, int age, String sex) {
    }

    /** Another clinic's patient id, or any id at all, is simply absent. A merged-away record is still found. */
    Optional<PatientSummary> find(UUID tenantId, UUID patientId);
}
