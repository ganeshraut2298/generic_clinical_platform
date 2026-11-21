package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.store_return} (SAH-79-14, ADR-029 P12): a return of one batch from a store to the Central Store. The
 * table is insert only; the return item that follows the units is {@code return_item}. Notes are never logged here.
 */
public interface StoreReturnRepository {

    record Row(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, UUID productId, UUID batchId, int quantity,
               String reasonKey, String note, UUID secondSignerMembershipId, Instant createdAt, UUID createdBy) {
    }

    record NewRow(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, UUID productId, UUID batchId, int quantity,
                  String reasonKey, String note, UUID secondSignerMembershipId, UUID createdBy) {
    }

    Optional<Row> findById(UUID tenantId, UUID id);

    void insert(UUID tenantId, NewRow row);
}
