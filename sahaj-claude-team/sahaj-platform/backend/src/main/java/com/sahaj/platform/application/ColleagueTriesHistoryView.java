package com.sahaj.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code ColleagueTriesHistory} in {@code docs/api/platform.yaml} (SAH-38, ADR-014 D9): the owner's view of the
 * colleague tries on one member's PIN in the last 7 days. Names are looked up when the answer is built and are for the
 * screen only: never logged, audited or stored.
 */
public record ColleagueTriesHistoryView(UUID membershipId, Instant asOf, int wrongTries7d, boolean truncated,
                                        List<Entry> entries) {

    /** The longest list answered; {@code truncated} says more existed. */
    public static final int MAX_ENTRIES = 100;

    /** The name shown when no name can be found for a colleague (T10). */
    public static final String FORMER_STAFF_MEMBER = "Former staff member";

    /**
     * One line. {@code kind}: WRONG_TRIES (a colleague's tries on one IST day; {@code count} set), LOCKED
     * (no colleague, no name, no count) or RIGHT_AFTER_WRONG (the colleague who typed the right PIN).
     */
    public record Entry(String kind, Instant at, UUID byMembershipId, String byName, Integer count) {

        /** Redacted: no name. */
        @Override
        public String toString() {
            return "Entry[kind=" + kind + ", at=" + at + ", count=" + count + "]";
        }
    }
}
