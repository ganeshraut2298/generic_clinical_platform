package com.sahaj.patient.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port over {@code patient.record_access} (SAH-17). Append-only; ids and time only, never a name or number. */
public interface RecordAccessRepository {

    /** Appends one row; {@code tenant_id} and {@code at} come from the table defaults. */
    void insert(UUID patientId, UUID userId);

    /** Newest first, at most {@code limit} rows. */
    List<AccessRow> findRecent(UUID tenantId, int limit);

    record AccessRow(UUID patientId, UUID userId, Instant at) {
    }
}
