package com.sahaj.patient.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port over {@code patient.patient_consent} (SAH-17). Append-only: insert and read, never update or delete. */
public interface ConsentRepository {

    /** Appends one row; {@code tenant_id} and {@code recorded_at} come from the table defaults. */
    void insert(UUID patientId, String kind, boolean given, UUID recordedBy);

    /** The newest row of each kind for the patient (zero, one or two rows). */
    List<ConsentRow> findLatestPerKind(UUID tenantId, UUID patientId);

    record ConsentRow(String kind, boolean given, Instant recordedAt, UUID recordedBy) {
    }
}
