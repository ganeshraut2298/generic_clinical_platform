package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code ipd.emergency_triage_event} (SAH-28): the level history of an emergency case. Append-only: there is no
 * update or delete. Holds levels, times and the actor id only.
 */
public interface TriageEventRepository {

    /** Appends one event; {@code fromLevel} is null for REGISTERED. The database sets the id, clinic and time. */
    void insert(UUID tenantId, UUID caseId, Integer fromLevel, int level, int scale, String reason, UUID by);

    /** The history of one case, oldest first. */
    List<EventRow> findByCase(UUID tenantId, UUID caseId);

    record EventRow(UUID id, UUID caseId, Integer fromLevel, int level, int scale, String reason, Instant recordedAt,
                    UUID recordedBy) {
    }
}
