package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.ActiveMembershipQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CachedActiveMembershipCheckerTest {

    @Test
    void asksTheDatabaseOnlyOnceWithinTheSixtySecondWindow() {
        ActiveMembershipQueryService queryService = mock(ActiveMembershipQueryService.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T10:00:00Z"));
        Clock clock = new MovableClock(now);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(queryService.isActive(tenantId, userId)).thenReturn(true);
        CachedActiveMembershipChecker checker = new CachedActiveMembershipChecker(queryService, clock);

        assertThat(checker.isActive(tenantId, userId)).isTrue();
        now.set(now.get().plusSeconds(59));
        assertThat(checker.isActive(tenantId, userId)).isTrue();

        verify(queryService, times(1)).isActive(tenantId, userId);
    }

    @Test
    void asksAgainAfterTheWindowExpires() {
        ActiveMembershipQueryService queryService = mock(ActiveMembershipQueryService.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T10:00:00Z"));
        Clock clock = new MovableClock(now);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(queryService.isActive(tenantId, userId)).thenReturn(true, false);
        CachedActiveMembershipChecker checker = new CachedActiveMembershipChecker(queryService, clock);

        assertThat(checker.isActive(tenantId, userId)).isTrue();
        now.set(now.get().plusSeconds(61));
        assertThat(checker.isActive(tenantId, userId)).isFalse();

        verify(queryService, times(2)).isActive(tenantId, userId);
    }

    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;

        MovableClock(AtomicReference<Instant> now) {
            this.now = now;
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
            return now.get();
        }
    }
}
