package com.sahaj.pharmacy.integration;

import com.sahaj.pharmacy.application.PharmacyProblemException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The pharmacy-owned default of the patient search: not connected. It answers 503 {@code patients-unreachable}, the sentence the
 * contract names, so the counter continues as a walk-in with "Link to patient pending" and a pharmacist links the patient later.
 * Nothing is read, so no text is kept or logged.
 */
@Component
public class NoOpPatientSearchPort implements PatientSearchPort {

    @Override
    public List<Hit> search(UUID tenantId, String text, int limit) {
        throw PharmacyProblemException.patientsUnreachable();
    }
}
