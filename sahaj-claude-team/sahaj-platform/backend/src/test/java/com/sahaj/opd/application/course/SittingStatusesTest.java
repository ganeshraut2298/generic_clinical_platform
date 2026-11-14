package com.sahaj.opd.application.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-026 D4: the order of the states of a course sitting, in one pure function used by the course view, the port that
 * billing and ipd read and the oncology strip. Today is 14 Nov 2026 (Asia/Kolkata).
 */
class SittingStatusesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private static final Instant EARLIER = Instant.parse("2026-11-10T05:00:00Z");
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();

    private static SittingRow sitting(String kind, LocalDate planned, UUID visit, UUID admission, Instant doneAt,
                                      Instant missedAt, Instant removedAt) {
        return new SittingRow(UUID.randomUUID(), UUID.randomUUID(), 1, kind, "OPD_SITTING", planned, List.of(), 0, null,
                visit, admission, !"OPD".equals(kind), doneAt, null, missedAt, removedAt, null, null);
    }

    private static VisitRow visitMadeAt(String instant) {
        return new VisitRow(VISIT, UUID.randomUUID(), UUID.randomUUID(), "NEW", "CONSULTING", 1, 0, Instant.parse(instant),
                0, 1);
    }

    private static RecordRow record(String status) {
        return new RecordRow(UUID.randomUUID(), "OPD_VISIT", VISIT, null, null, null, UUID.randomUUID(), UUID.randomUUID(),
                "gm", "GM", "gm", "STARTER_LAYOUT", null, 1, "X", status, null, "{}", null, null, false, null, 1, EARLIER);
    }

    private static String status(SittingRow s, VisitRow visit, RecordRow record, CourseAdmission admission) {
        return SittingStatuses.status(s, TODAY, visit, record, admission);
    }

    @Test
    void aRemovedSittingIsRemovedWhateverElseIsSet() {
        assertThat(status(sitting("OPD", TODAY, null, null, null, null, EARLIER), null, null, null)).isEqualTo("REMOVED");
        assertThat(status(sitting("OPD", TODAY, null, null, null, EARLIER, EARLIER), null, null, null))
                .isEqualTo("REMOVED");
    }

    @Test
    void doneByHandBySignedVisitOrByDischargeIsDoneAndDoneBeatsMissed() {
        assertThat(status(sitting("OPD", null, null, null, EARLIER, null, null), null, null, null)).isEqualTo("DONE");
        assertThat(status(sitting("OPD", null, VISIT, null, null, null, null), visitMadeAt("2026-11-14T04:00:00Z"),
                record("SIGNED"), null)).isEqualTo("DONE");
        assertThat(status(sitting("DAY_CARE", null, null, ADMISSION, null, null, null), null, null,
                new CourseAdmission(ADMISSION, "IP/1", "DISCHARGED", 0))).isEqualTo("DONE");
        assertThat(status(sitting("OPD", TODAY, null, null, EARLIER, EARLIER, null), null, null, null)).isEqualTo("DONE");
    }

    @Test
    void missedBeatsTodayAndPlanned() {
        assertThat(status(sitting("OPD", TODAY, null, null, null, EARLIER, null), null, null, null)).isEqualTo("MISSED");
        assertThat(status(sitting("OPD", null, null, null, null, EARLIER, null), null, null, null)).isEqualTo("MISSED");
    }

    @Test
    void todayIsALinkedVisitMadeTodayAnAdmissionNotDischargedOrAnUnlinkedOpdSittingPlannedForToday() {
        assertThat(status(sitting("OPD", null, VISIT, null, null, null, null), visitMadeAt("2026-11-14T04:00:00Z"),
                record("DRAFT"), null)).isEqualTo("TODAY");
        assertThat(status(sitting("DAY_CARE", null, null, ADMISSION, null, null, null), null, null,
                new CourseAdmission(ADMISSION, "IP/1", "ADMITTED", 0))).isEqualTo("TODAY");
        assertThat(status(sitting("OPD", TODAY, null, null, null, null, null), null, null, null)).isEqualTo("TODAY");
    }

    @Test
    void aVisitMadeOnAnotherDayDoesNotMakeTheSittingTodayEvenWhenItsDateIsToday() {
        assertThat(status(sitting("OPD", TODAY, VISIT, null, null, null, null), visitMadeAt("2026-11-13T04:00:00Z"),
                record("DRAFT"), null)).isEqualTo("PLANNED");
    }

    @Test
    void aVisitMadeAtTwoAmIndiaTimeCountsAsThatIndiaDayNotTheUtcDay() {
        // 13 Nov 20:30 UTC is 14 Nov 02:00 in India
        assertThat(status(sitting("OPD", null, VISIT, null, null, null, null), visitMadeAt("2026-11-13T20:30:00Z"),
                record("DRAFT"), null)).isEqualTo("TODAY");
    }

    @Test
    void aDayCareOrIpdSittingWithNoLinkStaysPlannedEvenWhenPlannedForToday() {
        assertThat(status(sitting("DAY_CARE", TODAY, null, null, null, null, null), null, null, null)).isEqualTo("PLANNED");
        assertThat(status(sitting("IPD", TODAY, null, null, null, null, null), null, null, null)).isEqualTo("PLANNED");
    }

    @Test
    void anOpdSittingPlannedForAnotherDayOrWithNoDateIsPlanned() {
        assertThat(status(sitting("OPD", TODAY.plusDays(1), null, null, null, null, null), null, null, null))
                .isEqualTo("PLANNED");
        assertThat(status(sitting("OPD", null, null, null, null, null, null), null, null, null)).isEqualTo("PLANNED");
    }
}
