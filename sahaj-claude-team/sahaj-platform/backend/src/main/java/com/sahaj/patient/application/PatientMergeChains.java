package com.sahaj.patient.application;

import com.sahaj.patient.application.PatientRepository.PatientRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one resolution of {@code merged_into_id} for batch reads (security review S4, ADR-013 D15): a merged-away record
 * answers with the record it was merged into. Shared by {@link PatientClinicalLookup} and {@link PatientLookup}, so every
 * role sees the same record. The chain is followed for at most {@value #MAX_HOPS} steps and stops at a loop or at a
 * missing record. Every read is scoped to the given clinic. Reads names and UHIDs, logs nothing.
 */
final class PatientMergeChains {

    /** A merge chain is short (a kept record can itself be merged away later); this only stops a damaged one. */
    static final int MAX_HOPS = 10;

    private PatientMergeChains() {
    }

    /** The current row of each asked id that exists in the clinic, keyed by the id asked for. */
    static Map<UUID, PatientRow> current(PatientRepository repository, UUID tenantId, Collection<UUID> patientIds) {
        Map<UUID, PatientRow> result = new HashMap<>();
        if (patientIds.isEmpty()) {
            return result;
        }
        // Where each asked id stands now: the row it has reached and the ids it has passed.
        Map<UUID, PatientRow> reached = new HashMap<>();
        Map<UUID, Set<UUID>> passed = new HashMap<>();
        for (PatientRow row : repository.findByIds(tenantId, patientIds)) {
            reached.put(row.id(), row);
            passed.put(row.id(), new HashSet<>(Set.of(row.id())));
        }
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            Set<UUID> wanted = new LinkedHashSet<>();
            for (Map.Entry<UUID, PatientRow> entry : reached.entrySet()) {
                UUID next = entry.getValue().mergedIntoId();
                if (next != null && !passed.get(entry.getKey()).contains(next)) {
                    wanted.add(next);
                }
            }
            if (wanted.isEmpty()) {
                break;
            }
            Map<UUID, PatientRow> found = new HashMap<>();
            for (PatientRow row : repository.findByIds(tenantId, wanted)) {
                found.put(row.id(), row);
            }
            for (Map.Entry<UUID, PatientRow> entry : reached.entrySet()) {
                UUID next = entry.getValue().mergedIntoId();
                if (next == null || !passed.get(entry.getKey()).add(next)) {
                    continue;
                }
                PatientRow target = found.get(next);
                if (target != null) {
                    entry.setValue(target);
                }
            }
        }
        for (UUID asked : patientIds) {
            PatientRow row = reached.get(asked);
            if (row != null) {
                result.put(asked, row);
            }
        }
        return result;
    }
}
