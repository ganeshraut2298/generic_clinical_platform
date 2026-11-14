package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.billing.application.CourseAccountViews.Collected;
import com.sahaj.billing.application.CourseAccountViews.CourseAccount;
import com.sahaj.billing.application.CourseAccountViews.CoursePaymentInput;
import com.sahaj.billing.application.CourseAccountViews.CourseReceipt;
import com.sahaj.billing.application.CoursePaymentRepository.NewPayment;
import com.sahaj.billing.application.CoursePaymentRepository.PaymentRow;
import com.sahaj.billing.application.CoursePaymentRepository.TransferRow;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.CourseFacts;
import com.sahaj.opd.api.TreatmentCoursePort.SittingFacts;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.ClinicLetterheadPort.Letterhead;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-58: the course account, collecting a payment and the receipt (ADR-022 D12; DEC-042 H1 to H4). Rule tag B-1: the
 * rules are values from the EMR mockup, not clinically reviewed.
 */
class CourseAccountServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final Instant T1 = Instant.parse("2026-11-10T05:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-11T05:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-12T05:00:00Z");
    private static final String KEY = "pay-key-0001";
    private static final ClinicUser FRONT = new ClinicUser(USER, TENANT, "FRONT_DESK");
    private static final ClinicUser OWNER = new ClinicUser(USER, TENANT, "OWNER");
    private static final ClinicUser BILLING = new ClinicUser(USER, TENANT, "BILLING");

    private final TreatmentCoursePort courses = mock(TreatmentCoursePort.class);
    private final CoursePaymentRepository payments = mock(CoursePaymentRepository.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final ActorDirectoryPort actors = mock(ActorDirectoryPort.class);
    private final ClinicLetterheadPort letterhead = mock(ClinicLetterheadPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private CourseAccountService service;

    @BeforeEach
    void setUp() {
        service = new CourseAccountService(courses, payments, patients, actors, letterhead, audit);
    }

    private static SittingFacts sitting(UUID id, int no, String kind, long charge, String status, UUID admission,
                                        String ip, Instant doneAt) {
        return new SittingFacts(id, no, kind, null, charge, no == 3 ? "Admission deposit is due by 36 weeks." : null,
                status, null, admission, ip, admission != null, doneAt);
    }

    private void course(SittingFacts... sittings) {
        when(courses.course(TENANT, COURSE)).thenReturn(Optional.of(new CourseFacts(COURSE, "TC-0412", PATIENT,
                "DENTAL", "SITTING", "Dental treatment course", UUID.randomUUID(), List.of(sittings))));
    }

    private void money(List<PaymentRow> paid, List<TransferRow> transfers) {
        when(payments.findByCourse(TENANT, COURSE)).thenReturn(paid);
        when(payments.transfersOf(TENANT, COURSE)).thenReturn(transfers);
    }

    private static PaymentRow payment(long receipt, String type, String mode, long amount, UUID sitting, String label,
                                      Instant at, String hash) {
        return new PaymentRow(UUID.randomUUID(), COURSE, PATIENT, receipt, type, mode, amount, sitting, label, false,
                hash, USER, at);
    }

    // ---- the account ----

    @Test
    void theAccountAddsUpEstimatePaidCreditAndBalanceToTheRupee() {
        course(sitting(S1, 1, "OPD", 100_000, "DONE", null, null, T2), sitting(S2, 2, "OPD", 50_000, "TODAY", null,
                null, null), sitting(S3, 4, "OPD", 50_000, "PLANNED", null, null, null));
        money(List.of(payment(1, "ADVANCE", "UPI", 150_000, null, "Course advance", T1, "h")), List.of());

        CourseAccount account = service.account(FRONT, COURSE);

        assertThat(account.courseNo()).isEqualTo("TC-0412");
        assertThat(account.estimatePaise()).isEqualTo(200_000);
        assertThat(account.paidPaise()).isEqualTo(150_000);
        assertThat(account.creditPaise()).isEqualTo(50_000);
        assertThat(account.dueFromEarlierPaise()).isZero();
        assertThat(account.todayChargePaise()).isEqualTo(50_000);
        assertThat(account.dueTodayPaise()).isZero();
        assertThat(account.balancePaise()).isEqualTo(50_000);
        assertThat(account.sittingCount()).isEqualTo(3);
        assertThat(account.doneCount()).isEqualTo(1);
        assertThat(account.receiptCount()).isEqualTo(1);
        assertThat(account.canCollect()).isTrue();
        // paid + credit used (negative) = the credit left
        long ledgerSum = account.ledger().stream().mapToLong(CourseAccountViews.LedgerLine::amountPaise).sum();
        assertThat(ledgerSum).isEqualTo(account.creditPaise());
        assertThat(account.ledger()).extracting(CourseAccountViews.LedgerLine::kind).containsExactly("PAYMENT",
                "CREDIT_USED");
        assertThat(account.ledger().get(0).receiptNo()).isEqualTo("RC-1");
        assertThat(account.ledger().get(1).against()).isEqualTo("Sitting 1");
        assertThat(account.ledger().get(1).receiptNo()).isNull();
        assertThat(account.suggestions().partPaymentPaise()).isEqualTo(50_000);
        assertThat(account.suggestions().ipdDepositPaise()).isNull();
    }

    @Test
    void whatIsDueFromEarlierShowsWhenTheMoneyDoesNotCoverTheDoneSittings() {
        course(sitting(S1, 1, "OPD", 100_000, "DONE", null, null, T1), sitting(S2, 2, "OPD", 50_000, "TODAY", null,
                null, null));
        money(List.of(payment(1, "PART_PAYMENT", "CASH", 30_000, null, "Course balance", T1, "h")), List.of());

        CourseAccount account = service.account(OWNER, COURSE);

        assertThat(account.creditPaise()).isZero();
        assertThat(account.dueFromEarlierPaise()).isEqualTo(70_000);
        assertThat(account.dueTodayPaise()).isEqualTo(120_000);
        assertThat(account.balancePaise()).isEqualTo(120_000);
    }

    @Test
    void aPlannedAdmissionOffersTheDepositSuggestionAndTheNextAdmissionBlock() {
        course(sitting(S1, 1, "OPD", 100_000, "PLANNED", null, null, null),
                sitting(S3, 3, "DAY_CARE", 300_000, "PLANNED", null, null, null));
        money(List.of(), List.of());

        CourseAccount account = service.account(BILLING, COURSE);

        assertThat(account.nextPlannedAdmission().sittingId()).isEqualTo(S3);
        assertThat(account.nextPlannedAdmission().sittingNo()).isEqualTo(3);
        assertThat(account.nextPlannedAdmission().chargePaise()).isEqualTo(300_000);
        assertThat(account.nextPlannedAdmission().note()).isEqualTo("Admission deposit is due by 36 weeks.");
        assertThat(account.suggestions().ipdDepositPaise()).isEqualTo(100_000);
        assertThat(account.canCollect()).isFalse();
        assertThat(account.ledger()).isEmpty();
    }

    @Test
    void creditCarriedToAnAdmissionLeavesTheCourseAccountAndShowsAsANegativeLine() {
        course(sitting(S1, 1, "OPD", 100_000, "PLANNED", null, null, null),
                sitting(S3, 3, "DAY_CARE", 300_000, "TODAY", ADMISSION, "IP/26-27/0003", null));
        money(List.of(payment(1, "IPD_DEPOSIT", "UPI", 120_000, S3, "Admission deposit", T1, "h")),
                List.of(new TransferRow(UUID.randomUUID(), COURSE, S3, ADMISSION, 120_000, USER, T2)));

        CourseAccount account = service.account(OWNER, COURSE);

        assertThat(account.estimatePaise()).isEqualTo(100_000);
        assertThat(account.paidPaise()).isZero();
        assertThat(account.creditPaise()).isZero();
        assertThat(account.ledger()).extracting(CourseAccountViews.LedgerLine::kind).containsExactly("PAYMENT",
                "CARRIED_TO_IPD");
        assertThat(account.ledger().get(1).against()).isEqualTo("Admission IP/26-27/0003");
        assertThat(account.ledger().get(1).amountPaise()).isEqualTo(-120_000);
        assertThat(account.nextPlannedAdmission()).isNull();
    }

    @Test
    void doctorsReadTheAccountNurseAndPharmacistDoNotAndAnotherClinicsCourseIs404() {
        course(sitting(S1, 1, "OPD", 0, "PLANNED", null, null, null));
        money(List.of(), List.of());

        assertThat(service.account(new ClinicUser(USER, TENANT, "DOCTOR"), COURSE).canCollect()).isFalse();
        assertThat(service.account(new ClinicUser(USER, TENANT, "VISITING_DOCTOR"), COURSE)).isNotNull();
        for (String role : List.of("NURSE", "PHARMACIST", "COUNSELLOR")) {
            assertThatThrownBy(() -> service.account(new ClinicUser(USER, TENANT, role), COURSE))
                    .isInstanceOf(AccessDeniedException.class);
        }
        when(courses.course(TENANT, COURSE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.account(OWNER, COURSE)).isInstanceOfSatisfying(CourseProblemException.class,
                ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.slug()).isEqualTo("course-not-found");
                });
    }

    // ---- collect ----

    private CoursePaymentInput part(long amount) {
        return new CoursePaymentInput("PART_PAYMENT", amount, "UPI", true, null);
    }

    private void simpleCourse() {
        course(sitting(S1, 1, "OPD", 100_000, "PLANNED", null, null, null));
        money(List.of(), List.of());
        when(payments.nextReceiptNo(TENANT)).thenReturn(5L);
    }

    @Test
    void aPaymentIsRecordedOnceWithTheNextReceiptNumberAndAuditedWithoutNames() {
        simpleCourse();

        Collected result = service.collect(FRONT, COURSE, KEY, part(20_000));

        assertThat(result.created()).isTrue();
        assertThat(result.result().receiptNo()).isEqualTo("RC-5");
        ArgumentCaptor<NewPayment> inserted = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments).insert(eq(TENANT), inserted.capture(), eq(USER));
        assertThat(inserted.getValue().receiptNo()).isEqualTo(5);
        assertThat(inserted.getValue().type()).isEqualTo("PART_PAYMENT");
        assertThat(inserted.getValue().mode()).isEqualTo("UPI");
        assertThat(inserted.getValue().amountPaise()).isEqualTo(20_000);
        assertThat(inserted.getValue().againstLabel()).isEqualTo("Course balance");
        assertThat(inserted.getValue().idempotencyKey()).isEqualTo(KEY);
        assertThat(inserted.getValue().patientId()).isEqualTo(PATIENT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("COURSE_PAYMENT_RECORDED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null),
                after.capture());
        assertThat(after.getValue()).containsEntry("receiptNo", "RC-5").containsEntry("type", "PART_PAYMENT")
                .containsEntry("mode", "UPI").containsEntry("amountPaise", 20_000L);
        assertThat(after.getValue()).doesNotContainKeys("patientName", "mobile", "items");
    }

    @Test
    void paymentsOfOneCourseAreSerialisedOnTheKeyThenTheCourse() {
        simpleCourse();

        service.collect(FRONT, COURSE, KEY, part(20_000));

        InOrder order = inOrder(payments);
        order.verify(payments).lock(TENANT, "key:" + KEY);
        order.verify(payments).lock(TENANT, "course:" + COURSE);
        order.verify(payments).nextReceiptNo(TENANT);
    }

    @Test
    void aVisitPaymentFullBalanceAndPartPaymentMayNotPassTheBalanceButAnAdvanceMay() {
        simpleCourse();

        for (String type : List.of("VISIT_PAYMENT", "FULL_BALANCE", "PART_PAYMENT")) {
            assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                    new CoursePaymentInput(type, 100_100L, "CASH", false, null)))
                    .isInstanceOfSatisfying(CourseProblemException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(400);
                        assertThat(ex.slug()).isEqualTo("amount-over-balance");
                    });
        }
        verify(payments, never()).insert(any(), any(), any());

        service.collect(FRONT, COURSE, KEY, new CoursePaymentInput("ADVANCE", 500_000L, "CASH", false, null));
        verify(payments).insert(eq(TENANT), any(), eq(USER));
    }

    @Test
    void theBalanceItselfMayBePaidInFull() {
        simpleCourse();

        service.collect(FRONT, COURSE, KEY, new CoursePaymentInput("FULL_BALANCE", 100_000L, "CARD", false, null));

        ArgumentCaptor<NewPayment> inserted = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments).insert(eq(TENANT), inserted.capture(), eq(USER));
        assertThat(inserted.getValue().againstLabel()).isEqualTo("Whole course");
    }

    @Test
    void badAmountsModesTypesAndKeysAreRefusedAndNothingIsRecorded() {
        simpleCourse();

        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY, part(150))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY, part(0))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY, part(-100))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY, part(10_000_000_100L)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("PART_PAYMENT", 100L, "CHEQUE", true, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("GIFT", 100L, "CASH", true, null))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("PART_PAYMENT", 100L, "CASH", null, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, "short", part(100)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, null, part(100)))
                .isInstanceOf(InvalidInputException.class);
        verify(payments, never()).insert(any(), any(), any());
        verify(payments, never()).nextReceiptNo(any());
    }

    @Test
    void anIpdDepositNeedsAPlannedAdmissionSittingAndOtherTypesTakeNoSitting() {
        course(sitting(S1, 1, "OPD", 100_000, "PLANNED", null, null, null),
                sitting(S3, 3, "IPD", 300_000, "PLANNED", null, null, null));
        money(List.of(), List.of());
        when(payments.nextReceiptNo(TENANT)).thenReturn(1L);

        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("IPD_DEPOSIT", 100_000L, "UPI", false, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("IPD_DEPOSIT", 100_000L, "UPI", false, S1)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("ADVANCE", 100_000L, "UPI", false, S1))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY,
                new CoursePaymentInput("VISIT_PAYMENT", 100L, "UPI", false, UUID.randomUUID())))
                .isInstanceOf(InvalidInputException.class);
        verify(payments, never()).insert(any(), any(), any());

        service.collect(FRONT, COURSE, KEY, new CoursePaymentInput("IPD_DEPOSIT", 120_000L, "UPI", false, S3));
        ArgumentCaptor<NewPayment> inserted = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments).insert(eq(TENANT), inserted.capture(), eq(USER));
        assertThat(inserted.getValue().againstSittingId()).isEqualTo(S3);
        assertThat(inserted.getValue().againstLabel()).isEqualTo("Admission deposit · Sitting 3");
    }

    @Test
    void aVisitPaymentIsLabelledWithTheSittingItIsFor() {
        simpleCourse();

        service.collect(FRONT, COURSE, KEY, new CoursePaymentInput("VISIT_PAYMENT", 10_000L, "CASH", true, S1));

        ArgumentCaptor<NewPayment> inserted = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments).insert(eq(TENANT), inserted.capture(), eq(USER));
        assertThat(inserted.getValue().againstLabel()).isEqualTo("Sitting 1");
        assertThat(inserted.getValue().useCredit()).isTrue();
    }

    @Test
    void theSameKeyAndBodyReturnsTheFirstPaymentNotASecond() {
        simpleCourse();
        service.collect(FRONT, COURSE, KEY, part(20_000));
        ArgumentCaptor<NewPayment> first = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments).insert(eq(TENANT), first.capture(), eq(USER));
        PaymentRow stored = payment(5, "PART_PAYMENT", "UPI", 20_000, null, "Course balance", T3,
                first.getValue().requestHash());
        when(payments.findByIdempotencyKey(TENANT, KEY)).thenReturn(Optional.of(stored));
        org.mockito.Mockito.clearInvocations(payments, audit);

        Collected again = service.collect(FRONT, COURSE, KEY, part(20_000));

        assertThat(again.created()).isFalse();
        assertThat(again.result().receiptNo()).isEqualTo("RC-5");
        verify(payments, never()).insert(any(), any(), any());
        verify(payments, never()).nextReceiptNo(any());
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theSameKeyWithAnotherBodyOrForAnotherCourseIs409IdempotencyConflict() {
        simpleCourse();
        when(payments.findByIdempotencyKey(TENANT, KEY)).thenReturn(Optional.of(payment(5, "PART_PAYMENT", "UPI",
                20_000, null, "Course balance", T3, "a different hash")));

        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY, part(30_000)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.problemType()).isEqualTo("idempotency-conflict"));
        verify(payments, never()).insert(any(), any(), any());
    }

    @Test
    void onlyOwnerAndFrontDeskCollect() {
        simpleCourse();

        for (String role : List.of("BILLING", "DOCTOR", "VISITING_DOCTOR", "NURSE", "PHARMACIST", "COUNSELLOR")) {
            assertThatThrownBy(() -> service.collect(new ClinicUser(USER, TENANT, role), COURSE, KEY, part(100)))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verify(payments, never()).insert(any(), any(), any());
        assertThat(service.collect(OWNER, COURSE, KEY, part(100)).created()).isTrue();
    }

    @Test
    void aPaymentOnAnotherClinicsCourseIs404() {
        when(courses.course(TENANT, COURSE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collect(FRONT, COURSE, KEY, part(100)))
                .isInstanceOfSatisfying(CourseProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("course-not-found"));
        verify(payments, never()).insert(any(), any(), any());
    }

    // ---- receipt ----

    @Test
    void aReceiptHasTheLetterheadPatientAndTheBalanceAfterThatPayment() {
        course(sitting(S1, 1, "OPD", 100_000, "PLANNED", null, null, null));
        PaymentRow first = payment(4, "ADVANCE", "UPI", 30_000, null, "Course advance", T1, "h1");
        PaymentRow second = payment(5, "PART_PAYMENT", "CASH", 20_000, null, "Course balance", T2, "h2");
        money(List.of(first, second), List.of());
        when(payments.findByReceipt(TENANT, COURSE, 4)).thenReturn(Optional.of(first));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Asha Rao", 30, "F",
                "9000000001", false, "UHID-0001")));
        when(letterhead.find(TENANT)).thenReturn(Optional.of(new Letterhead("Sahaj clinic", "1 Main Road, Pune",
                "020 1234 5678")));
        when(actors.displayName(TENANT, USER)).thenReturn(Optional.of("Meena"));

        CourseReceipt receipt = service.receipt(BILLING, COURSE, 4);

        assertThat(receipt.receiptNo()).isEqualTo("RC-4");
        assertThat(receipt.clinicName()).isEqualTo("Sahaj clinic");
        assertThat(receipt.clinicAddress()).isEqualTo("1 Main Road, Pune");
        assertThat(receipt.patientName()).isEqualTo("Asha Rao");
        assertThat(receipt.uhid()).isEqualTo("UHID-0001");
        assertThat(receipt.courseNo()).isEqualTo("TC-0412");
        assertThat(receipt.type()).isEqualTo("ADVANCE");
        assertThat(receipt.amountPaise()).isEqualTo(30_000);
        assertThat(receipt.collectedByName()).isEqualTo("Meena");
        assertThat(receipt.balanceAfterPaise()).isEqualTo(70_000);
        assertThat(receipt.toString()).doesNotContain("Asha");
    }

    @Test
    void receiptsAreForOwnerFrontDeskAndBillingOnlyAndAnUnknownReceiptIs404() {
        course(sitting(S1, 1, "OPD", 100_000, "PLANNED", null, null, null));
        money(List.of(), List.of());

        for (String role : List.of("DOCTOR", "VISITING_DOCTOR", "NURSE", "PHARMACIST", "COUNSELLOR")) {
            assertThatThrownBy(() -> service.receipt(new ClinicUser(USER, TENANT, role), COURSE, 1))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.receipt(OWNER, COURSE, 99)).isInstanceOfSatisfying(
                CourseProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.slug()).isEqualTo("receipt-not-found");
                });
        verify(payments, never()).insert(any(), any(), any());
    }

    // ---- 0.4.0 (SAH-75-10, ADR-026 D4): renumbered sittings, missed sittings, one charge ----

    /** A sitting as the port reports it after a removal: {@code displayNo} differs from the stable {@code no}. */
    private static SittingFacts shown(UUID id, int no, int displayNo, String kind, long charge, String status,
                                      UUID admission, String ip, Instant doneAt) {
        return new SittingFacts(id, no, kind, null, charge, null, status, null, admission, ip, admission != null, doneAt,
                displayNo);
    }

    @Test
    void theNextAdmissionBlockCarriesTheDisplayNumberWhileTheStableNumberStaysTheKey() {
        // sitting 2 was removed, so the planned admission that is stored as 3 is shown as 2
        course(shown(S1, 1, 1, "OPD", 100_000, "PLANNED", null, null, null),
                shown(S3, 3, 2, "DAY_CARE", 300_000, "PLANNED", null, null, null));
        money(List.of(), List.of());

        CourseAccount account = service.account(OWNER, COURSE);

        assertThat(account.nextPlannedAdmission().sittingNo()).isEqualTo(3);
        assertThat(account.nextPlannedAdmission().displayNo()).isEqualTo(2);
        assertThat(account.sittingCount()).isEqualTo(2);
    }

    @Test
    void theAdvanceUsedLineNamesTheSittingByItsDisplayNumber() {
        course(shown(S1, 1, 1, "OPD", 100_000, "DONE", null, null, T1),
                shown(S3, 4, 2, "OPD", 100_000, "DONE", null, null, T2));
        money(List.of(payment(1, "ADVANCE", "UPI", 150_000, null, "Course advance", T1, "h")), List.of());

        CourseAccount account = service.account(OWNER, COURSE);

        assertThat(account.ledger()).filteredOn(l -> "CREDIT_USED".equals(l.kind())).extracting(l -> l.against())
                .containsExactly("Sitting 1", "Sitting 2");
    }

    @Test
    void aMissedSittingStaysInTheEstimateAndIsNeitherDoneNorADueTodayCharge() {
        course(shown(S1, 1, 1, "OPD", 100_000, "DONE", null, null, T1),
                shown(S2, 2, 2, "OPD", 50_000, "MISSED", null, null, null),
                shown(S3, 3, 3, "OPD", 50_000, "TODAY", null, null, null));
        money(List.of(), List.of());

        CourseAccount account = service.account(OWNER, COURSE);

        assertThat(account.estimatePaise()).isEqualTo(200_000);
        assertThat(account.doneCount()).isEqualTo(1);
        assertThat(account.todayChargePaise()).isEqualTo(50_000);
        assertThat(account.dueTodayPaise()).isEqualTo(150_000);
    }

    @Test
    void theTodayChargeIsTheSumOfEveryTodaySittingNotSettledInIpd() {
        course(shown(S1, 1, 1, "OPD", 100_000, "TODAY", null, null, null),
                shown(S2, 2, 2, "OPD", 50_000, "TODAY", null, null, null),
                shown(S3, 3, 3, "DAY_CARE", 300_000, "TODAY", ADMISSION, "IP/26-27/0003", null));
        money(List.of(), List.of());

        assertThat(service.account(OWNER, COURSE).todayChargePaise()).isEqualTo(150_000);
    }

    @Test
    void aSittingDoneByHandAndThenSignedIsOneDoneSittingAndItsChargeIsCountedOnce() {
        // both paths end in the same DONE state on the same sitting, so the port reports it once
        course(shown(S1, 1, 1, "OPD", 100_000, "DONE", null, null, T1));
        money(List.of(payment(1, "ADVANCE", "UPI", 100_000, null, "Course advance", T1, "h")), List.of());

        CourseAccount account = service.account(OWNER, COURSE);

        assertThat(account.doneCount()).isEqualTo(1);
        assertThat(account.creditPaise()).isZero();
        assertThat(account.dueFromEarlierPaise()).isZero();
        assertThat(account.balancePaise()).isZero();
    }

    @Test
    void aPaymentIsLabelledWithTheDisplayNumberOfTheSittingItIsFor() {
        course(shown(S1, 1, 1, "OPD", 100_000, "PLANNED", null, null, null),
                shown(S3, 3, 2, "IPD", 300_000, "PLANNED", null, null, null));
        money(List.of(), List.of());
        when(payments.nextReceiptNo(TENANT)).thenReturn(1L);

        service.collect(FRONT, COURSE, KEY, new CoursePaymentInput("IPD_DEPOSIT", 120_000L, "UPI", false, S3));
        service.collect(FRONT, COURSE, "pay-key-0002", new CoursePaymentInput("VISIT_PAYMENT", 10_000L, "UPI", false, S3));

        ArgumentCaptor<NewPayment> inserted = ArgumentCaptor.forClass(NewPayment.class);
        verify(payments, org.mockito.Mockito.times(2)).insert(eq(TENANT), inserted.capture(), eq(USER));
        assertThat(inserted.getAllValues().get(0).againstLabel()).isEqualTo("Admission deposit · Sitting 2");
        assertThat(inserted.getAllValues().get(1).againstLabel()).isEqualTo("Sitting 2");
        assertThat(inserted.getAllValues().get(0).againstSittingId()).isEqualTo(S3);
    }

    @Test
    void theOldTwelveArgumentFactsReadTheSittingNumberAsTheDisplayNumber() {
        SittingFacts old = new SittingFacts(S1, 4, "OPD", null, 0, null, "PLANNED", null, null, null, false, null);

        assertThat(old.displayNo()).isEqualTo(4);
    }
}
