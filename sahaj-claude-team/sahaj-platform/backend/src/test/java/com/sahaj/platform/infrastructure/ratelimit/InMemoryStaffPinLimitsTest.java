package com.sahaj.platform.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-31, ADR-010 D13 and review R1: 10 failed PIN checks per 5 minutes (right PINs never count), 10 set or change
 * attempts per 5 minutes in a bucket of their own, 5 code requests per hour, 30 resets per hour.
 */
class InMemoryStaffPinLimitsTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T06:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final InMemoryStaffPinLimits limits = new InMemoryStaffPinLimits(clock);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            limits.pinCheckFailed(tenant, user);
        }
    }

    @Test
    void lookingAtTheCheckLimitNeverUsesAllowance() {
        for (int i = 0; i < 100; i++) {
            assertThat(limits.pinCheckWait(tenant, user)).as("look " + (i + 1)).isZero();
        }

        fail(9);

        assertThat(limits.pinCheckWait(tenant, user)).isZero();
    }

    @Test
    void successfulChecksNeverConsumeAllowance() {
        for (int i = 0; i < 50; i++) {
            assertThat(limits.pinCheckWait(tenant, user)).as("check " + (i + 1)).isZero();
        }

        fail(1);

        assertThat(limits.pinCheckWait(tenant, user)).isZero();
    }

    @Test
    void tenFailedChecksInFiveMinutesStopTheEleventhBeforeComparing() {
        fail(9);
        assertThat(limits.pinCheckWait(tenant, user)).isZero();

        fail(1);

        assertThat(limits.pinCheckWait(tenant, user)).isBetween(1L, 300L);
    }

    @Test
    void rightPinsBetweenWrongOnesStillLetNineFailuresThrough() {
        for (int i = 0; i < 9; i++) {
            assertThat(limits.pinCheckWait(tenant, user)).isZero();
            limits.pinCheckFailed(tenant, user);
            assertThat(limits.pinCheckWait(tenant, user)).isZero();
        }
    }

    @Test
    void theFailedCheckWindowReopensAfterFiveMinutes() {
        fail(10);
        assertThat(limits.pinCheckWait(tenant, user)).isPositive();

        clock.advance(Duration.ofMinutes(5));

        assertThat(limits.pinCheckWait(tenant, user)).isZero();
    }

    @Test
    void theEleventhSetAttemptInFiveMinutesIsRefusedWithAWait() {
        for (int i = 0; i < 10; i++) {
            assertThat(limits.pinSetWait(tenant, user)).as("try " + (i + 1)).isZero();
        }

        assertThat(limits.pinSetWait(tenant, user)).isBetween(1L, 300L);
        clock.advance(Duration.ofMinutes(5));
        assertThat(limits.pinSetWait(tenant, user)).isZero();
    }

    @Test
    void setAndCheckKeepTheirOwnBuckets() {
        fail(10);
        assertThat(limits.pinCheckWait(tenant, user)).isPositive();
        assertThat(limits.pinSetWait(tenant, user)).isZero();

        UUID other = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            limits.pinSetWait(tenant, other);
        }
        assertThat(limits.pinSetWait(tenant, other)).isPositive();
        assertThat(limits.pinCheckWait(tenant, other)).isZero();
    }

    @Test
    void oneClinicOrPersonNeverUsesAnotherOnesAllowance() {
        fail(10);

        assertThat(limits.pinCheckWait(tenant, UUID.randomUUID())).isZero();
        assertThat(limits.pinCheckWait(UUID.randomUUID(), user)).isZero();
    }

    @Test
    void theSixthCodeRequestInAnHourIsRefused() {
        for (int i = 0; i < 5; i++) {
            assertThat(limits.codeRequestWait(tenant, user)).isZero();
        }

        assertThat(limits.codeRequestWait(tenant, user)).isBetween(1L, 3600L);
        clock.advance(Duration.ofHours(1));
        assertThat(limits.codeRequestWait(tenant, user)).isZero();
    }

    @Test
    void theThirtyFirstResetInAnHourIsRefused() {
        for (int i = 0; i < 30; i++) {
            assertThat(limits.resetWait(tenant, user)).isZero();
        }

        assertThat(limits.resetWait(tenant, user)).isPositive();
    }

    // ---- SAH-32, ADR-011 D7: per caller, 5 failed colleague checks per 15 minutes and 10 per 24 hours ----

    private void colleagueFail(int times) {
        for (int i = 0; i < times; i++) {
            limits.colleagueCheckFailed(tenant, user);
        }
    }

    @Test
    void lookingAtTheColleagueLimitNeverUsesAllowance() {
        for (int i = 0; i < 100; i++) {
            assertThat(limits.colleagueCheckWait(tenant, user)).as("look " + (i + 1)).isZero();
        }

        colleagueFail(4);

        assertThat(limits.colleagueCheckWait(tenant, user)).isZero();
    }

    @Test
    void theSixthFailedColleagueCheckInFifteenMinutesIsStoppedBeforeAnyHash() {
        colleagueFail(5);

        assertThat(limits.colleagueCheckWait(tenant, user)).isBetween(1L, 900L);
    }

    @Test
    void theFifteenMinuteWindowReopensButTheDayWindowKeepsCounting() {
        colleagueFail(5);
        clock.advance(Duration.ofMinutes(15));
        assertThat(limits.colleagueCheckWait(tenant, user)).isZero();

        colleagueFail(5);
        assertThat(limits.colleagueCheckWait(tenant, user)).as("10 in the day, 5 in the last 15 minutes").isPositive();
        clock.advance(Duration.ofMinutes(15));

        assertThat(limits.colleagueCheckWait(tenant, user)).as("10 failures in 24 hours stop the 11th").isPositive();
    }

    @Test
    void theDayWindowAnswersTheWaitUntilTheOldestFailureLeavesIt() {
        colleagueFail(5);
        clock.advance(Duration.ofMinutes(20));
        colleagueFail(5);
        clock.advance(Duration.ofMinutes(20));

        long wait = limits.colleagueCheckWait(tenant, user);

        // the oldest failure is 40 minutes old: it leaves the 24-hour window in 23 h 20 min
        assertThat(wait).isBetween(23L * 3600 + 19 * 60, 23L * 3600 + 21 * 60);
    }

    @Test
    void theDayWindowReopensAfterTwentyFourHours() {
        colleagueFail(5);
        clock.advance(Duration.ofMinutes(20));
        colleagueFail(5);

        clock.advance(Duration.ofHours(24).minusMinutes(20));
        assertThat(limits.colleagueCheckWait(tenant, user)).as("the first five have left").isZero();
        colleagueFail(5);
        assertThat(limits.colleagueCheckWait(tenant, user)).isPositive();
    }

    @Test
    void theLongerWaitOfTheTwoWindowsIsAnswered() {
        colleagueFail(5);
        clock.advance(Duration.ofMinutes(20));
        colleagueFail(5);

        // 15-minute window: the last five are 0 minutes old -> up to 15 minutes; 24-hour window: nearly a day
        assertThat(limits.colleagueCheckWait(tenant, user)).isGreaterThan(900L);
    }

    @Test
    void oneCallerNeverUsesAnotherCallersOrAnotherClinicsColleagueAllowance() {
        colleagueFail(5);

        assertThat(limits.colleagueCheckWait(tenant, UUID.randomUUID())).isZero();
        assertThat(limits.colleagueCheckWait(UUID.randomUUID(), user)).isZero();
    }

    @Test
    void theColleagueBucketsAreSeparateFromTheOwnCheckBucket() {
        colleagueFail(10);

        assertThat(limits.colleagueCheckWait(tenant, user)).isPositive();
        assertThat(limits.pinCheckWait(tenant, user)).isZero();

        UUID other = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            limits.pinCheckFailed(tenant, other);
        }
        assertThat(limits.pinCheckWait(tenant, other)).isPositive();
        assertThat(limits.colleagueCheckWait(tenant, other)).isZero();
    }

    @Test
    void ofTenFailuresADayOnePersonCanCauseAtMostTwoLocksOfFiveWrongTries() {
        // the arithmetic of ADR-011 D7: a lock needs 5 wrong tries in a row, the switch-off a third lock in 24 hours
        int allowed = 0;
        while (limits.colleagueCheckWait(tenant, user) == 0) {
            limits.colleagueCheckFailed(tenant, user);
            allowed++;
            if (allowed % 5 == 0) {
                clock.advance(Duration.ofMinutes(15));
            }
        }

        assertThat(allowed).isEqualTo(10);
        // 10 tries = two locks of 5; with 4 wrong tries already on the colleague's row it is still floor(14 / 5) = 2
        assertThat((allowed + 4) / 5).isEqualTo(2);
    }

    @Test
    void thePurposesCountSeparately() {
        fail(10);
        for (int i = 0; i < 10; i++) {
            limits.pinSetWait(tenant, user);
        }

        assertThat(limits.codeRequestWait(tenant, user)).isZero();
        assertThat(limits.resetWait(tenant, user)).isZero();
    }
}
