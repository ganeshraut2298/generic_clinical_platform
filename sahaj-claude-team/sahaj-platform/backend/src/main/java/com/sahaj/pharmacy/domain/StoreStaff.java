package com.sahaj.pharmacy.domain;

import java.time.LocalDate;
import java.util.UUID;

/** One row of {@code pharmacy.store_staff}: a team member's mapping to a store. A REMOVED row is history, never deleted. */
public record StoreStaff(UUID id, UUID storeId, UUID membershipId, StoreRole storeRole, LocalDate delegateFrom,
                         LocalDate delegateTo, boolean active, long version) {
}
