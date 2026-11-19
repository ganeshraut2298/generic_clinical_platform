package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.debit_note}, {@code debit_note_line} and {@code debit_note_credit} (SAH-79-8, ADR-029 P10, design
 * note section 13.3). Row level security scopes every statement to the bound clinic. No delete: a line taken off a draft is
 * flagged {@code removed}; every read of lines filters {@code not removed}; the lines are written before the header leaves
 * DRAFT. The actor of every writer is a membership id (security L13). The vendor representative's name, quantities and amounts
 * are never logged here.
 */
public interface DebitNoteRepository {

    record Row(UUID id, UUID unitId, UUID storeId, UUID vendorId, Long docSeq, String docNo, String status,
               UUID preparedFromGrnId, String reasonKey, String note, long creditTotalPaise, long gstReversalPaise,
               UUID approvalRequestId, Instant pickedAt, UUID pickedBy, String representativeName, Instant handedOverAt,
               UUID handedOverBy, UUID secondSignerMembershipId, Instant createdAt, UUID createdBy, long version) {
    }

    record Line(UUID id, String lineType, UUID productId, int sort, UUID batchId, String sourceState,
                UUID goodsReceiptLineId, int qty, BigDecimal rate, BigDecimal creditPercent, boolean creditPercentChanged,
                long creditPaise, BigDecimal gstRate, long gstReversalPaise) {

        public boolean stockReturn() {
            return "STOCK_RETURN".equals(lineType);
        }
    }

    record NewLine(String lineType, UUID productId, int sort, UUID batchId, String sourceState, UUID goodsReceiptLineId, int qty,
                   BigDecimal rate, BigDecimal creditPercent, boolean creditPercentChanged, long creditPaise,
                   BigDecimal gstRate, long gstReversalPaise) {
    }

    record Credit(String creditNoteNo, LocalDate creditNoteDate, long amountPaise, long differencePaise, long appliedPaise,
                  long unappliedCreditPaise, Instant createdAt, UUID createdBy) {
    }

    record ListRow(UUID id, String docNo, String vendorName, long creditTotalPaise, String status, Instant createdAt) {
    }

    record Slice(List<ListRow> items, long total) {
    }

    /**
     * A balance row of the vendor's batches at the Central Store that may be returned: QUARANTINE, or AVAILABLE with expiry
     * inside the vendor's return days. {@code reservedQty} is what other notes waiting for approval already name.
     * {@code causeTxn} and {@code causeReason} are the ledger line that last moved units into Quarantine.
     */
    record StockCandidate(UUID productId, UUID batchId, String state, int qty, int reservedQty, String batchNo,
                          LocalDate expiryDate, BigDecimal mrp, BigDecimal purchaseRate, String causeTxn, String causeReason) {

        public int availableQty() {
            return Math.max(0, qty - reservedQty);
        }
    }

    Optional<Row> find(UUID tenantId, UUID storeId, UUID id);

    /** The header locked {@code FOR UPDATE} (lock order step 3). Read the status only after this. */
    Optional<Row> findForUpdate(UUID tenantId, UUID storeId, UUID id);

    /** The header by id alone, not locked (an approval summary on a read). */
    Optional<Row> findById(UUID tenantId, UUID id);

    Optional<Row> findForUpdateById(UUID tenantId, UUID id);

    /** The newest note prepared from this receipt that is not discarded. */
    Optional<Row> findPreparedFromReceipt(UUID tenantId, UUID goodsReceiptId);

    List<Line> lines(UUID tenantId, UUID debitNoteId);

    void insert(UUID tenantId, UUID unitId, UUID id, UUID storeId, UUID vendorId, UUID preparedFromGrnId, String reasonKey,
                String note, UUID createdBy);

    void insertLine(UUID tenantId, UUID unitId, UUID debitNoteId, NewLine line, UUID actor);

    void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor);

    void removeLine(UUID tenantId, UUID lineId, UUID actor);

    /** The draft's reason, note and totals when its version is {@code version}; false when it is not. The vendor never changes. */
    boolean updateDraft(UUID tenantId, UUID id, long version, String reasonKey, String note, long creditTotalPaise,
                        long gstReversalPaise, UUID actor);

    /** A move that changes the status only (discard, credit note received, cancel). False when the version is not current. */
    boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actor);

    /** The status move of an approval decision (no version: the header is locked). */
    boolean setStatusLocked(UUID tenantId, UUID id, String expectedStatus, String status, UUID actor);

    /** The first submit: the status (CREATED or WAITING_APPROVAL) and the DN number. */
    boolean submit(UUID tenantId, UUID id, long version, String status, long docSeq, String docNo, UUID actor);

    boolean attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor);

    boolean markPicked(UUID tenantId, UUID id, long version, UUID actor);

    boolean markHandedOver(UUID tenantId, UUID id, long version, String representativeName, UUID handedOverBy, UUID signer);

    void insertCredit(UUID tenantId, UUID unitId, UUID debitNoteId, String creditNoteNo, LocalDate creditNoteDate,
                      long amountPaise, long differencePaise, long appliedPaise, long unappliedPaise, UUID actor);

    Optional<Credit> credit(UUID tenantId, UUID debitNoteId);

    Slice list(UUID tenantId, UUID storeId, String status, UUID vendorId, int limit, int offset);

    /**
     * The balance rows of the vendor's batches at the store that can be offered: all QUARANTINE rows, and AVAILABLE rows with
     * expiry from {@code today} to {@code today + returnDays} (none when {@code returnDays} is null). Rows of other notes
     * waiting for approval are counted as reserved; the note {@code exceptNoteId} does not count against itself.
     */
    List<StockCandidate> stockCandidates(UUID tenantId, UUID storeId, UUID vendorId, LocalDate today, Integer returnDays,
                                         UUID exceptNoteId);
}
