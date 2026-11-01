package com.sahaj.platform.application;

import com.sahaj.platform.domain.OtpChallenge;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.otp_challenge} (no tenant, no row level security). */
public interface OtpChallengeRepository {

    /** The newest challenge for this mobile, if any. */
    Optional<OtpChallenge> findLatestByMobile(String mobile);

    UUID insert(String mobile, String codeHash, Instant expiresAt, Instant lastSentAt);

    void recordFailedAttempt(UUID id, int failedCount, Instant lockedUntil);

    void markConsumed(UUID id, Instant consumedAt);
}
