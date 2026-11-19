package com.sahaj.pharmacy.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The pharmacy-owned no-op reader of admitted patients (DEC-072 Q2, ADR-029 P14). The IPD module exposes no list of a ward's
 * admitted patients, so every read answers 409 {@code integration-off} and nothing is read. It is the only
 * {@link AdmittedPatientsReader} in phase 1; the adapter that replaces it belongs to the package that builds patient-wise
 * consumption (79-16).
 */
@Component
public class NoOpAdmittedPatientsReader implements AdmittedPatientsReader {

    @Override
    public List<Admitted> admittedTo(UUID tenantId, UUID wardId) {
        throw new IntegrationOffException();
    }
}
