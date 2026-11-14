package com.sahaj.opd.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * DEC-040 extension point (ADR-025 D16): a signed record's sections with their codes (ICD-10 on diagnoses, master keys) for
 * the later FHIR bundle. Declared and implemented in {@code opd}; no caller yet. Clinical data: never logged.
 */
public interface SignedEmrRecordReader {

    record SignedRecord(UUID recordId, String encounterType, UUID encounterId, UUID patientId, String departmentKey,
                        Instant signedAt, UUID signedByMembershipId, JsonNode sections) {

        @Override
        public String toString() {
            return "SignedRecord[recordId=" + recordId + "]";
        }
    }

    Optional<SignedRecord> read(UUID tenantId, UUID recordId);
}
