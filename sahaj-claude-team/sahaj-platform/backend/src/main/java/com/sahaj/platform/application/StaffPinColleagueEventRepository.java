package com.sahaj.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code platform.staff_pin_colleague_event} (SAH-38, ADR-014 D2; statements in
 * {@code docs/database/SAH-38.md} section 4). Append-only: there is no update and no delete. Needs {@code app.tenant_id}
 * bound; {@code id}, {@code tenant_id} and {@code at} always come from the database (the app cannot name them).
 * Ids, a kind and times only: never a PIN, a hash or a name. A refusal (SQLSTATE 23514, 23503, 42501) is a code bug.
 */
public interface StaffPinColleagueEventRepository {

    /**
     * The number of {@code WRONG} rows of the pair (the PIN's owner {@code membershipId}, the caller {@code byMembershipId})
     * inside its window: after the latest of 7 days ago (database clock), {@code pinSetAt} (the current PIN's start) and
     * the pair's latest {@code RIGHT_AFTER_WRONG}. Call it under the owner's {@code staff_pin ... for update}.
     */
    int countPairWrongTries(UUID tenantId, UUID membershipId, UUID byMembershipId, Instant pinSetAt);

    /** A colleague typed this person's PIN and it was wrong. */
    void insertWrong(UUID membershipId, UUID byMembershipId, UUID createdBy);

    /** A lock caused by a run that has a colleague try. */
    void insertLocked(UUID membershipId, UUID createdBy);

    /** A right PIN typed by the same caller after wrong tries of the pair: clears the pair. */
    void insertRightAfterWrong(UUID membershipId, UUID byMembershipId, UUID createdBy);

    /** The newest try the owner has dismissed ("Got it"); empty before the first one. */
    Optional<Instant> latestSeenThrough(UUID tenantId, UUID membershipId);

    /** The {@code WRONG} rows of the owner strictly after {@code after} ({@link Instant#EPOCH} = all): count, newest time. */
    Unseen unseen(UUID tenantId, UUID membershipId, Instant after);

    /** True when a {@code WRONG} row lies strictly after {@code after} and at or before {@code through}. */
    boolean existsWrongBetween(UUID tenantId, UUID membershipId, Instant after, Instant through);

    /** The owner's "Got it" up to {@code seenThrough}; the database caps it at its own clock. */
    void insertNoticeSeen(UUID membershipId, Instant seenThrough, UUID createdBy);

    /** Per membership of the clinic, the {@code WRONG} rows of the last 7 days (database clock); only members with any. */
    Map<UUID, Integer> wrongTriesLast7Days(UUID tenantId);

    /** Number of {@code WRONG} rows of one member in the last 7 days (database clock). */
    int wrongTriesLast7Days(UUID tenantId, UUID membershipId);

    /**
     * The owner's history of one member: lines of the 7 days before the database clock, latest first, at most
     * {@code limit} (ask for one more to know about truncation). {@code WRONG} rows are grouped by caller and IST day.
     */
    History history(UUID tenantId, UUID membershipId, int limit);

    /** How many unseen wrong tries and the time of the newest one (null when none). */
    record Unseen(int count, Instant latestAt) {
    }

    /**
     * One line of the history: kind {@code WRONG} (a group of {@code count} rows), {@code LOCKED} or
     * {@code RIGHT_AFTER_WRONG} (one row each). {@code byMembershipId} is null for {@code LOCKED}.
     */
    record Line(String kind, Instant at, UUID byMembershipId, int count) {
    }

    /** The database clock the window was cut at, the lines (latest first), and the wrong tries in the window. */
    record History(Instant asOf, List<Line> lines, int wrongTries) {
    }
}
