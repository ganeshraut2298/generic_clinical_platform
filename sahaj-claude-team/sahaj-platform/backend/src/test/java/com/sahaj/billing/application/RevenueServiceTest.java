package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-16: every figure of the revenue report, the previous-period change, IST day boundaries and the input check. */
class RevenueServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCTOR_A = UUID.randomUUID();
    private static final UUID DOCTOR_B = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    // 15:30 IST on 4 Nov 2026.
    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant TODAY_START = Instant.parse("2026-11-03T18:30:00Z");
    private static final Instant EARLIER_TODAY = Instant.parse("2026-11-04T05:00:00Z");

    private BillRepository billRepository;
    private BillItemRepository billItemRepository;
    private PaymentRepository paymentRepository;
    private VisitBillingPort visitBillingPort;
    private PatientLookupPort patientLookupPort;
    private ActorDirectoryPort actorDirectoryPort;
    private OwnerGuard ownerGuard;
    private CoursePaymentRepository coursePayments;
    private RevenueService service;

    private final List<VisitForRevenue> visits = new ArrayList<>();
    private final List<BillRow> bills = new ArrayList<>();
    private final List<BillItemOfBill> items = new ArrayList<>();
    private final List<BillPaid> paid = new ArrayList<>();
    private final List<PaymentOfVisit> payments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        billRepository = mock(BillRepository.class);
        billItemRepository = mock(BillItemRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        visitBillingPort = mock(VisitBillingPort.class);
        patientLookupPort = mock(PatientLookupPort.class);
        actorDirectoryPort = mock(ActorDirectoryPort.class);
        ownerGuard = mock(OwnerGuard.class);
        coursePayments = mock(CoursePaymentRepository.class);
        doReturn(new PaymentTotals(0, 0)).when(coursePayments).totalsBetween(any(), any(), any());
        doReturn(List.of()).when(coursePayments).findBetween(any(), any(), any());
        service = new RevenueService(billRepository, billItemRepository, paymentRepository, visitBillingPort,
                patientLookupPort, actorDirectoryPort, ownerGuard, Clock.fixed(NOW, ZoneOffset.UTC),
                coursePayments);

        doReturn(visits).when(visitBillingPort).between(eq(TENANT), any(), any());
        doReturn(List.of()).when(visitBillingPort).findAll(eq(TENANT), any());
        doReturn(bills).when(billRepository).findByVisits(eq(TENANT), any());
        doReturn(items).when(billItemRepository).findByBills(eq(TENANT), any());
        doReturn(paid).when(paymentRepository).paidByBills(eq(TENANT), any());
        doReturn(payments).when(paymentRepository).findBetween(eq(TENANT), any(), any());
        doReturn(new PaymentTotals(0, 0)).when(paymentRepository).totalsBetween(eq(TENANT), any(), any());
        when(actorDirectoryPort.displayName(TENANT, USER)).thenReturn(Optional.of("Meena Front"));
    }

    private VisitForRevenue visit(String stage, UUID doctor, String doctorName, long fee, Instant createdAt) {
        UUID patientId = UUID.randomUUID();
        VisitForRevenue visit = new VisitForRevenue(UUID.randomUUID(), patientId, doctor, doctorName, stage, fee,
                createdAt);
        visits.add(visit);
        when(patientLookupPort.find(TENANT, patientId)).thenReturn(Optional.of(
                new PatientSummary(patientId, "Patient " + visits.size(), 30, "F", "98765432" + visits.size())));
        return visit;
    }

    private UUID bill(VisitForRevenue visit, int percent, String reason, long paidPaise) {
        UUID billId = UUID.randomUUID();
        bills.add(new BillRow(billId, visit.id(), percent, reason, 0));
        paid.add(new BillPaid(billId, paidPaise));
        return billId;
    }

    private void pay(VisitForRevenue visit, String mode, long amount, Instant at, String reference) {
        payments.add(new PaymentOfVisit(visit.id(), mode, amount, reference, USER, at));
    }

    @Test
    void chunksSplitIdsAtOneThousand() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            ids.add(UUID.randomUUID());
        }

        List<List<UUID>> chunks = RevenueService.chunks(ids);

        assertThat(chunks).extracting(List::size).containsExactly(1000, 1000, 500);
        assertThat(RevenueService.chunks(List.<UUID>of())).isEmpty();
    }

    @Test
    void billLookupsAreSentInChunksOfAThousandVisits() {
        for (int i = 0; i < 1500; i++) {
            visits.add(new VisitForRevenue(UUID.randomUUID(), UUID.randomUUID(), DOCTOR_A, "Dr A", "DONE", 10000,
                    EARLIER_TODAY));
        }

        service.report(TENANT, USER, "TODAY");

        ArgumentCaptor<java.util.Collection<UUID>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(billRepository, org.mockito.Mockito.times(2)).findByVisits(eq(TENANT), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(ids -> assertThat(ids.size()).isLessThanOrEqualTo(1000));
        assertThat(captor.getAllValues().stream().mapToInt(java.util.Collection::size).sum()).isEqualTo(1500);
    }

    @Test
    void theSameRecorderNameIsLookedUpOnceAcrossRefunds() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr A", 10000, EARLIER_TODAY);
        bill(visit, 0, null, 10000);
        pay(visit, "CASH", -1000, EARLIER_TODAY, "Wrong item");
        pay(visit, "CASH", -2000, EARLIER_TODAY, "Not given");

        RevenueView view = service.report(TENANT, USER, "TODAY");

        assertThat(view.adjustments()).hasSize(2);
        verify(actorDirectoryPort, org.mockito.Mockito.times(1)).displayName(TENANT, USER);
    }

    @Test
    void anUnknownRangeIs400() {
        assertThatThrownBy(() -> service.report(TENANT, USER, "YEAR")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.report(TENANT, USER, null)).isInstanceOf(InvalidInputException.class);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void anEmptyClinicGivesZerosEveryDayAndNoChange() {
        RevenueView week = service.report(TENANT, USER, "WEEK");

        assertThat(week.collectedPaise()).isZero();
        assertThat(week.collectedChangePercent()).isNull();
        assertThat(week.averageBillPaise()).isZero();
        assertThat(week.days()).hasSize(7);
        assertThat(week.days().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(week.days().get(6).date()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(week.days()).allMatch(d -> d.collectedPaise() == 0);
        assertThat(week.modes()).extracting(RevenueView.Mode::mode).containsExactly("CASH", "UPI", "CARD");
        assertThat(service.report(TENANT, USER, "MONTH").days()).hasSize(30);
        assertThat(service.report(TENANT, USER, "TODAY").days()).hasSize(1);
    }

    @Test
    void collectedIsNetOfRefundsAndModesAndDaysFollowIt() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        bill(visit, 0, null, 40000);
        pay(visit, "CASH", 30000, EARLIER_TODAY, null);
        pay(visit, "UPI", 20000, EARLIER_TODAY, "ref");
        pay(visit, "CASH", -10000, EARLIER_TODAY.plusSeconds(60), "Wrong item");

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.collectedPaise()).isEqualTo(40000);
        assertThat(report.refundPaise()).isEqualTo(10000);
        assertThat(report.days()).singleElement().satisfies(d -> assertThat(d.collectedPaise()).isEqualTo(40000));
        assertThat(report.modes()).extracting(RevenueView.Mode::amountPaise).containsExactly(20000L, 20000L, 0L);
        assertThat(report.adjustments()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("REFUND");
            assertThat(a.amountPaise()).isEqualTo(10000);
            assertThat(a.detail()).isEqualTo("Wrong item - CASH - by Meena Front");
        });
    }

    @Test
    void billedPatientsAverageAndDiscountUseTheCountersBillMath() {
        VisitForRevenue first = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        UUID firstBill = bill(first, 10, "Senior citizen", 45000);
        items.add(new BillItemOfBill(firstBill, "ECG", 10000, 1));
        VisitForRevenue second = visit("DONE", DOCTOR_A, "Dr Rao", 30000, EARLIER_TODAY);
        bill(second, 0, null, 30000);

        RevenueView report = service.report(TENANT, USER, "TODAY");

        // first: subtotal 60000, discount 6000, total 54000, balance 9000; second: total 30000.
        assertThat(report.billedPaise()).isEqualTo(84000);
        assertThat(report.patients()).isEqualTo(2);
        assertThat(report.averageBillPaise()).isEqualTo(42000);
        assertThat(report.discountPaise()).isEqualTo(6000);
        assertThat(report.duePaise()).isEqualTo(9000);
        assertThat(report.adjustments()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("DISCOUNT");
            assertThat(a.amountPaise()).isEqualTo(6000);
            assertThat(a.detail()).isEqualTo("10% - Senior citizen");
        });
    }

    @Test
    void duesComeOnlyFromDoneBillsWithABalanceOldestFirstAndCounterBalanceIsSeparate() {
        VisitForRevenue newer = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        bill(newer, 0, null, 20000);
        VisitForRevenue older = visit("DONE", DOCTOR_B, "Dr Iyer", 40000, EARLIER_TODAY.minusSeconds(3600));
        bill(older, 0, null, 0);
        VisitForRevenue settled = visit("DONE", DOCTOR_A, "Dr Rao", 10000, EARLIER_TODAY);
        bill(settled, 0, null, 10000);
        VisitForRevenue atCounter = visit("BILLING", DOCTOR_A, "Dr Rao", 25000, EARLIER_TODAY);
        bill(atCounter, 0, null, 5000);
        VisitForRevenue waiting = visit("WAITING", DOCTOR_A, "Dr Rao", 99900, EARLIER_TODAY);

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.duePaise()).isEqualTo(30000 + 40000);
        assertThat(report.atCounterPaise()).isEqualTo(20000);
        assertThat(report.dues()).extracting(RevenueView.Due::visitId).containsExactly(older.id(), newer.id());
        assertThat(report.dues().get(0).balancePaise()).isEqualTo(40000);
        assertThat(report.dues().get(0).mobile()).isNotNull();
        assertThat(report.dues().get(0).doctorName()).isEqualTo("Dr Iyer");
        assertThat(report.billedPaise()).isEqualTo(100000);
        assertThat(report.dues()).extracting(RevenueView.Due::visitId)
                .doesNotContain(atCounter.id(), waiting.id(), settled.id());
    }

    @Test
    void aDoneVisitWithNoBillRowStillBillsItsConsultationFee() {
        visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.billedPaise()).isEqualTo(50000);
        assertThat(report.duePaise()).isEqualTo(50000);
    }

    @Test
    void doctorsAreGroupedWithDistinctPatientsAndCollected() {
        VisitForRevenue a1 = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        bill(a1, 0, null, 50000);
        VisitForRevenue a2 = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        bill(a2, 0, null, 50000);
        VisitForRevenue b1 = visit("DONE", DOCTOR_B, "Dr Iyer", 80000, EARLIER_TODAY);
        bill(b1, 0, null, 80000);
        pay(a1, "CASH", 50000, EARLIER_TODAY, null);
        pay(a2, "CASH", 50000, EARLIER_TODAY, null);
        pay(b1, "CARD", 80000, EARLIER_TODAY, null);

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.doctors()).hasSize(2);
        assertThat(report.doctors().get(0).doctorName()).isEqualTo("Dr Rao");
        assertThat(report.doctors().get(0).patients()).isEqualTo(2);
        assertThat(report.doctors().get(0).collectedPaise()).isEqualTo(100000);
        assertThat(report.doctors().get(1).doctorMembershipId()).isEqualTo(DOCTOR_B);
        assertThat(report.doctors().get(1).collectedPaise()).isEqualTo(80000);
    }

    @Test
    void aPaymentOnAnEarlierVisitIsAttributedToItsDoctorEvenIfTheVisitIsOutsideTheRange() {
        VisitForRevenue old = new VisitForRevenue(UUID.randomUUID(), UUID.randomUUID(), DOCTOR_B, "Dr Iyer", "DONE",
                1000, Instant.parse("2026-09-01T05:00:00Z"));
        doReturn(List.of(old)).when(visitBillingPort).findAll(eq(TENANT), any());
        when(patientLookupPort.find(TENANT, old.patientId())).thenReturn(Optional.empty());
        pay(old, "CASH", 7000, EARLIER_TODAY, null);

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.collectedPaise()).isEqualTo(7000);
        assertThat(report.doctors()).singleElement().satisfies(d -> {
            assertThat(d.doctorName()).isEqualTo("Dr Iyer");
            assertThat(d.collectedPaise()).isEqualTo(7000);
            assertThat(d.patients()).isZero();
        });
        assertThat(report.billedPaise()).isZero();
    }

    @Test
    void topServicesAreRankedByBilledAmountAndCappedAtSixWithConsultationNamed() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        UUID billId = bill(visit, 0, null, 0);
        for (int i = 1; i <= 7; i++) {
            items.add(new BillItemOfBill(billId, "Service " + i, i * 1000L, 2));
        }

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.topServices()).hasSize(6);
        assertThat(report.topServices().get(0)).isEqualTo(new RevenueView.Service("Consultation", 50000));
        assertThat(report.topServices().get(1)).isEqualTo(new RevenueView.Service("Service 7", 14000));
        assertThat(report.topServices().get(5)).isEqualTo(new RevenueView.Service("Service 3", 6000));
    }

    @Test
    void changeIsComparedWithThePreviousPeriodOfTheSameLength() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 60000, EARLIER_TODAY);
        bill(visit, 0, null, 60000);
        pay(visit, "CASH", 60000, EARLIER_TODAY, null);
        doReturn(new PaymentTotals(40000, 3)).when(paymentRepository).totalsBetween(eq(TENANT), any(), any());

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.collectedChangePercent()).isEqualTo(50);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).totalsBetween(eq(TENANT), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-11-02T18:30:00Z"));
        assertThat(to.getValue()).isEqualTo(TODAY_START);
    }

    @Test
    void aFallInCollectionsIsNegativeAndNoPreviousActivityIsNull() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 30000, EARLIER_TODAY);
        bill(visit, 0, null, 30000);
        pay(visit, "CASH", 30000, EARLIER_TODAY, null);
        doReturn(new PaymentTotals(40000, 2)).when(paymentRepository).totalsBetween(eq(TENANT), any(), any());

        assertThat(service.report(TENANT, USER, "TODAY").collectedChangePercent()).isEqualTo(-25);

        doReturn(new PaymentTotals(0, 0)).when(paymentRepository).totalsBetween(eq(TENANT), any(), any());
        assertThat(service.report(TENANT, USER, "TODAY").collectedChangePercent()).isNull();
    }

    @Test
    void previousPeriodForWeekIsTheImmediatelyPrecedingSevenDays() {
        service.report(TENANT, USER, "WEEK");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).totalsBetween(eq(TENANT), from.capture(), to.capture());
        // This week starts 29 Oct (IST); the one before starts 22 Oct.
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-10-28T18:30:00Z"));
    }

    @Test
    void previousPeriodForMonthIsTheImmediatelyPrecedingThirtyDays() {
        service.report(TENANT, USER, "MONTH");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).totalsBetween(eq(TENANT), from.capture(), to.capture());
        // This period starts 6 Oct (IST); the one before starts 6 Sep.
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-10-05T18:30:00Z"));
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-09-05T18:30:00Z"));
    }

    @Test
    void theDayBoundaryIsMidnightIndiaTime() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 10000, EARLIER_TODAY);
        bill(visit, 0, null, 10000);
        // 23:59:59 IST on 3 Nov and 00:00:00 IST on 4 Nov.
        pay(visit, "CASH", 1000, Instant.parse("2026-11-03T18:29:59Z"), null);
        pay(visit, "CASH", 2000, Instant.parse("2026-11-03T18:30:00Z"), null);

        RevenueView report = service.report(TENANT, USER, "WEEK");

        assertThat(report.days().get(5).date()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(report.days().get(5).collectedPaise()).isEqualTo(1000);
        assertThat(report.days().get(6).date()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(report.days().get(6).collectedPaise()).isEqualTo(2000);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(paymentRepository).findBetween(eq(TENANT), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-28T18:30:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-11-04T18:30:00Z"));
    }

    @Test
    void aNonOwnerIsRefusedBeforeAnythingIsRead() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, USER);

        assertThatThrownBy(() -> service.report(TENANT, USER, "TODAY")).isInstanceOf(AccessDeniedException.class);

        verify(paymentRepository, never()).findBetween(any(), any(), any());
    }

    @Test
    void justAfterUtcMidnightIsAlreadyTheNextIndianDay() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-11-03T18:45:00Z"), ZoneOffset.UTC);
        RevenueService late = new RevenueService(billRepository, billItemRepository, paymentRepository,
                visitBillingPort, patientLookupPort, actorDirectoryPort, ownerGuard, lateUtc, coursePayments);

        RevenueView report = late.report(TENANT, USER, "TODAY");

        assertThat(report.days()).singleElement()
                .satisfies(d -> assertThat(d.date()).isEqualTo(LocalDate.of(2026, 11, 4)));
        verify(paymentRepository).sumByModeSince(TENANT, "CASH", TODAY_START);
    }

    @Test
    void cashInDrawerIsTodaysNetCashFromTheCounterQuery() {
        when(paymentRepository.sumByModeSince(TENANT, "CASH", TODAY_START)).thenReturn(12345L);

        assertThat(service.report(TENANT, USER, "MONTH").cashInDrawerPaise()).isEqualTo(12345);
    }

    @Test
    void coursePaymentsCountInCollectedModesDaysAndTheCashInTheDrawer() {
        // SAH-58, DEC-042 H3: course payments are money collected; they have no visit, so no doctor line.
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 50000, EARLIER_TODAY);
        bill(visit, 0, null, 50000);
        pay(visit, "CASH", 50000, EARLIER_TODAY, null);
        doReturn(List.of(new CoursePaymentRepository.Point("UPI", 200000, EARLIER_TODAY),
                new CoursePaymentRepository.Point("CASH", 30000, EARLIER_TODAY.plusSeconds(60))))
                .when(coursePayments).findBetween(eq(TENANT), any(), any());
        when(coursePayments.sumByModeSince(TENANT, "CASH", TODAY_START)).thenReturn(30000L);
        when(paymentRepository.sumByModeSince(TENANT, "CASH", TODAY_START)).thenReturn(50000L);

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.collectedPaise()).isEqualTo(280000);
        assertThat(report.days()).singleElement().satisfies(d -> assertThat(d.collectedPaise()).isEqualTo(280000));
        assertThat(report.modes()).extracting(RevenueView.Mode::amountPaise).containsExactly(80000L, 200000L, 0L);
        assertThat(report.cashInDrawerPaise()).isEqualTo(80000);
        assertThat(report.doctors()).singleElement().satisfies(d -> assertThat(d.collectedPaise()).isEqualTo(50000));
        assertThat(report.refundPaise()).isZero();
    }

    @Test
    void thePreviousPeriodComparisonIncludesCoursePaymentsToo() {
        when(paymentRepository.totalsBetween(eq(TENANT), any(), any())).thenReturn(new PaymentTotals(100000, 2));
        doReturn(new PaymentTotals(100000, 1)).when(coursePayments).totalsBetween(eq(TENANT), any(), any());
        doReturn(List.of(new CoursePaymentRepository.Point("UPI", 300000, EARLIER_TODAY)))
                .when(coursePayments).findBetween(eq(TENANT), any(), any());

        RevenueView report = service.report(TENANT, USER, "TODAY");

        // collected 300000 against a previous 200000 (100000 at the counter + 100000 on a course): +50 percent
        assertThat(report.collectedChangePercent()).isEqualTo(50);
    }

    @Test
    void anUnknownPatientShowsAsUnknownWithoutFailing() {
        VisitForRevenue visit = visit("DONE", DOCTOR_A, "Dr Rao", 10000, EARLIER_TODAY);
        bill(visit, 0, null, 0);
        when(patientLookupPort.find(TENANT, visit.patientId())).thenReturn(Optional.empty());

        RevenueView report = service.report(TENANT, USER, "TODAY");

        assertThat(report.dues()).singleElement().satisfies(d -> {
            assertThat(d.patientName()).isEqualTo("Unknown");
            assertThat(d.mobile()).isNull();
        });
    }
}
