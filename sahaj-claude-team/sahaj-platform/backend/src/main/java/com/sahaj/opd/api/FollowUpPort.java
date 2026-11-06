package com.sahaj.opd.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Follow-ups that other modules add (SAH-27: an IPD discharge), and the OPD follow-up fee rule they show.
 * Implemented by opd; runs in the caller's transaction (tenant already bound). opd keeps sourceAdmissionId
 * as an opaque id and never reads IPD data with it.
 */
public interface FollowUpPort {

    /** OPD fee settings (SAH-7): days = followUpDays (1..30), fee = "free", "half" or "full". Defaults when never saved. */
    record FeeRule(int days, String fee) {
    }

    /** A follow-up added by a discharge. reason: 1..200 characters (Unicode code points), already cut by the caller. */
    record DischargeFollowUp(UUID sourceAdmissionId, UUID patientId, UUID doctorMembershipId, LocalDate dueDate,
                             String reason) {

        /** Ids and the due date only: the reason is clinical text and never reaches a log. */
        @Override
        public String toString() {
            return "DischargeFollowUp[sourceAdmissionId=" + sourceAdmissionId + ", dueDate=" + dueDate + "]";
        }
    }

    FeeRule feeRule(UUID tenantId);

    /**
     * Adds the follow-up and audits FOLLOW_UP_CREATED (patientId, doctorMembershipId, sourceAdmissionId, dueDate;
     * never the reason). Idempotent per sourceAdmissionId: a second call writes nothing and returns the first id.
     * A null field, or a blank or too long reason, throws IllegalArgumentException (a caller bug: 500, rolls back).
     *
     * @return the follow-up id
     */
    UUID createForDischarge(UUID tenantId, UUID actorUserId, DischargeFollowUp followUp);
}
