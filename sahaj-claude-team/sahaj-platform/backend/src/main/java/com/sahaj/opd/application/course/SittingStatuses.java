package com.sahaj.opd.application.course;

import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The state of a course sitting, worked out on every read and never stored (ADR-022 D12; ADR-026 D4). One function, used
 * by the course view, the port that billing and ipd read, and the oncology journey strip, so they always agree.
 *
 * <p>In this order: REMOVED (removed_at), DONE (done_at, a SIGNED linked visit record, or a DISCHARGED linked admission),
 * MISSED (missed_at), TODAY (a linked visit made today, a linked admission not discharged, or an unlinked OPD sitting
 * planned for today), else PLANNED.
 */
public final class SittingStatuses {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private SittingStatuses() {
    }

    /**
     * @param today     today in Asia/Kolkata
     * @param visit     the linked OPD visit, or null
     * @param record    the EMR record of that visit, or null
     * @param admission the linked admission as ipd reports it, or null
     */
    public static String status(SittingRow s, LocalDate today, VisitRow visit, RecordRow record,
                                CourseAdmission admission) {
        if (s.removedAt() != null) {
            return "REMOVED";
        }
        if (s.doneAt() != null) {
            return "DONE";
        }
        if (record != null && "SIGNED".equals(record.status())) {
            return "DONE";
        }
        if (admission != null && "DISCHARGED".equals(admission.status())) {
            return "DONE";
        }
        if (s.missedAt() != null) {
            return "MISSED";
        }
        if (s.admissionId() != null) {
            return "TODAY";
        }
        if (visit != null && visit.createdAt().atZone(INDIA).toLocalDate().equals(today)) {
            return "TODAY";
        }
        if (s.opdVisitId() == null && CourseKinds.ENCOUNTER_OPD.equals(s.encounterKind())
                && today.equals(s.plannedDate())) {
            return "TODAY";
        }
        return "PLANNED";
    }
}
