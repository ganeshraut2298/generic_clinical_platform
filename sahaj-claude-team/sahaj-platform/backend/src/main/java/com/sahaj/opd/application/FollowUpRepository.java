package com.sahaj.opd.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.follow_up} (SAH-14). Needs {@code app.tenant_id} bound. */
public interface FollowUpRepository {

    /** The most rows one tab returns; the counts still cover everything. */
    int MAX_ROWS = 200;

    /** Inserts with {@code on conflict (source_visit_id) do nothing}; false when the visit already had one. */
    boolean insertIfAbsent(UUID id, UUID patientId, UUID doctorMembershipId, UUID sourceVisitId, LocalDate dueDate,
                           String reason, UUID actorUserId);

    /**
     * SAH-27: inserts a follow-up added by an IPD discharge with {@code on conflict (source_admission_id) do nothing};
     * false when the admission already had one.
     */
    boolean insertForAdmissionIfAbsent(UUID id, UUID patientId, UUID doctorMembershipId, UUID sourceAdmissionId,
                                       LocalDate dueDate, String reason, UUID actorUserId);

    /** SAH-27: the follow-up added by this admission's discharge, if any. */
    Optional<UUID> findIdBySourceAdmission(UUID tenantId, UUID sourceAdmissionId);

    /**
     * SAH-27: the latest time a discharge added a follow-up for this patient and doctor (the discharge instant, as the
     * row is written in the discharge transaction); empty when there is none.
     */
    Optional<Instant> lastDischargeAt(UUID tenantId, UUID patientId, UUID doctorMembershipId);

    /**
     * Closes the oldest open follow-up (due date, then created) of this patient and doctor whose source visit
     * is not {@code returnedVisitId} (a follow-up added by a discharge has no source visit and is closed too); returns its id, or empty when there was none.
     */
    Optional<UUID> closeOldestOpen(UUID tenantId, UUID patientId, UUID doctorMembershipId, UUID returnedVisitId,
                                    UUID actorUserId);

    /** Counts for the four tabs, relative to {@code today} (Asia/Kolkata). */
    Counts counts(UUID tenantId, LocalDate today);

    /** Follow-ups whose patient came back at or after {@code since} (the Today dashboard's "returned today"). */
    int countReturnedSince(UUID tenantId, Instant since);

    /**
     * The rows of one tab (DUE, MISSED, UPCOMING, RETURNED), at most {@link #MAX_ROWS}: oldest due date first,
     * except RETURNED which is newest return first.
     */
    List<FollowUpRow> findByStatus(UUID tenantId, String status, LocalDate today);

    record Counts(int due, int missed, int upcoming, int returned) {
    }

    record FollowUpRow(UUID id, UUID patientId, UUID doctorMembershipId, UUID sourceVisitId,
                        UUID sourceAdmissionId, LocalDate dueDate,
                        String reason, UUID returnedVisitId) {

        /** Ids and due date only: never the reason (SAH-27 security review L2). */
        @Override
        public String toString() {
            return "FollowUpRow[id=" + id + ", patientId=" + patientId + ", dueDate=" + dueDate + "]";
        }
    }
}
