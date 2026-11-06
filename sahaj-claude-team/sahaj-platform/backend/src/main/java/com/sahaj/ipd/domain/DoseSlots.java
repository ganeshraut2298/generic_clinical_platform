package com.sahaj.ipd.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SAH-32 the dose slots of one order for today (ADR-011 D2): computed, never stored. Pure Java: no Spring and no I/O.
 *
 * <p>"Today" is the calendar date in Asia/Kolkata (no daylight saving) of {@code asOf}, which is always the database
 * clock ({@code clock_timestamp()}), never the application or device clock. A slot is the instant of that date at each
 * dose time of the frequency. Minutes are whole minutes rounded down.
 *
 * <p>State of a slot, first that applies: a dose record (GIVEN, HELD, REFUSED); the admission is not ADMITTED
 * (NOT_RECORDED); the order is still waiting for the pharmacist (WAITING); the order was checked today and the slot is
 * more than 60 minutes before that time (BEFORE_START); more than 60 minutes after the slot (LATE); from 60 minutes
 * before to 60 minutes after (DUE); otherwise LATER. Exactly 60 whole minutes after is still DUE, 61 is LATE; exactly 60
 * minutes ahead is DUE, 61 is LATER.
 */
public final class DoseSlots {

    /** India has no daylight saving time. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** The window either side of a slot time, in whole minutes. */
    public static final long WINDOW_MINUTES = 60;

    private DoseSlots() {
    }

    /** The part of the order's life that matters to a slot. Rejected and Stopped orders have no slots. */
    public enum Stage { PENDING, ACTIVE }

    /** What a slot shows (mockup sfSlot). */
    public enum State {
        GIVEN, HELD, REFUSED, NOT_RECORDED, WAITING, BEFORE_START, LATE, DUE, LATER;

        /** DUE and LATE are the states of a button (and of the tab count). */
        public boolean actionable() {
            return this == LATE || this == DUE;
        }
    }

    /** One slot: its instant, state, and for LATE the whole hours late (at least 1). */
    public record Slot(Instant slotAt, State state, Integer lateHours) {
    }

    /** The IST calendar date of the instant. */
    public static LocalDate today(Instant asOf) {
        return asOf.atZone(IST).toLocalDate();
    }

    /** Midnight (IST) at the start of today, as an instant. */
    public static Instant dayStart(Instant asOf) {
        return today(asOf).atStartOfDay(IST).toInstant();
    }

    /** Midnight (IST) at the end of today (the start of tomorrow), as an instant. */
    public static Instant dayEnd(Instant asOf) {
        return today(asOf).plusDays(1).atStartOfDay(IST).toInstant();
    }

    /** The dose times of the frequency on today's IST date, in time order. */
    public static List<Instant> times(Frequency frequency, Instant asOf) {
        LocalDate date = today(asOf);
        List<Instant> instants = new ArrayList<>();
        for (LocalTime time : frequency.times()) {
            instants.add(ZonedDateTime.of(date, time, IST).toInstant());
        }
        return List.copyOf(instants);
    }

    /**
     * The slots of today in time order.
     *
     * @param recorded   today's dose records of the order by slot instant (may be empty, never null)
     * @param verifiedAt when the pharmacist checked the order; null while PENDING
     */
    public static List<Slot> slots(Frequency frequency, Stage stage, Instant verifiedAt,
                                   Map<Instant, DoseOutcome> recorded, boolean admitted, Instant asOf) {
        Objects.requireNonNull(recorded, "recorded");
        List<Slot> slots = new ArrayList<>();
        for (Instant slotAt : times(frequency, asOf)) {
            DoseOutcome outcome = recorded.get(slotAt);
            State state = outcome != null ? State.valueOf(outcome.name())
                    : clockState(slotAt, stage, verifiedAt, admitted, asOf);
            slots.add(new Slot(slotAt, state, state == State.LATE ? lateHours(slotAt, asOf) : null));
        }
        return List.copyOf(slots);
    }

    /**
     * The state of a slot that has no dose record: NOT_RECORDED, WAITING, BEFORE_START, LATE, DUE or LATER. The dose
     * endpoint uses it to refuse a slot before it checks whether the slot is already recorded.
     */
    public static State clockState(Instant slotAt, Stage stage, Instant verifiedAt, boolean admitted, Instant asOf) {
        if (!admitted) {
            return State.NOT_RECORDED;
        }
        if (stage == Stage.PENDING) {
            return State.WAITING;
        }
        if (verifiedAt != null && today(verifiedAt).equals(today(asOf))
                && slotAt.isBefore(verifiedAt.minus(Duration.ofMinutes(WINDOW_MINUTES)))) {
            return State.BEFORE_START;
        }
        // By design (ADR-011 D2): whole minutes are rounded down, so a slot 60 min 59 s ahead or behind is still DUE.
        if (!asOf.isBefore(slotAt)) {
            return minutes(slotAt, asOf) > WINDOW_MINUTES ? State.LATE : State.DUE;
        }
        return minutes(asOf, slotAt) > WINDOW_MINUTES ? State.LATER : State.DUE;
    }

    /** Whole hours late, rounded down, for a slot that is more than 60 minutes past: at least 1. */
    static int lateHours(Instant slotAt, Instant asOf) {
        return (int) (minutes(slotAt, asOf) / 60);
    }

    /** How many slots are DUE or LATE: the count on the tab. */
    public static int dueCount(List<Slot> slots) {
        int count = 0;
        for (Slot slot : slots) {
            if (slot.state().actionable()) {
                count++;
            }
        }
        return count;
    }

    /** Whole minutes from {@code from} to {@code to}, rounded down; {@code from} is not after {@code to}. */
    private static long minutes(Instant from, Instant to) {
        return Duration.between(from, to).getSeconds() / 60;
    }

    /** The time of day in IST as the mockup writes it: "8 AM", "2 PM", "8:30 AM" (minutes left out when 0). */
    public static String clock(Instant instant) {
        ZonedDateTime time = instant.atZone(IST);
        int hour = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        String suffix = time.getHour() < 12 ? "AM" : "PM";
        return time.getMinute() == 0 ? hour + " " + suffix
                : hour + ":" + (time.getMinute() < 10 ? "0" : "") + time.getMinute() + " " + suffix;
    }
}
