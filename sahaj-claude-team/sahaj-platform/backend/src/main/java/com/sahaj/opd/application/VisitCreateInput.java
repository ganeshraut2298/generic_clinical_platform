package com.sahaj.opd.application;

import java.util.UUID;

/** {@code VisitCreate} in {@code docs/api/opd.yaml} (SAH-9). */
public record VisitCreateInput(UUID patientId, UUID doctorMembershipId) {
}
