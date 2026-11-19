package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The answers of the indent operations (docs/api/pharmacy.yaml Indent, IndentPage), SAH-79 WP-F. */
public final class IndentViews {

    private IndentViews() {
    }

    /** {@code scheduleXWaiting} is the number of Schedule X dispatch lines not yet received (0.3.4). */
    public record Chips(int rejectedWaitingCs, int shortInvestigating, boolean backOrderOpen, int scheduleXWaiting) {
    }

    /** The two stores of the indent only (OQ-79-21). A null quantity is a figure the caller may not see (rule 24, WP-H). */
    public record StockImpact(Integer csSaleable, int inTransit, Integer requestingAvailable, Integer pendingReturn, int backOrder,
                              int shortInvestigating) {
    }

    public record Replacement(StockViews.ProductRef product, StockViews.ReasonRef reason) {
    }

    public record Line(UUID id, StockViews.ProductRef product, int requestedQty, Integer approvedQty, Integer hodChange,
                       StockViews.ReasonRef hodRemark, int remainingQty, int dispatchedQty, int receivedQty, int rejectedQty,
                       int shortQty, String balanceAction, StockViews.ReasonRef closeReason, Replacement replacement,
                       Integer requestingStoreAvailable, Integer csStock, StockImpact stockImpact) {
    }

    public record DispatchRef(UUID dispatchId, String reference, String status, Instant dispatchedAt) {
    }

    public record Indent(UUID id, String number, StockViews.StoreRef fromStore, StockViews.StoreRef toStore, String priority,
                         String status, Chips chips, PharmacyViews.Person raisedBy, Instant raisedAt, String actingAs,
                         List<Line> lines, StockViews.ApprovalSummary approval, List<DispatchRef> dispatches,
                         StockViews.ReasonRef cancelReason, List<StockViews.HistoryEvent> history, long version) {
    }

    public record ListItem(UUID id, String number, StockViews.StoreRef fromStore, StockViews.StoreRef toStore, int itemCount,
                           String priority, String status, PharmacyViews.Person raisedBy, Instant raisedAt,
                           Integer ageMinutes) {
    }

    public record Page(List<ListItem> items, long total, int page, int size) {
    }
}
