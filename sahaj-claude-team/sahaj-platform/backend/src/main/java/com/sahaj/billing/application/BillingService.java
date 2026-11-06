package com.sahaj.billing.application;

import com.sahaj.billing.application.BillItemRepository.BillItemRow;
import com.sahaj.billing.application.BillRepository.BillRow;
import com.sahaj.billing.application.PaymentRepository.PaymentRow;
import com.sahaj.opd.api.DiscountLimitPort;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.api.ServiceLookupPort.ServiceSummary;
import com.sahaj.opd.api.VisitBillingPort;
import com.sahaj.opd.api.VisitBillingPort.VisitForBilling;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-10 Billing counter: the compose-time bill (items, discount, payment, refund) and day
 * closing. {@code billing} never joins {@code opd.visit}/{@code opd.service} or
 * {@code patient.patient} directly: a visit's billing detail, the price list and the discount
 * limit come through {@link VisitBillingPort}, {@link ServiceLookupPort} and
 * {@link DiscountLimitPort} ({@code opd.api}); the patient's name/age/sex come through
 * {@link PatientLookupPort} ({@code patient.api}).
 *
 * <p>A bill row is created lazily on the first write (an item, a discount or a payment); until
 * then a read is synthesized from the visit's own consultation fee with zero items, discount and
 * payments (SAH-10 technical design).
 */
@Service
public class BillingService {

    static final Set<String> COLLECT_ROLES = Set.of("OWNER", "FRONT_DESK");
    static final Set<String> MODES = Set.of("CASH", "UPI", "CARD");
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String STAGE_BILLING = "BILLING";
    private static final String STAGE_DONE = "DONE";
    private static final String ENTITY_BILL = "BILL";
    private static final String ENTITY_PAYMENT = "PAYMENT";
    private static final String ENTITY_DAY_CLOSING = "DAY_CLOSING";

    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final PaymentRepository paymentRepository;
    private final DayClosingRepository dayClosingRepository;
    private final VisitBillingPort visitBillingPort;
    private final ServiceLookupPort serviceLookupPort;
    private final DiscountLimitPort discountLimitPort;
    private final PatientLookupPort patientLookupPort;
    private final ActorDirectoryPort actorDirectoryPort;
    private final AuditLog auditLog;
    private final Clock clock;

    public BillingService(BillRepository billRepository, BillItemRepository billItemRepository,
                           PaymentRepository paymentRepository, DayClosingRepository dayClosingRepository,
                           VisitBillingPort visitBillingPort, ServiceLookupPort serviceLookupPort,
                           DiscountLimitPort discountLimitPort, PatientLookupPort patientLookupPort,
                           ActorDirectoryPort actorDirectoryPort, AuditLog auditLog, Clock clock) {
        this.billRepository = billRepository;
        this.billItemRepository = billItemRepository;
        this.paymentRepository = paymentRepository;
        this.dayClosingRepository = dayClosingRepository;
        this.visitBillingPort = visitBillingPort;
        this.serviceLookupPort = serviceLookupPort;
        this.discountLimitPort = discountLimitPort;
        this.patientLookupPort = patientLookupPort;
        this.actorDirectoryPort = actorDirectoryPort;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** AC1, AC5, AC6: today's board - awaiting payment, dues and paid today. */
    @Transactional(readOnly = true)
    public CounterView counter(UUID tenantId) {
        List<VisitForBilling> visits = visitBillingPort.today(tenantId);
        Instant startOfToday = startOfToday();
        List<BillSummaryView> awaiting = new ArrayList<>();
        List<BillSummaryView> dues = new ArrayList<>();
        List<BillSummaryView> paidToday = new ArrayList<>();
        for (VisitForBilling visit : visits) {
            if (STAGE_BILLING.equals(visit.stage())) {
                awaiting.add(summaryFor(tenantId, visit));
            } else if (STAGE_DONE.equals(visit.stage())) {
                Totals totals = totalsFor(tenantId, visit.id(), visit.feePaise());
                if (totals.balancePaise() > 0) {
                    dues.add(summaryFrom(tenantId, visit, totals));
                } else if (totals.billId() != null && paymentRepository.existsSince(tenantId, totals.billId(), startOfToday)) {
                    paidToday.add(summaryFrom(tenantId, visit, totals));
                }
            }
        }
        return new CounterView(awaiting, dues, paidToday);
    }

    /**
     * SAH-15: collected = net of today's payments (payments minus refunds, Asia/Kolkata day); pending = visits in
     * the Billing stage with a balance above zero (the counter's "Awaiting payment").
     */
    @Transactional(readOnly = true)
    public BillingTodaySummary todaySummary(UUID tenantId) {
        long collected = paymentRepository.netSince(tenantId, startOfToday());
        int pending = (int) counter(tenantId).awaiting().stream().filter(bill -> bill.balancePaise() > 0).count();
        return new BillingTodaySummary(collected, pending);
    }

    /** Any clinic role reads a bill (AC8); writers also see their own write rights on it. */
    @Transactional(readOnly = true)
    public BillView get(UUID tenantId, String actorRole, UUID visitId) {
        return toBillView(tenantId, actorRole, findVisit(tenantId, visitId));
    }

    /** AC2: add an extra service line, snapshotted so a later price change does not rewrite history. */
    @Transactional
    public BillView addItem(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId, ItemAddInput input) {
        requireCollectRole(actorRole);
        findVisit(tenantId, visitId);
        if (input.serviceId() == null) {
            throw new InvalidInputException("serviceId", "Choose a service");
        }
        ServiceSummary service = serviceLookupPort.find(tenantId, input.serviceId())
                .filter(ServiceSummary::active)
                .orElseThrow(() -> new InvalidInputException("serviceId", "Choose a service from the list"));
        int qty = input.qty() == null ? 1 : input.qty();
        if (qty < 1 || qty > 20) {
            throw new InvalidInputException("qty", "Enter a quantity from 1 to 20");
        }
        UUID billId = billRepository.getOrCreate(tenantId, visitId, actorUserId);
        UUID itemId = UUID.randomUUID();
        billItemRepository.insert(itemId, billId, service.id(), service.name(), service.pricePaise(), qty,
                actorUserId);
        auditLog.record("BILL_ITEM_ADDED", ENTITY_BILL, billId, null,
                Map.of("visitId", visitId, "serviceId", service.id(), "qty", qty, "amountPaise", service.pricePaise()));
        return toBillView(tenantId, actorRole, findVisit(tenantId, visitId));
    }

    /** AC2: remove any item except the consultation line, which is never stored here in the first place. */
    @Transactional
    public BillView removeItem(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId, UUID itemId) {
        requireCollectRole(actorRole);
        findVisit(tenantId, visitId);
        BillRow bill = billRepository.findByVisit(tenantId, visitId)
                .orElseThrow(() -> new NotFoundException("Item not found"));
        BillItemRow item = billItemRepository.findById(tenantId, bill.id(), itemId)
                .orElseThrow(() -> new NotFoundException("Item not found"));
        billItemRepository.delete(tenantId, item.id());
        auditLog.record("BILL_ITEM_REMOVED", ENTITY_BILL, bill.id(),
                Map.of("itemId", item.id(), "amountPaise", item.amountPaise()), null);
        return toBillView(tenantId, actorRole, findVisit(tenantId, visitId));
    }

    /** AC3: percent cannot exceed the signed-in role's discount limit; the owner has no limit. */
    @Transactional
    public BillView setDiscount(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId,
                                 DiscountInput input) {
        requireCollectRole(actorRole);
        findVisit(tenantId, visitId);
        if (input.percent() == null || input.percent() < 0 || input.percent() > 100) {
            throw new InvalidInputException("percent", "Enter a discount from 0 to 100 percent");
        }
        int limit = discountLimitFor(tenantId, actorRole);
        if (input.percent() > limit) {
            throw new InvalidInputException("percent", "This discount is above your role's limit");
        }
        UUID billId = billRepository.getOrCreate(tenantId, visitId, actorUserId);
        billRepository.updateDiscount(tenantId, billId, input.percent(), input.reason(), actorUserId);
        auditLog.record("BILL_DISCOUNT_SET", ENTITY_BILL, billId, null,
                Map.of("visitId", visitId, "percent", input.percent()));
        return toBillView(tenantId, actorRole, findVisit(tenantId, visitId));
    }

    /** AC4: a payment reduces the balance; reaching ₹0 moves the visit to DONE in the same transaction. */
    @Transactional
    public BillView recordPayment(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId,
                                   PaymentInput input) {
        requireCollectRole(actorRole);
        VisitForBilling visit = findVisit(tenantId, visitId);
        requireValidMode(input.mode());
        if (input.amountPaise() == null || input.amountPaise() < 1) {
            throw new InvalidInputException("amountPaise", "Enter an amount above zero");
        }
        UUID billId = billRepository.getOrCreate(tenantId, visitId, actorUserId);
        Totals before = totalsFor(tenantId, visitId, visit.feePaise());
        if (input.amountPaise() > before.balancePaise()) {
            throw new InvalidInputException("amountPaise", "The amount cannot be more than the balance due");
        }
        UUID paymentId = UUID.randomUUID();
        paymentRepository.insert(paymentId, billId, input.mode(), input.amountPaise(), input.reference(),
                actorUserId);
        auditLog.record("PAYMENT_RECORDED", ENTITY_PAYMENT, paymentId, null,
                Map.of("visitId", visitId, "mode", input.mode(), "amountPaise", input.amountPaise()));

        Totals after = totalsFor(tenantId, visitId, visit.feePaise());
        if (after.balancePaise() == 0) {
            visitBillingPort.advanceToDone(tenantId, actorUserId, actorRole, visitId);
        }
        return toBillView(tenantId, actorRole, findVisit(tenantId, visitId));
    }

    /** AC7: owner only; recorded as a negative payment with a reason, never more than what was collected. */
    @Transactional
    public BillView refund(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId, RefundInput input) {
        if (!"OWNER".equals(actorRole)) {
            throw new AccessDeniedException("Only the owner can issue a refund.");
        }
        findVisit(tenantId, visitId);
        requireValidMode(input.mode());
        if (input.amountPaise() == null || input.amountPaise() < 1) {
            throw new InvalidInputException("amountPaise", "Enter an amount above zero");
        }
        if (input.reason() == null || input.reason().isBlank()) {
            throw new InvalidInputException("reason", "Enter a reason for the refund");
        }
        BillRow bill = billRepository.findByVisit(tenantId, visitId)
                .orElseThrow(() -> new InvalidInputException("amountPaise", "There is nothing to refund"));
        long paidSoFar = paymentRepository.findByBill(tenantId, bill.id()).stream()
                .mapToLong(PaymentRow::amountPaise).sum();
        if (input.amountPaise() > paidSoFar) {
            throw new InvalidInputException("amountPaise", "The refund cannot be more than what was collected");
        }
        UUID paymentId = UUID.randomUUID();
        paymentRepository.insert(paymentId, bill.id(), input.mode(), -input.amountPaise(), input.reason(),
                actorUserId);
        auditLog.record("PAYMENT_REFUNDED", ENTITY_PAYMENT, paymentId, null,
                Map.of("visitId", visitId, "mode", input.mode(), "amountPaise", -input.amountPaise()));
        return toBillView(tenantId, actorRole, findVisit(tenantId, visitId));
    }

    /** AC9: one closing per clinic per day; expected cash is today's cash payments net of cash refunds. */
    @Transactional
    public DayClosingView closeDay(UUID tenantId, UUID actorUserId, String actorRole, DayClosingInput input) {
        requireCollectRole(actorRole);
        if (input.countedCashPaise() == null || input.countedCashPaise() < 0) {
            throw new InvalidInputException("countedCashPaise", "Enter the cash counted");
        }
        LocalDate businessDate = LocalDate.now(clock.withZone(INDIA));
        if (dayClosingRepository.findByDate(tenantId, businessDate).isPresent()) {
            throw new ConflictException("day-already-closed", "The day is already closed",
                    "Today's cash has already been closed.");
        }
        Instant startOfToday = businessDate.atStartOfDay(INDIA).toInstant();
        long expectedCashPaise = paymentRepository.sumByModeSince(tenantId, "CASH", startOfToday);
        long diffPaise = input.countedCashPaise() - expectedCashPaise;
        UUID id = UUID.randomUUID();
        dayClosingRepository.insert(id, businessDate, input.countedCashPaise(), expectedCashPaise, diffPaise,
                input.note(), actorUserId);
        auditLog.record("DAY_CLOSING_RECORDED", ENTITY_DAY_CLOSING, id, null,
                Map.of("businessDate", businessDate, "countedCashPaise", input.countedCashPaise(),
                        "expectedCashPaise", expectedCashPaise, "diffPaise", diffPaise));
        String closedByName = actorDirectoryPort.displayName(tenantId, actorUserId).orElse("Someone");
        return new DayClosingView(businessDate, input.countedCashPaise(), expectedCashPaise, diffPaise,
                input.note(), closedByName, clock.instant());
    }

    // --- role and input checks ---

    private void requireCollectRole(String actorRole) {
        if (!COLLECT_ROLES.contains(actorRole)) {
            throw new AccessDeniedException(
                    "Only the front desk or the owner can take payments. You can view this bill.");
        }
    }

    private static void requireValidMode(String mode) {
        if (mode == null || !MODES.contains(mode)) {
            throw new InvalidInputException("mode", "Choose Cash, UPI or Card");
        }
    }

    private VisitForBilling findVisit(UUID tenantId, UUID visitId) {
        return visitBillingPort.find(tenantId, visitId).orElseThrow(() -> new NotFoundException("Visit not found"));
    }

    /** AC3: the owner has no limit; anyone else is capped at their role's saved limit, 0 if unset. */
    private int discountLimitFor(UUID tenantId, String actorRole) {
        if ("OWNER".equals(actorRole)) {
            return 100;
        }
        return discountLimitPort.discountLimits(tenantId).getOrDefault(actorRole, 0);
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(INDIA)).atStartOfDay(INDIA).toInstant();
    }

    // --- totals math (server-side only, per the technical design) ---

    private record Totals(UUID billId, long subtotalPaise, int discountPercent, String discountReason,
                           long discountPaise, long totalPaise, long paidPaise, long balancePaise,
                           List<BillItemRow> items, List<PaymentRow> payments) {
    }

    private Totals totalsFor(UUID tenantId, UUID visitId, long feePaise) {
        BillRow bill = billRepository.findByVisit(tenantId, visitId).orElse(null);
        List<BillItemRow> items = bill == null ? List.of() : billItemRepository.findByBill(tenantId, bill.id());
        List<PaymentRow> payments = bill == null ? List.of() : paymentRepository.findByBill(tenantId, bill.id());
        long itemsTotal = items.stream().mapToLong(item -> item.amountPaise() * item.qty()).sum();
        int discountPercent = bill == null ? 0 : bill.discountPercent();
        String discountReason = bill == null ? null : bill.discountReason();
        long paid = payments.stream().mapToLong(PaymentRow::amountPaise).sum();
        BillMath math = BillMath.of(feePaise, itemsTotal, discountPercent, paid);
        return new Totals(bill == null ? null : bill.id(), math.subtotalPaise(), discountPercent, discountReason,
                math.discountPaise(), math.totalPaise(), paid, math.balancePaise(), items, payments);
    }

    private BillView toBillView(UUID tenantId, String actorRole, VisitForBilling visit) {
        PatientSummary patient = patientLookupPort.find(tenantId, visit.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        Totals totals = totalsFor(tenantId, visit.id(), visit.feePaise());

        List<BillItemView> itemViews = new ArrayList<>();
        itemViews.add(new BillItemView(visit.id(), "Consultation", visit.feePaise(), 1, false));
        for (BillItemRow item : totals.items()) {
            itemViews.add(new BillItemView(item.id(), item.name(), item.amountPaise(), item.qty(), true));
        }
        List<PaymentView> paymentViews = totals.payments().stream()
                .map(payment -> new PaymentView(payment.id(), payment.mode(), payment.amountPaise(),
                        payment.reference(), actorDirectoryPort.displayName(tenantId, payment.recordedBy())
                                .orElse("Someone"), payment.recordedAt()))
                .toList();

        return new BillView(visit.id(), visit.tokenNo(), patient.name(), patient.age(), patient.sex(),
                visit.doctorName(), visit.visitType(), itemViews, totals.subtotalPaise(), totals.discountPercent(),
                totals.discountReason(), totals.discountPaise(), totals.totalPaise(), totals.paidPaise(),
                totals.balancePaise(), paymentViews, COLLECT_ROLES.contains(actorRole),
                discountLimitFor(tenantId, actorRole));
    }

    private BillSummaryView summaryFor(UUID tenantId, VisitForBilling visit) {
        return summaryFrom(tenantId, visit, totalsFor(tenantId, visit.id(), visit.feePaise()));
    }

    private BillSummaryView summaryFrom(UUID tenantId, VisitForBilling visit, Totals totals) {
        String patientName = patientLookupPort.find(tenantId, visit.patientId())
                .map(PatientSummary::name).orElse("Unknown");
        return new BillSummaryView(visit.id(), visit.tokenNo(), patientName, visit.doctorName(), totals.totalPaise(),
                totals.balancePaise());
    }
}
