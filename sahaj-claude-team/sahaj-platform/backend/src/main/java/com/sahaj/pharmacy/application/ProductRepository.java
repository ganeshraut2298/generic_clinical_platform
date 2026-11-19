package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.product} and {@code pharmacy.product_store_setting} (tenant tables, row level security) and the
 * read-only global catalogue ({@code pharmacy.catalogue_product}, {@code pharmacy.catalogue_barcode}: no tenant, never
 * written). An adopted product holds no global field: reads join the catalogue, so a catalogue correction reaches every
 * adopter at once (ADR-028 D3). No delete: a product is switched off by status.
 */
public interface ProductRepository {

    /** One page of a list and the number of rows over all pages. */
    record Slice<T>(List<T> items, long total) {
    }

    /**
     * The fields of a product as the unit sees them: an adopted product's global fields are the catalogue's, an own
     * product's are its own. {@code source} GLOBAL or OWN; {@code inUnit} ADOPTED, NOT_ADOPTED, WITHDRAWN, ACTIVE or INACTIVE.
     */
    record Row(String source, UUID catalogueProductId, UUID productId, String code, String name, String manufacturer,
               boolean madeInHouse, String salt, String strength, String form, String purchaseUnit, String issueUnit,
               int conversion, String hsn, BigDecimal gstRate, String schedule, boolean ndps, String inUnit,
               boolean sample, String alias) {
    }

    /** A unit product with its local settings. {@code own} is false for an adopted product. */
    record Full(Row row, boolean own, boolean active, String barcode, boolean returnable, UUID preferredVendorId,
                boolean hasStock, long version, UUID createdBy) {
    }

    /** What adopt needs to know about a catalogue product. */
    record CatalogueRef(UUID id, String code, boolean active) {
    }

    record ClientKeyHit(UUID productId, UUID createdBy) {
    }

    /** The editable global fields of an own product. */
    record OwnFields(String name, String salt, String strength, String form, String manufacturer, boolean madeInHouse,
                     String hsn, BigDecimal gstRate, String schedule, String purchaseUnit, String issueUnit,
                     int conversion, String dupKey, String barcode) {
    }

    record NewOwn(UUID unitId, String code, UUID clientKey, OwnFields fields) {
    }

    record StoreSettingRow(UUID storeId, Integer reorderLevel, String rack) {
    }

    record VendorRef(UUID id, String name, boolean active, boolean licenceRequired, LocalDate licenceValidTill) {
    }

    record ProductRef(UUID id, String code, String name) {
    }

    // ---- reads ----

    /** Global catalogue and own products together: {@code like} and {@code exact} are lower case, "" for no filter. */
    Slice<Row> search(UUID tenantId, String like, String exact, String source, int limit, int offset);

    Optional<Full> find(UUID tenantId, UUID productId);

    /**
     * The same read with {@code FOR UPDATE} on the product row (DEC-077 L10): a product edit takes it before its open-import
     * check, so a line write (which reads the product {@code FOR SHARE}) and the edit run one after the other.
     */
    Optional<Full> findForUpdate(UUID tenantId, UUID productId);

    /** The unit's product that adopts this catalogue product, if any (any status). */
    Optional<Full> findAdopted(UUID tenantId, UUID catalogueProductId);

    /** Global: no tenant. */
    Optional<CatalogueRef> catalogue(UUID catalogueProductId);

    /** Global: no tenant. */
    List<String> catalogueBarcodes(UUID catalogueProductId);

    /** An own product created with this client key by anyone in the clinic. */
    Optional<ClientKeyHit> findByClientKey(UUID tenantId, UUID clientKey);

    /** ACTIVE catalogue products with this duplicate key, by code. */
    List<PharmacyViews.DuplicateCandidate> duplicatesInCatalogue(UUID tenantId, String dupKey, int limit);

    /** ACTIVE own products of the unit with this duplicate key, by code, leaving out {@code exceptProductId}. */
    List<PharmacyViews.DuplicateCandidate> duplicatesOwn(UUID tenantId, String dupKey, UUID exceptProductId, int limit);

    /** The name of the other ACTIVE product of the unit that uses this barcode (ignoring case), if any. */
    Optional<String> barcodeOwner(UUID tenantId, String barcode, UUID exceptProductId);

    List<StoreSettingRow> storeSettings(UUID tenantId, UUID productId);

    Optional<VendorRef> vendor(UUID tenantId, UUID vendorId);

    /** Distinct purchase and issue units, and forms, that the unit's own products already use. */
    List<String> usedUnits(UUID tenantId);

    List<String> usedForms(UUID tenantId);

    /**
     * ACTIVE adopted or own products by lower-case code (opening stock rows), each read with {@code FOR SHARE} so that a product
     * edit (which locks the row first) cannot change its units or switch it off between this check and the line write.
     */
    Map<String, ProductRef> activeByCodesForShare(UUID tenantId, Collection<String> lowerCodes);

    /**
     * Whether the product is on a line of an opening-stock import (of any store) that is DRAFT or WAITING_APPROVAL (an open
     * import, DEC-071 option A).
     */
    boolean onOpenImport(UUID tenantId, UUID productId);

    // ---- writes (the caller holds the unit lock) ----

    /** Takes the next own-product number of the unit; the first call returns 1. */
    int takeOwnNumber(UUID tenantId, UUID unitId, UUID actorUserId);

    UUID insertAdopted(UUID tenantId, UUID unitId, UUID catalogueProductId, UUID actorUserId);

    UUID insertOwn(UUID tenantId, NewOwn product, UUID actorUserId);

    /** Saves the own fields when the version is still {@code version}; false when it is not. */
    boolean updateOwn(UUID tenantId, UUID id, long version, OwnFields fields, UUID actorUserId);

    /** Saves the local fields when the version is still {@code version}; false when it is not. */
    boolean updateLocal(UUID tenantId, UUID id, long version, String alias, String barcode, boolean returnable,
                        UUID preferredVendorId, boolean active, UUID actorUserId);

    /** Inserts or updates the setting of one store (a cleared setting keeps its row with both values null). */
    void saveStoreSetting(UUID tenantId, UUID unitId, UUID productId, UUID storeId, Integer reorderLevel, String rack,
                          UUID actorUserId);
}
