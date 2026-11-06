package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.Stage;
import com.sahaj.ipd.domain.DoseSlots.State;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * SAH-32 the slots of today (ADR-011 D2), as tables: every frequency, the IST day boundaries, the late/due/later edges
 * in whole minutes rounded down, the state order, and an order checked in the middle of the day. Pure Java, no database.
 */
class DoseSlotsTest {

    /** 11 Nov 2026, 8 AM IST. */
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant ONE_PM_IST = Instant.parse("2026-11-11T07:30:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-11-01T05:00:00Z");

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private static State stateOf(Instant slotAt, Instant asOf) {
        return DoseSlots.clockState(slotAt, Stage.ACTIVE, LONG_AGO, true, asOf);
    }

    // ---- the time zone and the day ----

    @Test
    void indiaHasNoDaylightSavingSoTheOffsetIsTheSameAllYear() {
        for (int month = 1; month <= 12; month++) {
            assertThat(DoseSlots.IST.getRules().getOffset(LocalDateTime.of(2026, month, 15, 12, 0)))
                    .isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
        }
    }

    @ParameterizedTest(name = "{0} is {1} in IST")
    @CsvSource({
            "2026-11-10T18:29:59Z, 2026-11-10",
            "2026-11-10T18:30:00Z, 2026-11-11",
            "2026-11-11T00:00:00Z, 2026-11-11",
            "2026-11-11T18:29:59.999999Z, 2026-11-11",
            "2026-11-11T18:30:00Z, 2026-11-12",
            "2026-12-31T18:29:59Z, 2026-12-31",
            "2026-12-31T18:30:00Z, 2027-01-01",
            "2028-02-28T18:30:00Z, 2028-02-29"
    })
    void todayIsTheIstCalendarDateOfTheDatabaseClock(String asOf, String date) {
        assertThat(DoseSlots.today(Instant.parse(asOf))).isEqualTo(LocalDate.parse(date));
    }

    @Test
    void theDayRunsFromIstMidnightToIstMidnight() {
        Instant asOf = utc("2026-11-11T09:00:00Z");

        assertThat(DoseSlots.dayStart(asOf)).isEqualTo(utc("2026-11-10T18:30:00Z"));
        assertThat(DoseSlots.dayEnd(asOf)).isEqualTo(utc("2026-11-11T18:30:00Z"));
        assertThat(Duration.between(DoseSlots.dayStart(asOf), DoseSlots.dayEnd(asOf))).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void justBeforeIstMidnightTheSlotsAreStillOfTheOldDayAndJustAfterTheyAreOfTheNewDay() {
        assertThat(DoseSlots.times(Frequency.OD, utc("2026-11-10T18:29:59Z"))).containsExactly(
                utc("2026-11-10T02:30:00Z"));
        assertThat(DoseSlots.times(Frequency.OD, utc("2026-11-10T18:30:00Z"))).containsExactly(
                utc("2026-11-11T02:30:00Z"));
    }

    // ---- every frequency ----

    @Test
    void onceDailyIsEightAm() {
        assertThat(DoseSlots.times(Frequency.OD, ONE_PM_IST)).containsExactly(utc("2026-11-11T02:30:00Z"));
    }

    @Test
    void twiceDailyIsEightAmAndEightPm() {
        assertThat(DoseSlots.times(Frequency.BD, ONE_PM_IST)).containsExactly(utc("2026-11-11T02:30:00Z"),
                utc("2026-11-11T14:30:00Z"));
    }

    @Test
    void threeTimesDailyIsEightAmTwoPmAndEightPm() {
        assertThat(DoseSlots.times(Frequency.TDS, ONE_PM_IST)).containsExactly(utc("2026-11-11T02:30:00Z"),
                utc("2026-11-11T08:30:00Z"), utc("2026-11-11T14:30:00Z"));
    }

    @Test
    void eightHourlyIsSixAmTwoPmAndTenPm() {
        assertThat(DoseSlots.times(Frequency.Q8, ONE_PM_IST)).containsExactly(utc("2026-11-11T00:30:00Z"),
                utc("2026-11-11T08:30:00Z"), utc("2026-11-11T16:30:00Z"));
    }

    @ParameterizedTest
    @EnumSource(Frequency.class)
    void theSlotTimesAgreeWithTheDoseTimeTextsOfTheFrequency(Frequency frequency) {
        List<String> clocks = DoseSlots.times(frequency, ONE_PM_IST).stream().map(DoseSlots::clock).toList();

        assertThat(clocks).hasSameSizeAs(frequency.doseTimes());
        for (int i = 0; i < clocks.size(); i++) {
            // "8:00 AM" in the order text is "8 AM" on the MAR (minutes left out when 0)
            assertThat(frequency.doseTimes().get(i).replace(":00", "")).isEqualTo(clocks.get(i));
        }
    }

    @ParameterizedTest
    @EnumSource(Frequency.class)
    void everyFrequencyGivesBetweenOneAndThreeSlotsInTimeOrderAtWholeMinutes(Frequency frequency) {
        List<Instant> times = DoseSlots.times(frequency, ONE_PM_IST);

        assertThat(times).isNotEmpty().hasSizeLessThanOrEqualTo(3).isSorted();
        for (Instant t : times) {
            assertThat(t.getEpochSecond() % 60).isZero();
            assertThat(t.getNano()).isZero();
        }
    }

    // ---- the clock text ----

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "2026-11-11T02:30:00Z, 8 AM",
            "2026-11-11T08:30:00Z, 2 PM",
            "2026-11-11T14:30:00Z, 8 PM",
            "2026-11-11T16:30:00Z, 10 PM",
            "2026-11-11T00:30:00Z, 6 AM",
            "2026-11-11T03:00:00Z, 8:30 AM",
            "2026-11-11T03:05:00Z, 8:35 AM",
            "2026-11-11T18:30:00Z, 12 AM",
            "2026-11-11T18:35:00Z, 12:05 AM",
            "2026-11-11T06:30:00Z, 12 PM",
            "2026-11-11T06:35:00Z, 12:05 PM",
            "2026-11-11T05:30:00Z, 11 AM",
            "2026-11-11T07:30:00Z, 1 PM"
    })
    void theClockIsWrittenAsTheMockupWritesIt(String instant, String text) {
        assertThat(DoseSlots.clock(Instant.parse(instant))).isEqualTo(text);
    }

    // ---- due, late and later, in whole minutes rounded down ----

    @ParameterizedTest(name = "{0} seconds after the slot is {1}")
    @CsvSource({
            "0, DUE",
            "1, DUE",
            "1800, DUE",
            "3599, DUE",
            "3600, DUE",
            "3659, DUE",
            "3660, LATE",
            "3661, LATE",
            "7199, LATE",
            "7200, LATE",
            "10799, LATE",
            "10800, LATE",
            "36000, LATE"
    })
    void afterTheSlotItIsDueUpToSixtyWholeMinutesAndLateFromSixtyOne(long secondsAfter, State expected) {
        assertThat(stateOf(EIGHT_AM, EIGHT_AM.plusSeconds(secondsAfter))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} seconds before the slot is {1}")
    @CsvSource({
            "1, DUE",
            "1800, DUE",
            "3599, DUE",
            "3600, DUE",
            "3601, DUE",
            "3659, DUE",
            "3660, LATER",
            "3661, LATER",
            "7200, LATER",
            "21600, LATER"
    })
    void beforeTheSlotItIsDueFromSixtyWholeMinutesAheadAndLaterBeforeThat(long secondsBefore, State expected) {
        assertThat(stateOf(EIGHT_AM, EIGHT_AM.minusSeconds(secondsBefore))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} minutes late is {1} hours late")
    @CsvSource({"61, 1", "90, 1", "119, 1", "120, 2", "121, 2", "179, 2", "180, 3", "600, 10", "900, 15"})
    void theHoursLateAreRoundedDownAndAreAtLeastOne(long minutes, int hours) {
        List<Slot> slots = DoseSlots.slots(Frequency.OD, Stage.ACTIVE, LONG_AGO, Map.of(), true,
                EIGHT_AM.plus(Duration.ofMinutes(minutes)));

        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.state()).isEqualTo(State.LATE);
            assertThat(slot.lateHours()).isEqualTo(hours);
        });
    }

    @Test
    void exactlySixtyMinutesLateIsStillDueAndHasNoLateHours() {
        List<Slot> slots = DoseSlots.slots(Frequency.OD, Stage.ACTIVE, LONG_AGO, Map.of(), true,
                EIGHT_AM.plus(Duration.ofMinutes(60)));

        assertThat(slots).singleElement().satisfies(slot -> {
            assertThat(slot.state()).isEqualTo(State.DUE);
            assertThat(slot.lateHours()).isNull();
        });
    }

    @Test
    void sixtyMinutesAndFiftyNineSecondsLateRoundsDownToSixtyAndIsDue() {
        assertThat(stateOf(EIGHT_AM, EIGHT_AM.plusSeconds(60 * 60 + 59))).isEqualTo(State.DUE);
    }

    @Test
    void theDatabaseClockCountsInMicrosecondsAndTheRoundingStillHolds() {
        Instant justUnderSixtyOne = EIGHT_AM.plusSeconds(3660).minusNanos(1000);

        assertThat(stateOf(EIGHT_AM, justUnderSixtyOne)).isEqualTo(State.DUE);
        assertThat(stateOf(EIGHT_AM, justUnderSixtyOne.plusNanos(1000))).isEqualTo(State.LATE);
    }

    @Test
    void aSlotThatIsNotLateHasNoLateHours() {
        List<Slot> slots = DoseSlots.slots(Frequency.TDS, Stage.ACTIVE, LONG_AGO, Map.of(), true, ONE_PM_IST);

        assertThat(slots).extracting(Slot::state).containsExactly(State.LATE, State.DUE, State.LATER);
        assertThat(slots).extracting(Slot::lateHours).containsExactly(5, null, null);
    }

    // ---- the state order ----

    @Test
    void aRecordedDoseWinsOverEveryOtherState() {
        // late, recorded as given; the admission is gone; the order is pending: the record still shows
        for (DoseOutcome outcome : DoseOutcome.values()) {
            List<Slot> slots = DoseSlots.slots(Frequency.OD, Stage.PENDING, null, Map.of(EIGHT_AM, outcome), false,
                    ONE_PM_IST);

            assertThat(slots).singleElement().satisfies(slot -> {
                assertThat(slot.state().name()).isEqualTo(outcome.name());
                assertThat(slot.lateHours()).isNull();
            });
        }
    }

    @Test
    void anAdmissionThatIsNotAdmittedShowsNotRecordedBeforeWaiting() {
        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.PENDING, null, false, ONE_PM_IST)).isEqualTo(State.NOT_RECORDED);
        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, LONG_AGO, false, ONE_PM_IST))
                .isEqualTo(State.NOT_RECORDED);
        assertThat(DoseSlots.clockState(EIGHT_AM.plus(Duration.ofHours(12)), Stage.ACTIVE, LONG_AGO, false, ONE_PM_IST))
                .isEqualTo(State.NOT_RECORDED);
    }

    @Test
    void aPendingOrderWaitsForThePharmacistWhateverTheClockSays() {
        for (long minutes : new long[] {-600, -61, -60, 0, 60, 61, 600}) {
            assertThat(DoseSlots.clockState(EIGHT_AM, Stage.PENDING, null, true,
                    EIGHT_AM.plus(Duration.ofMinutes(minutes)))).isEqualTo(State.WAITING);
        }
    }

    @Test
    void onlyDueAndLateAreActionable() {
        for (State state : State.values()) {
            assertThat(state.actionable()).isEqualTo(state == State.DUE || state == State.LATE);
        }
    }

    // ---- an order checked in the middle of the day (AC10, OQ-32-2) ----

    @Test
    void anOrderCheckedAtHalfPastTenHasNoEightAmDoseButKeepsEightPm() {
        Instant verifiedAt = utc("2026-11-11T05:00:00Z"); // 10:30 AM IST
        List<Slot> slots = DoseSlots.slots(Frequency.BD, Stage.ACTIVE, verifiedAt, Map.of(), true,
                verifiedAt.plusSeconds(60));

        assertThat(slots).extracting(Slot::state).containsExactly(State.BEFORE_START, State.LATER);
        assertThat(slots.get(0).lateHours()).isNull();
    }

    @Test
    void anOrderCheckedAtQuarterToNineKeepsTheEightAmDoseWhichIsDueUntilNineAndLateAfter() {
        Instant verifiedAt = utc("2026-11-11T03:15:00Z"); // 8:45 AM IST

        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, verifiedAt, true, verifiedAt)).isEqualTo(State.DUE);
        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, verifiedAt, true, EIGHT_AM.plus(Duration.ofMinutes(60))))
                .isEqualTo(State.DUE);
        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, verifiedAt, true, EIGHT_AM.plus(Duration.ofMinutes(61))))
                .isEqualTo(State.LATE);
    }

    @ParameterizedTest(name = "checked {0} seconds after 8 AM: the 8 AM slot is {1}")
    @CsvSource({
            "-7200, DUE",
            "0, DUE",
            "3600, DUE",
            "3601, BEFORE_START",
            "3660, BEFORE_START",
            "7200, BEFORE_START",
            "36000, BEFORE_START"
    })
    void theEdgeOfTheSixtyMinuteWindowBeforeTheCheck(long secondsAfterEight, State expected) {
        Instant verifiedAt = EIGHT_AM.plusSeconds(secondsAfterEight);
        // the clock is a minute after the check, still the same IST day
        Instant asOf = verifiedAt.plusSeconds(60);

        State state = DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, verifiedAt, true, asOf);

        if (expected == State.DUE) {
            assertThat(state).isNotEqualTo(State.BEFORE_START);
        } else {
            assertThat(state).isEqualTo(expected);
        }
    }

    @Test
    void exactlySixtyMinutesBeforeTheCheckTheSlotStillCounts() {
        Instant verifiedAt = EIGHT_AM.plus(Duration.ofMinutes(60));

        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, verifiedAt, true, verifiedAt.plusSeconds(10)))
                .isEqualTo(State.DUE);
        assertThat(DoseSlots.clockState(EIGHT_AM, Stage.ACTIVE, verifiedAt.plusSeconds(1), true,
                verifiedAt.plusSeconds(10))).isEqualTo(State.BEFORE_START);
    }

    @Test
    void anOrderCheckedOnAnEarlierDayCountsEverySlotOfToday() {
        Instant yesterday = utc("2026-11-10T17:00:00Z"); // 10:30 PM IST on 10 Nov
        List<Slot> slots = DoseSlots.slots(Frequency.TDS, Stage.ACTIVE, yesterday, Map.of(), true, ONE_PM_IST);

        assertThat(slots).extracting(Slot::state).containsExactly(State.LATE, State.DUE, State.LATER);
    }

    @Test
    void anOrderCheckedJustAfterIstMidnightHasNoSlotBeforeItsStartWindowOnThatDay() {
        Instant verifiedAt = utc("2026-11-10T18:31:00Z"); // 12:01 AM IST on 11 Nov
        List<Slot> slots = DoseSlots.slots(Frequency.Q8, Stage.ACTIVE, verifiedAt, Map.of(), true, verifiedAt);

        // 6 AM is 5 h 59 min after the check: ahead, later
        assertThat(slots).extracting(Slot::state).containsExactly(State.LATER, State.LATER, State.LATER);
    }

    @Test
    void theCheckOnTheSameIstDayButBeforeMidnightUtcIsStillToday() {
        // 11 Nov 12:30 AM IST is 10 Nov 19:00 UTC: UTC date differs from the IST date
        Instant verifiedAt = utc("2026-11-10T19:00:00Z");
        Instant asOf = utc("2026-11-11T06:30:00Z");

        assertThat(DoseSlots.clockState(utc("2026-11-10T18:30:00Z").plus(Duration.ofHours(8)), Stage.ACTIVE, verifiedAt,
                true, asOf)).isEqualTo(State.LATE);
        assertThat(DoseSlots.today(verifiedAt)).isEqualTo(DoseSlots.today(asOf));
    }

    // ---- the count on the tab ----

    @Test
    void theCountIsTheDueAndLateSlots() {
        List<Slot> slots = DoseSlots.slots(Frequency.TDS, Stage.ACTIVE, LONG_AGO, Map.of(), true, ONE_PM_IST);

        assertThat(DoseSlots.dueCount(slots)).isEqualTo(2);
    }

    @Test
    void aRecordedSlotIsNotCounted() {
        List<Slot> slots = DoseSlots.slots(Frequency.TDS, Stage.ACTIVE, LONG_AGO,
                Map.of(EIGHT_AM, DoseOutcome.GIVEN), true, ONE_PM_IST);

        assertThat(DoseSlots.dueCount(slots)).isEqualTo(1);
    }

    @Test
    void aWaitingSlotOrABeforeStartSlotOrAnyNotRecordedSlotIsNotCounted() {
        assertThat(DoseSlots.dueCount(DoseSlots.slots(Frequency.BD, Stage.PENDING, null, Map.of(), true, ONE_PM_IST)))
                .isZero();
        assertThat(DoseSlots.dueCount(DoseSlots.slots(Frequency.BD, Stage.ACTIVE, LONG_AGO, Map.of(), false, ONE_PM_IST)))
                .isZero();
        Instant verifiedAt = utc("2026-11-11T05:00:00Z");
        assertThat(DoseSlots.dueCount(DoseSlots.slots(Frequency.OD, Stage.ACTIVE, verifiedAt, Map.of(), true,
                verifiedAt))).isZero();
    }

    @Test
    void theSlotsOfOneFrequencyOfOneOrderOnTwoDaysDoNotMix() {
        Instant day1 = utc("2026-11-11T06:30:00Z");
        Instant day2 = utc("2026-11-12T06:30:00Z");
        // the dose recorded for day 1's 8 AM is not a record for day 2's 8 AM
        List<Slot> slots = DoseSlots.slots(Frequency.OD, Stage.ACTIVE, LONG_AGO,
                Map.of(DoseSlots.times(Frequency.OD, day1).get(0), DoseOutcome.GIVEN), true, day2);

        assertThat(slots).singleElement().satisfies(slot -> assertThat(slot.state()).isEqualTo(State.LATE));
    }

    @Test
    void theSlotListCannotBeChanged() {
        List<Slot> slots = DoseSlots.slots(Frequency.OD, Stage.ACTIVE, LONG_AGO, Map.of(), true, ONE_PM_IST);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> slots.add(slots.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
