package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Release audit G-14: the skipped-order warning is written at most once per clinic and order per 10 minutes. */
class SkippedOrderWarningsTest {

    private static final UUID TENANT = UUID.randomUUID();

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-11T08:00:00Z");

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
    private final SkippedOrderWarnings warnings = new SkippedOrderWarnings(clock);

    @Test
    void theFirstWarningForAnOrderIsLetThroughAndTheRepeatsAreNot() {
        UUID order = UUID.randomUUID();

        assertThat(warnings.shouldWarn(TENANT, order)).isTrue();
        clock.advance(Duration.ofSeconds(60));
        assertThat(warnings.shouldWarn(TENANT, order)).isFalse();
        clock.advance(Duration.ofMinutes(8));
        assertThat(warnings.shouldWarn(TENANT, order)).isFalse();
    }

    @Test
    void afterTenMinutesTheOrderIsWarnedAboutAgain() {
        UUID order = UUID.randomUUID();
        assertThat(warnings.shouldWarn(TENANT, order)).isTrue();

        clock.advance(Duration.ofMinutes(10));

        assertThat(warnings.shouldWarn(TENANT, order)).isTrue();
        clock.advance(Duration.ofMinutes(5));
        assertThat(warnings.shouldWarn(TENANT, order)).as("the quiet period restarts at the last warning").isFalse();
    }

    @Test
    void anotherOrderOrAnotherClinicIsWarnedAboutIndependently() {
        UUID order = UUID.randomUUID();
        assertThat(warnings.shouldWarn(TENANT, order)).isTrue();

        assertThat(warnings.shouldWarn(TENANT, UUID.randomUUID())).isTrue();
        assertThat(warnings.shouldWarn(UUID.randomUUID(), order)).isTrue();
    }

    @Test
    void theMemoryIsBoundedAndTheOldestOrderIsForgottenFirst() {
        UUID first = UUID.randomUUID();
        assertThat(warnings.shouldWarn(TENANT, first)).isTrue();
        for (int i = 0; i < SkippedOrderWarnings.MAX_ENTRIES; i++) {
            assertThat(warnings.shouldWarn(TENANT, UUID.randomUUID())).isTrue();
        }

        // 1,001 distinct orders went in, so the first was dropped and is warned about again
        assertThat(warnings.shouldWarn(TENANT, first)).isTrue();
    }

    @Test
    void aRecentOrderSurvivesTheEvictionOfOlderOnes() {
        UUID recent = UUID.randomUUID();
        for (int i = 0; i < SkippedOrderWarnings.MAX_ENTRIES - 1; i++) {
            warnings.shouldWarn(TENANT, UUID.randomUUID());
        }
        assertThat(warnings.shouldWarn(TENANT, recent)).isTrue();

        warnings.shouldWarn(TENANT, UUID.randomUUID());

        assertThat(warnings.shouldWarn(TENANT, recent)).isFalse();
    }
}
