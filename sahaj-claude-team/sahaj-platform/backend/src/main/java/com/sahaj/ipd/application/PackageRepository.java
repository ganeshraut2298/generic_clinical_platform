package com.sahaj.ipd.application;

import java.util.List;
import java.util.UUID;

/**
 * {@code ipd.package} (SAH-29, read-only slice). The database role can select and nothing more: there is no write
 * method. Every method is scoped to the tenant (and row-level security does the same).
 */
public interface PackageRepository {

    /** The clinic's active packages in the order they were created ({@code created_at}, then {@code id}). */
    List<PackageRow> findActive(UUID tenantId);

    /** One row of the Packages list; the price is in paise (whole rupees) and 0 included days means day-care. */
    record PackageRow(UUID id, String name, long pricePaise, int includedDays) {
    }
}
