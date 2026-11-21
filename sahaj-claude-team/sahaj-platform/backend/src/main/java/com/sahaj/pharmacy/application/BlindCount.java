package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.StoreCapability;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Rule 24, the blind count (docs/api/pharmacy.yaml 0.3.6, ADR-029 P12 and note N3, BR-30). While a store has an open stock audit
 * (COUNTING, RECOUNT, WAITING_REVIEW or WAITING_APPROVAL), a caller who can count there but cannot review the count (a mapped Store
 * user, Central Store user or pharmacist who is not the HOD or the current delegate; never the owner) must not learn the system
 * quantity of any product on that audit, so every stock read answers such a quantity as null. The rule is worked out on each call,
 * from the audit as it is at that moment, and it ends when the audit is CLOSED. A caller blind at one store sees another store's
 * quantities as usual. Reads the audit only when the caller is blind, so a reviewer pays nothing for it.
 */
@Component
public class BlindCount {

    /** The quantity message for a blind caller (0.3.6): it carries no number, so the validation cannot be used to read stock. */
    public static final String MORE_THAN_IN_STOCK = "This is more than the quantity in stock.";

    /** The audited products a caller must not see quantities of, at one store. Empty when the caller is not blind. */
    public record Mask(Set<UUID> products) {

        public static final Mask NONE = new Mask(Set.of());

        /** Whether any product at the store is hidden (the store's value and the totals are then hidden too). */
        public boolean active() {
            return !products.isEmpty();
        }

        public boolean hides(UUID productId) {
            return products.contains(productId);
        }

        /** The quantity, or null when it belongs to an audited product the caller may not read. */
        public Integer qty(UUID productId, int value) {
            return hides(productId) ? null : Integer.valueOf(value);
        }
    }

    private final StockAuditRepository audits;

    public BlindCount(StockAuditRepository audits) {
        this.audits = audits;
    }

    /** The caller's access there has readStock true and reviewCount false (a CASHIER, whose count is false, is blind too: S16). */
    public static boolean blindAt(PharmacyCaller caller, UUID storeId) {
        return StoreAccessGuard.holds(caller, storeId, StoreCapability.READ_STOCK)
                && !StoreAccessGuard.holds(caller, storeId, StoreCapability.REVIEW_COUNT);
    }

    /** What this caller may not read at this store now. */
    public Mask at(PharmacyCaller caller, UUID storeId) {
        if (storeId == null || !blindAt(caller, storeId)) {
            return Mask.NONE;
        }
        Set<UUID> products = audits.openAuditProducts(caller.tenantId(), storeId);
        return products.isEmpty() ? Mask.NONE : new Mask(products);
    }

    /** True when the caller is blind at the store for this product now. */
    public boolean hides(PharmacyCaller caller, UUID storeId, UUID productId) {
        return at(caller, storeId).hides(productId);
    }

    /** The insufficient-stock number of a movement: null when it would show an audited product's quantity to a blind caller. */
    public Integer available(PharmacyCaller caller, UUID storeId, UUID productId, int available) {
        return at(caller, storeId).qty(productId, available);
    }

    /**
     * An insufficient-stock refusal of a movement out of {@code storeId}, as the caller may see it: the usable quantity of a line
     * whose product is on the store's open audit is null for a blind caller (rule 24), as is the problem's own {@code available}
     * when its first line is such a line. Any other problem, and any caller who is not blind, gets the same exception back.
     */
    @SuppressWarnings("unchecked")
    public RuntimeException scrub(PharmacyCaller caller, UUID storeId, RuntimeException problem) {
        if (!(problem instanceof PharmacyProblemException refusal) || !"insufficient-stock".equals(refusal.slug())
                || !(refusal.extensions().get("lines") instanceof java.util.List<?> given)) {
            return problem;
        }
        Mask mask = at(caller, storeId);
        if (!mask.active()) {
            return problem;
        }
        java.util.List<PharmacyProblemException.FailingLine> lines = (java.util.List<PharmacyProblemException.FailingLine>) given;
        java.util.List<PharmacyProblemException.FailingLine> scrubbed = new java.util.ArrayList<>();
        for (PharmacyProblemException.FailingLine line : lines) {
            boolean hidden = mask.hides(line.productId());
            scrubbed.add(hidden ? new PharmacyProblemException.FailingLine(line.lineId(), line.rowNo(), line.productId(), null)
                    : line);
        }
        Integer available = scrubbed.isEmpty() ? null : scrubbed.get(0).available();
        return PharmacyProblemException.insufficientStock(available, scrubbed);
    }
}
