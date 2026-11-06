package com.sahaj.patient.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-31 (ADR-009 D9): what {@code ipd} needs to show an identity bar and to run the medicine safety check: UHID, name,
 * age, sex and the allergy text of the patient file (SAH-8). Implemented by {@code patient}, the owner of
 * {@code patient.patient}; {@code ipd} never reads that table. Runs in the caller's transaction (tenant already bound).
 *
 * <p>The allergy text is clinical data: it goes to the screen of the person who asked and into the safety check, and
 * is never logged, audited or put in a clinical event.
 */
public interface PatientClinicalPort {

    /** {@code allergyText} is null when nothing is recorded. {@code toString} shows the id only. */
    record PatientClinical(UUID id, String uhid, String name, int age, String sex, String allergyText) {

        @Override
        public String toString() {
            return "PatientClinical[id=" + id + "]";
        }
    }

    /**
     * Another clinic's patient id, or any id at all, is simply absent. A merged-away record answers with the record it
     * was merged into (its id, UHID and allergy text; security review S4), so a later allergy update is never missed.
     */
    Optional<PatientClinical> find(UUID tenantId, UUID patientId);

    /**
     * Many patients, keyed by the id asked for (also for a merged-away record, which answers with the record it was
     * merged into). Unknown or other-clinic ids are absent.
     */
    Map<UUID, PatientClinical> findAll(UUID tenantId, Collection<UUID> patientIds);
}
