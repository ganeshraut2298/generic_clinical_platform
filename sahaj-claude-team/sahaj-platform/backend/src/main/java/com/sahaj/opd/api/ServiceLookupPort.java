package com.sahaj.opd.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@code billing} needs from the clinic's price list to add a line item to a bill (SAH-10): a
 * service's name and price, snapshotted at the moment it is added so a later rename or price change
 * does not change an existing bill's history. {@code billing} never joins {@code opd.service}
 * directly. Implemented by {@code opd}; runs in the caller's transaction (tenant already bound).
 */
public interface ServiceLookupPort {

    record ServiceSummary(UUID id, String name, long pricePaise, boolean active) {
    }

    /** Another clinic's service id, or any id at all, is simply absent. */
    Optional<ServiceSummary> find(UUID tenantId, UUID serviceId);
}
