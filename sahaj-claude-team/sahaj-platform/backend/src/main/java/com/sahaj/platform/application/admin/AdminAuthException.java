package com.sahaj.platform.application.admin;

/**
 * The refusals of the admin sign-in flow that the contract names as problem types
 * (docs/api/admin.yaml). The web layer turns each kind into its problem; the messages here are
 * fixed text and never carry a submitted email, password, code or token.
 */
public class AdminAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 401 admin-credentials-invalid. Same for unknown email, wrong password and switched-off admin. */
        CREDENTIALS_INVALID("Email or password is not right."),
        /** 401 totp-invalid. */
        TOTP_INVALID("That code is not right."),
        /** 401 preauth-expired. */
        PREAUTH_EXPIRED("Your sign-in took too long. Start again."),
        /** 401 admin-session-ended. */
        SESSION_ENDED("Your session ended. Sign in again."),
        /** 423 admin-sign-in-locked. */
        SIGN_IN_LOCKED("Too many attempts."),
        /** 429 rate-limit. */
        RATE_LIMITED("Too many requests."),
        /** 409 totp-already-enrolled. */
        TOTP_ALREADY_ENROLLED("This admin already has an authenticator app."),
        /** 409 totp-enrolment-not-started. */
        TOTP_ENROLMENT_NOT_STARTED("Start the authenticator setup first.");

        private final String message;

        Kind(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final Kind kind;
    private final long retryAfterSeconds;

    public AdminAuthException(Kind kind) {
        this(kind, 0);
    }

    public AdminAuthException(Kind kind, long retryAfterSeconds) {
        super(kind.message());
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    /** Only meaningful for {@link Kind#SIGN_IN_LOCKED} and {@link Kind#RATE_LIMITED}. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
