package com.sahaj.platform.api;

import java.util.UUID;

/**
 * SAH-78 (ADR-028 D1, D11): the one question {@code platform} asks the Pharmacy and Inventory module, for the sidebar
 * entry {@code pharmacy}. Declared here and implemented by {@code com.sahaj.pharmacy} (consumer-declared port, precedent
 * {@code OpdSetupStatus}), so that no module depends on {@code pharmacy} and {@code platform} never reads a pharmacy
 * table. Runs in the caller's transaction (the clinic is already bound).
 */
public interface PharmacyAccessPort {

    /** Used while the pharmacy module is absent (for example in a slice test): nobody has a store mapping. */
    PharmacyAccessPort NONE = (tenantId, membershipId) -> false;

    /**
     * True when this member has at least one ACTIVE store mapping to an ACTIVE store of the bound clinic. A switched-off
     * member is handled by the caller (the menu is only built for a signed-in, active member).
     */
    boolean hasActiveMapping(UUID tenantId, UUID membershipId);
}
