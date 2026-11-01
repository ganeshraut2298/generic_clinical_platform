package com.sahaj.platform.application;

import java.time.Duration;

/** AC3: 5 wrong codes lock the mobile for 15 minutes. Mapped to HTTP 423 Locked. */
public class OtpLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public OtpLockedException(Duration retryAfter) {
        super("Too many wrong codes. Try again later.");
        this.retryAfterSeconds = Math.max(0, retryAfter.toSeconds());
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
