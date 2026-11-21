package com.sahaj.pharmacy.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TP-2 (SAH-80-11, 80-12; ADR-030 S12): signed OPD prescriptions, pulled at read time. Owned by the pharmacy module; the only
 * implementation in phase 1 is {@link NoOpOpdPrescriptionSource}. The adapter that calls a read port of the OPD module needs
 * that module's interface and a human approval first (DEC-072 Q2), so nothing in {@code com.sahaj.opd} is touched. Runs in the
 * caller's transaction (RLS bound). A prescription is never logged.
 */
public interface OpdPrescriptionSource {

    /** False while no real implementation is wired: the OPD queue then shows "Not connected" and typed prescriptions still work. */
    boolean connected();

    /** Signed records of the IST day that have a prescription section, oldest first. */
    List<RxHeader> signedOn(UUID tenantId, LocalDate istDay);

    Optional<Rx> read(UUID tenantId, UUID recordId);

    record RxHeader(UUID recordId, UUID patientId, UUID prescriberMembershipId, String doctorName, Instant signedAt,
                    int lineCount) {

        @Override
        public String toString() {
            return "RxHeader[recordId=" + recordId + "]";
        }
    }

    record Rx(RxHeader header, List<RxLine> lines) {

        @Override
        public String toString() {
            return "Rx[recordId=" + header.recordId() + "]";
        }
    }

    record RxLine(String lineKey, UUID medicineId, String medicineText, String dosePattern, String duration, String timing,
                  String instructions) {

        @Override
        public String toString() {
            return "RxLine[lineKey=" + lineKey + "]";
        }
    }
}
