package com.sahaj.opd.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A follow-up list (SAH-14): counts for all four tabs and the rows of the requested one. */
public record FollowUpView(Counts counts, List<Item> items) {

    public record Counts(int due, int missed, int upcoming, int returned) {
    }

    /** {@code mobile} is for the screen only; it is never logged or audited. */
    public record Item(UUID id, UUID patientId, String patientName, String mobile, UUID doctorMembershipId,
                        String doctorName, LocalDate dueDate, String reason, String status, int daysLate,
                        UUID sourceVisitId, UUID sourceAdmissionId, UUID returnedVisitId, LocalDate appointmentDate,
                        java.time.LocalTime appointmentTime) {

        /** An item with no booked appointment (the shape before 0.6.0). */
        public Item(UUID id, UUID patientId, String patientName, String mobile, UUID doctorMembershipId,
                    String doctorName, LocalDate dueDate, String reason, String status, int daysLate,
                    UUID sourceVisitId, UUID sourceAdmissionId, UUID returnedVisitId) {
            this(id, patientId, patientName, mobile, doctorMembershipId, doctorName, dueDate, reason, status, daysLate,
                    sourceVisitId, sourceAdmissionId, returnedVisitId, null, null);
        }

        /** Ids, due date and status only: never the name, mobile or reason (SAH-27 security review L2). */
        @Override
        public String toString() {
            return "FollowUpView.Item[id=" + id + ", patientId=" + patientId + ", dueDate=" + dueDate + ", status="
                    + status + "]";
        }
    }
}
