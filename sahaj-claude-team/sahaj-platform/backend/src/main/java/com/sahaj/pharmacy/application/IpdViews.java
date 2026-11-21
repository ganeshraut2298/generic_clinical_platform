package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response records of the IPD dispensing operations (docs/api/pharmacy-sales.yaml WP-M), field names as the contract. In phase 1 the
 * ports behind them are the no-op ones, so every list is empty and every {@code connected} is false (ADR-030 S12). A name appears
 * only where the contract says so and is never logged (rule 29).
 */
public final class IpdViews {

    private IpdViews() {
    }

    public record QueueCounts(int stat, int urgent, int routine, Integer discharge) {
    }

    public record QueueRow(String lane, UUID admissionId, String wardName, String bedLabel, String patientName,
                           Integer patientCount, int itemCount, String line, int waitingMinutes) {
    }

    public record IpdQueuePage(List<QueueRow> items, long total, int page, int size, boolean connected,
                               int refreshAfterSeconds, QueueCounts counts) {
    }

    public record IssueRow(UUID id, String slipNo, String admissionNo, String wardName, String bedLabel, int lineCount,
                           Instant sentAt, String chargeStatus) {
    }

    public record IpdIssuePage(List<IssueRow> items, long total, int page, int size) {
    }

    public record ReturnableLine(UUID issueLineId, String slipNo, StockViews.ProductRef product, StockViews.BatchRef batch,
                                 int issuedQty, int returnedQty, int returnableQty) {
    }

    public record ReturnableAdmission(UUID admissionId, String admissionNo, String patientName, List<ReturnableLine> lines) {
    }

    public record WardReturnable(List<ReturnableAdmission> items) {
    }

    public record ReturnsWaitingRow(String kind, UUID id, String from, String who, String reference, StockViews.ProductRef product,
                                    StockViews.BatchRef batch, int qty, StockViews.ReasonRef reason, String condition,
                                    Long creditPaise, Long refundPaise, List<String> actions) {
    }

    /** What the sales-return side hands over: the rows of one page and the number of returns waiting. */
    public record ReturnsWaitingSlice(List<ReturnsWaitingRow> rows, long total) {
    }

    public record ReturnsWaitingPage(List<ReturnsWaitingRow> items, long total, int page, int size, boolean creditConnected) {
    }

    public record ChargeCounts(int issueLines, int returnLines, int consumption) {
    }

    public record ChargeRow(String sourceKind, UUID sourceLineId, String admissionNo, StockViews.ProductRef product, int qty,
                            long amountPaise, Instant savedAt, String lastFailure) {
    }

    public record ChargesWaitingPage(List<ChargeRow> items, long total, int page, int size, boolean connected,
                                     ChargeCounts counts) {
    }

    public record PostChargesResult(int posted, int stillWaiting, Map<String, Integer> reasons) {
    }

    public record DischargeRow(UUID admissionId, String wardName, String bedLabel, String patientName, Instant dischargeAt,
                               String clearance, PharmacyViews.Person clearedBy, Instant clearedAt, int suppliesToday) {
    }

    public record DischargeList(boolean connected, List<DischargeRow> items) {
    }
}
