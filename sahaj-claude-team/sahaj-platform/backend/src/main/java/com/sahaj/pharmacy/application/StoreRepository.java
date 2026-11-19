package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.store}. Another clinic's row is never visible (row level security). */
public interface StoreRepository {

    Optional<Store> find(UUID tenantId, UUID id);

    /** The same read with {@code for update}, used by every change so two changes of one store run one after the other. */
    Optional<Store> findForUpdate(UUID tenantId, UUID id);

    /** The Central Store first, then the other stores by name; INACTIVE stores only when {@code includeInactive}. */
    List<Store> list(UUID tenantId, boolean includeInactive);

    /** The ACTIVE Central Store, if there is one. */
    Optional<Store> activeCentral(UUID tenantId);

    /** Name or code used by another store of the unit, ignoring case, whatever its status. */
    boolean nameInUse(UUID tenantId, String name, UUID exceptStoreId);

    boolean codeInUse(UUID tenantId, String code, UUID exceptStoreId);

    /** How many ACTIVE stores there are besides {@code exceptStoreId}. */
    int countActiveExcept(UUID tenantId, UUID exceptStoreId);

    UUID insert(UUID tenantId, UUID unitId, String name, String code, StoreType type, UUID issuesFromStoreId,
                boolean saleAllowed, boolean consumptionAllowed, UUID actorUserId);

    /** Saves the store when its version is still {@code version}; false when it is not. */
    boolean update(UUID tenantId, UUID id, long version, String name, String code, StoreType type,
                   UUID issuesFromStoreId, boolean saleAllowed, boolean consumptionAllowed, UUID actorUserId);

    /** Status INACTIVE, version plus one. */
    void switchOff(UUID tenantId, UUID id, UUID actorUserId);

    /**
     * Whether the store has an opening-stock import that is DRAFT or WAITING_APPROVAL (an open import, DEC-071 option A).
     * Read under the store row lock that import start and submit take first.
     */
    boolean hasOpenImport(UUID tenantId, UUID storeId);

    /** Sets the stock flag (one way: it is never cleared) when an opening-stock import is approved. */
    void markHasStock(UUID tenantId, UUID id, UUID actorUserId);
}
