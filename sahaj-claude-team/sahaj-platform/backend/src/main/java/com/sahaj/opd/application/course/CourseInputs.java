package com.sahaj.opd.application.course;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the treatment course (docs/api/opd.yaml 0.7.0, SAH-58, SAH-75). Each body is closed: a field the
 * contract does not list is refused with 400 validation by the strict JSON module, except {@code tenantId} and
 * {@code clinicId} on the create body, which are accepted and ignored (the clinic is always the signed-in user's).
 * Fields are nullable so that a missing field is answered by the service with a field error.
 */
public final class CourseInputs {

    private CourseInputs() {
    }

    /** One planned sitting of a new course: items and note are clinical plan text, never logged. */
    public record SittingPlanInput(String addOptionKey, LocalDate plannedDate, List<String> items, Long chargePaise,
                                   String note, String regimenKey) {

        /** The 0.6.0 shape: no regimen. */
        public SittingPlanInput(String addOptionKey, LocalDate plannedDate, List<String> items, Long chargePaise,
                                String note) {
            this(addOptionKey, plannedDate, items, chargePaise, note, null);
        }
    }

    /** POST /emr/courses. */
    public record CreateInput(UUID fromRecordId, String kind, String name, String note,
                              List<SittingPlanInput> sittings, Boolean fromPlan) {

        /** The 0.4.0 shape: no fromPlan. */
        public CreateInput(UUID fromRecordId, String kind, String name, String note, List<SittingPlanInput> sittings) {
            this(fromRecordId, kind, name, note, sittings, null);
        }
    }

    /** POST /emr/courses/{courseId}/sittings (the revision is required by the contract; adds are never refused for it). */
    public record AddSittingInput(Long revision, String addOptionKey, LocalDate plannedDate, String regimenKey) {

        /** The 0.6.0 shape: no regimen. */
        public AddSittingInput(Long revision, String addOptionKey, LocalDate plannedDate) {
            this(revision, addOptionKey, plannedDate, null);
        }
    }

    /** PUT /emr/courses/{courseId}/sittings/{sittingId}. */
    public record ChangeSittingInput(Long revision, String encounterKind, LocalDate plannedDate, List<String> items,
                                     Long chargePaise, String note, String regimenKey) {

        /** The 0.6.0 shape: no regimen (the stored one is kept). */
        public ChangeSittingInput(Long revision, String encounterKind, LocalDate plannedDate, List<String> items,
                                  Long chargePaise, String note) {
            this(revision, encounterKind, plannedDate, items, chargePaise, note, null);
        }
    }

    /** POST /emr/courses/{courseId}/sittings/{sittingId}/link-visit. */
    public record LinkVisitInput(Long revision, UUID visitId) {
    }

    /** POST .../missed and .../remove (0.7.0): the course revision the screen holds. */
    public record RevisionInput(Long revision) {
    }

    /** POST .../mark-done (0.7.0): the visit of the EMR hosting the panel, linked first when the sitting has no link. */
    public record MarkDoneInput(Long revision, UUID visitId) {
    }

    /** POST .../rebook and .../reschedule (0.7.0): today or a later date, Asia/Kolkata. */
    public record DateInput(Long revision, LocalDate plannedDate) {
    }

    /** POST .../admission-request (0.7.0): DAY_CARE (default) or IPD for an OPD sitting; ignored for the others. */
    public record AdmissionRequestInput(Long revision, String encounterKind) {
    }
}
