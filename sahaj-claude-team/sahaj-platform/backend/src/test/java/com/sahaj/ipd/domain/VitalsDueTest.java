package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SAH-30 AC10: vitals are due with no record, or when the last one is more than 240 whole minutes old. */
class VitalsDueTest {

    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00Z");

    @Test
    void anAdmittedPatientWithNoVitalsIsDue() {
        assertThat(VitalsDue.isDue(true, null, 240, NOW)).isTrue();
    }

    @Test
    void exactly240MinutesIsNotDueAndTheNextWholeMinuteIs() {
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(240)), 240, NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(241)), 240, NOW)).isTrue();
    }

    @Test
    void minutesAreRoundedDownSo240MinutesAnd59SecondsIsNotDue() {
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofSeconds(240 * 60 + 59)), 240, NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofSeconds(241 * 60)), 240, NOW)).isTrue();
    }

    @Test
    void recentVitalsAreNotDue() {
        assertThat(VitalsDue.isDue(true, NOW, 240, NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(35)), 240, NOW)).isFalse();
    }

    @Test
    void aRecordInTheFutureIsNotDue() {
        assertThat(VitalsDue.isDue(true, NOW.plusSeconds(30), 240, NOW)).isFalse();
    }

    @Test
    void theIntervalOfTheWardProfileDecidesWhenVitalsAreDue() {
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(60)), 60, NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(61)), 60, NOW)).isTrue();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(61)), 1440, NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, null, 60, NOW)).as("no vitals is always due").isTrue();
        assertThat(VitalsDue.isDue(false, null, 60, NOW)).isFalse();
    }

    @Test
    void theDueTimeIsTheLastRecordPlusItsInterval() {
        assertThat(VitalsDue.dueAt(NOW, 60)).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(VitalsDue.dueAt(null, 60)).isNull();
        assertThat(VitalsDue.DEFAULT_INTERVAL_MINUTES).isEqualTo(240);
    }

    @Test
    void aDischargedAdmissionIsNeverDue() {
        assertThat(VitalsDue.isDue(false, null, 240, NOW)).isFalse();
        assertThat(VitalsDue.isDue(false, NOW.minus(Duration.ofDays(2)), 240, NOW)).isFalse();
    }
}
