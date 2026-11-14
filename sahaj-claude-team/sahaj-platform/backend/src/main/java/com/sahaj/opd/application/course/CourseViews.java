package com.sahaj.opd.application.course;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of the treatment course endpoints (docs/api/opd.yaml 0.4.0: TreatmentCourseSummary, TreatmentCourseView,
 * CourseSitting, CourseAddOption). {@code items} and {@code note} are clinical plan text: shown on screen, never logged.
 */
public final class CourseViews {

    private CourseViews() {
    }

    public record AddOptionView(String key, String label, String encounterKind) {
    }

    public record SittingView(UUID id, int sittingNo, String encounterKind, String encounterLabel,
                              String encounterRef, LocalDate plannedDate, String status, List<String> items,
                              long chargePaise, String note, boolean admissionRequested, boolean settledInIpd,
                              Long ipdBillTotalPaise, UUID opdVisitId, UUID admissionId) {

        @Override
        public String toString() {
            return "SittingView[id=" + id + ", sittingNo=" + sittingNo + "]";
        }
    }

    public record SummaryView(UUID id, String courseNo, String kind, String unit, String name, LocalDate startedOn,
                              String status, int sittingCount, int doneCount) {
    }

    public record CourseView(UUID id, String courseNo, String kind, String unit, String name, LocalDate startedOn,
                             String status, int sittingCount, int doneCount, UUID patientId,
                             UUID doctorMembershipId, String doctorName, String note, long revision,
                             List<SittingView> sittings, List<AddOptionView> addOptions, boolean canPlan,
                             boolean canAdmit, String accountNote) {

        @Override
        public String toString() {
            return "CourseView[id=" + id + ", courseNo=" + courseNo + "]";
        }
    }

    /** The result of a create or an add: the course and whether this call made the change (false = the same key came before). */
    public record Changed(boolean created, CourseView course) {
    }
}
