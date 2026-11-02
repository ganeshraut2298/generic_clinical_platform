package com.sahaj.billing.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port over {@code billing.payment} (SAH-10), append-only. Needs {@code app.tenant_id} bound. */
public interface PaymentRepository {

    /** Every payment and refund on a bill, oldest first. */
    List<PaymentRow> findByBill(UUID tenantId, UUID billId);

    /** {@code amountPaise} is negative for a refund. */
    void insert(UUID id, UUID billId, String mode, long amountPaise, String reference, UUID actorUserId);

    /** Net paise (payments minus refunds) for this mode, recorded at or after {@code since}, for the whole tenant. */
    long sumByModeSince(UUID tenantId, String mode, Instant since);

    /** Whether this bill has any payment or refund recorded at or after {@code since} ("paid today", SAH-10). */
    boolean existsSince(UUID tenantId, UUID billId, Instant since);

    record PaymentRow(UUID id, String mode, long amountPaise, String reference, UUID recordedBy, Instant recordedAt) {
    }
}
