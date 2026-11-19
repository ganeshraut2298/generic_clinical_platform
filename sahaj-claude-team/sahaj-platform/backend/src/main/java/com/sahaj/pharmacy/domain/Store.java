package com.sahaj.pharmacy.domain;

import java.util.UUID;

/** One row of {@code pharmacy.store}. {@code issuesFromStoreId} is null exactly for the Central Store. */
public record Store(UUID id, UUID unitId, String name, String code, StoreType type, UUID issuesFromStoreId,
                    boolean saleAllowed, boolean consumptionAllowed, boolean hasStock, boolean active, long version) {
}
