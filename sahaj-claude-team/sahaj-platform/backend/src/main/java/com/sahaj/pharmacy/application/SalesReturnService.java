package com.sahaj.pharmacy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.SaleBillRepository.BillRow;
import com.sahaj.pharmacy.application.SaleBillRepository.LineRow;
import com.sahaj.pharmacy.application.SaleInputs.AcceptSalesReturnInput;
import com.sahaj.pharmacy.application.SaleInputs.ReturnLineInput;
import com.sahaj.pharmacy.application.SaleInputs.ReturnRefundInput;
import com.sahaj.pharmacy.application.SaleInputs.SalesReturnCreate;
import com.sahaj.pharmacy.application.SalesReturnRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * SAH-80-18, the sales return (docs/api/pharmacy-sales.yaml pharmacyFindReturnableBills, pharmacyGetReturnableBill,
 * pharmacyListSalesReturns, pharmacyCreateSalesReturn, pharmacyGetSalesReturn, pharmacyAcceptSalesReturn, pharmacyFinishSalesReturn;
 * ADR-030 S5, DEC-074 OQ-80-12) and the approval handler of the type SALES_RETURN (the cashier's return approval, O-8).
 *
 * <p>Outcome of a new return: a cashier's return waits for the pharmacist (WAITING_PHARMACIST, nothing moves, no money); otherwise a
 * refund above the SALES_RETURN rule goes to the engine (WAITING_APPROVAL; an approval makes it APPROVED, and the starter or a
 * pharmacist finishes it); otherwise it completes at once. A return that completes writes, in one transaction: the lines, the
 * SALES_RETURN ledger lines (Sealed to AVAILABLE, doubtful or expired to QUARANTINE), the CREDIT_NOTE number (last), the credit note and
 * refund rows, the customer account entries, the reversing H1 and Schedule X rows, the returned quantity of the bill lines and the
 * history. Lock order: client key, the bill, the customer, the stock rows, the number. Names and notes are never logged.
 */
@Service
public class SalesReturnService implements ApprovalSubjectHandler {

    static final String SUBJECT = "SALES_RETURN";
    static final String CREATE = "pharmacyCreateSalesReturn";
    static final String ACCEPT = "pharmacyAcceptSalesReturn";
    static final String FINISH = "pharmacyFinishSalesReturn";
    static final String WINDOW_NOT_SET = "Return window is not set. Ask your owner.";
    static final String WINDOW_OVER = "Return window is over.";
    static final String BILL_CANCELLED = "This bill was cancelled.";
    static final String RETURN_STATE = "This return is not in the right state for this step.";
    static final String SEALED = "return-condition.sealed";
    private static final Logger log = LoggerFactory.getLogger(SalesReturnService.class);
    private static final String ENTITY_TYPE = "PHARMACY_SALES_RETURN";
    private static final int MAX_SIZE = 25;
    private static final int MAX_BILLS = 25;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final SaleBillRepository bills;
    private final SalesReturnRepository returns;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final SaleRegisterRepository registers;
    private final ApprovalEngine engine;
    private final ApprovalRuleRepository rules;
    private final ActiveReasonService reasons;
    private final DocumentNumberPort numbers;
    private final DocumentSeriesRepository series;
    private final ScheduleXSignature signature;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final DocumentViews documents;
    private final StoreRepository stores;
    private final SaleSupport support;
    private final BlindCount blind;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final ObjectMapper mapper;

    public SalesReturnService(PharmacyGate gate, StoreAccessGuard guard, SaleBillRepository bills, SalesReturnRepository returns,
                              CustomerRepository customers, ProductRepository products, StockRepository stock,
                              StockPosting posting, SaleRegisterRepository registers, ApprovalEngine engine,
                              ApprovalRuleRepository rules, ActiveReasonService reasons, DocumentNumberPort numbers,
                              DocumentSeriesRepository series, ScheduleXSignature signature, ClientRequestRepository clients,
                              DocumentEventRepository events, DocumentViews documents, StoreRepository stores,
                              SaleSupport support, BlindCount blind, AuditLog auditLog, PharmacyTime time, ObjectMapper mapper) {
        this.gate = gate;
        this.guard = guard;
        this.bills = bills;
        this.returns = returns;
        this.customers = customers;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.registers = registers;
        this.engine = engine;
        this.rules = rules;
        this.reasons = reasons;
        this.numbers = numbers;
        this.series = series;
        this.signature = signature;
        this.clients = clients;
        this.events = events;
        this.documents = documents;
        this.stores = stores;
        this.support = support;
        this.blind = blind;
        this.auditLog = auditLog;
        this.time = time;
        this.mapper = mapper;
    }

    /** One refund as typed: a mode and whole paise. */
    record Refund(String mode, long amountPaise) {
    }

    // ---- finding the bill ----

    /** Exactly one of {@code number} or {@code customer}. Writes nothing. */
    @Transactional(readOnly = true)
    public SalesReturnViews.ReturnableBillList findBills(UUID tenantId, UUID userId, String rawStoreId, String rawNumber,
                                                         String rawCustomer) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        String number = SaleSupport.blank(rawNumber);
        String customer = SaleSupport.blank(rawCustomer);
        if (number == null == (customer == null)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("number", "Type the bill number, or a customer name")));
        }
        if (number != null && number.length() > 16 || customer != null && (customer.length() < 2 || customer.length() > 80)) {
            throw new InvalidFieldsException(List.of(new FieldMessage(number != null ? "number" : "customer",
                    "Check what you typed")));
        }
        int days = bills.returnWindowDays(tenantId)
                .orElseThrow(() -> PharmacyProblemException.saleConflict("return-window-not-set", "Return window not set",
                        WINDOW_NOT_SET));
        List<BillRow> found = new ArrayList<>();
        if (number != null) {
            bills.findByNumber(tenantId, storeId, number.toLowerCase(Locale.ROOT)).ifPresent(found::add);
        } else {
            String phone = SaleRules.validPhone(customer) ? customer : null;
            Instant since = time.today().minusDays(days).atStartOfDay(PharmacyTime.IST).toInstant();
            found.addAll(bills.findByBuyer(tenantId, storeId, customer.toLowerCase(Locale.ROOT), phone, since, MAX_BILLS));
        }
        Map<UUID, String> labels = labels(caller, found);
        List<SalesReturnViews.ReturnableBillRow> items = new ArrayList<>();
        for (BillRow b : found) {
            items.add(new SalesReturnViews.ReturnableBillRow(b.id(), b.billNo(), b.savedAt(), labels.get(b.id()),
                    b.netPaise(), window(b, days)));
        }
        return new SalesReturnViews.ReturnableBillList(items);
    }

    /** The bill card with its return lines, refund modes and the approval limit. Writes a record-access entry for a patient. */
    @Transactional
    public SalesReturnViews.ReturnableBill getBill(UUID tenantId, UUID userId, String rawStoreId, String rawBillId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID billId = PharmacyParams.pathId(rawBillId);
        Store store = guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        int days = bills.returnWindowDays(tenantId)
                .orElseThrow(() -> PharmacyProblemException.saleConflict("return-window-not-set", "Return window not set",
                        WINDOW_NOT_SET));
        BillRow bill = bills.find(tenantId, billId).filter(b -> b.storeId().equals(storeId)).orElseThrow(SalesReturnService::notFound);
        if (bill.patientId() != null) {
            support.recordOpened(tenantId, bill.patientId(), userId);
        }
        List<LineRow> lines = bills.lines(tenantId, billId);
        Map<UUID, Integer> open = returns.openQtyByBillLine(tenantId, billId);
        Set<UUID> productIds = new LinkedHashSet<>();
        lines.forEach(l -> productIds.add(l.productId()));
        Map<UUID, ProductRepository.Full> found = products.findAll(tenantId, productIds);
        List<SalesReturnViews.ReturnableLine> views = new ArrayList<>();
        for (LineRow l : lines) {
            ProductRepository.Full p = found.get(l.productId());
            int already = l.returnedQty() + open.getOrDefault(l.id(), 0);
            int left = Math.max(0, l.qty() - already);
            long perUnit = left == 0 ? 0 : SaleRules.returnShare(l.netPaise(), l.taxablePaise(), l.qty(), already, 1).refundPaise();
            views.add(new SalesReturnViews.ReturnableLine(l.id(), IndentService.productRef(p.row()), batchRef(tenantId, l.batchId()),
                    l.qty(), l.returnedQty() + open.getOrDefault(l.id(), 0), left, p.returnable(), perUnit,
                    p.row().schedule() == null ? "NONE" : p.row().schedule()));
        }
        boolean named = bill.customerId() != null;
        CustomerRepository.Row customer = bill.customerId() == null ? null : customers.find(tenantId, bill.customerId()).orElse(null);
        List<SalesReturnViews.RefundMode> modes = refundModes(tenantId, billId, customer);
        Long limit = limitPaise(tenantId);
        return new SalesReturnViews.ReturnableBill(bill.id(), bill.billNo(), bill.savedAt(), labels(caller, List.of(bill)).get(bill.id()),
                bill.netPaise(), window(bill, days), OpeningStockPostService.storeRef(store), named, limit, modes, views);
    }

    // ---- list and get ----

    @Transactional(readOnly = true)
    public SalesReturnViews.SalesReturnPage list(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawPage,
                                                 String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        String status = status(rawStatus);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        boolean everyone = StoreAccessGuard.holds(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN);
        SalesReturnRepository.Slice slice = returns.page(tenantId, storeId, status, everyone ? null : caller.actorId(), size,
                page * size);
        Set<UUID> people = new HashSet<>();
        slice.items().forEach(r -> people.add(r.startedBy()));
        Map<UUID, TeamMember> members = support.members(tenantId, people);
        List<SalesReturnViews.SalesReturnRow> items = new ArrayList<>();
        Map<UUID, BillRow> billsById = billsOf(tenantId, slice.items());
        Map<UUID, String> who = labels(caller, new ArrayList<>(billsById.values()));
        for (Row r : slice.items()) {
            BillRow bill = billsById.get(r.billId());
            items.add(new SalesReturnViews.SalesReturnRow(r.id(), r.status(), r.creditNoteNo(), bill == null ? "" : bill.billNo(),
                    r.startedAt(), bill == null ? "" : who.get(bill.id()), r.refundTotalPaise(),
                    support.person(r.startedBy(), members)));
        }
        return new SalesReturnViews.SalesReturnPage(items, slice.total(), page, size);
    }

    @Transactional
    public SalesReturnViews.SalesReturn get(UUID tenantId, UUID userId, String rawStoreId, String rawReturnId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID returnId = PharmacyParams.pathId(rawReturnId);
        guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        Row row = returns.find(tenantId, returnId).filter(r -> r.storeId().equals(storeId)).orElseThrow(SalesReturnService::notFound);
        if (!StoreAccessGuard.holds(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN) && !row.startedBy().equals(caller.actorId())) {
            throw notFound();
        }
        return view(caller, row);
    }

    // ---- create ----

    /** The second signer to verify before the transaction: a pharmacist saving a return that holds a Schedule X line. */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckCreate(UUID tenantId, UUID userId, String rawStoreId,
                                                           PharmacyInputs.Body<SalesReturnCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        SalesReturnCreate in = body.read();
        guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        if (in.secondSigner() == null || !caller.isPharmacist() || in.billId() == null) {
            return null;
        }
        return hasScheduleX(tenantId, bills.lines(tenantId, in.billId())) ? in.secondSigner() : null;
    }

    @Transactional
    public PharmacyViews.Outcome<SalesReturnViews.SalesReturn> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                      PharmacyInputs.Body<SalesReturnCreate> body,
                                                                      UUID verifiedSigner) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        SalesReturnCreate in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = returns.find(tenantId, own.subjectId()).filter(r -> r.storeId().equals(storeId))
                        .orElseThrow(SalesReturnService::notFound);
                return new PharmacyViews.Outcome<>(view(caller, first), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        if (in.billId() == null) {
            errors.add(new FieldMessage("billId", UnitService.RELOAD));
        }
        String note = SaleSupport.blank(in.note());
        if (in.reasonKey() == null || !reasons.isActive(tenantId, ReasonList.SALES_RETURN, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Enter a note of at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        Map<UUID, Wanted> wanted = wanted(tenantId, in.lines(), errors);
        Map<String, Long> refunds = refunds(in.refunds(), errors, 1);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        UUID returnId = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), CREATE, returnId, false) != null) {
            return create(tenantId, userId, rawStoreId, body, verifiedSigner); // L15
        }

        // locks: the bill, then the customer; the stock rows are taken inside the posting
        BillRow bill = bills.findForUpdate(tenantId, in.billId()).filter(b -> b.storeId().equals(storeId))
                .orElseThrow(SalesReturnService::notFound);
        CustomerRepository.Row customer = bill.customerId() == null ? null
                : customers.findForUpdate(tenantId, bill.customerId()).orElseThrow(SalesReturnService::notFound);
        requireWindow(tenantId, bill);

        List<LineRow> billLines = bills.lines(tenantId, bill.id());
        Map<UUID, LineRow> byId = new HashMap<>();
        billLines.forEach(l -> byId.put(l.id(), l));
        Map<UUID, ProductRepository.Full> found = productsOf(tenantId, billLines);
        Map<UUID, Integer> open = returns.openQtyByBillLine(tenantId, bill.id());
        errors = new ArrayList<>();
        List<Planned> planned = plan(wanted, byId, found, open, errors, time.today(), tenantId);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        boolean anyX = planned.stream().anyMatch(p -> p.scheduleX());
        boolean canAccept = StoreAccessGuard.holds(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN);
        if (anyX && canAccept && !caller.isPharmacist()) {
            throw PharmacyProblemException.pharmacistRequired(SaleBillService.PHARMACIST_ONLY_X);
        }
        UUID signer = null;
        if (anyX && canAccept) {
            if (verifiedSigner == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSigner);
            signer = verifiedSigner;
        }
        long total = planned.stream().mapToLong(p -> p.share().refundPaise()).sum();
        errors = new ArrayList<>();
        checkRefunds(refunds, total, bills.refundModes(tenantId), allowedByMode(tenantId, bill.id()), customer, errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        String refundsJson = json(refunds);

        if (!canAccept) {
            insertReturn(tenantId, store, returnId, bill, "WAITING_PHARMACIST", in.reasonKey(), note, me, null, total, refundsJson,
                    planned, null, null, true);
            return created(caller, tenantId, returnId, "WAITING_PHARMACIST", store, bill, total);
        }
        ApprovalEngine.Plan plan = needsApproval(tenantId, storeId, returnId, bill, total, anyX);
        if (plan != null) {
            UUID approvalId = engine.submit(tenantId, store.unitId(), me, subject(returnId, bill, storeId, total, anyX), plan);
            insertReturn(tenantId, store, returnId, bill, "WAITING_APPROVAL", in.reasonKey(), note, me, approvalId, total,
                    refundsJson, planned, null, null, false);
            return created(caller, tenantId, returnId, "WAITING_APPROVAL", store, bill, total);
        }
        insertReturn(tenantId, store, returnId, bill, "WAITING_PHARMACIST", in.reasonKey(), note, me, null, total, refundsJson,
                planned, null, null, false);
        Row row = returns.findForUpdate(tenantId, returnId).orElseThrow();
        complete(caller, store, bill, customer, row, returns.lines(tenantId, returnId), byId, refunds, signer, "COMPLETED");
        return new PharmacyViews.Outcome<>(view(caller, returns.find(tenantId, returnId).orElseThrow()), true);
    }

    private PharmacyViews.Outcome<SalesReturnViews.SalesReturn> created(PharmacyCaller caller, UUID tenantId, UUID returnId,
                                                                        String status, Store store, BillRow bill, long total) {
        events.insert(tenantId, store.unitId(), SUBJECT, returnId, "CREATED", store.id(), caller.actorId(), null, null, null, null,
                null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("returnId", returnId.toString());
        after.put("billId", bill.id().toString());
        after.put("status", status);
        after.put("refundTotalPaise", total);
        auditLog.record("PHARMACY_SALES_RETURN", ENTITY_TYPE, returnId, null, after);
        log.info("Sales return started returnId={} status={}", returnId, status);
        return new PharmacyViews.Outcome<>(view(caller, returns.find(tenantId, returnId).orElseThrow()), true);
    }

    // ---- accept ----

    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckAccept(UUID tenantId, UUID userId, String rawStoreId, String rawReturnId,
                                                           PharmacyInputs.Body<AcceptSalesReturnInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID returnId = PharmacyParams.pathId(rawReturnId);
        AcceptSalesReturnInput in = body.read();
        guard.requireSale(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN);
        return in.secondSigner() != null && caller.isPharmacist() && returnHasX(tenantId, storeId, returnId) ? in.secondSigner() : null;
    }

    @Transactional
    public SalesReturnViews.SalesReturn accept(UUID tenantId, UUID userId, String rawStoreId, String rawReturnId,
                                               PharmacyInputs.Body<AcceptSalesReturnInput> body, UUID verifiedSigner) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID returnId = PharmacyParams.pathId(rawReturnId);
        AcceptSalesReturnInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN);
        UUID me = caller.actorId();
        if (replay(caller, in.clientKey(), ACCEPT, returnId)) {
            return view(caller, returns.find(tenantId, returnId).orElseThrow(SalesReturnService::notFound));
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        Map<String, Long> refunds = refunds(in.refunds(), errors, 0);
        Map<UUID, String> decisions = new HashMap<>();
        if (in.decisions() != null) {
            for (int i = 0; i < in.decisions().size(); i++) {
                SaleInputs.ReturnDecisionInput d = in.decisions().get(i);
                if (d == null || d.returnLineId() == null || d.decision() == null
                        || !Set.of("ACCEPT", "ACCEPT_QUARANTINE", "REFUSE").contains(d.decision())) {
                    errors.add(new FieldMessage("decisions[" + i + "].decision", "Choose a decision"));
                } else {
                    decisions.put(d.returnLineId(), d.decision());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), ACCEPT, returnId, true) != null) {
            return accept(tenantId, userId, rawStoreId, rawReturnId, body, verifiedSigner); // L15
        }
        Row row = lockedReturn(tenantId, storeId, returnId);
        BillRow bill = bills.findForUpdate(tenantId, row.billId()).orElseThrow(SalesReturnService::notFound);
        CustomerRepository.Row customer = bill.customerId() == null ? null
                : customers.findForUpdate(tenantId, bill.customerId()).orElseThrow(SalesReturnService::notFound);
        if (!"WAITING_PHARMACIST".equals(row.status())) {
            throw PharmacyProblemException.saleConflict("return-state", "Return state", RETURN_STATE);
        }
        if (version != row.version()) {
            throw PharmacyProblemException.changed(view(caller, row));
        }
        List<SalesReturnRepository.LineRow> lines = returns.lines(tenantId, returnId);
        for (UUID id : decisions.keySet()) {
            if (lines.stream().noneMatch(l -> l.id().equals(id))) {
                throw new InvalidFieldsException(List.of(new FieldMessage("decisions", "Check the decisions")));
            }
        }
        List<LineRow> billLines = bills.lines(tenantId, bill.id());
        Map<UUID, LineRow> byId = new HashMap<>();
        billLines.forEach(l -> byId.put(l.id(), l));
        Map<UUID, ProductRepository.Full> found = productsOf(tenantId, billLines);
        LocalDate today = time.today();
        boolean anyX = false;
        long total = 0;
        List<SalesReturnRepository.LineRow> decided = new ArrayList<>();
        Map<UUID, Integer> open = returns.openQtyByBillLine(tenantId, bill.id());
        for (SalesReturnRepository.LineRow l : lines) {
            LineRow bl = byId.get(l.billLineId());
            String decision = decisions.get(l.id());
            boolean doubtful = !SEALED.equals(l.conditionKey());
            if (!doubtful && decision != null && !"ACCEPT".equals(decision)) {
                throw new InvalidFieldsException(List.of(new FieldMessage("decisions", "Only a doubtful line can be refused")));
            }
            String destination = destination(l.conditionKey(), decision, expired(tenantId, l.batchId(), today));
            // this return's own quantity is in the open total: the others (not this return) set the already-returned base
            int already = bl.returnedQty() + open.getOrDefault(bl.id(), 0) - l.qty();
            SaleRules.ReturnShare share = "REFUSED".equals(destination) ? new SaleRules.ReturnShare(0, 0, 0)
                    : SaleRules.returnShare(bl.netPaise(), bl.taxablePaise(), bl.qty(), Math.max(0, already), l.qty());
            total += share.refundPaise();
            anyX |= GoodsReceiptService.isScheduleX(found.get(bl.productId()).row()) && !"REFUSED".equals(destination);
            decided.add(new SalesReturnRepository.LineRow(l.id(), l.billLineId(), l.productId(), l.batchId(), l.qty(),
                    l.conditionKey(), decision == null && doubtful ? "ACCEPT_QUARANTINE" : decision, destination, share.refundPaise(),
                    share.taxablePaise(), share.taxPaise(), l.secondSigner()));
        }
        if (anyX && !caller.isPharmacist()) {
            throw PharmacyProblemException.pharmacistRequired(SaleBillService.PHARMACIST_ONLY_X);
        }
        UUID signer = null;
        if (anyX) {
            if (verifiedSigner == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSigner);
            signer = verifiedSigner;
        }
        errors = new ArrayList<>();
        if (total > 0 || !refunds.isEmpty()) {
            checkRefunds(refunds, total, bills.refundModes(tenantId), allowedByMode(tenantId, bill.id()), customer, errors);
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        for (SalesReturnRepository.LineRow l : decided) {
            returns.updateLine(tenantId, l.id(), l.decision(), l.destination(), l.refundPaise(), l.taxablePaise(), l.taxPaise(),
                    l.secondSigner());
        }
        String refundsJson = json(refunds);
        if (total == 0) {
            returns.markDecided(tenantId, returnId, row.version(), "REJECTED", me);
            events.insert(tenantId, store.unitId(), SUBJECT, returnId, "REJECTED", storeId, me, null, null, null, null, null);
            return view(caller, returns.find(tenantId, returnId).orElseThrow());
        }
        ApprovalEngine.Plan plan = needsApproval(tenantId, storeId, returnId, bill, total, anyX);
        if (plan != null) {
            UUID approvalId = engine.submit(tenantId, store.unitId(), row.startedBy(),
                    subject(returnId, bill, storeId, total, anyX), plan);
            returns.markAccepted(tenantId, returnId, row.version(), "WAITING_APPROVAL", me, approvalId, total, refundsJson, me);
            events.insert(tenantId, store.unitId(), SUBJECT, returnId, "ACCEPTED", storeId, me, null, null, null, null, null);
        } else {
            returns.markAccepted(tenantId, returnId, row.version(), "WAITING_PHARMACIST", me, null, total, refundsJson, me);
            events.insert(tenantId, store.unitId(), SUBJECT, returnId, "ACCEPTED", storeId, me, null, null, null, null, null);
            Row accepted = returns.findForUpdate(tenantId, returnId).orElseThrow();
            complete(caller, store, bill, customer, accepted, returns.lines(tenantId, returnId), byId, refunds, signer, "COMPLETED");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("returnId", returnId.toString());
        after.put("refundTotalPaise", total);
        auditLog.record("PHARMACY_SALES_RETURN_ACCEPTED", ENTITY_TYPE, returnId, Map.of("status", "WAITING_PHARMACIST"), after);
        return view(caller, returns.find(tenantId, returnId).orElseThrow());
    }

    // ---- finish ----

    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckFinish(UUID tenantId, UUID userId, String rawStoreId, String rawReturnId,
                                                           PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID returnId = PharmacyParams.pathId(rawReturnId);
        PharmacyInputs.PostWithSigner in = body.read();
        guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        return in.secondSigner() != null && caller.isPharmacist() && returnHasX(tenantId, storeId, returnId) ? in.secondSigner() : null;
    }

    @Transactional
    public SalesReturnViews.SalesReturn finish(UUID tenantId, UUID userId, String rawStoreId, String rawReturnId,
                                               PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body, UUID verifiedSigner) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID returnId = PharmacyParams.pathId(rawReturnId);
        PharmacyInputs.PostWithSigner in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SALES_RETURN);
        UUID me = caller.actorId();
        if (replay(caller, in.clientKey(), FINISH, returnId)) {
            return view(caller, returns.find(tenantId, returnId).orElseThrow(SalesReturnService::notFound));
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), FINISH, returnId, true) != null) {
            return finish(tenantId, userId, rawStoreId, rawReturnId, body, verifiedSigner); // L15
        }
        Row row = lockedReturn(tenantId, storeId, returnId);
        BillRow bill = bills.findForUpdate(tenantId, row.billId()).orElseThrow(SalesReturnService::notFound);
        CustomerRepository.Row customer = bill.customerId() == null ? null
                : customers.findForUpdate(tenantId, bill.customerId()).orElseThrow(SalesReturnService::notFound);
        if (!"APPROVED".equals(row.status())) {
            throw PharmacyProblemException.saleConflict("return-state", "Return state", RETURN_STATE);
        }
        if (!row.startedBy().equals(me) && !StoreAccessGuard.holds(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN)) {
            throw guard.refuse(caller, storeId, StoreCapability.SALES_RETURN);
        }
        if (version != row.version()) {
            throw PharmacyProblemException.changed(view(caller, row));
        }
        List<SalesReturnRepository.LineRow> lines = returns.lines(tenantId, returnId);
        List<LineRow> billLines = bills.lines(tenantId, bill.id());
        Map<UUID, LineRow> byId = new HashMap<>();
        billLines.forEach(l -> byId.put(l.id(), l));
        Map<UUID, ProductRepository.Full> found = productsOf(tenantId, billLines);
        boolean anyX = lines.stream().anyMatch(l -> !"REFUSED".equals(l.destination())
                && GoodsReceiptService.isScheduleX(found.get(byId.get(l.billLineId()).productId()).row()));
        UUID signer = null;
        if (anyX) {
            if (!caller.isPharmacist()) {
                throw PharmacyProblemException.pharmacistRequired(SaleBillService.PHARMACIST_ONLY_X);
            }
            if (verifiedSigner == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSigner);
            signer = verifiedSigner;
        }
        complete(caller, store, bill, customer, row, lines, byId, parseRefunds(row.refundsJson()), signer, "COMPLETED");
        return view(caller, returns.find(tenantId, returnId).orElseThrow());
    }

    // ---- completion (the one transaction of a finished return) ----

    private void complete(PharmacyCaller caller, Store store, BillRow bill, CustomerRepository.Row customer, Row row,
                          List<SalesReturnRepository.LineRow> lines, Map<UUID, LineRow> billLines, Map<String, Long> refunds,
                          UUID signer, String outcome) {
        UUID tenantId = caller.tenantId();
        UUID me = caller.actorId();
        UUID storeId = store.id();
        LocalDate today = time.today();
        // L7: the shares are worked again from the quantities that were COMPLETED, not from the open returns of the same bill
        // lines, so a rejected earlier return cannot leave a paisa behind. The bill row is locked, so these quantities are final.
        lines = recomputed(lines, billLines);
        List<SalesReturnRepository.LineRow> moving = lines.stream().filter(l -> !"REFUSED".equals(l.destination())).toList();
        Set<UUID> productIds = new LinkedHashSet<>();
        moving.forEach(l -> productIds.add(l.productId()));
        if (!productIds.isEmpty()) {
            stock.lockBalances(tenantId, storeId, productIds); // canonical order, before the number
        }
        long total = moving.stream().mapToLong(SalesReturnRepository.LineRow::refundPaise).sum();
        refunds = reconciled(refunds, total, customer);
        Map<UUID, ProductRepository.Full> productRows = productIds.isEmpty() ? Map.of() : products.findAll(tenantId, productIds);

        // the number, last (rule 28)
        long seq = numbers.next(tenantId, DocType.CREDIT_NOTE);
        String creditNoteNo = StoreUseSupport.render(series, tenantId, DocType.CREDIT_NOTE, seq, today);
        DocumentSeriesRepository.Row unitRow = series.find(tenantId, DocType.CREDIT_NOTE).orElse(null);
        boolean restarts = unitRow == null ? DocType.CREDIT_NOTE.defaultRestarts() : unitRow.restartsEachFinancialYear();

        Map<UUID, UUID> original = new HashMap<>();
        // one COUNTER_SALE ledger line per bill line (SaleBillService writes exactly one), so the first is the only one
        for (SaleBillRepository.SaleLedger l : bills.saleLedger(tenantId, bill.id())) {
            original.putIfAbsent(l.billLineId(), l.ledgerId());
        }
        List<StockPosting.Line> ledger = new ArrayList<>();
        int rowNo = 0;
        for (SalesReturnRepository.LineRow l : moving) {
            rowNo++;
            boolean isX = GoodsReceiptService.isScheduleX(productRows.get(l.productId()).row());
            ledger.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.valueOf(l.destination()), l.qty(),
                    "SALES_RETURN", row.reasonKey(), UUID.randomUUID(), original.get(l.billLineId()),
                    new StockPosting.Doc(SUBJECT, row.id(), creditNoteNo, l.id()), rowNo, isX ? signer : null));
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), ledger);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }
        for (SalesReturnRepository.LineRow l : lines) {
            returns.updateLine(tenantId, l.id(), l.decision(), l.destination(), l.refundPaise(), l.taxablePaise(), l.taxPaise(),
                    l.secondSigner());
        }
        registers.insertCreditNote(tenantId, new SaleRegisterRepository.NewCreditNote(UUID.randomUUID(), store.unitId(), storeId,
                creditNoteNo, restarts ? DocumentNumbers.financialYearStart(today) : 0, "SALES_RETURN", bill.id(), row.id(), total), me);
        for (Map.Entry<String, Long> r : refunds.entrySet()) {
            registers.insertRefund(tenantId, new SaleRegisterRepository.NewRefund(store.unitId(), storeId, "SALES_RETURN",
                    bill.id(), row.id(), r.getKey(), r.getValue(), creditNoteNo), me);
        }
        if (customer != null) {
            long owed = customer.duePaise();
            long balance = customer.creditNoteBalancePaise();
            long toCredit = refunds.getOrDefault("CREDIT_NOTE", 0L);
            long toUdhar = Math.min(refunds.getOrDefault("UDHAR", 0L), owed);
            if (toUdhar > 0) {
                owed -= toUdhar;
                customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(), customer.id(),
                        storeId, "RETURN_TO_UDHAR", toUdhar, owed, balance, bill.id(), row.id(), null, me));
            }
            if (toCredit > 0) {
                balance += toCredit;
                customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(), customer.id(),
                        storeId, "CREDIT_NOTE_ISSUED", toCredit, owed, balance, bill.id(), row.id(), null, me));
            }
            if (toUdhar > 0 || toCredit > 0) {
                customers.setBalances(tenantId, customer.id(), owed, balance, me);
            }
        }
        for (SalesReturnRepository.LineRow l : moving) {
            bills.addReturnedQty(tenantId, l.billLineId(), l.qty());
            LineRow bl = billLines.get(l.billLineId());
            if (bl == null) {
                continue;
            }
            ProductRepository.Row product = productRows.get(l.productId()).row();
            if ("H1".equals(product.schedule())) {
                registers.reverseH1ForReturn(tenantId, l.billLineId(), l.qty(), creditNoteNo);
            } else if (GoodsReceiptService.isScheduleX(product) && signer != null) {
                registers.reverseScheduleXForReturn(tenantId, l.billLineId(), l.qty(), row.id(), creditNoteNo, me, signer);
            }
        }
        if (!returns.markCompleted(tenantId, row.id(), row.version(), me, creditNoteNo, total, json(refunds), null, null)) {
            throw PharmacyProblemException.changed(null);
        }
        events.insert(tenantId, store.unitId(), SUBJECT, row.id(), outcome, storeId, me, null, null, null, null, null);
        if (signer != null) {
            events.insert(tenantId, store.unitId(), SUBJECT, row.id(), "SECOND_SIGNATURE", storeId, me, null, null, null,
                    null, null);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("returnId", row.id().toString());
        after.put("billId", bill.id().toString());
        after.put("creditNoteNo", creditNoteNo);
        after.put("lines", moving.size());
        after.put("refundTotalPaise", total);
        auditLog.record("PHARMACY_SALES_RETURN", ENTITY_TYPE, row.id(), null, after);
        log.info("Sales return completed returnId={} refundTotalPaise={}", row.id(), total);
    }

    /** The lines with their refund, taxable and tax parts worked from the completed returned quantity of each bill line. */
    static List<SalesReturnRepository.LineRow> recomputed(List<SalesReturnRepository.LineRow> lines, Map<UUID, LineRow> billLines) {
        List<SalesReturnRepository.LineRow> out = new ArrayList<>();
        for (SalesReturnRepository.LineRow l : lines) {
            LineRow bl = billLines.get(l.billLineId());
            if ("REFUSED".equals(l.destination()) || bl == null) {
                out.add(l);
                continue;
            }
            SaleRules.ReturnShare share = SaleRules.returnShare(bl.netPaise(), bl.taxablePaise(), bl.qty(), bl.returnedQty(), l.qty());
            out.add(new SalesReturnRepository.LineRow(l.id(), l.billLineId(), l.productId(), l.batchId(), l.qty(), l.conditionKey(),
                    l.decision(), l.destination(), share.refundPaise(), share.taxablePaise(), share.taxPaise(), l.secondSigner()));
        }
        return out;
    }

    /**
     * The refund modes after the shares were worked again: when the total moved (a paisa or two), the difference goes to the
     * largest mode that is not UDHAR (UDHAR only when it is the only mode and the customer owes enough), so the modes still add up to
     * the credit note. A change that cannot be placed answers 409 changed.
     */
    static Map<String, Long> reconciled(Map<String, Long> refunds, long total, CustomerRepository.Row customer) {
        long paid = refunds.values().stream().mapToLong(Long::longValue).sum();
        if (refunds.isEmpty() || paid == total) {
            return refunds;
        }
        long delta = total - paid;
        String target = null;
        for (Map.Entry<String, Long> e : refunds.entrySet()) {
            if (!"UDHAR".equals(e.getKey()) && e.getValue() + delta >= 0 && (target == null || e.getValue() > refunds.get(target))) {
                target = e.getKey();
            }
        }
        if (target == null && refunds.containsKey("UDHAR") && refunds.get("UDHAR") + delta >= 0
                && refunds.get("UDHAR") + delta <= (customer == null ? 0L : customer.duePaise())) {
            target = "UDHAR";
        }
        if (target == null) {
            throw PharmacyProblemException.changed(null);
        }
        Map<String, Long> out = new LinkedHashMap<>(refunds);
        out.put(target, out.get(target) + delta);
        out.values().removeIf(v -> v == 0L);
        return out;
    }

    // ---- planning and checks ----

    /** One requested line after the checks. */
    record Wanted(UUID billLineId, int qty, String conditionKey) {
    }

    /** One line with its share of the bill line's amounts. */
    record Planned(LineRow billLine, ProductRepository.Row product, int qty, String conditionKey, String destination,
                   SaleRules.ReturnShare share, boolean scheduleX) {
    }

    private Map<UUID, Wanted> wanted(UUID tenantId, List<ReturnLineInput> in, List<FieldMessage> errors) {
        Map<UUID, Wanted> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty() || in.size() > 100) {
            errors.add(new FieldMessage("lines", in != null && in.size() > 100 ? "A return can have at most 100 items."
                    : "Choose at least one item to return."));
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            ReturnLineInput l = in.get(i);
            Integer qty = l == null ? null : PharmacyParams.wholeNumber(l.qty(), 1, SaleCart.MAX_QTY);
            if (l == null || l.billLineId() == null) {
                errors.add(new FieldMessage("lines[" + i + "].billLineId", UnitService.RELOAD));
            } else if (qty == null) {
                errors.add(new FieldMessage("lines[" + i + "].qty", "Enter a whole number of 1 or more"));
            } else if (l.conditionKey() == null || !reasons.isActive(tenantId, ReasonList.RETURN_CONDITION, l.conditionKey())) {
                errors.add(new FieldMessage("lines[" + i + "].conditionKey", "Choose the condition"));
            } else if (out.putIfAbsent(l.billLineId(), new Wanted(l.billLineId(), qty, l.conditionKey())) != null) {
                errors.add(new FieldMessage("lines[" + i + "].billLineId", "Use each item once"));
            }
        }
        return out;
    }

    /** Checks each line against the bill and works out its refund share; adds field errors together. */
    private List<Planned> plan(Map<UUID, Wanted> wanted, Map<UUID, LineRow> byId, Map<UUID, ProductRepository.Full> found,
                               Map<UUID, Integer> open, List<FieldMessage> errors, LocalDate today, UUID tenantId) {
        List<Planned> out = new ArrayList<>();
        int index = 0;
        for (Wanted w : wanted.values()) {
            LineRow bl = byId.get(w.billLineId());
            if (bl == null) {
                errors.add(new FieldMessage("lines[" + index + "].billLineId", UnitService.RELOAD));
                index++;
                continue;
            }
            ProductRepository.Full p = found.get(bl.productId());
            int already = bl.returnedQty() + open.getOrDefault(bl.id(), 0);
            int left = bl.qty() - already;
            if (!p.returnable()) {
                errors.add(new FieldMessage("lines[" + index + "].qty", "Not returnable"));
            } else if (w.qty() > left) {
                errors.add(new FieldMessage("lines[" + index + "].qty", "You can return at most " + Math.max(0, left) + "."));
            } else {
                out.add(new Planned(bl, p.row(), w.qty(), w.conditionKey(),
                        destination(w.conditionKey(), null, expired(tenantId, bl.batchId(), today)),
                        SaleRules.returnShare(bl.netPaise(), bl.taxablePaise(), bl.qty(), already, w.qty()),
                        GoodsReceiptService.isScheduleX(p.row())));
            }
            index++;
        }
        return out;
    }

    /** The destination of a returned line (ADR-030 S5): Sealed to AVAILABLE; doubtful or expired to QUARANTINE; REFUSE refuses. */
    static String destination(String conditionKey, String decision, boolean expired) {
        if ("REFUSE".equals(decision)) {
            return "REFUSED";
        }
        if ("ACCEPT_QUARANTINE".equals(decision) || expired) {
            return "QUARANTINE";
        }
        if (SEALED.equals(conditionKey) || "ACCEPT".equals(decision)) {
            return "AVAILABLE";
        }
        return "QUARANTINE";
    }

    private boolean expired(UUID tenantId, UUID batchId, LocalDate today) {
        return stock.findBatch(tenantId, batchId).map(b -> b.expiryDate().isBefore(today)).orElse(false);
    }

    private void requireWindow(UUID tenantId, BillRow bill) {
        int days = bills.returnWindowDays(tenantId).orElseThrow(() -> PharmacyProblemException.saleConflict(
                "return-window-not-set", "Return window not set", WINDOW_NOT_SET));
        if ("CANCELLED".equals(bill.status())) {
            throw PharmacyProblemException.saleConflict("return-state", "Return state", BILL_CANCELLED);
        }
        if ("OVER".equals(window(bill, days).state())) {
            throw PharmacyProblemException.saleConflict("return-window-over", "Return window over", WINDOW_OVER);
        }
    }

    private SalesReturnViews.WindowState window(BillRow bill, int days) {
        LocalDate billDay = bill.savedAt().atZone(PharmacyTime.IST).toLocalDate();
        LocalDate last = billDay.plusDays(days);
        String state = "CANCELLED".equals(bill.status()) ? "CANCELLED" : time.today().isAfter(last) ? "OVER" : "WITHIN";
        return new SalesReturnViews.WindowState(state, days, last);
    }

    /** One to five refunds (a minimum of {@code min}), each mode once, whole paise of at least 1. */
    private static Map<String, Long> refunds(List<ReturnRefundInput> in, List<FieldMessage> errors, int min) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (in == null || in.size() < min || in.size() > 5) {
            errors.add(new FieldMessage("refunds", "Choose how the money goes back."));
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            ReturnRefundInput r = in.get(i);
            Long amount = r == null ? null : SaleSupport.wholePaise(r.amountPaise(), CustomerService.MAX_LIMIT_PAISE);
            if (r == null || r.mode() == null || !Set.of("CASH", "UPI", "CARD", "CREDIT_NOTE", "UDHAR").contains(r.mode())) {
                errors.add(new FieldMessage("refunds[" + i + "].mode", "Choose a refund mode"));
            } else if (amount == null || amount < 1) {
                errors.add(new FieldMessage("refunds[" + i + "].amountPaise", "Enter an amount of at least ₹ 0.01"));
            } else if (out.putIfAbsent(r.mode(), amount) != null) {
                errors.add(new FieldMessage("refunds[" + i + "].mode", "Use each refund mode once"));
            }
        }
        return out;
    }

    /** The refunds add up to the total (payment-mismatch on the field), each mode is allowed and within what it can give. */
    static void checkRefunds(Map<String, Long> refunds, long total, List<String> allowed, Map<String, Long> paidLeft,
                             CustomerRepository.Row customer, List<FieldMessage> errors) {
        long sum = refunds.values().stream().mapToLong(Long::longValue).sum();
        if (sum != total) {
            errors.add(new FieldMessage("refunds", "Refunds must add up to " + SaleRules.rupeesText(total) + "."));
        }
        // the udhar-above-due check lives in BillCancelService.checkRefunds, shared by the cancelled-bill refund and this return
        BillCancelService.checkRefunds(refunds, sum, allowed, paidLeft, customer, errors);
    }

    /** What each original mode can still give back: paid less already refunded. */
    private Map<String, Long> allowedByMode(UUID tenantId, UUID billId) {
        Map<String, Long> left = new HashMap<>();
        bills.payments(tenantId, billId).forEach(p -> left.put(p.mode(), p.amountPaise()));
        bills.refundedByMode(tenantId, billId).forEach((mode, amount) -> left.merge(mode, -amount, Long::sum));
        return left;
    }

    private List<SalesReturnViews.RefundMode> refundModes(UUID tenantId, UUID billId, CustomerRepository.Row customer) {
        List<String> allowed = bills.refundModes(tenantId);
        Map<String, Long> left = allowedByMode(tenantId, billId);
        List<SalesReturnViews.RefundMode> out = new ArrayList<>();
        boolean original = allowed.contains("ORIGINAL_MODE");
        for (String mode : List.of("CASH", "UPI", "CARD")) {
            long own = Math.max(0, left.getOrDefault(mode, 0L));
            if (original && own > 0) {
                out.add(new SalesReturnViews.RefundMode(mode, own, true));
            } else if ("CASH".equals(mode) && allowed.contains("CASH")) {
                out.add(new SalesReturnViews.RefundMode(mode, null, false));
            }
        }
        if (customer != null && allowed.contains("CREDIT_NOTE")) {
            out.add(new SalesReturnViews.RefundMode("CREDIT_NOTE", null, false));
        }
        if (customer != null && allowed.contains("UDHAR")) {
            out.add(new SalesReturnViews.RefundMode("UDHAR", customer.duePaise(), false));
        }
        return out;
    }

    /** The SALES_RETURN rule's amount ("Returns above ₹ x need approval"); null when no amount condition applies. */
    private Long limitPaise(UUID tenantId) {
        for (ApprovalRule rule : ruleSet(tenantId)) {
            if (rule.type() == TransactionType.SALES_RETURN) {
                return rule.levels().stream().filter(l -> l.condition().kind() == ConditionKind.ABOVE_AMOUNT)
                        .map(l -> l.condition().amountPaise()).findFirst().orElse(null);
            }
        }
        return null;
    }

    private List<ApprovalRule> ruleSet(UUID tenantId) {
        ApprovalRuleRepository.Version version = rules.current(tenantId).orElse(null);
        return version == null || version.id() == null ? ApprovalPresets.defaults() : rules.rules(tenantId, version.id());
    }

    private ApprovalEngine.Subject subject(UUID returnId, BillRow bill, UUID storeId, long total, boolean anyX) {
        return new ApprovalEngine.Subject(TransactionType.SALES_RETURN, SUBJECT, returnId, "Return of bill " + bill.billNo(),
                storeId, total, anyX, 0L, false, null);
    }

    private ApprovalEngine.Plan needsApproval(UUID tenantId, UUID storeId, UUID returnId, BillRow bill, long total, boolean anyX) {
        return engine.plan(tenantId, subject(returnId, bill, storeId, total, anyX));
    }

    private void insertReturn(UUID tenantId, Store store, UUID id, BillRow bill, String status, String reasonKey, String note,
                              UUID me, UUID approvalId, long total, String refundsJson, List<Planned> planned, UUID acceptedBy,
                              Instant acceptedAt, boolean pending) {
        returns.insert(tenantId, new Row(id, store.unitId(), store.id(), bill.id(), status, reasonKey, note, me, null, acceptedBy,
                acceptedAt, null, null, approvalId, total, refundsJson, null, 0L), me);
        for (Planned p : planned) {
            returns.insertLine(tenantId, store.unitId(), id, new SalesReturnRepository.LineRow(UUID.randomUUID(), p.billLine().id(),
                    p.billLine().productId(), p.billLine().batchId(), p.qty(), p.conditionKey(), null,
                    pending ? "PENDING" : p.destination(), p.share().refundPaise(), p.share().taxablePaise(), p.share().taxPaise(), null), me);
        }
    }

    // ---- small helpers ----

    private boolean replay(PharmacyCaller caller, UUID clientKey, String operation, UUID returnId) {
        if (clientKey == null) {
            return false;
        }
        ClientRequestRepository.Taken own = clients.own(caller.tenantId(), caller.actorId(), clientKey).orElse(null);
        if (own != null) {
            if (!operation.equals(own.operation()) || !returnId.equals(own.subjectId())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            return true;
        }
        if (clients.heldByAnyone(caller.tenantId(), clientKey)) {
            throw PharmacyProblemException.clientKeyReused();
        }
        return false;
    }

    private Row lockedReturn(UUID tenantId, UUID storeId, UUID returnId) {
        return returns.findForUpdate(tenantId, returnId).filter(r -> r.storeId().equals(storeId))
                .orElseThrow(SalesReturnService::notFound);
    }

    private boolean returnHasX(UUID tenantId, UUID storeId, UUID returnId) {
        Row row = returns.find(tenantId, returnId).filter(r -> r.storeId().equals(storeId)).orElse(null);
        if (row == null) {
            return false;
        }
        Set<UUID> ids = new HashSet<>();
        returns.lines(tenantId, returnId).forEach(l -> ids.add(l.productId()));
        return ids.isEmpty() ? false : products.findAll(tenantId, ids).values().stream()
                .anyMatch(f -> GoodsReceiptService.isScheduleX(f.row()));
    }

    private boolean hasScheduleX(UUID tenantId, List<LineRow> lines) {
        Set<UUID> ids = new HashSet<>();
        lines.forEach(l -> ids.add(l.productId()));
        return !ids.isEmpty() && products.findAll(tenantId, ids).values().stream()
                .anyMatch(f -> GoodsReceiptService.isScheduleX(f.row()));
    }

    private Map<UUID, ProductRepository.Full> productsOf(UUID tenantId, List<LineRow> lines) {
        Set<UUID> ids = new HashSet<>();
        lines.forEach(l -> ids.add(l.productId()));
        return products.findAll(tenantId, ids);
    }

    private static String status(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!Set.of("WAITING_PHARMACIST", "WAITING_APPROVAL", "APPROVED", "COMPLETED", "REJECTED").contains(raw)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("status", "Choose a valid option")));
        }
        return raw;
    }

    private String json(Map<String, Long> refunds) {
        List<Map<String, Object>> list = new ArrayList<>();
        refunds.forEach((mode, amount) -> list.add(Map.of("mode", mode, "amountPaise", amount)));
        try {
            return mapper.writeValueAsString(list);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> parseRefunds(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> list = mapper.readValue(text, List.class);
            for (Map<String, Object> m : list) {
                out.put((String) m.get("mode"), ((Number) m.get("amountPaise")).longValue());
            }
        } catch (JsonProcessingException broken) {
            throw new IllegalStateException(broken);
        }
        return out;
    }

    private StockViews.BatchRef batchRef(UUID tenantId, UUID batchId) {
        return stock.findBatch(tenantId, batchId).map(b -> new StockViews.BatchRef(b.id(), b.batchNo(), b.expiryDate()))
                .orElse(new StockViews.BatchRef(batchId, "", null));
    }

    /** The bills of these returns in one read (no query per row). */
    private Map<UUID, BillRow> billsOf(UUID tenantId, List<Row> rows) {
        Set<UUID> ids = new LinkedHashSet<>();
        rows.forEach(r -> ids.add(r.billId()));
        return bills.findAll(tenantId, ids);
    }

    /** "Walk-in", or the customer or patient name: in full for the owner and a pharmacist, masked for a cashier. */
    private Map<UUID, String> labels(PharmacyCaller caller, List<BillRow> list) {
        UUID tenantId = caller.tenantId();
        Set<UUID> patientIds = new HashSet<>();
        Set<UUID> customerIds = new HashSet<>();
        list.forEach(b -> {
            if (b.patientId() != null) {
                patientIds.add(b.patientId());
            }
            if (b.customerId() != null) {
                customerIds.add(b.customerId());
            }
        });
        Map<UUID, SaleSupport.PatientCard> cards = Map.of();
        try {
            cards = support.patientCards(tenantId, patientIds, false);
        } catch (PharmacyProblemException unreachable) {
            // a label never fails for a name
        }
        Map<UUID, CustomerRepository.Row> customerRows = customerIds.isEmpty() ? Map.of() : customers.findAll(tenantId, customerIds);
        boolean full = caller.isOwner() || caller.isPharmacist();
        Map<UUID, String> out = new HashMap<>();
        for (BillRow b : list) {
            String name = null;
            if (b.patientId() != null) {
                SaleSupport.PatientCard card = cards.get(b.patientId());
                name = card == null ? "Patient" : card.name();
            } else if (b.customerId() != null && customerRows.get(b.customerId()) != null
                    && customerRows.get(b.customerId()).name() != null) {
                name = customerRows.get(b.customerId()).name();
            } else if (b.walkInName() != null) {
                name = b.walkInName();
            }
            out.put(b.id(), name == null ? "Walk-in" : full ? name : SaleRules.maskName(name));
        }
        return out;
    }

    /**
     * The counter returns waiting for a pharmacist (status WAITING_PHARMACIST) as one row per returned line, for the tab "Returns" of
     * the pharmacy (pharmacyReturnsWaiting). Someone who cannot accept a return (a cashier) sees the returns they started only. The
     * caller has passed the store guard. {@code total} counts returns, not lines.
     */
    IpdViews.ReturnsWaitingSlice waitingCounterReturns(PharmacyCaller caller, UUID storeId, int page, int size) {
        UUID tenantId = caller.tenantId();
        boolean everyone = StoreAccessGuard.holds(caller, storeId, StoreCapability.ACCEPT_SALES_RETURN);
        SalesReturnRepository.Slice slice = returns.page(tenantId, storeId, "WAITING_PHARMACIST", everyone ? null : caller.actorId(),
                size, page * size);
        Map<String, String> reasonLabels = reasons.labels(tenantId);
        List<IpdViews.ReturnsWaitingRow> rows = new ArrayList<>();
        Map<UUID, BillRow> billsById = billsOf(tenantId, slice.items());
        Map<UUID, String> labelOf = labels(caller, new ArrayList<>(billsById.values()));
        for (Row r : slice.items()) {
            BillRow bill = billsById.get(r.billId());
            if (bill == null) {
                continue;
            }
            String who = labelOf.get(bill.id());
            List<SalesReturnRepository.LineRow> lines = returns.lines(tenantId, r.id());
            Set<UUID> productIds = new LinkedHashSet<>();
            lines.forEach(l -> productIds.add(l.productId()));
            Map<UUID, ProductRepository.Full> found = productIds.isEmpty() ? Map.of() : products.findAll(tenantId, productIds);
            StockViews.ReasonRef reason = new StockViews.ReasonRef(r.reasonKey(),
                    reasonLabels.getOrDefault(r.reasonKey(), r.reasonKey()), r.note());
            for (SalesReturnRepository.LineRow l : lines) {
                ProductRepository.Full full = found.get(l.productId());
                if (full == null) {
                    continue;
                }
                rows.add(new IpdViews.ReturnsWaitingRow("SALES_RETURN", r.id(), "OPD counter", who == null ? "" : who,
                        bill.billNo(), IndentService.productRef(full.row()), batchRef(tenantId, l.batchId()), l.qty(), reason,
                        reasonLabels.getOrDefault(l.conditionKey(), l.conditionKey()), null, l.refundPaise(),
                        everyone ? List.of("ACCEPT") : List.of()));
            }
        }
        return new IpdViews.ReturnsWaitingSlice(rows, slice.total());
    }

    private SalesReturnViews.SalesReturn view(PharmacyCaller caller, Row row) {
        UUID tenantId = caller.tenantId();
        BillRow bill = bills.find(tenantId, row.billId()).orElseThrow(SalesReturnService::notFound);
        List<SalesReturnRepository.LineRow> lines = returns.lines(tenantId, row.id());
        Set<UUID> productIds = new LinkedHashSet<>();
        lines.forEach(l -> productIds.add(l.productId()));
        Map<UUID, ProductRepository.Full> found = productIds.isEmpty() ? Map.of() : products.findAll(tenantId, productIds);
        Map<String, String> labels = reasons.labels(tenantId);
        List<SalesReturnViews.ReturnLineView> lineViews = new ArrayList<>();
        for (SalesReturnRepository.LineRow l : lines) {
            ProductRepository.Row p = found.get(l.productId()).row();
            lineViews.add(new SalesReturnViews.ReturnLineView(l.id(), l.billLineId(), IndentService.productRef(p),
                    batchRef(tenantId, l.batchId()), l.qty(), new StockViews.ReasonRef(l.conditionKey(),
                    labels.getOrDefault(l.conditionKey(), l.conditionKey()), null), l.destination(), l.refundPaise(),
                    l.taxablePaise(), l.taxPaise(), SaleRules.percent(p.gstRate())));
        }
        List<SalesReturnViews.RefundView> refunds = new ArrayList<>();
        parseRefunds(row.refundsJson()).forEach((mode, amount) -> refunds.add(new SalesReturnViews.RefundView(mode, amount)));
        Set<UUID> people = new HashSet<>();
        people.add(row.startedBy());
        if (row.acceptedBy() != null) {
            people.add(row.acceptedBy());
        }
        if (row.completedBy() != null) {
            people.add(row.completedBy());
        }
        Map<UUID, TeamMember> members = support.members(tenantId, people);
        Store store = stores.find(tenantId, row.storeId()).orElseThrow(SalesReturnService::notFound);
        DocumentViews.Loaded loaded = documents.load(tenantId, SUBJECT, row.id(), people);
        boolean canAccept = "WAITING_PHARMACIST".equals(row.status())
                && StoreAccessGuard.holds(caller, row.storeId(), StoreCapability.ACCEPT_SALES_RETURN);
        boolean canFinish = "APPROVED".equals(row.status()) && (row.startedBy().equals(caller.actorId())
                || StoreAccessGuard.holds(caller, row.storeId(), StoreCapability.ACCEPT_SALES_RETURN));
        Map<String, Object> print = row.creditNoteNo() == null ? null : print(tenantId, row, bill, lineViews, refunds);
        return new SalesReturnViews.SalesReturn(row.id(), row.status(), row.creditNoteNo(),
                new SalesReturnViews.BillRef(bill.id(), bill.billNo(), bill.savedAt(), labels(caller, List.of(bill)).get(bill.id())),
                OpeningStockPostService.storeRef(store), lineViews, new StockViews.ReasonRef(row.reasonKey(),
                labels.getOrDefault(row.reasonKey(), row.reasonKey()), row.note()), refunds, row.refundTotalPaise(),
                support.person(row.startedBy(), members), row.startedAt(), row.acceptedBy() == null ? null
                : support.person(row.acceptedBy(), members), row.completedBy() == null ? null
                : support.person(row.completedBy(), members), row.completedAt(),
                row.approvalRequestId() == null ? null : engine.summary(caller, SUBJECT, row.id()), canAccept, canFinish, print,
                documents.history(tenantId, loaded), row.version());
    }

    private Map<String, Object> print(UUID tenantId, Row row, BillRow bill, List<SalesReturnViews.ReturnLineView> lines,
                                      List<SalesReturnViews.RefundView> refunds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", support.invoiceHeader(tenantId));
        out.put("unitName", support.unitName(tenantId));
        out.put("creditNoteNo", row.creditNoteNo());
        out.put("issuedAt", returns.creditNoteIssuedAt(tenantId, row.creditNoteNo()).orElse(row.completedAt()));
        out.put("billNo", bill.billNo());
        Map<String, long[]> byRate = new LinkedHashMap<>();
        for (SalesReturnViews.ReturnLineView l : lines) {
            long[] sums = byRate.computeIfAbsent(l.gstRate(), k -> new long[2]);
            sums[0] += l.taxablePaise();
            sums[1] += l.taxPaise();
        }
        List<Map<String, Object>> taxByRate = new ArrayList<>();
        byRate.forEach((rate, sums) -> taxByRate.add(Map.of("gstRate", rate, "taxablePaise", sums[0], "taxPaise", sums[1])));
        out.put("taxByRate", taxByRate);
        out.put("refunds", refunds);
        return out;
    }

    // ---- approval handler (SALES_RETURN) ----

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = returns.find(tenantId, request.subjectId()).orElse(null);
        String billNo = row == null ? null : bills.find(tenantId, row.billId()).map(BillRow::billNo).orElse(null);
        String what = row == null ? request.subjectNo() : "Sales return of bill " + billNo + " · "
                + SaleRules.rupeesText(row.refundTotalPaise());
        return new Summary(what, "SALES_RETURN", request.ruleStoreId(), List.of("APPROVE", "REJECT", "REVIEW"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.SALES_RETURN);
    }

    /** The approval makes the return APPROVED; nothing moves until the starter or a pharmacist finishes it. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = returns.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(SalesReturnService::notFound);
        if ("WAITING_APPROVAL".equals(row.status())) {
            returns.markDecided(tenantId, row.id(), row.version(), "APPROVED", decision.approver().actorId());
            events.insert(tenantId, row.unitId(), SUBJECT, row.id(), "APPROVED", row.storeId(), decision.approver().actorId(),
                    decision.actingAs(), null, null, null, null);
        }
    }

    /** A rejection makes the return REJECTED (final); no stock moves and no money. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = returns.findForUpdate(tenantId, decision.request().subjectId()).orElseThrow(SalesReturnService::notFound);
        if ("WAITING_APPROVAL".equals(row.status())) {
            returns.markDecided(tenantId, row.id(), row.version(), "REJECTED", decision.approver().actorId());
            events.insert(tenantId, row.unitId(), SUBJECT, row.id(), "REJECTED", row.storeId(), decision.approver().actorId(),
                    decision.actingAs(), null, null, null, decision.input() == null ? null : decision.input().reasonKey());
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }
}
