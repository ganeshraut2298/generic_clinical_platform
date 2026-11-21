package com.sahaj.pharmacy.application;

import java.util.List;
import java.util.UUID;

/**
 * Response shapes of SAH-79 WP-G (docs/api/pharmacy.yaml 0.3.5): a store return, a damage report, a transfer and a ward
 * consumption entry. Names of staff appear only as {@link PharmacyViews.Person}; a patient is never named.
 */
public final class StoreUseViews {

    private StoreUseViews() {
    }

    /** StoreReturn: one batch sent back to the Central Store, with its return item and history. */
    public record StoreReturn(UUID id, String number, StockViews.StoreRef store, StockViews.ProductRef product,
                              StockViews.BatchRef batch, int quantity, StockViews.ReasonRef reason, DispatchViews.ReturnItem item,
                              List<StockViews.HistoryEvent> history) {
    }

    /** DamageReport: {@code outcome} is a plain sentence once decided, null while waiting. */
    public record DamageReport(UUID id, String number, StockViews.StoreRef store, StockViews.ProductRef product,
                               StockViews.BatchRef batch, int quantity, StockViews.ReasonRef reason, String afterApproval,
                               String status, String outcome, StockViews.ApprovalSummary approval,
                               List<StockViews.HistoryEvent> history, long version) {
    }
}
