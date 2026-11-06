package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.ews_escalation}, the part used by the vitals save (SAH-30) and by the discharge (SAH-33): insert a row,
 * raise its score, re-open an Acknowledged one, close one at discharge. The Safety board's reads and the three doctor
 * and nurse actions are in {@link EscalationBoardRepository}. At most one row per admission is not DONE (partial
 * unique index). Rows are never deleted. The caller holds the admission row lock (ADR-006 D4, ADR-007 D3).
 *
 * <p>The database stamps {@code acknowledged_at}, {@code closed_at} and {@code reopened_at}; no statement here sends
 * them (ADR-007 D1).
 */
public interface EscalationRepository {

    /** The escalation of the admission whose status is not DONE (OPEN or ACK). */
    Optional<EscalationRow> findOpen(UUID tenantId, UUID admissionId);

    /** New OPEN escalation raised now by {@code raisedBy}; returns its id. A second open one is a 23505 clash. */
    UUID insert(UUID tenantId, UUID admissionId, int ewsScore, Risk ewsRisk, int targetMinutes, UUID raisedBy);

    /**
     * Replaces score, risk and target together and bumps the version, only when the stored score is lower than
     * {@code ewsScore} and the row is not DONE. False when no row changed.
     */
    boolean raiseScore(UUID tenantId, UUID id, int ewsScore, Risk ewsRisk, int targetMinutes);

    /**
     * SAH-33 AC12: an Acknowledged MEDIUM escalation goes back to OPEN with a HIGH score: status OPEN, the
     * acknowledgement cleared, score {@code ewsScore}, risk HIGH, target 15, version + 1; the database restarts the
     * response clock. False when the row is not ACK with risk MEDIUM.
     */
    boolean reopen(UUID tenantId, UUID id, int ewsScore);

    /**
     * SAH-33 D6: closes the not-DONE escalation as CLOSED_AT_DISCHARGE (no plan, closing user {@code closedBy},
     * version + 1, acknowledgement kept as it was). The admission must already be DISCHARGED in this transaction.
     * False when no row changed.
     */
    boolean closeAtDischarge(UUID tenantId, UUID id, UUID closedBy);

    record EscalationRow(UUID id, UUID admissionId, int ewsScore, Risk ewsRisk, int targetMinutes, String status,
                         Instant raisedAt, Instant reopenedAt) {

        /** The start of the response clock: the re-open time when there is one, otherwise the first raise. */
        public Instant clockStart() {
            return reopenedAt != null ? reopenedAt : raisedAt;
        }
    }
}
