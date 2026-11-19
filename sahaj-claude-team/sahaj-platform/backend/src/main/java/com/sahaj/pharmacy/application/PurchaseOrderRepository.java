package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.purchase_order} and {@code pharmacy.purchase_order_line} (SAH-79-6, ADR-029 P10; design note
 * section 13.1). Row level security scopes every statement to the bound clinic. No delete: a line taken off a draft is flagged
 * {@code removed}, every read of lines filters {@code not removed}, and the lines are written before the header leaves DRAFT
 * (the line trigger reads the header's status). The actor of every writer is a membership id (security L13).
 */
public interface PurchaseOrderRepository {

    record Row(UUID id, UUID unitId, UUID storeId, UUID vendorId, Long docSeq, String docNo, String status,
               LocalDate deliveryExpected, int creditDays, LocalDate sentOn, UUID approvalRequestId, long subtotalPaise,
               long gstPaise, long totalPaise, Instant createdAt, UUID createdBy, Instant updatedAt, long version) {
    }

    record Line(UUID id, UUID productId, int sort, int qty, BigDecimal rate, BigDecimal gstRate, long amountPaise, long gstPaise,
                int receivedQty, boolean closedShort) {
    }

    record NewLine(UUID productId, int sort, int qty, BigDecimal rate, BigDecimal gstRate, long amountPaise, long gstPaise) {
    }

    /** One list row: the header columns the list shows, and whether goods are still outstanding. */
    record ListRow(UUID id, String docNo, String vendorName, long totalPaise, String status, LocalDate deliveryExpected,
                   int shortLineCount, boolean outstanding) {
    }

    record Slice(List<ListRow> items, long total) {
    }

    /** The counts of the tiles, whatever the filters. */
    record Tiles(int draft, int waitingApproval, int sent, int partlyReceived, int overdue) {
    }

    record Receipt(UUID grnId, String docNo, Instant postedAt) {
    }

    /** A product on the reorder list before the vendor and the last rate are added. */
    record ReorderRow(UUID productId, int csStock, int reorderLevel, int openQtyIssueUnits, int openPurchaseUnits,
                      String openPoNo) {
    }

    Optional<Row> find(UUID tenantId, UUID storeId, UUID id);

    /** The header locked {@code FOR UPDATE}: lock order step 3. Read the status only after this. */
    Optional<Row> findForUpdate(UUID tenantId, UUID storeId, UUID id);

    /** The header by id alone, with no lock (read paths such as an approval summary run in read-only transactions). */
    Optional<Row> findById(UUID tenantId, UUID id);

    /** The header locked by id alone (an approval handler knows only the subject id). */
    Optional<Row> findForUpdateById(UUID tenantId, UUID id);

    /** The live lines in screen order. */
    List<Line> lines(UUID tenantId, UUID purchaseOrderId);

    /** The live lines of the order locked {@code FOR UPDATE} in ascending sort (step 3a, a receipt posting against it). */
    List<Line> linesForUpdate(UUID tenantId, UUID purchaseOrderId);

    void insert(UUID tenantId, UUID unitId, UUID id, UUID storeId, UUID vendorId, LocalDate deliveryExpected, int creditDays,
                UUID createdBy);

    void insertLine(UUID tenantId, UUID unitId, UUID purchaseOrderId, NewLine line, UUID actor);

    void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor);

    /** Flags the line removed (a draft only). */
    void removeLine(UUID tenantId, UUID lineId, UUID actor);

    /** The draft's fields and totals when its version is {@code version}; false when it is not. */
    boolean updateDraft(UUID tenantId, UUID id, long version, UUID vendorId, LocalDate deliveryExpected, int creditDays,
                        long subtotalPaise, long gstPaise, long totalPaise, UUID actor);

    /** Draft to WAITING_APPROVAL or APPROVED with its number (the first submit). False when the version is not current. */
    boolean submit(UUID tenantId, UUID id, long version, String status, long docSeq, String docNo, UUID actor);

    /** A move that changes the status only. False when the version is not current. */
    boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actor);

    /** The status move of an approval decision (no version: the header is locked). */
    boolean setStatusLocked(UUID tenantId, UUID id, String expectedStatus, String status, UUID actor);

    boolean attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor);

    boolean markSent(UUID tenantId, UUID id, long version, LocalDate sentOn, UUID actor);

    /** Closes every open live line short and moves the order to CLOSED_SHORT; returns the lines closed. */
    int closeShort(UUID tenantId, UUID id, long version, UUID actor);

    /** True when the vendor supplies the store (an active vendor_store row). */
    boolean vendorSupplies(UUID tenantId, UUID vendorId, UUID storeId);

    Slice list(UUID tenantId, UUID storeId, List<String> statuses, boolean overdueOnly, LocalDate today, int limit, int offset);

    Tiles tiles(UUID tenantId, UUID storeId, LocalDate today);

    List<Receipt> receipts(UUID tenantId, UUID purchaseOrderId);

    /** The ids of the orders one request created in one transaction (a replay of from-suggestions), by vendor name. */
    List<UUID> createdTogether(UUID tenantId, UUID storeId, UUID createdBy, Instant createdAt);

    /** The lines closed short (the "Received · n short" count). */
    int shortLineCount(UUID tenantId, UUID purchaseOrderId);

    /** Products at or below the reorder level, with the open purchase-order quantity (issue units); a page and the total. */
    List<ReorderRow> reorder(UUID tenantId, UUID storeId, LocalDate today, int limit, int offset);

    long reorderTotal(UUID tenantId, UUID storeId, LocalDate today);

    /** A receipt posting adds the received purchase units to the line (the header is locked; lock order step 3a). */
    void addReceived(UUID tenantId, UUID lineId, int qty, UUID actor);

    /** A receipt line closed short: the line stays closed (SAH-79-7, "Close short"). */
    void closeLineShort(UUID tenantId, UUID lineId, UUID actor);

    /** The AVAILABLE unexpired stock of the product in every store (the owner's all-stores column). */
    int allStoresStock(UUID tenantId, UUID productId, LocalDate today);

    /** The net rate of the last posted receipt line of the vendor and product, per purchase unit; empty when none. */
    Optional<BigDecimal> lastPostedRate(UUID tenantId, UUID vendorId, UUID productId);
}
