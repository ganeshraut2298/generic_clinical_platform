package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.SaleBillRepository.BillRow;
import com.sahaj.pharmacy.application.SaleInputs.BillCreate;
import com.sahaj.pharmacy.application.SaleInputs.PaymentInput;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.SaleMath;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
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
 * SAH-80-3, 80-4 and 80-12 (the typed part), the counter bill (docs/api/pharmacy-sales.yaml pharmacyCreateBill, pharmacyListBills,
 * pharmacyGetBill; ADR-030 S3). The sale transaction, in this order: the client key; the document rows {@code FOR UPDATE} (the held
 * bill, the customer account); every balance row of the cart's products in canonical order and FEFO in memory
 * ({@link SalePricer}); the checks, all or nothing; the number, last (the one documented exception to the lock order, ADR-030 S3);
 * then the bill, its lines and payments, the COUNTER_SALE ledger lines through {@link StockPosting} (the only writer of stock), the
 * customer account entries, the H1 and Schedule X register rows, the held bill closed, the history and the audit row.
 *
 * <p>Before the transaction a Schedule X line has its second signature verified (ADR-029 P7, {@link ScheduleXSignature#verify} runs
 * outside any transaction). A discount that needs approval answers 409 {@code discount-approval-needed} until the approval request
 * of WP-J exists. Prescription parts (an e-prescription, an OPD queue item) are 409 {@code not-connected}. Names, allergy text and
 * prescription details are written only to the register rows; the audit row and the log hold ids, counts and the total.
 */
@Service
public class SaleBillService {

    static final String OPERATION = "pharmacyCreateBill";
    static final String DOC_TYPE = "BILL";
    static final String PHARMACIST_NEEDED = "A pharmacist must complete this bill.";
    static final String PHARMACIST_ONLY_X = "Only a pharmacist can sell a Schedule X medicine.";
    static final String DISCOUNT_NEEDS_APPROVAL = "This discount needs approval.";
    static final String DISCOUNT_APPROVAL_VOID = "This discount approval no longer matches the bill. Send it for approval again.";
    static final String CREDIT_LIMIT = "This is above the credit limit. Reduce the udhar amount or ask the owner.";
    static final String H1_PRESCRIBER = "Prescriber name and registration number are needed for Schedule H1.";
    static final String PRESCRIPTION = "Enter the prescription details for the Schedule H1 and X items.";
    static final String ALLERGY_BLOCKED = "This patient has an allergy recorded for this medicine. A pharmacist can override it.";
    static final String CUSTOMER_INACTIVE = "This customer is switched off. Udhar is not allowed.";
    private static final Logger log = LoggerFactory.getLogger(SaleBillService.class);
    private static final String ENTITY_TYPE = "PHARMACY_BILL";
    private static final int MAX_SIZE = 25;
    private static final Set<String> MODES = Set.of("CASH", "UPI", "CARD", "UDHAR", "CREDIT_NOTE");

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final HeldBillRepository held;
    private final SaleBillRepository bills;
    private final SaleRegisterRepository registers;
    private final SalePricer pricer;
    private final DiscountApprovals approvals;
    private final SaleBillAssembler assembler;
    private final SaleSupport support;
    private final DocumentNumberPort numbers;
    private final DocumentSeriesRepository series;
    private final StoreBillSeriesRepository storeSeries;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final BlindCount blind;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public SaleBillService(PharmacyGate gate, StoreAccessGuard guard, ProductRepository products, CustomerRepository customers,
                           HeldBillRepository held, SaleBillRepository bills, SaleRegisterRepository registers,
                           SalePricer pricer, DiscountApprovals approvals, SaleBillAssembler assembler, SaleSupport support,
                           DocumentNumberPort numbers, DocumentSeriesRepository series, StoreBillSeriesRepository storeSeries,
                           StockPosting posting, ScheduleXSignature signature, ClientRequestRepository clients,
                           DocumentEventRepository events, BlindCount blind, AuditLog auditLog, PharmacyTime time) {
        this.approvals = approvals;
        this.gate = gate;
        this.guard = guard;
        this.products = products;
        this.customers = customers;
        this.held = held;
        this.bills = bills;
        this.registers = registers;
        this.pricer = pricer;
        this.assembler = assembler;
        this.support = support;
        this.numbers = numbers;
        this.series = series;
        this.storeSeries = storeSeries;
        this.posting = posting;
        this.signature = signature;
        this.clients = clients;
        this.events = events;
        this.blind = blind;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- create ----

    /**
     * The first step of a save, before any PIN is checked: the gate, the path id, the body and the store guard, in that order.
     * Returns the second signer the body names only for a pharmacist saving a cart that holds a Schedule X product (no other caller
     * can make a PIN attempt against a colleague).
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId,
                                                     PharmacyInputs.Body<BillCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        BillCreate in = body.read();
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        if (in.secondSigner() == null || !caller.isPharmacist() || in.lines() == null) {
            return null;
        }
        return hasScheduleX(tenantId, in.lines()) ? in.secondSigner() : null;
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<SaleViews.Bill> create(UUID tenantId, UUID userId, String rawStoreId,
                                                        PharmacyInputs.Body<BillCreate> body, UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        BillCreate in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OPERATION.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                BillRow first = bills.find(tenantId, own.subjectId()).filter(b -> b.storeId().equals(storeId))
                        .orElseThrow(SaleBillService::notFound);
                return new PharmacyViews.Outcome<>(assembler.view(caller, userId, first), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        // validation, all together (rule 21): what needs no lock
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        SaleCart.Cart cart = pricer.read(tenantId, in.cart(), errors);
        if (SaleSupport.blank(in.quoteVersion()) == null || in.quoteVersion().length() > 64) {
            errors.add(new FieldMessage("quoteVersion", UnitService.RELOAD));
        }
        Long expectedNet = SaleSupport.wholePaise(in.expectedNetPaise(), CustomerService.MAX_LIMIT_PAISE);
        if (expectedNet == null) {
            errors.add(new FieldMessage("expectedNetPaise", UnitService.RELOAD));
        }
        Map<String, Long> payments = payments(in.payments(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        pricer.requireReady(tenantId, cart);

        // lock order: the client key, the document rows (held bill, customer), then the stock rows, the number last
        UUID billId = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OPERATION, billId, false) != null) {
            return create(tenantId, userId, rawStoreId, body, verifiedSignerMembershipId); // L15: answer as the replay
        }
        HeldBillRepository.Row heldRow = null;
        if (in.heldBillId() != null) {
            heldRow = held.findForUpdate(tenantId, in.heldBillId()).filter(h -> h.storeId().equals(storeId))
                    .orElseThrow(SaleBillService::notFound);
            if (!"OPEN".equals(heldRow.status())) {
                throw PharmacyProblemException.changed(null);
            }
        }
        boolean useCredit = payments.containsKey("UDHAR") || payments.containsKey("CREDIT_NOTE");
        CustomerRepository.Row customer = useCredit ? lockCustomer(tenantId, cart.buyer()) : null;

        SalePricer.Priced priced = pricer.price(caller, store, cart, true);

        // permissions first (403), then the state of the stock and the price (409), then the checks of the bill (400)
        if ((priced.anyScheduled() || priced.anyDecision()) && !priced.canSellScheduled()) {
            throw PharmacyProblemException.pharmacistRequired(priced.anyX() ? PHARMACIST_ONLY_X : PHARMACIST_NEEDED);
        }
        UUID signer = null;
        if (priced.anyX()) {
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }
        if (!priced.shortLines().isEmpty()) {
            throw blind.scrub(caller, storeId,
                    PharmacyProblemException.insufficientStock(priced.firstAvailable(), priced.shortLines()));
        }
        if (!priced.quote().quoteVersion().equals(in.quoteVersion()) || priced.math().netPayable() != expectedNet) {
            throw PharmacyProblemException.saleChanged(priced.quote());
        }
        long net = priced.math().netPayable();
        errors = new ArrayList<>();
        checkBill(cart, priced, payments, net, customer, errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (priced.discount().approvalNeeded()) {
            // the pricer lifts the need for an APPROVED request of this cart; what is left is a missing or a void request
            if (cart.discountRequestId() == null) {
                throw PharmacyProblemException.saleConflict("discount-approval-needed", "Discount needs approval",
                        DISCOUNT_NEEDS_APPROVAL);
            }
            throw PharmacyProblemException.saleConflict("discount-approval-void", "Discount approval is not valid",
                    DISCOUNT_APPROVAL_VOID);
        }
        UUID discountRequestId = caller.isOwner() || priced.discount().lean() || cart.percent() == null
                || cart.percent().signum() == 0 ? null
                : approvals.check(tenantId, storeId, cart).approved() ? cart.discountRequestId() : null;
        long udhar = payments.getOrDefault("UDHAR", 0L);
        if (udhar > 0 && customer != null && !customer.active()) {
            throw PharmacyProblemException.saleConflict("customer-inactive", "Customer switched off", CUSTOMER_INACTIVE);
        }

        // the buyer of the record
        UUID savedCustomerId = customer == null ? cart.buyer().customerId() : customer.id();
        String buyerKind = cart.buyer().kind();
        String walkInName = cart.buyer().walkInName();
        String walkInPhone = cart.buyer().walkInPhone();
        if ("WALK_IN".equals(buyerKind) && cart.buyer().saveToCustomers()) {
            savedCustomerId = UUID.randomUUID();
            customers.insert(tenantId, new CustomerRepository.NewCustomer(savedCustomerId, store.unitId(), "WALK_IN", null,
                    walkInName, walkInPhone, null, 0L), me);
            buyerKind = "CUSTOMER";
            walkInName = null;
            walkInPhone = null;
        }
        UUID patientId = priced.patient() == null ? null : priced.patient().id();

        // the number, last
        LocalDate today = time.today();
        Numbered numbered = takeNumber(tenantId, store, today);
        Instant now = time.now();
        SaleMath.Result m = priced.math();
        SaleCart.Prescription card = cart.prescription();
        BillRow bill = new BillRow(billId, store.unitId(), storeId, numbered.text(), numbered.seriesKind(), numbered.fiscalYear(),
                "SAVED", buyerKind, patientId, savedCustomerId, walkInName, walkInPhone,
                cart.buyer().linkPending(), priced.allergyNotChecked(), null, card == null ? null : card.source(),
                card == null ? null : card.prescriberName(), card == null ? null : card.prescriberRegNo(),
                card == null ? null : card.prescriptionDate(), in.heldBillId(), cart.percent(), discountRequestId, m.gross(), m.discount(),
                m.taxable(), m.cgst(), m.sgst(), m.roundOff(), net, udhar, now, me, caller.isPharmacist() ? me : null, null, null,
                null, null, null, 0L, null, 0L);
        bills.insertBill(tenantId, bill);

        List<UUID> lineIds = new ArrayList<>();
        List<StockPosting.Line> stockLines = new ArrayList<>();
        for (SalePricer.PricedLine l : priced.lines()) {
            UUID lineId = UUID.randomUUID();
            lineIds.add(lineId);
            SaleCart.Line input = l.input();
            boolean decision = input.substituted() || input.allergyOverride() != null || input.h1Override() != null;
            boolean isX = "X".equals(l.product().schedule());
            bills.insertLine(tenantId, store.unitId(), billId, new SaleBillRepository.LineRow(lineId, l.lineNo(),
                    l.product().productId(), l.batchId(), l.qty(), 0, l.mrp(), l.gstRate(), l.product().hsn(),
                    l.money().a(), l.money().d(), l.money().n(), l.money().t(), l.money().x(), input.prescribedProductId(),
                    input.substituted() ? input.substitution().reasonKey() : null,
                    input.substituted() ? SaleSupport.blank(input.substitution().note()) : null,
                    input.substituted() ? Boolean.TRUE : null,
                    input.allergyOverride() == null ? null : input.allergyOverride().reasonKey(),
                    input.allergyOverride() == null ? null : SaleSupport.blank(input.allergyOverride().note()),
                    input.h1Override() == null ? null : input.h1Override().reasonKey(),
                    input.h1Override() == null ? null : SaleSupport.blank(input.h1Override().note()),
                    decision ? me : null, me, isX ? signer : null, input.rxLineId()), me);
            stockLines.add(new StockPosting.Line(storeId, l.product().productId(), l.batchId(), StockState.AVAILABLE, -l.qty(),
                    "COUNTER_SALE", null, UUID.randomUUID(), null, new StockPosting.Doc(DOC_TYPE, billId, numbered.text(),
                    lineId), l.lineNo(), isX ? signer : null));
        }
        for (Map.Entry<String, Long> p : payments.entrySet()) {
            bills.insertPayment(tenantId, store.unitId(), storeId, billId, p.getKey(), p.getValue(), me);
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), stockLines);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }
        accountEntries(tenantId, store, customer, payments, billId, me);
        registerRows(tenantId, store, cart, priced, bill, lineIds, signer, me);
        if (heldRow != null) {
            held.close(tenantId, heldRow.id(), heldRow.version(), "RESUMED", me);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "SAVED", storeId, me, caller.isPharmacist() ? "PHARMACIST" : null,
                null, null, priced.lines().stream().mapToInt(SalePricer.PricedLine::qty).sum(), null);
        for (SalePricer.PricedLine l : priced.lines()) {
            SaleCart.Line input = l.input();
            if (input.allergyOverride() != null) {
                events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "ALLERGY_OVERRIDE", storeId, me, "PHARMACIST", null,
                        null, null, input.allergyOverride().reasonKey());
            }
            if (input.h1Override() != null) {
                events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "H1_OVERRIDE", storeId, me, "PHARMACIST", null, null,
                        null, input.h1Override().reasonKey());
            }
        }
        if (signer != null) {
            events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "SECOND_SIGNATURE", storeId, me, "PHARMACIST", null, null,
                    null, null);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("billId", billId.toString());
        after.put("billNo", numbered.text());
        after.put("lines", priced.lines().size());
        after.put("units", priced.lines().stream().mapToInt(SalePricer.PricedLine::qty).sum());
        after.put("netPaise", net);
        after.put("udharPaise", udhar);
        auditLog.record("PHARMACY_BILL_SAVED", ENTITY_TYPE, billId, null, after);
        log.info("Bill saved billId={} lines={} netPaise={}", billId, priced.lines().size(), net);
        return new PharmacyViews.Outcome<>(assembler.view(caller, userId, bills.find(tenantId, billId).orElseThrow()), true);
    }

    // ---- reads ----

    /** Bills of this store, newest first: by number, IST day (default today), status and mark. Writes nothing. */
    @Transactional(readOnly = true)
    public SaleViews.BillPage list(UUID tenantId, UUID userId, String rawStoreId, String rawNumber, String rawDate,
                                   String rawStatus, String rawMark, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        String number = rawNumber == null ? "" : rawNumber.trim().toLowerCase(Locale.ROOT);
        if (number.length() > 16) {
            throw new InvalidFieldsException(List.of(new FieldMessage("number", "Type at most 16 characters.")));
        }
        LocalDate day = time.today();
        if (rawDate != null && !rawDate.isBlank()) {
            try {
                day = LocalDate.parse(rawDate.trim());
            } catch (java.time.format.DateTimeParseException notADate) {
                throw new InvalidFieldsException(List.of(new FieldMessage("date", "Choose a valid date")));
            }
        }
        String status = oneOf("status", rawStatus, "SAVED", "CANCELLED");
        String mark = oneOf("mark", rawMark, "LINK_PENDING", "ALLERGY_NOT_CHECKED", "REFUND_DUE");
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        requireSellOrRegisters(caller, storeId);

        // a bill number search ignores the day (find by number); the day filter is the default view
        Instant from = number.isEmpty() ? day.atStartOfDay(PharmacyTime.IST).toInstant() : Instant.EPOCH;
        Instant to = number.isEmpty() ? day.plusDays(1).atStartOfDay(PharmacyTime.IST).toInstant()
                : Instant.parse("9999-01-01T00:00:00Z");
        SaleBillRepository.ListSlice slice = bills.page(tenantId, storeId, number, from, to, status, mark, size, page * size);
        Set<UUID> patientIds = new HashSet<>();
        slice.items().forEach(r -> {
            if (r.patientId() != null) {
                patientIds.add(r.patientId());
            }
            if (r.customerPatientId() != null) {
                patientIds.add(r.customerPatientId());
            }
        });
        Map<UUID, SaleSupport.PatientCard> cards = Map.of();
        try {
            cards = support.patientCards(tenantId, patientIds, true);
        } catch (PharmacyProblemException unreachable) {
            // a list never fails for a name: the label then says "Patient"
        }
        boolean full = caller.isOwner() || caller.isPharmacist();
        List<SaleViews.BillRow> items = new ArrayList<>();
        for (SaleBillRepository.ListRow r : slice.items()) {
            items.add(new SaleViews.BillRow(r.id(), r.billNo(), r.savedAt(), label(r, cards, full), r.itemCount(), r.netPaise(),
                    r.status(), marks(r)));
        }
        return new SaleViews.BillPage(items, slice.total(), page, size);
    }

    /** One bill with lines, payments, marks, history and the invoice print data; a record-access entry for a registered patient. */
    @Transactional
    public SaleViews.Bill get(UUID tenantId, UUID userId, String rawStoreId, String rawBillId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID billId = PharmacyParams.pathId(rawBillId);
        requireSellOrRegisters(caller, storeId);
        BillRow bill = bills.find(tenantId, billId).filter(b -> b.storeId().equals(storeId))
                .orElseThrow(SaleBillService::notFound);
        return assembler.view(caller, userId, bill);
    }

    // ---- helpers ----

    /** The capability sell, or readRegisters (the owner's and the HOD's read of the bills). */
    private void requireSellOrRegisters(PharmacyCaller caller, UUID storeId) {
        if (StoreAccessGuard.holds(caller, storeId, StoreCapability.SELL)) {
            guard.require(caller, storeId, StoreCapability.SELL);
        } else {
            guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        }
    }

    private static String oneOf(String name, String raw, String... allowed) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String a : allowed) {
            if (a.equals(raw)) {
                return a;
            }
        }
        throw new InvalidFieldsException(List.of(new FieldMessage(name, "Choose a valid option")));
    }

    private static String label(SaleBillRepository.ListRow r, Map<UUID, SaleSupport.PatientCard> cards, boolean full) {
        UUID patientId = r.patientId() != null ? r.patientId() : r.customerPatientId();
        String name;
        String mobile = null;
        if (patientId != null) {
            SaleSupport.PatientCard card = cards.get(patientId);
            name = card == null ? "Patient" : card.name();
            mobile = card == null || card.mobile() == null ? null : SaleRules.maskMobile(card.mobile());
        } else if (r.customerName() != null) {
            name = r.customerName();
        } else {
            return "Walk-in";
        }
        String shown = full ? name : SaleRules.maskName(name);
        return mobile == null ? shown : shown + " · " + mobile;
    }

    private static List<String> marks(SaleBillRepository.ListRow r) {
        List<String> marks = new ArrayList<>();
        if (r.linkPending()) {
            marks.add("LINK_PENDING");
        }
        if (r.allergyNotChecked()) {
            marks.add("ALLERGY_NOT_CHECKED");
        }
        if (r.refundDue()) {
            marks.add("REFUND_DUE");
        }
        if (r.allergyOverride()) {
            marks.add("ALLERGY_OVERRIDE");
        }
        if (r.h1Override()) {
            marks.add("H1_OVERRIDE");
        }
        if (r.substituted()) {
            marks.add("SUBSTITUTED");
        }
        if (r.hasReturns()) {
            marks.add("HAS_RETURNS");
        }
        return marks;
    }

    private boolean hasScheduleX(UUID tenantId, List<SaleInputs.CartLineInput> lines) {
        Set<UUID> ids = new HashSet<>();
        for (SaleInputs.CartLineInput l : lines) {
            if (l != null && l.productId() != null) {
                ids.add(l.productId());
            }
        }
        return products.findAll(tenantId, ids).values().stream().anyMatch(f -> GoodsReceiptService.isScheduleX(f.row()));
    }

    /** Each mode at most once; whole paise of at least 1; one to five payments. */
    private static Map<String, Long> payments(List<PaymentInput> in, List<FieldMessage> errors) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty() || in.size() > 5) {
            errors.add(new FieldMessage("payments", "Choose how the customer pays."));
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            PaymentInput p = in.get(i);
            Long amount = p == null ? null : SaleSupport.wholePaise(p.amountPaise(), CustomerService.MAX_LIMIT_PAISE);
            if (p == null || p.mode() == null || !MODES.contains(p.mode())) {
                errors.add(new FieldMessage("payments[" + i + "].mode", "Choose a payment mode"));
            } else if (amount == null || amount < 1) {
                errors.add(new FieldMessage("payments[" + i + "].amountPaise", "Enter an amount of at least ₹ 0.01"));
            } else if (out.putIfAbsent(p.mode(), amount) != null) {
                errors.add(new FieldMessage("payments[" + i + "].mode", "Use each payment mode once"));
            }
        }
        return out;
    }

    /** The customer account row locked {@code FOR UPDATE} for udhar or a credit note; null when the buyer has none. */
    private CustomerRepository.Row lockCustomer(UUID tenantId, SaleCart.Buyer buyer) {
        UUID id = buyer.customerId();
        if (id == null && buyer.patientId() != null) {
            id = customers.findByPatient(tenantId, buyer.patientId()).map(CustomerRepository.Row::id).orElse(null);
        }
        return id == null ? null : customers.findForUpdate(tenantId, id).orElseThrow(SaleBillService::notFound);
    }

    /** The checks of the bill that need the priced cart, all together (rule 21). */
    private void checkBill(SaleCart.Cart cart, SalePricer.Priced priced, Map<String, Long> payments, long net,
                           CustomerRepository.Row customer, List<FieldMessage> errors) {
        for (Integer index : priced.allergyBlocked()) {
            errors.add(new FieldMessage("lines[" + index + "].allergyOverride", ALLERGY_BLOCKED));
        }
        if (!priced.h1Missing().isEmpty()) {
            errors.add(new FieldMessage("prescription", H1_PRESCRIBER));
        }
        if (priced.prescriptionIncomplete()) {
            errors.add(new FieldMessage("prescription", PRESCRIPTION));
        }
        long paid = payments.values().stream().mapToLong(Long::longValue).sum();
        if (paid != net) {
            errors.add(new FieldMessage("payments", "Payments must add up to " + SaleRules.rupeesText(net) + "."));
        }
        long udhar = payments.getOrDefault("UDHAR", 0L);
        if (udhar > 0 && (customer == null && !"CUSTOMER".equals(cart.buyer().kind()) || customer == null)) {
            errors.add(new FieldMessage("payments", "Choose a customer for udhar."));
        } else if (udhar > 0 && customer.duePaise() + udhar > customer.creditLimitPaise()) {
            errors.add(new FieldMessage("payments", CREDIT_LIMIT));
        }
        long creditNote = payments.getOrDefault("CREDIT_NOTE", 0L);
        if (creditNote > 0 && (customer == null || creditNote > customer.creditNoteBalancePaise())) {
            errors.add(new FieldMessage("payments", "The credit note balance is lower than this amount."));
        }
    }

    private record Numbered(String text, String seriesKind, int fiscalYear) {
    }

    /** The bill number from the store's own series, else the unit's BILL series (rule 28). Taken last, in this transaction. */
    private Numbered takeNumber(UUID tenantId, Store store, LocalDate today) {
        StoreBillSeriesRepository.Row own = storeSeries.find(tenantId, store.id()).orElse(null);
        if (own != null) {
            long seq = numbers.nextStoreBill(tenantId, store.id());
            return new Numbered(DocumentNumbers.render(own.prefix(), own.financialYearPart(), own.sequenceLength(), seq, today),
                    "STORE", fiscalYear(own.restartsEachFinancialYear(), today));
        }
        long seq = numbers.next(tenantId, DocType.BILL);
        DocumentSeriesRepository.Row unit = series.find(tenantId, DocType.BILL).orElse(null);
        boolean restarts = unit == null ? DocType.BILL.defaultRestarts() : unit.restartsEachFinancialYear();
        return new Numbered(StoreUseSupport.render(series, tenantId, DocType.BILL, seq, today), "UNIT",
                fiscalYear(restarts, today));
    }

    private static int fiscalYear(boolean restarts, LocalDate today) {
        return restarts ? DocumentNumbers.financialYearStart(today) : 0;
    }

    /** CREDIT_NOTE_USED first (it lowers the balance), then CREDIT_BILL (it raises the due); one update of the row. */
    private void accountEntries(UUID tenantId, Store store, CustomerRepository.Row customer, Map<String, Long> payments,
                                UUID billId, UUID me) {
        long udhar = payments.getOrDefault("UDHAR", 0L);
        long creditNote = payments.getOrDefault("CREDIT_NOTE", 0L);
        if (customer == null || udhar == 0 && creditNote == 0) {
            return;
        }
        long due = customer.duePaise();
        long balance = customer.creditNoteBalancePaise();
        if (creditNote > 0) {
            balance -= creditNote;
            customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(), customer.id(),
                    store.id(), "CREDIT_NOTE_USED", creditNote, due, balance, billId, null, null, me));
        }
        if (udhar > 0) {
            due += udhar;
            customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(), customer.id(),
                    store.id(), "CREDIT_BILL", udhar, due, balance, billId, null, null, me));
        }
        customers.setBalances(tenantId, customer.id(), due, balance, me);
    }

    /** One H1 register row per H1 bill line and one Schedule X row per Schedule X bill line (ADR-030 S7, S8). */
    private void registerRows(UUID tenantId, Store store, SaleCart.Cart cart, SalePricer.Priced priced, BillRow bill,
                              List<UUID> lineIds, UUID signer, UUID me) {
        SaleCart.Prescription card = cart.prescription();
        String patientName = null;
        Integer patientAge = null;
        if (priced.patient() != null) {
            patientName = priced.patient().name();
            patientAge = priced.patient().age();
        }
        if (patientName == null) {
            patientName = card != null && card.patientName() != null ? card.patientName()
                    : cart.buyer().walkInName() != null ? cart.buyer().walkInName()
                    : priced.customer() == null ? null : priced.customer().name();
        }
        if (patientAge == null && card != null) {
            patientAge = card.patientAge();
        }
        for (int i = 0; i < priced.lines().size(); i++) {
            SalePricer.PricedLine l = priced.lines().get(i);
            String schedule = l.product().schedule();
            if ("H1".equals(schedule)) {
                registers.insertH1(tenantId, new SaleRegisterRepository.H1Entry(store.unitId(), store.id(), bill.id(),
                        lineIds.get(i), bill.billNo(), bill.patientId(), patientName, patientAge,
                        card == null ? null : card.patientAddress(), card == null ? null : card.prescriberName(),
                        card == null ? null : card.prescriberRegNo(), card == null ? null : card.prescriptionDate(),
                        card == null ? null : card.source(), null, l.product().productId(),
                        l.input().prescribedProductId(), l.batchId(), l.qty(), me,
                        l.input().h1Override() == null ? null : l.input().h1Override().reasonKey()));
            } else if ("X".equals(schedule)) {
                registers.insertScheduleX(tenantId, new SaleRegisterRepository.ScheduleXEntry(store.unitId(), store.id(),
                        bill.id(), lineIds.get(i), bill.billNo(), bill.patientId(), patientName, patientAge,
                        card.prescriberName(), card.prescriberRegNo(), card.prescriptionDate(), l.product().productId(),
                        l.batchId(), l.qty(), me, signer));
            }
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }

}
