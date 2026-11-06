package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.platform.api.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link PatientAccessPort} over {@link RecordAccessRepository} (SAH-23, same row as {@code PatientService.open}). */
@Service
public class PatientAccess implements PatientAccessPort {

    private final PatientRepository patientRepository;
    private final RecordAccessRepository recordAccessRepository;

    public PatientAccess(PatientRepository patientRepository, RecordAccessRepository recordAccessRepository) {
        this.patientRepository = patientRepository;
        this.recordAccessRepository = recordAccessRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOpened(UUID tenantId, UUID patientId, UUID userId) {
        if (patientRepository.findById(tenantId, patientId).isEmpty()) {
            throw new NotFoundException("Patient not found");
        }
        recordAccessRepository.insert(patientId, userId);
    }
}
