package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Port for the counter's product search (SAH-80-3, ADR-030 S13): what is on the shelf of one store. A read. */
public interface SaleSearchRepository {

    /** The AVAILABLE, unexpired quantity of a product in the store and the MRP of the batch FEFO would pick first. */
    record Shelf(int availableQty, BigDecimal firstMrp) {
    }

    /** One statement for all the products; a product with nothing usable on the shelf is absent from the map. */
    Map<UUID, Shelf> shelf(UUID tenantId, UUID storeId, Collection<UUID> productIds, LocalDate today);
}
