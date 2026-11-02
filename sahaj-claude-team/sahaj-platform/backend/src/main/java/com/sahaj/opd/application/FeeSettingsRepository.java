package com.sahaj.opd.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over the fee columns of {@code opd.opd_settings}, one row per clinic (SAH-7). Needs {@code app.tenant_id} bound. */
public interface FeeSettingsRepository {

    Optional<FeeRow> find(UUID tenantId);

    /** Inserts the clinic's row or replaces its fee columns; the queue and vitals columns are never touched. */
    void upsert(UUID tenantId, FeeRow row, UUID actorUserId);

    /** {@code discountLimits} maps a role name to a percent from 0 to 100. */
    record FeeRow(String feeTiming, int followUpDays, String followUpFee, Map<String, Integer> discountLimits,
                  boolean upiOnBill) {
    }
}
