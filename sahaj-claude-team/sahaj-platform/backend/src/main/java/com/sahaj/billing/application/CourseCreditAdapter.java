package com.sahaj.billing.application;

import com.sahaj.billing.api.CourseCreditPort;
import com.sahaj.billing.application.CourseAccountViews.CourseAccount;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.CourseFacts;
import com.sahaj.platform.api.AuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-58 (ADR-022 D12; DEC-042 H1): implements {@link CourseCreditPort} so {@code ipd} can carry the credit a treatment
 * course holds for an admission into the admission's deposits. The amount is the IPD deposit payments earmarked to that
 * sitting, but never more than the course's credit now and never more than ₹10,00,000 (the IPD deposit limit, which the
 * stored transfer also enforces). One transfer per admission and per sitting; it is append-only. Payments of the course
 * are serialised on the same advisory lock as a collect, so a payment cannot slip in between the credit read and the
 * transfer. Audit: ids and money only.
 */
@Component
class CourseCreditAdapter implements CourseCreditPort {

    /** ₹10,00,000, the limit of one IPD deposit (ck_deposit_amount). */
    static final long MAX_CARRY_PAISE = 100_000_000L;

    private final CoursePaymentRepository payments;
    private final TreatmentCoursePort courses;
    private final CourseAccountService accounts;
    private final AuditLog auditLog;

    CourseCreditAdapter(CoursePaymentRepository payments, TreatmentCoursePort courses, CourseAccountService accounts,
                        AuditLog auditLog) {
        this.payments = payments;
        this.courses = courses;
        this.accounts = accounts;
        this.auditLog = auditLog;
    }

    @Override
    public Optional<Transfer> carryToAdmission(UUID tenantId, UUID actorUserId, UUID courseId, UUID sittingId,
                                               UUID admissionId) {
        payments.lock(tenantId, "course:" + courseId);
        long earmarked = payments.earmarkedFor(tenantId, courseId, sittingId);
        if (earmarked <= 0) {
            return Optional.empty();
        }
        boolean carriedBefore = payments.transfersOf(tenantId, courseId).stream()
                .anyMatch(t -> sittingId.equals(t.sittingId()) || admissionId.equals(t.admissionId()));
        if (carriedBefore) {
            return Optional.empty();
        }
        Optional<CourseFacts> course = courses.course(tenantId, courseId);
        if (course.isEmpty()) {
            return Optional.empty();
        }
        CourseAccount account = accounts.build(tenantId, course.get(), false);
        long amount = amount(earmarked, account.creditPaise());
        if (amount <= 0) {
            return Optional.empty();
        }
        UUID transferId = payments.insertTransfer(tenantId, courseId, sittingId, admissionId, amount, actorUserId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sittingId", sittingId);
        after.put("admissionId", admissionId);
        after.put("amountPaise", amount);
        auditLog.record("COURSE_CREDIT_CARRIED", "TREATMENT_COURSE", courseId, null, after);
        return Optional.of(new Transfer(transferId, amount));
    }

    /** The earmarked deposits, capped at the course's credit and at the IPD deposit limit, in whole rupees. */
    static long amount(long earmarkedPaise, long creditPaise) {
        long capped = Math.min(Math.min(earmarkedPaise, creditPaise), MAX_CARRY_PAISE);
        return capped - capped % CourseAccountMath.RUPEE;
    }

}
