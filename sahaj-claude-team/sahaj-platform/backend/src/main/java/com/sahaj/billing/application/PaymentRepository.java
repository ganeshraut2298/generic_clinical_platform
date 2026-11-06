package com.sahaj.billing.application;

import java.time.Instant;
import java.util.Collection;
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

    /** Net paise (payments minus refunds) for the whole tenant recorded at or after {@code since} (SAH-15). */
    long netSince(UUID tenantId, Instant since);

    /** Whether this bill has any payment or refund recorded at or after {@code since} ("paid today", SAH-10). */
    boolean existsSince(UUID tenantId, UUID billId, Instant since);

    /** Every payment and refund recorded in {@code [from, to)} with the visit it belongs to (SAH-16), oldest first. */
    List<PaymentOfVisit> findBetween(UUID tenantId, Instant from, Instant to);

    /** Net paise and number of rows recorded in {@code [from, to)} (SAH-16 previous-period comparison). */
    PaymentTotals totalsBetween(UUID tenantId, Instant from, Instant to);

    /** Net paid paise per bill, for these bills only; a bill with no payments is absent (SAH-16). */
    List<BillPaid> paidByBills(UUID tenantId, Collection<UUID> billIds);

    record PaymentOfVisit(UUID visitId, String mode, long amountPaise, String reference, UUID recordedBy,
                           Instant recordedAt) {
    }

    record PaymentTotals(long netPaise, long count) {
    }

    record BillPaid(UUID billId, long paidPaise) {
    }

    record PaymentRow(UUID id, String mode, long amountPaise, String reference, UUID recordedBy, Instant recordedAt) {
    }
}
