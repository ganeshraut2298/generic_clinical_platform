package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.held_bill} (SAH-80-5, BR-11): a cart kept as ids, without a number, stock or payment. A row that is
 * not OPEN is final (the table guard). The count of OPEN rows of a store is taken under the store row lock.
 */
public interface HeldBillRepository {

    record Row(UUID id, UUID unitId, UUID storeId, UUID holder, String cartJson, String status, UUID discountRequestId,
               long heldNetPaise, Instant createdAt, long version) {
    }

    record NewHeld(UUID id, UUID unitId, UUID storeId, UUID holder, String cartJson, UUID discountRequestId, long heldNetPaise) {
    }

    Optional<Row> find(UUID tenantId, UUID id);

    /** Locked {@code FOR UPDATE}: a document row (lock order step 3). */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    /** The OPEN held bills of a store, oldest first, at most {@code limit}. */
    List<Row> open(UUID tenantId, UUID storeId, int limit);

    int countOpen(UUID tenantId, UUID storeId);

    void insert(UUID tenantId, NewHeld held);

    /** Moves an OPEN row to RESUMED, REPLACED or DISCARDED; false when the version is not the stored one. */
    boolean close(UUID tenantId, UUID id, long version, String status, UUID actorMembershipId);
}
