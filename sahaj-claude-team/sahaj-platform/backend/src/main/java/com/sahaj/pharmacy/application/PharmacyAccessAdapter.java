package com.sahaj.pharmacy.application;

import com.sahaj.platform.api.PharmacyAccessPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@code platform.api.PharmacyAccessPort} (ADR-028 D1, D11): the sidebar entry of a member who is neither
 * OWNER nor PHARMACIST shows when they have at least one ACTIVE mapping to an ACTIVE store. Read only; runs in the
 * caller's transaction ({@code GET /me}), where the clinic is already bound.
 */
@Component
class PharmacyAccessAdapter implements PharmacyAccessPort {

    private final StoreStaffRepository staff;

    PharmacyAccessAdapter(StoreStaffRepository staff) {
        this.staff = staff;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveMapping(UUID tenantId, UUID membershipId) {
        return staff.hasActiveMapping(tenantId, membershipId);
    }
}
