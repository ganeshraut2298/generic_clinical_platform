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
        assertThat(VitalsDue.isDue(true, null, NOW)).isTrue();
    }

    @Test
    void exactly240MinutesIsNotDueAndTheNextWholeMinuteIs() {
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(240)), NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(241)), NOW)).isTrue();
    }

    @Test
    void minutesAreRoundedDownSo240MinutesAnd59SecondsIsNotDue() {
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofSeconds(240 * 60 + 59)), NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofSeconds(241 * 60)), NOW)).isTrue();
    }

    @Test
    void recentVitalsAreNotDue() {
        assertThat(VitalsDue.isDue(true, NOW, NOW)).isFalse();
        assertThat(VitalsDue.isDue(true, NOW.minus(Duration.ofMinutes(35)), NOW)).isFalse();
    }

    @Test
    void aRecordInTheFutureIsNotDue() {
        assertThat(VitalsDue.isDue(true, NOW.plusSeconds(30), NOW)).isFalse();
    }

    @Test
    void aDischargedAdmissionIsNeverDue() {
        assertThat(VitalsDue.isDue(false, null, NOW)).isFalse();
        assertThat(VitalsDue.isDue(false, NOW.minus(Duration.ofDays(2)), NOW)).isFalse();
    }
}
