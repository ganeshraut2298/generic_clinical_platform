package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ProductRepository.Full;
import com.sahaj.pharmacy.application.ProductRepository.StoreSettingRow;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.BatchView;
import com.sahaj.pharmacy.application.StockRepository.StateRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.PurchaseMath;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-3 and 79-4: the stock screens of a store (docs/api/pharmacy.yaml 0.3.0). Every read of a store first runs the expiry
 * sweep of that store (rule 17, ADR-029 P4), so the transactions are read-write; a row a movement holds is skipped and never
 * waited for. The gate order is: module off, access level, path id, the store guard, then the query values. Quantities come from
 * the balances that the ledger keeps. Nothing here logs a name, a batch number or a quantity.
 */
@Service
public class StockReadService {

    private static final int Q_MIN_FILTER = 2;
    private static final int Q_MAX = 60;
    private static final int NEAR_EXPIRY_CHIP_DAYS = 90;

    /** The Batch inventory views. */
    enum View { ALL, NEAR_EXPIRY, EXPIRED, SCHEDULE }

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final PharmacyTime time;

    public StockReadService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                            StockRepository stock, StockPosting posting, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.time = time;
    }

    @Transactional
    public StockViews.Overview overview(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.membershipId());
        StockRepository.Overview o = stock.overview(tenantId, storeId, time.today());
        return new StockViews.Overview(OpeningStockPostService.storeRef(store), o.skuCount(), o.batchCount(),
                o.stockValuePaise(), new StockViews.Pills(o.nearExpiry30(), o.nearExpiry60(), o.nearExpiry90(),
                        o.expiredQuarantined(), o.lowStock(), o.scheduleProducts()), o.skuCount() == 0 && o.batchCount() == 0);
    }

    @Transactional
    public StockViews.BatchPage batches(UUID tenantId, UUID userId, String rawStoreId, String rawView, String rawWithin,
                                        String rawQ, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        View view = PharmacyParams.optionalEnum("view", rawView, View.class);
        int within = PharmacyParams.intValue("withinDays", rawWithin, 90, 1, 365);
        if (within != 30 && within != 60 && within != 90) {
            throw new InvalidFieldsException(List.of(new FieldMessage("withinDays", "Choose 30, 60 or 90 days")));
        }
        String q = rawQ == null ? "" : rawQ.trim();
        if (q.length() > Q_MAX) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type at most 60 characters.")));
        }
        if (q.length() == 1) {
            // D-7 (contract 0.3.4, minLength 2): one character is an error, never a list
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type at least 2 characters.")));
        }
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 25, 1, 100);
        String exact = q.length() >= Q_MIN_FILTER ? q.toLowerCase(Locale.ROOT) : "";
        String like = exact.isEmpty() ? "" : "%" + ProductSearchService.escapeLike(exact) + "%";

        posting.sweepStore(tenantId, store.unitId(), storeId, caller.membershipId());
        LocalDate today = time.today();
        StockRepository.BatchSlice slice = stock.batches(tenantId, storeId, today, (view == null ? View.ALL : view).name(),
                within, like, exact, size, page * size);
        boolean canReturn = canVendorReturn(caller, store);
        List<StockViews.BatchRow> items = slice.items().stream().map(v -> row(v, today, canReturn)).toList();
        return new StockViews.BatchPage(items, slice.total(), page, size);
    }

    @Transactional
    public StockViews.ProductStock product(UUID tenantId, UUID userId, String rawStoreId, String rawProductId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID productId = PharmacyParams.pathId(rawProductId);
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        Full full = products.find(tenantId, productId).orElseThrow(StockReadService::notFound);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.membershipId());
        LocalDate today = time.today();
        boolean canReturn = canVendorReturn(caller, store);
        List<StockViews.BatchRow> batches = stock.batchesOfProduct(tenantId, storeId, productId, today).stream()
                .map(v -> row(v, today, canReturn)).toList();
        StoreSettingRow setting = products.storeSettings(tenantId, productId).stream()
                .filter(s -> s.storeId().equals(storeId)).findFirst().orElse(null);
        ProductRepository.Row r = full.row();
        return new StockViews.ProductStock(productRef(r), r.salt(), r.hsn(), ProductAssembler.percent(r.gstRate()),
                r.purchaseUnit() + " of " + r.conversion() + " → " + r.issueUnit(),
                setting == null ? null : setting.rack(), setting == null ? null : setting.reorderLevel(), batches);
    }

    @Transactional
    public StockViews.LowStockPage lowStock(UUID tenantId, UUID userId, String rawStoreId, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 25, 1, 100);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.membershipId());
        StockRepository.LowStockSlice slice = stock.lowStock(tenantId, storeId, time.today(), size, page * size);
        String action = null;
        if (store.type().isCentral()) {
            action = hasPurchase(caller, store) ? "ADD_TO_PURCHASE_ORDER" : null;
        } else if (accessIn(caller, store).has(StoreCapability.RAISE_INDENT)) {
            action = "RAISE_INDENT";
        }
        String chosen = action;
        List<StockViews.LowStockItem> items = slice.items().stream()
                .map(r -> new StockViews.LowStockItem(new StockViews.ProductRef(r.productId(), r.code(), r.name(),
                        r.strength(), r.form(), r.schedule(), r.purchaseUnit(), r.issueUnit(), r.conversion(),
                        PurchaseMath.percentText(r.gstPercent())),
                        r.available(), r.reorderLevel(), chosen)).toList();
        return new StockViews.LowStockPage(items, slice.total(), page, size);
    }

    /** The batches FEFO would pick now. A preview: the posting picks again under locks. */
    @Transactional
    public StockViews.FefoPick fefoPick(UUID tenantId, UUID userId, String rawStoreId, String rawProductId,
                                        String rawQuantity) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        List<FieldMessage> errors = new ArrayList<>();
        UUID productId = PharmacyParams.optionalUuid("productId", rawProductId, "Choose a product");
        if (productId == null) {
            errors.add(new FieldMessage("productId", "Choose a product"));
        }
        int quantity = 0;
        try {
            quantity = rawQuantity == null ? 0 : Integer.parseInt(rawQuantity.trim());
        } catch (NumberFormatException notANumber) {
            quantity = 0;
        }
        if (quantity < 1 || quantity > 999_999) {
            errors.add(new FieldMessage("quantity", "Enter a whole number from 1 to 999999"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        products.find(tenantId, productId).orElseThrow(StockReadService::notFound);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.membershipId());
        List<Bal> rows = stock.availableOfProduct(tenantId, storeId, productId);
        Fefo.Result result = posting.pick(rows, quantity);
        Map<UUID, Bal> byBatch = new HashMap<>();
        rows.forEach(b -> byBatch.put(b.batchId(), b));
        List<StockViews.FefoBatch> batches = new ArrayList<>();
        for (Fefo.Pick pick : result.picks()) {
            Bal bal = byBatch.get(pick.batchId());
            batches.add(new StockViews.FefoBatch(new StockViews.BatchRef(bal.batchId(), bal.batchNo(), bal.expiryDate()),
                    pick.quantity(), ProductAssembler.rate(bal.purchaseRate())));
        }
        return new StockViews.FefoPick(productId, quantity, result.available(), result.shortBy(), batches);
    }

    /** "All stores": the owner gets every ACTIVE store, anyone else the stores they are mapped to (rule 15). */
    @Transactional
    public StockViews.ProductStockByStore productAllStores(UUID tenantId, UUID userId, String rawProductId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID productId = PharmacyParams.pathId(rawProductId);
        Full full = products.find(tenantId, productId).orElseThrow(StockReadService::notFound);
        List<Store> reachable = new ArrayList<>();
        if (caller.isOwner()) {
            reachable.addAll(stores.list(tenantId, false));
        } else {
            for (PharmacyCaller.Mapping mapping : caller.mappings()) {
                if (mapping.access().has(StoreCapability.READ_STOCK)) {
                    reachable.add(mapping.store());
                }
            }
            reachable.sort((a, b) -> {
                int central = Boolean.compare(!a.type().isCentral(), !b.type().isCentral());
                return central != 0 ? central : a.name().compareToIgnoreCase(b.name());
            });
        }
        for (Store store : reachable) {
            posting.sweepStore(tenantId, store.unitId(), store.id(), caller.membershipId());
        }
        return byState(tenantId, full, reachable);
    }

    @Transactional
    public StockViews.ProductStockByStore productMyStore(UUID tenantId, UUID userId, String rawStoreId,
                                                         String rawProductId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID productId = PharmacyParams.pathId(rawProductId);
        Store store = guard.require(caller, storeId, StoreCapability.READ_STOCK);
        Full full = products.find(tenantId, productId).orElseThrow(StockReadService::notFound);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.membershipId());
        return byState(tenantId, full, List.of(store));
    }

    // ---- building ----

    private StockViews.ProductStockByStore byState(UUID tenantId, Full full, List<Store> reachable) {
        Map<UUID, StateRow> byStore = new HashMap<>();
        stock.statesOfProduct(tenantId, full.row().productId(), reachable.stream().map(Store::id).toList())
                .forEach(r -> byStore.put(r.storeId(), r));
        List<StockViews.StoreStateRow> rows = new ArrayList<>();
        int available = 0;
        int inTransit = 0;
        int pending = 0;
        int quarantine = 0;
        for (Store store : reachable) {
            StateRow s = byStore.getOrDefault(store.id(), new StateRow(store.id(), 0, 0, 0, 0, null));
            rows.add(new StockViews.StoreStateRow(OpeningStockPostService.storeRef(store), s.available(), s.inTransit(),
                    s.pendingReturn(), s.quarantine(), s.reorderLevel(), status(s)));
            available += s.available();
            inTransit += s.inTransit();
            pending += s.pendingReturn();
            quarantine += s.quarantine();
        }
        StockViews.StoreStateRow total = new StockViews.StoreStateRow(null, available, inTransit, pending, quarantine,
                null, null);
        boolean empty = available + inTransit + pending + quarantine == 0;
        return new StockViews.ProductStockByStore(productRef(full.row()), rows, total, empty);
    }

    /** OK, BELOW_REORDER (at or below the level) or INDENT_IN_TRANSIT (below it, with units on the way); no level is OK. */
    static String status(StateRow s) {
        if (s.reorderLevel() == null || s.available() > s.reorderLevel()) {
            return "OK";
        }
        return s.inTransit() > 0 ? "INDENT_IN_TRANSIT" : "BELOW_REORDER";
    }

    private StockViews.BatchRow row(BatchView v, LocalDate today, boolean canReturnInStore) {
        int days = (int) ChronoUnit.DAYS.between(today, v.expiryDate());
        List<String> chips = new ArrayList<>();
        switch (v.state()) {
            case AVAILABLE:
                // n-3: an expired row the sweep skipped (it was locked) is still shown as expired, never as "expires in -3 days"
                chips.add(days < 0 ? "EXPIRED_QUARANTINED" : days <= NEAR_EXPIRY_CHIP_DAYS ? "EXPIRES_IN" : "SALEABLE");
                break;
            case QUARANTINE:
                chips.add(days < 0 ? "EXPIRED_QUARANTINED" : "QUARANTINE");
                break;
            case IN_TRANSIT:
                chips.add("IN_TRANSIT");
                break;
            default:
                chips.add("PENDING_RETURN");
                break;
        }
        if (v.newMrp()) {
            chips.add("NEW_MRP");
        }
        boolean canReturn = canReturnInStore && v.vendorId() != null && (v.state() == StockState.QUARANTINE
                || v.state() == StockState.AVAILABLE && v.vendorReturnDays() != null && days <= v.vendorReturnDays());
        String landed = ProductAssembler.rate(v.purchaseRate());
        return new StockViews.BatchRow(new StockViews.ProductRef(v.productId(), v.productCode(), v.productName(),
                v.strength(), v.form(), v.schedule(), v.purchaseUnit(), v.issueUnit(), v.conversion(),
                PurchaseMath.percentText(v.gstPercent())),
                new StockViews.BatchRef(v.batchId(), v.batchNo(), v.expiryDate()), v.state().name(), v.qty(),
                v.fefoRank(), ProductAssembler.rate(v.mrp()), landed, landed,
                StockMath.marginPercent(v.mrp(), v.purchaseRate()),
                v.vendorId() == null ? null : new StockViews.Supplier(v.vendorId(), v.vendorName()), chips, days, canReturn);
    }

    private static StockViews.ProductRef productRef(ProductRepository.Row r) {
        return new StockViews.ProductRef(r.productId(), r.code(), r.name(), r.strength(), r.form(), r.schedule(),
                r.purchaseUnit(), r.issueUnit(), r.conversion(), PurchaseMath.percentText(r.gstRate()));
    }

    /** The Central Store: a person with the purchase capability (owner or Central Store user). */
    private static boolean canVendorReturn(PharmacyCaller caller, Store store) {
        return store.type().isCentral() && hasPurchase(caller, store);
    }

    static boolean hasPurchase(PharmacyCaller caller, Store store) {
        if (!store.type().isCentral()) {
            return false;
        }
        if (caller.isOwner()) {
            return true;
        }
        return caller.mappingFor(store.id()).map(m -> m.effective() == StoreRole.CENTRAL_STORE_USER).orElse(false);
    }

    private static StoreAccess accessIn(PharmacyCaller caller, Store store) {
        if (caller.isOwner()) {
            return StoreRules.access(caller.teamRole(), StoreRole.APPROVER, store.consumptionAllowed());
        }
        return caller.mappingFor(store.id()).map(PharmacyCaller.Mapping::access).orElse(StoreAccess.NONE);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
