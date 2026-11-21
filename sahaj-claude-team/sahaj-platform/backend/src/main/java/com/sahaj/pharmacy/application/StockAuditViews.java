package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of SAH-79-17 (docs/api/pharmacy.yaml 0.3.6 StockAudit, StockAuditPage, CountSheetLine, CountSheetPage,
 * VarianceReview). {@link CountSheetLine} has no system, book, difference or value field, for any caller: the count is blind
 * (BR-30) and a QA test asserts it.
 */
public final class StockAuditViews {

    private StockAuditViews() {
    }

    /** {@code withVariance} and {@code varianceValuePaise} are null for a caller without reviewCount and before submit. */
    public record StockAudit(UUID id, String number, StockViews.StoreRef store, String status, PharmacyViews.Person startedBy,
                             Instant startedAt, PharmacyViews.Person submittedBy, int itemsInCount, int counted,
                             Integer withVariance, Long varianceValuePaise, StockViews.ApprovalSummary approval,
                             List<StockViews.HistoryEvent> history, long version) {
    }

    public record Page(List<StockAudit> items, long total, int page, int size) {
    }

    public record CountSheetLine(UUID auditLineId, StockViews.ProductRef product, StockViews.BatchRef batch, String rack,
                                 String barcode, Integer counted, boolean notCounted, StockViews.ReasonRef notCountedReason,
                                 boolean recount) {
    }

    public record CountSheet(List<CountSheetLine> items, long total, int page, int size, String auditNo, String storeName,
                             String status, int counted, int itemsInCount, long version) {
    }

    public record VarianceLine(UUID auditLineId, StockViews.ProductRef product, StockViews.BatchRef batch, int system,
                               int counted, int diff, long valuePaise, StockViews.ReasonRef reason,
                               PharmacyViews.Person counter, boolean stockMoved, boolean suggestConsumption,
                               boolean scheduleX) {
    }

    public record VarianceReview(UUID auditId, String auditNo, StockViews.StoreRef store, String status,
                                 long varianceValuePaise, List<VarianceLine> lines, long version) {
    }
}
