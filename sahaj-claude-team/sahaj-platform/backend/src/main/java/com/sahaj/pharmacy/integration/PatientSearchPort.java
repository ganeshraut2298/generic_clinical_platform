package com.sahaj.pharmacy.integration;

import java.util.List;
import java.util.UUID;

/**
 * TP-1, the search half (SAH-80-2, ADR-030 S6): find a registered patient by UHID, mobile or name for a bill. {@code patient.api}
 * has no search method (only a find by id), so the pharmacy owns this port; the only implementation in phase 1 is
 * {@link NoOpPatientSearchPort}, which is "not reachable" so that the screen offers the walk-in fallback (BR-3). A real one needs
 * a {@code patient.api.PatientSearchPort}, which is a change in the patient module and a follow-up. The search text is never logged.
 */
public interface PatientSearchPort {

    /** One match: the patient id only; the card is read through {@code patient.api} afterwards. */
    record Hit(UUID patientId) {
    }

    /**
     * At most {@code limit} matches, by name.
     *
     * @throws com.sahaj.pharmacy.application.PharmacyProblemException 503 {@code patients-unreachable} while no search is connected
     */
    List<Hit> search(UUID tenantId, String text, int limit);
}
