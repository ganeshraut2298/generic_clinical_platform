package com.sahaj.platform.infrastructure.ratelimit;

import com.sahaj.platform.application.StaffPinLimits;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The in-memory limiters of the staff PIN (ADR-010 D13, review R1; SAH-32: the two per-caller windows of the colleague
 * check, ADR-011 D7, 5 per 15 minutes and 10 per 24 hours, wrong tries only), one object so that no second
 * {@link SlidingWindowRateLimiter} bean competes with the enquiry limiter. Keys are {@code tenantId:userId}, so one
 * clinic's traffic never uses another's allowance. Only FAILED PIN checks count toward the check limit (a pharmacist
 * verifying many orders is never stopped by right PINs); set or change has its own bucket. Phase 1 runs one instance;
 * with more, the limits become per instance and the lockout in the database still holds.
 */
@Component
public class InMemoryStaffPinLimits implements StaffPinLimits {

    private static final int MAX_KEYS = 100_000;

    private final SlidingWindowRateLimiter pinFailures;
    private final SlidingWindowRateLimiter pinSets;
    private final SlidingWindowRateLimiter codeRequests;
    private final SlidingWindowRateLimiter resets;
    private final SlidingWindowRateLimiter colleagueFailuresShort;
    private final SlidingWindowRateLimiter colleagueFailuresDay;

    public InMemoryStaffPinLimits(Clock clock) {
        this.colleagueFailuresShort = new SlidingWindowRateLimiter(5, Duration.ofMinutes(15), MAX_KEYS, clock);
        this.colleagueFailuresDay = new SlidingWindowRateLimiter(10, Duration.ofHours(24), MAX_KEYS, clock);
        this.pinFailures = new SlidingWindowRateLimiter(10, Duration.ofMinutes(5), MAX_KEYS, clock);
        this.pinSets = new SlidingWindowRateLimiter(10, Duration.ofMinutes(5), MAX_KEYS, clock);
        this.codeRequests = new SlidingWindowRateLimiter(5, Duration.ofHours(1), MAX_KEYS, clock);
        this.resets = new SlidingWindowRateLimiter(30, Duration.ofHours(1), MAX_KEYS, clock);
    }

    @Override
    public long pinCheckWait(UUID tenantId, UUID userId) {
        SlidingWindowRateLimiter.Decision decision = pinFailures.peek(key(tenantId, userId));
        return decision.allowed() ? 0 : Math.max(1, decision.retryAfterSeconds());
    }

    @Override
    public void pinCheckFailed(UUID tenantId, UUID userId) {
        pinFailures.tryAcquire(key(tenantId, userId));
    }

    @Override
    public long colleagueCheckWait(UUID tenantId, UUID callerUserId) {
        String key = key(tenantId, callerUserId);
        SlidingWindowRateLimiter.Decision shortWindow = colleagueFailuresShort.peek(key);
        SlidingWindowRateLimiter.Decision dayWindow = colleagueFailuresDay.peek(key);
        long wait = 0;
        if (!shortWindow.allowed()) {
            wait = Math.max(wait, shortWindow.retryAfterSeconds());
        }
        if (!dayWindow.allowed()) {
            wait = Math.max(wait, dayWindow.retryAfterSeconds());
        }
        return shortWindow.allowed() && dayWindow.allowed() ? 0 : Math.max(1, wait);
    }

    @Override
    public void colleagueCheckFailed(UUID tenantId, UUID callerUserId) {
        String key = key(tenantId, callerUserId);
        colleagueFailuresShort.tryAcquire(key);
        colleagueFailuresDay.tryAcquire(key);
    }

    @Override
    public long pinSetWait(UUID tenantId, UUID userId) {
        return wait(pinSets, tenantId, userId);
    }

    @Override
    public long codeRequestWait(UUID tenantId, UUID userId) {
        return wait(codeRequests, tenantId, userId);
    }

    @Override
    public long resetWait(UUID tenantId, UUID ownerUserId) {
        return wait(resets, tenantId, ownerUserId);
    }

    private static long wait(SlidingWindowRateLimiter limiter, UUID tenantId, UUID userId) {
        SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(key(tenantId, userId));
        return decision.allowed() ? 0 : Math.max(1, decision.retryAfterSeconds());
    }

    private static String key(UUID tenantId, UUID userId) {
        return tenantId + ":" + userId;
    }
}
