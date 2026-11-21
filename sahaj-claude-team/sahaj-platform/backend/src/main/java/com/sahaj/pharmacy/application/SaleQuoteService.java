package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-3, the counter's search and the quote (docs/api/pharmacy-sales.yaml pharmacySaleProductSearch and pharmacyQuoteSale,
 * ADR-030 S3, S13). Both run the store's expiry sweep first (ADR-029 P4), so both are plain read-write transactions and neither
 * takes a row lock of its own. The quote prices the cart without saving it; the bill save prices it again under the balance locks.
 * Blind count (rule 32): for a caller blind at the store, the quantity of an audited product is null; rows, chips and the order stay.
 */
@Service
public class SaleQuoteService {

    private static final Pattern BARCODE = Pattern.compile("^[A-Za-z0-9-]{1,40}$");
    private static final int MAX_ROWS = 20;
    private static final int CANDIDATES = 100;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final ProductRepository products;
    private final SaleSearchRepository shelf;
    private final StockPosting posting;
    private final SalePricer pricer;
    private final BlindCount blind;
    private final PharmacyTime time;

    public SaleQuoteService(PharmacyGate gate, StoreAccessGuard guard, ProductRepository products, SaleSearchRepository shelf,
                            StockPosting posting, SalePricer pricer, BlindCount blind, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.products = products;
        this.shelf = shelf;
        this.posting = posting;
        this.pricer = pricer;
        this.blind = blind;
        this.time = time;
    }

    /**
     * Exactly one of {@code q} (2 to 80 characters) or {@code barcode}. ACTIVE products of the unit only, at most 20 rows, in stock
     * first, then by name. No purchase rate, margin or other store's stock. An unknown barcode answers an empty list.
     */
    @Transactional
    public SaleViews.SaleProductList search(UUID tenantId, UUID userId, String rawStoreId, String rawQ, String rawBarcode) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        String q = rawQ == null ? "" : rawQ.trim();
        String barcode = rawBarcode == null ? "" : rawBarcode.trim();
        if (q.isEmpty() == barcode.isEmpty()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type a name or scan a barcode.")));
        }
        if (!q.isEmpty() && (q.length() < 2 || q.length() > 80)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type 2 to 80 characters.")));
        }
        if (!barcode.isEmpty() && !BARCODE.matcher(barcode).matches()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("barcode", "Scan the barcode again.")));
        }
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.actorId());

        String lowered = (q.isEmpty() ? barcode : q).toLowerCase(Locale.ROOT);
        String like = q.isEmpty() ? SaleSupport.escapeLike(lowered) : "%" + SaleSupport.escapeLike(lowered) + "%";
        List<ProductRepository.Row> rows = new ArrayList<>();
        for (ProductRepository.Row row : products.search(tenantId, like, lowered, "", q.isEmpty() ? MAX_ROWS : CANDIDATES, 0)
                .items()) {
            boolean usable = row.productId() != null && ("ADOPTED".equals(row.inUnit()) || "ACTIVE".equals(row.inUnit()));
            if (usable && (q.isEmpty() ? barcodeMatches(tenantId, row, barcode) : true)) {
                rows.add(row);
            }
        }
        Map<UUID, SaleSearchRepository.Shelf> onShelf = shelf.shelf(tenantId, storeId,
                rows.stream().map(ProductRepository.Row::productId).toList(), time.today());
        BlindCount.Mask mask = blind.at(caller, storeId);
        List<SaleViews.SaleProductRow> out = new ArrayList<>();
        for (ProductRepository.Row row : rows) {
            SaleSearchRepository.Shelf s = onShelf.get(row.productId());
            boolean inStock = s != null && s.availableQty() > 0;
            boolean salt = !q.isEmpty() && row.salt() != null && row.salt().toLowerCase(Locale.ROOT).contains(lowered)
                    && !row.name().toLowerCase(Locale.ROOT).contains(lowered);
            out.add(new SaleViews.SaleProductRow(IndentService.productRef(row), row.salt(), packText(row),
                    inStock ? mask.qty(row.productId(), s.availableQty()) : mask.qty(row.productId(), 0), !inStock, salt,
                    inStock ? SaleRules.rate(s.firstMrp()) : null));
        }
        out.sort(Comparator.comparing((SaleViews.SaleProductRow r) -> r.outOfStock())
                .thenComparing(r -> r.product().name().toLowerCase(Locale.ROOT)));
        return new SaleViews.SaleProductList(out.size() > MAX_ROWS ? new ArrayList<>(out.subList(0, MAX_ROWS)) : out);
    }

    /** Prices and checks the cart without saving it. Writes nothing except the store sweep. */
    @Transactional
    public SaleViews.SaleQuote quote(UUID tenantId, UUID userId, String rawStoreId,
                                     PharmacyInputs.Body<SaleInputs.CartInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        SaleInputs.CartInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        List<FieldMessage> errors = new ArrayList<>();
        SaleCart.Cart cart = pricer.read(tenantId, in, errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        pricer.requireReady(tenantId, cart);
        return pricer.price(caller, store, cart, false).quote();
    }

    private boolean barcodeMatches(UUID tenantId, ProductRepository.Row row, String barcode) {
        if (row.code() != null && row.code().equalsIgnoreCase(barcode)) {
            return true;
        }
        ProductRepository.Full full = products.find(tenantId, row.productId()).orElse(null);
        if (full != null && full.barcode() != null && full.barcode().equalsIgnoreCase(barcode)) {
            return true;
        }
        return row.catalogueProductId() != null
                && products.catalogueBarcodes(row.catalogueProductId()).stream().anyMatch(b -> b.equalsIgnoreCase(barcode));
    }

    private static String packText(ProductRepository.Row row) {
        String purchase = row.purchaseUnit() == null ? "" : row.purchaseUnit();
        return purchase + " of " + row.conversion() + " · " + (row.issueUnit() == null ? "" : row.issueUnit());
    }

}
