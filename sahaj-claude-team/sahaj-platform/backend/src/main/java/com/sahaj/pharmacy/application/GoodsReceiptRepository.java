package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.goods_receipt} and {@code pharmacy.goods_receipt_line} (SAH-79-7, ADR-029 P10, design note section
 * 13.2). Row level security scopes every statement to the bound clinic. No delete: a line taken off a draft is flagged
 * {@code removed}, every read of lines filters {@code not removed}, and lines are written before the header leaves DRAFT. The
 * actor of every writer is a membership id (security L13). Batch numbers, quantities and amounts are never logged here.
 */
public interface GoodsReceiptRepository {

    record Row(UUID id, UUID unitId, UUID storeId, UUID purchaseOrderId, UUID vendorId, Long docSeq, String docNo,
               String status, String invoiceNo, LocalDate invoiceDate, int creditDays, long invoiceTotalPaise,
               UUID approvalRequestId, Instant postedAt, UUID postedBy, Instant createdAt, UUID createdBy, long version) {
    }

    /** A live line with its stored computations and (after posting) the batch, landed rate and signer. */
    record Line(UUID id, UUID purchaseOrderLineId, UUID productId, int sort, String batchNo, LocalDate expiryDate, int qty,
                int billedQty, int freeQty, BigDecimal ptr, BigDecimal mrp, BigDecimal discountPercent, BigDecimal gstRate,
                long taxablePaise, long gstPaise, BigDecimal netRate, BigDecimal referenceRate, String rateBasis,
                BigDecimal ratePercent, Integer shortQty, String shortAction, boolean claimRateDifference, UUID batchId,
                BigDecimal landedRate, UUID secondSignerMembershipId) {
    }

    /** A line to write: the typed values and the stored computations. */
    record NewLine(UUID purchaseOrderLineId, UUID productId, int sort, String batchNo, LocalDate expiryDate, int qty,
                   int billedQty, int freeQty, BigDecimal ptr, BigDecimal mrp, BigDecimal discountPercent, BigDecimal gstRate,
                   long taxablePaise, long gstPaise, BigDecimal netRate, BigDecimal referenceRate, String rateBasis,
                   BigDecimal ratePercent, Integer shortQty, String shortAction, boolean claimRateDifference) {
    }

    record ListRow(UUID id, String docNo, String vendorName, String invoiceNo, long invoiceTotalPaise, String status,
                   Instant createdAt) {
    }

    record Slice(List<ListRow> items, long total) {
    }

    /** A posted receipt line with short billed quantity (billed above received), for the debit note of short supply. */
    record ShortLine(UUID lineId, UUID goodsReceiptId, String receiptNo, UUID productId, UUID batchId, int shortPurchaseUnits,
                     int claimedIssueUnits, BigDecimal netRate, int conversion) {

        /** Issue units billed but not received and not yet on a debit note. */
        public int unclaimedIssueUnits() {
            return Math.max(0, shortPurchaseUnits * conversion - claimedIssueUnits);
        }
    }

    Optional<Row> find(UUID tenantId, UUID storeId, UUID id);

    /** The header locked {@code FOR UPDATE} (lock order step 3). Read the status only after this. */
    Optional<Row> findForUpdate(UUID tenantId, UUID storeId, UUID id);

    /** The header locked by id alone (an approval handler knows only the subject id). */
    /** The header by id alone, not locked (an approval summary on a read). */
    Optional<Row> findById(UUID tenantId, UUID id);

    Optional<Row> findForUpdateById(UUID tenantId, UUID id);

    /** The live lines in screen order. */
    List<Line> lines(UUID tenantId, UUID goodsReceiptId);

    void insert(UUID tenantId, UUID unitId, UUID id, UUID storeId, UUID purchaseOrderId, UUID vendorId, String invoiceNo,
                LocalDate invoiceDate, int creditDays, long invoiceTotalPaise, UUID createdBy);

    void insertLine(UUID tenantId, UUID unitId, UUID goodsReceiptId, NewLine line, UUID actor);

    void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor);

    /** Flags the line removed (a draft only). */
    void removeLine(UUID tenantId, UUID lineId, UUID actor);

    /** The draft's header fields when its version is {@code version}; false when it is not. */
    boolean updateDraft(UUID tenantId, UUID id, long version, UUID vendorId, String invoiceNo, LocalDate invoiceDate,
                        int creditDays, long invoiceTotalPaise, UUID actor);

    /** A move that changes the status only (discard, reopen). False when the version is not current. */
    boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actor);

    /** The status move of an approval decision (no version: the header is locked). */
    boolean setStatusLocked(UUID tenantId, UUID id, String expectedStatus, String status, UUID actor);

    /** DRAFT to WAITING_APPROVAL with the request linked. False when the version is not current. */
    boolean sendForApproval(UUID tenantId, UUID id, long version, UUID requestId, UUID actor);

    /** DRAFT or APPROVED to POSTED with the number, time and poster in one statement. False when the version is not current. */
    boolean markPosted(UUID tenantId, UUID id, long version, long docSeq, String docNo, UUID postedBy);

    /** The posting data of a line, set once: the batch, the landed rate per issue unit and the Schedule X signer. */
    void setLinePosting(UUID tenantId, UUID lineId, UUID batchId, BigDecimal landedRate, UUID secondSigner, UUID actor);

    /** Another non-discarded receipt of the vendor with this invoice number (case and edge spaces ignored). */
    boolean invoiceTaken(UUID tenantId, UUID vendorId, String invoiceNo, UUID exceptId);

    Slice list(UUID tenantId, UUID storeId, String status, int limit, int offset);

    /**
     * Short billed lines of posted receipts of the vendor, newest first, with the issue units already on a debit note that is not
     * discarded, cancelled or rejected (the note {@code exceptNoteId} does not count against itself).
     */
    List<ShortLine> shortLinesOfVendor(UUID tenantId, UUID vendorId, UUID exceptNoteId);

    /** The posted receipt line as a short line (or empty when it is not a posted line of this store). */
    Optional<ShortLine> shortLine(UUID tenantId, UUID storeId, UUID lineId, UUID exceptNoteId);

    /** The posting number of the receipt a line belongs to. */
    Optional<String> receiptNoOfLine(UUID tenantId, UUID lineId);
}
