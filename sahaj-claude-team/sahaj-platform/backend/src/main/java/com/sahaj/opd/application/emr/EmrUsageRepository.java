package com.sahaj.opd.application.emr;

import java.time.Instant;
import java.util.UUID;

/** Port over {@code opd.emr_item_usage}: per clinic, doctor, master and item, a count and a last-used time (ADR-019 D10). */
public interface EmrUsageRepository {

    /**
     * Counts one use with one statement: inserts the row with a count of 1, or adds 1 to the existing count and moves
     * the last-used time forward (never back). The row lock of the upsert means two visits signed at the same moment
     * both count. Returns 1 when counted and 0 when the item is not in its master (ignored). Needs the clinic bound.
     */
    int recordUse(UUID tenantId, UUID doctorMembershipId, EmrMaster master, String itemKey, Instant usedAt);
}
