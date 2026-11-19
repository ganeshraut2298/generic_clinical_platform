package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyViews.DuplicateCandidate;
import com.sahaj.pharmacy.domain.ProductKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The duplicate check of stories 78-5 and 78-6 (OQ-78-17, ADR-028 D3): ACTIVE catalogue products with the same salt,
 * strength and form first, then the unit's ACTIVE own products, at most ten. The answer is advice only; the database never
 * rejects on it.
 */
@Component
class DuplicateFinder {

    static final int MAX = 10;

    private final ProductRepository products;

    DuplicateFinder(ProductRepository products) {
        this.products = products;
    }

    List<DuplicateCandidate> find(UUID tenantId, String salt, String strength, String form, UUID exceptProductId) {
        return findByKey(tenantId, ProductKeys.dupKey(salt, strength, form), exceptProductId);
    }

    List<DuplicateCandidate> findByKey(UUID tenantId, String dupKey, UUID exceptProductId) {
        List<DuplicateCandidate> found = new ArrayList<>(products.duplicatesInCatalogue(tenantId, dupKey, MAX));
        if (found.size() < MAX) {
            found.addAll(products.duplicatesOwn(tenantId, dupKey, exceptProductId, MAX - found.size()));
        }
        return List.copyOf(found);
    }
}
