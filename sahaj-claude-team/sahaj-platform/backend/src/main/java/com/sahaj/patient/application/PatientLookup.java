package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientLookupPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link PatientLookupPort} over {@link PatientRepository} (SAH-9). */
@Service
public class PatientLookup implements PatientLookupPort {

    private final PatientRepository patientRepository;

    public PatientLookup(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PatientSummary> find(UUID tenantId, UUID patientId) {
        return patientRepository.findById(tenantId, patientId)
                .map(row -> new PatientSummary(row.id(), row.name(), row.age(), row.sex()));
    }
}
