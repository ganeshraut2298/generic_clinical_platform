package com.sahaj.ipd.domain;

import com.sahaj.ipd.domain.EscalationState.Status;
import java.time.Duration;
import java.time.Instant;

/**
 * SAH-33 the response clock of an early warning escalation (ADR-007 D5). One small class used by the board, the
 * summary, the banner and (later) the patient card, so "past target" has exactly one definition. No Spring, no I/O.
 *
 * <p>Every instant comes from the database clock; {@code asOf} is the {@code clock_timestamp()} of the answer. The
 * clock starts at the later of the raise and the re-open: {@code reopenedAt} when set, otherwise {@code raisedAt}.
 */
public final class EscalationClock {

    private EscalationClock() {
    }

    /** The start of the response clock: the re-open time when there is one, otherwise the first raise. */
    public static Instant start(Instant raisedAt, Instant reopenedAt) {
        return reopenedAt != null ? reopenedAt : raisedAt;
    }

    /**
     * True only for an OPEN escalation whose exact waiting time is more than its target: at 15:00 for a High
     * escalation it is not past, any time after that it is. An Acknowledged or closed one is never past target.
     */
    public static boolean pastTarget(Status status, Instant clockStart, int targetMinutes, Instant asOf) {
        return status == Status.OPEN
                && Duration.between(clockStart, asOf).compareTo(Duration.ofMinutes(targetMinutes)) > 0;
    }

    /**
     * True when a sign-off made at {@code signedAt} covers an escalation whose response clock started at
     * {@code clockStart}: strictly later is required, equal instants do not cover it (SAH-33 security review M1,
     * ADR-007 R2). Both instants are database time.
     */
    public static boolean signoffCovers(Instant signedAt, Instant clockStart) {
        return signedAt != null && clockStart != null && signedAt.isAfter(clockStart);
    }

    /** Whole minutes from the start of the response clock to {@code asOf}, rounded down, never negative. */
    public static int waitingMinutes(Instant clockStart, Instant asOf) {
        return minutes(clockStart, asOf);
    }

    /** Whole minutes from the first raise to {@code asOf}, rounded down, never negative. */
    public static int minutesSinceRaised(Instant raisedAt, Instant asOf) {
        return minutes(raisedAt, asOf);
    }

    /** Whole minutes from {@code from} to {@code to}, rounded down, never negative (clock skew shows as 0). */
    public static int minutes(Instant from, Instant to) {
        long whole = Duration.between(from, to).toMinutes();
        return whole <= 0 ? 0 : (int) Math.min(whole, Integer.MAX_VALUE);
    }
}
