package com.sahaj.ipd.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * SAH-76-5 (ADR-027 D3, OQ-76-25): who may undo or mark a given dose as recorded in error, and until when. Pure Java. The
 * database guard repeats the time and recorder rules; this is what the service and the MAR flags call first.
 */
public final class DoseCorrectionRules {

    private DoseCorrectionRules() {
    }

    /** What stops a correction, in the order of the contract checks. */
    public enum Refusal { NOT_GIVEN_OR_CORRECTED, NOT_RECORDER, UNDO_EXPIRED, ERROR_WINDOW_CLOSED }

    /**
     * Undo: a GIVEN dose without a correction, by the nurse who recorded it, while the clock is not after
     * {@code undoUntil}. A dose with no window (written before SAH-76) cannot be undone.
     *
     * @return the refusal, or null when allowed
     */
    public static Refusal undo(boolean given, boolean corrected, UUID recordedBy, UUID caller, Instant undoUntil,
                               Instant now) {
        if (!given || corrected) {
            return Refusal.NOT_GIVEN_OR_CORRECTED;
        }
        if (!recordedBy.equals(caller)) {
            return Refusal.NOT_RECORDER;
        }
        if (undoUntil == null || now.isAfter(undoUntil)) {
            return Refusal.UNDO_EXPIRED;
        }
        return null;
    }

    /**
     * Recorded in error: a GIVEN dose without a correction, while the IST date of the clock is the IST date of the slot.
     * Who may do it (the recorder, any nurse in scope, the owner) is a role rule of the service.
     */
    public static Refusal inError(boolean given, boolean corrected, Instant slotAt, Instant now) {
        if (!given || corrected) {
            return Refusal.NOT_GIVEN_OR_CORRECTED;
        }
        if (!DoseSlots.today(slotAt).equals(DoseSlots.today(now))) {
            return Refusal.ERROR_WINDOW_CLOSED;
        }
        return null;
    }

    /** Whole seconds from {@code now} to {@code undoUntil}, never negative; 0 without a window. */
    public static int undoSecondsLeft(Instant undoUntil, Instant now) {
        if (undoUntil == null || !now.isBefore(undoUntil)) {
            return 0;
        }
        return (int) Math.max(0, Duration.between(now, undoUntil).getSeconds());
    }
}
