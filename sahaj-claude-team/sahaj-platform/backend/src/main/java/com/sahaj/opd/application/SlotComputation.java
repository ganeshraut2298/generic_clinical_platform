package com.sahaj.opd.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Port of the mockup's {@code slotsFor} (SAH-11 AC1): given a doctor's slot settings and a
 * calendar day, lists the day's bookable slot start times, marking every Nth slot per session as
 * kept for walk-ins. Returns an empty list on a day the doctor does not work (the mockup returns
 * {@code null} for the same case; an empty list is used here so callers never need a null check).
 */
final class SlotComputation {

    private SlotComputation() {
    }

    /** One session's start and end time, zero-indexed ({@code sessionIndex} 0 or 1). */
    record Session(int sessionIndex, LocalTime startTime, LocalTime endTime) {
    }

    /** One bookable slot of the day: its start time, which session it belongs to, and whether it is kept for walk-ins. */
    record Slot(LocalTime startTime, int sessionIndex, boolean walkInReserved) {
    }

    static List<Slot> slotsFor(LocalDate date, int slotMinutes, Set<Integer> workingDays, int walkInEveryNth,
                                List<Session> sessions) {
        if (!workingDays.contains(isoDayOfWeekToMockupDay(date.getDayOfWeek()))) {
            return List.of();
        }
        List<Slot> out = new ArrayList<>();
        for (Session session : sessions) {
            int startMinute = session.startTime().toSecondOfDay() / 60;
            int endMinute = session.endTime().toSecondOfDay() / 60;
            int i = 0;
            for (int minute = startMinute; minute + slotMinutes <= endMinute; minute += slotMinutes, i++) {
                boolean walkIn = walkInEveryNth > 0 && (i + 1) % walkInEveryNth == 0;
                out.add(new Slot(LocalTime.ofSecondOfDay(minute * 60L), session.sessionIndex(), walkIn));
            }
        }
        return out;
    }

    /** The mockup's {@code Date.getDay()}: 0 = Sunday .. 6 = Saturday. Java's {@link DayOfWeek} is 1 = Monday .. 7 = Sunday. */
    static int isoDayOfWeekToMockupDay(DayOfWeek dayOfWeek) {
        return dayOfWeek == DayOfWeek.SUNDAY ? 0 : dayOfWeek.getValue();
    }
}
