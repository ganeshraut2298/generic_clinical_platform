package com.sahaj.opd.api.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the sign transaction and delivered after commit (ADR-025 D16). No listener exists in phase 5; the
 * {@code integration} module will listen once the human creates it, and must re-bind the tenant from the event
 * (ADR-002 D4). No clinical text: ids, the department key and the time only.
 */
public record EmrRecordSigned(UUID tenantId, UUID recordId, String encounterType, UUID encounterId, UUID patientId,
                              UUID doctorMembershipId, String departmentKey, Instant signedAt) {
}
