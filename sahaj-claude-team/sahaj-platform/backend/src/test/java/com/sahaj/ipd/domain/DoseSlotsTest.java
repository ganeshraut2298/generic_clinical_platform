package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.DoseSlots.LiveDose;
import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-32 and SAH-76 the one slot rule (ADR-011 D2, ADR-027 D2): the states in the order of the table, the windows in
 * force, versions, holds, stop, STAT (ONCE) and PRN. No database, no Spring.
 */
class DoseSlotsTest {

    /** 8:00 AM, 2:00 PM and 8:00 PM IST on 11 Nov 2026. */
    private static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant TWO_PM = Instant.parse("2026-11-11T08:30:00Z");
    private static final Instant EIGHT_PM = Instant.parse("2026-11-11T14:30:00Z");
    private static final Instant LONG_AGO = RoundOrders.LONG_AGO;
    private static final EffectiveNursingSettings DEFAULTS = EffectiveNursingSettings.defaults();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID DOCTOR = RoundOrders.DOCTOR;
    private static final UUID OTHER_DOCTOR = UUID.randomUUID();

    private static Instant minutesAfter(Instant at, long minutes) {
        return at.plus(Duration.ofMinutes(minutes));
    }

    private static MedicineOrderVersion scheduled(int no, Frequency f, Instant start, Instant end, Instant superseded,
                                                 Instant verifiedAt, UUID createdBy) {
        return new MedicineOrderVersion(ORDER, no, Kind.SCHEDULED, f.key(), null, null, "ORAL", start, end, null,
                OrderPriority.ROUTINE, null, null, no == 1 ? null : "Dose changed", createdBy, start, superseded,
                Verification.REQUIRED, verifiedAt == null ? null : DOCTOR, verifiedAt);
    }

    private static OrderTimeline timeline(OrderStatus status, Instant stoppedAt, List<OrderHoldInterval> holds,
                                          MedicineOrderVersion... versions) {
        return new OrderTimeline(ORDER, ADMISSION, status, List.of(versions), holds, stoppedAt);
    }

    private static List<Slot> slots(OrderTimeline timeline, List<LiveDose> live, boolean admitted,
                                    EffectiveNursingSettings settings, Instant asOf) {
        return DoseSlots.slots(timeline, DOCTOR, live, admitted, settings, asOf);
    }

    private static OrderTimeline verifiedBd() {
        return timeline(OrderStatus.ACTIVE, null, List.of(), scheduled(1, Frequency.BD, LONG_AGO, null, null,
                LONG_AGO, DOCTOR));
    }

    private static Slot at(List<Slot> slots, Instant slotAt) {
        return slots.stream().filter(s -> s.slotAt().equals(slotAt)).findFirst().orElseThrow();
    }

    // ---- clock states and the window (whole minutes rounded down) ----

    @Test
    void clockStatesAreLateDueAndLaterWithTheBuiltSixtyMinuteWindow() {
        List<Slot> slots = slots(verifiedBd(), List.of(), true, DEFAULTS, minutesAfter(EIGHT_AM, 185));

        assertThat(at(slots, EIGHT_AM).state()).isEqualTo(State.LATE);
        assertThat(at(slots, EIGHT_AM).lateHours()).isEqualTo(3);
        assertThat(at(slots, EIGHT_AM).lateMinutes()).isEqualTo(185);
        assertThat(at(slots, EIGHT_PM).state()).isEqualTo(State.LATER);
        assertThat(at(slots, EIGHT_AM).current()).isTrue();
    }

    @Test
    void exactlySixtyMinutesAfterIsDueAndSixtyOneIsLate() {
        assertThat(at(slots(verifiedBd(), List.of(), true, DEFAULTS, minutesAfter(EIGHT_AM, 60)), EIGHT_AM).state())
                .isEqualTo(State.DUE);
        assertThat(at(slots(verifiedBd(), List.of(), true, DEFAULTS, minutesAfter(EIGHT_AM, 61)), EIGHT_AM).state())
                .isEqualTo(State.LATE);
    }

    @Test
    void exactlySixtyMinutesAheadIsDueAndSixtyOneIsLater() {
        assertThat(at(slots(verifiedBd(), List.of(), true, DEFAULTS, minutesAfter(EIGHT_PM, -60)), EIGHT_PM).state())
                .isEqualTo(State.DUE);
        assertThat(at(slots(verifiedBd(), List.of(), true, DEFAULTS, minutesAfter(EIGHT_PM, -61)), EIGHT_PM).state())
                .isEqualTo(State.LATER);
    }

    @Test
    void theWindowsOfTheWardProfileReplaceTheBuiltSixtyMinutes() {
        EffectiveNursingSettings thirty = new EffectiveNursingSettings(true, 30, 30, 15, 30, 5, 10, 240);

        assertThat(at(slots(verifiedBd(), List.of(), true, thirty, minutesAfter(EIGHT_PM, -30)), EIGHT_PM).state())
                .isEqualTo(State.DUE);
        assertThat(at(slots(verifiedBd(), List.of(), true, thirty, minutesAfter(EIGHT_PM, -31)), EIGHT_PM).state())
                .isEqualTo(State.LATER);
        Slot late = at(slots(verifiedBd(), List.of(), true, thirty, minutesAfter(EIGHT_AM, 31)), EIGHT_AM);
        assertThat(late.state()).isEqualTo(State.LATE);
        assertThat(late.lateHours()).as("at least one hour is shown even with a short window").isEqualTo(1);
        assertThat(late.lateMinutes()).isEqualTo(31);
    }

    // ---- the order of the table ----

    @Test
    void aLiveDoseRecordDecidesBeforeAnythingElse() {
        UUID dose = UUID.randomUUID();
        OrderTimeline stopped = timeline(OrderStatus.STOPPED, EIGHT_AM, List.of(), scheduled(1, Frequency.BD, LONG_AGO,
                null, null, LONG_AGO, DOCTOR));

        List<Slot> slots = slots(stopped, List.of(new LiveDose(dose, EIGHT_AM, DoseOutcome.REFUSED)), false, DEFAULTS,
                minutesAfter(EIGHT_AM, 30));

        assertThat(at(slots, EIGHT_AM).state()).isEqualTo(State.REFUSED);
        assertThat(at(slots, EIGHT_AM).doseId()).isEqualTo(dose);
        assertThat(at(slots, EIGHT_PM).state()).as("not admitted comes before stopped").isEqualTo(State.NOT_RECORDED);
    }

    @Test
    void aStoppedOrderShowsStoppedForSlotsAtOrAfterTheStop() {
        Instant stop = minutesAfter(EIGHT_AM, 120);
        OrderTimeline stopped = timeline(OrderStatus.STOPPED, stop, List.of(), scheduled(1, Frequency.TDS, LONG_AGO,
                null, null, LONG_AGO, DOCTOR));

        List<Slot> slots = slots(stopped, List.of(), true, DEFAULTS, minutesAfter(EIGHT_AM, 130));

        assertThat(at(slots, EIGHT_AM).state()).isEqualTo(State.LATE);
        Slot twoPm = at(slots, TWO_PM);
        assertThat(twoPm.state()).isEqualTo(State.STOPPED);
        assertThat(twoPm.stateAt()).isEqualTo(stop);
        assertThat(twoPm.stateBy()).isEqualTo(DOCTOR);
        assertThat(at(slots, EIGHT_PM).state()).isEqualTo(State.STOPPED);
    }

    @Test
    void aSlotAtTheMomentOfTheStopIsStopped() {
        OrderTimeline stopped = timeline(OrderStatus.STOPPED, TWO_PM, List.of(), scheduled(1, Frequency.TDS, LONG_AGO,
                null, null, LONG_AGO, DOCTOR));

        assertThat(at(slots(stopped, List.of(), true, DEFAULTS, TWO_PM), TWO_PM).state()).isEqualTo(State.STOPPED);
    }

    @Test
    void aChangeReplacesTheLaterSlotsOfTheOlderVersionWhenTheNewOneHasNoSlotThere() {
        Instant change = minutesAfter(EIGHT_AM, 180);
        MedicineOrderVersion v1 = scheduled(1, Frequency.BD, LONG_AGO, null, change, LONG_AGO, DOCTOR);
        MedicineOrderVersion v2 = scheduled(2, Frequency.OD, change, null, null, change, OTHER_DOCTOR);

        List<Slot> slots = slots(timeline(OrderStatus.ACTIVE, null, List.of(), v1, v2), List.of(), true, DEFAULTS,
                minutesAfter(change, 5));

        Slot eightAm = at(slots, EIGHT_AM);
        assertThat(eightAm.state()).as("missed before the change: clock state of the old version").isEqualTo(State.LATE);
        assertThat(eightAm.current()).isFalse();
        assertThat(eightAm.actionable()).as("never givable, never counted").isFalse();
        Slot eightPm = at(slots, EIGHT_PM);
        assertThat(eightPm.state()).isEqualTo(State.REPLACED);
        assertThat(eightPm.stateAt()).isEqualTo(change);
        assertThat(eightPm.stateBy()).isEqualTo(OTHER_DOCTOR);
        assertThat(DoseSlots.dueCount(slots)).isZero();
    }

    @Test
    void whenTwoVersionsHaveTheSameInstantTheCurrentVersionWins() {
        Instant change = minutesAfter(EIGHT_AM, 180);
        MedicineOrderVersion v1 = scheduled(1, Frequency.BD, LONG_AGO, null, change, LONG_AGO, DOCTOR);
        MedicineOrderVersion v2 = scheduled(2, Frequency.BD, change, null, null, change, OTHER_DOCTOR);

        Slot eightPm = at(slots(timeline(OrderStatus.ACTIVE, null, List.of(), v1, v2), List.of(), true, DEFAULTS,
                minutesAfter(EIGHT_PM, -30)), EIGHT_PM);

        assertThat(eightPm.versionNo()).isEqualTo(2);
        assertThat(eightPm.current()).isTrue();
        assertThat(eightPm.state()).isEqualTo(State.DUE);
    }

    @Test
    void aSlotInsideAnOpenHoldIsOnHoldAndNeverDueOrLate() {
        Instant held = minutesAfter(EIGHT_AM, 60);
        OrderTimeline onHold = timeline(OrderStatus.ON_HOLD, null,
                List.of(new OrderHoldInterval(held, OTHER_DOCTOR, "Reason", null, null)),
                scheduled(1, Frequency.TDS, LONG_AGO, null, null, LONG_AGO, DOCTOR));

        List<Slot> slots = slots(onHold, List.of(), true, DEFAULTS, minutesAfter(TWO_PM, 5));

        assertThat(at(slots, EIGHT_AM).state()).as("before the hold").isEqualTo(State.LATE);
        Slot twoPm = at(slots, TWO_PM);
        assertThat(twoPm.state()).isEqualTo(State.ON_HOLD);
        assertThat(twoPm.stateAt()).isEqualTo(held);
        assertThat(twoPm.stateBy()).isEqualTo(OTHER_DOCTOR);
        assertThat(DoseSlots.dueCount(slots)).as("a held slot is never counted").isEqualTo(1);
    }

    @Test
    void aClosedHoldOnlyCoversItsOwnInterval() {
        OrderTimeline resumed = timeline(OrderStatus.ACTIVE, null,
                List.of(new OrderHoldInterval(minutesAfter(EIGHT_AM, 300), OTHER_DOCTOR, "Reason",
                        minutesAfter(EIGHT_AM, 420), OTHER_DOCTOR)),
                scheduled(1, Frequency.TDS, LONG_AGO, null, null, LONG_AGO, DOCTOR));

        List<Slot> slots = slots(resumed, List.of(), true, DEFAULTS, minutesAfter(TWO_PM, 5));

        assertThat(at(slots, TWO_PM).state()).isEqualTo(State.ON_HOLD);
        assertThat(at(slots, EIGHT_PM).state()).isEqualTo(State.LATER);
    }

    @Test
    void anUnverifiedVersionWaitsForThePharmacist() {
        OrderTimeline pending = timeline(OrderStatus.PENDING, null, List.of(), scheduled(1, Frequency.BD, LONG_AGO,
                null, null, null, DOCTOR));

        List<Slot> slots = slots(pending, List.of(), true, DEFAULTS, minutesAfter(EIGHT_AM, 30));

        assertThat(slots).extracting(Slot::state).containsOnly(State.WAITING);
        assertThat(DoseSlots.dueCount(slots)).isZero();
    }

    @Test
    void aVersionThatNeedsNoVerificationIsGivableAtOnce() {
        MedicineOrderVersion free = new MedicineOrderVersion(ORDER, 1, Kind.SCHEDULED, "bd", null, null, "ORAL",
                LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, DOCTOR, LONG_AGO, null,
                Verification.NOT_REQUIRED, null, null);

        List<Slot> slots = slots(timeline(OrderStatus.ACTIVE, null, List.of(), free), List.of(), true, DEFAULTS,
                minutesAfter(EIGHT_AM, 10));

        assertThat(at(slots, EIGHT_AM).state()).isEqualTo(State.DUE);
    }

    @Test
    void aSlotMoreThanTheWindowBeforeAMidDayVerificationIsBeforeStart() {
        Instant verified = Instant.parse("2026-11-11T05:30:00Z");
        OrderTimeline timeline = timeline(OrderStatus.ACTIVE, null, List.of(), scheduled(1, Frequency.TDS, LONG_AGO,
                null, null, verified, DOCTOR));

        List<Slot> slots = slots(timeline, List.of(), true, DEFAULTS, minutesAfter(verified, 1));

        assertThat(at(slots, EIGHT_AM).state()).isEqualTo(State.BEFORE_START);
        assertThat(at(slots, TWO_PM).state()).isEqualTo(State.LATER);
    }

    @Test
    void aNotAdmittedPatientHasNoRecordedSlots() {
        assertThat(slots(verifiedBd(), List.of(), false, DEFAULTS, EIGHT_AM)).extracting(Slot::state)
                .containsOnly(State.NOT_RECORDED);
    }

    @Test
    void aSlotBeforeTheVersionStartsAndAfterItEndsIsNotGenerated() {
        Instant start = minutesAfter(EIGHT_AM, 60);
        Instant end = minutesAfter(TWO_PM, 60);
        OrderTimeline timeline = timeline(OrderStatus.ACTIVE, null, List.of(), scheduled(1, Frequency.TDS, start, end,
                null, LONG_AGO, DOCTOR));

        assertThat(slots(timeline, List.of(), true, DEFAULTS, TWO_PM)).extracting(Slot::slotAt)
                .containsExactly(TWO_PM);
    }

    // ---- STAT (ONCE) and PRN ----

    private static MedicineOrderVersion stat(Instant created, Instant verifiedAt) {
        return new MedicineOrderVersion(ORDER, 1, Kind.ONCE, null, null, null, "IV", created, null, null,
                OrderPriority.STAT, null, null, null, DOCTOR, created, null, Verification.REQUIRED,
                verifiedAt == null ? null : OTHER_DOCTOR, verifiedAt);
    }

    @Test
    void aStatSlotIsDueForTheStatWindowThenLateAndNeverWaitingOrLater() {
        Instant created = Instant.parse("2026-11-11T05:30:20Z");
        Instant slot = Instant.parse("2026-11-11T05:30:00Z");
        OrderTimeline pending = timeline(OrderStatus.PENDING, null, List.of(), stat(created, null));

        Slot due = slots(pending, List.of(), true, DEFAULTS, minutesAfter(slot, 15)).get(0);
        Slot late = slots(pending, List.of(), true, DEFAULTS, minutesAfter(slot, 40)).get(0);

        assertThat(due.slotAt()).isEqualTo(slot);
        assertThat(due.state()).isEqualTo(State.DUE);
        assertThat(due.beforeVerification()).isTrue();
        assertThat(late.state()).isEqualTo(State.LATE);
        assertThat(late.lateMinutes()).as("counted from the end of the STAT window").isEqualTo(25);
        assertThat(DoseSlots.givable(due, OrderStatus.PENDING)).isTrue();
        assertThat(DoseSlots.givable(due, OrderStatus.QUERIED)).isFalse();
    }

    @Test
    void aStatSlotBelongsToItsIstDay() {
        Instant created = Instant.parse("2026-11-10T18:25:00Z");

        assertThat(slots(timeline(OrderStatus.ACTIVE, null, List.of(), stat(created, created)), List.of(), true,
                DEFAULTS, Instant.parse("2026-11-10T18:28:00Z"))).hasSize(1);
        assertThat(slots(timeline(OrderStatus.ACTIVE, null, List.of(), stat(created, created)), List.of(), true,
                DEFAULTS, Instant.parse("2026-11-10T18:40:00Z"))).isEmpty();
    }

    @Test
    void aVerifiedStatSlotIsGivableAsAnActiveOrder() {
        Instant created = Instant.parse("2026-11-11T05:30:20Z");
        Slot slot = slots(timeline(OrderStatus.ACTIVE, null, List.of(), stat(created, created)), List.of(), true,
                DEFAULTS, minutesAfter(created, 2)).get(0);

        assertThat(slot.state()).isEqualTo(State.DUE);
        assertThat(slot.beforeVerification()).isFalse();
        assertThat(DoseSlots.givable(slot, OrderStatus.ACTIVE)).isTrue();
        assertThat(DoseSlots.givable(slot, OrderStatus.PENDING)).as("only a dose before verification").isFalse();
    }

    @Test
    void aPrnOrderHasNoSlots() {
        MedicineOrderVersion prn = new MedicineOrderVersion(ORDER, 1, Kind.PRN, null, null, null, "ORAL", LONG_AGO, null,
                "Pain", OrderPriority.ROUTINE, 240, 4, null, DOCTOR, LONG_AGO, null, Verification.NOT_REQUIRED, null,
                null);

        List<Slot> slots = slots(timeline(OrderStatus.ACTIVE, null, List.of(), prn),
                List.of(new LiveDose(UUID.randomUUID(), EIGHT_AM, DoseOutcome.GIVEN)), true, DEFAULTS, TWO_PM);

        assertThat(slots).isEmpty();
    }

    @Test
    void givableNeedsAnActiveOrderAndACurrentActionableSlot() {
        List<Slot> slots = slots(verifiedBd(), List.of(), true, DEFAULTS, minutesAfter(EIGHT_AM, 5));
        Slot due = at(slots, EIGHT_AM);

        assertThat(DoseSlots.givable(due, OrderStatus.ACTIVE)).isTrue();
        assertThat(DoseSlots.givable(due, OrderStatus.ON_HOLD)).isFalse();
        assertThat(DoseSlots.givable(due, OrderStatus.PENDING)).isFalse();
        assertThat(DoseSlots.givable(at(slots, EIGHT_PM), OrderStatus.ACTIVE)).as("LATER is not givable").isFalse();
    }

    @Test
    void theTimeOfDayIsWrittenAsTheMockupWritesIt() {
        assertThat(DoseSlots.clock(EIGHT_AM)).isEqualTo("8 AM");
        assertThat(DoseSlots.clock(EIGHT_PM)).isEqualTo("8 PM");
        assertThat(DoseSlots.clock(Instant.parse("2026-11-11T03:00:00Z"))).isEqualTo("8:30 AM");
        assertThat(DoseSlots.clock(Instant.parse("2026-11-10T18:30:00Z"))).isEqualTo("12 AM");
    }

    @Test
    void theDayRunsFromMidnightToMidnightInIst() {
        assertThat(DoseSlots.dayStart(EIGHT_AM)).isEqualTo(Instant.parse("2026-11-10T18:30:00Z"));
        assertThat(DoseSlots.dayEnd(EIGHT_AM)).isEqualTo(Instant.parse("2026-11-11T18:30:00Z"));
        List<Instant> times = new ArrayList<>(DoseSlots.times(Frequency.TDS, EIGHT_AM));
        assertThat(times).containsExactly(EIGHT_AM, TWO_PM, EIGHT_PM);
        assertThat(DoseSlots.times(Frequency.PRN, EIGHT_AM)).isEmpty();
    }
}
