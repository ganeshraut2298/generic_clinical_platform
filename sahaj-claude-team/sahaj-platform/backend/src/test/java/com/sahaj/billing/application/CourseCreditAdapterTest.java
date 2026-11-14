package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.billing.api.CourseCreditPort.Transfer;
import com.sahaj.billing.application.CourseAccountViews.CourseAccount;
import com.sahaj.billing.application.CoursePaymentRepository.TransferRow;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.CourseFacts;
import com.sahaj.platform.api.AuditLog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-58 (DEC-042 H1): the credit a course carries into an IPD admission's deposits. */
class CourseCreditAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID SITTING = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();

    private final CoursePaymentRepository payments = mock(CoursePaymentRepository.class);
    private final TreatmentCoursePort courses = mock(TreatmentCoursePort.class);
    private final CourseAccountService accounts = mock(CourseAccountService.class);
    private final AuditLog audit = mock(AuditLog.class);
    private CourseCreditAdapter adapter;
    private final CourseFacts facts = new CourseFacts(COURSE, "TC-0001", UUID.randomUUID(), "DENTAL", "SITTING", "x",
            UUID.randomUUID(), List.of());

    @BeforeEach
    void setUp() {
        adapter = new CourseCreditAdapter(payments, courses, accounts, audit);
        when(courses.course(TENANT, COURSE)).thenReturn(Optional.of(facts));
        when(payments.transfersOf(TENANT, COURSE)).thenReturn(List.of());
        when(payments.insertTransfer(eq(TENANT), eq(COURSE), eq(SITTING), eq(ADMISSION), anyLong(), eq(USER)))
                .thenReturn(TRANSFER);
    }

    private void situation(long earmarked, long credit) {
        when(payments.earmarkedFor(TENANT, COURSE, SITTING)).thenReturn(earmarked);
        CourseAccount account = new CourseAccount(COURSE, "TC-0001", "SITTING", 0, 0, credit, 0, 0, 0, 0, 0, 0, 0,
                null, null, false, List.of());
        when(accounts.build(TENANT, facts, false)).thenReturn(account);
    }

    @Test
    void theEarmarkedDepositIsCarriedAndAuditedWithIdsAndMoneyOnly() {
        situation(120_000, 500_000);

        Optional<Transfer> result = adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION);

        assertThat(result).hasValueSatisfying(t -> {
            assertThat(t.transferId()).isEqualTo(TRANSFER);
            assertThat(t.amountPaise()).isEqualTo(120_000);
        });
        verify(payments).lock(TENANT, "course:" + COURSE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("COURSE_CREDIT_CARRIED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("amountPaise", 120_000L).containsEntry("admissionId", ADMISSION)
                .containsEntry("sittingId", SITTING);
    }

    @Test
    void nothingMoreThanTheCoursesCreditIsCarried() {
        situation(120_000, 80_000);

        assertThat(adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION))
                .hasValueSatisfying(t -> assertThat(t.amountPaise()).isEqualTo(80_000));
    }

    @Test
    void atMostTenLakhRupeesIsCarriedBecauseOfTheIpdDepositLimit() {
        situation(150_000_000L, 900_000_000L);

        assertThat(adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION))
                .hasValueSatisfying(t -> assertThat(t.amountPaise()).isEqualTo(100_000_000L));
        assertThat(CourseCreditAdapter.amount(150_000_000L, 900_000_000L)).isEqualTo(100_000_000L);
    }

    @Test
    void theAmountIsRoundedDownToWholeRupees() {
        assertThat(CourseCreditAdapter.amount(12_345, 99_999)).isEqualTo(12_300);
        assertThat(CourseCreditAdapter.amount(99, 10_000)).isZero();
    }

    @Test
    void nothingIsCarriedWhenNoDepositWasEarmarkedOrTheCourseHoldsNoCredit() {
        situation(0, 500_000);
        assertThat(adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION)).isEmpty();

        situation(120_000, 0);
        assertThat(adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION)).isEmpty();
        verify(payments, never()).insertTransfer(any(), any(), any(), any(), anyLong(), any());
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aSittingOrAdmissionThatAlreadyReceivedCreditGetsNoSecondTransfer() {
        situation(120_000, 500_000);
        when(payments.transfersOf(TENANT, COURSE)).thenReturn(List.of(new TransferRow(UUID.randomUUID(), COURSE,
                SITTING, UUID.randomUUID(), 50_000, USER, Instant.now())));

        assertThat(adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION)).isEmpty();
        verify(payments, never()).insertTransfer(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aCourseOfAnotherClinicCarriesNothing() {
        situation(120_000, 500_000);
        when(courses.course(TENANT, COURSE)).thenReturn(Optional.empty());

        assertThat(adapter.carryToAdmission(TENANT, USER, COURSE, SITTING, ADMISSION)).isEmpty();
    }
}
