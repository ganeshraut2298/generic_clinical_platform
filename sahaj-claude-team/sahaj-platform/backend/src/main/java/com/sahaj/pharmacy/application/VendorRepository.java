package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyMasterViews.StoreName;
import com.sahaj.pharmacy.domain.Vendor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.vendor} and {@code pharmacy.vendor_store}. Row level security scopes it; nothing is deleted. */
public interface VendorRepository {

    /** The status filter of the list: DEFAULT is ACTIVE and BLOCKED (everything but INACTIVE). */
    enum Filter {
        DEFAULT, ACTIVE, BLOCKED, INACTIVE, ALL
    }

    /** The editable fields of a vendor, already checked and normalised. */
    record Fields(String name, String gstin, boolean licenceRequired, String licenceNo, LocalDate licenceValidTill,
                  String contactName, String contactPhone, int creditDays, Integer returnDaysBeforeExpiry,
                  BigDecimal returnCreditPercent, BigDecimal damagedCreditPercent, BigDecimal expiredCreditPercent) {
    }

    record Row(Vendor vendor, int productsCount) {
    }

    record Slice(List<Row> items, long total) {
    }

    record ClientKeyHit(UUID vendorId, UUID createdBy) {
    }

    /** By name ignoring case. {@code like} is lower case with {@code %} and {@code _} already escaped, or empty for no filter. */
    Slice list(UUID tenantId, String like, Filter filter, LocalDate today, int limit, int offset);

    /** The same page restricted to the vendors that supply the (ACTIVE) store (0.3.3, suppliesStoreId). */
    Slice listSupplying(UUID tenantId, String like, Filter filter, LocalDate today, UUID storeId, int limit, int offset);

    Optional<Vendor> find(UUID tenantId, UUID id);

    /** ACTIVE products whose preferred vendor is this vendor. */
    int productsCount(UUID tenantId, UUID vendorId);

    /** The ACTIVE stores each vendor supplies to (a switched-off store is left out), the Central Store first. */
    Map<UUID, List<StoreName>> suppliesTo(UUID tenantId, Collection<UUID> vendorIds);

    Optional<ClientKeyHit> findByClientKey(UUID tenantId, UUID clientKey);

    /** Name used by another vendor of the unit, ignoring case, whatever its status. */
    boolean nameInUse(UUID tenantId, String name, UUID exceptVendorId);

    UUID insert(UUID tenantId, UUID unitId, UUID clientKey, Fields fields, UUID actorUserId);

    /** Saves the fields when the row's version is still {@code version}; false when it is not. */
    boolean update(UUID tenantId, UUID id, long version, Fields fields, UUID actorUserId);

    /** Status INACTIVE, version plus one. */
    void switchOff(UUID tenantId, UUID id, UUID actorUserId);

    /** Status ACTIVE (the licence state decides Blocked on every read), version plus one. */
    void switchOn(UUID tenantId, UUID id, UUID actorUserId);

    /** Makes exactly these stores the active ones of the vendor; a store taken off keeps its row with active false. */
    void replaceStores(UUID tenantId, UUID unitId, UUID vendorId, Collection<UUID> storeIds, UUID actorUserId);
}
