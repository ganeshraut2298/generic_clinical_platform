package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read port of the Command Center cards that no other port answers (SAH-80-20, docs/api/pharmacy-sales.yaml pharmacyCommandCentre).
 * Select only; every statement is scoped to the bound clinic and to the stores passed in. Nothing here reads a name.
 */
public interface CommandCentreRepository {

    /** Bills saved in the range (net payable, cancelled bills left out) and the refunds of sales returns completed in it. */
    record Sales(long netPaise, int bills, long refundedPaise) {
    }

    /** AVAILABLE batches of one product at one store that fall in a band by expiry: EXPIRED, D0_30, D31_60 or D61_90. */
    record ExpiryBand(UUID storeId, UUID productId, String band, int batches, long valuePaise) {
    }

    /** The due of the customers, the part of it older than 60 days, and whether any customer has a credit limit. */
    record Udhar(long duePaise, long over60Paise, boolean anyLimit) {
    }

    Sales sales(UUID tenantId, Collection<UUID> storeIds, Instant from, Instant to);

    List<ExpiryBand> expiryBands(UUID tenantId, Collection<UUID> storeIds, LocalDate today);

    Udhar udhar(UUID tenantId, Instant since);

    /** Indents the stores still have to dispatch (they issue) or to receive (they raised), not yet closed. */
    int indentsWaiting(UUID tenantId, Collection<UUID> storeIds);
}
