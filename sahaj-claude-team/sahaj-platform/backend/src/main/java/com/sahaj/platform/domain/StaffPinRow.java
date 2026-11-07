package com.sahaj.platform.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code platform.staff_pin} (SAH-31, ADR-010 D2 and D5; SAH-38, ADR-014 D6) with the database clock at the
 * time it was read. Every decision (locked? window older than 24 hours?) uses {@link #dbNow()}, never the application
 * clock, so the guard triggers in the database and this code always agree.
 *
 * <p>{@code runHasColleagueTry}: at least one wrong try in the current run of wrong tries ({@code failedCount} above 0)
 * was typed by a colleague. {@code lockByColleagues}: the current or last lock was caused by a run with a colleague try
 * (such a lock never moves {@code lockCount} and never switches the PIN off).
 *
 * <p>{@code pinHash} is a credential: {@link #toString()} never prints it.
 */
public record StaffPinRow(UUID membershipId, String pinHash, int pepperVersion, int failedCount,
                          Instant lockedUntil, int lockCount, Instant lockWindowStartedAt, Instant setAt,
                          String notSetReason, Instant resetAt, boolean runHasColleagueTry, boolean lockByColleagues,
                          long version, Instant dbNow) {

    public static final int MAX_TRIES = 5;
    public static final Duration LOCK_WINDOW = Duration.ofHours(24);

    /** A row of the SAH-31 shape: neither colleague flag is set. */
    public StaffPinRow(UUID membershipId, String pinHash, int pepperVersion, int failedCount, Instant lockedUntil,
                       int lockCount, Instant lockWindowStartedAt, Instant setAt, String notSetReason,
                       Instant resetAt, long version, Instant dbNow) {
        this(membershipId, pinHash, pepperVersion, failedCount, lockedUntil, lockCount, lockWindowStartedAt, setAt,
                notSetReason, resetAt, false, false, version, dbNow);
    }

    public boolean hasPin() {
        return pinHash != null;
    }

    public boolean isLocked() {
        return hasPin() && lockedUntil != null && lockedUntil.isAfter(dbNow);
    }

    public PinState state() {
        if (!hasPin()) {
            return PinState.NOT_SET;
        }
        return isLocked() ? PinState.LOCKED : PinState.SET;
    }

    /** Wrong tries left before a lock: 5 minus the wrong tries in a row; 0 when locked; null when no PIN. */
    public Integer triesLeft() {
        if (!hasPin()) {
            return null;
        }
        return isLocked() ? Integer.valueOf(0) : Integer.valueOf(MAX_TRIES - failedCount);
    }

    /** True when the 24-hour window of earlier locks is over (or there were no earlier locks). */
    public boolean lockWindowIsNew() {
        return lockCount == 0 || lockWindowStartedAt == null
                || !Duration.between(lockWindowStartedAt, dbNow).minus(LOCK_WINDOW).isNegative();
    }

    /** Redacted: no hash. */
    @Override
    public String toString() {
        return "StaffPinRow[membershipId=" + membershipId + ", state=" + state() + ", failedCount=" + failedCount
                + ", lockCount=" + lockCount + ", runHasColleagueTry=" + runHasColleagueTry
                + ", lockByColleagues=" + lockByColleagues + "]";
    }
}
