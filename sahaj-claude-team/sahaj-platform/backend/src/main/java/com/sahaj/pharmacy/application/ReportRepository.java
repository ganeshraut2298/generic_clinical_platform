package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.StockState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Port for the SAH-79 reports (docs/api/pharmacy.yaml 0.3.6, SAH-79-18 and 79-19). Read only; row level security scopes every
 * statement to the bound clinic and the caller's reachable stores are always passed in. A row carries the store and product it
 * belongs to (null where it has none) so the service can hide the quantity of an audited product from a blind caller (rule 24).
 * Cells are in the column order of the {@link ReportDef}: text, numbers (paise as whole numbers), dates and instants; a person is
 * a membership id that the service turns into a name.
 */
public interface ReportRepository {

    /** The filters of one run. {@code from} and {@code to} are IST dates (inclusive), null when the report is "now". */
    record Filter(UUID tenantId, List<UUID> storeIds, LocalDate from, LocalDate to, UUID productId, UUID vendorId,
                  String status, LocalDate today) {
    }

    /** One report row. */
    record Row(UUID storeId, UUID productId, List<Object> cells, UUID patientId) {

        public Row(UUID storeId, UUID productId, List<Object> cells) {
            this(storeId, productId, cells, null);
        }
    }

    /** A cell in the person columns: the membership id of a staff member. */
    record PersonCell(UUID membershipId) {
    }

    /** A purchase-register total line. */
    record Total(String label, List<Object> cells) {
    }

    List<Row> rows(ReportDef report, Filter filter, int limit, int offset);

    long count(ReportDef report, Filter filter);

    /** The purchase register's totals, per vendor then per GST percentage; cells are in the register's column order. */
    List<Total> purchaseTotals(Filter filter);

    /** One stock ledger line. Actor ids are membership ids; a null actor is the system. */
    record LedgerLine(UUID ledgerId, Instant at, String transaction, String ref, UUID storeId, String storeName,
                      String storeType, StockState state, UUID batchId, String batchNo, LocalDate expiryDate, int qtyIn,
                      int qtyOut, int balance, UUID actorMembershipId, UUID secondSignerMembershipId, UUID reversesLedgerId) {
    }

    List<LedgerLine> ledger(Filter filter, int limit, int offset);

    long ledgerCount(Filter filter);
}
