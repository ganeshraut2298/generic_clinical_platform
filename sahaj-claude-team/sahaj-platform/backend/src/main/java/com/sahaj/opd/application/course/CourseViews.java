package com.sahaj.opd.application.course;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of the treatment course endpoints (docs/api/opd.yaml 0.7.0: TreatmentCourseSummary, TreatmentCourseView,
 * CourseSitting, CourseAddOption). {@code items} and {@code note} are clinical plan text: shown on screen, never logged.
 */
public final class CourseViews {

    private CourseViews() {
    }

    public record AddOptionView(String key, String label, String encounterKind) {
    }

    /**
     * 0.7.0 (SAH-75-10, ADR-026 D4): {@code displayNo} is the number shown, {@code doneSource} MARK_DONE or SIGN (null
     * when not done by either), {@code regimenKey} the protocol of a chemotherapy cycle, {@code allowedActions} what the
     * caller may do now (MARK_DONE, MISSED, REBOOK, RESCHEDULE, REMOVE, REQUEST_ADMISSION, ADMIT).
     */
    public record SittingView(UUID id, int sittingNo, String encounterKind, String encounterLabel,
                              String encounterRef, LocalDate plannedDate, String status, List<String> items,
                              long chargePaise, String note, boolean admissionRequested, boolean settledInIpd,
                              Long ipdBillTotalPaise, UUID opdVisitId, UUID admissionId, int displayNo,
                              String doneSource, String regimenKey, List<String> allowedActions) {

        /** The 0.6.0 shape: the display number is the sitting number, no source, regimen or actions. */
        public SittingView(UUID id, int sittingNo, String encounterKind, String encounterLabel, String encounterRef,
                           LocalDate plannedDate, String status, List<String> items, long chargePaise, String note,
                           boolean admissionRequested, boolean settledInIpd, Long ipdBillTotalPaise, UUID opdVisitId,
                           UUID admissionId) {
            this(id, sittingNo, encounterKind, encounterLabel, encounterRef, plannedDate, status, items, chargePaise,
                    note, admissionRequested, settledInIpd, ipdBillTotalPaise, opdVisitId, admissionId, sittingNo,
                    null, null, List.of());
        }

        @Override
        public String toString() {
            return "SittingView[id=" + id + ", sittingNo=" + sittingNo + "]";
        }
    }

    public record SummaryView(UUID id, String courseNo, String kind, String unit, String name, LocalDate startedOn,
                              String status, int sittingCount, int doneCount) {
    }

    /** 0.7.0: {@code estimateSigned} drives the sub line "estimate signed by patient" (SAH-75-10, G15-4). */
    public record CourseView(UUID id, String courseNo, String kind, String unit, String name, LocalDate startedOn,
                             String status, int sittingCount, int doneCount, UUID patientId,
                             UUID doctorMembershipId, String doctorName, String note, long revision,
                             List<SittingView> sittings, List<AddOptionView> addOptions, boolean canPlan,
                             boolean canAdmit, String accountNote, boolean estimateSigned) {

        /** The 0.6.0 shape: the estimate is not signed. */
        public CourseView(UUID id, String courseNo, String kind, String unit, String name, LocalDate startedOn,
                          String status, int sittingCount, int doneCount, UUID patientId, UUID doctorMembershipId,
                          String doctorName, String note, long revision, List<SittingView> sittings,
                          List<AddOptionView> addOptions, boolean canPlan, boolean canAdmit, String accountNote) {
            this(id, courseNo, kind, unit, name, startedOn, status, sittingCount, doneCount, patientId,
                    doctorMembershipId, doctorName, note, revision, sittings, addOptions, canPlan, canAdmit,
                    accountNote, false);
        }

        @Override
        public String toString() {
            return "CourseView[id=" + id + ", courseNo=" + courseNo + "]";
        }
    }

    /** The result of a create or an add: the course and whether this call made the change (false = the same key came before). */
    public record Changed(boolean created, CourseView course) {
    }
}
