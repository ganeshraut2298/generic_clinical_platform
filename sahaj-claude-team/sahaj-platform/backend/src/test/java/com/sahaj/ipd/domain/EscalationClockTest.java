package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.EscalationState.Status;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SAH-33 the response clock: "past target" and the minute counts (ADR-007 D5, Q6). */
class EscalationClockTest {

    private static final Instant RAISED = Instant.parse("2026-11-11T05:00:00Z");

    private static boolean past(Status status, int target, Duration waited) {
        return EscalationClock.pastTarget(status, RAISED, target, RAISED.plus(waited));
    }

    @Test
    void aHighEscalationIsNotPastTargetAtExactly15MinutesAndIsPastAfterIt() {
        assertThat(past(Status.OPEN, 15, Duration.ofMinutes(14).plusSeconds(59))).isFalse();
        assertThat(past(Status.OPEN, 15, Duration.ofMinutes(15))).isFalse();
        assertThat(past(Status.OPEN, 15, Duration.ofMinutes(15).plusNanos(1_000))).isTrue();
        assertThat(past(Status.OPEN, 15, Duration.ofMinutes(15).plusSeconds(1))).isTrue();
        assertThat(past(Status.OPEN, 15, Duration.ofMinutes(16))).isTrue();
    }

    @Test
    void aMediumEscalationIsNotPastTargetAtExactly60MinutesAndIsPastAfterIt() {
        assertThat(past(Status.OPEN, 60, Duration.ofMinutes(59).plusSeconds(59))).isFalse();
        assertThat(past(Status.OPEN, 60, Duration.ofMinutes(60))).isFalse();
        assertThat(past(Status.OPEN, 60, Duration.ofMinutes(60).plusNanos(1_000))).isTrue();
        assertThat(past(Status.OPEN, 60, Duration.ofMinutes(61))).isTrue();
    }

    @Test
    void anAcknowledgedOrClosedEscalationIsNeverPastTargetHoweverLongItWaited() {
        assertThat(past(Status.ACK, 15, Duration.ofHours(10))).isFalse();
        assertThat(past(Status.DONE, 15, Duration.ofHours(10))).isFalse();
        assertThat(past(Status.ACK, 60, Duration.ofDays(2))).isFalse();
    }

    @Test
    void aFutureClockStartIsNeverPastTarget() {
        assertThat(EscalationClock.pastTarget(Status.OPEN, RAISED.plusSeconds(5), 15, RAISED)).isFalse();
    }

    @Test
    void theClockStartsAtTheReopenTimeWhenThereIsOneAndAtTheRaiseTimeOtherwise() {
        Instant reopened = RAISED.plus(Duration.ofMinutes(40));

        assertThat(EscalationClock.start(RAISED, null)).isEqualTo(RAISED);
        assertThat(EscalationClock.start(RAISED, reopened)).isEqualTo(reopened);
    }

    @Test
    void aReopenedEscalationIsMeasuredFromTheReopenAndNotFromTheFirstRaise() {
        Instant reopened = RAISED.plus(Duration.ofMinutes(40));
        Instant start = EscalationClock.start(RAISED, reopened);

        // 50 minutes after the first raise is only 10 minutes after the re-open: not past a 15 minute target
        assertThat(EscalationClock.pastTarget(Status.OPEN, start, 15, RAISED.plus(Duration.ofMinutes(50)))).isFalse();
        assertThat(EscalationClock.pastTarget(Status.OPEN, start, 15, RAISED.plus(Duration.ofMinutes(55)))).isFalse();
        assertThat(EscalationClock.pastTarget(Status.OPEN, start, 15, RAISED.plus(Duration.ofMinutes(55))
                .plusSeconds(1))).isTrue();
        assertThat(EscalationClock.waitingMinutes(start, RAISED.plus(Duration.ofMinutes(50)))).isEqualTo(10);
        assertThat(EscalationClock.minutesSinceRaised(RAISED, RAISED.plus(Duration.ofMinutes(50)))).isEqualTo(50);
    }

    @Test
    void minutesAreRoundedDownAndNeverNegative() {
        assertThat(EscalationClock.minutes(RAISED, RAISED)).isZero();
        assertThat(EscalationClock.minutes(RAISED, RAISED.plusSeconds(59))).isZero();
        assertThat(EscalationClock.minutes(RAISED, RAISED.plusSeconds(60))).isEqualTo(1);
        assertThat(EscalationClock.minutes(RAISED, RAISED.plusSeconds(15 * 60 + 30))).isEqualTo(15);
        assertThat(EscalationClock.minutes(RAISED, RAISED.plusSeconds(61 * 60 - 1))).isEqualTo(60);
        assertThat(EscalationClock.minutes(RAISED, RAISED.minusSeconds(30))).isZero();
        assertThat(EscalationClock.minutes(RAISED, RAISED.minusSeconds(3600))).isZero();
    }

    @Test
    void aVeryLongWaitDoesNotOverflow() {
        assertThat(EscalationClock.minutes(Instant.EPOCH, Instant.parse("9999-12-31T23:59:59Z")))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aHighEscalationAt15MinutesAnd30SecondsReadsFifteenMinutesAndIsAlreadyPast() {
        Instant asOf = RAISED.plusSeconds(15 * 60 + 30);

        assertThat(EscalationClock.waitingMinutes(RAISED, asOf)).isEqualTo(15);
        assertThat(EscalationClock.pastTarget(Status.OPEN, RAISED, 15, asOf)).isTrue();
    }

    @Test
    void aSignoffCoversAnEscalationOnlyWhenItIsStrictlyLaterThanTheClockStart() {
        assertThat(EscalationClock.signoffCovers(RAISED.plusNanos(1_000), RAISED)).isTrue();
        assertThat(EscalationClock.signoffCovers(RAISED, RAISED)).isFalse();
        assertThat(EscalationClock.signoffCovers(RAISED.minusSeconds(1), RAISED)).isFalse();
        assertThat(EscalationClock.signoffCovers(null, RAISED)).isFalse();
        assertThat(EscalationClock.signoffCovers(RAISED, null)).isFalse();
    }
}
