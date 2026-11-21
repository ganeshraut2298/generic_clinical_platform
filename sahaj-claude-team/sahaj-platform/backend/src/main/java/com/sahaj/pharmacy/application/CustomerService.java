package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.CustomerRepository.Row;
import com.sahaj.pharmacy.application.SaleInputs.CustomerCreate;
import com.sahaj.pharmacy.application.SaleInputs.CustomerPaymentInput;
import com.sahaj.pharmacy.application.SaleInputs.CustomerUpdate;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-2 and SAH-80-4, the pharmacy's own customers and their account (docs/api/pharmacy-sales.yaml pharmacyCreateCustomer,
 * pharmacySearchCustomers, pharmacyGetCustomer, pharmacyUpdateCustomer, pharmacySwitchOffCustomer, pharmacyGetCustomerAccount,
 * pharmacyReceiveCustomerPayment; ADR-030 S5). A customer is unit-wide; the store of the path only runs the guard (rule 24:
 * sale-allowed store, capability sell). In Integrated mode a registered patient's pharmacy account is a customer with the patient
 * id and no stored name.
 *
 * <p>Lock order of a write: the client key, then the customer row {@code FOR UPDATE}. Audit rows and logs hold ids, the limit
 * before and after, and amounts; never a name, a phone number or an allergy note (rule 29).
 */
@Service
public class CustomerService {

    static final String CREATE = "pharmacyCreateCustomer";
    static final String PAYMENT = "pharmacyReceiveCustomerPayment";
    static final String LIMIT_FORBIDDEN = "Only the owner or a pharmacist can set a credit limit.";
    static final String PHONE_EXISTS = "A customer with this phone already exists. Use them?";
    static final long MAX_LIMIT_PAISE = 9_999_999_900L;
    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);
    private static final String ENTITY_TYPE = "PHARMACY_CUSTOMER";
    private static final int MAX_SIZE = 25;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final CustomerRepository customers;
    private final SaleSupport support;
    private final ClientRequestRepository clients;
    private final AuditLog auditLog;

    public CustomerService(PharmacyGate gate, StoreAccessGuard guard, CustomerRepository customers, SaleSupport support,
                           ClientRequestRepository clients, AuditLog auditLog) {
        this.gate = gate;
        this.guard = guard;
        this.customers = customers;
        this.support = support;
        this.clients = clients;
        this.auditLog = auditLog;
    }

    // ---- reads ----

    /** Name (contains) or phone (exact, ten digits); newest first; the phone is always masked in the list. Writes nothing. */
    @Transactional(readOnly = true)
    public SaleViews.CustomerPage search(UUID tenantId, UUID userId, String rawStoreId, String rawQ, String rawPatientId,
                                         String rawIncludeInactive, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        String q = rawQ == null ? "" : rawQ.trim();
        if (q.length() > 80) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type at most 80 characters.")));
        }
        UUID patientId = PharmacyParams.optionalUuid("patientId", rawPatientId, "Choose a valid patient");
        boolean includeInactive = PharmacyParams.bool("includeInactive", rawIncludeInactive, false);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        guard.requireSale(caller, storeId, StoreCapability.SELL);

        boolean phoneSearch = SaleRules.validPhone(q);
        String like = q.isEmpty() || phoneSearch ? "" : "%" + SaleSupport.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        boolean mayList = caller.isOwner() || caller.isPharmacist();
        CustomerRepository.Slice slice = customers.search(tenantId, like, phoneSearch ? q : "", patientId,
                includeInactive && mayList, size, page * size);
        return new SaleViews.CustomerPage(views(caller, tenantId, slice.items(), true), slice.total(), page, size);
    }

    /** Writes a record-access entry for a patient account (rule 29); nothing else. */
    @Transactional
    public SaleViews.Customer get(UUID tenantId, UUID userId, String rawStoreId, String rawCustomerId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID customerId = PharmacyParams.pathId(rawCustomerId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        Row row = customers.find(tenantId, customerId).orElseThrow(CustomerService::notFound);
        if (row.patientId() != null) {
            support.recordOpened(tenantId, row.patientId(), userId);
        }
        return views(caller, tenantId, List.of(row), false).get(0);
    }

    /** Credit bills, payments and credit notes with the running due, newest first. Writes nothing. */
    @Transactional(readOnly = true)
    public SaleViews.CustomerAccount account(UUID tenantId, UUID userId, String rawStoreId, String rawCustomerId,
                                             String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID customerId = PharmacyParams.pathId(rawCustomerId);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        Row row = customers.find(tenantId, customerId).orElseThrow(CustomerService::notFound);
        CustomerRepository.EntrySlice slice = customers.entries(tenantId, customerId, size, page * size);
        Set<UUID> people = new HashSet<>();
        slice.items().forEach(e -> people.add(e.actor()));
        Map<UUID, TeamMember> members = support.members(tenantId, people);
        List<SaleViews.AccountEntry> items = new ArrayList<>();
        for (CustomerRepository.Entry e : slice.items()) {
            items.add(entryView(e, members));
        }
        return new SaleViews.CustomerAccount(credit(caller, tenantId, row), items, slice.total(), page, size);
    }

    // ---- writes ----

    /**
     * 201 for a new customer; 200 for a replay of the same client key or for the existing account of that patient. A phone that
     * another customer already has needs {@code confirmSamePhone} (409 customer-phone-exists with candidates).
     */
    @Transactional
    public PharmacyViews.Outcome<SaleViews.Customer> create(UUID tenantId, UUID userId, String rawStoreId,
                                                            PharmacyInputs.Body<CustomerCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        CustomerCreate in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = customers.find(tenantId, own.subjectId()).orElseThrow(CustomerService::notFound);
                return new PharmacyViews.Outcome<>(views(caller, tenantId, List.of(first), false).get(0), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        boolean patientAccount = in.patientId() != null;
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        String name = SaleSupport.blank(in.name());
        String phone = SaleSupport.blank(in.phone());
        String note = SaleSupport.blank(in.allergyNote());
        if (patientAccount) {
            if (name != null) {
                errors.add(new FieldMessage("name", "A patient account has no name of its own"));
            }
            if (phone != null) {
                errors.add(new FieldMessage("phone", "A patient account has no phone of its own"));
            }
            if (note != null) {
                errors.add(new FieldMessage("allergyNote", "A patient account has no allergy note of its own"));
            }
        } else {
            checkWalkInFields(name, phone, note, errors);
        }
        Long limit = limit(in.creditLimitPaise(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (limit != null && !caller.isOwner() && !caller.isPharmacist()) {
            throw new ForbiddenException("forbidden", "Not allowed", LIMIT_FORBIDDEN);
        }
        if (patientAccount) {
            support.requireIntegrated(tenantId);
            support.patient(tenantId, in.patientId(), false);
            Row existing = customers.findByPatient(tenantId, in.patientId()).orElse(null);
            if (existing != null) {
                return new PharmacyViews.Outcome<>(views(caller, tenantId, List.of(existing), false).get(0), false);
            }
        } else if (phone != null && !Boolean.TRUE.equals(in.confirmSamePhone())) {
            List<Row> same = customers.byPhone(tenantId, phone, null, 10);
            if (!same.isEmpty()) {
                throw phoneExists(same);
            }
        }

        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body); // L15: the other tap won; answer as its replay
        }
        long stored = limit == null ? 0L : limit;
        customers.insert(tenantId, new CustomerRepository.NewCustomer(id, store.unitId(), patientAccount ? "PATIENT" : "WALK_IN",
                in.patientId(), name, phone, note, stored), me);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", id.toString());
        after.put("kind", patientAccount ? "PATIENT" : "WALK_IN");
        after.put("limitSet", stored > 0);
        auditLog.record("PHARMACY_CUSTOMER_CREATED", ENTITY_TYPE, id, null, after);
        log.info("Customer created customerId={}", id);
        Row saved = customers.find(tenantId, id).orElseThrow();
        return new PharmacyViews.Outcome<>(views(caller, tenantId, List.of(saved), false).get(0), true);
    }

    /** Same field rules as create; a stale version is 409 settings-changed with the current customer. */
    @Transactional
    public SaleViews.Customer update(UUID tenantId, UUID userId, String rawStoreId, String rawCustomerId,
                                     PharmacyInputs.Body<CustomerUpdate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID customerId = PharmacyParams.pathId(rawCustomerId);
        CustomerUpdate in = body.read();
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        List<FieldMessage> errors = new ArrayList<>();
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        Row row = customers.findForUpdate(tenantId, customerId).orElseThrow(CustomerService::notFound);
        boolean patientAccount = row.patientId() != null;
        String name = SaleSupport.blank(in.name());
        String phone = SaleSupport.blank(in.phone());
        String note = SaleSupport.blank(in.allergyNote());
        if (patientAccount) {
            if (name != null || phone != null || note != null) {
                errors.add(new FieldMessage("name", "A patient account has no name, phone or allergy note of its own"));
            }
        } else {
            checkWalkInFields(name, phone, note, errors);
        }
        Long limit = limit(in.creditLimitPaise(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        long newLimit = limit == null ? 0L : limit;
        if (newLimit != row.creditLimitPaise() && !caller.isOwner() && !caller.isPharmacist()) {
            throw new ForbiddenException("forbidden", "Not allowed", LIMIT_FORBIDDEN);
        }
        if (version != row.version()) {
            throw PharmacyProblemException.settingsChanged(views(caller, tenantId, List.of(row), false).get(0));
        }
        if (!patientAccount && phone != null && !Boolean.TRUE.equals(in.confirmSamePhone())) {
            List<Row> same = customers.byPhone(tenantId, phone, customerId, 10);
            if (!same.isEmpty()) {
                throw phoneExists(same);
            }
        }
        if (!customers.update(tenantId, customerId, row.version(), name, phone, note, newLimit, me)) {
            throw PharmacyProblemException.settingsChanged(
                    views(caller, tenantId, List.of(customers.find(tenantId, customerId).orElseThrow()), false).get(0));
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("creditLimitPaise", row.creditLimitPaise());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", customerId.toString());
        after.put("creditLimitPaise", newLimit);
        auditLog.record("PHARMACY_CUSTOMER_CHANGED", ENTITY_TYPE, customerId, before, after);
        log.info("Customer changed customerId={}", customerId);
        return views(caller, tenantId, List.of(customers.find(tenantId, customerId).orElseThrow()), false).get(0);
    }

    /** The owner or a pharmacist; already off is 200 with nothing written. The due stays and payments can still be received. */
    @Transactional
    public SaleViews.Customer switchOff(UUID tenantId, UUID userId, String rawStoreId, String rawCustomerId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID customerId = PharmacyParams.pathId(rawCustomerId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        if (!caller.isOwner() && !caller.isPharmacist()) {
            throw guard.refuse(caller, storeId, StoreCapability.SELL);
        }
        Row row = customers.findForUpdate(tenantId, customerId).orElseThrow(CustomerService::notFound);
        if (row.active()) {
            if (!customers.switchOff(tenantId, customerId, row.version(), caller.actorId())) {
                throw PharmacyProblemException.settingsChanged(views(caller, tenantId, List.of(row), false).get(0));
            }
            auditLog.record("PHARMACY_CUSTOMER_SWITCHED_OFF", ENTITY_TYPE, customerId, null,
                    Map.of("customerId", customerId.toString()));
        }
        return views(caller, tenantId, List.of(customers.find(tenantId, customerId).orElseThrow()), false).get(0);
    }

    /**
     * A payment against udhar under the customer row lock: above the due is 400 "The payment is more than the due." The entry
     * belongs to the receiving store and person (it counts in their shift). A switched-off customer may still pay.
     */
    @Transactional
    public PharmacyViews.Outcome<SaleViews.CustomerPaymentReceipt> receivePayment(UUID tenantId, UUID userId,
                                                                                  String rawStoreId, String rawCustomerId,
                                                                                  PharmacyInputs.Body<CustomerPaymentInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID customerId = PharmacyParams.pathId(rawCustomerId);
        CustomerPaymentInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!PAYMENT.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row replayed = customers.find(tenantId, customerId).orElseThrow(CustomerService::notFound);
                CustomerRepository.Entry entry = customers.findEntry(tenantId, own.subjectId())
                        .orElseThrow(CustomerService::notFound);
                return new PharmacyViews.Outcome<>(receipt(caller, tenantId, replayed, entry, store), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long amount = SaleSupport.wholePaise(in.amountPaise(), MAX_LIMIT_PAISE);
        if (amount == null || amount < 1) {
            errors.add(new FieldMessage("amountPaise", "Enter an amount of at least ₹ 0.01"));
            amount = null;
        }
        String mode = in.mode();
        if (!"CASH".equals(mode) && !"UPI".equals(mode) && !"CARD".equals(mode)) {
            errors.add(new FieldMessage("mode", "Choose Cash, UPI or Card"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        UUID entryId = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), PAYMENT, entryId, false) != null) {
            return receivePayment(tenantId, userId, rawStoreId, rawCustomerId, body);
        }
        Row row = customers.findForUpdate(tenantId, customerId).orElseThrow(CustomerService::notFound);
        if (amount > row.duePaise()) {
            throw new InvalidFieldsException(List.of(new FieldMessage("amountPaise", "The payment is more than the due.")));
        }
        long dueAfter = row.duePaise() - amount;
        customers.setBalances(tenantId, customerId, dueAfter, row.creditNoteBalancePaise(), me);
        customers.insertEntry(tenantId, new CustomerRepository.NewEntry(entryId, store.unitId(), customerId, storeId, "PAYMENT",
                amount, dueAfter, row.creditNoteBalancePaise(), null, null, mode, me));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customerId", customerId.toString());
        after.put("entryId", entryId.toString());
        after.put("amountPaise", amount);
        after.put("mode", mode);
        auditLog.record("PHARMACY_CUSTOMER_PAYMENT", ENTITY_TYPE, customerId, null, after);
        log.info("Customer payment entryId={} amountPaise={}", entryId, amount);
        CustomerRepository.Entry entry = customers.findEntry(tenantId, entryId).orElseThrow();
        return new PharmacyViews.Outcome<>(receipt(caller, tenantId, customers.find(tenantId, customerId).orElseThrow(), entry,
                store), true);
    }

    // ---- views ----

    private SaleViews.CustomerPaymentReceipt receipt(PharmacyCaller caller, UUID tenantId, Row row,
                                                     CustomerRepository.Entry entry, Store store) {
        String name = row.name();
        if (row.patientId() != null) {
            SaleSupport.PatientCard card = support.patientCards(tenantId, List.of(row.patientId()), false).get(row.patientId());
            name = card == null ? "Patient" : card.name();
        }
        Map<UUID, TeamMember> members = support.members(tenantId, List.of(entry.actor()));
        return new SaleViews.CustomerPaymentReceipt(entry.id(), row.id(), name, entry.amountPaise(), entry.mode(), entry.at(),
                support.person(entry.actor(), members), OpeningStockPostService.storeRef(store), entry.dueAfterPaise(),
                support.invoiceHeader(tenantId));
    }

    private SaleViews.CreditLine credit(PharmacyCaller caller, UUID tenantId, Row row) {
        long recent = customers.creditBillsSince(tenantId, List.of(row.id()), support.sixtyDaysAgo())
                .getOrDefault(row.id(), 0L);
        return support.creditLine(caller, row, recent);
    }

    /** Customers as the contract shows them; {@code masked} forces the masked phone (the list). */
    private List<SaleViews.Customer> views(PharmacyCaller caller, UUID tenantId, List<Row> rows, boolean masked) {
        Set<UUID> patientIds = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> {
            ids.add(r.id());
            if (r.patientId() != null) {
                patientIds.add(r.patientId());
            }
        });
        Map<UUID, SaleSupport.PatientCard> cards = support.patientCards(tenantId, patientIds, !masked);
        Map<UUID, Long> recent = customers.creditBillsSince(tenantId, ids, support.sixtyDaysAgo());
        boolean mayShowPhone = caller.isOwner() || caller.isPharmacist();
        List<SaleViews.Customer> out = new ArrayList<>();
        for (Row r : rows) {
            SaleViews.CreditLine credit = support.creditLine(caller, r, recent.getOrDefault(r.id(), 0L));
            String name = r.name();
            String phone;
            boolean phoneMasked;
            if (r.patientId() != null) {
                SaleSupport.PatientCard card = cards.get(r.patientId());
                name = card == null ? "Patient" : card.name();
                phone = card == null || card.mobile() == null ? null : SaleRules.maskMobile(card.mobile());
                phoneMasked = true;
            } else if (r.phone() == null) {
                phone = null;
                phoneMasked = false;
            } else if (!masked && mayShowPhone) {
                phone = r.phone();
                phoneMasked = false;
            } else {
                phone = SaleRules.maskMobile(r.phone());
                phoneMasked = true;
            }
            out.add(new SaleViews.Customer(r.id(), r.kind(), r.patientId(), name, phone, phoneMasked, r.allergyNote(),
                    r.status(), credit, r.version()));
        }
        return out;
    }

    private SaleViews.AccountEntry entryView(CustomerRepository.Entry e, Map<UUID, TeamMember> members) {
        long signed;
        switch (e.kind()) {
            case "CREDIT_BILL":
            case "CREDIT_NOTE_ISSUED":
                signed = e.amountPaise();
                break;
            default:
                signed = -e.amountPaise();
                break;
        }
        return new SaleViews.AccountEntry(e.id(), e.kind(), e.at(), e.storeName(), e.reference(), e.mode(), signed,
                e.dueAfterPaise(), e.creditNoteBalanceAfterPaise(), support.person(e.actor(), members));
    }

    // ---- validation ----

    private static void checkWalkInFields(String name, String phone, String note, List<FieldMessage> errors) {
        if (name == null || name.length() > 80) {
            errors.add(new FieldMessage("name", "Enter the name"));
        }
        if (phone != null && !SaleRules.validPhone(phone)) {
            errors.add(new FieldMessage("phone", "Enter a 10-digit phone number"));
        }
        if (note != null && note.length() > 200) {
            errors.add(new FieldMessage("allergyNote", "Keep the note to 200 characters"));
        }
    }

    /** The credit limit in paise; null when none was sent; an error message when it is not a whole amount of 0 or more. */
    private static Long limit(java.math.BigDecimal value, List<FieldMessage> errors) {
        if (value == null) {
            return null;
        }
        Long paise = SaleSupport.wholePaise(value, MAX_LIMIT_PAISE);
        if (paise == null) {
            errors.add(new FieldMessage("creditLimitPaise", "Enter a credit limit of 0 or more"));
        }
        return paise;
    }

    private PharmacyProblemException phoneExists(List<Row> same) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Row r : same) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("customerId", r.id());
            c.put("name", r.name());
            c.put("maskedPhone", SaleRules.maskMobile(r.phone()));
            candidates.add(c);
        }
        return PharmacyProblemException.saleConflict("customer-phone-exists", "Customer already exists", PHONE_EXISTS)
                .with("customerCandidates", candidates);
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }
}
