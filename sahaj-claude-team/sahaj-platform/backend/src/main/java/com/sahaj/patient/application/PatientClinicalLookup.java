package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    public PatientClinicalLookup(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
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
        return Optional.of(clinical(current));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, PatientClinical> findAll(UUID tenantId, Collection<UUID> patientIds) {
        Map<UUID, PatientClinical> result = new HashMap<>();
        for (Map.Entry<UUID, PatientRow> entry : PatientMergeChains.current(patientRepository, tenantId, patientIds)
                .entrySet()) {
            result.put(entry.getKey(), clinical(entry.getValue()));
        }
        return result;
    }

    private static PatientClinical clinical(PatientRow row) {
        return new PatientClinical(row.id(), row.uhid(), row.name(), row.age(), row.sex(), row.allergy());
    }
}
