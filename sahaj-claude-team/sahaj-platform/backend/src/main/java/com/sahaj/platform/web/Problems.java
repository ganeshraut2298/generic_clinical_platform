package com.sahaj.platform.web;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem bodies in the shape of {@code docs/api/platform.yaml}: {@code type},
 * {@code title}, {@code status}, optional {@code detail}, {@code fieldErrors} and
 * {@code retryAfterSeconds}. Nothing here ever carries a submitted value, so a mobile number
 * cannot leak through an error response.
 */
public final class Problems {

    public static final String TYPE_BASE = "https://sahaj.in/problems/";

    private Problems() {
    }

    /** One field that failed validation. */
    public record FieldError(String field, String message) {
    }

    public static ProblemDetail of(HttpStatusCode status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        if (detail != null) {
            problem.setDetail(detail);
        }
        return problem;
    }

    public static ProblemDetail validation(List<FieldError> errors) {
        ProblemDetail problem = of(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                "Some fields are missing or not valid.");
        problem.setProperty("fieldErrors", errors);
        return problem;
    }

    public static ProblemDetail tooManyRequests(long retryAfterSeconds) {
        ProblemDetail problem = of(HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "Too many requests",
                "Too many requests from this network. Try again later.");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        return problem;
    }

    /** SAH-3: the per-mobile OTP resend cooldown, distinct from the network-wide {@link #tooManyRequests}. */
    public static ProblemDetail otpResendTooSoon(long retryAfterSeconds) {
        ProblemDetail problem = of(HttpStatus.TOO_MANY_REQUESTS, "rate-limit", "Too many requests",
                "Wait before requesting another code for this number.");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        return problem;
    }

    /** SAH-3 AC3: 5 wrong codes lock the mobile for 15 minutes. */
    public static ProblemDetail locked(String detail, long retryAfterSeconds) {
        ProblemDetail problem = of(HttpStatus.LOCKED, "otp-locked", "Too many wrong codes", detail);
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        return problem;
    }

    public static ProblemDetail unauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "unauthorized", "Sign in required",
                "You need to sign in to use this.");
    }

    public static ProblemDetail forbidden() {
        return of(HttpStatus.FORBIDDEN, "forbidden", "Not allowed",
                "You do not have access to this.");
    }

    public static ProblemDetail notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "We could not find that.");
    }

    public static ProblemDetail internal() {
        return of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Something went wrong",
                "Something went wrong on our side. Please try again.");
    }

    /**
     * DEC-054: the database did not answer in time (a lock that stayed taken, a statement that ran too long, or no free
     * connection). The request was rolled back, so nothing was saved by it; the client may try again after a moment.
     */
    public static ProblemDetail busy(long retryAfterSeconds) {
        ProblemDetail problem = of(HttpStatus.SERVICE_UNAVAILABLE, "database-busy", "Sahaj is busy",
                "This is taking longer than usual and was stopped. Nothing was saved by this attempt. "
                        + "Wait a moment and try again.");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        return problem;
    }

    /** A generic problem for any other status: the standard reason phrase and no detail from the cause. */
    public static ProblemDetail generic(HttpStatusCode status) {
        String reason = status instanceof HttpStatus http ? http.getReasonPhrase() : "Error";
        String slug = reason.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
        return of(status, slug, reason, null);
    }
}
