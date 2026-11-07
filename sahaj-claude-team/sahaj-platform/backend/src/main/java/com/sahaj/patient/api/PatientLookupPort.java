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

    /**
     * {@code merged} is true for a record that was merged away into another (SAH-23: it cannot be admitted).
     * {@code uhid} is the patient's UHID as stored (SAH-36, ADR-013 D15); the constructors without it leave it null.
     */
    record PatientSummary(UUID id, String name, int age, String sex, String mobile, boolean merged, String uhid) {

        public PatientSummary(UUID id, String name, int age, String sex, String mobile, boolean merged) {
            this(id, name, age, sex, mobile, merged, null);
        }

        public PatientSummary(UUID id, String name, int age, String sex, String mobile) {
            this(id, name, age, sex, mobile, false, null);
        }

        /**
         * Ids and the merged flag only: the name, the UHID and the mobile number must never reach a log (security
         * review L5).
         */
        @Override
        public String toString() {
            return "PatientSummary[id=" + id + ", merged=" + merged + "]";
        }
    }

    /** Another clinic's patient id, or any id at all, is simply absent. A merged-away record is still found. */
    Optional<PatientSummary> find(UUID tenantId, UUID patientId);

    /** SAH-24: many patients in one query, keyed by id. Unknown or other-clinic ids are absent. */
    Map<UUID, PatientSummary> findAll(UUID tenantId, Collection<UUID> patientIds);

    /**
     * SAH-36 (ADR-013 D15): many patients in one batch, keyed by the id asked for, for a caller without clinical
     * access. A merged-away record answers with the record it was merged into (id, UHID, name, age, sex), the same
     * resolution as {@link PatientClinicalPort#findAll}, so every role sees the same identity. The mobile number is
     * never filled in, and the allergy text is never read. Unknown or other-clinic ids are absent. {@link #find} and
     * {@link #findAll} do not follow a merge.
     */
    Map<UUID, PatientSummary> findAllCurrent(UUID tenantId, Collection<UUID> patientIds);
}
