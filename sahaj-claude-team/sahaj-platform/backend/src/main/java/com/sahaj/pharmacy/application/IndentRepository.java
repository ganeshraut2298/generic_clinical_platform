package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.indent} and {@code pharmacy.indent_line} (SAH-79-9 and 79-10, ADR-029 P12). Quantities are issue units.
 * Every statement names the tenant as well as relying on row level security. A line taken off a draft is flagged removed; every
 * read of lines is {@code not removed}.
 */
public interface IndentRepository {

    record Row(UUID id, UUID unitId, UUID fromStoreId, UUID toStoreId, Long docSeq, String docNo, String priority, String status,
               UUID approvalRequestId, Instant raisedAt, String cancelReasonKey, String cancelNote, Instant createdAt,
               UUID createdBy, long version) {
    }

    record Line(UUID id, UUID productId, int sort, int requestedQty, Integer approvedQty, String hodRemarkReasonKey,
                int dispatchedQty, int receivedQty, int rejectedQty, int shortQty, int closedQty, String balanceAction,
                String closeReasonKey, UUID replacementProductId, String replacementReasonKey) {
    }

    record NewLine(UUID productId, int sort, int requestedQty) {
    }

    /** One list row: the header columns the list shows and the number of live lines. */
    record ListRow(UUID id, String docNo, UUID fromStoreId, UUID toStoreId, int itemCount, String priority, String status,
                   UUID createdBy, Instant raisedAt) {
    }

    record Slice(List<ListRow> items, long total) {
    }

    /** The header by id with no lock (read paths and approval summaries run in read-only transactions). */
    Optional<Row> findById(UUID tenantId, UUID id);

    /** The header locked {@code FOR UPDATE} by id (lock order step 3). Read the status only after this. */
    Optional<Row> findForUpdateById(UUID tenantId, UUID id);

    /** The live lines in screen order. */
    List<Line> lines(UUID tenantId, UUID indentId);

    /** The live lines locked {@code FOR UPDATE} in ascending id order (lock order step 3: after the header). */
    List<Line> linesForUpdate(UUID tenantId, UUID indentId);

    /**
     * A dispatch of one line: adds to the dispatched quantity and to the closed quantity, and sets the balance action, the close
     * reason and the replacement when given (null keeps what the line has). The caller holds the line lock.
     */
    void applyDispatch(UUID tenantId, UUID lineId, int dispatchedDelta, int closedDelta, String balanceAction,
                       String closeReasonKey, UUID replacementProductId, String replacementReasonKey, UUID actor);

    /** A receipt of one line: adds to the received, rejected (physical) and short quantities. The caller holds the line lock. */
    void applyReceipt(UUID tenantId, UUID lineId, int receivedDelta, int rejectedDelta, int shortDelta, UUID actor);

    void insert(UUID tenantId, UUID unitId, UUID id, UUID fromStoreId, UUID toStoreId, String priority, UUID actor);

    void insertLine(UUID tenantId, UUID unitId, UUID indentId, NewLine line, UUID actor);

    void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor);

    /** Flags the line removed (a draft only). */
    void removeLine(UUID tenantId, UUID lineId, UUID actor);

    /** The draft's priority when its version is {@code version}; false when it is not. */
    boolean updateDraft(UUID tenantId, UUID id, long version, String priority, UUID actor);

    /** Draft to WAITING_APPROVAL or APPROVED with its number and the time it was raised (the first submit). */
    boolean submit(UUID tenantId, UUID id, long version, String status, long docSeq, String docNo, UUID actor);

    /** A status move of a document the service has locked and checked (discard). False when the version is not current. */
    boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actor);

    /** The status move of an approval decision (no version: the header is locked). */
    boolean setStatusLocked(UUID tenantId, UUID id, String expectedStatus, String status, UUID actor);

    boolean attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor);

    /** CANCELLED with its reason, from WAITING_APPROVAL or APPROVED, when the version is current. */
    boolean cancel(UUID tenantId, UUID id, long version, String reasonKey, String note, UUID actor);

    /** The approved quantity of a line and the HOD's remark (null remark clears it). Only before anything is dispatched. */
    void setApproved(UUID tenantId, UUID lineId, int approvedQty, String remarkReasonKey, UUID actor);

    /** Indents whose requesting store is {@code storeId}: not discarded, and a draft only for its raiser. Newest first. */
    Slice listRaised(UUID tenantId, UUID storeId, UUID viewer, String status, int limit, int offset);

    /** Indents to dispatch from {@code storeId}: APPROVED or PARTLY_DISPATCHED, urgent first, then oldest. */
    Slice listToDispatch(UUID tenantId, UUID storeId, String status, int limit, int offset);

    /** The latest purchase rate per issue unit of any batch of the product (BR-17 amount); empty when none is known. */
    Optional<BigDecimal> latestPurchaseRate(UUID tenantId, UUID productId);
}
