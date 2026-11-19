package com.sahaj.pharmacy.application;

import java.util.UUID;

/** The counts behind the live subtitles of the setup tabs (docs/api/pharmacy.yaml GET /setup-summary). */
public interface SetupCountRepository {

    /** ACTIVE stores of the unit. */
    int activeStores(UUID tenantId);

    /** ACTIVE vendors of the unit; a Blocked vendor (licence expired) is still ACTIVE and still counts. */
    int activeVendors(UUID tenantId);
}
