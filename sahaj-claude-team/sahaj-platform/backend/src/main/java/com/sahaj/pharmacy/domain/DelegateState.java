package com.sahaj.pharmacy.domain;

import java.time.LocalDate;

/** Where a delegation stands on a given IST date. */
public enum DelegateState {
    UPCOMING,
    CURRENT,
    ENDED;

    /** A delegate period is at most 90 calendar days counting the first and the last day (DEC-068 Q1: to - from <= 89). */
    public static final int MAX_DAYS = 90;

    /** The number of calendar days of a period, counting both its first and its last day. */
    public static long daysIncluded(LocalDate from, LocalDate to) {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** True when the period, both ends counted, is longer than {@link #MAX_DAYS}. */
    public static boolean tooLong(LocalDate from, LocalDate to) {
        return daysIncluded(from, to) > MAX_DAYS;
    }

    /** Both dates inclusive. */
    public static DelegateState of(LocalDate from, LocalDate to, LocalDate today) {
        if (today.isBefore(from)) {
            return UPCOMING;
        }
        if (today.isAfter(to)) {
            return ENDED;
        }
        return CURRENT;
    }
}
