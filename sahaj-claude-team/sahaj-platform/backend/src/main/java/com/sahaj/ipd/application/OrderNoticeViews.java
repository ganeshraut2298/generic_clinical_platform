package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineViews.OrderChangeLine;
import com.sahaj.ipd.application.MedicineViews.OrderDetails;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-76-3 request and response shapes of {@code docs/api/ipd.yaml} (nurse order notices, ADR-027 D5). Patient names
 * and the clinical texts of a notice (the reason, the indication in {@code details}) are shown on screen only: records
 * that carry them hide them from {@code toString}, so they cannot reach a log line by accident. The summary and the
 * counts hold numbers and ids only.
 */
public final class OrderNoticeViews {

    private OrderNoticeViews() {
    }

    /** The badge "n to acknowledge": red when {@code stat} is above 0, else amber; hidden at 0. */
    public record NoticeCount(int count, int stat, int overdue) {

        public static final NoticeCount NONE = new NoticeCount(0, 0, 0);
    }

    /** The STAT line of a one-time order: "give within n min, m min left" or "overdue by m min". */
    public record StatLine(Instant dueBy, int windowMinutes, Integer minutesLeft, Integer minutesOverdue) {
    }

    /** One notice. The reason and the details hold clinical text: hidden from {@code toString}. */
    public record OrderNotice(UUID id, UUID admissionId, UUID orderId, int orderVersion, String kind, String priority,
                              String drugKey, String drugName, OrderDetails details, List<OrderChangeLine> changes,
                              String reason, Instant createdAt, String createdByName, Instant acknowledgedAt,
                              String acknowledgedByName, Integer waitingMinutes, boolean overdue, StatLine stat,
                              boolean canAcknowledge) {

        @Override
        public String toString() {
            return "OrderNotice[id=" + id + ", kind=" + kind + "]";
        }
    }

    public record OrderNoticeList(UUID admissionId, Instant asOf, String state, String status, NoticeCount open,
                                  List<OrderNotice> items, boolean canAcknowledge) {

        @Override
        public String toString() {
            return "OrderNoticeList[admissionId=" + admissionId + "]";
        }
    }

    /** A row of the cross-patient panel. The patient name is on screen only. */
    public record OrderNoticePageRow(OrderNotice notice, String patientName, String bedCode) {

        @Override
        public String toString() {
            return "OrderNoticePageRow[noticeId=" + notice.id() + "]";
        }
    }

    public record OrderNoticePage(Instant asOf, NoticeCount open, List<OrderNoticePageRow> items, String nextCursor) {
    }

    public record SummaryAdmission(UUID admissionId, int count, int stat, int overdue, Instant statDueBy) {
    }

    /** Counts only (no names, drugs or text). */
    public record OrderNoticeSummary(Instant asOf, NoticeCount total, List<SummaryAdmission> byAdmission,
                                     String changeToken) {
    }
}
