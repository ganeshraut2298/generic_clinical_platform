package com.sahaj.pharmacy.application;

import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.client_request}: the idempotency keys of Phase 2 creates and posts (ADR-029 P5). Insert and select only. */
public interface ClientRequestRepository {

    /** A key already taken: by whom, for which operation and which document. */
    record Taken(UUID membershipId, String operation, UUID subjectId) {
    }

    /**
     * Takes the key for this caller in the request transaction (first lock of the order, so two taps of one key meet here).
     * False when the same caller already holds it.
     */
    boolean take(UUID tenantId, UUID unitId, UUID membershipId, UUID clientKey, String operation, UUID subjectId);

    /** The row of this caller for the key. */
    Optional<Taken> own(UUID tenantId, UUID membershipId, UUID clientKey);

    /** Whether anyone in the clinic holds the key (a key held by another caller answers 409 client-key-reused). */
    boolean heldByAnyone(UUID tenantId, UUID clientKey);
}
