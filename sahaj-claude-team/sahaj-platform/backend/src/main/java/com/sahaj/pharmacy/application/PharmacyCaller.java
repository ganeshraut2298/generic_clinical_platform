package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.TeamRoles;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in member as Pharmacy and Inventory sees them on this request: the membership read again through
 * {@code TeamMemberPort} (so a switched-off member is already gone), their team role, and their ACTIVE mappings to ACTIVE
 * stores with the role that counts today and the derived access. Never cached across requests.
 */
public record PharmacyCaller(UUID tenantId, UUID userId, UUID membershipId, String name, String teamRole,
                             List<Mapping> mappings) {

    /** One ACTIVE mapping to an ACTIVE store. {@code effective} is the role that counts today (see StoreRules). */
    public record Mapping(Store store, StoreStaff staff, StoreRole effective, StoreAccess access) {
    }

    /**
     * The id every SAH-79 writer stores as the actor: {@code created_by}, {@code signer}, {@code raised_by},
     * {@code decided_by}, the ledger actor and the document event actor are membership ids, never the user id (security L13,
     * DEC-080). The SAH-79 tables refuse a row whose signer or decider equals its raiser by membership, so a user id stored
     * in one column and a membership id in another would let the same person pass. One method, so a writer cannot pick the
     * wrong id.
     */
    public UUID actorId() {
        return membershipId;
    }

    public boolean isOwner() {
        return TeamRoles.OWNER.equals(teamRole);
    }

    public boolean isPharmacist() {
        return TeamRoles.PHARMACIST.equals(teamRole);
    }

    public Optional<Mapping> mappingFor(UUID storeId) {
        return mappings.stream().filter(m -> m.store().id().equals(storeId)).findFirst();
    }

    /** Whether the access level is met (ADR-028 D4 layer 2). */
    public boolean meets(AccessLevel level) {
        switch (level) {
            case SETUP_EDIT:
                return isOwner();
            case SETUP_VIEW:
                return isOwner() || isPharmacist() || hasCentralStoreUserMapping();
            case PHARMACY_ACCESS:
            default:
                return isOwner() || isPharmacist() || !mappings.isEmpty();
        }
    }

    /** An ACTIVE Central Store user mapping that still counts (the team role still allows it). */
    private boolean hasCentralStoreUserMapping() {
        return mappings.stream().anyMatch(m -> m.effective() == StoreRole.CENTRAL_STORE_USER);
    }
}
