package com.sahaj.platform.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code platform.otp_challenge} (SAH-3). Never log {@link #mobile()} or
 * {@link #codeHash()}. No tenant: OTP verification happens before any tenant is known.
 */
public record OtpChallenge(
        UUID id,
        String mobile,
        String codeHash,
        Instant expiresAt,
        int failedCount,
        Instant lockedUntil,
        Instant lastSentAt,
        Instant consumedAt) {

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }
}
