package com.sahaj.ipd.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * SAH-76-3 (ADR-027 D5): the two clocks of an order notice, pure and computed from the server clock at read time;
 * nothing is stored or sent. Whole minutes are rounded down.
 *
 * <ul>
 *   <li>"Not acknowledged for N min": an open notice is overdue when its waiting minutes are above the setting in
 *       force (the STAT setting for a STAT notice, else the ordinary one).</li>
 *   <li>The STAT line: {@code dueBy} is the slot plus the STAT window; before it "N min left", and "overdue by N
 *       min" (at least 1) once the whole minutes after the slot are above the window: the same whole-minute rule
 *       as the MAR slot ({@link DoseSlots#statMinutesOverdue}).</li>
 * </ul>
 */
public final class NoticeClock {

    private NoticeClock() {
    }

    /** Whole minutes from {@code createdAt} to {@code asOf}; 0 when the clock is not after it. */
    public static int waitingMinutes(Instant createdAt, Instant asOf) {
        return wholeMinutes(createdAt, asOf);
    }

    /** True when the notice is open and has waited more than the limit ({@code stat} picks the STAT limit). */
    public static boolean overdue(Instant createdAt, boolean stat, int unackMinutes, int unackStatMinutes,
                                  Instant asOf) {
        return waitingMinutes(createdAt, asOf) > (stat ? unackStatMinutes : unackMinutes);
    }

    /**
     * The STAT line of a one-time order. {@code minutesLeft} is set while the window runs (0 at the last minute),
     * {@code minutesOverdue} only once {@code dueBy} has passed.
     */
    public record StatLine(Instant dueBy, int windowMinutes, Integer minutesLeft, Integer minutesOverdue) {
    }

    /**
     * The STAT line of the slot. It follows the MAR slot ({@link DoseSlots#statMinutesOverdue}): overdue only once the
     * whole minutes after the slot are above the window, so at the window plus 30 s both still say "0 min left".
     */
    public static StatLine statLine(Instant slotAt, int windowMinutes, Instant asOf) {
        Instant dueBy = DoseSlots.statDueBy(slotAt, windowMinutes);
        long overdue = DoseSlots.statMinutesOverdue(slotAt.truncatedTo(ChronoUnit.MINUTES), windowMinutes, asOf);
        if (overdue == 0) {
            return new StatLine(dueBy, windowMinutes, asOf.isBefore(dueBy) ? wholeMinutes(asOf, dueBy) : 0, null);
        }
        return new StatLine(dueBy, windowMinutes, null, (int) Math.min(Integer.MAX_VALUE, overdue));
    }

    private static int wholeMinutes(Instant from, Instant to) {
        long seconds = Duration.between(from, to).getSeconds();
        return seconds <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, seconds / 60);
    }
}
