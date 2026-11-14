package com.sahaj.opd.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-68-1 (ADR-025 D12): the nursing-chart vitals of an admission, for the IPD round's pre-fill. Implemented by
 * {@code ipd}, which applies its scope rules. Clinical values: screen only, never logged ({@code toString} shows ids).
 */
public interface EmrVitalsSource {

    record VitalsReading(UUID entryId, UUID recordedByUserId, Instant recordedAt, int bpSystolic, int bpDiastolic,
                         int pulse, int spo2, BigDecimal temperatureC, int respiratoryRate) {

        @Override
        public String toString() {
            return "VitalsReading[entryId=" + entryId + "]";
        }
    }

    /** The latest entry recorded on {@code day} (Asia/Kolkata). */
    Optional<VitalsReading> latestNursing(UUID tenantId, UUID admissionId, LocalDate day);

    /** The latest entry recorded before {@code day}. */
    Optional<VitalsReading> lastNursingBefore(UUID tenantId, UUID admissionId, LocalDate day);
}
