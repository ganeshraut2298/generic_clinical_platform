package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.ForbiddenException;
import java.util.UUID;

/**
 * 403 {@code store-permission-denied}: the caller is mapped to the store (or is the owner) but lacks the capability the
 * operation needs (ADR-028 D4 layer 3).
 *
 * <p>It carries the ids the denial audit needs (membership, capability, store: never a name or a note). {@link StoreAccessGuard}
 * does not write the audit: it would need a second pooled connection while the caller's transaction holds the first (code
 * review N-1, security L7). The pharmacy problem handler writes the {@code PHARMACY_STORE_ACCESS_DENIED} row through
 * {@link StoreDenialAuditor} after the caller's transaction has ended, in one short transaction.
 */
public class StoreAccessDeniedException extends ForbiddenException {

    private static final long serialVersionUID = 1L;

    private final UUID membershipId;
    private final StoreCapability capability;
    private final UUID storeId;

    public StoreAccessDeniedException(UUID membershipId, StoreCapability capability, UUID storeId) {
        super("store-permission-denied", "Not allowed in this store", "You cannot do this in this store.");
        this.membershipId = membershipId;
        this.capability = capability;
        this.storeId = storeId;
    }

    public UUID membershipId() {
        return membershipId;
    }

    public StoreCapability capability() {
        return capability;
    }

    public UUID storeId() {
        return storeId;
    }
}
