package com.sahaj.pharmacy.application;

import java.util.UUID;

/**
 * L15 / m-3 (review round 5): the one place a document service takes its client key. The key is the first lock of every write, so
 * two taps of one key meet in {@code take}; the one that loses sees {@code false} after the winner committed. It must then answer
 * as a replay of the winner (the same operation, and for an operation on one document the same document), and anything else is
 * 409 {@code client-key-reused}. Never a bare 409 from a later check, and never a second write.
 */
final class ClientKeys {

    private ClientKeys() {
    }

    /**
     * Takes the key. Returns null when this request now holds it and goes on. Otherwise returns the stored row of the request that
     * got there first, already checked: its operation is {@code operation} and, when {@code sameSubject}, its document is
     * {@code subjectId}. The caller then answers the replay (its own early replay path, read again).
     *
     * @param sameSubject true for an operation on one existing document (submit, post, hand over, decide); false for a create,
     *                    where the winner's document is a different, new one that the replay returns
     */
    static ClientRequestRepository.Taken claim(ClientRequestRepository clients, UUID tenantId, UUID unitId, UUID membershipId,
                                               UUID clientKey, String operation, UUID subjectId, boolean sameSubject) {
        if (clients.take(tenantId, unitId, membershipId, clientKey, operation, subjectId)) {
            return null;
        }
        ClientRequestRepository.Taken first = clients.own(tenantId, membershipId, clientKey).orElse(null);
        if (first == null || !operation.equals(first.operation()) || sameSubject && !subjectId.equals(first.subjectId())) {
            throw PharmacyProblemException.clientKeyReused();
        }
        return first;
    }
}
