package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.MedicineRound.RoundOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-35 ADR-012 D3: the change token with dose lines and the one counts function. The token with no dose line is the
 * SAH-33 token (fixed vectors of SafetyRowsTest still hold); a dose recorded, a slot turning from due to late and a slot
 * entering the window change it; the growth of {@code lateHours} alone does not.
 */
class SafetyRowsRoundTest {

    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");

    private static Detail detail(UUID id, long version) {
        return new Detail(id, UUID.randomUUID(), version, 8, Risk.HIGH, 15, Status.OPEN,
                Instant.parse("2026-11-11T03:00:00Z"), UUID.randomUUID(), null, null, null, null, null, 0, null, null);
    }

    private static MedicineRound.Result round(Instant asOf, RoundOrder... orders) {
        return MedicineRound.compute(List.of(orders), List.of(), asOf);
    }

    private static RoundOrder odOrder(UUID id) {
        return new RoundOrder(id, UUID.randomUUID(), UUID.randomUUID(), "101", "Tab. Paracetamol 650 mg", null,
                Frequency.OD, LONG_AGO);
    }

    @Test
    void noDoseLineGivesTheSah33TokenAndTheFixedEmptyVector() {
        Detail d = detail(ID_1, 3);

        assertThat(SafetyRows.changeToken(List.of(), List.of())).isEqualTo("47DEQpj8HBSa-_TImW-5JA");
        assertThat(SafetyRows.counts(List.of(d), MedicineRound.empty(), EIGHT_AM).changeToken())
                .isEqualTo(SafetyRows.changeToken(List.of(d), List.of()));
    }

    @Test
    void theDoseLinesAreSortedWithTheEscalationLinesSoTheInputOrderDoesNotMatter() {
        Detail d = detail(ID_1, 3);
        List<String> lines = new ArrayList<>(List.of("dose:b:1:LATE", "dose:a:2:DUE", "dose:c:3:LATE"));
        String first = SafetyRows.changeToken(List.of(d), lines);
        Collections.reverse(lines);

        assertThat(SafetyRows.changeToken(List.of(d), lines)).isEqualTo(first);
        assertThat(first).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(first).isNotEqualTo(SafetyRows.changeToken(List.of(d), List.of()));
    }

    @Test
    void aSlotDisappearingBecauseItsDoseWasRecordedChangesTheToken() {
        RoundOrder a = odOrder(UUID.randomUUID());
        Instant asOf = EIGHT_AM.plus(Duration.ofMinutes(90));

        Counts with = SafetyRows.counts(List.of(), round(asOf, a), asOf);
        Counts without = SafetyRows.counts(List.of(), round(asOf), asOf);

        assertThat(with.changeToken()).isNotEqualTo(without.changeToken());
        assertThat(with.dosesLate()).isEqualTo(1);
        assertThat(without.dosesLate()).isZero();
    }

    @Test
    void aSlotTurningFromDueToLateChangesTheToken() {
        RoundOrder a = odOrder(UUID.randomUUID());

        Counts due = SafetyRows.counts(List.of(), round(EIGHT_AM.plus(Duration.ofMinutes(60)), a), EIGHT_AM);
        Counts late = SafetyRows.counts(List.of(), round(EIGHT_AM.plus(Duration.ofMinutes(61)), a), EIGHT_AM);

        assertThat(due.dosesDue()).isEqualTo(1);
        assertThat(late.dosesLate()).isEqualTo(1);
        assertThat(late.changeToken()).isNotEqualTo(due.changeToken());
    }

    @Test
    void aSlotEnteringTheWindowChangesTheToken() {
        RoundOrder a = odOrder(UUID.randomUUID());

        Counts outside = SafetyRows.counts(List.of(), round(EIGHT_AM.minus(Duration.ofMinutes(61)), a), EIGHT_AM);
        Counts inside = SafetyRows.counts(List.of(), round(EIGHT_AM.minus(Duration.ofMinutes(60)), a), EIGHT_AM);

        assertThat(outside.changeToken()).isEqualTo(SafetyRows.changeToken(List.of(), List.of()));
        assertThat(inside.changeToken()).isNotEqualTo(outside.changeToken());
    }

    @Test
    void oneDoseRecordedWhileAnotherBecomesLateKeepsTheCountsAndChangesTheToken() {
        RoundOrder first = odOrder(UUID.randomUUID());
        RoundOrder second = odOrder(UUID.randomUUID());
        Instant asOf = EIGHT_AM.plus(Duration.ofMinutes(90));

        Counts before = SafetyRows.counts(List.of(), round(asOf, first), asOf);
        Counts after = SafetyRows.counts(List.of(), round(asOf, second), asOf);

        assertThat(after.dosesLate()).isEqualTo(before.dosesLate());
        assertThat(after.dosesDue()).isEqualTo(before.dosesDue());
        assertThat(after.changeToken()).isNotEqualTo(before.changeToken());
    }

    @Test
    void theGrowthOfLateHoursAloneDoesNotChangeTheToken() {
        RoundOrder a = odOrder(UUID.randomUUID());

        Counts oneHour = SafetyRows.counts(List.of(), round(EIGHT_AM.plus(Duration.ofMinutes(70)), a), EIGHT_AM);
        Counts threeHours = SafetyRows.counts(List.of(), round(EIGHT_AM.plus(Duration.ofMinutes(200)), a), EIGHT_AM);

        assertThat(threeHours.changeToken()).isEqualTo(oneHour.changeToken());
    }

    @Test
    void theCountsCarryDueAndLateAndTheEscalationFiguresUnchanged() {
        Detail d = detail(ID_1, 1);
        RoundOrder a = odOrder(UUID.randomUUID());
        Instant asOf = EIGHT_AM.plus(Duration.ofMinutes(90));

        Counts counts = SafetyRows.counts(List.of(d), round(asOf, a), asOf);

        assertThat(counts.open()).isEqualTo(1);
        assertThat(counts.pastTarget()).isEqualTo(1);
        assertThat(counts.dosesLate()).isEqualTo(1);
        assertThat(counts.dosesDue()).isZero();
        assertThat(counts.asOf()).isEqualTo(asOf);
    }
}
