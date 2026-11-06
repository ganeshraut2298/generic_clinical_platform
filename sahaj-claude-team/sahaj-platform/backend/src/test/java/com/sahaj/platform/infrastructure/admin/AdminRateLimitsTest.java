package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminRateLimitsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void tenLoginsPerEmailThenARetryAfter() {
        AdminRateLimits limits = new AdminRateLimits(clock);
        for (int i = 0; i < 10; i++) {
            assertThat(limits.retryAfterSecondsIfRefused("key-a")).isZero();
        }
        assertThat(limits.retryAfterSecondsIfRefused("key-a")).isPositive().isLessThanOrEqualTo(900);
        assertThat(limits.retryAfterSecondsIfRefused("key-b")).isZero();
    }

    @Test
    void twentySignInRequestsPerAddress() {
        AdminRateLimits limits = new AdminRateLimits(clock);
        for (int i = 0; i < 20; i++) {
            assertThat(limits.signIn().tryAcquire("ip").allowed()).isTrue();
        }
        assertThat(limits.signIn().tryAcquire("ip").allowed()).isFalse();
    }

    @Test
    void thirtyRefreshesPerAddressIndependentlyOfSignIn() {
        AdminRateLimits limits = new AdminRateLimits(clock);
        for (int i = 0; i < 30; i++) {
            assertThat(limits.refresh().tryAcquire("ip").allowed()).isTrue();
        }
        assertThat(limits.refresh().tryAcquire("ip").allowed()).isFalse();
        assertThat(limits.signIn().tryAcquire("ip").allowed()).isTrue();
    }
}
