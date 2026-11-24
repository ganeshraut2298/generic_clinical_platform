package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.SaleBillRepository.BillRow;
import com.sahaj.pharmacy.application.SaleBillRepository.LineRow;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.RoleLabels;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code Bill} of docs/api/pharmacy-sales.yaml from the stored rows (SAH-80-3, 80-5): lines with batch and product,
 * payments, the summary worked from the stored lines, the marks, who did what, the history and the invoice print data. A registered
 * patient's name is read through {@code patient.api} and one record-access entry is written (rule 29); the name is never logged.
 * {@code canCancel} is worked out for the caller: the owner any day, a pharmacist only on the IST day of the bill.
 */
@Component
public class SaleBillAssembler {

    static final String ONLY_OWNER_EARLIER_DAY = "Only the owner can cancel a bill from an earlier day.";
    static final String DOC_TYPE = "BILL";

    private final SaleBillRepository bills;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StoreRepository stores;
    private final CustomerRepository customers;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final SaleSupport support;
    private final PharmacyTime time;

    public SaleBillAssembler(SaleBillRepository bills, ProductRepository products, StockRepository stock,
                             StoreRepository stores, CustomerRepository customers, DocumentEventRepository events,
                             ActiveReasonService reasons, SaleSupport support, PharmacyTime time) {
        this.bills = bills;
        this.products = products;
        this.stock = stock;
        this.stores = stores;
        this.customers = customers;
        this.events = events;
        this.reasons = reasons;
        this.support = support;
        this.time = time;
    }

    /** The bill as the caller sees it; {@code userId} is the signed-in user, for the record-access entry of a patient. */
    public SaleViews.Bill view(PharmacyCaller caller, UUID userId, BillRow bill) {
        UUID tenantId = caller.tenantId();
        List<LineRow> lines = bills.lines(tenantId, bill.id());
        List<SaleBillRepository.PaymentRow> payments = bills.payments(tenantId, bill.id());

        Set<UUID> productIds = new LinkedHashSet<>();
        Set<UUID> batchIds = new LinkedHashSet<>();
        Set<UUID> people = new HashSet<>();
        people.add(bill.savedBy());
        if (bill.cancelledBy() != null) {
            people.add(bill.cancelledBy());
        }
        if (bill.pharmacistMembershipId() != null) {
            people.add(bill.pharmacistMembershipId());
        }
        for (LineRow l : lines) {
            productIds.add(l.productId());
            if (l.prescribedProductId() != null) {
                productIds.add(l.prescribedProductId());
            }
            batchIds.add(l.batchId());
            if (l.overrideDecidedBy() != null) {
                people.add(l.overrideDecidedBy());
            }
            if (l.secondSigner() != null) {
                people.add(l.secondSigner());
            }
        }
        List<DocumentEventRepository.Event> history = events.forDocument(tenantId, DOC_TYPE, bill.id());
        history.forEach(e -> {
            if (e.actorMembershipId() != null) {
                people.add(e.actorMembershipId());
            }
        });
        Map<UUID, TeamMember> members = support.members(tenantId, people);
        Map<UUID, ProductRepository.Full> productRows = products.findAll(tenantId, productIds);
        Map<UUID, StockRepository.BatchRow> batchRows = new HashMap<>();
        for (UUID batchId : batchIds) {
            stock.findBatch(tenantId, batchId).ifPresent(b -> batchRows.put(batchId, b));
        }
        Map<String, String> labels = reasons.labels(tenantId);

        List<SaleViews.BillLine> lineViews = new ArrayList<>();
        for (LineRow l : lines) {
            ProductRepository.Row p = productRows.get(l.productId()).row();
            StockRepository.BatchRow b = batchRows.get(l.batchId());
            ProductRepository.Full prescribed = l.prescribedProductId() == null ? null : productRows.get(l.prescribedProductId());
            lineViews.add(new SaleViews.BillLine(l.id(), l.lineNo(), IndentService.productRef(p),
                    l.hsn() == null ? p.hsn() : l.hsn(), new StockViews.BatchRef(l.batchId(), b == null ? "" : b.batchNo(),
                    b == null ? null : b.expiryDate()), l.qty(), SaleRules.rate(l.mrp()), SaleRules.percent(l.gstRate()),
                    l.grossPaise(), l.discountPaise(), l.netPaise(), l.taxablePaise(), l.taxPaise(), l.returnedQty(),
                    p.schedule() == null ? "NONE" : p.schedule(),
                    prescribed == null ? null : IndentService.productRef(prescribed.row()),
                    reason(labels, l.substitutionReasonKey(), l.substitutionNote()), l.patientAgreed(),
                    reason(labels, l.allergyOverrideKey(), l.allergyOverrideNote()),
                    reason(labels, l.h1OverrideKey(), l.h1OverrideNote()),
                    l.overrideDecidedBy() == null ? null : support.person(l.overrideDecidedBy(), members),
                    l.secondSigner() == null ? null : support.person(l.secondSigner(), members), l.rxLineKey()));
        }

        Store store = stores.find(tenantId, bill.storeId()).orElseThrow(() -> new NotFoundException(SaleSupport.NOT_FOUND));
        boolean hasReturns = bills.hasReturns(tenantId, bill.id());
        TeamMember saver = members.get(bill.savedBy());
        SaleViews.SavedBy savedBy = new SaleViews.SavedBy(bill.savedBy(), saver == null ? OpeningStockAssembler.UNKNOWN_MEMBER
                : saver.name(), saver == null ? null : RoleLabels.of(saver.role()));
        SaleViews.PharmacistLine pharmacist = null;
        if (bill.pharmacistMembershipId() != null) {
            TeamMember member = members.get(bill.pharmacistMembershipId());
            pharmacist = new SaleViews.PharmacistLine(member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name(),
                    bills.pharmacistRegistration(tenantId, bill.pharmacistMembershipId()).orElse(null));
        }
        SaleViews.PrescriptionView prescription = bill.rxSource() == null ? null
                : new SaleViews.PrescriptionView(bill.rxSource(), bill.prescriberName(), bill.prescriberRegNo(),
                        bill.prescriptionDate());
        SaleViews.CancelInfo cancel = null;
        if ("CANCELLED".equals(bill.status())) {
            cancel = new SaleViews.CancelInfo(bill.cancelledAt(), support.person(bill.cancelledBy(), members),
                    reason(labels, bill.cancelReasonKey(), bill.cancelNote()), Boolean.TRUE.equals(bill.backOnShelf()));
        }
        String lock = cancelLockReason(caller, bill, hasReturns);
        List<SaleViews.Payment> paymentViews = new ArrayList<>();
        payments.forEach(p -> paymentViews.add(new SaleViews.Payment(p.mode(), p.amountPaise())));
        List<StockViews.HistoryEvent> historyViews = new ArrayList<>();
        for (DocumentEventRepository.Event e : history) {
            historyViews.add(new StockViews.HistoryEvent(e.event(), e.occurredAt(),
                    e.actorMembershipId() == null ? null : support.person(e.actorMembershipId(), members), e.actingAs(),
                    OpeningStockPostService.storeRef(store), null, e.qtyBefore(), e.qtyAfter(),
                    e.reasonKey() == null ? null : new StockViews.ReasonRef(e.reasonKey(),
                            labels.getOrDefault(e.reasonKey(), e.reasonKey()), null)));
        }
        SaleViews.Buyer buyer = buyer(caller, userId, bill);
        PurchaseViews.PrintHeader header = support.invoiceHeader(tenantId);
        SaleViews.BillPrint print = new SaleViews.BillPrint(header, support.unitName(tenantId), header.drugLicenceNo(),
                header.unitGstin(), header.footerNote());
        return new SaleViews.Bill(bill.id(), bill.billNo(), OpeningStockPostService.storeRef(store), bill.status(),
                bill.savedAt(), savedBy, buyer, bill.rxRecordId(), lineViews, summary(bill, lines), paymentViews,
                bill.udharPaise(), bill.linkPending(), bill.allergyNotChecked(), null, pharmacist, prescription, cancel,
                bill.refundCreditNoteNo() == null ? bill.refundDuePaise() : 0L, bill.refundCreditNoteNo(), hasReturns, lock == null, lock, print, historyViews,
                bill.version());
    }

    /** NOT_SAME_DAY, NOT_ALLOWED, HAS_RETURNS or ALREADY_CANCELLED; null when the caller may cancel. */
    String cancelLockReason(PharmacyCaller caller, BillRow bill, boolean hasReturns) {
        if ("CANCELLED".equals(bill.status())) {
            return "ALREADY_CANCELLED";
        }
        if (hasReturns) {
            return "HAS_RETURNS";
        }
        if (!StoreAccessGuard.holds(caller, bill.storeId(), StoreCapability.CANCEL_BILL)) {
            return "NOT_ALLOWED";
        }
        if (!caller.isOwner() && !sameDay(bill)) {
            return "NOT_SAME_DAY";
        }
        return null;
    }

    /** The bill's IST day is today's. */
    boolean sameDay(BillRow bill) {
        LocalDate day = bill.savedAt().atZone(PharmacyTime.IST).toLocalDate();
        return day.equals(time.today());
    }

    /** The summary worked from the stored lines: per GST rate the taxable value and tax, CGST half rounded half up, SGST the rest. */
    static SaleViews.BillSummary summary(BillRow bill, List<LineRow> lines) {
        Map<BigDecimal, long[]> perRate = new TreeMap<>();
        Set<UUID> items = new HashSet<>();
        int units = 0;
        for (LineRow l : lines) {
            long[] sums = perRate.computeIfAbsent(l.gstRate().stripTrailingZeros(), r -> new long[2]);
            sums[0] += l.taxablePaise();
            sums[1] += l.taxPaise();
            items.add(l.productId());
            units += l.qty();
        }
        List<SaleViews.TaxByRate> byRate = new ArrayList<>();
        long cgstTotal = 0;
        long sgstTotal = 0;
        for (Map.Entry<BigDecimal, long[]> e : perRate.entrySet()) {
            long tax = e.getValue()[1];
            long cgst = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP).longValueExact();
            byRate.add(new SaleViews.TaxByRate(SaleRules.percent(e.getKey()), e.getValue()[0], cgst, tax - cgst,
                    e.getValue()[0] + tax));
            cgstTotal += cgst;
            sgstTotal += tax - cgst;
        }
        boolean discounted = bill.discountPercent() != null && bill.discountPercent().signum() > 0;
        return new SaleViews.BillSummary(items.size(), units, bill.grossPaise(),
                discounted ? SaleRules.percent(bill.discountPercent()) : null, bill.discountPaise(), byRate, cgstTotal,
                sgstTotal, bill.roundOffPaise(), bill.netPaise());
    }

    private SaleViews.Buyer buyer(PharmacyCaller caller, UUID userId, BillRow bill) {
        UUID tenantId = caller.tenantId();
        UUID patientId = bill.patientId();
        CustomerRepository.Row customer = bill.customerId() == null ? null
                : customers.find(tenantId, bill.customerId()).orElse(null);
        if (patientId == null && customer != null && customer.patientId() != null) {
            patientId = customer.patientId();
        }
        if (patientId != null) {
            Map<UUID, SaleSupport.PatientCard> cards;
            try {
                cards = support.patientCards(tenantId, List.of(patientId), true);
            } catch (PharmacyProblemException unreachable) {
                return new SaleViews.Buyer(bill.buyerKind(), bill.patientId(), bill.customerId(), "Patient", null, null);
            }
            SaleSupport.PatientCard card = cards.get(patientId);
            if (card != null) {
                support.recordOpened(tenantId, patientId, userId);
                return new SaleViews.Buyer(bill.buyerKind(), bill.patientId(), bill.customerId(), card.name(), card.uhid(),
                        card.mobile() == null ? null : SaleRules.maskMobile(card.mobile()));
            }
            return new SaleViews.Buyer(bill.buyerKind(), bill.patientId(), bill.customerId(), "Patient", null, null);
        }
        if (customer != null) {
            return new SaleViews.Buyer(bill.buyerKind(), null, customer.id(), customer.name(), null,
                    customer.phone() == null ? null : SaleRules.maskMobile(customer.phone()));
        }
        if ("WALK_IN".equals(bill.buyerKind())) {
            return new SaleViews.Buyer("WALK_IN", null, null, bill.walkInName(), null,
                    bill.walkInPhone() == null ? null : SaleRules.maskMobile(bill.walkInPhone()));
        }
        return new SaleViews.Buyer(bill.buyerKind(), null, null, "Walk-in", null, null);
    }

    private static StockViews.ReasonRef reason(Map<String, String> labels, String key, String note) {
        return key == null ? null : new StockViews.ReasonRef(key, labels.getOrDefault(key, key), note);
    }

}
