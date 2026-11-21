package com.sahaj.pharmacy.integration;

import java.util.UUID;

/**
 * TP-5 (SAH-80-14, 80-15, 80-16) and TP-6 (patient-wise consumption, SAH-79-16); ADR-030 S12: charges and credits on the IPD
 * running bill. Called inside the pharmacy transaction so stock and charge commit together; idempotent by {@code sourceKey}.
 * Owned by the pharmacy module; the only implementation in phase 1 is {@link NoOpIpdChargePort}. A real one needs an IPD-owned
 * port and an IPD migration (a HIGH-risk money change) and a human approval first (DEC-072 Q2).
 */
public interface IpdChargePort {

    boolean connected();

    Outcome charge(UUID tenantId, ChargeLine line);

    /** Never above what was charged minus what was credited for that source line. */
    Outcome credit(UUID tenantId, ChargeLine line);

    record ChargeLine(String sourceKey, UUID admissionId, UUID productId, String productName, int quantity, long amountPaise) {
    }

    enum Result { POSTED, BILL_CLOSED, ADMISSION_NOT_FOUND }

    record Outcome(Result result, String chargeRef) {
    }
}
