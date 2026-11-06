package com.sahaj.opd.web;

import com.sahaj.opd.application.FollowUpView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code FollowUpList} in {@code docs/api/opd.yaml} (SAH-14). */
public record FollowUpResponse(Counts counts, List<Item> items) {

    public record Counts(int due, int missed, int upcoming, int returned) {
    }

    public record Item(UUID id, UUID patientId, String patientName, String mobile, UUID doctorMembershipId,
                        String doctorName, LocalDate dueDate, String reason, String status, int daysLate,
                        UUID sourceVisitId, UUID sourceAdmissionId, UUID returnedVisitId) {

        /** Ids, due date and status only: never the name, mobile or reason (SAH-27 security review L2). */
        @Override
        public String toString() {
            return "FollowUpResponse.Item[id=" + id + ", patientId=" + patientId + ", dueDate=" + dueDate
                    + ", status=" + status + "]";
        }
    }

    public static FollowUpResponse from(FollowUpView view) {
        FollowUpView.Counts c = view.counts();
        return new FollowUpResponse(new Counts(c.due(), c.missed(), c.upcoming(), c.returned()),
                view.items().stream().map(i -> new Item(i.id(), i.patientId(), i.patientName(), i.mobile(),
                        i.doctorMembershipId(), i.doctorName(), i.dueDate(), i.reason(), i.status(), i.daysLate(),
                        i.sourceVisitId(), i.sourceAdmissionId(), i.returnedVisitId())).toList());
    }
}
