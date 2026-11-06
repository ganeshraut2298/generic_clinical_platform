package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminSignInPolicies;
import com.sahaj.platform.infrastructure.ratelimit.SlidingWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;

/**
 * The three in-memory limiters of ADR-003 D2.5, held in one object so that no second
 * {@link SlidingWindowRateLimiter} bean competes with the enquiry limiter:
 * <ul>
 *   <li>{@code signIn}: 20 requests per client address per 15 minutes across login, enrol and verify;</li>
 *   <li>{@code refresh}: 30 requests per client address per 15 minutes;</li>
 *   <li>{@code loginPerEmail}: 10 login requests per email key per 15 minutes.</li>
 * </ul>
 */
public final class AdminRateLimits implements AdminSignInPolicies.LoginLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 100_000;

    private final SlidingWindowRateLimiter signIn;
    private final SlidingWindowRateLimiter refresh;
    private final SlidingWindowRateLimiter loginPerEmail;

    public AdminRateLimits(Clock clock) {
        this.signIn = new SlidingWindowRateLimiter(20, WINDOW, MAX_KEYS, clock);
        this.refresh = new SlidingWindowRateLimiter(30, WINDOW, MAX_KEYS, clock);
        this.loginPerEmail = new SlidingWindowRateLimiter(10, WINDOW, MAX_KEYS, clock);
    }

    public SlidingWindowRateLimiter signIn() {
        return signIn;
    }

    public SlidingWindowRateLimiter refresh() {
        return refresh;
    }

    @Override
    public long retryAfterSecondsIfRefused(String emailKeyHash) {
        SlidingWindowRateLimiter.Decision decision = loginPerEmail.tryAcquire(emailKeyHash);
        return decision.allowed() ? 0 : Math.max(1, decision.retryAfterSeconds());
    }
}
