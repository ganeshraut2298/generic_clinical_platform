package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.DoseRepository.CorrectionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.ExecutionSlots.Result;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.RoundOrders;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-76 review #1 (ADR-027 D2): the execution line of the EMR sheet counts live scheduled doses only, exactly like the
 * MAR. An undone dose or one recorded in error makes the slot due or late again; a PRN dose never makes a slot recorded.
 * No database, no Spring.
 */
class ExecutionSlotsTest {

    /** 8:00 AM and 8:00 PM IST on 11 Nov 2026: the two slots of a BD order. */
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant EIGHT_PM = Instant.parse("2026-11-11T14:30:00Z");
    private static final EffectiveNursingSettings DEFAULTS = EffectiveNursingSettings.defaults();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID NURSE = UUID.randomUUID();

    /** Three hours after 8 AM: the morning slot is LATE, the evening slot LATER. */
    private static final Instant LATE_NOW = EIGHT_AM.plus(Duration.ofMinutes(185));
    /** Ten minutes after 8 AM: the morning slot is DUE. */
    private static final Instant DUE_NOW = EIGHT_AM.plus(Duration.ofMinutes(10));

    private final OrderTimeline timeline = RoundOrders.timeline(ORDER, ADMISSION, Frequency.BD,
            RoundOrders.LONG_AGO);
    private final OrderRow order = new OrderRow(ORDER, ADMISSION, UUID.randomUUID(), "pcm", "bd", "ACTIVE", NURSE,
            RoundOrders.LONG_AGO, null, List.of(), null, null, null, null, null, null, null, 0);

    private static DoseRow dose(Instant slotAt, String outcome, int attempt, String kind, CorrectionRow correction) {
        UUID id = UUID.randomUUID();
        return new DoseRow(id, ADMISSION, ORDER, UUID.randomUUID(), slotAt, outcome, null, NURSE, null, false,
                slotAt.plusSeconds(60), attempt, 1, kind, "GIVEN".equals(outcome) ? "SCAN" : null, null, false, null,
                correction);
    }

    private static DoseRow live(Instant slotAt, String outcome) {
        return dose(slotAt, outcome, 1, "SCHEDULED", null);
    }

    private static DoseRow corrected(Instant slotAt, String outcome, int attempt, String kind) {
        return dose(slotAt, outcome, attempt, kind,
                new CorrectionRow(UUID.randomUUID(), DoseRepository.KIND_UNDO, null, NURSE, slotAt.plusSeconds(120), null));
    }

    private Result of(List<DoseRow> doses, Instant asOf) {
        return ExecutionSlots.of(order, timeline, doses, true, DEFAULTS, asOf);
    }

    @Test
    void aLiveGivenDoseMakesTheMorningSlotRecordedSoNothingIsLateAndNextIsTheEvening() {
        Result r = of(List.of(live(EIGHT_AM, "GIVEN")), LATE_NOW);

        assertThat(r.lateCount()).isZero();
        assertThat(r.nextDueAt()).isEqualTo(EIGHT_PM);
    }

    @Test
    void withNoDoseTheMorningSlotIsLate() {
        Result r = of(List.of(), LATE_NOW);

        assertThat(r.lateCount()).isEqualTo(1);
        assertThat(r.nextDueAt()).isEqualTo(EIGHT_PM);
    }

    @Test
    void anUndoneDoseMakesTheSlotLateAgain() {
        Result r = of(List.of(corrected(EIGHT_AM, "GIVEN", 1, "SCHEDULED")), LATE_NOW);

        assertThat(r.lateCount()).as("the MAR shows this slot LATE again").isEqualTo(1);
    }

    @Test
    void anUndoneDoseMakesTheSlotDueAgainAndItIsTheNextDue() {
        Result r = of(List.of(corrected(EIGHT_AM, "GIVEN", 1, "SCHEDULED")), DUE_NOW);

        assertThat(r.lateCount()).isZero();
        assertThat(r.nextDueAt()).as("the slot is DUE again, so it is next").isEqualTo(EIGHT_AM);
    }

    @Test
    void aDoseRecordedInErrorMakesTheSlotLateOrDueAgain() {
        DoseRow inError = dose(EIGHT_AM, "GIVEN", 1, "SCHEDULED",
                new CorrectionRow(UUID.randomUUID(), "IN_ERROR", "WRONG_PATIENT", NURSE, EIGHT_AM.plusSeconds(300),
                        null));

        assertThat(of(List.of(inError), LATE_NOW).lateCount()).isEqualTo(1);
        assertThat(of(List.of(inError), DUE_NOW).nextDueAt()).isEqualTo(EIGHT_AM);
    }

    @Test
    void aCorrectedFirstAttemptAndALiveSecondAttemptCountOnceInAnyOrder() {
        DoseRow first = corrected(EIGHT_AM, "GIVEN", 1, "SCHEDULED");
        DoseRow second = dose(EIGHT_AM, "GIVEN", 2, "SCHEDULED", null);

        Result firstThenSecond = of(List.of(first, second), LATE_NOW);
        Result secondThenFirst = of(List.of(second, first), LATE_NOW);

        assertThat(firstThenSecond).isEqualTo(secondThenFirst);
        assertThat(firstThenSecond.lateCount()).as("the live second attempt records the slot").isZero();
        assertThat(firstThenSecond.nextDueAt()).isEqualTo(EIGHT_PM);
    }

    @Test
    void aLiveRefusedSecondAttemptStillRecordsTheSlotInAnyOrder() {
        DoseRow first = corrected(EIGHT_AM, "GIVEN", 1, "SCHEDULED");
        DoseRow second = dose(EIGHT_AM, "REFUSED", 2, "SCHEDULED", null);

        assertThat(of(List.of(first, second), LATE_NOW).lateCount()).isZero();
        assertThat(of(List.of(second, first), LATE_NOW).lateCount()).isZero();
    }

    @Test
    void aPrnDoseNeverRecordsAScheduledSlot() {
        DoseRow prn = dose(EIGHT_AM, "GIVEN", 1, "PRN", null);

        Result r = of(List.of(prn), LATE_NOW);

        assertThat(r.lateCount()).as("a PRN dose at the slot time does not count the slot as recorded").isEqualTo(1);
        assertThat(r.nextDueAt()).isEqualTo(EIGHT_PM);
    }

    @Test
    void aLivePrnRowAlongsideALiveScheduledDoseChangesNothing() {
        List<DoseRow> rows = List.of(live(EIGHT_AM, "GIVEN"), dose(EIGHT_PM, "GIVEN", 1, "PRN", null));

        Result r = of(rows, LATE_NOW);

        assertThat(r.lateCount()).isZero();
        assertThat(r.nextDueAt()).as("the PRN dose does not record the evening slot").isEqualTo(EIGHT_PM);
    }
}
