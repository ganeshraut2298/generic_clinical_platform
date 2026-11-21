package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.dispatch}, {@code dispatch_line} and {@code dispatch_receipt_line} (SAH-79-11 and 79-12, ADR-029 P12).
 * One record serves an indent and a transfer. Quantities are issue units. The header is inserted before its lines; the receipt
 * lines are inserted before the header takes its receipt status. Lines are never changed or deleted.
 */
public interface DispatchRepository {

    record Row(UUID id, UUID unitId, String sourceType, UUID indentId, UUID transferId, Integer dispatchSeq, String reference,
               UUID fromStoreId, UUID toStoreId, String status, UUID dispatchedBy, Instant dispatchedAt,
               UUID secondSignerMembershipId, UUID receivedBy, Instant receivedAt, UUID receiptSecondSignerMembershipId,
               String rejectedNote, String shortNote, long version) {
    }

    record Line(UUID id, UUID dispatchId, UUID indentLineId, UUID productId, UUID batchId, int dispatchedQty,
                boolean replacedByCs, String changeReasonKey, String changeNote, int backOrderedQty, boolean scheduleX) {
    }

    record Receipt(UUID dispatchLineId, int acceptedQty, int rejectedQty, String rejectReasonKey, String note, UUID receivedBy,
                   Instant receivedAt, UUID secondSignerMembershipId) {
    }

    record NewDispatch(UUID id, UUID unitId, UUID indentId, int seq, String reference, UUID fromStoreId, UUID toStoreId,
                       UUID dispatchedBy, UUID secondSignerMembershipId) {
    }

    record NewLine(UUID id, UUID unitId, UUID dispatchId, UUID indentLineId, UUID productId, UUID batchId, int dispatchedQty,
                   boolean replacedByCs, String changeReasonKey, String changeNote, int backOrderedQty, boolean scheduleX,
                   UUID createdBy) {
    }

    record NewReceipt(UUID unitId, UUID dispatchId, UUID dispatchLineId, int dispatchedQty, int acceptedQty, int rejectedQty,
                      String rejectReasonKey, String note, UUID receivedBy, UUID secondSignerMembershipId) {
    }

    record ListRow(UUID id, String reference, String sourceType, UUID fromStoreId, int itemCount, String status,
                   Instant dispatchedAt) {
    }

    record Slice(List<ListRow> items, long total) {
    }

    /** The header with no lock (read paths). */
    Optional<Row> findById(UUID tenantId, UUID id);

    /** The header locked {@code FOR UPDATE} (lock order step 3: after the indent when both are taken). */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    /** The dispatches of an indent, oldest first. */
    List<Row> byIndent(UUID tenantId, UUID indentId);

    /** The lines of a dispatch in the order they were written. */
    List<Line> lines(UUID tenantId, UUID dispatchId);

    /** The lines of a dispatch that have no receipt line yet. */
    List<Line> unreceivedLines(UUID tenantId, UUID dispatchId);

    List<Receipt> receipts(UUID tenantId, UUID dispatchId);

    /** The next {@code n} of "IND-6012 / n"; the caller holds the lock of the indent, so two dispatchers cannot take one number. */
    int nextSeq(UUID tenantId, UUID indentId);

    void insert(UUID tenantId, NewDispatch dispatch);

    void insertLine(UUID tenantId, NewLine line);

    /** A transfer dispatch: source TRANSFER, no sequence, the reference is the transfer number. */
    record NewTransferDispatch(UUID id, UUID unitId, UUID transferId, String reference, UUID fromStoreId, UUID toStoreId,
                               UUID dispatchedBy, UUID secondSignerMembershipId) {
    }

    record NewTransferLine(UUID id, UUID unitId, UUID dispatchId, UUID transferLineId, UUID productId, UUID batchId,
                           int dispatchedQty, boolean scheduleX, UUID createdBy) {
    }

    /** The one dispatch of a transfer, when it has been dispatched. */
    Optional<Row> byTransfer(UUID tenantId, UUID transferId);

    void insertTransfer(UUID tenantId, NewTransferDispatch dispatch);

    void insertTransferLine(UUID tenantId, NewTransferLine line);

    void insertReceipt(UUID tenantId, NewReceipt receipt);

    /** The receipt columns and the new status of a locked dispatch, when its version is current. */
    boolean markReceived(UUID tenantId, UUID id, long version, String status, UUID receivedBy, UUID secondSignerMembershipId,
                         String rejectedNote, String shortNote);

    /** Dispatches whose receiving store is {@code toStoreId}; {@code received} false lists IN_TRANSIT and PARTLY_RECEIVED. */
    Slice incoming(UUID tenantId, UUID toStoreId, boolean received, int limit, int offset);

    /** ACTIVE products of the unit with the same normalised salt and schedule as the product, other than it. */
    List<UUID> replacementCandidates(UUID tenantId, UUID productId, int limit);

    /** Schedule X dispatch lines of the indent's PARTLY_RECEIVED dispatches that have no receipt line yet. */
    int scheduleXWaiting(UUID tenantId, UUID indentId);

    /** One dispatch line by id (a return item or a shortage points at it). */
    Optional<Line> findLine(UUID tenantId, UUID lineId);
}
