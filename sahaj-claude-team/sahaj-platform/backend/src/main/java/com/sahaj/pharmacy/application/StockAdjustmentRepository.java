package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.stock_adjustment} (SAH-79-5). Saved WAITING_APPROVAL (or POSTED when no rule applied), then only its
 * status, approval request and posting time change, once, while it waits (the table's trigger refuses everything else). The
 * difference is a generated column and is never written. Row level security scopes every statement to the bound clinic.
 */
public interface StockAdjustmentRepository {

    record Row(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, UUID productId, UUID batchId, int bookQty,
               int physicalQty, int difference, long valuePaise, String reasonKey, String note, UUID correctsLedgerId,
               UUID secondSignerMembershipId, String status, UUID approvalRequestId, Instant postedAt, Instant createdAt,
               UUID createdBy, long version) {
    }

    record Slice(List<Row> items, long total) {
    }

    /** The row to save. {@code status} is WAITING_APPROVAL or POSTED (then {@code posted_at} is set by the database). */
    record NewRow(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, UUID productId, UUID batchId, int bookQty,
                  int physicalQty, long valuePaise, String reasonKey, String note, UUID correctsLedgerId,
                  UUID secondSignerMembershipId, String status, UUID createdBy) {
    }

    Optional<Row> find(UUID tenantId, UUID storeId, UUID adjustmentId);

    /** By id alone (the approval handler works from the request's subject id), locked {@code FOR UPDATE}. */
    Optional<Row> findForUpdate(UUID tenantId, UUID adjustmentId);

    Optional<Row> findById(UUID tenantId, UUID adjustmentId);

    Slice list(UUID tenantId, UUID storeId, String status, int limit, int offset);

    /** True when another adjustment of this batch in this store waits for approval. */
    boolean waitingFor(UUID tenantId, UUID storeId, UUID batchId);

    UUID insert(UUID tenantId, NewRow row);

    /** Points a WAITING_APPROVAL adjustment at its approval request. */
    void attachRequest(UUID tenantId, UUID adjustmentId, UUID requestId, UUID actorMembershipId);

    /** WAITING_APPROVAL to POSTED or REJECTED (REJECTED needs the request attached). False when it is no longer waiting. */
    boolean setStatus(UUID tenantId, UUID adjustmentId, String status, UUID actorMembershipId);
}
