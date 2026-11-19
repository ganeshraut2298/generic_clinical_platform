package com.sahaj.platform.api;

import java.util.UUID;

/**
 * Read-only view of a clinic's module entitlements for other modules (SAH-19). IPD and HRA stories
 * use it to decide whether their menus and endpoints are available; they never read
 * {@code platform.module_entitlement} directly.
 */
public interface ModuleEntitlementPort {

    /** The clinic's entitlements; defaults (IPD off, HRA off, 25 beds) when nothing was saved yet. */
    ModuleEntitlement forTenant(UUID tenantId);

    /**
     * SAH-78 (ADR-028 D11): true when the Pharmacy and Inventory module is switched on for the clinic
     * ({@code platform.module_entitlement.pharmacy_on}); false by default and when nothing was saved yet. A separate
     * method, so the {@link ModuleEntitlement} record and every existing caller stay unchanged.
     */
    default boolean pharmacyOn(UUID tenantId) {
        return false;
    }

    /**
     * @param ipdState {@code off}, {@code trial}, {@code on} or {@code readonly}; a trial whose end date has passed reads as {@code readonly}
     * @param hraOn    true when the HRA add-on is switched on
     * @param bedLimit IPD bed limit, 5 to 300
     */
    record ModuleEntitlement(String ipdState, boolean hraOn, int bedLimit) {
    }
}
