package com.sahaj.platform.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A PIN check or PIN save that cannot go on (SAH-31, ADR-010 D11). The platform error handler
 * maps it to an RFC 9457 problem: type {@code https://sahaj.in/problems/<slug>}, the {@link #status()},
 * {@link #title()} and {@link #getMessage()} as the detail, and {@code retryAfterSeconds} when it is
 * greater than zero. The texts are fixed plain English written here once, so every caller (ipd verify,
 * the later MAR check, the PIN endpoints) answers the same way. No text ever carries a PIN, a name or
 * a mobile number.
 */
public class StaffPinException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String UNAVAILABLE_TEXT = "PIN checks are not available right now. Try again later.";
    private static final String RATE_LIMITED_TEXT = "Too many PIN tries in a short time. Try again in a few minutes.";
    private static final String COLLEAGUE_NOT_SET = "The second nurse has not set a PIN yet. Choose another nurse.";
    private static final String COLLEAGUE_WRONG_PIN = "That PIN is not right.";
    private static final String PAIR_LIMIT_TEXT = "Too many wrong PIN tries for this nurse. Choose another nurse.";
    /** SAH-66 (ADR-023 D6, H7): the neutral texts of the chemotherapy second check, whose checker may have several roles. */
    private static final String CHECKER_NOT_SET = "The checker has not set a PIN yet. Choose another checker.";
    private static final String CHECKER_PAIR_LIMIT_TEXT = "Too many wrong PIN tries for this person. Choose another checker.";
    private static final String CHECK_FAILED_TEXT = "We could not check the PIN. Try again.";

    private static final DateTimeFormatter CLOCK_IST = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    /** The problem types a PIN operation can answer. */
    public enum Kind {
        WRONG_PIN("wrong-pin", 403, "Wrong PIN"),
        PIN_LOCKED("pin-locked", 403, "PIN locked"),
        PIN_NOT_SET("pin-not-set", 409, "No PIN set"),
        RATE_LIMIT("rate-limit", 429, "Too many requests"),
        PIN_UNAVAILABLE("pin-unavailable", 503, "PIN checks are off"),
        /** SAH-38 (ADR-014 D3): 5 wrong tries of one colleague's PIN by one caller in a rolling 7 days. No retry time. */
        COLLEAGUE_PIN_LIMIT("colleague-pin-limit", 429, "Too many wrong PIN tries"),
        /** SAH-38 (ADR-014 D5): the PIN store could not be read or written in time; nothing was counted. */
        PIN_CHECK_FAILED("pin-check-failed", 503, "PIN check failed");

        private final String slug;
        private final int status;
        private final String title;

        Kind(String slug, int status, String title) {
            this.slug = slug;
            this.status = status;
            this.title = title;
        }

        public String slug() {
            return slug;
        }

        public int status() {
            return status;
        }

        public String title() {
            return title;
        }
    }

    /**
     * Whose PIN the problem is about (SAH-32, ADR-011 D7). {@code SELF}: the signed-in person's own PIN ("Your PIN ...").
     * {@code COLLEAGUE}: another person's PIN, checked for a second-nurse check; the texts never name the colleague,
     * and the same problem types and statuses are used.
     */
    public enum Subject {
        SELF, COLLEAGUE,
        /**
         * SAH-66 (ADR-023 D6, H7): the independent checker of a chemotherapy order, who may have several roles. The texts
         * are the colleague texts without the word "nurse"; the problem types and statuses are the same.
         */
        CHECKER
    }

    private final Kind kind;
    private final long retryAfterSeconds;
    private final Subject subject;

    public StaffPinException(Kind kind, String detail, long retryAfterSeconds) {
        this(kind, detail, retryAfterSeconds, Subject.SELF);
    }

    public StaffPinException(Kind kind, String detail, long retryAfterSeconds, Subject subject) {
        super(detail);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
        this.subject = subject;
    }

    /** Builds the exception for any outcome other than OK, with the exact texts of ADR-010 D11. */
    public static StaffPinException of(PinCheck check) {
        return of(check, Subject.SELF);
    }

    /**
     * Builds the exception for any outcome other than OK and NOT_ELIGIBLE. With {@link Subject#COLLEAGUE} the texts are
     * the second-nurse texts of ADR-011 D7 (contract wording of {@code docs/api/ipd.yaml}); wrong-pin has no tries-left count
     * for a colleague (review S4); rate-limit and pin-unavailable read the same for both subjects.
     */
    public static StaffPinException of(PinCheck check, Subject subject) {
        boolean checker = subject == Subject.CHECKER;
        boolean colleague = subject == Subject.COLLEAGUE || checker;
        switch (check.outcome()) {
            case WRONG:
                // A colleague's answer never counts the tries left, so a guesser is not told when to stop (review S4).
                return new StaffPinException(Kind.WRONG_PIN,
                        colleague ? COLLEAGUE_WRONG_PIN : wrongPinText(check.triesLeft()), 0, subject);
            case LOCKED:
                return new StaffPinException(Kind.PIN_LOCKED,
                        checker ? checkerLockedText(check.lockedUntil())
                                : colleague ? colleagueLockedText(check.lockedUntil()) : lockedText(check.lockedUntil()),
                        Math.max(0, check.retryAfterSeconds()), subject);
            case NOT_SET:
                return new StaffPinException(Kind.PIN_NOT_SET,
                        checker ? CHECKER_NOT_SET : colleague ? COLLEAGUE_NOT_SET : notSetText(check.notSetReason()), 0,
                        subject);
            case RATE_LIMITED:
                return new StaffPinException(Kind.RATE_LIMIT, RATE_LIMITED_TEXT,
                        Math.max(0, check.retryAfterSeconds()), subject);
            case UNAVAILABLE:
                return new StaffPinException(Kind.PIN_UNAVAILABLE, UNAVAILABLE_TEXT, 0, subject);
            case PAIR_LIMIT:
                return new StaffPinException(Kind.COLLEAGUE_PIN_LIMIT, checker ? CHECKER_PAIR_LIMIT_TEXT : PAIR_LIMIT_TEXT,
                        0, subject);
            case CHECK_FAILED:
                return new StaffPinException(Kind.PIN_CHECK_FAILED, CHECK_FAILED_TEXT, 0, subject);
            default:
                throw new IllegalArgumentException(check.outcome() + " is not a PIN problem");
        }
    }

    public static StaffPinException unavailable() {
        return new StaffPinException(Kind.PIN_UNAVAILABLE, UNAVAILABLE_TEXT, 0);
    }

    public static StaffPinException rateLimited(long retryAfterSeconds) {
        return new StaffPinException(Kind.RATE_LIMIT, RATE_LIMITED_TEXT, Math.max(0, retryAfterSeconds));
    }

    static String colleagueLockedText(Instant lockedUntil) {
        String base = "The second nurse's PIN is locked after 5 wrong tries. Choose another nurse, or try again";
        return lockedUntil == null ? base + " later." : base + " at " + CLOCK_IST.format(lockedUntil) + ".";
    }

    static String checkerLockedText(Instant lockedUntil) {
        String base = "The checker's PIN is locked after 5 wrong tries. Choose another checker, or try again";
        return lockedUntil == null ? base + " later." : base + " at " + CLOCK_IST.format(lockedUntil) + ".";
    }

    static String wrongPinText(int triesLeft) {
        return triesLeft == 1 ? "That PIN is not right. 1 try left."
                : "That PIN is not right. " + triesLeft + " tries left.";
    }

    static String lockedText(Instant lockedUntil) {
        String base = "Your PIN is locked after 5 wrong tries.";
        return lockedUntil == null ? base + " Try again later."
                : base + " Try again at " + CLOCK_IST.format(lockedUntil) + ".";
    }

    static String notSetText(PinCheck.NotSetReason reason) {
        if (reason == PinCheck.NotSetReason.RESET_BY_OWNER) {
            return "The clinic owner reset your PIN. Set a new one, then try again.";
        }
        if (reason == PinCheck.NotSetReason.TOO_MANY_LOCKS) {
            return "Your PIN was switched off after too many wrong tries. Set a new one, then try again.";
        }
        return "You have not set your PIN yet. Set your PIN, then try again.";
    }

    public Kind kind() {
        return kind;
    }

    /** Whose PIN this is about; the error handler uses the same problem types for both. */
    public Subject subject() {
        return subject;
    }

    public String slug() {
        return kind.slug();
    }

    public int status() {
        return kind.status();
    }

    public String title() {
        return kind.title();
    }

    /** Seconds to wait before trying again; 0 when not applicable. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
