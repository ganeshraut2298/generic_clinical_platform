package com.sahaj.opd.application.course;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the treatment course (docs/api/opd.yaml 0.4.0, SAH-58). Each body is closed: a field the
 * contract does not list is refused with 400 validation by the strict JSON module, except {@code tenantId} and
 * {@code clinicId} on the create body, which are accepted and ignored (the clinic is always the signed-in user's).
 * Fields are nullable so that a missing field is answered by the service with a field error.
 */
public final class CourseInputs {

    private CourseInputs() {
    }

    /** One planned sitting of a new course: items and note are clinical plan text, never logged. */
    public record SittingPlanInput(String addOptionKey, LocalDate plannedDate, List<String> items, Long chargePaise,
                                   String note) {
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
    public record AddSittingInput(Long revision, String addOptionKey, LocalDate plannedDate) {
    }

    /** PUT /emr/courses/{courseId}/sittings/{sittingId}. */
    public record ChangeSittingInput(Long revision, String encounterKind, LocalDate plannedDate, List<String> items,
                                     Long chargePaise, String note) {
    }

    /** POST /emr/courses/{courseId}/sittings/{sittingId}/link-visit. */
    public record LinkVisitInput(Long revision, UUID visitId) {
    }
}
