package com.sahaj.platform.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, Duration.ofHours(1), 100, clock);

    @Test
    void allowsFiveThenRefusesTheSixth() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        SlidingWindowRateLimiter.Decision sixth = limiter.tryAcquire("a");
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.retryAfterSeconds()).isEqualTo(3600);
    }

    @Test
    void keysAreCountedSeparately() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void anAttemptThatWasRefusedDoesNotExtendTheWait() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofMinutes(30));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(1800);
        clock.advance(Duration.ofMinutes(29));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void allowsAgainOnceTheWindowHasPassed() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void theWindowSlidesInsteadOfResettingAtOnce() {
        limiter.tryAcquire("a");
        clock.advance(Duration.ofMinutes(40));
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        clock.advance(Duration.ofMinutes(21));
        // only the first request has left the window, so exactly one slot is free
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void aNewVisitorIsAdmittedByEvictingTheOldestKeyRatherThanBeingRefused() {
        SlidingWindowRateLimiter small = new SlidingWindowRateLimiter(5, Duration.ofHours(1), 3, clock);
        assertThat(small.tryAcquire("a").allowed()).isTrue();
        assertThat(small.tryAcquire("b").allowed()).isTrue();
        assertThat(small.tryAcquire("c").allowed()).isTrue();
        // the map is full, but a brand new visitor is still let in: "a" (the oldest) is evicted instead
        assertThat(small.tryAcquire("d").allowed()).isTrue();
        // "a" lost its history to the eviction and so gets a fresh allowance, same as any new key
        for (int i = 0; i < 5; i++) {
            assertThat(small.tryAcquire("a").allowed()).isTrue();
        }
    }

    @Test
    void expiredKeysAreSweptToMakeRoom() {
        SlidingWindowRateLimiter small = new SlidingWindowRateLimiter(5, Duration.ofHours(1), 2, clock);
        small.tryAcquire("a");
        small.tryAcquire("b");
        clock.advance(Duration.ofHours(2));
        assertThat(small.tryAcquire("c").allowed()).isTrue();
    }

    @Test
    void anIpv6SlashFortyEightIsCappedToASetNumberOfSubKeys() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, Duration.ofHours(1), 100_000, clock);
        String prefix = "sales-enquiry:v6:" + "ab".repeat(6); // 12 hex chars = the /48
        for (int i = 0; i < SlidingWindowRateLimiter.MAX_V6_SUBKEYS_PER_BLOCK; i++) {
            String subkey = prefix + String.format("%04x", i); // distinct /64 inside the same /48
            assertThat(limiter.tryAcquire(subkey).allowed()).isTrue();
        }
        String oneMore = prefix + String.format("%04x", SlidingWindowRateLimiter.MAX_V6_SUBKEYS_PER_BLOCK);
        assertThat(limiter.tryAcquire(oneMore).allowed()).isFalse();
        // a /64 from a different /48 is unaffected
        String otherBlock = "sales-enquiry:v6:" + "cd".repeat(6) + "0000";
        assertThat(limiter.tryAcquire(otherBlock).allowed()).isTrue();
    }

    @Test
    void aGlobalHourlyCapLimitsHowManyBrandNewKeysAreAdmitted() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, Duration.ofHours(1), 1_000_000, clock);
        for (int i = 0; i < SlidingWindowRateLimiter.GLOBAL_HOURLY_NEW_KEY_CAP; i++) {
            assertThat(limiter.tryAcquire("key-" + i).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("one-more-brand-new-key").allowed()).isFalse();
        // an already-known key keeps working even once the global cap is reached
        assertThat(limiter.tryAcquire("key-0").allowed()).isTrue();

        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("a-key-after-the-global-window-resets").allowed()).isTrue();
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(0, Duration.ofHours(1), 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(5, Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(5, Duration.ofHours(1), 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
