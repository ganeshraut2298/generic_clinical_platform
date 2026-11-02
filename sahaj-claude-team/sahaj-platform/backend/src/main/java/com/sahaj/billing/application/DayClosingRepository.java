package com.sahaj.billing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code billing.day_closing} (SAH-10 AC9), one row per tenant per business date. Needs {@code app.tenant_id} bound. */
public interface DayClosingRepository {

    Optional<DayClosingRow> findByDate(UUID tenantId, LocalDate businessDate);

    void insert(UUID id, LocalDate businessDate, long countedCashPaise, long expectedCashPaise, long diffPaise,
                String note, UUID actorUserId);

    record DayClosingRow(UUID id, LocalDate businessDate, long countedCashPaise, long expectedCashPaise,
                         long diffPaise, String note, UUID closedBy, Instant closedAt) {
    }
}
