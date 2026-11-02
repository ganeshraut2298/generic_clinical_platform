package com.sahaj.opd.application;

import java.time.Instant;
import java.util.UUID;

/** {@code Visit} in {@code docs/api/opd.yaml} (SAH-9). */
public record VisitView(UUID id, int tokenNo, UUID patientId, String patientName, int age, String sex,
                         UUID doctorMembershipId, String doctorName, String visitType, String stage, long feePaise,
                         Instant createdAt) {
}
