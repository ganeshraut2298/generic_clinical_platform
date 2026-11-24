package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.SaleBillRepository.BillRow;
import com.sahaj.pharmacy.application.SaleBillRepository.LineRow;
import com.sahaj.pharmacy.application.SaleInputs.CancelBillInput;
import com.sahaj.pharmacy.application.SaleInputs.RefundInput;
import com.sahaj.pharmacy.application.SaleInputs.RefundLine;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-5, cancelling a saved bill and settling its refund (docs/api/pharmacy-sales.yaml pharmacyCancelBill and
 * pharmacyRecordCancelledBillRefund; ADR-030 S5, DEC-074 OQ-80-13). No approval engine.
 *
 * <p><b>Cancel</b>, in one transaction: the client key, the bill row and the customer row {@code FOR UPDATE}, then the stock rows
 * inside {@link StockPosting}. The units come back with COUNTER_SALE_CANCELLED lines that point at the originals (to AVAILABLE when
 * "back on the shelf", else to QUARANTINE; an expired batch always goes to QUARANTINE), the H1 and Schedule X rows are reversed in
 * the database, the udhar part comes off the due (never below zero), a credit note paid is given back to the balance, and the cash,
 * UPI and card paid become "Refund due". The number is kept. A pharmacist may cancel only on the IST day of the bill. A Schedule X
 * bill is cancelled by a pharmacist with a verified second signature (outside the transaction, ADR-029 P7).
 *
 * <p><b>Refund</b>: the refunds add up to the refund due; a CREDIT_NOTE number is taken last (rule 28), the credit note and the
 * refund rows are written, and a credit-note or udhar refund moves the customer's account. Audit rows and logs hold ids, the reason
 * key and amounts; never a name or a note (rule 29).
 */
@Service
public class BillCancelService {

    static final String CANCEL = "pharmacyCancelBill";
    static final String REFUND = "pharmacyRecordCancelledBillRefund";
    static final String DOC_TYPE = "BILL";
    static final String ALREADY_CANCELLED = "This bill is already cancelled.";
    static final String HAS_RETURNS = "This bill has a sales return and cannot be cancelled.";
    static final String NOT_CANCELLED = "This bill is not cancelled.";
    static final String REFUND_RECORDED = "The refund of this bill was already recorded.";
    private static final Logger log = LoggerFactory.getLogger(BillCancelService.class);
    private static final String ENTITY_TYPE = "PHARMACY_BILL";
    private static final Set<String> REFUND_MODES = Set.of("CASH", "UPI", "CARD", "CREDIT_NOTE", "UDHAR");

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final SaleBillRepository bills;
    private final CustomerRepository customers;
    private final SaleRegisterRepository registers;
    private final SaleBillAssembler assembler;
    private final ActiveReasonService reasons;
    private final DocumentNumberPort numbers;
    private final DocumentSeriesRepository series;
    private final ScheduleXSignature signature;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final BlindCount blind;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public BillCancelService(PharmacyGate gate, StoreAccessGuard guard, ProductRepository products, StockRepository stock,
                             StockPosting posting, SaleBillRepository bills, CustomerRepository customers,
                             SaleRegisterRepository registers, SaleBillAssembler assembler, ActiveReasonService reasons,
                             DocumentNumberPort numbers, DocumentSeriesRepository series, ScheduleXSignature signature,
                             ClientRequestRepository clients, DocumentEventRepository events, BlindCount blind,
                             AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.bills = bills;
        this.customers = customers;
        this.registers = registers;
        this.assembler = assembler;
        this.reasons = reasons;
        this.numbers = numbers;
        this.series = series;
        this.signature = signature;
        this.clients = clients;
        this.events = events;
        this.blind = blind;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * The first step of a cancel, before any PIN is checked: the gate, the path ids, the body and the store guard. Returns the
     * second signer the body names only for a pharmacist cancelling a bill that has a Schedule X line.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId, String rawBillId,
                                                     PharmacyInputs.Body<CancelBillInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID billId = PharmacyParams.pathId(rawBillId);
        CancelBillInput in = body.read();
        guard.requireSale(caller, storeId, StoreCapability.CANCEL_BILL);
        if (in.secondSigner() == null || !caller.isPharmacist()) {
            return null;
        }
        BillRow bill = bills.find(tenantId, billId).filter(b -> b.storeId().equals(storeId)).orElse(null);
        return bill != null && hasScheduleX(tenantId, bills.lines(tenantId, billId)) ? in.secondSigner() : null;
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<SaleViews.Bill> cancel(UUID tenantId, UUID userId, String rawStoreId, String rawBillId,
                                                        PharmacyInputs.Body<CancelBillInput> body,
                                                        UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID billId = PharmacyParams.pathId(rawBillId);
        CancelBillInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.CANCEL_BILL);
        UUID me = caller.actorId();

        PharmacyViews.Outcome<SaleViews.Bill> replay = replay(caller, userId, storeId, billId, in.clientKey(), CANCEL);
        if (replay != null) {
            return replay;
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        String note = SaleSupport.blank(in.note());
        if (in.reasonKey() == null || !reasons.isActive(tenantId, ReasonList.CANCEL_BILL, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Enter a note of at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        if (in.backOnShelf() == null) {
            errors.add(new FieldMessage("backOnShelf", "Say whether the medicines are back on the shelf"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), CANCEL, billId, true) != null) {
            return cancel(tenantId, userId, rawStoreId, rawBillId, body, verifiedSignerMembershipId); // L15
        }

        // locks: the bill, then the customer, then the stock rows inside the posting
        BillRow bill = bills.findForUpdate(tenantId, billId).filter(b -> b.storeId().equals(storeId))
                .orElseThrow(BillCancelService::notFound);
        CustomerRepository.Row customer = bill.customerId() == null ? null
                : customers.findForUpdate(tenantId, bill.customerId()).orElseThrow(BillCancelService::notFound);
        if ("CANCELLED".equals(bill.status())) {
            throw PharmacyProblemException.saleConflict("already-cancelled", "Already cancelled", ALREADY_CANCELLED);
        }
        if (bills.hasReturns(tenantId, billId)) {
            throw PharmacyProblemException.saleConflict("has-returns", "Bill has returns", HAS_RETURNS);
        }
        if (version != bill.version()) {
            throw PharmacyProblemException.changed(assembler.view(caller, userId, bill));
        }
        if (!caller.isOwner() && !assembler.sameDay(bill)) {
            throw new ForbiddenException("cancel-not-allowed", "Not allowed", SaleBillAssembler.ONLY_OWNER_EARLIER_DAY);
        }
        List<LineRow> lines = bills.lines(tenantId, billId);
        Map<UUID, ProductRepository.Full> found = productsOf(tenantId, lines);
        UUID signer = null;
        if (found.values().stream().anyMatch(f -> GoodsReceiptService.isScheduleX(f.row()))) {
            // SEC I27 / DEC-092 item 6: the owner never handles Schedule X; only a pharmacist with a second signer cancels it

            if (!caller.isPharmacist()) {
                throw PharmacyProblemException.pharmacistRequired(SaleBillService.PHARMACIST_ONLY_X);
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }

        // the units come back: one reversing line per bill line, pointing at the original ledger line
        LocalDate today = time.today();
        Map<UUID, UUID> original = new HashMap<>();
        for (SaleBillRepository.SaleLedger l : bills.saleLedger(tenantId, billId)) {
            original.putIfAbsent(l.billLineId(), l.ledgerId());
        }
        List<StockPosting.Line> back = new ArrayList<>();
        for (LineRow l : lines) {
            StockRepository.BatchRow batch = stock.findBatch(tenantId, l.batchId()).orElseThrow(BillCancelService::notFound);
            boolean toShelf = Boolean.TRUE.equals(in.backOnShelf()) && !batch.expiryDate().isBefore(today);
            boolean isX = GoodsReceiptService.isScheduleX(found.get(l.productId()).row());
            back.add(new StockPosting.Line(storeId, l.productId(), l.batchId(),
                    toShelf ? StockState.AVAILABLE : StockState.QUARANTINE, l.qty(), "COUNTER_SALE_CANCELLED", in.reasonKey(),
                    UUID.randomUUID(), original.get(l.id()), new StockPosting.Doc(DOC_TYPE, billId, bill.billNo(), l.id()),
                    l.lineNo(), isX ? signer : null));
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), back);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }
        registers.reverseH1ForCancelledBill(tenantId, billId);
        if (signer != null) {
            // the reversal carries the people who cancelled (the pharmacist and the second signer), not the sale's signers
            registers.reverseScheduleXForCancelledBill(tenantId, billId, me, signer);
        }

        // the money: the udhar part off the due, a credit note given back, the rest is refund due
        Map<String, Long> paid = new HashMap<>();
        bills.payments(tenantId, billId).forEach(p -> paid.put(p.mode(), p.amountPaise()));
        long refundDue = paid.getOrDefault("CASH", 0L) + paid.getOrDefault("UPI", 0L) + paid.getOrDefault("CARD", 0L);
        if (customer != null) {
            long due = customer.duePaise();
            long balance = customer.creditNoteBalancePaise();
            long off = Math.min(bill.udharPaise(), due);
            if (off > 0) {
                due -= off;
                customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(),
                        customer.id(), storeId, "CANCELLED_BILL", off, due, balance, billId, null, null, me));
            }
            long creditNote = paid.getOrDefault("CREDIT_NOTE", 0L);
            if (creditNote > 0) {
                balance += creditNote;
                customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(),
                        customer.id(), storeId, "CREDIT_NOTE_ISSUED", creditNote, due, balance, billId, null, null, me));
            }
            if (off > 0 || creditNote > 0) {
                customers.setBalances(tenantId, customer.id(), due, balance, me);
            }
        }
        if (!bills.markCancelled(tenantId, billId, bill.version(), me, in.reasonKey(), note, in.backOnShelf(), refundDue)) {
            throw PharmacyProblemException.changed(null);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "CANCELLED", storeId, me,
                caller.isOwner() ? "OWNER" : null, null, null, null, in.reasonKey());
        if (signer != null) {
            events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "SECOND_SIGNATURE", storeId, me, null, null, null,
                    null, null);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("billId", billId.toString());
        after.put("billNo", bill.billNo());
        after.put("reasonKey", in.reasonKey());
        after.put("refundDuePaise", refundDue);
        auditLog.record("PHARMACY_BILL_CANCELLED", ENTITY_TYPE, billId, Map.of("status", "SAVED"), after);
        log.info("Bill cancelled billId={} refundDuePaise={}", billId, refundDue);
        return new PharmacyViews.Outcome<>(assembler.view(caller, userId, bills.find(tenantId, billId).orElseThrow()), true);
    }

    /**
     * Settles the "Refund due" of a cancelled bill like a sales-return refund (DEC-074 OQ-80-13): the refunds add up to the refund
     * due; the modes are among those the owner allows; a CREDIT_NOTE number is taken last. Replays answer 200.
     */
    @Transactional
    public PharmacyViews.Outcome<SaleViews.Bill> refund(UUID tenantId, UUID userId, String rawStoreId, String rawBillId,
                                                        PharmacyInputs.Body<RefundInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID billId = PharmacyParams.pathId(rawBillId);
        RefundInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        PharmacyViews.Outcome<SaleViews.Bill> replay = replay(caller, userId, storeId, billId, in.clientKey(), REFUND);
        if (replay != null) {
            return replay;
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        Map<String, Long> refunds = refunds(in.refunds(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), REFUND, billId, true) != null) {
            return refund(tenantId, userId, rawStoreId, rawBillId, body); // L15
        }

        BillRow bill = bills.findForUpdate(tenantId, billId).filter(b -> b.storeId().equals(storeId))
                .orElseThrow(BillCancelService::notFound);
        CustomerRepository.Row customer = bill.customerId() == null ? null
                : customers.findForUpdate(tenantId, bill.customerId()).orElseThrow(BillCancelService::notFound);
        if (!"CANCELLED".equals(bill.status())) {
            throw PharmacyProblemException.saleConflict("not-cancelled", "Bill not cancelled", NOT_CANCELLED);
        }
        if (bill.refundCreditNoteNo() != null || bill.refundDuePaise() == 0) {
            throw PharmacyProblemException.saleConflict("refund-already-recorded", "Refund already recorded", REFUND_RECORDED);
        }
        if (version != bill.version()) {
            throw PharmacyProblemException.changed(assembler.view(caller, userId, bill));
        }
        long due = bill.refundDuePaise();
        errors = new ArrayList<>();
        checkRefunds(refunds, due, bills.refundModes(tenantId), paidByMode(tenantId, billId), customer, errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        // the credit note number, last
        LocalDate today = time.today();
        long seq = numbers.next(tenantId, DocType.CREDIT_NOTE);
        String creditNoteNo = StoreUseSupport.render(series, tenantId, DocType.CREDIT_NOTE, seq, today);
        DocumentSeriesRepository.Row unitRow = series.find(tenantId, DocType.CREDIT_NOTE).orElse(null);
        boolean restarts = unitRow == null ? DocType.CREDIT_NOTE.defaultRestarts() : unitRow.restartsEachFinancialYear();
        registers.insertCreditNote(tenantId, new SaleRegisterRepository.NewCreditNote(UUID.randomUUID(), store.unitId(), storeId,
                creditNoteNo, restarts ? DocumentNumbers.financialYearStart(today) : 0, "CANCELLED_BILL", billId, null, due), me);
        for (Map.Entry<String, Long> r : refunds.entrySet()) {
            registers.insertRefund(tenantId, new SaleRegisterRepository.NewRefund(store.unitId(), storeId, "CANCELLED_BILL",
                    billId, null, r.getKey(), r.getValue(), creditNoteNo), me);
        }
        if (customer != null) {
            long owed = customer.duePaise();
            long balance = customer.creditNoteBalancePaise();
            long toCredit = refunds.getOrDefault("CREDIT_NOTE", 0L);
            long toUdhar = Math.min(refunds.getOrDefault("UDHAR", 0L), owed);
            if (toUdhar > 0) {
                owed -= toUdhar;
                customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(), customer.id(),
                        storeId, "RETURN_TO_UDHAR", toUdhar, owed, balance, billId, null, null, me));
            }
            if (toCredit > 0) {
                balance += toCredit;
                customers.insertEntry(tenantId, new CustomerRepository.NewEntry(UUID.randomUUID(), store.unitId(), customer.id(),
                        storeId, "CREDIT_NOTE_ISSUED", toCredit, owed, balance, billId, null, null, me));
            }
            if (toUdhar > 0 || toCredit > 0) {
                customers.setBalances(tenantId, customer.id(), owed, balance, me);
            }
        }
        if (!bills.setRefundCreditNote(tenantId, billId, bill.version(), creditNoteNo, me)) {
            throw PharmacyProblemException.changed(null);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, billId, "REFUND_RECORDED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("billId", billId.toString());
        after.put("creditNoteNo", creditNoteNo);
        after.put("amountPaise", due);
        after.put("modes", String.join(",", refunds.keySet()));
        auditLog.record("PHARMACY_BILL_REFUND_RECORDED", ENTITY_TYPE, billId, null, after);
        log.info("Refund recorded billId={} amountPaise={}", billId, due);
        return new PharmacyViews.Outcome<>(assembler.view(caller, userId, bills.find(tenantId, billId).orElseThrow()), true);
    }

    // ---- helpers ----

    /** A replay of the same key on the same bill answers the bill as it is now (200); another operation or bill is 409. */
    private PharmacyViews.Outcome<SaleViews.Bill> replay(PharmacyCaller caller, UUID userId, UUID storeId, UUID billId,
                                                         UUID clientKey, String operation) {
        if (clientKey == null) {
            return null;
        }
        UUID tenantId = caller.tenantId();
        ClientRequestRepository.Taken own = clients.own(tenantId, caller.actorId(), clientKey).orElse(null);
        if (own != null) {
            if (!operation.equals(own.operation()) || !billId.equals(own.subjectId())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            BillRow bill = bills.find(tenantId, billId).filter(b -> b.storeId().equals(storeId))
                    .orElseThrow(BillCancelService::notFound);
            return new PharmacyViews.Outcome<>(assembler.view(caller, userId, bill), false);
        }
        if (clients.heldByAnyone(tenantId, clientKey)) {
            throw PharmacyProblemException.clientKeyReused();
        }
        return null;
    }

    private boolean hasScheduleX(UUID tenantId, List<LineRow> lines) {
        return productsOf(tenantId, lines).values().stream().anyMatch(f -> GoodsReceiptService.isScheduleX(f.row()));
    }

    private Map<UUID, ProductRepository.Full> productsOf(UUID tenantId, List<LineRow> lines) {
        Set<UUID> ids = new HashSet<>();
        lines.forEach(l -> ids.add(l.productId()));
        return products.findAll(tenantId, ids);
    }

    private Map<String, Long> paidByMode(UUID tenantId, UUID billId) {
        Map<String, Long> paid = new HashMap<>();
        bills.payments(tenantId, billId).forEach(p -> paid.put(p.mode(), p.amountPaise()));
        return paid;
    }

    /** One to four refunds, each mode once, whole paise of at least 1. */
    private static Map<String, Long> refunds(List<RefundLine> in, List<FieldMessage> errors) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (in == null || in.isEmpty() || in.size() > 4) {
            errors.add(new FieldMessage("refunds", "Choose how the money goes back."));
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            RefundLine r = in.get(i);
            Long amount = r == null ? null : SaleSupport.wholePaise(r.amountPaise(), CustomerService.MAX_LIMIT_PAISE);
            if (r == null || r.mode() == null || !REFUND_MODES.contains(r.mode())) {
                errors.add(new FieldMessage("refunds[" + i + "].mode", "Choose a refund mode"));
            } else if (amount == null || amount < 1) {
                errors.add(new FieldMessage("refunds[" + i + "].amountPaise", "Enter an amount of at least ₹ 0.01"));
            } else if (out.putIfAbsent(r.mode(), amount) != null) {
                errors.add(new FieldMessage("refunds[" + i + "].mode", "Use each refund mode once"));
            }
        }
        return out;
    }

    /**
     * The refunds add up to the refund due; each mode is allowed by the unit (ORIGINAL_MODE allows the bill's own cash, UPI and
     * card, up to what that mode paid); a credit note or udhar refund needs a customer.
     */
    static void checkRefunds(Map<String, Long> refunds, long due, List<String> allowed, Map<String, Long> paid,
                             CustomerRepository.Row customer, List<FieldMessage> errors) {
        long total = refunds.values().stream().mapToLong(Long::longValue).sum();
        if (total != due) {
            errors.add(new FieldMessage("refunds", "Refunds must add up to " + SaleRules.rupeesText(due) + "."));
        }
        boolean original = allowed.contains("ORIGINAL_MODE");
        for (Map.Entry<String, Long> r : refunds.entrySet()) {
            String mode = r.getKey();
            long amount = r.getValue();
            boolean ok;
            switch (mode) {
                case "CREDIT_NOTE":
                    ok = allowed.contains("CREDIT_NOTE");
                    break;
                case "UDHAR":
                    ok = allowed.contains("UDHAR");
                    break;
                case "CASH":
                    ok = allowed.contains("CASH") || original && paid.getOrDefault("CASH", 0L) >= amount;
                    break;
                default:
                    ok = original && paid.getOrDefault(mode, 0L) >= amount;
                    break;
            }
            if (!ok) {
                errors.add(new FieldMessage("refunds", "This refund mode is not allowed. Ask your owner."));
            } else if (("CREDIT_NOTE".equals(mode) || "UDHAR".equals(mode)) && customer == null) {
                errors.add(new FieldMessage("refunds", "Choose a customer to refund to a credit note or udhar."));
            } else if ("UDHAR".equals(mode) && amount > customer.duePaise()) {
                // M3: money recorded as returned must reach the account, so more than the customer owes is refused, never capped
                errors.add(new FieldMessage("refunds", "The udhar refund is more than the customer owes."));
            }
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }
}
