package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.DiscountLimitPort;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.EmrVisitChargePort.Line;
import com.sahaj.opd.api.EmrVisitChargePort.Outcome;
import com.sahaj.opd.api.EmrVisitChargePort.PricedLine;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.api.ServiceLookupPort.ServiceSummary;
import com.sahaj.opd.api.VisitBillingPort;
import com.sahaj.opd.api.VisitBillingPort.VisitForBilling;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BUG-emr-finish-no-call (SAH-51-2, SAH-48-4, SAH-10; ADR-022 D7, ADR-025 D6): what the counter sees after the doctor's sign of
 * a General Medicine visit. The sign reaches billing only through {@link EmrVisitChargePort} (in the sign transaction); the
 * counter reads the same rows through {@link BillingService}. Here both run on one in-memory store keyed by clinic, so the
 * test proves the joined result: the consultation line from the visit's fee, one line per "Bill today" service at the price
 * list in paise, no second line for a save followed by the sign or a second sync, and nothing visible to another clinic.
 */
class VisitBillAfterEmrSignTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID DOCTOR_USER = UUID.randomUUID();
    private static final UUID DESK_USER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID DRESSING = UUID.randomUUID();
    private static final long FEE_PAISE = 50_000L;
    private static final long DRESSING_PAISE = 15_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T06:00:00Z"), ZoneOffset.UTC);

    private final Store store = new Store();
    private final ServiceLookupPort services = mock(ServiceLookupPort.class);
    private final VisitBillingPort visits = mock(VisitBillingPort.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final DiscountLimitPort discountLimits = mock(DiscountLimitPort.class);
    private final ActorDirectoryPort actors = mock(ActorDirectoryPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private EmrVisitChargePort charges;
    private BillingService billing;

    @BeforeEach
    void setUp() {
        charges = new EmrVisitChargeAdapter(store.bills, store.items, store.payments, services, audit);
        billing = new BillingService(store.bills, store.items, store.payments, mock(DayClosingRepository.class), visits,
                services, discountLimits, patients, actors, audit, CLOCK, mock(CoursePaymentRepository.class));
        when(services.find(TENANT, DRESSING)).thenReturn(Optional.of(new ServiceSummary(DRESSING, "Dressing",
                DRESSING_PAISE, true)));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Test Patient", 40, "M",
                "9000000000")));
        when(actors.displayName(any(), any())).thenReturn(Optional.of("Front desk"));
        visitIsAt("BILLING");
    }

    private void visitIsAt(String stage) {
        VisitForBilling visit = new VisitForBilling(VISIT, PATIENT, "Dr Test GM Live", "NEW", stage, 4, FEE_PAISE);
        when(visits.find(TENANT, VISIT)).thenReturn(Optional.of(visit));
        when(visits.today(TENANT)).thenReturn(List.of(visit));
    }

    /** The sign's wanted set: the EMR item id, the service, the quantity (EmrRecordService.syncServiceLines). */
    private Outcome sign(int qty) {
        return charges.syncEmrLines(TENANT, DOCTOR_USER, VISIT, List.of(new Line("emr-item-1", DRESSING, qty)));
    }

    @Test
    void aSignedGmVisitWithOneBillTodayServiceGivesTheConsultationLineAndTheServiceLineInPaise() {
        assertThat(sign(1)).isEqualTo(Outcome.SYNCED);

        BillView bill = billing.get(TENANT, "FRONT_DESK", VISIT);

        assertThat(bill.items()).extracting(BillItemView::name, BillItemView::amountPaise, BillItemView::qty,
                BillItemView::removable, BillItemView::source).containsExactly(
                        tuple("Consultation", FEE_PAISE, 1, false, "COUNTER"),
                        tuple("Dressing", DRESSING_PAISE, 1, true, "EMR"));
        assertThat(bill.subtotalPaise()).isEqualTo(65_000L);
        assertThat(bill.totalPaise()).isEqualTo(65_000L);
        assertThat(bill.paidPaise()).isZero();
        assertThat(bill.balancePaise()).isEqualTo(65_000L);
        assertThat(bill.canCollect()).isTrue();
    }

    @Test
    void theVisitIsOnTheCounterUnderAwaitingPaymentWithTheFullTotal() {
        sign(2);

        CounterView counter = billing.counter(TENANT);

        assertThat(counter.awaiting()).extracting(BillSummaryView::visitId, BillSummaryView::totalPaise,
                BillSummaryView::balancePaise).containsExactly(tuple(VISIT, 80_000L, 80_000L));
        assertThat(counter.dues()).isEmpty();
    }

    @Test
    void aSaveThenTheSignAndASecondSyncWithTheSameItemGiveOneLine() {
        sign(1); // the draft save
        sign(1); // the sign (ADR-025 D6: equal once more)
        sign(1); // a retried sign request reaching the port again

        assertThat(store.itemRows).hasSize(1);
        assertThat(billing.get(TENANT, "FRONT_DESK", VISIT).totalPaise()).isEqualTo(65_000L);
        assertThat(store.billRows).as("one bill per visit").hasSize(1);
    }

    @Test
    void theQuantityTheDoctorChangedBeforeTheSignIsTheQuantityBilled() {
        sign(1);
        sign(3);

        assertThat(billing.get(TENANT, "FRONT_DESK", VISIT).items()).extracting(BillItemView::name, BillItemView::qty)
                .containsExactly(tuple("Consultation", 1), tuple("Dressing", 3));
        assertThat(billing.get(TENANT, "FRONT_DESK", VISIT).totalPaise()).isEqualTo(95_000L);
    }

    @Test
    void aDentalPlanLineSentToBillingIsAddedOnceBesideTheService() {
        sign(1);
        List<PricedLine> plan = List.of(new PricedLine("plan-row-1", "Composite filling", 120_000L));
        charges.addPricedLines(TENANT, DOCTOR_USER, VISIT, plan);
        charges.addPricedLines(TENANT, DOCTOR_USER, VISIT, plan);

        BillView bill = billing.get(TENANT, "FRONT_DESK", VISIT);

        assertThat(bill.items()).extracting(BillItemView::name, BillItemView::source).containsExactly(
                tuple("Consultation", "COUNTER"), tuple("Dressing", "EMR"), tuple("Composite filling", "EMR_PLAN"));
        assertThat(bill.totalPaise()).isEqualTo(FEE_PAISE + DRESSING_PAISE + 120_000L);
    }

    @Test
    void theLinesAreWrittenForTheSignersClinicOnlyAndAnotherClinicSeesNoBill() {
        sign(1);

        assertThat(store.billRows).allMatch(b -> b.tenantId.equals(TENANT));
        assertThat(store.itemRows).allMatch(i -> i.tenantId.equals(TENANT));
        assertThat(store.bills.findByVisit(OTHER_TENANT, VISIT)).isEmpty();
        assertThat(charges.billedState(OTHER_TENANT, VISIT)).isEmpty();
        assertThat(charges.syncEmrLines(OTHER_TENANT, DOCTOR_USER, VISIT, List.of(new Line("emr-item-1", DRESSING, 1))))
                .as("the other clinic has no such service").isEqualTo(Outcome.INVALID_SERVICE);
        assertThat(store.itemRows).hasSize(1);
    }

    @Test
    void payingTheBalanceInFullMovesTheVisitToDone() {
        sign(1);

        BillView paid = billing.recordPayment(TENANT, DESK_USER, "FRONT_DESK", VISIT,
                new PaymentInput("CASH", 65_000L, null));

        assertThat(paid.balancePaise()).isZero();
        verify(visits).advanceToDone(TENANT, DESK_USER, "FRONT_DESK", VISIT);
    }

    @Test
    void aPartPaymentLeavesTheVisitInBillingWithTheRestDue() {
        sign(1);

        BillView part = billing.recordPayment(TENANT, DESK_USER, "FRONT_DESK", VISIT,
                new PaymentInput("UPI", 50_000L, "ref-1"));

        assertThat(part.balancePaise()).isEqualTo(15_000L);
        verify(visits, never()).advanceToDone(any(), any(), any(), eq(VISIT));
    }

    @Test
    void aSignWithNoBillTodayServiceWritesNoBillRowAndTheCounterShowsTheConsultationFeeOnly() {
        assertThat(charges.syncEmrLines(TENANT, DOCTOR_USER, VISIT, List.of())).isEqualTo(Outcome.SYNCED);

        assertThat(store.billRows).isEmpty();
        BillView bill = billing.get(TENANT, "FRONT_DESK", VISIT);
        assertThat(bill.items()).extracting(BillItemView::name).containsExactly("Consultation");
        assertThat(bill.balancePaise()).isEqualTo(FEE_PAISE);
    }

    // ------------------------------------------------------------------------------------------------------------
    // One in-memory store behind the three repository ports, keyed by clinic as row level security keys the tables.
    // ------------------------------------------------------------------------------------------------------------

    private static final class BillRec {
        final UUID tenantId;
        final UUID id;
        final UUID visitId;
        int discountPercent;
        String discountReason;

        BillRec(UUID tenantId, UUID id, UUID visitId) {
            this.tenantId = tenantId;
            this.id = id;
            this.visitId = visitId;
        }
    }

    private static final class ItemRec {
        final UUID tenantId;
        final UUID id;
        final UUID billId;
        final UUID serviceId;
        final String name;
        final long amountPaise;
        int qty;
        final String sourceKind;
        final String sourceKey;

        ItemRec(UUID tenantId, UUID id, UUID billId, UUID serviceId, String name, long amountPaise, int qty,
                String sourceKind, String sourceKey) {
            this.tenantId = tenantId;
            this.id = id;
            this.billId = billId;
            this.serviceId = serviceId;
            this.name = name;
            this.amountPaise = amountPaise;
            this.qty = qty;
            this.sourceKind = sourceKind;
            this.sourceKey = sourceKey;
        }
    }

    private static final class PayRec {
        final UUID tenantId;
        final UUID billId;
        final PaymentRepository.PaymentRow row;

        PayRec(UUID tenantId, UUID billId, PaymentRepository.PaymentRow row) {
            this.tenantId = tenantId;
            this.billId = billId;
            this.row = row;
        }
    }

    /**
     * The JDBC repositories insert without a tenant id: the column defaults to the bound clinic. The store does the same with
     * {@link #bound}, the clinic of the bill the row belongs to.
     */
    private static final class Store {
        final List<BillRec> billRows = new ArrayList<>();
        final List<ItemRec> itemRows = new ArrayList<>();
        final List<PayRec> payRows = new ArrayList<>();

        UUID tenantOfBill(UUID billId) {
            return billRows.stream().filter(b -> b.id.equals(billId)).map(b -> b.tenantId).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no bill " + billId));
        }

        final BillRepository bills = new BillRepository() {
            @Override
            public Optional<BillRow> findByVisit(UUID tenantId, UUID visitId) {
                return billRows.stream().filter(b -> b.tenantId.equals(tenantId) && b.visitId.equals(visitId))
                        .map(b -> new BillRow(b.id, b.visitId, b.discountPercent, b.discountReason, 1)).findFirst();
            }

            @Override
            public List<BillRow> findByVisits(UUID tenantId, Collection<UUID> visitIds) {
                List<BillRow> out = new ArrayList<>();
                for (UUID visitId : visitIds) {
                    findByVisit(tenantId, visitId).ifPresent(out::add);
                }
                return out;
            }

            @Override
            public UUID getOrCreate(UUID tenantId, UUID visitId, UUID actorUserId) {
                return findByVisit(tenantId, visitId).map(BillRow::id).orElseGet(() -> {
                    BillRec bill = new BillRec(tenantId, UUID.randomUUID(), visitId);
                    billRows.add(bill);
                    return bill.id;
                });
            }

            @Override
            public void updateDiscount(UUID tenantId, UUID id, int percent, String reason, UUID actorUserId) {
                billRows.stream().filter(b -> b.tenantId.equals(tenantId) && b.id.equals(id)).forEach(b -> {
                    b.discountPercent = percent;
                    b.discountReason = reason;
                });
            }
        };

        final BillItemRepository items = new BillItemRepository() {
            @Override
            public List<BillItemRow> findByBill(UUID tenantId, UUID billId) {
                return itemRows.stream().filter(i -> i.tenantId.equals(tenantId) && i.billId.equals(billId))
                        .map(i -> new BillItemRow(i.id, i.serviceId, i.name, i.amountPaise, i.qty, i.sourceKind)).toList();
            }

            @Override
            public void insert(UUID id, UUID billId, UUID serviceId, String name, long amountPaise, int qty,
                               UUID actorUserId) {
                itemRows.add(new ItemRec(tenantOfBill(billId), id, billId, serviceId, name, amountPaise, qty, null, null));
            }

            @Override
            public Optional<BillItemRow> findById(UUID tenantId, UUID billId, UUID itemId) {
                return findByBill(tenantId, billId).stream().filter(i -> i.id().equals(itemId)).findFirst();
            }

            @Override
            public void delete(UUID tenantId, UUID id) {
                itemRows.removeIf(i -> i.tenantId.equals(tenantId) && i.id.equals(id));
            }

            @Override
            public List<BillItemOfBill> findByBills(UUID tenantId, Collection<UUID> billIds) {
                return itemRows.stream().filter(i -> i.tenantId.equals(tenantId) && billIds.contains(i.billId))
                        .map(i -> new BillItemOfBill(i.billId, i.name, i.amountPaise, i.qty)).toList();
            }

            @Override
            public List<EmrLine> findEmrLines(UUID tenantId, UUID billId) {
                return itemRows.stream().filter(i -> i.tenantId.equals(tenantId) && i.billId.equals(billId)
                        && "EMR".equals(i.sourceKind))
                        .map(i -> new EmrLine(i.id, i.sourceKey, i.serviceId, i.qty, i.amountPaise)).toList();
            }

            @Override
            public void insertEmr(UUID id, UUID billId, UUID serviceId, String name, long amountPaise, int qty,
                                  String sourceKey, UUID actorUserId) {
                if (!hasKey(billId, sourceKey)) {
                    itemRows.add(new ItemRec(tenantOfBill(billId), id, billId, serviceId, name, amountPaise, qty, "EMR",
                            sourceKey));
                }
            }

            @Override
            public boolean insertPlan(UUID id, UUID billId, String label, long amountPaise, String sourceKey,
                                      UUID actorUserId) {
                if (hasKey(billId, sourceKey)) {
                    return false;
                }
                itemRows.add(new ItemRec(tenantOfBill(billId), id, billId, null, label, amountPaise, 1, "EMR_PLAN",
                        sourceKey));
                return true;
            }

            /** The unique index of bill_item on (bill_id, source_key): a repeat is ignored ("on conflict do nothing"). */
            private boolean hasKey(UUID billId, String sourceKey) {
                return itemRows.stream().anyMatch(i -> i.billId.equals(billId) && Objects.equals(i.sourceKey, sourceKey));
            }

            @Override
            public void updateQty(UUID tenantId, UUID id, int qty) {
                itemRows.stream().filter(i -> i.tenantId.equals(tenantId) && i.id.equals(id)).forEach(i -> i.qty = qty);
            }
        };

        final PaymentRepository payments = new PaymentRepository() {
            @Override
            public List<PaymentRow> findByBill(UUID tenantId, UUID billId) {
                return payRows.stream().filter(p -> p.tenantId.equals(tenantId) && p.billId.equals(billId))
                        .map(p -> p.row).toList();
            }

            @Override
            public void insert(UUID id, UUID billId, String mode, long amountPaise, String reference, UUID actorUserId) {
                payRows.add(new PayRec(tenantOfBill(billId), billId, new PaymentRow(id, mode, amountPaise, reference,
                        actorUserId, CLOCK.instant())));
            }

            @Override
            public long sumByModeSince(UUID tenantId, String mode, Instant since) {
                return 0;
            }

            @Override
            public long netSince(UUID tenantId, Instant since) {
                return 0;
            }

            @Override
            public boolean existsSince(UUID tenantId, UUID billId, Instant since) {
                return !findByBill(tenantId, billId).isEmpty();
            }

            @Override
            public List<PaymentOfVisit> findBetween(UUID tenantId, Instant from, Instant to) {
                return List.of();
            }

            @Override
            public PaymentTotals totalsBetween(UUID tenantId, Instant from, Instant to) {
                return new PaymentTotals(0, 0);
            }

            @Override
            public List<BillPaid> paidByBills(UUID tenantId, Collection<UUID> billIds) {
                List<BillPaid> out = new ArrayList<>();
                for (UUID billId : billIds) {
                    long paid = findByBill(tenantId, billId).stream().mapToLong(PaymentRow::amountPaise).sum();
                    if (paid != 0) {
                        out.add(new BillPaid(billId, paid));
                    }
                }
                return out;
            }
        };
    }
}
