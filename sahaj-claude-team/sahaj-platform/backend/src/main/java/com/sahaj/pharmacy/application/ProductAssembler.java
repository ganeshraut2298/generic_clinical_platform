package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ProductRepository.Full;
import com.sahaj.pharmacy.application.ProductRepository.Row;
import com.sahaj.pharmacy.application.ProductRepository.StoreSettingRow;
import com.sahaj.pharmacy.application.ProductRepository.VendorRef;
import com.sahaj.pharmacy.domain.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the product answers of the contract (ProductRow, ProductDetail) from the repository rows. The global fields of an
 * adopted product were read from the catalogue by the repository, so they are always current. Rates and percents leave as
 * decimal strings, never as floating point numbers.
 */
@Component
class ProductAssembler {

    private final ProductRepository products;
    private final StoreRepository stores;
    private final PharmacyTime time;

    ProductAssembler(ProductRepository products, StoreRepository stores, PharmacyTime time) {
        this.products = products;
        this.stores = stores;
        this.time = time;
    }

    static PharmacyViews.ProductRow row(Row r) {
        return new PharmacyViews.ProductRow(r.source(), r.catalogueProductId(), r.productId(), r.code(), r.name(),
                r.manufacturer(), r.madeInHouse(), r.salt(), r.strength(), r.form(), r.purchaseUnit(), r.issueUnit(),
                r.conversion(), r.hsn(), percent(r.gstRate()), r.schedule(), r.ndps(), r.inUnit(), r.sample(), r.alias());
    }

    PharmacyViews.ProductDetail detail(UUID tenantId, Full full) {
        Row r = full.row();
        List<String> barcodes = full.own() || r.catalogueProductId() == null ? List.of()
                : products.catalogueBarcodes(r.catalogueProductId());
        return new PharmacyViews.ProductDetail(r.source(), r.catalogueProductId(), r.productId(), r.code(), r.name(),
                r.manufacturer(), r.madeInHouse(), r.salt(), r.strength(), r.form(), r.purchaseUnit(), r.issueUnit(),
                r.conversion(), r.hsn(), percent(r.gstRate()), r.schedule(), r.ndps(), r.inUnit(), r.sample(), r.alias(),
                barcodes, localSettings(tenantId, full), full.hasStock(), full.hasStock(), full.version());
    }

    private PharmacyViews.ProductLocalSettings localSettings(UUID tenantId, Full full) {
        Map<UUID, StoreSettingRow> saved = new HashMap<>();
        products.storeSettings(tenantId, full.row().productId()).forEach(s -> saved.put(s.storeId(), s));
        List<PharmacyViews.StoreSetting> settings = stores.list(tenantId, false).stream().map(store -> {
            StoreSettingRow row = saved.get(store.id());
            return new PharmacyViews.StoreSetting(store.id(), store.name(), row == null ? null : row.reorderLevel(),
                    row == null ? null : row.rack());
        }).toList();
        PharmacyViews.PreferredVendor vendor = null;
        if (full.preferredVendorId() != null) {
            vendor = products.vendor(tenantId, full.preferredVendorId())
                    .map(v -> new PharmacyViews.PreferredVendor(v.id(), v.name(), !usable(v, time.today()))).orElse(null);
        }
        return new PharmacyViews.ProductLocalSettings(settings, vendor, full.row().alias(), full.barcode(),
                full.returnable(), full.active());
    }

    /** A vendor can be chosen as preferred when it is ACTIVE and its licence has not expired (Blocked is computed in IST). */
    static boolean usable(VendorRef vendor, LocalDate today) {
        boolean blocked = vendor.licenceRequired() && vendor.licenceValidTill() != null
                && vendor.licenceValidTill().isBefore(today);
        return vendor.active() && !blocked;
    }

    /** "12", "5.5", "0": a percent without trailing zeros. */
    static String percent(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** A rupee rate with at least two decimals: "45.50", "30.00". Null stays null. */
    static String rate(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal trimmed = value.stripTrailingZeros();
        return (trimmed.scale() < 2 ? trimmed.setScale(2) : trimmed).toPlainString();
    }

    /** The active stores of the unit, for the settings check. */
    List<Store> activeStores(UUID tenantId) {
        return stores.list(tenantId, false);
    }
}
