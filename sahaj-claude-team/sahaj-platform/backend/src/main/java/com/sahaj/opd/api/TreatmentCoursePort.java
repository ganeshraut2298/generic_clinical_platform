package com.sahaj.opd.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-58 (ADR-022 D1, D12): how {@code billing} (the course account) and {@code ipd} (an admission for a course
 * sitting) read and link a treatment course without {@code opd} depending on either (both already depend on this
 * package, so the reverse would be a module cycle). Declared here, implemented by {@code opd}. Every method runs in
 * the caller's transaction with the clinic already bound; another clinic's id is simply absent.
 *
 * <p>The sitting items are clinical plan text and are not part of any fact here. The course name and sitting notes are
 * not patient data but are never logged; {@code toString} of the records shows ids only.
 */
public interface TreatmentCoursePort {

    /**
     * One sitting as billing needs it. {@code status} is PLANNED, TODAY or DONE (computed by opd). {@code settledInIpd}
     * is true for a day-care or IPD sitting linked to an admission: it is billed on the IPD bill (ADR-022 H1).
     * {@code ipNumber} is the linked admission's IP number, {@code doneAt} when the sitting finished (null if not DONE
     * or not known).
     */
    record SittingFacts(UUID sittingId, int sittingNo, String encounterKind, LocalDate plannedDate, long chargePaise,
                        String note, String status, UUID opdVisitId, UUID admissionId, String ipNumber,
                        boolean settledInIpd, Instant doneAt) {

        @Override
        public String toString() {
            return "SittingFacts[sittingId=" + sittingId + ", sittingNo=" + sittingNo + "]";
        }
    }

    /** {@code kind} DENTAL, ANC, CHEMO, SURGERY or REHAB; {@code unit} SITTING, VISIT, CYCLE, STEP or SESSION. */
    record CourseFacts(UUID courseId, String courseNo, UUID patientId, String kind, String unit, String name,
                       UUID doctorMembershipId, List<SittingFacts> sittings) {

        @Override
        public String toString() {
            return "CourseFacts[courseId=" + courseId + ", courseNo=" + courseNo + "]";
        }
    }

    /** A planned, unlinked day-care or IPD sitting of an active course of the patient, locked for the caller. */
    record AdmittableSitting(UUID courseId, String courseNo, UUID sittingId, int sittingNo) {
    }

    /** A planned, unlinked day-care or IPD sitting waiting for admission (ADR-025 D10, H16). Ids and numbers only. */
    record RequestedSitting(UUID courseId, String courseNo, UUID sittingId, int sittingNo, UUID patientId,
                            String encounterKind, LocalDate plannedDate, UUID doctorMembershipId) {
    }

    /** The planned, unlinked sittings with admissionRequested of every ACTIVE course, newest first, at most 200. */
    List<RequestedSitting> openAdmissionSittings(UUID tenantId);

    /** The course with its sittings, statuses computed now; absent for another clinic's course. */
    Optional<CourseFacts> course(UUID tenantId, UUID courseId);

    /** The display number ("TC-0412") of a course. */
    Optional<String> courseNo(UUID tenantId, UUID courseId);

    /**
     * The sitting when it is a planned, unlinked DAY_CARE or IPD sitting of an ACTIVE course of {@code patientId}; the
     * sitting row is locked until the end of the caller's transaction. Absent otherwise (the caller answers 409
     * course-sitting-not-available and writes nothing).
     */
    Optional<AdmittableSitting> admittableSitting(UUID tenantId, UUID patientId, UUID sittingId);

    /** Links the admission to the sitting (the sitting becomes TODAY); audits COURSE_SITTING_ADMISSION_LINKED. */
    void linkAdmission(UUID tenantId, UUID actorUserId, UUID sittingId, UUID admissionId);
}
