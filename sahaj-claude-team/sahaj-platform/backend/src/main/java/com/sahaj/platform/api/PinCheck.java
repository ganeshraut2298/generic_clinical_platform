package com.sahaj.platform.api;

import java.time.Instant;

/**
 * The result of {@link StaffPinPort#check}. Numbers and states only: it never holds the PIN, a
 * hash, a name or a mobile number.
 *
 * @param outcome           what happened
 * @param triesLeft         wrong tries left before the lock (WRONG: 1 to 4; OK: 5; other outcomes: 0)
 * @param lockedUntil       end of the lock (LOCKED only; otherwise null)
 * @param retryAfterSeconds seconds to wait (LOCKED and RATE_LIMITED; otherwise 0)
 * @param notSetReason      why there is no PIN (NOT_SET only; otherwise null)
 */
public record PinCheck(Outcome outcome, int triesLeft, Instant lockedUntil, long retryAfterSeconds,
                       NotSetReason notSetReason) {

    /**
     * What a check can answer. {@code NOT_ELIGIBLE} is answered only by {@link StaffPinPort#checkColleague} (SAH-32,
     * ADR-011 D7): the named person is not an ACTIVE member of the clinic with the required role, or is the caller.
     * {@link StaffPinPort#check} never answers it.
     */
    public enum Outcome { OK, WRONG, LOCKED, NOT_SET, RATE_LIMITED, UNAVAILABLE, NOT_ELIGIBLE }

    /** Why a person has no PIN. {@code NEVER_SET} when no PIN was ever saved. */
    public enum NotSetReason { NEVER_SET, RESET_BY_OWNER, TOO_MANY_LOCKS }

    public PinCheck {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
    }

    /** The four-value form of ADR-010 D9 (no reason: a NOT_SET answer is then read as never set). */
    public PinCheck(Outcome outcome, int triesLeft, Instant lockedUntil, long retryAfterSeconds) {
        this(outcome, triesLeft, lockedUntil, retryAfterSeconds, null);
    }

    public boolean isOk() {
        return outcome == Outcome.OK;
    }

    /**
     * Returns normally for {@link Outcome#OK}; otherwise throws {@link StaffPinException} with the
     * problem type, status and text of ADR-010 D11 (the signed-in person's own PIN: "Your PIN ..." texts). Not for a
     * colleague check: use {@link ColleaguePinCheck#requireOk()}, which words the same outcomes without "your".
     *
     * @throws IllegalStateException for {@link Outcome#NOT_ELIGIBLE}, which an own-PIN check never answers
     */
    public void requireOk() {
        if (outcome == Outcome.NOT_ELIGIBLE) {
            throw new IllegalStateException("NOT_ELIGIBLE belongs to a colleague check; use ColleaguePinCheck.requireOk");
        }
        if (outcome != Outcome.OK) {
            throw StaffPinException.of(this);
        }
    }

    /** Outcome and numbers only. */
    @Override
    public String toString() {
        return "PinCheck[outcome=" + outcome + ", triesLeft=" + triesLeft + ", retryAfterSeconds="
                + retryAfterSeconds + "]";
    }
}
