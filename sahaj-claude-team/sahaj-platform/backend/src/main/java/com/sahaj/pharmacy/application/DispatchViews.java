package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The answers of the dispatch, receipt, return item and shortage operations (docs/api/pharmacy.yaml Dispatch, DispatchPage,
 * ReplacementOption, ReturnItem, WaitingForActionPage, Shortage, ShortagePage), SAH-79 WP-F. Plain records, never entities.
 */
public final class DispatchViews {

    private DispatchViews() {
    }

    /**
     * One dispatch line (a batch of one product). {@code dispatched} is the held quantity: dispatched and not yet received (in
     * transit); {@code backOrdered} is the approved quantity still to come after this dispatch, kept open by BACK_ORDER (0.3.5).
     * {@code accepted}, {@code rejected} and {@code rejectReason} are null until the line is received.
     */
    public record Line(UUID id, StockViews.ProductRef product, StockViews.BatchRef batch, int dispatched, boolean replacedByCs,
                       StockViews.ReasonRef changeReason, int backOrdered, boolean scheduleX, Integer accepted, Integer rejected,
                       StockViews.ReasonRef rejectReason, boolean received) {
    }

    /** The latest receipt of the dispatch. */
    public record Receipt(PharmacyViews.Person receivedBy, Instant receivedAt, PharmacyViews.Person secondSigner) {
    }

    public record Notes(String rejectedNote, String shortNote) {
    }

    public record Dispatch(UUID id, String reference, String source, UUID sourceId, String sourceNo, StockViews.StoreRef fromStore,
                           StockViews.StoreRef toStore, String status, PharmacyViews.Person dispatchedBy, Instant dispatchedAt,
                           String actingAs, List<Line> lines, Receipt receipt, Notes notes, PurchaseViews.PrintHeader print,
                           long version) {
    }

    public record DispatchItem(UUID id, String reference, String source, StockViews.StoreRef fromStore, int itemCount,
                               String status, Instant dispatchedAt) {
    }

    public record DispatchPage(List<DispatchItem> items, long total, int page, int size) {
    }

    /** {@code csAvailable} is the Central Store AVAILABLE unexpired quantity. */
    public record ReplacementOption(StockViews.ProductRef product, Integer csAvailable) {
    }

    public record ReplacementOptions(List<ReplacementOption> items) {
    }

    // ---- return items (79-13) ----

    public record Dispute(StockViews.ReasonRef reason, PharmacyViews.Person by, Instant at) {
    }

    public record ReturnNote(String note, PharmacyViews.Person by, Instant at) {
    }

    public record ReturnItem(UUID id, String docNo, String type, StockViews.StoreRef holdingStore,
                             StockViews.StoreRef acceptingStore, StockViews.ProductRef product, StockViews.BatchRef batch,
                             int qty, StockViews.ReasonRef reason, PharmacyViews.Person raisedBy, Instant raisedAt,
                             String status, String defaultPutBackInto, boolean expired, boolean scheduleX, Dispute dispute,
                             List<ReturnNote> notes, String resultNote, List<StockViews.HistoryEvent> history, long version) {
    }

    public record ReturnItemPage(List<ReturnItem> items, long total, int page, int size) {
    }

    // ---- shortages (79-13) ----

    public record Shortage(UUID id, String dispatchReference, StockViews.StoreRef receivingStore,
                           StockViews.StoreRef settlingStore, StockViews.ProductRef product, StockViews.BatchRef batch, int qty,
                           String status, StockViews.ApprovalSummary approval, String banner, String resultNote,
                           List<StockViews.HistoryEvent> history, long version) {
    }

    public record ShortagePage(List<Shortage> items, long total, int page, int size) {
    }
}
