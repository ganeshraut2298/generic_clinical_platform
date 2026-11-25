package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.application.BookingPolicy.OpenSlot;
import com.sahaj.opd.application.BookingPolicy.Refusal;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SAH-90, SAH-91 (ADR-032 D3, D4): the one slot and booking rule. */
class BookingPolicyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);

    private static SlotSettingsRow plan(int walkInEveryNth, Integer doctorCloseBefore) {
        return new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), walkInEveryNth, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        new SessionRow(1, LocalTime.of(17, 0), LocalTime.of(18, 0))), doctorCloseBefore);
    }

    private static List<String> times(List<OpenSlot> slots) {
        return slots.stream().map(s -> s.time().toString()).toList();
    }

    @Test
    void anOpenSlotIsOnTheGridAfterNowAndNotTaken() {
        List<OpenSlot> open = BookingPolicy.openSlots(plan(0, null), 1, DAY, NOW, false, false,
                Set.of(LocalTime.of(11, 0)));

        assertThat(times(open)).doesNotContain("09:00", "10:00", "10:15", "11:00");
        assertThat(times(open)).contains("11:15", "17:00");
    }

    @Test
    void nonStaffDoNotSeeSlotsInsideTheClinicCutOffButStaffDoWithTheFlag() {
        List<OpenSlot> nurse = BookingPolicy.openSlots(plan(0, null), 1, DAY, NOW, false, false, Set.of());
        List<OpenSlot> staff = BookingPolicy.openSlots(plan(0, null), 1, DAY, NOW, true, false, Set.of());

        assertThat(times(nurse)).doesNotContain("10:15", "10:45").contains("11:00");
        assertThat(times(staff)).contains("10:15", "10:45", "11:00");
        assertThat(staff).filteredOn(OpenSlot::insideCutOff).extracting(s -> s.time().toString())
                .containsExactly("10:15", "10:30", "10:45");
        assertThat(nurse).noneMatch(OpenSlot::insideCutOff);
    }

    @Test
    void theDoctorsOwnCutOffWinsOverTheClinicValue() {
        // clinic 1 hour, doctor 3 hours: for a nurse the first slot is 13:00 (none left in the morning session)
        List<OpenSlot> nurse = BookingPolicy.openSlots(plan(0, 3), 1, DAY, NOW, false, false, Set.of());

        assertThat(times(nurse)).containsExactly("17:00", "17:15", "17:30", "17:45");
        assertThat(BookingPolicy.effectiveCloseBefore(3, 1)).isEqualTo(3);
        assertThat(BookingPolicy.effectiveCloseBefore(null, 1)).isEqualTo(1);
        // a doctor value of 0 is a real value, not "unset"
        assertThat(BookingPolicy.effectiveCloseBefore(0, 5)).isZero();
    }

    @Test
    void walkInReservedSlotsAreNeverOffered() {
        List<OpenSlot> open = BookingPolicy.openSlots(plan(4, null), 1, DAY, NOW, true, false, Set.of());

        // every 4th slot of a session is kept for walk-ins: 09:45, 10:45, 11:45, 12:45
        assertThat(times(open)).doesNotContain("10:45", "11:45", "12:45").contains("10:15", "11:00");
    }

    @Test
    void leaveAndNonWorkingDaysHaveNoSlots() {
        assertThat(BookingPolicy.openSlots(plan(0, null), 1, DAY, NOW, true, true, Set.of())).isEmpty();
        SlotSettingsRow noWednesday = new SlotSettingsRow(15, List.of(1, 2), 0, 0,
                List.of(new SessionRow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))));
        assertThat(BookingPolicy.openSlots(noWednesday, 1, DAY, NOW, true, false, Set.of())).isEmpty();
        assertThat(BookingPolicy.working(noWednesday, DAY)).isFalse();
    }

    @Test
    void aDoctorWithNoSessionDoesNotWork() {
        SlotSettingsRow none = new SlotSettingsRow(15, List.of(0, 1, 2, 3, 4, 5, 6), 0, 0, List.of());

        assertThat(BookingPolicy.working(none, DAY)).isFalse();
        assertThat(BookingPolicy.firstRefusal(none, 1, DAY, LocalTime.of(12, 0), NOW, true))
                .isEqualTo(Refusal.NOT_WORKING);
    }

    @Test
    void refusalsComeInTheContractOrder() {
        SlotSettingsRow p = plan(4, null);
        LocalTime tenSeven = LocalTime.of(10, 7);

        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, tenSeven, NOW, true)).isEqualTo(Refusal.NOT_ON_GRID);
        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, LocalTime.of(13, 0), NOW, true))
                .isEqualTo(Refusal.NOT_ON_GRID);
        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, LocalTime.of(11, 45), NOW, true))
                .isEqualTo(Refusal.WALK_IN_ONLY);
        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, LocalTime.of(9, 0), NOW, true)).isEqualTo(Refusal.IN_PAST);
        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, LocalTime.of(10, 0), NOW, true)).isEqualTo(Refusal.IN_PAST);
    }

    @Test
    void theCutOffRefusesNonStaffOnlyAndNeverStaff() {
        SlotSettingsRow p = plan(0, null);
        LocalTime inside = LocalTime.of(10, 30);

        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, inside, NOW, false)).isEqualTo(Refusal.BOOKING_CLOSED);
        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, inside, NOW, true)).isNull();
        assertThat(BookingPolicy.firstRefusal(p, 1, DAY, LocalTime.of(11, 0), NOW, false)).isNull();
    }

    @Test
    void theCutOffUsesTheDoctorsValueWhenSetElseTheClinics() {
        assertThat(BookingPolicy.firstRefusal(plan(0, 3), 1, DAY, LocalTime.of(11, 0), NOW, false))
                .isEqualTo(Refusal.BOOKING_CLOSED);
        assertThat(BookingPolicy.firstRefusal(plan(0, null), 1, DAY, LocalTime.of(11, 0), NOW, false)).isNull();
        assertThat(BookingPolicy.firstRefusal(plan(0, 0), 5, DAY, LocalTime.of(10, 15), NOW, false)).isNull();
    }

    @Test
    void roleHelpers() {
        assertThat(BookingPolicy.isStaff("OWNER")).isTrue();
        assertThat(BookingPolicy.isStaff("FRONT_DESK")).isTrue();
        assertThat(BookingPolicy.isStaff("NURSE")).isFalse();
        assertThat(BookingPolicy.isStaff(null)).isFalse();
        assertThat(BookingPolicy.morning(LocalTime.of(11, 59))).isTrue();
        assertThat(BookingPolicy.morning(LocalTime.NOON)).isFalse();
    }
}
