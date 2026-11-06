package com.sahaj.patient.api;

import java.util.UUID;

/**
 * SAH-23 (security review M1): lets another module record that a user opened a patient's details, so the
 * owner's "Who opened which record" log (SAH-17) is complete. Implemented by {@code patient}, the owner of
 * {@code patient.record_access}. Runs in the caller's transaction (tenant already bound), which must not be
 * read-only. Ids only; a name or number is never stored.
 */
public interface PatientAccessPort {

    /** Appends one access row for the patient; an unknown or other-clinic patient id is a not-found error. */
    void recordOpened(UUID tenantId, UUID patientId, UUID userId);
}
