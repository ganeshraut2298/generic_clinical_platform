package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.transfer} and {@code transfer_line} (SAH-79-15, ADR-029 P12). A transfer is requested and submitted in
 * one step, so it is inserted WAITING_APPROVAL or (no approval level) APPROVED. The table lets the status move forward only; the
 * header changes only by status, request and version. Lines are written once, with the header, and never changed.
 */
public interface TransferRepository {

    record Row(UUID id, UUID unitId, long docSeq, String docNo, UUID fromStoreId, UUID toStoreId, String reasonKey, String note,
               String status, UUID approvalRequestId, long totalValuePaise, Instant createdAt, UUID createdBy, long version) {
    }

    record Line(UUID id, UUID transferId, UUID productId, int quantity, long valuePaise) {
    }

    record NewRow(UUID id, UUID unitId, long docSeq, String docNo, UUID fromStoreId, UUID toStoreId, String reasonKey, String note,
                  String status, long totalValuePaise, UUID createdBy) {
    }

    record NewLine(UUID id, UUID unitId, UUID transferId, UUID productId, int quantity, long valuePaise, UUID createdBy) {
    }

    record ListRow(UUID id, String docNo, UUID fromStoreId, UUID toStoreId, int itemCount, String status, Instant createdAt,
                   Instant dispatchedAt) {
    }

    record Slice(List<ListRow> items, long total) {
    }

    Optional<Row> findById(UUID tenantId, UUID id);

    /** By id, locked {@code FOR UPDATE} (lock order: the sending store, then the request, then the transfer, then the stock rows). */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    List<Line> lines(UUID tenantId, UUID transferId);

    void insert(UUID tenantId, NewRow row);

    void insertLine(UUID tenantId, NewLine line);

    /** Points a WAITING_APPROVAL transfer at its approval request. */
    void attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actorMembershipId);

    /** Moves the status when the version is current; the version goes up by one. */
    boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actorMembershipId);

    /** Transfers from or to {@code storeId}, newest first; {@code open} keeps WAITING_APPROVAL, APPROVED and IN_TRANSIT. */
    Slice listOfStore(UUID tenantId, UUID storeId, boolean open, int limit, int offset);
}
