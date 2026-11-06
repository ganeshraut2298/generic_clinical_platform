package com.sahaj.hra.application;

/** {@code StaffSummary} in {@code docs/api/hra.yaml} (SAH-20). {@code presentToday} counts present and late; {@code leaveRequests} (SAH-21) is the pending leave count for the owner, 0 for other roles. */
public record StaffSummaryView(int staff, int doctors, int others, int presentToday, int lateToday,
                               int leaveRequests) {
}
