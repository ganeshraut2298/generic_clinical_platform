package com.sahaj.platform.application;

import java.time.Duration;

/** Resend requested before the 30-second throttle elapsed. Mapped to HTTP 429. */
public class OtpResendTooSoonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public OtpResendTooSoonException(Duration retryAfter) {
        super("Please wait before requesting another code.");
        this.retryAfterSeconds = Math.max(0, retryAfter.toSeconds());
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
