package com.sahaj.opd.application;

import com.sahaj.opd.application.DoctorSlotSettingsRepository.SessionRow;
import com.sahaj.opd.application.DoctorSlotSettingsRepository.SlotSettingsRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SAH-90, SAH-91 (ADR-032 D3, D4): the one rule for "is this slot free" and "may this booking be made". The snapshot, the
 * finder, the counters, POST /appointments, reschedule and the later follow-up bookings all call it, so a slot the screen
 * offers is a slot the server accepts. Pure: no database, no clock of its own (the caller passes the Asia/Kolkata "now").
 */
final class BookingPolicy {

    /** Why a booking is refused, in the order of ADR-032 D4 rows 5 to 8. */
    enum Refusal { NOT_WORKING, NOT_ON_GRID, WALK_IN_ONLY, IN_PAST, BOOKING_CLOSED }

    /** A free slot; {@code insideCutOff} is only ever true for staff. */
    record OpenSlot(LocalTime time, int sessionIndex, boolean insideCutOff) {
    }

    private BookingPolicy() {
    }

    static boolean isStaff(String role) {
        return "OWNER".equals(role) || "FRONT_DESK".equals(role);
    }

    /** The doctor's own cut-off when set, else the clinic's. */
    static int effectiveCloseBefore(Integer doctorCloseBeforeHours, int clinicCloseBeforeHours) {
        return doctorCloseBeforeHours != null ? doctorCloseBeforeHours : clinicCloseBeforeHours;
    }

    static boolean insideCutOff(LocalDate date, LocalTime time, LocalDateTime now, int closeBeforeHours) {
        return LocalDateTime.of(date, time).isBefore(now.plusHours(closeBeforeHours));
    }

    /** A working day with at least one session. */
    static boolean working(SlotSettingsRow plan, LocalDate date) {
        return !plan.sessions().isEmpty()
                && plan.workingDays().contains(SlotComputation.isoDayOfWeekToMockupDay(date.getDayOfWeek()));
    }

    static List<SlotComputation.Slot> gridOf(SlotSettingsRow plan, LocalDate date) {
        List<SlotComputation.Session> sessions = new ArrayList<>();
        for (SessionRow s : plan.sessions()) {
            sessions.add(new SlotComputation.Session(s.sessionIndex(), s.startTime(), s.endTime()));
        }
        return SlotComputation.slotsFor(date, plan.slotMinutes(), new HashSet<>(plan.workingDays()),
                plan.walkInEveryNth(), sessions);
    }

    /** Checks 5 to 8 of ADR-032 D4; null when the time may be booked by this caller. Leave and clashes are the caller's. */
    static Refusal firstRefusal(SlotSettingsRow plan, int clinicCloseBeforeHours, LocalDate date, LocalTime time,
                                LocalDateTime now, boolean staff) {
        if (!working(plan, date)) {
            return Refusal.NOT_WORKING;
        }
        SlotComputation.Slot found = null;
        for (SlotComputation.Slot slot : gridOf(plan, date)) {
            if (slot.startTime().equals(time)) {
                found = slot;
                break;
            }
        }
        if (found == null) {
            return Refusal.NOT_ON_GRID;
        }
        if (found.walkInReserved()) {
            return Refusal.WALK_IN_ONLY;
        }
        if (!LocalDateTime.of(date, time).isAfter(now)) {
            return Refusal.IN_PAST;
        }
        int closeBefore = effectiveCloseBefore(plan.closeBeforeHours(), clinicCloseBeforeHours);
        if (!staff && insideCutOff(date, time, now, closeBefore)) {
            return Refusal.BOOKING_CLOSED;
        }
        return null;
    }

    /**
     * The free slots of one doctor on one day for this caller, earliest first: on the grid, not walk-in reserved, after now,
     * not on leave, not taken, and outside the cut-off unless staff (who get them flagged).
     */
    static List<OpenSlot> openSlots(SlotSettingsRow plan, int clinicCloseBeforeHours, LocalDate date, LocalDateTime now,
                                    boolean staff, boolean onLeave, Set<LocalTime> taken) {
        List<OpenSlot> out = new ArrayList<>();
        if (onLeave || !working(plan, date)) {
            return out;
        }
        int closeBefore = effectiveCloseBefore(plan.closeBeforeHours(), clinicCloseBeforeHours);
        for (SlotComputation.Slot slot : gridOf(plan, date)) {
            if (slot.walkInReserved() || taken.contains(slot.startTime())
                    || !LocalDateTime.of(date, slot.startTime()).isAfter(now)) {
                continue;
            }
            boolean inside = insideCutOff(date, slot.startTime(), now, closeBefore);
            if (inside && !staff) {
                continue;
            }
            out.add(new OpenSlot(slot.startTime(), slot.sessionIndex(), inside));
        }
        out.sort((a, b) -> a.time().compareTo(b.time()));
        return out;
    }

    /** Morning for a session starting before 12:00 IST, else Evening (ADR-032 D3). */
    static boolean morning(LocalTime sessionStart) {
        return sessionStart.isBefore(LocalTime.NOON);
    }
}
