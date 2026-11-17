package com.sahaj.ipd.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * "Vitals due" (SAH-30 AC10, mockup {@code pcDue}; SAH-76-6, ADR-027 D10): an admitted patient with no vitals, or whose
 * last vitals are more than the interval old, in whole minutes (minutes are rounded down, so 4 hours and 59 seconds is
 * not yet due at 240). The interval is the one stamped on the last record (the ward profile in force when it was saved;
 * 240 for a record that carries none). The due time is the last record plus that interval.
 */
public final class VitalsDue {

    /** The interval of a record that carries none, and of a ward without a profile (SAH-30). */
    public static final int DEFAULT_INTERVAL_MINUTES = 240;

    /** Vitals are due when more than this many whole minutes have passed, with no stamped interval. */
    public static final long LIMIT_MINUTES = DEFAULT_INTERVAL_MINUTES;

    private VitalsDue() {
    }

    /**
     * @param admitted        false for a discharged admission, which is never due
     * @param lastVitals      when the latest vitals were recorded, or null when there are none
     * @param intervalMinutes the interval stamped on the last record (15 to 1440)
     * @param now             the read time
     */
    public static boolean isDue(boolean admitted, Instant lastVitals, int intervalMinutes, Instant now) {
        if (!admitted) {
            return false;
        }
        if (lastVitals == null) {
            return true;
        }
        return Duration.between(lastVitals, now).toMinutes() > intervalMinutes;
    }

    /** When the next vitals are due: the last record plus its interval; null when there are no vitals. */
    public static Instant dueAt(Instant lastVitals, int intervalMinutes) {
        return lastVitals == null ? null : lastVitals.plus(Duration.ofMinutes(intervalMinutes));
    }
}
