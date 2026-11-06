package com.sahaj.ipd.domain;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.util.UUID;

/**
 * SAH-33 the early warning escalation state machine (ADR-007 D2, D4, D7). Pure rules, no Spring and no I/O: the
 * services read the row under the admission lock, ask this class what the request means, and only then write. The
 * database guard trigger enforces the same transitions as a backstop.
 *
 * <pre>
 *   OPEN -> ACK    Acknowledge
 *   OPEN -> DONE   Close escalation (the doctor is also stored as the acknowledging doctor), or the discharge close
 *   ACK  -> DONE   Close escalation, or the discharge close
 *   ACK  -> OPEN   only when a vitals save raises the risk from MEDIUM to HIGH (re-open)
 *   DONE is final.
 * </pre>
 *
 * <p>Check order of every action: a state that cannot take the action (409 escalation-state) is answered before a
 * stale version (409 escalation-changed); a repeat of the same user's own earlier request is a replay.
 */
public final class EscalationState {

    private EscalationState() {
    }

    /** The three stored states. */
    public enum Status {
        OPEN, ACK, DONE;

        /** The stored value; an unknown value is a data fault and fails fast. */
        public static Status of(String stored) {
            return valueOf(stored);
        }
    }

    /** What a request means for the escalation as it is now. */
    public enum Answer {
        /** Go ahead and write. */
        WRITE,
        /** The same user's own earlier request already did this: answer 200 with nothing written. */
        REPLAY,
        /** 409 escalation-changed: the version moved on; the score or risk is what the doctor must look at. */
        CHANGED_SCORE,
        /** 409 escalation-changed: another doctor acknowledged it in the meantime. */
        CHANGED_ACKNOWLEDGED,
        /** 409 escalation-state: it is Acknowledged already (by a doctor whose name is shown). */
        ALREADY_ACKNOWLEDGED,
        /** 409 escalation-state: it is closed already. */
        ALREADY_CLOSED,
        /**
         * Call senior only (ADR-007 R4): another user's call is already recorded. Answer 200 replayed; write one
         * ALREADY_CALLED entry and its event for the caller, no audit, count and version unchanged.
         */
        SEEN_OTHER_CALL,
        /** Call senior only (ADR-007 R4): the count is at the smallint cap, 409 escalation-state, nothing written. */
        CALL_LIMIT;

        public boolean isWrite() {
            return this == WRITE;
        }

        public boolean isReplay() {
            return this == REPLAY;
        }
    }

    /** The most senior calls one escalation can record (the smallint column). */
    public static final int MAX_SENIOR_CALLS = 32_767;
    /** One accepted senior-call entry per user per escalation per this many seconds (ADR-007 R4). */
    public static final int CALL_THROTTLE_SECONDS = 60;

    /**
     * What the caller has already recorded on this escalation (ADR-007 R4). Read lazily, only when the stored count is
     * not lower than the sent one, so a plain write costs one throttle read.
     */
    public interface CallHistory {

        /** The caller has an accepted CALLED entry on this escalation whose number is above {@code sentCalls}. */
        boolean ownCallAbove(int sentCalls);

        /** The caller has any entry on this escalation from the last {@link #CALL_THROTTLE_SECONDS} seconds. */
        boolean enteredRecently();
    }

    /** What a new vitals reading does to the open escalation. */
    public enum VitalsEffect {
        NONE, UPDATED, REOPENED
    }

    /**
     * The fields of the escalation the rules read. The plan text is clinical: {@code toString} leaves it out.
     */
    public record Snapshot(Status status, long version, int score, Risk risk, int seniorCalls, UUID acknowledgedBy,
                           UUID closedBy, String responseAction, String responseNote) {

        @Override
        public String toString() {
            return "Snapshot[status=" + status + ", version=" + version + "]";
        }
    }

    // ---- transitions ----

    /** True when an Acknowledged escalation of risk {@code current} goes back to OPEN for a reading of {@code incoming}. */
    public static boolean canReopen(Status status, Risk current, Risk incoming) {
        return status == Status.ACK && current == Risk.MEDIUM && incoming == Risk.HIGH;
    }

    /**
     * What a vitals reading does to the open escalation (AC12). A Low reading and a score that is equal or lower
     * change nothing. An Acknowledged MEDIUM escalation that meets a HIGH reading is re-opened; every other higher
     * score updates the score (and the risk) and keeps the status.
     */
    public static VitalsEffect onVitals(Status status, int score, Risk risk, int newScore, Risk newRisk) {
        if (status == Status.DONE || newRisk == Risk.LOW || newScore <= score) {
            return VitalsEffect.NONE;
        }
        return canReopen(status, risk, newRisk) ? VitalsEffect.REOPENED : VitalsEffect.UPDATED;
    }

    // ---- the three actions ----

    /**
     * Acknowledge. OPEN with the version the doctor saw: write. OPEN with another version: changed. ACK by the caller
     * with the version one higher than the one sent: replay (lost answer, double tap). ACK otherwise: already
     * acknowledged. DONE: already closed.
     */
    public static Answer acknowledge(Snapshot s, long sentVersion, UUID caller) {
        if (s.status() == Status.DONE) {
            return Answer.ALREADY_CLOSED;
        }
        if (s.status() == Status.ACK) {
            boolean mine = caller != null && caller.equals(s.acknowledgedBy());
            return mine && s.version() == sentVersion + 1 ? Answer.REPLAY : Answer.ALREADY_ACKNOWLEDGED;
        }
        return s.version() == sentVersion ? Answer.WRITE : Answer.CHANGED_SCORE;
    }

    /**
     * Close escalation. OPEN or ACK with the version the doctor saw: write. OPEN or ACK with another version:
     * changed (by another doctor's acknowledgement when it is Acknowledged by someone else, otherwise because the
     * score moved). DONE, closed by the caller at the version one higher than the one sent, with the same action and
     * the same stored plan: replay. DONE otherwise: already closed.
     */
    public static Answer respond(Snapshot s, long sentVersion, UUID caller, ResponseAction action, String note) {
        if (s.status() == Status.DONE) {
            boolean mine = caller != null && caller.equals(s.closedBy());
            boolean same = action != null && action.name().equals(s.responseAction()) && note != null
                    && note.equals(s.responseNote());
            return mine && same && s.version() == sentVersion + 1 ? Answer.REPLAY : Answer.ALREADY_CLOSED;
        }
        if (s.version() == sentVersion) {
            return Answer.WRITE;
        }
        boolean acknowledgedByOther = s.status() == Status.ACK && !(caller != null && caller.equals(s.acknowledgedBy()));
        return acknowledgedByOther ? Answer.CHANGED_ACKNOWLEDGED : Answer.CHANGED_SCORE;
    }

    /**
     * Call senior (ADR-007 R4). DONE: already closed. ACK: already acknowledged. OPEN with another version: changed.
     * OPEN at the same version, by the stored count against the count the nurse saw: lower is changed (not possible
     * from a screen of this server); higher is a replay when the caller's own call is above the sent count, or when
     * she entered something in the last 60 seconds (throttle), otherwise "seen another team member's call"; equal
     * is a replay inside the throttle, the cap answer at 32,767, and otherwise a write.
     */
    public static Answer callSenior(Snapshot s, long sentVersion, int sentSeniorCalls, CallHistory history) {
        if (s.status() == Status.DONE) {
            return Answer.ALREADY_CLOSED;
        }
        if (s.status() == Status.ACK) {
            return Answer.ALREADY_ACKNOWLEDGED;
        }
        if (s.version() != sentVersion || s.seniorCalls() < sentSeniorCalls) {
            return Answer.CHANGED_SCORE;
        }
        if (s.seniorCalls() > sentSeniorCalls) {
            if (history.ownCallAbove(sentSeniorCalls) || history.enteredRecently()) {
                return Answer.REPLAY;
            }
            return Answer.SEEN_OTHER_CALL;
        }
        if (history.enteredRecently()) {
            return Answer.REPLAY;
        }
        return s.seniorCalls() >= MAX_SENIOR_CALLS ? Answer.CALL_LIMIT : Answer.WRITE;
    }
}
