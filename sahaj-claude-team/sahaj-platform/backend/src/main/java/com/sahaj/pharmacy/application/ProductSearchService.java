package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ProductRepository.Full;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-5 and 78-6, the reads: the product list (global catalogue and own products together), one product, the duplicate
 * check without saving, and the unit and form suggestions. Writes are in {@link ProductService}. Path ids and query values
 * arrive as text and are read after the gate (module off 403, access level 403, then 400, then 404). The search text is
 * never logged and never part of the SQL.
 */
@Service
public class ProductSearchService {

    static final int Q_MAX = 60;
    static final int Q_MIN_FILTER = 2;
    static final int DEFAULT_SIZE = 25;

    private static final List<String> STARTER_UNITS = List.of("Box", "Strip", "Bottle", "Vial", "Ampoule", "Tablet",
            "Capsule", "Kit", "Tube", "Sachet");
    private static final List<String> STARTER_FORMS = List.of("tablet", "capsule", "syrup", "injection", "infusion",
            "ointment", "cream", "drops", "inhaler", "kit", "device", "consumable");

    /** The {@code source} filter of the list. */
    enum Source {
        GLOBAL,
        OWN
    }

    private final PharmacyGate gate;
    private final ProductRepository products;
    private final ProductAssembler assembler;
    private final DuplicateFinder duplicates;

    public ProductSearchService(PharmacyGate gate, ProductRepository products, ProductAssembler assembler,
                                DuplicateFinder duplicates) {
        this.gate = gate;
        this.products = products;
        this.assembler = assembler;
        this.duplicates = duplicates;
    }

    @Transactional(readOnly = true)
    public PharmacyViews.ProductPage search(UUID tenantId, UUID userId, String rawQ, String rawSource, String rawPage,
                                            String rawSize) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        String q = rawQ == null ? "" : rawQ.trim();
        if (q.length() > Q_MAX) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type at most 60 characters.")));
        }
        Source source = PharmacyParams.optionalEnum("source", rawSource, Source.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 2000);
        int size = PharmacyParams.intValue("size", rawSize, DEFAULT_SIZE, 1, 50);

        String exact = q.length() >= Q_MIN_FILTER ? q.toLowerCase(Locale.ROOT) : "";
        String like = exact.isEmpty() ? "" : "%" + escapeLike(exact) + "%";
        ProductRepository.Slice<ProductRepository.Row> slice = products.search(tenantId, like, exact,
                source == null ? "" : source.name(), size, page * size);
        return new PharmacyViews.ProductPage(slice.items().stream().map(ProductAssembler::row).toList(), slice.total(),
                page, size);
    }

    @Transactional(readOnly = true)
    public PharmacyViews.ProductDetail get(UUID tenantId, UUID userId, String rawProductId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        UUID productId = PharmacyParams.pathId(rawProductId);
        Full full = products.find(tenantId, productId).orElseThrow(ProductSearchService::notFound);
        return assembler.detail(tenantId, full);
    }

    @Transactional(readOnly = true)
    public PharmacyViews.Duplicates duplicates(UUID tenantId, UUID userId, String rawSalt, String rawStrength,
                                               String rawForm, String rawExclude) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        List<FieldMessage> errors = new ArrayList<>();
        String salt = required("salt", rawSalt, 80, "Enter the salt", errors);
        String strength = required("strength", rawStrength, 80, "Enter the strength", errors);
        String form = required("form", rawForm, 30, "Enter the form", errors);
        UUID exclude = null;
        if (rawExclude != null && !rawExclude.isEmpty()) {
            try {
                exclude = UUID.fromString(rawExclude);
            } catch (IllegalArgumentException notAUuid) {
                errors.add(new FieldMessage("excludeProductId", "Choose a valid option"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new PharmacyViews.Duplicates(duplicates.find(tenantId, salt, strength, form, exclude));
    }

    /** The starter list plus what the unit's own products already use, A to Z, each value once (ignoring case). */
    @Transactional(readOnly = true)
    public PharmacyViews.ProductOptions options(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return new PharmacyViews.ProductOptions(merge(STARTER_UNITS, products.usedUnits(tenantId)),
                merge(STARTER_FORMS, products.usedForms(tenantId)));
    }

    private static List<String> merge(List<String> starter, List<String> used) {
        Map<String, String> byLower = new TreeMap<>();
        for (String value : starter) {
            byLower.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        for (String value : used) {
            byLower.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        return List.copyOf(byLower.values());
    }

    private static String required(String field, String raw, int max, String empty, List<FieldMessage> errors) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            errors.add(new FieldMessage(field, empty));
        } else if (value.length() > max) {
            errors.add(new FieldMessage(field, "Keep it to " + max + " characters"));
        }
        return value;
    }

    /** Makes {@code %}, {@code _} and the escape character itself literal in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
