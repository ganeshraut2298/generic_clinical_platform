package com.sahaj.ipd.domain;

import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SAH-32 and SAH-76 (ADR-011 D2, ADR-027 D2): the dose slots of one order for today. Computed, never stored. The one slot
 * rule: the MAR, the tab and card counts, the Medicine round and the dose endpoint all call {@link #slots}. Pure Java:
 * no Spring and no I/O.
 *
 * <p>"Today" is the calendar date in Asia/Kolkata of {@code asOf}, which is always the database clock. Minutes are whole
 * minutes rounded down. Per order, today's slots are the union over the versions of the frequency's times with
 * {@code start_at <= slot < end_at}; a ONCE (STAT) version has one slot at its creation minute; a PRN version has none.
 * When two versions give the same instant the higher version number is kept.
 *
 * <p>State of a slot, first that applies (ADR-027 D2): a live dose record (GIVEN, HELD, REFUSED); the admission is not
 * ADMITTED (NOT_RECORDED); the order is stopped at or before the slot (STOPPED); the slot belongs to an older version
 * and is at or after that version's replacement (REPLACED); the slot is inside a hold (ON_HOLD); the slot's version is
 * not verified yet and is not a STAT (ONCE) version (WAITING); the version was verified today and the slot is more than
 * the window before that (BEFORE_START); then by the clock with the window in force: more than the window after is
 * LATE, from the window before to the window after is DUE, otherwise LATER. A STAT (ONCE) slot is DUE for the STAT
 * window from its time, then LATE, and never LATER.
 *
 * <p>A slot of an older (replaced) version that was not recorded keeps its clock state, but it is never givable and
 * never counted ({@link Slot#current()} is false).
 */
public final class DoseSlots {

    /** India has no daylight saving time. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The built window either side of a slot time (the default of the clinic settings), in whole minutes. */
    public static final long WINDOW_MINUTES = 60;

    private DoseSlots() {
    }

    /** What a slot shows (mockup sfSlot; ADR-027 D2). */
    public enum State {
        GIVEN, HELD, REFUSED, NOT_RECORDED, STOPPED, REPLACED, ON_HOLD, WAITING, BEFORE_START, LATE, DUE, LATER;

        /** DUE and LATE are the states of a button (and of the tab count). */
        public boolean actionable() {
            return this == LATE || this == DUE;
        }
    }

    /**
     * One slot of today.
     *
     * @param lateHours            LATE only: whole hours late (at least 1)
     * @param lateMinutes          LATE only: whole minutes after the slot; for a STAT slot, after the end of the STAT window
     * @param versionNo            the order version the slot belongs to
     * @param current              the slot belongs to the current version of the order
     * @param beforeVerification   the slot is the STAT (ONCE) slot of a current version that is not verified yet
     * @param stateAt              STOPPED, REPLACED, ON_HOLD: when it happened
     * @param stateBy              STOPPED, REPLACED, ON_HOLD: the user who did it (null when unknown)
     * @param doseId               GIVEN, HELD, REFUSED: the live dose record
     */
    public record Slot(Instant slotAt, State state, Integer lateHours, Integer lateMinutes, int versionNo,
                       boolean current, boolean beforeVerification, Instant stateAt, UUID stateBy, UUID doseId) {

        /** A DUE or LATE slot of the current version: the only kind that is a button and that is counted. */
        public boolean actionable() {
            return current && state.actionable();
        }
    }

    /** A live (not corrected) dose record of the order: its slot, outcome and id. */
    public record LiveDose(UUID id, Instant slotAt, DoseOutcome outcome) {
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

    /** The dose times of the frequency on today's IST date, in time order. PRN has none. */
    public static List<Instant> times(Frequency frequency, Instant asOf) {
        LocalDate date = today(asOf);
        List<Instant> instants = new ArrayList<>();
        for (LocalTime time : frequency.times()) {
            instants.add(ZonedDateTime.of(date, time, IST).toInstant());
        }
        return List.copyOf(instants);
    }

    /** The slot time of a ONCE version: its creation minute. */
    public static Instant onceSlot(MedicineOrderVersion version) {
        return version.createdAt().truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * The slots of today in time order.
     *
     * @param timeline  versions, holds and stop of the order
     * @param stoppedBy the user who stopped the order (for the STOPPED text), or null
     * @param live      today's live dose records of the order (corrected ones are not passed)
     * @param admitted  false for a discharged admission
     * @param settings  the windows in force for the patient
     * @param asOf      the database clock
     */
    public static List<Slot> slots(OrderTimeline timeline, UUID stoppedBy, Collection<LiveDose> live, boolean admitted,
                                   EffectiveNursingSettings settings, Instant asOf) {
        Objects.requireNonNull(live, "live");
        Map<Instant, LiveDose> doses = new HashMap<>();
        for (LiveDose dose : live) {
            doses.put(dose.slotAt(), dose);
        }
        int currentNo = timeline.current().versionNo();
        // Higher version wins an instant.
        Map<Instant, MedicineOrderVersion> candidates = new TreeMap<>();
        for (MedicineOrderVersion version : timeline.versions()) {
            for (Instant at : instantsOf(version, asOf)) {
                candidates.put(at, version);
            }
        }
        // A live dose of a slot no version generates any more (a course that ended): still shown as recorded.
        for (LiveDose dose : doses.values()) {
            if (!candidates.containsKey(dose.slotAt()) && today(dose.slotAt()).equals(today(asOf))
                    && timeline.versions().stream().anyMatch(v -> v.kind() != Kind.PRN)) {
                candidates.put(dose.slotAt(), timeline.current());
            }
        }
        List<Slot> slots = new ArrayList<>();
        for (Map.Entry<Instant, MedicineOrderVersion> e : candidates.entrySet()) {
            slots.add(slot(timeline, stoppedBy, doses.get(e.getKey()), e.getKey(), e.getValue(), currentNo, admitted,
                    settings, asOf));
        }
        return List.copyOf(slots);
    }

    private static List<Instant> instantsOf(MedicineOrderVersion version, Instant asOf) {
        List<Instant> instants = new ArrayList<>();
        if (version.kind() == Kind.SCHEDULED) {
            Optional<Frequency> frequency = Frequency.ofKey(version.frequencyKey());
            if (frequency.isPresent()) {
                for (Instant at : times(frequency.get(), asOf)) {
                    boolean started = !at.isBefore(version.startAt());
                    boolean notEnded = version.endAt() == null || at.isBefore(version.endAt());
                    if (started && notEnded) {
                        instants.add(at);
                    }
                }
            }
        } else if (version.kind() == Kind.ONCE) {
            Instant at = onceSlot(version);
            if (today(at).equals(today(asOf))) {
                instants.add(at);
            }
        }
        return instants;
    }

    private static Slot slot(OrderTimeline timeline, UUID stoppedBy, LiveDose dose, Instant at,
                             MedicineOrderVersion version, int currentNo, boolean admitted,
                             EffectiveNursingSettings settings, Instant asOf) {
        boolean current = version.versionNo() == currentNo;
        boolean beforeVerification = current && isStatBeforeVerification(version);
        if (dose != null) {
            return new Slot(at, State.valueOf(dose.outcome().name()), null, null, version.versionNo(), current, false,
                    null, null, dose.id());
        }
        if (!admitted) {
            return plain(at, State.NOT_RECORDED, version, current, beforeVerification);
        }
        if (timeline.isStoppedAt(at)) {
            return new Slot(at, State.STOPPED, null, null, version.versionNo(), current, false, timeline.stoppedAt(),
                    stoppedBy, null);
        }
        if (!current && version.supersededAt() != null && !at.isBefore(version.supersededAt())) {
            MedicineOrderVersion next = timeline.version(version.versionNo() + 1).orElse(timeline.current());
            return new Slot(at, State.REPLACED, null, null, version.versionNo(), false, false, version.supersededAt(),
                    next.createdBy(), null);
        }
        Optional<OrderHoldInterval> hold = timeline.holds().stream().filter(h -> h.covers(at)).findFirst();
        if (hold.isPresent()) {
            return new Slot(at, State.ON_HOLD, null, null, version.versionNo(), current, false,
                    hold.get().heldAt(), hold.get().heldBy(), null);
        }
        if (version.verification() == Verification.REQUIRED && !version.isVerified() && !isStat(version)) {
            return plain(at, State.WAITING, version, current, false);
        }
        return clock(at, version, current, beforeVerification, settings, asOf);
    }

    private static Slot plain(Instant at, State state, MedicineOrderVersion version, boolean current,
                              boolean beforeVerification) {
        return new Slot(at, state, null, null, version.versionNo(), current, beforeVerification, null, null, null);
    }

    private static boolean isStat(MedicineOrderVersion version) {
        return version.kind() == Kind.ONCE && version.priority() == OrderPriority.STAT;
    }

    private static boolean isStatBeforeVerification(MedicineOrderVersion version) {
        return isStat(version) && version.verification() == Verification.REQUIRED && !version.isVerified();
    }

    private static Slot clock(Instant at, MedicineOrderVersion version, boolean current, boolean beforeVerification,
                              EffectiveNursingSettings settings, Instant asOf) {
        if (version.kind() == Kind.ONCE) {
            int overdue = (int) statMinutesOverdue(at, settings.statWindowMin(), asOf);
            if (overdue == 0) {
                return new Slot(at, State.DUE, null, null, version.versionNo(), current, beforeVerification, null,
                        null, null);
            }
            return new Slot(at, State.LATE, Math.max(1, (int) (minutesAfter(at, asOf) / 60)), overdue,
                    version.versionNo(), current, beforeVerification, null, null, null);
        }
        State state = clockState(at, version.verifiedAt(), settings, asOf);
        if (state == State.LATE) {
            long after = minutes(at, asOf);
            return new Slot(at, state, Math.max(1, (int) (after / 60)), (int) after, version.versionNo(), current,
                    beforeVerification, null, null, null);
        }
        return new Slot(at, state, null, null, version.versionNo(), current, beforeVerification, null, null, null);
    }

    /**
     * BEFORE_START, LATE, DUE or LATER for a scheduled slot of an admitted, verified (or verification-free) version
     * (rows 7 and 8 of the ADR-027 D2 table). {@code verifiedAt} is null when the version needed no verification.
     */
    public static State clockState(Instant slotAt, Instant verifiedAt, EffectiveNursingSettings settings,
                                   Instant asOf) {
        if (verifiedAt != null && today(verifiedAt).equals(today(asOf))
                && slotAt.isBefore(verifiedAt.minus(Duration.ofMinutes(settings.windowBeforeMin())))) {
            return State.BEFORE_START;
        }
        // By design (ADR-011 D2): whole minutes are rounded down, so a slot 60 min 59 s ahead or behind is still DUE.
        if (!asOf.isBefore(slotAt)) {
            return minutes(slotAt, asOf) > settings.windowAfterMin() ? State.LATE : State.DUE;
        }
        return minutes(asOf, slotAt) > settings.windowBeforeMin() ? State.LATER : State.DUE;
    }

    /** Whether a dose may be recorded now for this slot of an order with this head status. */
    public static boolean givable(Slot slot, OrderStatus status) {
        if (!slot.actionable()) {
            return false;
        }
        if (status == OrderStatus.ACTIVE) {
            return true;
        }
        return status == OrderStatus.PENDING && slot.beforeVerification();
    }

    /** How many slots are DUE or LATE of the current version: the count on the tab (for an order that may be given). */
    public static int dueCount(List<Slot> slots) {
        int count = 0;
        for (Slot slot : slots) {
            if (slot.actionable()) {
                count++;
            }
        }
        return count;
    }

    /**
     * SAH-76 review #3: the one STAT clock. Whole minutes from the slot to {@code asOf}, rounded down (0 when {@code
     * asOf} is not after the slot). The MAR slot and the Orders tab STAT line both read it, so they cannot disagree.
     */
    public static long minutesAfter(Instant slotAt, Instant asOf) {
        return asOf.isBefore(slotAt) ? 0 : minutes(slotAt, asOf);
    }

    /**
     * A STAT slot is DUE while its whole minutes after the slot are at most the window and LATE above it. Returns how
     * many whole minutes it is LATE (at least 1), or 0 while it is still DUE.
     */
    public static long statMinutesOverdue(Instant slotAt, int windowMinutes, Instant asOf) {
        return Math.max(0, minutesAfter(slotAt, asOf) - windowMinutes);
    }

    /** The time a STAT slot is due by: the slot minute plus the STAT window ("dueBy" of the Orders tab). */
    public static Instant statDueBy(Instant slotAt, int windowMinutes) {
        return slotAt.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(windowMinutes));
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
