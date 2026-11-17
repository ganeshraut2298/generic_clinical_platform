package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.PrnRules.Decision;
import com.sahaj.ipd.domain.PrnRules.Lock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-76-4 the PRN limits (ADR-027 D7, OQ-76-19): minimum interval and the most doses in a rolling 24 hours. */
class PrnRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T08:30:00Z");

    private static Instant ago(long minutes) {
        return NOW.minus(Duration.ofMinutes(minutes));
    }

    @Test
    void withNoDoseTheDoseIsAllowed() {
        Decision decision = PrnRules.decide(List.of(), 240, 4, NOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.givenLast24h()).isZero();
        assertThat(decision.lastGivenAt()).isNull();
        assertThat(decision.nextAllowedAt()).isNull();
    }

    @Test
    void underTheMinimumIntervalIsTooSoonWithTheNextTime() {
        Decision decision = PrnRules.decide(List.of(ago(100)), 240, 4, NOW);

        assertThat(decision.lock()).isEqualTo(Lock.TOO_SOON);
        assertThat(decision.nextAllowedAt()).isEqualTo(ago(100).plus(Duration.ofMinutes(240)));
        assertThat(decision.givenLast24h()).isEqualTo(1);
    }

    @Test
    void exactlyTheMinimumIntervalIsAllowed() {
        assertThat(PrnRules.decide(List.of(ago(240)), 240, 4, NOW).allowed()).isTrue();
        assertThat(PrnRules.decide(List.of(ago(239)), 240, 4, NOW).lock()).isEqualTo(Lock.TOO_SOON);
    }

    @Test
    void theMaximumInTwentyFourHoursLocksUntilTheOldestCountedDoseLeavesTheWindow() {
        List<Instant> given = List.of(ago(1000), ago(800), ago(500), ago(300));

        Decision decision = PrnRules.decide(given, 60, 4, NOW);

        assertThat(decision.lock()).isEqualTo(Lock.MAX_REACHED);
        assertThat(decision.nextAllowedAt()).isEqualTo(ago(1000).plus(Duration.ofHours(24)));
        assertThat(decision.givenLast24h()).isEqualTo(4);
    }

    @Test
    void aDoseOlderThanTwentyFourHoursDoesNotCount() {
        List<Instant> given = List.of(ago(24 * 60 + 1), ago(800), ago(500), ago(300));

        Decision decision = PrnRules.decide(given, 60, 4, NOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.givenLast24h()).isEqualTo(3);
    }

    @Test
    void whenBothLimitsApplyTheLaterTimeIsGiven() {
        List<Instant> given = List.of(ago(1400), ago(30));

        Decision decision = PrnRules.decide(given, 600, 2, NOW);

        assertThat(decision.lock()).isEqualTo(Lock.MAX_REACHED);
        assertThat(decision.nextAllowedAt()).isEqualTo(ago(30).plus(Duration.ofMinutes(600)));
    }
}
