package com.sahaj.opd.application;

import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.booking_rules}, one row per clinic (SAH-11). Needs {@code app.tenant_id} bound. */
public interface BookingRulesRepository {

    Optional<BookingRulesRow> find(UUID tenantId);

    void upsert(UUID tenantId, BookingRulesRow row, UUID actorUserId);

    record BookingRulesRow(int closeBeforeHours, int remindBeforeHours, int noShowAfterMinutes,
                            boolean followPriority, boolean selfBook, boolean overbook) {
    }
}
