package com.sahaj.platform.devtools.bootstrap;

import java.time.Instant;
import java.util.UUID;

/**
 * The five SECURITY DEFINER functions of docs/database/SAH-42.md (ADR-018 Revision 3), one method each. Runs inside the
 * transaction the caller ({@link BootstrapService}) opened for that one call; the repository never starts or ends one.
 * Failures arrive as {@link BootstrapExceptions}. No method takes a PIN, a ticket or a binding value: only the try id,
 * keyed hashes and the admin's own form values.
 */
interface BootstrapRepository {

    /**
     * The claim: counts one try and hands the 30-second lease to {@code tryId}. The caller commits before it compares
     * the PIN. Always called with a try id; the read-only peek for a bad body is {@link #ticketStatus}.
     */
    Claim begin(UUID tryId);

    /** The judge for a wrong PIN: audit rows only, never changes the count. Returns the row's count and lock. */
    Failure unlockFailed(UUID tryId, String remoteAddress, String requestId);

    /** The judge for the right PIN: resets the count, lifts this try's own lock, stores the new ticket hashes. */
    void unlockSucceeded(UUID tryId, String ticketHash, String bindingHash);

    /** Reads only, takes no row lock. {@code (null, null)} is the read-only peek used for a bad unlock body. */
    TicketState ticketStatus(String ticketHash, String bindingHash);

    RegisterReply register(String ticketHash, String bindingHash, UUID adminId, String email, String fullName,
                           String passwordHash, String remoteAddress, String requestId);

    enum ClaimOutcome { CLOSED, LOCKED, BUSY, COUNTED }

    /** {@code lockedUntil} is set with LOCKED and with the 5th COUNTED try. */
    record Claim(ClaimOutcome outcome, int failures, Instant lockedUntil) {
    }

    record Failure(int failures, Instant lockedUntil) {
    }

    enum Status { CLOSED, LOCKED, INVALID, VALID }

    /** {@code lockedUntil} is set only with LOCKED. */
    record TicketState(Status status, Instant lockedUntil) {
    }

    enum RegisterResult { CREATED, CLOSED, LOCKED, INVALID }

    /** {@code lockedUntil} is set only with LOCKED. */
    record RegisterReply(RegisterResult result, Instant lockedUntil) {
    }
}
