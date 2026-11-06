package com.sahaj.ipd.application;

import java.util.UUID;

/**
 * {@code ipd.ews_senior_call} (SAH-33 review fix, ADR-007 R4): the append-only entries of "Call senior", one per
 * accepted tap: CALLED (with the count after the call) or ALREADY_CALLED. Used for the replay, the 60-second throttle
 * per user and "who called". Every method is scoped to the tenant. The database stamps {@code called_at}; the 60
 * seconds are measured on the database clock. The caller holds the admission row lock, so two taps of one nurse
 * serialise. Nothing is updated or deleted.
 */
public interface SeniorCallRepository {

    /** The user has an accepted CALLED entry on the escalation whose number is above {@code sentCalls}. */
    boolean hasCalledAbove(UUID tenantId, UUID escalationId, UUID user, int sentCalls);

    /** The user has any entry on the escalation from the last {@code seconds} seconds (database clock). */
    boolean hasEnteredWithin(UUID tenantId, UUID escalationId, UUID user, int seconds);

    /** Appends a CALLED entry with the escalation's senior-call count after the call. */
    void insertCalled(UUID tenantId, UUID escalationId, UUID user, int callNo);

    /** Appends an ALREADY_CALLED entry (no number). */
    void insertAlreadyCalled(UUID tenantId, UUID escalationId, UUID user);
}
