package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.shortage} (SAH-79-12 and 79-13, ADR-029 P12): units a receiving store did not get. They stay
 * IN_TRANSIT at the receiving store until the settling store settles them. A shortage is never a physical return.
 */
public interface ShortageRepository {

    /** {@code dispatchReference} is the reference of the dispatch of the line ("IND-6012 / 1"). */
    record Row(UUID id, UUID unitId, UUID dispatchLineId, String dispatchReference, UUID receivingStoreId, UUID settlingStoreId,
               UUID productId, UUID batchId, int qty, String status, UUID approvalRequestId, String resultNote,
               UUID secondSignerMembershipId, UUID settledBy, Instant settledAt, UUID raisedBy, Instant createdAt,
               long version) {
    }

    record NewShortage(UUID id, UUID unitId, UUID dispatchLineId, UUID receivingStoreId, UUID settlingStoreId, UUID productId,
                       UUID batchId, int qty, UUID raisedBy) {
    }

    record Slice(List<Row> items, long total) {
    }

    Optional<Row> findById(UUID tenantId, UUID id);

    /** The shortage locked {@code FOR UPDATE} (lock order step 3b). */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    void insert(UUID tenantId, NewShortage shortage);

    /**
     * Moves a locked shortage to {@code status}. FOUND and WRITTEN_OFF set {@code settledBy} (and the time); other statuses leave
     * it null. {@code approvalRequestId} null keeps the request already attached. False when the version is not current.
     */
    boolean move(UUID tenantId, UUID id, long version, String status, String resultNote, UUID secondSigner, UUID settledBy,
                 UUID approvalRequestId, UUID actor);

    /** The shortages of dispatches sent from {@code settlingStoreId}; status null lists open ones first, oldest first. */
    Slice page(UUID tenantId, UUID settlingStoreId, String status, int limit, int offset);

    /** Units of this indent's dispatch lines that are short and not yet settled (OPEN, CLAIM_OPEN, WAITING_WRITE_OFF). */
    int unsettledQtyForIndent(UUID tenantId, UUID indentId);
}
