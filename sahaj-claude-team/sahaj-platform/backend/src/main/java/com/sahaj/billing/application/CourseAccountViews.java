package com.sahaj.billing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of the treatment course account endpoints (docs/api/billing.yaml 0.2.0: CourseAccount, CourseLedgerLine,
 * CoursePaymentResult, CourseReceipt). All money is whole paise. The receipt holds the patient's name: screen and print
 * only, never logged. The other records hold ids and money only.
 */
public final class CourseAccountViews {

    private CourseAccountViews() {
    }

    /** {@code kind} PAYMENT, CREDIT_USED or CARRIED_TO_IPD; the amount is negative for the last two. */
    public record LedgerLine(String kind, LocalDate date, String type, String mode, String against, long amountPaise,
                             String receiptNo) {
    }

    public record NextPlannedAdmission(UUID sittingId, int sittingNo, long chargePaise, String note) {
    }

    /** Both are null when there is nothing to suggest (balance 0; no planned admission). */
    public record Suggestions(Long partPaymentPaise, Long ipdDepositPaise) {
    }

    public record CourseAccount(UUID courseId, String courseNo, String unit, long estimatePaise, long paidPaise,
                                long creditPaise, long dueFromEarlierPaise, long todayChargePaise, long dueTodayPaise,
                                long balancePaise, int sittingCount, int doneCount, int receiptCount,
                                NextPlannedAdmission nextPlannedAdmission, Suggestions suggestions, boolean canCollect,
                                List<LedgerLine> ledger) {
    }

    public record PaymentResult(String receiptNo, CourseAccount account) {
    }

    /** The result of a collect: {@code created} false means the same Idempotency-Key came before (HTTP 200). */
    public record Collected(boolean created, PaymentResult result) {
    }

    public record CourseReceipt(String receiptNo, String clinicName, String clinicAddress, String clinicPhone,
                                Instant date, String patientName, String uhid, String courseNo, String courseName,
                                String type, String mode, String against, long amountPaise, String collectedByName,
                                long balanceAfterPaise) {

        @Override
        public String toString() {
            return "CourseReceipt[receiptNo=" + receiptNo + "]";
        }
    }

    /** The body of POST /courses/{courseId}/payments (fields nullable so a missing one is a field error). */
    public record CoursePaymentInput(String type, Long amountPaise, String mode, Boolean useCredit,
                                     UUID againstSittingId) {
    }
}
