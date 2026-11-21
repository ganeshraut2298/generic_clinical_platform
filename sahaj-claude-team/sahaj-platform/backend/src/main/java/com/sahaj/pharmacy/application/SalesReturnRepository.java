package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.sales_return} and {@code sales_return_line} (SAH-80-18; ADR-030 S5). The statuses move only along the
 * table guard (WAITING_PHARMACIST, WAITING_APPROVAL, APPROVED, COMPLETED, REJECTED); a line is frozen once its return is final, so the
 * service updates the lines before it completes the return. {@code refunds} is the chosen refund as a JSON array of
 * {@code {mode, amountPaise}} until the return completes.
 */
public interface SalesReturnRepository {

    record Row(UUID id, UUID unitId, UUID storeId, UUID billId, String status, String reasonKey, String note, UUID startedBy,
               Instant startedAt, UUID acceptedBy, Instant acceptedAt, UUID completedBy, Instant completedAt,
               UUID approvalRequestId, long refundTotalPaise, String refundsJson, String creditNoteNo, long version) {
    }

    record LineRow(UUID id, UUID billLineId, UUID productId, UUID batchId, int qty, String conditionKey, String decision,
                   String destination, long refundPaise, long taxablePaise, long taxPaise, UUID secondSigner) {
    }

    record Slice(List<Row> items, long total) {
    }

    Optional<Row> find(UUID tenantId, UUID id);

    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    List<LineRow> lines(UUID tenantId, UUID returnId);

    /** Newest first; {@code startedBy} null for every return of the store, else only those started by that membership. */
    Slice page(UUID tenantId, UUID storeId, String status, UUID startedBy, int limit, int offset);

    void insert(UUID tenantId, Row row, UUID actor);

    void insertLine(UUID tenantId, UUID unitId, UUID returnId, LineRow line, UUID actor);

    /** The quantity on returns that are still open (waiting or approved), per bill line of the bill. */
    Map<UUID, Integer> openQtyByBillLine(UUID tenantId, UUID billId);

    /** WAITING_PHARMACIST to WAITING_APPROVAL (or WAITING_APPROVAL unchanged): who accepted, the request and the refund plan. */
    boolean markAccepted(UUID tenantId, UUID id, long version, String newStatus, UUID acceptedBy, UUID approvalRequestId,
                         long refundTotalPaise, String refundsJson, UUID actor);

    /** WAITING_APPROVAL to APPROVED or REJECTED. */
    boolean markDecided(UUID tenantId, UUID id, long version, String newStatus, UUID actor);

    /** To COMPLETED with the credit note number; lines must already be updated. */
    boolean markCompleted(UUID tenantId, UUID id, long version, UUID completedBy, String creditNoteNo, long refundTotalPaise,
                          String refundsJson, UUID acceptedBy, Instant acceptedAt);

    void updateLine(UUID tenantId, UUID lineId, String decision, String destination, long refundPaise, long taxablePaise,
                    long taxPaise, UUID secondSigner);

    /** A saved CREDIT_NOTE source row of this return, if any (the print data of a completed return). */
    Optional<Instant> creditNoteIssuedAt(UUID tenantId, String creditNoteNo);
}
