package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of SAH-79 WP-E (docs/api/pharmacy.yaml 0.3.1): purchase orders, goods receipts, vendor payables and debit notes.
 * Money is whole paise; a rate or a GST percentage is a decimal string, as the contract's Rate and GstRate.
 */
public final class PurchaseViews {

    private PurchaseViews() {
    }

    public record VendorRef(UUID vendorId, String name, String gstin, boolean blocked, boolean needsReview) {
    }

    public record PrintHeader(String templateDocType, List<String> headerLines, String footerNote, String paperSize,
                              String unitGstin, String drugLicenceNo) {
    }

    // ---- purchase orders (79-6) ----

    public record PoLine(UUID id, StockViews.ProductRef product, int qty, String rate, String gstRate, long amountPaise,
                         long gstPaise, int receivedQty, boolean closedShort) {
    }

    public record PoReceipt(UUID grnId, String number, Instant postedAt) {
    }

    public record PurchaseOrder(UUID id, String number, StockViews.StoreRef store, VendorRef vendor, String status,
                                LocalDate deliveryExpected, int creditDays, LocalDate sentOn, Integer overdueDays,
                                Integer shortLineCount, List<PoLine> lines, long subtotalPaise, long gstPaise, long totalPaise,
                                String ruleNote, StockViews.ApprovalSummary approval, List<PoReceipt> receipts,
                                PharmacyViews.Person raisedBy, Instant createdAt, List<StockViews.HistoryEvent> history,
                                PrintHeader print, long version) {
    }

    public record PoRow(UUID id, String number, String vendorName, long totalPaise, String status, Integer overdueDays,
                        Integer shortLineCount) {
    }

    public record PoTiles(int draft, int waitingApproval, int sent, int partlyReceived, int overdue) {
    }

    public record PoPage(List<PoRow> items, long total, int page, int size, PoTiles tiles) {
    }

    public record PoDrafts(List<PurchaseOrder> items) {
    }

    public record ReorderRow(StockViews.ProductRef product, Integer csStock, Integer allStoresStock, int openPoQty,
                             String openPoNo, int reorderLevel, int suggestedQty, VendorRef vendor, String lastRate) {
    }

    public record ReorderPage(List<ReorderRow> items, long total, int page, int size) {
    }

    // ---- goods receipts (79-7) ----

    /** One line of a receipt as the screen shows it; {@code purchaseOrderLineId} is 0.3.3. */
    public record GrnLine(UUID id, UUID purchaseOrderLineId, StockViews.ProductRef product, String batchNo,
                          LocalDate expiryDate, int qty, int billedQty, int freeQty, String ptr, String mrp,
                          String discountPercent, String gstRate, long taxablePaise, long gstPaise, String landed,
                          String marginPercent, List<String> checks, String ratePercent, String rateBasis, Integer shortQty,
                          String shortAction, boolean claimRateDifference, boolean scheduleX,
                          PharmacyViews.Person secondSigner) {
    }

    public record GrnPoRef(UUID poId, String number) {
    }

    public record ThreeWayRow(UUID lineId, String product, Integer poQty, int billedQty, int receivedQty, String poRate,
                              String billedRate) {
    }

    public record VendorPayable(UUID payableId, VendorRef vendor, String invoiceNo, LocalDate invoiceDate, long amountPaise,
                                long creditedPaise, long openPaise, LocalDate dueDate, String status) {
    }

    public record DebitNoteDraftRef(UUID dnId, int lineCount, long amountPaise) {
    }

    /** {@code needsApproval} is 0.3.3: the receipt, as it stands, waits for or would need a rate-rise approval. */
    public record GoodsReceipt(UUID id, String number, String status, String outcome, String step, GrnPoRef purchaseOrder,
                               VendorRef vendor, String invoiceNo, LocalDate invoiceDate, int creditDays,
                               long invoiceTotalPaise, long computedTotalPaise, long totalDifferencePaise,
                               List<String> warnings, List<GrnLine> lines, List<ThreeWayRow> threeWayMatch,
                               StockViews.ApprovalSummary approval, VendorPayable payable, DebitNoteDraftRef debitNoteDraft,
                               List<StockViews.HistoryEvent> history, boolean needsApproval, long version) {
    }

    public record GrnRow(UUID id, String number, String vendorName, String invoiceNo, long invoiceTotalPaise, String status,
                         Instant createdAt) {
    }

    public record GrnPage(List<GrnRow> items, long total, int page, int size) {
    }

    // ---- vendor return and debit notes (79-8) ----

    public record ReturnPolicy(Integer returnDaysBeforeExpiry, String creditPercent) {
    }

    /** {@code defaultCreditPercent} is the starting credit as the server works it (0.3.3). */
    public record EligibleRow(String source, StockViews.ProductRef product, StockViews.BatchRef batch, String sourceState,
                              UUID goodsReceiptLineId, String receiptNo, Integer availableQty, String rate,
                              String defaultCreditPercent, Integer daysToExpiry) {
    }

    public record VendorReturnEligible(VendorRef vendor, ReturnPolicy policy, List<EligibleRow> items) {
    }

    /** {@code goodsReceiptLineId} is 0.3.3. */
    public record DnLine(UUID id, String type, StockViews.ProductRef product, StockViews.BatchRef batch, String sourceState,
                         UUID goodsReceiptLineId, int qty, String rate, String creditPercent, boolean creditPercentChanged,
                         long creditPaise, String gstRate, long gstReversalPaise, boolean scheduleX) {
    }

    public record DnStep(String step, Instant at, PharmacyViews.Person by) {
    }

    public record CreditNoteView(String creditNoteNo, LocalDate creditNoteDate, long amountPaise, long differencePaise) {
    }

    public record PayableEffect(long appliedPaise, long unappliedCreditPaise) {
    }

    public record PickRow(String product, StockViews.BatchRef batch, int qty, String rack) {
    }

    public record DebitNote(UUID id, String number, StockViews.StoreRef store, VendorRef vendor, String status,
                            UUID preparedFromGrnId, StockViews.ReasonRef reason, List<DnLine> lines, long creditTotalPaise,
                            long gstReversalPaise, StockViews.ApprovalSummary approval, List<DnStep> steps,
                            String representativeName, CreditNoteView creditNote, PayableEffect payableEffect,
                            List<PickRow> pickList, PrintHeader print, List<StockViews.HistoryEvent> history, long version) {
    }

    public record DnRow(UUID id, String number, String vendorName, long creditTotalPaise, String status, Instant createdAt) {
    }

    public record DnPage(List<DnRow> items, long total, int page, int size) {
    }
}
