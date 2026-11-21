package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ProductKeys;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-7, the exact substitute card (docs/api/pharmacy-sales.yaml pharmacySubstituteOptions; DEC-074 OQ-80-23). A pharmacist
 * member only: the cashier and the owner answer 403 {@code pharmacist-required}. An option is another ACTIVE product of the unit with
 * the same normalised salt, strength and form key, the same schedule, and AVAILABLE unexpired stock in this store; at most 20, by MRP
 * then name. The margin is the MRP less the batch's landed rate and is shown because the caller is a pharmacist. A blind caller
 * (rule 32) gets a null stock for an audited product. A read-write transaction on purpose: it runs the expiry sweep first (P4).
 */
@Service
public class SubstituteService {

    static final String PHARMACIST_ONLY = "A pharmacist must substitute.";
    static final String ONLY_EXACT = "Only exact equivalents are offered.";
    static final int MAX_OPTIONS = 20;
    private static final int CANDIDATE_CAP = 200;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final DispatchRepository dispatches;
    private final BlindCount blind;
    private final PharmacyTime time;

    public SubstituteService(PharmacyGate gate, StoreAccessGuard guard, ProductRepository products, StockRepository stock,
                             StockPosting posting, DispatchRepository dispatches, BlindCount blind, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.dispatches = dispatches;
        this.blind = blind;
        this.time = time;
    }

    @Transactional
    public SaleCheckViews.SubstituteOptions options(UUID tenantId, UUID userId, String rawStoreId, String rawProductId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID productId = PharmacyParams.pathId(rawProductId);
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        if (!StoreAccessGuard.holds(caller, storeId, StoreCapability.SUBSTITUTE)) {
            throw PharmacyProblemException.pharmacistRequired(PHARMACIST_ONLY);
        }
        ProductRepository.Row product = products.find(tenantId, productId).map(ProductRepository.Full::row)
                .orElseThrow(() -> new NotFoundException(SaleSupport.NOT_FOUND));
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.actorId());
        LocalDate today = time.today();
        String key = ProductKeys.dupKey(product.salt(), product.strength(), product.form());
        List<SaleCheckViews.SubstituteOption> items = new ArrayList<>();
        BlindCount.Mask mask = blind.at(caller, storeId);
        for (UUID candidate : dispatches.replacementCandidates(tenantId, productId, CANDIDATE_CAP)) {
            ProductRepository.Row other = products.find(tenantId, candidate).map(ProductRepository.Full::row).orElse(null);
            if (other == null || !key.equals(ProductKeys.dupKey(other.salt(), other.strength(), other.form()))) {
                continue;
            }
            List<Bal> live = stock.availableOfProduct(tenantId, storeId, candidate).stream()
                    .filter(b -> b.state() == StockState.AVAILABLE && b.qty() > 0 && !b.expiryDate().isBefore(today)).toList();
            if (live.isEmpty()) {
                continue;
            }
            Bal first = live.stream().min(Comparator.comparing(Bal::expiryDate).thenComparing(Bal::firstReceivedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))).orElseThrow();
            int available = live.stream().mapToInt(Bal::qty).sum();
            items.add(new SaleCheckViews.SubstituteOption(IndentService.productRef(other), SaleRules.rate(first.mrp()),
                    mask.qty(candidate, available), margin(first), new SaleCheckViews.SubstituteChecks(true,
                    other.strength() == null ? "" : other.strength(), other.form() == null ? "" : other.form())));
        }
        items.sort(Comparator.comparing((SaleCheckViews.SubstituteOption o) -> new BigDecimal(o.mrp()))
                .thenComparing(o -> o.product().name()));
        List<SaleCheckViews.SubstituteOption> shown = items.size() > MAX_OPTIONS ? items.subList(0, MAX_OPTIONS) : items;
        boolean out = stock.availableOfProduct(tenantId, storeId, productId).stream()
                .noneMatch(b -> b.state() == StockState.AVAILABLE && b.qty() > 0 && !b.expiryDate().isBefore(today));
        String sentence = out ? product.name() + " is out of stock. " + ONLY_EXACT : ONLY_EXACT;
        return new SaleCheckViews.SubstituteOptions(IndentService.productRef(product), out, sentence, List.copyOf(shown));
    }

    /** The MRP less the landed rate of the batch, as a rate; null when the batch has no rate (OQ-79-6). */
    static String margin(Bal batch) {
        if (batch.purchaseRate() == null || batch.purchaseRate().signum() <= 0) {
            return null;
        }
        return SaleRules.rate(batch.mrp().subtract(batch.purchaseRate()));
    }
}
