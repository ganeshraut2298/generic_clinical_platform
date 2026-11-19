package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.VendorCreate;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.VendorUpdate;
import com.sahaj.pharmacy.application.PharmacyMasterViews.StoreName;
import com.sahaj.pharmacy.application.VendorRepository.ClientKeyHit;
import com.sahaj.pharmacy.application.VendorRepository.Fields;
import com.sahaj.pharmacy.application.VendorRepository.Filter;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * SAH-78-8: the vendor master (docs/api/pharmacy.yaml 0.2.0). Everyone who can read setup reads it; only the owner writes. A
 * vendor is never deleted, only switched off. "Blocked" (licence expired) is worked out from the licence valid-till date in IST
 * on every read and is never stored, so no job is needed. The contact phone, the licence number and the GSTIN are business
 * data: they are never logged and only the audit rows may hold them.
 */
@Service
public class VendorService {

    static final Pattern PERCENT = Pattern.compile("^(100(\\.0{1,2})?|[0-9]{1,2}(\\.[0-9]{1,2})?)$");
    static final Pattern PHONE = Pattern.compile("^[0-9]{10}$");
    static final String CHOOSE_STORES = "Choose active stores";
    static final String CREDIT_DAYS = "Enter 0 to 365 days";
    static final String PERCENT_MESSAGE = "Enter a % from 0 to 100";
    private static final String ENTITY_TYPE = "PHARMACY_VENDOR";
    private static final int NAME_MAX = 120;
    private static final int LICENCE_MAX = 40;
    private static final int CONTACT_MAX = 80;
    private static final int Q_MAX = 60;
    private static final int STORES_MAX = 200;

    private final PharmacyGate gate;
    private final UnitService units;
    private final VendorRepository vendors;
    private final StoreRepository stores;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public VendorService(PharmacyGate gate, UnitService units, VendorRepository vendors, StoreRepository stores,
                         AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.units = units;
        this.vendors = vendors;
        this.stores = stores;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- reads ----

    /** One page by name. The default filter is ACTIVE and BLOCKED; INACTIVE vendors are shown only when asked for. Writes nothing. */
    public PharmacyMasterViews.VendorPage list(UUID tenantId, UUID userId, String rawQ, String rawStatus, String rawPage,
                                               String rawSize) {
        return list(tenantId, userId, rawQ, rawStatus, null, rawPage, rawSize);
    }

    /**
     * 0.3.3: {@code suppliesStoreId} keeps the vendors that supply that store; a caller without SETUP_VIEW who may buy at that
     * store (the owner, or a Central Store user mapped to it) may read this list for that store only, else 403. An unknown or
     * foreign store answers an empty page.
     */
    @Transactional(readOnly = true)
    public PharmacyMasterViews.VendorPage list(UUID tenantId, UUID userId, String rawQ, String rawStatus,
                                               String rawSuppliesStoreId, String rawPage, String rawSize) {
        boolean forStore = rawSuppliesStoreId != null && !rawSuppliesStoreId.isEmpty();
        PharmacyCaller caller = gate.require(tenantId, userId, forStore ? AccessLevel.PHARMACY_ACCESS : AccessLevel.SETUP_VIEW);
        UUID suppliesStoreId = PharmacyParams.optionalUuid("suppliesStoreId", rawSuppliesStoreId, "Choose a valid store");
        if (suppliesStoreId != null && !caller.meets(AccessLevel.SETUP_VIEW) && !mayBuyAt(caller, suppliesStoreId)) {
            throw new ForbiddenException("forbidden", "Not allowed", PharmacyGate.NOT_ALLOWED);
        }
        String q = rawQ == null ? "" : rawQ.trim();
        if (q.length() > Q_MAX) {
            throw invalid("q", "Use up to 60 characters");
        }
        Filter filter = filter(rawStatus);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 25, 1, 100);
        String like = q.isEmpty() ? "" : "%" + ProductSearchService.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        LocalDate today = time.today();
        VendorRepository.Slice slice = suppliesStoreId == null ? vendors.list(tenantId, like, filter, today, size, page * size)
                : vendors.listSupplying(tenantId, like, filter, today, suppliesStoreId, size, page * size);
        boolean full = seesFullRecord(caller);
        Map<UUID, List<StoreName>> supplies = !full ? Map.of() : vendors.suppliesTo(tenantId,
                slice.items().stream().map(row -> row.vendor().id()).toList());
        List<PharmacyMasterViews.Vendor> items = slice.items().stream()
                .map(row -> full ? view(row.vendor(), row.productsCount(), supplies.getOrDefault(row.vendor().id(), List.of()), today)
                        : reduced(row.vendor(), today))
                .toList();
        return new PharmacyMasterViews.VendorPage(items, slice.total(), page, size);
    }

    private static boolean mayBuyAt(PharmacyCaller caller, UUID storeId) {
        return caller.isOwner() || caller.mappingFor(storeId).map(m -> m.effective() == StoreRole.CENTRAL_STORE_USER).orElse(false);
    }

    /**
     * DEC-082 (security M3): the view is shaped by who reads. An owner or a pharmacist member gets the full record; a caller whose
     * vendor read comes only through a Central Store user mapping gets the reduced one (see {@link PharmacyMasterViews.Vendor#reduced}).
     */
    static boolean seesFullRecord(PharmacyCaller caller) {
        return caller.isOwner() || caller.isPharmacist();
    }

    static PharmacyMasterViews.Vendor reduced(com.sahaj.pharmacy.domain.Vendor v, LocalDate today) {
        return PharmacyMasterViews.Vendor.reduced(v.id(), v.name(), v.status(today), v.blocked(today), v.version());
    }

    @Transactional(readOnly = true)
    public PharmacyMasterViews.Vendor get(UUID tenantId, UUID userId, String rawVendorId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        UUID id = PharmacyParams.pathId(rawVendorId);
        if (!seesFullRecord(caller)) {
            return reduced(vendors.find(tenantId, id).orElseThrow(VendorService::notFound), time.today());
        }
        return one(tenantId, id);
    }

    // ---- create ----

    /** For tests: the body is already read. */
    PharmacyViews.Outcome<PharmacyMasterViews.Vendor> create(UUID tenantId, UUID userId, VendorCreate input) {
        return create(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /** The body is read after the gate (module off 403, owner only 403). The same client key from the same caller answers 200. */
    @Transactional
    public PharmacyViews.Outcome<PharmacyMasterViews.Vendor> create(UUID tenantId, UUID userId,
                                                                   PharmacyInputs.Body<VendorCreate> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        VendorCreate in = body.read();
        PharmacyViews.Outcome<PharmacyMasterViews.Vendor> replay = replay(tenantId, userId, in.clientKey());
        if (replay != null) {
            return replay;
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Checked checked = check(tenantId, new Common(in.name(), in.gstin(), in.licenceRequired(), in.licenceNo(),
                in.licenceValidTill(), in.contactName(), in.contactPhone(), in.creditDays(), in.returnDaysBeforeExpiry(),
                in.returnCreditPercent(), in.damagedCreditPercent(), in.expiredCreditPercent(),
                in.suppliesToStoreIds()), errors);

        Unit unit = units.ensureUnit(tenantId, userId);
        replay = replay(tenantId, userId, in.clientKey());
        if (replay != null) {
            return replay;
        }
        if (vendors.nameInUse(tenantId, checked.fields().name(), null)) {
            throw nameInUse();
        }
        UUID id = vendors.insert(tenantId, unit.id(), in.clientKey(), checked.fields(), userId);
        vendors.replaceStores(tenantId, unit.id(), id, checked.storeIds(), userId);
        auditLog.record("PHARMACY_VENDOR_CREATED", ENTITY_TYPE, id, null, values(checked.fields(), checked.storeIds()));
        return new PharmacyViews.Outcome<>(one(tenantId, id), true);
    }

    private PharmacyViews.Outcome<PharmacyMasterViews.Vendor> replay(UUID tenantId, UUID userId, UUID clientKey) {
        if (clientKey == null) {
            return null;
        }
        ClientKeyHit hit = vendors.findByClientKey(tenantId, clientKey).orElse(null);
        if (hit == null) {
            return null;
        }
        if (!hit.createdBy().equals(userId)) {
            throw new ConflictException("client-key-reused", "Request already used", ProductService.KEY_REUSED);
        }
        return new PharmacyViews.Outcome<>(one(tenantId, hit.vendorId()), false);
    }

    // ---- edit ----

    /** For tests: the body is already read. */
    PharmacyMasterViews.Vendor update(UUID tenantId, UUID userId, String rawVendorId, VendorUpdate input) {
        return update(tenantId, userId, rawVendorId, PharmacyInputs.Body.of(input));
    }

    /**
     * Same rules as create plus the version read. A licence date moved to today or later makes the vendor Active at once
     * (nothing is stored for Blocked). An INACTIVE vendor may be edited and stays INACTIVE. A save that changes nothing
     * writes nothing.
     */
    @Transactional
    public PharmacyMasterViews.Vendor update(UUID tenantId, UUID userId, String rawVendorId,
                                             PharmacyInputs.Body<VendorUpdate> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID id = PharmacyParams.pathId(rawVendorId);
        VendorUpdate in = body.read();
        vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        if (in.version() == null || in.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        Checked checked = check(tenantId, new Common(in.name(), in.gstin(), in.licenceRequired(), in.licenceNo(),
                in.licenceValidTill(), in.contactName(), in.contactPhone(), in.creditDays(), in.returnDaysBeforeExpiry(),
                in.returnCreditPercent(), in.damagedCreditPercent(), in.expiredCreditPercent(),
                in.suppliesToStoreIds()), errors);

        Unit unit = units.ensureUnit(tenantId, userId);
        com.sahaj.pharmacy.domain.Vendor current = vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        if (current.version() != in.version()) {
            throw PharmacyProblemException.settingsChanged(one(tenantId, id));
        }
        if (vendors.nameInUse(tenantId, checked.fields().name(), id)) {
            throw nameInUse();
        }
        List<UUID> currentStores = vendors.suppliesTo(tenantId, List.of(id)).getOrDefault(id, List.of()).stream()
                .map(StoreName::storeId).toList();
        Fields now = checked.fields();
        // an edit that leaves a credit level out keeps what is stored; sending null clears it
        now = new Fields(now.name(), now.gstin(), now.licenceRequired(), now.licenceNo(), now.licenceValidTill(),
                now.contactName(), now.contactPhone(), now.creditDays(), now.returnDaysBeforeExpiry(),
                now.returnCreditPercent(),
                Sent.missing(in.damagedCreditPercent()) ? current.damagedCreditPercent() : now.damagedCreditPercent(),
                Sent.missing(in.expiredCreditPercent()) ? current.expiredCreditPercent() : now.expiredCreditPercent());
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> was = values(fieldsOf(current), currentStores);
        Map<String, Object> will = values(now, checked.storeIds());
        was.forEach((key, value) -> {
            if (!Objects.equals(value, will.get(key))) {
                before.put(key, value);
                after.put(key, will.get(key));
            }
        });
        if (before.isEmpty()) {
            return one(tenantId, id);
        }
        if (!vendors.update(tenantId, id, current.version(), now, userId)) {
            throw PharmacyProblemException.settingsChanged(one(tenantId, id));
        }
        vendors.replaceStores(tenantId, unit.id(), id, checked.storeIds(), userId);
        auditLog.record("PHARMACY_VENDOR_CHANGED", ENTITY_TYPE, id, before, after);
        return one(tenantId, id);
    }

    // ---- switch off ----

    /** Status INACTIVE: not offered for new work or as a preferred vendor. Already INACTIVE answers 200 and writes nothing. */
    @Transactional
    public PharmacyMasterViews.Vendor switchOff(UUID tenantId, UUID userId, String rawVendorId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID id = PharmacyParams.pathId(rawVendorId);
        vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        units.ensureUnit(tenantId, userId);
        com.sahaj.pharmacy.domain.Vendor current = vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        if (current.active()) {
            vendors.switchOff(tenantId, id, userId);
            auditLog.record("PHARMACY_VENDOR_SWITCHED_OFF", ENTITY_TYPE, id, Map.of("status", "ACTIVE"),
                    Map.of("status", "INACTIVE"));
        }
        return one(tenantId, id);
    }

    // ---- switch on ----

    /**
     * An INACTIVE vendor becomes ACTIVE again (BLOCKED when its licence date has passed: that is computed, never stored).
     * Already ACTIVE or BLOCKED answers 200 and writes nothing (0.2.1, DEC-078 item 7).
     */
    @Transactional
    public PharmacyMasterViews.Vendor switchOn(UUID tenantId, UUID userId, String rawVendorId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID id = PharmacyParams.pathId(rawVendorId);
        vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        units.ensureUnit(tenantId, userId);
        com.sahaj.pharmacy.domain.Vendor current = vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        if (!current.active()) {
            vendors.switchOn(tenantId, id, userId);
            com.sahaj.pharmacy.domain.Vendor now = vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
            auditLog.record("PHARMACY_VENDOR_SWITCHED_ON", ENTITY_TYPE, id, Map.of("status", "INACTIVE"),
                    Map.of("status", now.status(time.today())));
        }
        return one(tenantId, id);
    }

    // ---- views ----

    private PharmacyMasterViews.Vendor one(UUID tenantId, UUID id) {
        com.sahaj.pharmacy.domain.Vendor vendor = vendors.find(tenantId, id).orElseThrow(VendorService::notFound);
        return view(vendor, vendors.productsCount(tenantId, id),
                vendors.suppliesTo(tenantId, List.of(id)).getOrDefault(id, List.of()), time.today());
    }

    static PharmacyMasterViews.Vendor view(com.sahaj.pharmacy.domain.Vendor v, int productsCount, List<StoreName> supplies,
                                           LocalDate today) {
        return new PharmacyMasterViews.Vendor(v.id(), v.name(), v.gstin(), v.licenceRequired(), v.licenceNo(),
                v.licenceValidTill(), v.licenceState(today).name(), v.contactName(), v.contactPhone(), v.creditDays(),
                v.returnDaysBeforeExpiry(), ProductAssembler.percent(v.returnCreditPercent()),
                ProductAssembler.percent(v.damagedCreditPercent()), ProductAssembler.percent(v.expiredCreditPercent()),
                supplies, productsCount,
                v.status(today), v.blocked(today), v.version());
    }

    private static Fields fieldsOf(com.sahaj.pharmacy.domain.Vendor v) {
        return new Fields(v.name(), v.gstin(), v.licenceRequired(), v.licenceNo(), v.licenceValidTill(),
                v.contactName(), v.contactPhone(), v.creditDays(), v.returnDaysBeforeExpiry(),
                v.returnCreditPercent(), v.damagedCreditPercent(), v.expiredCreditPercent());
    }

    /** The audit and the change check compare these (text values; the phone is masked by the audit port). */
    private static Map<String, Object> values(Fields f, List<UUID> storeIds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", f.name());
        map.put("gstin", f.gstin());
        map.put("licenceRequired", f.licenceRequired());
        map.put("licenceNo", f.licenceNo());
        map.put("licenceValidTill", f.licenceValidTill() == null ? null : f.licenceValidTill().toString());
        map.put("contactName", f.contactName());
        map.put("contactPhone", f.contactPhone());
        map.put("creditDays", f.creditDays());
        map.put("returnDaysBeforeExpiry", f.returnDaysBeforeExpiry());
        map.put("returnCreditPercent", ProductAssembler.percent(f.returnCreditPercent()));
        map.put("damagedCreditPercent", ProductAssembler.percent(f.damagedCreditPercent()));
        map.put("expiredCreditPercent", ProductAssembler.percent(f.expiredCreditPercent()));
        map.put("suppliesToStoreIds", storeIds.stream().map(UUID::toString).sorted().toList().toString());
        return map;
    }

    // ---- checks ----

    private record Common(String name, Sent<String> gstin, Boolean licenceRequired, Sent<String> licenceNo,
                          Sent<LocalDate> licenceValidTill, Sent<String> contactName, Sent<String> contactPhone,
                          BigDecimal creditDays, Sent<BigDecimal> returnDays, Sent<String> returnPercent,
                          Sent<String> damagedPercent, Sent<String> expiredPercent, List<UUID> storeIds) {
    }

    private record Checked(Fields fields, List<UUID> storeIds) {
    }

    /** All field errors together (400 validation); nothing typed is echoed back. {@code errors} may already hold earlier ones. */
    private Checked check(UUID tenantId, Common in, List<FieldMessage> errors) {
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) {
            errors.add(new FieldMessage("name", "Enter the vendor name"));
        } else if (name.length() > NAME_MAX) {
            errors.add(new FieldMessage("name", "Keep the name to 120 characters"));
        }
        requireSent(in.gstin(), "gstin", errors);
        String rawGstin = Sent.valueOf(in.gstin());
        String gstin = rawGstin == null ? "" : rawGstin.trim().toUpperCase(Locale.ROOT);
        if (!gstin.isEmpty() && !UnitService.GSTIN.matcher(gstin).matches()) {
            errors.add(new FieldMessage("gstin", "Enter a 15-character GSTIN"));
        }

        requireSent(in.licenceNo(), "licenceNo", errors);
        requireSent(in.licenceValidTill(), "licenceValidTill", errors);
        boolean required = Boolean.TRUE.equals(in.licenceRequired());
        if (in.licenceRequired() == null) {
            errors.add(new FieldMessage("licenceRequired", UnitService.REQUIRED));
        }
        String rawLicence = Sent.valueOf(in.licenceNo());
        String licenceNo = rawLicence == null ? "" : rawLicence.trim();
        LocalDate licenceTill = Sent.valueOf(in.licenceValidTill());
        if (required) {
            if (!Sent.missing(in.licenceNo()) && licenceNo.isEmpty()) {
                errors.add(new FieldMessage("licenceNo", "Enter the drug licence number"));
            } else if (licenceNo.length() > LICENCE_MAX) {
                errors.add(new FieldMessage("licenceNo", "Keep the licence number to 40 characters"));
            }
            if (!Sent.missing(in.licenceValidTill()) && licenceTill == null) {
                errors.add(new FieldMessage("licenceValidTill", "Enter the licence valid-till date"));
            }
        }

        requireSent(in.contactName(), "contactName", errors);
        requireSent(in.contactPhone(), "contactPhone", errors);
        String rawContact = Sent.valueOf(in.contactName());
        String contactName = rawContact == null ? "" : rawContact.trim();
        if (contactName.length() > CONTACT_MAX) {
            errors.add(new FieldMessage("contactName", "Keep the contact name to 80 characters"));
        }
        String rawPhone = Sent.valueOf(in.contactPhone());
        String phone = rawPhone == null ? "" : rawPhone.trim();
        if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) {
            errors.add(new FieldMessage("contactPhone", "Enter a 10-digit phone number"));
        }

        Integer creditDays = PharmacyParams.wholeNumber(in.creditDays(), 0, 365);
        if (creditDays == null) {
            errors.add(new FieldMessage("creditDays", CREDIT_DAYS));
        }
        requireSent(in.returnDays(), "returnDaysBeforeExpiry", errors);
        BigDecimal rawReturn = Sent.valueOf(in.returnDays());
        Integer returnDays = null;
        if (rawReturn != null) {
            returnDays = PharmacyParams.wholeNumber(rawReturn, 0, 365);
            if (returnDays == null) {
                errors.add(new FieldMessage("returnDaysBeforeExpiry", CREDIT_DAYS));
            }
        }
        requireSent(in.returnPercent(), "returnCreditPercent", errors);
        String rawPercent = Sent.valueOf(in.returnPercent());
        String percentText = rawPercent == null ? "" : rawPercent.trim();
        BigDecimal percent = null;
        if (!percentText.isEmpty()) {
            if (PERCENT.matcher(percentText).matches()) {
                percent = new BigDecimal(percentText);
            } else {
                errors.add(new FieldMessage("returnCreditPercent", PERCENT_MESSAGE));
            }
        }

        // 0.2.1 (DEC-078 item 5, DEC-075): two optional credit levels; absent or null means "no level" (the standard level
        // applies), so a create stores null and an edit keeps the stored value when the field is absent.
        BigDecimal damaged = optionalPercent(in.damagedPercent(), "damagedCreditPercent", errors);
        BigDecimal expired = optionalPercent(in.expiredPercent(), "expiredCreditPercent", errors);

        List<UUID> storeIds = checkStores(tenantId, in.storeIds(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        Fields fields = new Fields(name, gstin.isEmpty() ? null : gstin, required, required ? licenceNo : null,
                required ? licenceTill : null, contactName.isEmpty() ? null : contactName, phone.isEmpty() ? null : phone,
                creditDays, returnDays, percent, damaged, expired);
        return new Checked(fields, storeIds);
    }

    /** A percent from 0 to 100 with at most 2 decimals, or nothing (absent, null or empty). */
    static BigDecimal optionalPercent(Sent<String> field, String name, List<FieldMessage> errors) {
        String raw = Sent.valueOf(field);
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!PERCENT.matcher(text).matches()) {
            errors.add(new FieldMessage(name, PERCENT_MESSAGE));
            return null;
        }
        return new BigDecimal(text);
    }

    /** Only ACTIVE stores of the unit; empty means the Central Store. */
    private List<UUID> checkStores(UUID tenantId, List<UUID> requested, List<FieldMessage> errors) {
        if (requested == null) {
            errors.add(new FieldMessage("suppliesToStoreIds", UnitService.REQUIRED));
            return List.of();
        }
        if (requested.size() > STORES_MAX || requested.stream().anyMatch(Objects::isNull)) {
            errors.add(new FieldMessage("suppliesToStoreIds", CHOOSE_STORES));
            return List.of();
        }
        Set<UUID> chosen = new LinkedHashSet<>(requested);
        Set<UUID> active = new HashSet<>();
        for (Store store : stores.list(tenantId, false)) {
            active.add(store.id());
        }
        if (chosen.isEmpty()) {
            Store central = stores.activeCentral(tenantId).orElse(null);
            if (central == null) {
                errors.add(new FieldMessage("suppliesToStoreIds", CHOOSE_STORES));
                return List.of();
            }
            chosen.add(central.id());
        }
        if (!active.containsAll(chosen)) {
            errors.add(new FieldMessage("suppliesToStoreIds", CHOOSE_STORES));
            return List.of();
        }
        return new ArrayList<>(chosen);
    }

    private static void requireSent(Sent<?> field, String name, List<FieldMessage> errors) {
        if (Sent.missing(field)) {
            errors.add(new FieldMessage(name, UnitService.REQUIRED));
        }
    }

    private static Filter filter(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Filter.DEFAULT;
        }
        for (Filter filter : Filter.values()) {
            if (filter != Filter.DEFAULT && filter.name().equals(raw)) {
                return filter;
            }
        }
        throw invalid("status", "Choose a valid option");
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }

    private static ConflictException nameInUse() {
        return new ConflictException("vendor-name-in-use", "Vendor already in the list",
                "That vendor is already in the list");
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
