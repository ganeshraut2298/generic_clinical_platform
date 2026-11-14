package com.sahaj.opd.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * SAH-67-1 (ADR-025 D8): marks the day's IPD round done when a doctor signs the progress note. Implemented by {@code ipd}
 * (one {@code round_note} per admission and day); runs in the caller's transaction.
 */
public interface EmrRoundPort {

    /**
     * {@code investigationsAdvised} is the active investigation texts joined with "; " (at most 1,000): the only clinical
     * text ipd receives, shown as a line on the nursing sheet. No lab order is made.
     */
    record RoundNote(UUID admissionId, LocalDate roundDate, UUID recordId, Instant signedAt, int ordersPlaced,
                     String investigationsAdvised) {

        @Override
        public String toString() {
            return "RoundNote[admissionId=" + admissionId + ", roundDate=" + roundDate + "]";
        }
    }

    enum RoundOutcome { RECORDED, ALREADY_RECORDED, NOT_ADMITTED }

    RoundOutcome recordSignedRound(UUID tenantId, UUID actorUserId, RoundNote note);
}
