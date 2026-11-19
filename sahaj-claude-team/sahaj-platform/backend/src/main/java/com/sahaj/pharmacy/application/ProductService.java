package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyViews.DuplicateCandidate;
import com.sahaj.pharmacy.application.ProductRepository.Full;
import com.sahaj.pharmacy.application.ProductRepository.OwnFields;
import com.sahaj.pharmacy.application.ProductRepository.StoreSettingRow;
import com.sahaj.pharmacy.application.ProductRepository.VendorRef;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ProductKeys;
import com.sahaj.pharmacy.domain.ProductSchedule;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-5 and 78-6, the writes (owner only): adopt a catalogue product, create and edit an own product, save the local
 * settings of either kind. Every write takes the clinic's unit lock first, so the duplicate check, the barcode check, the own
 * product number and the version check run against a stable picture. Global fields of an adopted product are never copied
 * and never changed; a change to them is refused 400 {@code global-fields-read-only}.
 */
@Service
public class ProductService {

    static final Pattern HSN = Pattern.compile("^[0-9]{4,8}$");
    static final Pattern GST = Pattern.compile("^(100(\\.0{1,2})?|[0-9]{1,2}(\\.[0-9]{1,2})?)$");
    static final Pattern BARCODE = Pattern.compile("^[A-Za-z0-9-]{1,40}$");
    static final String BARCODE_MESSAGE = "Use up to 40 letters, digits or hyphens";
    static final String KEY_REUSED = "This request was already sent by someone else. Reload the page and try again.";
    static final String UNITS_LOCKED = "Locked because this product has stock.";
    static final String HAS_STOCK = "This product has stock. Clear it in Inventory first.";
    private static final String ENTITY_TYPE = "PHARMACY_PRODUCT";

    private final PharmacyGate gate;
    private final UnitService units;
    private final ProductRepository products;
    private final ProductAssembler assembler;
    private final DuplicateFinder duplicates;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public ProductService(PharmacyGate gate, UnitService units, ProductRepository products, ProductAssembler assembler,
                          DuplicateFinder duplicates, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.units = units;
        this.products = products;
        this.assembler = assembler;
        this.duplicates = duplicates;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- adopt ----

    /** Adopt is safe to repeat: an already adopted product answers 200 and nothing is written. */
    @Transactional
    public PharmacyViews.Outcome<PharmacyViews.ProductDetail> adopt(UUID tenantId, UUID userId, String rawCatalogueId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID catalogueId = PharmacyParams.pathId(rawCatalogueId);
        ProductRepository.CatalogueRef catalogue = products.catalogue(catalogueId)
                .orElseThrow(ProductService::notFound);
        Full existing = products.findAdopted(tenantId, catalogueId).orElse(null);
        if (existing != null) {
            return new PharmacyViews.Outcome<>(assembler.detail(tenantId, existing), false);
        }
        if (!catalogue.active()) {
            throw new ConflictException("product-withdrawn", "Product withdrawn",
                    "This product was withdrawn from the catalogue.");
        }
        Unit unit = units.ensureUnit(tenantId, userId);
        existing = products.findAdopted(tenantId, catalogueId).orElse(null);
        if (existing != null) {
            return new PharmacyViews.Outcome<>(assembler.detail(tenantId, existing), false);
        }
        UUID id = products.insertAdopted(tenantId, unit.id(), catalogueId, userId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("catalogueProductId", catalogueId.toString());
        after.put("catalogueCode", catalogue.code());
        auditLog.record("PHARMACY_PRODUCT_ADOPTED", ENTITY_TYPE, id, null, after);
        return new PharmacyViews.Outcome<>(assembler.detail(tenantId, products.find(tenantId, id).orElseThrow()), true);
    }

    // ---- create an own product ----

    /** For tests: the body is already read. */
    PharmacyViews.Outcome<PharmacyViews.ProductDetail> create(UUID tenantId, UUID userId, PharmacyInputs.OwnProduct input) {
        return create(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /** The body is read after the gate (module off 403, owner only 403). */
    @Transactional
    public PharmacyViews.Outcome<PharmacyViews.ProductDetail> create(UUID tenantId, UUID userId,
                                                                    PharmacyInputs.Body<PharmacyInputs.OwnProduct> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        PharmacyInputs.OwnProduct in = body.read();
        PharmacyViews.Outcome<PharmacyViews.ProductDetail> replay = replay(tenantId, userId, in.clientKey());
        if (replay != null) {
            return replay;
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        OwnFields fields = checkFields(in.name(), in.salt(), in.strength(), in.form(), in.purchaseUnit(), in.issueUnit(),
                in.conversion(), in.manufacturer(), in.madeInHouse(), in.hsn(), in.gstRate(), in.schedule(),
                in.barcode(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        Unit unit = units.ensureUnit(tenantId, userId);
        replay = replay(tenantId, userId, in.clientKey());
        if (replay != null) {
            return replay;
        }
        List<DuplicateCandidate> matches = duplicates.findByKey(tenantId, fields.dupKey(), null);
        boolean ignore = Boolean.TRUE.equals(in.ignoreDuplicates());
        if (!matches.isEmpty() && !ignore) {
            throw PharmacyProblemException.possibleDuplicate(matches);
        }
        requireFreeBarcode(tenantId, fields.barcode(), null);

        int number = products.takeOwnNumber(tenantId, unit.id(), userId);
        String code = "U-" + unit.code() + "-" + String.format(Locale.ROOT, "%04d", number);
        UUID id = products.insertOwn(tenantId, new ProductRepository.NewOwn(unit.id(), code, in.clientKey(), fields),
                userId);
        Map<String, Object> after = ownValues(fields);
        after.put("code", code);
        if (!matches.isEmpty()) {
            after.put("duplicateOfCodes", matches.stream().map(DuplicateCandidate::code).toList());
        }
        auditLog.record("PHARMACY_OWN_PRODUCT_CREATED", ENTITY_TYPE, id, null, after);
        return new PharmacyViews.Outcome<>(assembler.detail(tenantId, products.find(tenantId, id).orElseThrow()), true);
    }

    /** The same client key from the same caller answers the product again; from another caller it is refused. */
    private PharmacyViews.Outcome<PharmacyViews.ProductDetail> replay(UUID tenantId, UUID userId, UUID clientKey) {
        if (clientKey == null) {
            return null;
        }
        ProductRepository.ClientKeyHit hit = products.findByClientKey(tenantId, clientKey).orElse(null);
        if (hit == null) {
            return null;
        }
        if (!hit.createdBy().equals(userId)) {
            throw new ConflictException("client-key-reused", "Request already used", KEY_REUSED);
        }
        Full full = products.find(tenantId, hit.productId()).orElseThrow();
        return new PharmacyViews.Outcome<>(assembler.detail(tenantId, full), false);
    }

    // ---- edit an own product ----

    /** For tests: the body is already read. */
    PharmacyViews.ProductDetail update(UUID tenantId, UUID userId, String rawProductId,
                                       PharmacyInputs.OwnProductChange input) {
        return update(tenantId, userId, rawProductId, PharmacyInputs.Body.of(input));
    }

    @Transactional
    public PharmacyViews.ProductDetail update(UUID tenantId, UUID userId, String rawProductId,
                                              PharmacyInputs.Body<PharmacyInputs.OwnProductChange> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID productId = PharmacyParams.pathId(rawProductId);
        PharmacyInputs.OwnProductChange in = body.read();
        Full first = products.find(tenantId, productId).orElseThrow(ProductService::notFound);
        if (!first.own()) {
            throw PharmacyProblemException.globalFieldsReadOnly();
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.version() == null || in.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        OwnFields fields = checkFields(in.name(), in.salt(), in.strength(), in.form(), in.purchaseUnit(), in.issueUnit(),
                in.conversion(), in.manufacturer(), in.madeInHouse(), in.hsn(), in.gstRate(), in.schedule(),
                in.barcode(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        units.ensureUnit(tenantId, userId);
        // DEC-077 L10: the product row is locked before the open-import check (a line write reads it FOR SHARE)
        Full current = products.findForUpdate(tenantId, productId).orElseThrow(ProductService::notFound);
        if (in.version() != current.version()) {
            throw PharmacyProblemException.settingsChanged(assembler.detail(tenantId, current));
        }
        OwnFields before = ownFields(current);
        if (current.hasStock() && (!Objects.equals(before.purchaseUnit(), fields.purchaseUnit())
                || !Objects.equals(before.issueUnit(), fields.issueUnit()) || before.conversion() != fields.conversion())) {
            throw new ConflictException("units-locked", "Units are locked", UNITS_LOCKED);
        }
        boolean unitsChange = !Objects.equals(before.purchaseUnit(), fields.purchaseUnit())
                || !Objects.equals(before.issueUnit(), fields.issueUnit()) || before.conversion() != fields.conversion();
        if (unitsChange && products.onOpenImport(tenantId, productId)) {
            throw PharmacyProblemException.openImport();
        }
        List<DuplicateCandidate> matches = List.of();
        if (!before.dupKey().equals(fields.dupKey())) {
            matches = duplicates.findByKey(tenantId, fields.dupKey(), productId);
            if (!matches.isEmpty() && !Boolean.TRUE.equals(in.ignoreDuplicates())) {
                throw PharmacyProblemException.possibleDuplicate(matches);
            }
        }
        if (current.active() && !sameText(before.barcode(), fields.barcode())) {
            requireFreeBarcode(tenantId, fields.barcode(), productId);
        }
        if (before.equals(fields)) {
            return assembler.detail(tenantId, current);
        }
        if (!products.updateOwn(tenantId, productId, current.version(), fields, userId)) {
            throw PharmacyProblemException.settingsChanged(
                    assembler.detail(tenantId, products.find(tenantId, productId).orElse(current)));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        track(was, now, ownValues(before), ownValues(fields));
        if (!matches.isEmpty()) {
            now.put("duplicateOfCodes", matches.stream().map(DuplicateCandidate::code).toList());
        }
        auditLog.record("PHARMACY_PRODUCT_CHANGED", ENTITY_TYPE, productId, was, now);
        return assembler.detail(tenantId, products.find(tenantId, productId).orElseThrow());
    }

    // ---- local settings ----

    /** For tests: the body is already read. */
    PharmacyViews.ProductDetail saveLocalSettings(UUID tenantId, UUID userId, String rawProductId,
                                                  PharmacyInputs.LocalSettings input) {
        return saveLocalSettings(tenantId, userId, rawProductId, PharmacyInputs.Body.of(input));
    }

    @Transactional
    public PharmacyViews.ProductDetail saveLocalSettings(UUID tenantId, UUID userId, String rawProductId,
                                                         PharmacyInputs.Body<PharmacyInputs.LocalSettings> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID productId = PharmacyParams.pathId(rawProductId);
        PharmacyInputs.LocalSettings in = body.read();
        Full first = products.find(tenantId, productId).orElseThrow(ProductService::notFound);
        Checked checked = checkLocal(tenantId, in, first);

        Unit unit = units.ensureUnit(tenantId, userId);
        // DEC-077 L10: the product row is locked before the open-import check (a line write reads it FOR SHARE)
        Full current = products.findForUpdate(tenantId, productId).orElseThrow(ProductService::notFound);
        if (checked.version() != current.version()) {
            throw PharmacyProblemException.settingsChanged(assembler.detail(tenantId, current));
        }
        boolean activating = checked.active() && !current.active();
        boolean barcodeChanged = !sameText(current.barcode(), checked.barcode());
        if (checked.active() && checked.barcode() != null && (barcodeChanged || activating)) {
            requireFreeBarcode(tenantId, checked.barcode(), productId);
        }
        if (current.active() && !checked.active() && current.hasStock()) {
            throw new ConflictException("product-has-stock", "Product has stock", HAS_STOCK);
        }
        if (current.active() && !checked.active() && products.onOpenImport(tenantId, productId)) {
            throw PharmacyProblemException.openImport();
        }

        Map<UUID, StoreSettingRow> saved = new HashMap<>();
        products.storeSettings(tenantId, productId).forEach(s -> saved.put(s.storeId(), s));
        List<SettingChange> changes = new ArrayList<>();
        for (SettingInput s : checked.settings()) {
            StoreSettingRow was = saved.get(s.storeId());
            Integer wasLevel = was == null ? null : was.reorderLevel();
            String wasRack = was == null ? null : was.rack();
            if (!Objects.equals(wasLevel, s.reorderLevel()) || !Objects.equals(wasRack, s.rack())) {
                changes.add(new SettingChange(s, wasLevel, wasRack));
            }
        }
        boolean localChanged = !sameText(current.row().alias(), checked.alias()) || barcodeChanged
                || current.returnable() != checked.returnable()
                || !Objects.equals(current.preferredVendorId(), checked.vendorId())
                || current.active() != checked.active();
        if (!localChanged && changes.isEmpty()) {
            return assembler.detail(tenantId, current);
        }

        if (!products.updateLocal(tenantId, productId, current.version(), checked.alias(), checked.barcode(),
                checked.returnable(), checked.vendorId(), checked.active(), userId)) {
            throw PharmacyProblemException.settingsChanged(
                    assembler.detail(tenantId, products.find(tenantId, productId).orElse(current)));
        }
        for (SettingChange change : changes) {
            products.saveStoreSetting(tenantId, unit.id(), productId, change.input().storeId(),
                    change.input().reorderLevel(), change.input().rack(), userId);
        }
        audit(productId, current, checked, changes);
        return assembler.detail(tenantId, products.find(tenantId, productId).orElseThrow());
    }

    private void audit(UUID productId, Full current, Checked checked, List<SettingChange> changes) {
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        trackValue(was, now, "alias", current.row().alias(), checked.alias());
        trackValue(was, now, "barcode", current.barcode(), checked.barcode());
        trackValue(was, now, "returnable", current.returnable(), checked.returnable());
        trackValue(was, now, "preferredVendorId", current.preferredVendorId(), checked.vendorId());
        trackValue(was, now, "active", current.active(), checked.active());
        if (!changes.isEmpty()) {
            List<Map<String, Object>> wasSettings = new ArrayList<>();
            List<Map<String, Object>> nowSettings = new ArrayList<>();
            for (SettingChange change : changes) {
                wasSettings.add(settingValues(change.input().storeId(), change.wasLevel(), change.wasRack()));
                nowSettings.add(settingValues(change.input().storeId(), change.input().reorderLevel(),
                        change.input().rack()));
            }
            was.put("storeSettings", wasSettings);
            now.put("storeSettings", nowSettings);
        }
        auditLog.record("PHARMACY_LOCAL_SETTINGS_CHANGED", ENTITY_TYPE, productId, was, now);
        if (current.active() != checked.active()) {
            auditLog.record(checked.active() ? "PHARMACY_PRODUCT_SWITCHED_ON" : "PHARMACY_PRODUCT_SWITCHED_OFF",
                    ENTITY_TYPE, productId, Map.of("active", current.active()), Map.of("active", checked.active()));
        }
    }

    private static Map<String, Object> settingValues(UUID storeId, Integer level, String rack) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("reorderLevel", level);
        values.put("rack", rack);
        return values;
    }

    // ---- checks ----

    private record SettingInput(UUID storeId, Integer reorderLevel, String rack) {
    }

    private record SettingChange(SettingInput input, Integer wasLevel, String wasRack) {
    }

    private record Checked(long version, List<SettingInput> settings, UUID vendorId, String alias, String barcode,
                           boolean returnable, boolean active) {
    }

    /** All field errors together; nothing typed is echoed. */
    private Checked checkLocal(UUID tenantId, PharmacyInputs.LocalSettings in, Full product) {
        List<FieldMessage> errors = new ArrayList<>();
        if (in.version() == null || in.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        List<SettingInput> settings = new ArrayList<>();
        if (in.storeSettings() == null) {
            errors.add(new FieldMessage("storeSettings", UnitService.REQUIRED));
        } else if (in.storeSettings().size() > 200) {
            errors.add(new FieldMessage("storeSettings", "Send at most 200 stores"));
        } else {
            Set<UUID> activeStores = new HashSet<>();
            assembler.activeStores(tenantId).stream().map(Store::id).forEach(activeStores::add);
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < in.storeSettings().size(); i++) {
                checkSetting(i, in.storeSettings().get(i), activeStores, seen, settings, errors);
            }
        }
        UUID vendorId = Sent.valueOf(in.preferredVendorId());
        if (Sent.missing(in.preferredVendorId())) {
            errors.add(new FieldMessage("preferredVendorId", UnitService.REQUIRED));
        } else if (vendorId != null && !vendorId.equals(product.preferredVendorId())) {
            VendorRef vendor = products.vendor(tenantId, vendorId).orElse(null);
            if (vendor == null || !ProductAssembler.usable(vendor, time.today())) {
                errors.add(new FieldMessage("preferredVendorId", "Choose an active vendor"));
            }
        }
        String alias = null;
        if (Sent.missing(in.alias())) {
            errors.add(new FieldMessage("alias", UnitService.REQUIRED));
        } else {
            alias = blankToNull(Sent.valueOf(in.alias()));
            if (alias != null && alias.length() > 40) {
                errors.add(new FieldMessage("alias", "Keep the alias to 40 characters"));
            }
        }
        String barcode = null;
        if (Sent.missing(in.barcode())) {
            errors.add(new FieldMessage("barcode", UnitService.REQUIRED));
        } else {
            barcode = checkBarcode("barcode", Sent.valueOf(in.barcode()), errors);
        }
        if (in.returnable() == null) {
            errors.add(new FieldMessage("returnable", UnitService.REQUIRED));
        }
        if (in.active() == null) {
            errors.add(new FieldMessage("active", UnitService.REQUIRED));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Checked(in.version(), settings, vendorId, alias, barcode, in.returnable(), in.active());
    }

    private static void checkSetting(int index, PharmacyInputs.StoreSettingInput s, Set<UUID> activeStores, Set<UUID> seen,
                                     List<SettingInput> settings, List<FieldMessage> errors) {
        String at = "storeSettings[" + index + "]";
        if (s == null || s.storeId() == null) {
            errors.add(new FieldMessage(at + ".storeId", "Choose a store"));
            return;
        }
        if (!activeStores.contains(s.storeId())) {
            errors.add(new FieldMessage(at + ".storeId", "Choose an active store"));
        } else if (!seen.add(s.storeId())) {
            errors.add(new FieldMessage(at + ".storeId", "List each store once"));
        }
        Integer level = null;
        if (Sent.missing(s.reorderLevel())) {
            errors.add(new FieldMessage(at + ".reorderLevel", UnitService.REQUIRED));
        } else if (Sent.valueOf(s.reorderLevel()) != null) {
            level = PharmacyParams.wholeNumber(Sent.valueOf(s.reorderLevel()), 0, 999_999);
            if (level == null) {
                errors.add(new FieldMessage(at + ".reorderLevel", "Enter a whole number, 0 or more"));
            }
        }
        String rack = null;
        if (Sent.missing(s.rack())) {
            errors.add(new FieldMessage(at + ".rack", UnitService.REQUIRED));
        } else {
            rack = blankToNull(Sent.valueOf(s.rack()));
            if (rack != null && rack.length() > 30) {
                errors.add(new FieldMessage(at + ".rack", "Keep the rack to 30 characters"));
            }
        }
        settings.add(new SettingInput(s.storeId(), level, rack));
    }

    /** The fields of an own product, all errors together; the duplicate key is worked out here. Returns null fields never: errors are collected. */
    private static OwnFields checkFields(String name, String salt, String strength, String form, String purchaseUnit,
                                         String issueUnit, BigDecimal conversion, Sent<String> manufacturer,
                                         Boolean madeInHouse, String hsn, String gstRate, Sent<ProductSchedule> schedule,
                                         String barcode, List<FieldMessage> errors) {
        String cleanName = text("name", name, 80, "Enter the product name", errors);
        String cleanSalt = text("salt", salt, 80, "Enter the salt", errors);
        String cleanStrength = text("strength", strength, 80, "Enter the strength", errors);
        String cleanForm = text("form", form, 30, "Enter the form", errors);
        String cleanPurchase = text("purchaseUnit", purchaseUnit, 20, "Enter the purchase unit", errors);
        String cleanIssue = text("issueUnit", issueUnit, 20, "Enter the issue unit", errors);
        Integer units = PharmacyParams.wholeNumber(conversion, 1, 10_000);
        if (units == null) {
            errors.add(new FieldMessage("conversion", "Enter a whole number, 1 or more"));
        }
        String maker = null;
        if (madeInHouse == null) {
            errors.add(new FieldMessage("madeInHouse", UnitService.REQUIRED));
        }
        if (Sent.missing(manufacturer)) {
            errors.add(new FieldMessage("manufacturer", UnitService.REQUIRED));
        } else {
            String typed = blankToNull(Sent.valueOf(manufacturer));
            if (typed != null && typed.length() > 80) {
                errors.add(new FieldMessage("manufacturer", "Keep it to 80 characters"));
            } else if (Boolean.FALSE.equals(madeInHouse) && typed == null) {
                errors.add(new FieldMessage("manufacturer", "Enter the manufacturer or choose Made in-house"));
            } else if (Boolean.FALSE.equals(madeInHouse)) {
                maker = typed;
            }
        }
        String cleanHsn = hsn == null ? "" : hsn.trim();
        if (!HSN.matcher(cleanHsn).matches()) {
            errors.add(new FieldMessage("hsn", "Enter a 4 to 8 digit HSN"));
        }
        String cleanGst = gstRate == null ? "" : gstRate.trim();
        BigDecimal gst = null;
        if (GST.matcher(cleanGst).matches()) {
            gst = new BigDecimal(cleanGst).setScale(2);
        } else {
            errors.add(new FieldMessage("gstRate", "Enter a GST % from 0 to 100"));
        }
        ProductSchedule chosen = Sent.valueOf(schedule);
        if (chosen == null) {
            errors.add(new FieldMessage("schedule", "Choose the schedule"));
        }
        String cleanBarcode = checkBarcode("barcode", barcode, errors);
        if (!errors.isEmpty()) {
            return null;
        }
        return new OwnFields(cleanName, cleanSalt, cleanStrength, cleanForm, maker, Boolean.TRUE.equals(madeInHouse),
                cleanHsn, gst, chosen.name(), cleanPurchase, cleanIssue, units,
                ProductKeys.dupKey(cleanSalt, cleanStrength, cleanForm), cleanBarcode);
    }

    private static String text(String field, String raw, int max, String empty, List<FieldMessage> errors) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            errors.add(new FieldMessage(field, empty));
        } else if (value.length() > max) {
            errors.add(new FieldMessage(field, "Keep it to " + max + " characters"));
        }
        return value;
    }

    private static String checkBarcode(String field, String raw, List<FieldMessage> errors) {
        String value = blankToNull(raw);
        if (value != null && !BARCODE.matcher(value).matches()) {
            errors.add(new FieldMessage(field, BARCODE_MESSAGE));
            return null;
        }
        return value;
    }

    private void requireFreeBarcode(UUID tenantId, String barcode, UUID exceptProductId) {
        if (barcode == null) {
            return;
        }
        products.barcodeOwner(tenantId, barcode, exceptProductId).ifPresent(owner -> {
            throw new ConflictException("barcode-in-use", "Barcode already used",
                    "That barcode is already used by " + owner);
        });
    }

    // ---- helpers ----

    private static OwnFields ownFields(Full f) {
        ProductRepository.Row r = f.row();
        return new OwnFields(r.name(), r.salt(), r.strength(), r.form(), r.manufacturer(), r.madeInHouse(), r.hsn(),
                r.gstRate(), r.schedule(), r.purchaseUnit(), r.issueUnit(), r.conversion(),
                ProductKeys.dupKey(r.salt(), r.strength(), r.form()), f.barcode());
    }

    private static Map<String, Object> ownValues(OwnFields f) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", f.name());
        values.put("salt", f.salt());
        values.put("strength", f.strength());
        values.put("form", f.form());
        values.put("purchaseUnit", f.purchaseUnit());
        values.put("issueUnit", f.issueUnit());
        values.put("conversion", f.conversion());
        values.put("manufacturer", f.manufacturer());
        values.put("madeInHouse", f.madeInHouse());
        values.put("hsn", f.hsn());
        values.put("gstRate", f.gstRate() == null ? null : ProductAssembler.percent(f.gstRate()));
        values.put("schedule", f.schedule());
        values.put("barcode", f.barcode());
        return values;
    }

    /** Only the values that differ. */
    private static void track(Map<String, Object> was, Map<String, Object> now, Map<String, Object> before,
                              Map<String, Object> after) {
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                was.put(entry.getKey(), before.get(entry.getKey()));
                now.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void trackValue(Map<String, Object> was, Map<String, Object> now, String field, Object before,
                                   Object after) {
        if (!Objects.equals(before, after)) {
            was.put(field, before == null ? null : before.toString());
            now.put(field, after == null ? null : after.toString());
        }
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : b != null && a.equalsIgnoreCase(b);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
