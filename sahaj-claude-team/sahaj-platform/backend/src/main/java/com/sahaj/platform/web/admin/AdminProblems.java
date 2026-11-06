package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.admin.AdminAuthException;
import com.sahaj.platform.web.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The problem types of docs/api/admin.yaml (RFC 9457, {@code https://sahaj.in/problems/<slug>}).
 * Nothing here ever carries a submitted email, password, code, recovery code or token, and no
 * problem says whether an email belongs to an admin.
 */
public final class AdminProblems {

    private AdminProblems() {
    }

    public static ProblemDetail of(AdminAuthException ex) {
        switch (ex.kind()) {
            case CREDENTIALS_INVALID:
                return Problems.of(HttpStatus.UNAUTHORIZED, "admin-credentials-invalid",
                        "Email or password is not right.", "Check your email and password and try again.");
            case TOTP_INVALID:
                return Problems.of(HttpStatus.UNAUTHORIZED, "totp-invalid", "That code is not right.",
                        "Check the code in your authenticator app and try again.");
            case PREAUTH_EXPIRED:
                return Problems.of(HttpStatus.UNAUTHORIZED, "preauth-expired",
                        "Your sign-in took too long. Start again.", "Start the sign-in again.");
            case SESSION_ENDED:
                return sessionEnded();
            case SIGN_IN_LOCKED:
                ProblemDetail locked = Problems.of(HttpStatus.LOCKED, "admin-sign-in-locked", "Too many attempts.",
                        "Too many attempts. Try again later.");
                locked.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
                return locked;
            case RATE_LIMITED:
                return Problems.tooManyRequests(ex.retryAfterSeconds());
            case TOTP_ALREADY_ENROLLED:
                return Problems.of(HttpStatus.CONFLICT, "totp-already-enrolled",
                        "This admin already has an authenticator app.", "Sign in with the code from your app.");
            case TOTP_ENROLMENT_NOT_STARTED:
            default:
                return Problems.of(HttpStatus.CONFLICT, "totp-enrolment-not-started",
                        "Start the authenticator setup first.", "Set up the authenticator app, then confirm it.");
        }
    }

    public static ProblemDetail sessionEnded() {
        return Problems.of(HttpStatus.UNAUTHORIZED, "admin-session-ended", "Your session ended. Sign in again.",
                "Sign in again to continue.");
    }

    public static ProblemDetail networkDenied() {
        return Problems.of(HttpStatus.FORBIDDEN, "admin-network-denied", "Sign-in works only from approved networks.",
                "Sign-in works only from approved networks.");
    }

    public static ProblemDetail unavailable() {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "admin-unavailable", "The admin portal is not available.",
                "The admin portal is not available right now.");
    }
}
