package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.application.SlotComputation.Session;
import com.sahaj.opd.application.SlotComputation.Slot;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SAH-11 AC1: a port of the mockup's {@code slotsFor}, hand-worked against its own fixtures. */
class SlotComputationTest {

    /** Mirrors the mockup's {@code d1}: len 15, one 09:00-13:30 session, every day, walk-in every 4th. */
    @Test
    void fifteenMinuteSlotsEveryFourthKeptForWalkIns() {
        List<Session> sessions = List.of(new Session(0, LocalTime.of(9, 0), LocalTime.of(9, 45)));

        List<Slot> slots = SlotComputation.slotsFor(LocalDate.of(2026, 11, 2), 15, Set.of(0, 1, 2, 3, 4, 5, 6), 4,
                sessions);

        assertThat(slots).extracting(Slot::startTime)
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30));
        assertThat(slots).extracting(Slot::walkInReserved).containsExactly(false, false, false);
        // A fourth slot would be reserved: check with a longer session.
        List<Slot> fourSlots = SlotComputation.slotsFor(LocalDate.of(2026, 11, 2), 15, Set.of(0, 1, 2, 3, 4, 5, 6), 4,
                List.of(new Session(0, LocalTime.of(9, 0), LocalTime.of(10, 0))));
        assertThat(fourSlots).extracting(Slot::walkInReserved).containsExactly(false, false, false, true);
    }

    @Test
    void zeroWalkInEveryNthMeansNoSlotIsReserved() {
        List<Slot> slots = SlotComputation.slotsFor(LocalDate.of(2026, 11, 2), 30, Set.of(0, 1, 2, 3, 4, 5, 6), 0,
                List.of(new Session(0, LocalTime.of(9, 0), LocalTime.of(10, 0))));

        assertThat(slots).extracting(Slot::walkInReserved).containsExactly(false, false);
    }

    @Test
    void twoSessionsAreBothExpandedWithTheirOwnIndex() {
        List<Session> sessions = List.of(
                new Session(0, LocalTime.of(9, 0), LocalTime.of(9, 30)),
                new Session(1, LocalTime.of(17, 0), LocalTime.of(17, 30)));

        List<Slot> slots = SlotComputation.slotsFor(LocalDate.of(2026, 11, 2), 30, Set.of(0, 1, 2, 3, 4, 5, 6), 0,
                sessions);

        assertThat(slots).extracting(Slot::sessionIndex).containsExactly(0, 1);
        assertThat(slots).extracting(Slot::startTime).containsExactly(LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    @Test
    void aNonWorkingDayHasNoSlots() {
        // 2026-11-02 is a Monday (day 1); a doctor who only works weekends has no slots that day.
        List<Slot> slots = SlotComputation.slotsFor(LocalDate.of(2026, 11, 2), 15, Set.of(0, 6), 0,
                List.of(new Session(0, LocalTime.of(9, 0), LocalTime.of(13, 0))));

        assertThat(slots).isEmpty();
    }

    @Test
    void mockupDayNumberingMatchesSundayZero() {
        assertThat(SlotComputation.isoDayOfWeekToMockupDay(LocalDate.of(2026, 11, 1).getDayOfWeek())).isEqualTo(0);
        assertThat(SlotComputation.isoDayOfWeekToMockupDay(LocalDate.of(2026, 11, 2).getDayOfWeek())).isEqualTo(1);
        assertThat(SlotComputation.isoDayOfWeekToMockupDay(LocalDate.of(2026, 11, 7).getDayOfWeek())).isEqualTo(6);
    }

    @Test
    void aSlotThatDoesNotFullyFitTheSessionIsDropped() {
        // 09:00-09:50 with 15 minute slots: 09:00, 09:15, 09:30 fit; 09:45-10:00 would overflow the session.
        List<Slot> slots = SlotComputation.slotsFor(LocalDate.of(2026, 11, 1), 15, Set.of(0), 0,
                List.of(new Session(0, LocalTime.of(9, 0), LocalTime.of(9, 50))));

        assertThat(slots).hasSize(3);
    }
}
