package com.sahaj.billing.application;

import com.sahaj.billing.application.BillItemRepository.BillItemOfBill;
import com.sahaj.billing.application.BillRepository.BillRow;
import com.sahaj.billing.application.PaymentRepository.BillPaid;
import com.sahaj.billing.application.PaymentRepository.PaymentOfVisit;
import com.sahaj.billing.application.PaymentRepository.PaymentTotals;
import com.sahaj.opd.api.VisitBillingPort;
import com.sahaj.opd.api.VisitBillingPort.VisitForRevenue;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-16 Revenue report: a read-only view over bills and payments for an Asia/Kolkata calendar range. It writes
 * nothing, audits nothing and logs nothing; patient names and mobiles are only placed in the returned view. Bill
 * totals come from {@link BillMath}, the same arithmetic the counter uses (SAH-10).
 *
 * <p>Definitions: <em>collected</em> is the net of payments and refunds recorded in the range; <em>billed</em> is
 * the total after discount of bills in the Done stage whose visit was created in the range; <em>still due</em> is
 * the unpaid balance on those Done bills; <em>at the counter</em> is the balance on visits in the range that are
 * still in the Billing stage.
 */
@Service
public class RevenueService {

    static final String CONSULTATION = "Consultation";
    private static final String STAGE_BILLING = "BILLING";
    private static final String STAGE_DONE = "DONE";
    private static final List<String> MODE_ORDER = List.of("CASH", "UPI", "CARD");
    private static final int TOP_SERVICES = 6;
    /** Ids per {@code in (:ids)} statement, far below PostgreSQL's 32767 bind parameter limit. */
    static final int CHUNK = 1000;

    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final PaymentRepository paymentRepository;
    private final VisitBillingPort visitBillingPort;
    private final PatientLookupPort patientLookupPort;
    private final ActorDirectoryPort actorDirectoryPort;
    private final OwnerGuard ownerGuard;
    private final Clock clock;

    public RevenueService(BillRepository billRepository, BillItemRepository billItemRepository,
                          PaymentRepository paymentRepository, VisitBillingPort visitBillingPort,
                          PatientLookupPort patientLookupPort, ActorDirectoryPort actorDirectoryPort, OwnerGuard ownerGuard,
                          Clock clock) {
        this.billRepository = billRepository;
        this.billItemRepository = billItemRepository;
        this.paymentRepository = paymentRepository;
        this.visitBillingPort = visitBillingPort;
        this.patientLookupPort = patientLookupPort;
        this.actorDirectoryPort = actorDirectoryPort;
        this.ownerGuard = ownerGuard;
        this.clock = clock;
    }

    /** {@code range} is TODAY, WEEK (7 days to today) or MONTH (30 days to today); anything else is a 400. */
    @Transactional(readOnly = true)
    public RevenueView report(UUID tenantId, UUID actorUserId, String range) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        int length = lengthOf(range);
        LocalDate today = LocalDate.now(clock.withZone(BillingService.INDIA));
        LocalDate firstDay = today.minusDays(length - 1L);
        Instant from = firstDay.atStartOfDay(BillingService.INDIA).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(BillingService.INDIA).toInstant();
        Instant previousFrom = firstDay.minusDays(length).atStartOfDay(BillingService.INDIA).toInstant();

        List<PaymentOfVisit> payments = paymentRepository.findBetween(tenantId, from, to);
        List<VisitForRevenue> visits = visitBillingPort.between(tenantId, from, to);
        Map<UUID, VisitForRevenue> visitsById = new HashMap<>();
        visits.forEach(visit -> visitsById.put(visit.id(), visit));
        Set<UUID> missing = new HashSet<>();
        payments.forEach(payment -> {
            if (!visitsById.containsKey(payment.visitId())) {
                missing.add(payment.visitId());
            }
        });
        if (!missing.isEmpty()) {
            chunks(missing).forEach(chunk -> visitBillingPort.findAll(tenantId, chunk)
                    .forEach(visit -> visitsById.put(visit.id(), visit)));
        }

        Map<UUID, Bill> bills = billsFor(tenantId, visits);

        long collected = payments.stream().mapToLong(PaymentOfVisit::amountPaise).sum();
        long refunds = -payments.stream().mapToLong(PaymentOfVisit::amountPaise).filter(a -> a < 0).sum();

        Totals totals = new Totals();
        Map<UUID, Doctor> doctors = new LinkedHashMap<>();
        Map<String, Long> services = new HashMap<>();
        List<DueRow> dues = new ArrayList<>();
        List<RevenueView.Adjustment> adjustments = new ArrayList<>();
        Map<UUID, Optional<PatientSummary>> patientCache = new HashMap<>();
        Map<UUID, String> actorNames = new HashMap<>();

        for (VisitForRevenue visit : visits) {
            Bill bill = bills.get(visit.id());
            if (bill == null) {
                continue;
            }
            if (STAGE_BILLING.equals(visit.stage())) {
                totals.atCounter += Math.max(bill.math().balancePaise(), 0);
            } else if (STAGE_DONE.equals(visit.stage())) {
                addDoneBill(visit, bill, totals, doctors, services, dues);
                if (bill.discountPercent() > 0 && bill.math().discountPaise() > 0) {
                    adjustments.add(new RevenueView.Adjustment("DISCOUNT",
                            patientName(tenantId, visit.patientId(), patientCache), bill.math().discountPaise(),
                            discountDetail(bill), istDate(visit.createdAt())));
                }
            }
        }

        for (PaymentOfVisit payment : payments) {
            VisitForRevenue visit = visitsById.get(payment.visitId());
            if (visit != null) {
                doctors.computeIfAbsent(visit.doctorMembershipId(), id -> new Doctor(id, visit.doctorName()))
                        .collected += payment.amountPaise();
            }
            if (payment.amountPaise() < 0) {
                adjustments.add(new RevenueView.Adjustment("REFUND",
                        visit == null ? "Unknown" : patientName(tenantId, visit.patientId(), patientCache),
                        -payment.amountPaise(), refundDetail(tenantId, payment, actorNames), istDate(payment.recordedAt())));
            }
        }

        PaymentTotals previous = paymentRepository.totalsBetween(tenantId, previousFrom, from);
        Instant startOfToday = today.atStartOfDay(BillingService.INDIA).toInstant();
        long cashInDrawer = paymentRepository.sumByModeSince(tenantId, "CASH", startOfToday);

        return new RevenueView(range, collected, changePercent(collected, previous), totals.billed,
                totals.patients.size(), totals.doneBills == 0 ? 0 : totals.billed / totals.doneBills, totals.due,
                totals.atCounter, totals.discount, refunds, cashInDrawer, days(firstDay, today, payments),
                modes(payments), doctorViews(doctors.values()), topServices(services),
                dueViews(tenantId, dues, patientCache), sortAdjustments(adjustments));
    }

    private static int lengthOf(String range) {
        if ("TODAY".equals(range)) {
            return 1;
        }
        if ("WEEK".equals(range)) {
            return 7;
        }
        if ("MONTH".equals(range)) {
            return 30;
        }
        throw new InvalidInputException("range", "Choose today, last 7 days or last 30 days");
    }

    /** Null when the previous period had no payments or refunds at all (or nets to zero, so there is nothing to compare). */
    static Integer changePercent(long collected, PaymentTotals previous) {
        if (previous.count() == 0 || previous.netPaise() == 0) {
            return null;
        }
        return (int) Math.round((double) (collected - previous.netPaise()) * 100 / Math.abs(previous.netPaise()));
    }

    // --- bills ---

    /** Splits ids into lists of at most {@link #CHUNK}, keeping each {@code in (:ids)} list small. */
    static <T> List<List<T>> chunks(java.util.Collection<T> ids) {
        List<T> all = new ArrayList<>(ids);
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            result.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return result;
    }

    private record Bill(UUID id, int discountPercent, String discountReason, List<BillItemOfBill> items, BillMath math) {
    }

    private Map<UUID, Bill> billsFor(UUID tenantId, List<VisitForRevenue> visits) {
        Set<UUID> visitIds = visits.stream()
                .filter(v -> STAGE_DONE.equals(v.stage()) || STAGE_BILLING.equals(v.stage()))
                .map(VisitForRevenue::id).collect(Collectors.toSet());
        Map<UUID, BillRow> rows = new HashMap<>();
        chunks(visitIds).forEach(chunk -> billRepository.findByVisits(tenantId, chunk)
                .forEach(row -> rows.put(row.visitId(), row)));
        List<UUID> billIds = rows.values().stream().map(BillRow::id).toList();
        Map<UUID, List<BillItemOfBill>> itemsByBill = new HashMap<>();
        Map<UUID, Long> paidByBill = new HashMap<>();
        for (List<UUID> chunk : chunks(billIds)) {
            billItemRepository.findByBills(tenantId, chunk)
                    .forEach(item -> itemsByBill.computeIfAbsent(item.billId(), id -> new ArrayList<>()).add(item));
            for (BillPaid paid : paymentRepository.paidByBills(tenantId, chunk)) {
                paidByBill.put(paid.billId(), paid.paidPaise());
            }
        }
        Map<UUID, Bill> bills = new HashMap<>();
        for (VisitForRevenue visit : visits) {
            if (!visitIds.contains(visit.id())) {
                continue;
            }
            BillRow row = rows.get(visit.id());
            // No bill row yet means no items, no discount and no payments: the counter's synthesized bill.
            List<BillItemOfBill> items = row == null ? List.of() : itemsByBill.getOrDefault(row.id(), List.of());
            long itemsTotal = items.stream().mapToLong(item -> item.amountPaise() * item.qty()).sum();
            int percent = row == null ? 0 : row.discountPercent();
            long paid = row == null ? 0 : paidByBill.getOrDefault(row.id(), 0L);
            bills.put(visit.id(), new Bill(row == null ? null : row.id(), percent,
                    row == null ? null : row.discountReason(), items,
                    BillMath.of(visit.feePaise(), itemsTotal, percent, paid)));
        }
        return bills;
    }

    private static final class Totals {
        private long billed;
        private long due;
        private long atCounter;
        private long discount;
        private int doneBills;
        private final Set<UUID> patients = new HashSet<>();
    }

    private static final class Doctor {
        private final UUID id;
        private final String name;
        private final Set<UUID> patients = new HashSet<>();
        private long collected;

        private Doctor(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private record DueRow(VisitForRevenue visit, long balancePaise) {
    }

    private void addDoneBill(VisitForRevenue visit, Bill bill, Totals totals, Map<UUID, Doctor> doctors,
                             Map<String, Long> services, List<DueRow> dues) {
        BillMath math = bill.math();
        totals.billed += math.totalPaise();
        totals.discount += math.discountPaise();
        totals.doneBills++;
        totals.patients.add(visit.patientId());
        if (math.balancePaise() > 0) {
            totals.due += math.balancePaise();
            dues.add(new DueRow(visit, math.balancePaise()));
        }
        doctors.computeIfAbsent(visit.doctorMembershipId(), id -> new Doctor(id, visit.doctorName()))
                .patients.add(visit.patientId());
        services.merge(CONSULTATION, visit.feePaise(), Long::sum);
        for (BillItemOfBill item : bill.items()) {
            services.merge(item.name(), item.amountPaise() * item.qty(), Long::sum);
        }
    }

    // --- view assembly ---

    private static LocalDate istDate(Instant instant) {
        return instant.atZone(BillingService.INDIA).toLocalDate();
    }

    private static List<RevenueView.Day> days(LocalDate firstDay, LocalDate today, List<PaymentOfVisit> payments) {
        Map<LocalDate, Long> byDay = new TreeMap<>();
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            byDay.put(day, 0L);
        }
        payments.forEach(payment -> byDay.merge(istDate(payment.recordedAt()), payment.amountPaise(), Long::sum));
        return byDay.entrySet().stream().map(e -> new RevenueView.Day(e.getKey(), e.getValue())).toList();
    }

    private static List<RevenueView.Mode> modes(List<PaymentOfVisit> payments) {
        Map<String, Long> byMode = new LinkedHashMap<>();
        MODE_ORDER.forEach(mode -> byMode.put(mode, 0L));
        payments.forEach(payment -> byMode.merge(payment.mode(), payment.amountPaise(), Long::sum));
        return byMode.entrySet().stream().map(e -> new RevenueView.Mode(e.getKey(), e.getValue())).toList();
    }

    private static List<RevenueView.Doctor> doctorViews(java.util.Collection<Doctor> doctors) {
        return doctors.stream()
                .sorted(Comparator.comparingLong((Doctor d) -> d.collected).reversed()
                        .thenComparing(d -> d.name == null ? "" : d.name))
                .map(d -> new RevenueView.Doctor(d.id, d.name, d.patients.size(), d.collected)).toList();
    }

    private static List<RevenueView.Service> topServices(Map<String, Long> services) {
        return services.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_SERVICES).map(e -> new RevenueView.Service(e.getKey(), e.getValue())).toList();
    }

    private List<RevenueView.Due> dueViews(UUID tenantId, List<DueRow> dues,
                                           Map<UUID, Optional<PatientSummary>> cache) {
        return dues.stream().sorted(Comparator.comparing((DueRow d) -> d.visit().createdAt())).map(due -> {
            Optional<PatientSummary> patient = patient(tenantId, due.visit().patientId(), cache);
            return new RevenueView.Due(due.visit().id(), patient.map(PatientSummary::name).orElse("Unknown"),
                    patient.map(PatientSummary::mobile).orElse(null), due.visit().doctorName(),
                    istDate(due.visit().createdAt()), due.balancePaise());
        }).toList();
    }

    private static List<RevenueView.Adjustment> sortAdjustments(List<RevenueView.Adjustment> adjustments) {
        return adjustments.stream()
                .sorted(Comparator.comparing(RevenueView.Adjustment::date).reversed()
                        .thenComparing(RevenueView.Adjustment::kind))
                .toList();
    }

    private static String discountDetail(Bill bill) {
        String reason = bill.discountReason();
        return reason == null || reason.isBlank() ? bill.discountPercent() + "%"
                : bill.discountPercent() + "% - " + reason.strip();
    }

    /** A refund's reason is stored in the payment's reference column (SAH-10). */
    private String refundDetail(UUID tenantId, PaymentOfVisit payment, Map<UUID, String> actorNames) {
        String who = actorNames.computeIfAbsent(payment.recordedBy(),
                id -> actorDirectoryPort.displayName(tenantId, id).orElse("Someone"));
        String reason = payment.reference() == null || payment.reference().isBlank() ? "No reason recorded"
                : payment.reference().strip();
        return reason + " - " + payment.mode() + " - by " + who;
    }

    private Optional<PatientSummary> patient(UUID tenantId, UUID patientId, Map<UUID, Optional<PatientSummary>> cache) {
        return cache.computeIfAbsent(patientId, id -> patientLookupPort.find(tenantId, id));
    }

    private String patientName(UUID tenantId, UUID patientId, Map<UUID, Optional<PatientSummary>> cache) {
        return patient(tenantId, patientId, cache).map(PatientSummary::name).orElse("Unknown");
    }
}
