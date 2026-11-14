package com.sahaj.opd.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SAH-48-4 (ADR-022 D1, D7): how the Doctor EMR of {@code opd} makes the visit bill's EMR lines equal to the "Bill
 * today" services, without {@code opd} depending on {@code billing} (billing already depends on this package, so the
 * reverse would be a module cycle). Declared here, implemented by {@code billing}. It runs in the caller's transaction
 * (the clinic is already bound), so a refusal rolls the whole EMR save back.
 *
 * <p>Lines are named by {@code sourceKey} (the EMR list item id): a retry or a double tap gives one line. Lines added at
 * the counter are never touched. Nothing here holds a patient name.
 */
public interface EmrVisitChargePort {

    /** One wanted line: the EMR item id, the service and the quantity. */
    record Line(String sourceKey, UUID serviceId, int qty) {
    }

    enum Outcome {
        /** The visit bill's EMR lines now equal the wanted set. */
        SYNCED,
        /** The bill has a payment and a line would be removed or changed: nothing was changed. */
        ALREADY_BILLED,
        /** A service is unknown, inactive or another clinic's: nothing was changed. */
        INVALID_SERVICE
    }

    /** Makes the visit bill's EMR lines equal {@code wanted}: insert missing, update qty, delete lines no longer wanted. */
    Outcome syncEmrLines(UUID tenantId, UUID actorUserId, UUID visitId, List<Line> wanted);

    /** One priced line with no service: a dental plan row done today (ADR-025 D7, H13). {@code label} is at most 120. */
    record PricedLine(String sourceKey, String label, long amountPaise) {
    }

    /** SAH-68-2 (ADR-025 D14): true when the visit's bill has any payment (the visit fee is then never recomputed). */
    boolean hasPayment(UUID tenantId, UUID visitId);

    /**
     * Adds priced lines (source EMR_PLAN) named by {@code sourceKey}; a repeat gives one line. Allowed on a bill that has
     * a payment (a new open line, never a change). INVALID_SERVICE when a label or an amount is out of range.
     */
    Outcome addPricedLines(UUID tenantId, UUID actorUserId, UUID visitId, List<PricedLine> lines);

    /** For the list view: source key to {@code ON_BILL} or {@code ON_PAID_BILL}, for the lines the EMR made. */
    Map<String, String> billedState(UUID tenantId, UUID visitId);
}
