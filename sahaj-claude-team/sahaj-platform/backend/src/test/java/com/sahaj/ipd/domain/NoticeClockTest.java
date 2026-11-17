package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.NoticeClock.StatLine;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-76-3 the two notice clocks: "not acknowledged for N min" and the STAT line, from the server clock. */
class NoticeClockTest {

    private static final Instant T0 = Instant.parse("2026-11-11T06:00:00Z");
    private static final UUID ORDER = UUID.randomUUID();

    @Test
    void waitingMinutesAreWholeMinutesRoundedDown() {
        assertThat(NoticeClock.waitingMinutes(T0, T0.plusSeconds(59))).isZero();
        assertThat(NoticeClock.waitingMinutes(T0, T0.plusSeconds(60))).isEqualTo(1);
        assertThat(NoticeClock.waitingMinutes(T0, T0.plusSeconds(61 * 60 + 59))).isEqualTo(61);
    }

    @Test
    void aClockNotAfterTheCreationTimeWaitsZero() {
        assertThat(NoticeClock.waitingMinutes(T0, T0)).isZero();
        assertThat(NoticeClock.waitingMinutes(T0, T0.minusSeconds(600))).isZero();
    }

    @Test
    void aNoticeIsOverdueOnlyAboveTheLimit() {
        assertThat(NoticeClock.overdue(T0, false, 30, 5, T0.plusSeconds(30 * 60))).isFalse();
        assertThat(NoticeClock.overdue(T0, false, 30, 5, T0.plusSeconds(31 * 60))).isTrue();
    }

    @Test
    void aStatNoticeUsesTheStatLimit() {
        assertThat(NoticeClock.overdue(T0, true, 30, 5, T0.plusSeconds(5 * 60))).isFalse();
        assertThat(NoticeClock.overdue(T0, true, 30, 5, T0.plusSeconds(6 * 60))).isTrue();
        assertThat(NoticeClock.overdue(T0, false, 30, 5, T0.plusSeconds(6 * 60))).isFalse();
    }

    @Test
    void theStatLineCountsDownInsideTheWindow() {
        StatLine line = NoticeClock.statLine(T0, 15, T0.plusSeconds(5 * 60 + 30));

        assertThat(line.dueBy()).isEqualTo(T0.plusSeconds(15 * 60));
        assertThat(line.windowMinutes()).isEqualTo(15);
        assertThat(line.minutesLeft()).isEqualTo(9);
        assertThat(line.minutesOverdue()).isNull();
    }

    @Test
    void atTheDueTimeThereAreZeroMinutesLeftAndItIsNotOverdue() {
        StatLine line = NoticeClock.statLine(T0, 15, T0.plusSeconds(15 * 60));

        assertThat(line.minutesLeft()).isZero();
        assertThat(line.minutesOverdue()).isNull();
    }

    @Test
    void theStatLineIsOverdueFromTheWholeMinuteAfterTheDueTimeByAtLeastOneMinute() {
        StatLine oneMinute = NoticeClock.statLine(T0, 15, T0.plusSeconds(16 * 60));
        StatLine later = NoticeClock.statLine(T0, 15, T0.plusSeconds(15 * 60 + 7 * 60 + 59));

        assertThat(oneMinute.minutesLeft()).isNull();
        assertThat(oneMinute.minutesOverdue()).isEqualTo(1);
        assertThat(later.minutesOverdue()).isEqualTo(7);
    }

    @Test
    void insideTheWholeMinuteAfterTheDueTimeTheLineStillSaysZeroLeftLikeTheMar() {
        StatLine justAfter = NoticeClock.statLine(T0, 15, T0.plusSeconds(15 * 60 + 30));
        StatLine lastSecond = NoticeClock.statLine(T0, 15, T0.plusSeconds(15 * 60 + 59));

        assertThat(justAfter.minutesLeft()).isZero();
        assertThat(justAfter.minutesOverdue()).isNull();
        assertThat(lastSecond.minutesLeft()).isZero();
        assertThat(lastSecond.minutesOverdue()).isNull();
    }

    @Test
    void theStatSlotIsTheCreationMinuteSoTheDueTimeIgnoresTheSeconds() {
        Instant createdAt = T0.plusSeconds(40);

        assertThat(DoseSlots.statDueBy(createdAt, 15)).isEqualTo(T0.plusSeconds(15 * 60));
        assertThat(NoticeClock.statLine(createdAt, 15, T0).dueBy()).isEqualTo(T0.plusSeconds(15 * 60));
    }

    // ---- review #3: the MAR and the Orders tab read one STAT clock ----

    private static Slot marSlot(Instant created, Instant asOf) {
        MedicineOrderVersion stat = new MedicineOrderVersion(ORDER, 1, Kind.ONCE, null, null, null, "IV", created, null,
                null, OrderPriority.STAT, null, null, null, RoundOrders.DOCTOR, created, null, Verification.REQUIRED,
                RoundOrders.DOCTOR, created);
        OrderTimeline timeline = new OrderTimeline(ORDER, UUID.randomUUID(), OrderStatus.ACTIVE, List.of(stat),
                List.of(), null);
        return DoseSlots.slots(timeline, RoundOrders.DOCTOR, List.of(), true, EffectiveNursingSettings.defaults(), asOf)
                .get(0);
    }

    @Test
    void atTheWindowPlusThirtySecondsTheMarSlotAndTheOrdersTabAgree() {
        Instant asOf = T0.plusSeconds(15 * 60 + 30);

        Slot slot = marSlot(T0.plusSeconds(10), asOf);
        StatLine line = NoticeClock.statLine(slot.slotAt(), 15, asOf);

        assertThat(slot.state()).as("MAR: whole minutes after the slot are 15, still within the window")
                .isEqualTo(State.DUE);
        assertThat(line.minutesOverdue()).as("Orders tab: not overdue either").isNull();
    }

    @Test
    void atTheWindowPlusSixtySecondsAndMoreTheMarSlotAndTheOrdersTabAgreeOnLateAndOnTheMinutes() {
        for (int extra : new int[] {60, 61, 119, 120, 600}) {
            Instant asOf = T0.plusSeconds(15 * 60 + extra);

            Slot slot = marSlot(T0.plusSeconds(10), asOf);
            StatLine line = NoticeClock.statLine(slot.slotAt(), 15, asOf);

            assertThat(slot.state()).as("MAR at +%d s", extra).isEqualTo(State.LATE);
            assertThat(line.minutesOverdue()).as("Orders tab at +%d s", extra).isEqualTo(slot.lateMinutes());
        }
    }

    @Test
    void insideTheWindowBothAreDue() {
        Instant asOf = T0.plusSeconds(5 * 60);

        assertThat(marSlot(T0, asOf).state()).isEqualTo(State.DUE);
        assertThat(NoticeClock.statLine(T0, 15, asOf).minutesOverdue()).isNull();
    }
}
