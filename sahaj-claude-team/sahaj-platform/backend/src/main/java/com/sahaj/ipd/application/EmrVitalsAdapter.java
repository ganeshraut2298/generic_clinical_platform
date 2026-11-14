package com.sahaj.ipd.application;

import com.sahaj.ipd.application.VitalsRepository.VitalsRow;
import com.sahaj.opd.api.EmrVitalsSource;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-68-1 (ADR-025 D12): implements {@link EmrVitalsSource}, the nursing-chart vitals of an admission for the IPD round's
 * pre-fill. The caller (the EMR) has already applied the IPD scope rules when it loaded the admission through
 * {@link com.sahaj.opd.api.EmrEncounterSource}; this adapter only reads. The values are clinical: never logged.
 */
@Component
class EmrVitalsAdapter implements EmrVitalsSource {

    private final VitalsRepository vitals;

    EmrVitalsAdapter(VitalsRepository vitals) {
        this.vitals = vitals;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VitalsReading> latestNursing(UUID tenantId, UUID admissionId, LocalDate day) {
        return vitals.findLatestOnDay(tenantId, admissionId, day).map(EmrVitalsAdapter::reading);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VitalsReading> lastNursingBefore(UUID tenantId, UUID admissionId, LocalDate day) {
        return vitals.findLatestBeforeDay(tenantId, admissionId, day).map(EmrVitalsAdapter::reading);
    }

    private static VitalsReading reading(VitalsRow row) {
        return new VitalsReading(row.id(), row.recordedBy(), row.recordedAt(), row.bpSystolic(), row.bpDiastolic(),
                row.pulse(), row.spo2(), row.tempC(), row.rr());
    }
}
