package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.billing.application.BillItemRepository.BillItemRow;
import com.sahaj.billing.application.BillRepository.BillRow;
import com.sahaj.billing.application.DayClosingRepository.DayClosingRow;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-10 Billing counter: lazy bill creation, items, discount limit, payment/stage-advance, refund, day closing. */
class BillingServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID VISIT_ID = UUID.randomUUID();
    private static final UUID BILL_ID = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private BillRepository billRepository;
    private BillItemRepository billItemRepository;
    private PaymentRepository paymentRepository;
    private DayClosingRepository dayClosingRepository;
    private VisitBillingPort visitBillingPort;
    private ServiceLookupPort serviceLookupPort;
    private DiscountLimitPort discountLimitPort;
    private PatientLookupPort patientLookupPort;
    private ActorDirectoryPort actorDirectoryPort;
    private AuditLog auditLog;
    private BillingService service;

    @BeforeEach
    void setUp() {
        billRepository = mock(BillRepository.class);
        billItemRepository = mock(BillItemRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        dayClosingRepository = mock(DayClosingRepository.class);
        visitBillingPort = mock(VisitBillingPort.class);
        serviceLookupPort = mock(ServiceLookupPort.class);
        discountLimitPort = mock(DiscountLimitPort.class);
        patientLookupPort = mock(PatientLookupPort.class);
        actorDirectoryPort = mock(ActorDirectoryPort.class);
        auditLog = mock(AuditLog.class);
        service = new BillingService(billRepository, billItemRepository, paymentRepository, dayClosingRepository,
                visitBillingPort, serviceLookupPort, discountLimitPort, patientLookupPort, actorDirectoryPort,
                auditLog, CLOCK);

        when(patientLookupPort.find(TENANT_ID, PATIENT_ID))
                .thenReturn(Optional.of(new PatientSummary(PATIENT_ID, "Asha Rao", 30, "F", "9000000001")));
        when(actorDirectoryPort.displayName(eq(TENANT_ID), any())).thenReturn(Optional.of("Priya"));
        when(billItemRepository.findByBill(eq(TENANT_ID), any())).thenReturn(List.of());
        when(paymentRepository.findByBill(eq(TENANT_ID), any())).thenReturn(List.of());
    }

    private static VisitForBilling visit(String stage, long feePaise) {
        return new VisitForBilling(VISIT_ID, PATIENT_ID, "Dr Rao", "NEW", stage, 7, feePaise);
    }

    // --- get: synthesized bill before any write ---

    @Test
    void aFreshVisitSynthesizesAReadOnlyBillFromTheConsultationFee() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID)).thenReturn(Optional.empty());

        BillView bill = service.get(TENANT_ID, "NURSE", VISIT_ID);

        assertThat(bill.items()).hasSize(1);
        assertThat(bill.items().get(0).removable()).isFalse();
        assertThat(bill.subtotalPaise()).isEqualTo(50000);
        assertThat(bill.totalPaise()).isEqualTo(50000);
        assertThat(bill.balancePaise()).isEqualTo(50000);
        assertThat(bill.canCollect()).isFalse();
        assertThat(bill.discountLimitPercent()).isEqualTo(0);
    }

    @Test
    void anUnknownVisitIs404() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT_ID, "OWNER", VISIT_ID)).isInstanceOf(NotFoundException.class);
    }

    // --- items ---

    @Test
    void aNonCollectRoleCannotAddAnItemAndGets403() {
        assertThatThrownBy(() -> service.addItem(TENANT_ID, USER_ID, "NURSE", VISIT_ID,
                new ItemAddInput(SERVICE_ID, 1))).isInstanceOf(AccessDeniedException.class);
        verify(billRepository, never()).getOrCreate(any(), any(), any());
    }

    @Test
    void addingAnItemCreatesTheBillLazilyAndSnapshotsTheServicePrice() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(serviceLookupPort.find(TENANT_ID, SERVICE_ID))
                .thenReturn(Optional.of(new ServiceSummary(SERVICE_ID, "Dressing", 15000, true)));
        when(billRepository.getOrCreate(TENANT_ID, VISIT_ID, USER_ID)).thenReturn(BILL_ID);
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));
        when(billItemRepository.findByBill(TENANT_ID, BILL_ID)).thenReturn(
                List.of(new BillItemRow(UUID.randomUUID(), SERVICE_ID, "Dressing", 15000, 2)));

        BillView bill = service.addItem(TENANT_ID, USER_ID, "FRONT_DESK", VISIT_ID, new ItemAddInput(SERVICE_ID, 2));

        verify(billItemRepository).insert(any(), eq(BILL_ID), eq(SERVICE_ID), eq("Dressing"), eq(15000L), eq(2),
                eq(USER_ID));
        assertThat(bill.subtotalPaise()).isEqualTo(50000 + 15000 * 2);
        verify(auditLog).record(eq("BILL_ITEM_ADDED"), eq("BILL"), eq(BILL_ID), eq(null), any());
    }

    @Test
    void anInactiveOrUnknownServiceIs400() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(serviceLookupPort.find(TENANT_ID, SERVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new ItemAddInput(SERVICE_ID, 1))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void removingAnItemDeletesItAndAudits() {
        UUID itemId = UUID.randomUUID();
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));
        when(billItemRepository.findById(TENANT_ID, BILL_ID, itemId))
                .thenReturn(Optional.of(new BillItemRow(itemId, SERVICE_ID, "Dressing", 15000, 1)));

        service.removeItem(TENANT_ID, USER_ID, "OWNER", VISIT_ID, itemId);

        verify(billItemRepository).delete(TENANT_ID, itemId);
        verify(auditLog).record(eq("BILL_ITEM_REMOVED"), eq("BILL"), eq(BILL_ID), any(), eq(null));
    }

    @Test
    void removingAnItemFromABillThatDoesNotExistIs404() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeItem(TENANT_ID, USER_ID, "OWNER", VISIT_ID, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // --- discount ---

    @Test
    void aDiscountOverTheRolesLimitIs400() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(discountLimitPort.discountLimits(TENANT_ID)).thenReturn(Map.of("FRONT_DESK", 10));

        assertThatThrownBy(() -> service.setDiscount(TENANT_ID, USER_ID, "FRONT_DESK", VISIT_ID,
                new DiscountInput(20, "goodwill"))).isInstanceOf(InvalidInputException.class);
        verify(billRepository, never()).getOrCreate(any(), any(), any());
    }

    @Test
    void aRoleWithNoDiscountKeyHasNoDiscountRight() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(discountLimitPort.discountLimits(TENANT_ID)).thenReturn(Map.of("FRONT_DESK", 10));

        assertThatThrownBy(() -> service.setDiscount(TENANT_ID, USER_ID, "FRONT_DESK", VISIT_ID,
                new DiscountInput(11, "goodwill"))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void theOwnerHasNoDiscountLimit() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(discountLimitPort.discountLimits(TENANT_ID)).thenReturn(Map.of("OWNER", 5));
        when(billRepository.getOrCreate(TENANT_ID, VISIT_ID, USER_ID)).thenReturn(BILL_ID);
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 100, "owner override", 0)));

        BillView bill = service.setDiscount(TENANT_ID, USER_ID, "OWNER", VISIT_ID, new DiscountInput(100, "waived"));

        assertThat(bill.discountLimitPercent()).isEqualTo(100);
        verify(billRepository).updateDiscount(TENANT_ID, BILL_ID, 100, "waived", USER_ID);
    }

    @Test
    void aDiscountWithinTheRolesLimitIsAccepted() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(discountLimitPort.discountLimits(TENANT_ID)).thenReturn(Map.of("FRONT_DESK", 10));
        when(billRepository.getOrCreate(TENANT_ID, VISIT_ID, USER_ID)).thenReturn(BILL_ID);
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 10, "loyal patient", 0)));

        BillView bill = service.setDiscount(TENANT_ID, USER_ID, "FRONT_DESK", VISIT_ID,
                new DiscountInput(10, "loyal patient"));

        assertThat(bill.discountPercent()).isEqualTo(10);
        assertThat(bill.discountPaise()).isEqualTo(5000);
        assertThat(bill.totalPaise()).isEqualTo(45000);
    }

    // --- payment ---

    @Test
    void aPaymentThatReachesZeroBalanceAdvancesTheVisitToDoneInTheSameCall() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(billRepository.getOrCreate(TENANT_ID, VISIT_ID, USER_ID)).thenReturn(BILL_ID);
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));
        when(paymentRepository.findByBill(TENANT_ID, BILL_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(new PaymentRow(UUID.randomUUID(), "CASH", 50000, null, USER_ID, CLOCK.instant())));

        BillView bill = service.recordPayment(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new PaymentInput("CASH", 50000L, null));

        assertThat(bill.balancePaise()).isEqualTo(0);
        verify(visitBillingPort).advanceToDone(TENANT_ID, USER_ID, "OWNER", VISIT_ID);
        verify(auditLog).record(eq("PAYMENT_RECORDED"), eq("PAYMENT"), any(), eq(null), any());
    }

    @Test
    void aPartialPaymentDoesNotAdvanceTheVisit() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(billRepository.getOrCreate(TENANT_ID, VISIT_ID, USER_ID)).thenReturn(BILL_ID);
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));
        when(paymentRepository.findByBill(TENANT_ID, BILL_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(new PaymentRow(UUID.randomUUID(), "CASH", 20000, null, USER_ID, CLOCK.instant())));

        BillView bill = service.recordPayment(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new PaymentInput("CASH", 20000L, null));

        assertThat(bill.balancePaise()).isEqualTo(30000);
        verify(visitBillingPort, never()).advanceToDone(any(), any(), any(), any());
    }

    @Test
    void aPaymentAboveTheBalanceIs400() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));
        when(billRepository.getOrCreate(TENANT_ID, VISIT_ID, USER_ID)).thenReturn(BILL_ID);
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));

        assertThatThrownBy(() -> service.recordPayment(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new PaymentInput("CASH", 100000L, null))).isInstanceOf(InvalidInputException.class);
        verify(paymentRepository, never()).insert(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void anInvalidPaymentModeIs400() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("BILLING", 50000)));

        assertThatThrownBy(() -> service.recordPayment(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new PaymentInput("CHEQUE", 1000L, null))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aNonCollectRoleCannotRecordAPaymentAndGets403() {
        assertThatThrownBy(() -> service.recordPayment(TENANT_ID, USER_ID, "DOCTOR", VISIT_ID,
                new PaymentInput("CASH", 1000L, null))).isInstanceOf(AccessDeniedException.class);
    }

    // --- refund ---

    @Test
    void onlyTheOwnerMayRefundAndGets403Otherwise() {
        assertThatThrownBy(() -> service.refund(TENANT_ID, USER_ID, "FRONT_DESK", VISIT_ID,
                new RefundInput(1000L, "CASH", "overcharged"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aRefundNeedsAReason() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("DONE", 50000)));

        assertThatThrownBy(() -> service.refund(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new RefundInput(1000L, "CASH", " "))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aRefundMoreThanWasCollectedIs400() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("DONE", 50000)));
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));
        when(paymentRepository.findByBill(TENANT_ID, BILL_ID))
                .thenReturn(List.of(new PaymentRow(UUID.randomUUID(), "CASH", 20000, null, USER_ID, CLOCK.instant())));

        assertThatThrownBy(() -> service.refund(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new RefundInput(30000L, "CASH", "overcharged"))).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aValidRefundIsRecordedAsANegativePayment() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("DONE", 50000)));
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID))
                .thenReturn(Optional.of(new BillRow(BILL_ID, VISIT_ID, 0, null, 0)));
        when(paymentRepository.findByBill(TENANT_ID, BILL_ID))
                .thenReturn(List.of(new PaymentRow(UUID.randomUUID(), "CASH", 50000, null, USER_ID, CLOCK.instant())));

        service.refund(TENANT_ID, USER_ID, "OWNER", VISIT_ID, new RefundInput(20000L, "CASH", "overcharged"));

        verify(paymentRepository).insert(any(), eq(BILL_ID), eq("CASH"), eq(-20000L), eq("overcharged"), eq(USER_ID));
        verify(auditLog).record(eq("PAYMENT_REFUNDED"), eq("PAYMENT"), any(), eq(null), any());
    }

    @Test
    void refundingABillThatNeverHadAWriteIs400() {
        when(visitBillingPort.find(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(visit("DONE", 50000)));
        when(billRepository.findByVisit(TENANT_ID, VISIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(TENANT_ID, USER_ID, "OWNER", VISIT_ID,
                new RefundInput(1000L, "CASH", "overcharged"))).isInstanceOf(InvalidInputException.class);
    }

    // --- counter ---

    @Test
    void counterSplitsVisitsByStageAndBalance() {
        UUID awaitingVisit = UUID.randomUUID();
        UUID dueVisit = UUID.randomUUID();
        UUID paidVisit = UUID.randomUUID();
        VisitForBilling awaiting = new VisitForBilling(awaitingVisit, PATIENT_ID, "Dr Rao", "NEW", "BILLING", 1, 50000);
        VisitForBilling due = new VisitForBilling(dueVisit, PATIENT_ID, "Dr Rao", "NEW", "DONE", 2, 50000);
        VisitForBilling paid = new VisitForBilling(paidVisit, PATIENT_ID, "Dr Rao", "NEW", "DONE", 3, 50000);
        when(visitBillingPort.today(TENANT_ID)).thenReturn(List.of(awaiting, due, paid));

        when(billRepository.findByVisit(TENANT_ID, awaitingVisit)).thenReturn(Optional.empty());
        when(billRepository.findByVisit(TENANT_ID, dueVisit)).thenReturn(Optional.empty());

        UUID paidBillId = UUID.randomUUID();
        when(billRepository.findByVisit(TENANT_ID, paidVisit))
                .thenReturn(Optional.of(new BillRow(paidBillId, paidVisit, 0, null, 0)));
        when(paymentRepository.findByBill(TENANT_ID, paidBillId))
                .thenReturn(List.of(new PaymentRow(UUID.randomUUID(), "CASH", 50000, null, USER_ID, CLOCK.instant())));
        when(paymentRepository.existsSince(eq(TENANT_ID), eq(paidBillId), any())).thenReturn(true);

        CounterView counter = service.counter(TENANT_ID);

        assertThat(counter.awaiting()).extracting(BillSummaryView::visitId).containsExactly(awaitingVisit);
        assertThat(counter.dues()).extracting(BillSummaryView::visitId).containsExactly(dueVisit);
        assertThat(counter.paidToday()).extracting(BillSummaryView::visitId).containsExactly(paidVisit);
    }

    // --- day closing ---

    @Test
    void closingTheDayTwiceIs409() {
        when(dayClosingRepository.findByDate(eq(TENANT_ID), any()))
                .thenReturn(Optional.of(new DayClosingRow(UUID.randomUUID(), java.time.LocalDate.now(CLOCK), 0, 0, 0,
                        null, USER_ID, CLOCK.instant())));

        assertThatThrownBy(() -> service.closeDay(TENANT_ID, USER_ID, "OWNER", new DayClosingInput(10000L, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void closingTheDaySumsCashPaymentsNetOfRefunds() {
        when(dayClosingRepository.findByDate(eq(TENANT_ID), any())).thenReturn(Optional.empty());
        when(paymentRepository.sumByModeSince(eq(TENANT_ID), eq("CASH"), any())).thenReturn(45000L);

        DayClosingView closing = service.closeDay(TENANT_ID, USER_ID, "FRONT_DESK", new DayClosingInput(45000L, "ok"));

        assertThat(closing.expectedCashPaise()).isEqualTo(45000);
        assertThat(closing.diffPaise()).isEqualTo(0);
        assertThat(closing.closedByName()).isEqualTo("Priya");
    }

    @Test
    void aNonCollectRoleCannotCloseTheDay() {
        assertThatThrownBy(() -> service.closeDay(TENANT_ID, USER_ID, "NURSE", new DayClosingInput(10000L, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- SAH-15 today summary ---

    @Test
    void todaySummaryReportsTheNetCollectedSinceMidnightIstAndCountsOnlyBillingVisitsWithABalance() {
        UUID owing = UUID.randomUUID();
        UUID settled = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        when(visitBillingPort.today(TENANT_ID)).thenReturn(List.of(
                new VisitForBilling(owing, PATIENT_ID, "Dr Rao", "NEW", "BILLING", 1, 50000),
                new VisitForBilling(settled, PATIENT_ID, "Dr Rao", "NEW", "BILLING", 2, 50000),
                new VisitForBilling(finished, PATIENT_ID, "Dr Rao", "NEW", "DONE", 3, 50000),
                new VisitForBilling(UUID.randomUUID(), PATIENT_ID, "Dr Rao", "NEW", "WITH_DOCTOR", 4, 50000)));
        when(billRepository.findByVisit(TENANT_ID, owing)).thenReturn(Optional.empty());
        UUID settledBill = UUID.randomUUID();
        when(billRepository.findByVisit(TENANT_ID, settled))
                .thenReturn(Optional.of(new BillRow(settledBill, settled, 0, null, 0)));
        when(paymentRepository.findByBill(TENANT_ID, settledBill)).thenReturn(
                List.of(new PaymentRow(UUID.randomUUID(), "CASH", 50000, null, USER_ID, CLOCK.instant())));
        // payments 80000 minus a refund of 30000, as the repository nets them
        when(paymentRepository.netSince(TENANT_ID, Instant.parse("2026-10-31T18:30:00Z"))).thenReturn(50000L);

        BillingTodaySummary summary = service.todaySummary(TENANT_ID);

        assertThat(summary.collectedPaise()).isEqualTo(50000);
        assertThat(summary.billsPending()).isEqualTo(1);
    }

    @Test
    void todaySummaryOnAQuietDayIsZeroAndTheDayBoundaryIsMidnightIst() {
        Clock justAfterMidnightIst = Clock.fixed(Instant.parse("2026-11-03T18:40:00Z"), ZoneOffset.UTC);
        BillingService lateService = new BillingService(billRepository, billItemRepository, paymentRepository,
                dayClosingRepository, visitBillingPort, serviceLookupPort, discountLimitPort, patientLookupPort,
                actorDirectoryPort, auditLog, justAfterMidnightIst);
        when(visitBillingPort.today(TENANT_ID)).thenReturn(List.of());
        when(paymentRepository.netSince(any(), any())).thenReturn(0L);

        BillingTodaySummary summary = lateService.todaySummary(TENANT_ID);

        assertThat(summary.collectedPaise()).isZero();
        assertThat(summary.billsPending()).isZero();
        verify(paymentRepository).netSince(TENANT_ID, Instant.parse("2026-11-03T18:30:00Z"));
    }
}
