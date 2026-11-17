package com.sahaj.ipd.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * SAH-76-4 (ADR-027 D7, OQ-76-19): the limits of an as-needed (PRN) order. Pure Java. Only live GIVEN doses are passed in
 * (undone and in-error doses never count). The minimum interval is measured from the last live dose; the maximum is the
 * count of live doses in the rolling 24 hours before {@code now}.
 */
public final class PrnRules {

    private static final Duration DAY = Duration.ofHours(24);

    private PrnRules() {
    }

    /** Why a PRN dose cannot be given now. */
    public enum Lock { TOO_SOON, MAX_REACHED }

    /** {@code lock} is null when a dose may be given; {@code nextAllowedAt} is then null too. */
    public record Decision(Lock lock, Instant nextAllowedAt, int givenLast24h, Instant lastGivenAt) {
        public boolean allowed() {
            return lock == null;
        }
    }

    /**
     * @param givenAt        recorded times of the live GIVEN doses of the order (any order of the list)
     * @param minIntervalMin the order's minimum interval in minutes
     * @param maxPer24h      the order's most doses in 24 hours
     */
    public static Decision decide(List<Instant> givenAt, int minIntervalMin, int maxPer24h, Instant now) {
        Instant last = givenAt.stream().max(Comparator.naturalOrder()).orElse(null);
        List<Instant> inWindow = givenAt.stream().filter(t -> t.isAfter(now.minus(DAY)) && !t.isAfter(now))
                .sorted().toList();
        int count = inWindow.size();
        Instant tooSoonUntil = last == null ? null : last.plus(Duration.ofMinutes(minIntervalMin));
        boolean tooSoon = tooSoonUntil != null && now.isBefore(tooSoonUntil);
        if (count >= maxPer24h) {
            // The dose that must leave the 24 hours window before another may be given.
            Instant maxUntil = inWindow.get(count - maxPer24h).plus(DAY);
            Instant next = tooSoonUntil != null && tooSoonUntil.isAfter(maxUntil) ? tooSoonUntil : maxUntil;
            return new Decision(Lock.MAX_REACHED, next, count, last);
        }
        if (tooSoon) {
            return new Decision(Lock.TOO_SOON, tooSoonUntil, count, last);
        }
        return new Decision(null, null, count, last);
    }
}
