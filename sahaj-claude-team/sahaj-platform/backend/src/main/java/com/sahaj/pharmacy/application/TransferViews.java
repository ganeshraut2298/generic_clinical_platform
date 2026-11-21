package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of SAH-79-15 (docs/api/pharmacy.yaml 0.3.6 Transfer, TransferPage, TransferStoreList). */
public final class TransferViews {

    private TransferViews() {
    }

    public record PreviewBatch(StockViews.BatchRef batch, int quantity) {
    }

    /** {@code availableAtSender} is null for a caller blind at the sending store while the product is on its open audit (rule 24). */
    public record Line(UUID id, StockViews.ProductRef product, int quantity, List<PreviewBatch> previewBatches,
                       Integer availableAtSender, long valuePaise) {
    }

    public record DispatchRef(UUID dispatchId, String status) {
    }

    public record Transfer(UUID id, String number, StockViews.StoreRef fromStore, StockViews.StoreRef toStore,
                           StockViews.ReasonRef reason, String status, String step, Integer notReceivedHours, List<Line> lines,
                           long totalValuePaise, StockViews.ApprovalSummary approval, DispatchRef dispatch,
                           PharmacyViews.Person raisedBy, Instant raisedAt, List<StockViews.HistoryEvent> history, long version) {
    }

    public record Item(UUID id, String number, StockViews.StoreRef fromStore, StockViews.StoreRef toStore, int itemCount,
                       String status, long ageMinutes, Integer notReceivedHours) {
    }

    public record Page(List<Item> items, long total, int page, int size) {
    }

    public record StoreList(List<StockViews.StoreRef> items) {
    }
}
