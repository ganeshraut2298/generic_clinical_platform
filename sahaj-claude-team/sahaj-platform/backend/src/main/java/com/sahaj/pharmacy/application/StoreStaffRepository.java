package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.store_staff}: a team member's mapping to a store. Never deleted; REMOVED rows are history. */
public interface StoreStaffRepository {

    /** A mapping with the store it belongs to (the store may be switched off). */
    record Mapped(StoreStaff staff, Store store) {
    }

    Optional<StoreStaff> find(UUID tenantId, UUID id);

    /** The one row of this person in this store, ACTIVE or REMOVED (a re-map re-activates it). */
    Optional<StoreStaff> findForMember(UUID tenantId, UUID storeId, UUID membershipId);

    /** Staff of one store: HOD first, then delegate, then the rest (the service sorts by name); REMOVED rows on request. */
    List<StoreStaff> listForStore(UUID tenantId, UUID storeId, boolean includeRemoved);

    /** ACTIVE mappings of these stores, for the store list (HOD, delegate, staff count). */
    List<StoreStaff> activeForStores(UUID tenantId, Collection<UUID> storeIds);

    /** The ACTIVE HOD (APPROVER) or delegate (DELEGATE) of a store. */
    Optional<StoreStaff> activeWithRole(UUID tenantId, UUID storeId, StoreRole role);

    /**
     * ACTIVE mappings of these members to ACTIVE stores, with the store row. The guard, the menu port, GET /context and
     * the "other stores" column all read this.
     */
    List<Mapped> activeMappedStores(UUID tenantId, Collection<UUID> membershipIds);

    /** True when the member has at least one ACTIVE mapping to an ACTIVE store (the menu port). */
    boolean hasActiveMapping(UUID tenantId, UUID membershipId);

    /** Distinct members with an ACTIVE mapping to an ACTIVE store (the setup summary; the service drops those who cannot sign in). */
    List<UUID> mappedMembersOfActiveStores(UUID tenantId);

    UUID insert(UUID tenantId, UUID unitId, UUID storeId, UUID membershipId, StoreRole role, LocalDate delegateFrom,
                LocalDate delegateTo, UUID actorUserId);

    /** Saves role, dates and status when the row's version is still {@code version}; false when it is not. */
    boolean update(UUID tenantId, UUID id, long version, StoreRole role, LocalDate delegateFrom, LocalDate delegateTo,
                   boolean active, UUID actorUserId);
}
