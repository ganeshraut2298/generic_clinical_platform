package com.sahaj.ipd.application;

import com.sahaj.billing.api.CourseCreditPort;
import com.sahaj.billing.api.CourseCreditPort.Transfer;
import com.sahaj.ipd.application.AdmissionRepository.CourseLink;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.AdmittableSitting;
import com.sahaj.platform.api.ConflictException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-58 (ADR-022 D12; DEC-042 H1): an admission made for a treatment course sitting ("Admit now"). In the admission's
 * own transaction: (1) {@link #prepare} checks that the sitting is a planned, unlinked day-care or IPD sitting of this
 * patient's active course and locks it (else 409 course-sitting-not-available, nothing written); (2) {@link #complete}
 * links the admission to the sitting through {@link TreatmentCoursePort}, carries the course credit earmarked for that
 * sitting through {@link CourseCreditPort} (capped at the course's credit and at ₹10,00,000) and records it as a deposit
 * with source COURSE and no mode. {@code ipd} depends on {@code billing.api}; {@code billing} never depends on
 * {@code ipd}. Nothing here logs; the audit is written by the admission.
 */
@Component
class AdmissionCourseLink {

    /** The sitting an admission is being made for. */
    record Prepared(UUID courseId, String courseNo, UUID sittingId) {
    }

    /** What was carried: nothing (0) when the course held no credit for the sitting. */
    record Carried(long amountPaise, UUID depositId) {
    }

    private final TreatmentCoursePort courses;
    private final CourseCreditPort credit;
    private final AdmissionRepository admissions;

    /**
     * The two ports are resolved lazily: {@code opd} (course plan) reaches {@code ipd} through the EMR encounter source
     * and {@code billing} (course credit) reaches {@code opd}, so eager injection would close a bean cycle with the
     * admission service.
     */
    AdmissionCourseLink(@org.springframework.context.annotation.Lazy TreatmentCoursePort courses,
                        @org.springframework.context.annotation.Lazy CourseCreditPort credit,
                        AdmissionRepository admissions) {
        this.courses = courses;
        this.credit = credit;
        this.admissions = admissions;
    }

    static ConflictException notAvailable() {
        return new ConflictException("course-sitting-not-available", "Course entry not available",
                "This entry of the treatment course cannot be admitted now.");
    }

    Prepared prepare(UUID tenantId, UUID patientId, UUID sittingId) {
        AdmittableSitting sitting = courses.admittableSitting(tenantId, patientId, sittingId)
                .orElseThrow(AdmissionCourseLink::notAvailable);
        return new Prepared(sitting.courseId(), sitting.courseNo(), sitting.sittingId());
    }

    Carried complete(UUID tenantId, UUID actorUserId, Prepared prepared, UUID admissionId) {
        courses.linkAdmission(tenantId, actorUserId, prepared.sittingId(), admissionId);
        Optional<Transfer> transfer = credit.carryToAdmission(tenantId, actorUserId, prepared.courseId(),
                prepared.sittingId(), admissionId);
        if (transfer.isEmpty()) {
            return new Carried(0, null);
        }
        UUID depositId = admissions.insertCourseDeposit(tenantId, admissionId, transfer.get().amountPaise(),
                transfer.get().transferId(), actorUserId);
        return new Carried(transfer.get().amountPaise(), depositId);
    }

    /** The course number ("TC-0412") of the course an admission was made for, if any. */
    Optional<String> courseNo(UUID tenantId, UUID admissionId) {
        return admissions.findCourseLink(tenantId, admissionId).map(CourseLink::courseId)
                .flatMap(courseId -> courses.courseNo(tenantId, courseId));
    }
}
