package com.sahaj.billing.application;

import java.util.Optional;
import java.util.UUID;

/** Port over {@code billing.bill} (SAH-10). Needs {@code app.tenant_id} bound. */
public interface BillRepository {

    /** Another clinic's visit id, or a visit with no bill written yet, is simply absent. */
    Optional<BillRow> findByVisit(UUID tenantId, UUID visitId);

    /** Creates the bill row the first time a visit is written to, otherwise a no-op; returns its id either way. */
    UUID getOrCreate(UUID tenantId, UUID visitId, UUID actorUserId);

    void updateDiscount(UUID tenantId, UUID id, int percent, String reason, UUID actorUserId);

    record BillRow(UUID id, UUID visitId, int discountPercent, String discountReason, long version) {
    }
}
