package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes of the reports (docs/api/pharmacy.yaml 0.3.6: ReportCatalogue, ReportPage, StockLedgerPage). */
public final class ReportViews {

    private ReportViews() {
    }

    public record CatalogueItem(String key, String label) {
    }

    /** {@code group} is STOCK, MOVEMENT or PURCHASE; {@code label} is the mockup 15 card title. */
    public record CatalogueGroup(String group, String label, List<CatalogueItem> reports) {
    }

    public record Catalogue(String line, List<CatalogueGroup> groups) {
    }

    public record Column(String key, String label, String kind) {
    }

    /** {@code cells} follow the column order; a cell is a string, a whole number or null. */
    public record TotalRow(String label, List<Object> cells) {
    }

    public record ReportPage(String reportKey, String title, List<Column> columns, List<List<Object>> rows,
                             List<TotalRow> totals, long total, int page, int size) {
    }

    public record LedgerItem(UUID ledgerId, Instant at, String transaction, String ref, StockViews.StoreRef store, String state,
                             StockViews.BatchRef batch, Integer qtyIn, Integer qtyOut, Integer balance,
                             PharmacyViews.Person user, PharmacyViews.Person secondSigner, UUID reversesLedgerId) {
    }

    public record StockLedgerPage(List<LedgerItem> items, StockViews.ProductRef product, List<StockViews.StoreRef> stores,
                                  LocalDate from, LocalDate to, long total, int page, int size) {
    }

    /** One finished CSV: the file name and the bytes (UTF-8 with a byte order mark). */
    public record CsvFile(String fileName, byte[] bytes) {
    }
}
