package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port over {@code pharmacy.store_bill_series} and a read of {@code pharmacy.store_bill_counter} (SAH-80-1, ADR-030 S3). The
 * counter is written only by the database function {@code pharmacy.next_store_bill_no()}.
 */
public interface StoreBillSeriesRepository {

    record Row(UUID id, UUID storeId, String prefix, boolean financialYearPart, int sequenceLength,
               boolean restartsEachFinancialYear, long version) {
    }

    List<Row> list(UUID tenantId);

    Optional<Row> find(UUID tenantId, UUID storeId);

    UUID insert(UUID tenantId, UUID unitId, UUID storeId, String prefix, boolean financialYearPart, int sequenceLength,
                boolean restartsEachFinancialYear, UUID actorUserId);

    /** Saves the series when its version is still {@code version}; false when it is not. */
    boolean update(UUID tenantId, UUID id, long version, String prefix, boolean financialYearPart, int sequenceLength,
                   boolean restartsEachFinancialYear, UUID actorUserId);

    /** The stores of which a bill number was ever issued from their own series. */
    Set<UUID> storesWithNumbers(UUID tenantId);

    /** The stores that issued a number at or after {@code since} (the start of the financial year). */
    Set<UUID> storesIssuedSince(UUID tenantId, Instant since);
}
