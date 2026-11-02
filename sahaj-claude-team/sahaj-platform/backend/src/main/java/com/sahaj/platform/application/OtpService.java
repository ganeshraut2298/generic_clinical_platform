package com.sahaj.platform.application;

import com.sahaj.platform.domain.OtpChallenge;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time code request and verification (SAH-3 AC3, AC6, AC8, AC9). Works on
 * {@code platform.otp_challenge} only, which has no tenant and no row level security, so no
 * {@code AuthenticationScope} binding is needed here.
 *
 * <p>The code is never logged, never returned in any response, and is stored only as
 * {@link PasswordEncoder#encode}'s hash; comparison goes through
 * {@link PasswordEncoder#matches}, which always runs the same computation regardless of where the
 * two values first differ (constant time with respect to the submitted code).
 */
@Service
public class OtpService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration RESEND_THROTTLE = Duration.ofSeconds(30);
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int MAX_FAILED_ATTEMPTS = 5;

    private final OtpChallengeRepository repository;
    private final OtpSender sender;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random;
    private final Clock clock;

    public OtpService(OtpChallengeRepository repository, OtpSender sender, PasswordEncoder passwordEncoder,
                       SecureRandom random, Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.passwordEncoder = passwordEncoder;
        this.random = random;
        this.clock = clock;
    }

    /**
     * LOCAL DEMO ONLY. When true (set only in application-dev.yml), no code is sent or stored and
     * any 6-digit code is accepted at verify. Never enable outside a local machine.
     */
    @Value("${sahaj.otp.dev-bypass:false}")
    private boolean devBypass;

    /** Sends a fresh code and returns the resend countdown, in seconds. */
    @Transactional
    public int request(String mobile) {
        if (devBypass) {
            return (int) RESEND_THROTTLE.toSeconds();
        }
        Instant now = clock.instant();
        Optional<OtpChallenge> latest = repository.findLatestByMobile(mobile);
        if (latest.isPresent()) {
            OtpChallenge challenge = latest.get();
            if (challenge.isLocked(now)) {
                throw new OtpLockedException(Duration.between(now, challenge.lockedUntil()));
            }
            Instant nextAllowed = challenge.lastSentAt().plus(RESEND_THROTTLE);
            if (nextAllowed.isAfter(now)) {
                throw new OtpResendTooSoonException(Duration.between(now, nextAllowed));
            }
        }
        String code = generateCode();
        String codeHash = passwordEncoder.encode(code);
        repository.insert(mobile, codeHash, now.plus(CODE_TTL), now);
        sender.send(mobile, code);
        return (int) RESEND_THROTTLE.toSeconds();
    }

    /**
     * Confirms the code. Throws when it is wrong, expired, already used, or the mobile is locked.
     * The two OTP exceptions must not roll the transaction back: a wrong code has to keep its
     * failed-attempt count (and the lock) or the 5-attempt limit would never take effect.
     */
    @Transactional(noRollbackFor = {OtpInvalidException.class, OtpLockedException.class})
    public void verify(String mobile, String code) {
        if (devBypass) {
            return;
        }
        Instant now = clock.instant();
        OtpChallenge challenge = repository.findLatestByMobile(mobile)
                .orElseThrow(() -> new OtpInvalidException("No code was sent to this number."));
        if (challenge.isLocked(now)) {
            throw new OtpLockedException(Duration.between(now, challenge.lockedUntil()));
        }
        if (challenge.isConsumed() || challenge.isExpired(now)) {
            throw new OtpInvalidException("That code has expired. Request a new one.");
        }
        if (!passwordEncoder.matches(code, challenge.codeHash())) {
            int failedCount = challenge.failedCount() + 1;
            Instant lockedUntil = failedCount >= MAX_FAILED_ATTEMPTS ? now.plus(LOCK_DURATION) : null;
            repository.recordFailedAttempt(challenge.id(), failedCount, lockedUntil);
            if (lockedUntil != null) {
                throw new OtpLockedException(LOCK_DURATION);
            }
            throw new OtpInvalidException("That code is not right.");
        }
        repository.markConsumed(challenge.id(), now);
    }

    private String generateCode() {
        int value = random.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", value);
    }
}
