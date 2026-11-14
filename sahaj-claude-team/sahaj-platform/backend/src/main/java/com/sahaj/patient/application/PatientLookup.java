package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.api.PatientLookupPort;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PatientLookupPort} over {@link PatientRepository} (SAH-9). SAH-73 (ADR-024 D1, D2): the {@code age} of
 * every summary is the effective completed years as of today in India; the date of birth is never part of a summary.
 */
@Service
public class PatientLookup implements PatientLookupPort {

    private final PatientRepository patientRepository;
    private final Clock clock;

    @Autowired
    public PatientLookup(PatientRepository patientRepository, Clock clock) {
        this.patientRepository = patientRepository;
        this.clock = clock;
    }

    public PatientLookup(PatientRepository patientRepository) {
        this(patientRepository, Clock.systemUTC());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PatientSummary> find(UUID tenantId, UUID patientId) {
        LocalDate today = PatientAge.today(clock);
        return patientRepository.findById(tenantId, patientId)
                .map(row -> summary(row, today));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, PatientSummary> findAll(UUID tenantId, Collection<UUID> patientIds) {
        Map<UUID, PatientSummary> result = new HashMap<>();
        if (patientIds.isEmpty()) {
            return result;
        }
        LocalDate today = PatientAge.today(clock);
        for (PatientRepository.PatientRow row : patientRepository.findByIds(tenantId, patientIds)) {
            result.put(row.id(), summary(row, today));
        }
        return result;
    }

    /** ADR-013 D15: the same merge resolution as the clinical port; no mobile number, no allergy text. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, PatientSummary> findAllCurrent(UUID tenantId, Collection<UUID> patientIds) {
        Map<UUID, PatientSummary> result = new HashMap<>();
        LocalDate today = PatientAge.today(clock);
        for (Map.Entry<UUID, PatientRepository.PatientRow> entry
                : PatientMergeChains.current(patientRepository, tenantId, patientIds).entrySet()) {
            PatientRepository.PatientRow row = entry.getValue();
            result.put(entry.getKey(), new PatientSummary(row.id(), row.name(), effectiveYears(row, today),
                    row.sex(), null, row.mergedIntoId() != null, row.uhid()));
        }
        return result;
    }

    /** SAH-37: the explicit mobile read. The merge resolution is the one of {@link #findAllCurrent}. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, String> mobilesOf(UUID tenantId, Collection<UUID> patientIds) {
        Map<UUID, String> result = new HashMap<>();
        for (Map.Entry<UUID, PatientRepository.PatientRow> entry
                : PatientMergeChains.current(patientRepository, tenantId, patientIds).entrySet()) {
            String mobile = entry.getValue().mobile();
            if (mobile != null) {
                result.put(entry.getKey(), mobile);
            }
        }
        return result;
    }

    private static PatientSummary summary(PatientRepository.PatientRow row, LocalDate today) {
        return new PatientSummary(row.id(), row.name(), effectiveYears(row, today), row.sex(), row.mobile(),
                row.mergedIntoId() != null, row.uhid());
    }

    private static int effectiveYears(PatientRepository.PatientRow row, LocalDate today) {
        return PatientAge.of(row.dateOfBirth(), row.age(), today).years();
    }
}
