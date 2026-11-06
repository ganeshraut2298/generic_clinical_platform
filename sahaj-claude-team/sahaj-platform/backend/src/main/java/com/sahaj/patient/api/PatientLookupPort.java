package com.sahaj.patient.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@code opd} needs to know about a patient to issue a token (SAH-9): name, age, sex and mobile (SAH-14: shown on screen only, never logged).
 * Implemented by {@code patient}, the owner of {@code patient.patient}; {@code opd} never joins
 * that table directly from a query (same module-boundary rule as {@code platform.api}'s ports).
 * Runs in the caller's transaction (tenant already bound).
 */
public interface PatientLookupPort {

    /** {@code merged} is true for a record that was merged away into another (SAH-23: it cannot be admitted). */
    record PatientSummary(UUID id, String name, int age, String sex, String mobile, boolean merged) {

        public PatientSummary(UUID id, String name, int age, String sex, String mobile) {
            this(id, name, age, sex, mobile, false);
        }

        /** Ids and the merged flag only: the name and the mobile number must never reach a log (security review L5). */
        @Override
        public String toString() {
            return "PatientSummary[id=" + id + ", merged=" + merged + "]";
        }
    }

    /** Another clinic's patient id, or any id at all, is simply absent. A merged-away record is still found. */
    Optional<PatientSummary> find(UUID tenantId, UUID patientId);

    /** SAH-24: many patients in one query, keyed by id. Unknown or other-clinic ids are absent. */
    Map<UUID, PatientSummary> findAll(UUID tenantId, Collection<UUID> patientIds);
}
