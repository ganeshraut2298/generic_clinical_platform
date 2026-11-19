package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.platform.api.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Layer 3 of the access model (ADR-028 D4, pattern of the ipd scope guard, ADR-017): the one class that decides whether
 * the caller may act inside the store named in the path of an {@code x-store-access: OPERATION} operation. The server
 * never trusts a "current store" from the client.
 *
 * <ul>
 *   <li>unknown id, another clinic's store (row level security), a switched-off store, or a store the caller is not
 *       mapped to (the owner is not excepted for the working store, and is for every other operation): 404 not-found;</li>
 *   <li>mapped (or the owner) but the capability is missing: 403 {@code store-permission-denied}, carrying the ids the
 *       audit needs ({@link StoreAccessDeniedException}).</li>
 * </ul>
 *
 * <p>It runs before any filter, sort or paging parameter. It writes nothing: the denial audit
 * ({@code PHARMACY_STORE_ACCESS_DENIED}, ids only) is written by the pharmacy problem handler through
 * {@link StoreDenialAuditor}, after the caller's transaction has ended and in one short transaction. A denial therefore
 * never needs a second pooled connection while the first is held (code review N-1, security L7), and no caller needs
 * {@code noRollbackFor}. Operations marked {@code MASTER} do not use the guard: they are gated by the access level only.
 */
@Component
public class StoreAccessGuard {

    private final StoreRepository stores;

    public StoreAccessGuard(StoreRepository stores) {
        this.stores = stores;
    }

    /**
     * The store, when the caller may use {@code capability} in it. The owner reaches every ACTIVE store with the owner's
     * capability table (no Schedule X); everyone else needs an ACTIVE mapping.
     */
    public Store require(PharmacyCaller caller, UUID storeId, StoreCapability capability) {
        Store store = stores.find(caller.tenantId(), storeId).filter(Store::active).orElseThrow(StoreAccessGuard::notFound);
        if (capability == StoreCapability.PURCHASE) {
            boolean buyer = caller.isOwner() || caller.mappingFor(storeId).map(m -> m.effective() == StoreRole.CENTRAL_STORE_USER)
                    .orElseThrow(StoreAccessGuard::notFound);
            if (!buyer) {
                throw refuse(caller, storeId, capability);
            }
            return store;
        }
        StoreAccess access;
        if (caller.isOwner()) {
            access = StoreRules.access(caller.teamRole(), StoreRole.APPROVER, store.consumptionAllowed());
        } else {
            access = caller.mappingFor(storeId).orElseThrow(StoreAccessGuard::notFound).access();
        }
        if (!access.has(capability)) {
            throw refuse(caller, storeId, capability);
        }
        return store;
    }

    /**
     * Whether the caller holds the capability at the store, without throwing (a read that answers 404 for everyone who may not
     * see the document, L18). The same rules as {@link #require}: PURCHASE is the owner or a Central Store user mapped to it;
     * every other capability is the owner's table, or the ACTIVE mapping's access. Needs no store row: the mappings are
     * ACTIVE stores only, and the owner's table does not depend on the store (consumption is not read here).
     */
    public static boolean holds(PharmacyCaller caller, UUID storeId, StoreCapability capability) {
        if (storeId == null) {
            return false;
        }
        if (capability == StoreCapability.PURCHASE) {
            return caller.isOwner() || caller.mappingFor(storeId).map(m -> m.effective() == StoreRole.CENTRAL_STORE_USER)
                    .orElse(false);
        }
        if (caller.isOwner()) {
            return StoreRules.access(caller.teamRole(), StoreRole.APPROVER, false).has(capability);
        }
        return caller.mappingFor(storeId).map(m -> m.access().has(capability)).orElse(false);
    }

    /**
     * A refusal inside a store the caller may reach, for a rule the capability table cannot express (for example "only the
     * person who started the import"). The caller throws the returned exception; the handler audits it (ids only).
     */
    public StoreAccessDeniedException refuse(PharmacyCaller caller, UUID storeId, StoreCapability capability) {
        return new StoreAccessDeniedException(caller.membershipId(), capability, storeId);
    }

    /**
     * For an operation that only needs the caller to be mapped to the store, such as saving the working store: the owner
     * too must be mapped (the working-store select lists mapped stores only). 404 otherwise.
     */
    public PharmacyCaller.Mapping requireMapped(PharmacyCaller caller, UUID storeId) {
        return caller.mappingFor(storeId).orElseThrow(StoreAccessGuard::notFound);
    }

    /** The 404 of the guard: the same answer for unknown, foreign, switched-off and unmapped stores. */
    static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
