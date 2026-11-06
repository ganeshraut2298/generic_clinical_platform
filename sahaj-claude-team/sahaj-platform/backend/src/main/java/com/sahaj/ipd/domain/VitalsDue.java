package com.sahaj.ipd.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * "Vitals due" (SAH-30 AC10, mockup {@code pcDue}): an admitted patient with no vitals, or whose last vitals are more
 * than 240 whole minutes old (minutes are rounded down, so 4 hours and 59 seconds is not yet due).
 */
public final class VitalsDue {

    /** Vitals are due when more than this many whole minutes have passed. */
    public static final long LIMIT_MINUTES = 240;

    private VitalsDue() {
    }

    /**
     * @param admitted   false for a discharged admission, which is never due
     * @param lastVitals when the latest vitals were recorded, or null when there are none
     * @param now        the read time
     */
    public static boolean isDue(boolean admitted, Instant lastVitals, Instant now) {
        if (!admitted) {
            return false;
        }
        if (lastVitals == null) {
            return true;
        }
        return Duration.between(lastVitals, now).toMinutes() > LIMIT_MINUTES;
    }
}
