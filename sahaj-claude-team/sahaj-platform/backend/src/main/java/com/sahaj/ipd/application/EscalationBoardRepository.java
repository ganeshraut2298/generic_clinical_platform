package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Snapshot;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.ResponseAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.ews_escalation} for the Safety board (SAH-33): the reads of the board, the summary and the banner, and
 * the three actions Acknowledge, Close escalation and Call senior. Every method is scoped to the tenant. Every action
 * statement carries the status and version it expects in its {@code where}, so a row that moved on matches nothing;
 * the caller already holds the admission row lock and has decided the answer with {@code EscalationState}. The
 * database stamps {@code acknowledged_at} and {@code closed_at}; no statement sends them (ADR-007 D1).
 */
public interface EscalationBoardRepository {

    /** The database clock now, {@code clock_timestamp()}: {@code asOf} of every answer (ADR-007 D5). */
    Instant databaseNow();

    /** One escalation by id, or empty when it does not exist or belongs to another clinic (row-level security). */
    Optional<Detail> findById(UUID tenantId, UUID id);

    /**
     * Every open and acknowledged escalation of every ADMITTED admission of the clinic, and every closed one whose
     * {@code closed_at} is within the 24 hours before {@code asOf} (ADR-007 R5), with the admission's patient id and
     * current bed code, in one query. Unordered: the board orders them.
     */
    List<BoardRow> findOnBoard(UUID tenantId, Instant asOf);

    /** The same, restricted to escalations that are not DONE (OPEN or ACK): the counts, the notice and the banner. */
    List<BoardRow> findLiveOnBoard(UUID tenantId);

    /** OPEN to ACK by {@code doctor}, only when the row is OPEN at {@code sentVersion}. False when no row changed. */
    boolean acknowledge(UUID tenantId, UUID id, UUID doctor, long sentVersion);

    /**
     * To DONE with a doctor action and plan, closing user {@code doctor}, only when the row is at
     * {@code sentVersion} and in the status {@code from} (OPEN or ACK). From OPEN the doctor is also stored as the
     * acknowledging doctor. False when no row changed.
     */
    boolean close(UUID tenantId, UUID id, Status from, UUID doctor, ResponseAction action, String note,
                  long sentVersion);

    /**
     * Adds one to the senior calls, only when the row is OPEN at {@code sentVersion} with {@code seenCalls} calls;
     * the version is not changed. False when no row changed.
     */
    boolean callSenior(UUID tenantId, UUID id, long sentVersion, int seenCalls);

    /**
     * An escalation as stored. The plan is clinical text: {@code toString} shows the id and status only.
     */
    record Detail(UUID id, UUID admissionId, long version, int ewsScore, Risk ewsRisk, int targetMinutes,
                  Status status, Instant raisedAt, UUID raisedBy, Instant reopenedAt, UUID acknowledgedBy,
                  Instant acknowledgedAt, String responseAction, String responseNote, int seniorCalls, UUID closedBy,
                  Instant closedAt) {

        /** The fields the state rules read. */
        public Snapshot snapshot() {
            return new Snapshot(status, version, ewsScore, ewsRisk, seniorCalls, acknowledgedBy, closedBy,
                    responseAction, responseNote);
        }

        @Override
        public String toString() {
            return "Detail[id=" + id + ", status=" + status + "]";
        }
    }

    /** An escalation of an admitted patient with the patient id and the bed code the board shows. */
    record BoardRow(Detail escalation, UUID patientId, String bedCode) {

        @Override
        public String toString() {
            return "BoardRow[id=" + escalation.id() + "]";
        }
    }
}
