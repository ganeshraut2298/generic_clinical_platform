package com.sahaj.billing.application;

import com.sahaj.billing.application.PaymentRepository.PaymentTotals;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code billing.course_payment}, {@code billing.course_credit_transfer} and {@code billing.receipt_counter}
 * (SAH-58; ADR-022 D12). Both tables are append-only. Money is whole paise. Needs {@code app.tenant_id} bound; every
 * statement also names the tenant.
 */
public interface CoursePaymentRepository {

    record PaymentRow(UUID id, UUID courseId, UUID patientId, long receiptNo, String type, String mode,
                      long amountPaise, UUID againstSittingId, String againstLabel, boolean useCredit,
                      String requestHash, UUID recordedBy, Instant recordedAt) {
    }

    record NewPayment(UUID courseId, UUID patientId, long receiptNo, String type, String mode, long amountPaise,
                      UUID againstSittingId, String againstLabel, boolean useCredit, String idempotencyKey,
                      String requestHash) {
    }

    /** One payment as the day closing and the revenue report need it. */
    record Point(String mode, long amountPaise, Instant recordedAt) {
    }

    record TransferRow(UUID id, UUID courseId, UUID sittingId, UUID admissionId, long amountPaise, UUID transferredBy,
                       Instant transferredAt) {
    }

    /** Serialises the payments of one course (and two requests with one key): a transaction-scoped advisory lock. */
    void lock(UUID tenantId, String scope);

    /** The next receipt number of the clinic by upsert-increment under the counter row lock (gaps allowed). */
    long nextReceiptNo(UUID tenantId);

    UUID insert(UUID tenantId, NewPayment payment, UUID actorUserId);

    Optional<PaymentRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** The course's payments, by receipt number. */
    List<PaymentRow> findByCourse(UUID tenantId, UUID courseId);

    Optional<PaymentRow> findByReceipt(UUID tenantId, UUID courseId, long receiptNo);

    /** The IPD deposit payments earmarked to this sitting, in paise. */
    long earmarkedFor(UUID tenantId, UUID courseId, UUID sittingId);

    /** Paise taken with this mode at or after {@code since}, for the whole clinic (day closing, H3). */
    long sumByModeSince(UUID tenantId, String mode, Instant since);

    /** Net paise and number of payments in {@code [from, to)} (revenue previous period, H3). */
    PaymentTotals totalsBetween(UUID tenantId, Instant from, Instant to);

    /** Every course payment in {@code [from, to)}, oldest first (revenue days and modes, H3). */
    List<Point> findBetween(UUID tenantId, Instant from, Instant to);

    UUID insertTransfer(UUID tenantId, UUID courseId, UUID sittingId, UUID admissionId, long amountPaise,
                        UUID actorUserId);

    /** The credit carried to IPD admissions from this course, oldest first. */
    List<TransferRow> transfersOf(UUID tenantId, UUID courseId);
}
