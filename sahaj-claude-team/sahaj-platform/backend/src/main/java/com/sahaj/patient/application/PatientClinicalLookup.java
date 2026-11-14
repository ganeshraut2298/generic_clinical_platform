package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PatientClinicalPort} over {@link PatientRepository} (SAH-31). A merged-away record answers with the
 * data of the record it was merged into (security review S4), so an admission that still points at the old record sees
 * the current allergy text; the chain of {@code merged_into_id} is followed for at most {@value #MAX_HOPS} steps and
 * stops at a loop. Every read is scoped to the given clinic.
 */
@Service
public class PatientClinicalLookup implements PatientClinicalPort {

    /** A merge chain is short (a kept record can itself be merged away later); this only stops a damaged one. */
    static final int MAX_HOPS = PatientMergeChains.MAX_HOPS;

    private final PatientRepository patientRepository;
    private final Clock clock;

    @Autowired
    public PatientClinicalLookup(PatientRepository patientRepository, Clock clock) {
        this.patientRepository = patientRepository;
        this.clock = clock;
    }

    public PatientClinicalLookup(PatientRepository patientRepository) {
        this(patientRepository, Clock.systemUTC());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PatientClinical> find(UUID tenantId, UUID patientId) {
        Optional<PatientRow> first = patientRepository.findById(tenantId, patientId);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        PatientRow current = first.get();
        Set<UUID> seen = new HashSet<>();
        seen.add(current.id());
        for (int hop = 0; hop < MAX_HOPS && current.mergedIntoId() != null; hop++) {
            if (!seen.add(current.mergedIntoId())) {
                break;
            }
            Optional<PatientRow> next = patientRepository.findById(tenantId, current.mergedIntoId());
            if (next.isEmpty()) {
                break;
            }
            current = next.get();
        }
        return Optional.of(clinical(current, PatientAge.today(clock)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, PatientClinical> findAll(UUID tenantId, Collection<UUID> patientIds) {
        Map<UUID, PatientClinical> result = new HashMap<>();
        LocalDate today = PatientAge.today(clock);
        for (Map.Entry<UUID, PatientRow> entry : PatientMergeChains.current(patientRepository, tenantId, patientIds)
                .entrySet()) {
            result.put(entry.getKey(), clinical(entry.getValue(), today));
        }
        return result;
    }

    /**
     * SAH-73 (ADR-024 D1, D2): {@code age} is the effective completed years as of today in India and {@code ageDetail}
     * the same computation in full; the date of birth goes to the caller, who decides who may see it (ADR-024 D3).
     */
    private static PatientClinical clinical(PatientRow row, LocalDate today) {
        PatientAge ageDetail = PatientAge.of(row.dateOfBirth(), row.age(), today);
        return new PatientClinical(row.id(), row.uhid(), row.name(), ageDetail.years(), row.sex(), row.allergy(),
                row.chronic(), row.dateOfBirth(), ageDetail);
    }
}
