package com.sahaj.opd.application.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.CourseAdmissionSource;
import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.CourseInputs.AdmissionRequestInput;
import com.sahaj.opd.application.course.CourseInputs.DateInput;
import com.sahaj.opd.application.course.CourseInputs.MarkDoneInput;
import com.sahaj.opd.application.course.CourseInputs.RevisionInput;
import com.sahaj.opd.application.course.CourseViews.CourseView;
import com.sahaj.opd.application.course.CourseViews.SittingView;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-75-10 (ADR-026 D4): the six sitting actions, the five states, the display numbers and the actions each caller may
 * take. Rule tag: values from the EMR mockup, not clinically reviewed. Today is 12 Nov 2026 in Asia/Kolkata.
 */
class CourseSittingActionsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final UUID OTHER_M = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();
    private static final UUID S4 = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-12");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordService records = mock(EmrRecordService.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final EmrRecordRepository emrRecords = mock(EmrRecordRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CourseAdmissionSource> provider = mock(ObjectProvider.class);
    private final CourseAdmissionSource admissionSource = mock(CourseAdmissionSource.class);
    private final AuditLog audit = mock(AuditLog.class);
    private TreatmentCourseService service;
    private TeamMember doctorMember;

    @BeforeEach
    void setUp() {
        doctorMember = new TeamMember(DOCTOR_M, "Dr Rao", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(any())).thenReturn(doctorMember);
        when(team.find(TENANT, DOCTOR_M)).thenReturn(Optional.of(doctorMember));
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(doctorMember));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(provider.getIfAvailable()).thenReturn(admissionSource);
        when(admissionSource.admissions(eq(TENANT), any())).thenReturn(Map.of());
        service = new TreatmentCourseService(access, records, courses, visits, emrRecords, team, entitlements,
                provider, audit, CLOCK);
    }

    private static CourseRow course(long revision, String kind) {
        return new CourseRow(COURSE, 412, PATIENT, kind, "SITTING", "Course", null, DOCTOR_M,
                LocalDate.parse("2026-11-01"), "ACTIVE", revision, "hash", Instant.parse("2026-11-01T05:00:00Z"));
    }

    /** A sitting: kind OPD or DAY_CARE; the three timestamps are the states' stored columns. */
    private static SittingRow sitting(UUID id, int no, String kind, LocalDate planned, UUID visit, UUID admission,
                                      boolean requested, Instant doneAt, Instant missedAt, Instant removedAt) {
        return new SittingRow(id, COURSE, no, kind, "OPD".equals(kind) ? "OPD_SITTING" : "DAY_CARE_ADMISSION", planned,
                List.of("Root canal"), 100_000, null, visit, admission, requested, doneAt, null, missedAt, removedAt,
                doneAt == null ? null : "SIGN", null);
    }

    private static SittingRow plannedOpd(UUID id, int no) {
        return sitting(id, no, "OPD", LocalDate.parse("2026-12-20"), null, null, false, null, null, null);
    }

    private static SittingRow todayOpd(UUID id, int no) {
        return sitting(id, no, "OPD", TODAY, null, null, false, null, null, null);
    }

    private void courseWith(long revision, SittingRow... sittings) {
        courseWith(revision, "DENTAL", sittings);
    }

    private void courseWith(long revision, String kind, SittingRow... sittings) {
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(course(revision, kind)));
        when(courses.findForUpdate(TENANT, COURSE)).thenReturn(Optional.of(course(revision, kind)));
        List<SittingRow> listed = java.util.Arrays.stream(sittings).filter(s -> s.removedAt() == null).toList();
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(listed);
        for (SittingRow s : sittings) {
            when(courses.sittingForUpdate(TENANT, s.id())).thenReturn(Optional.of(s));
        }
    }

    private static VisitRow visit(UUID id, Instant createdAt) {
        return new VisitRow(id, PATIENT, DOCTOR_M, "NEW", "CONSULTING", 12, 50000, createdAt, 0, 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditedValues(String action) {
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(action), eq("TREATMENT_COURSE"), eq(COURSE), eq(null), values.capture());
        return values.getValue();
    }

    private static void assertProblem(Throwable thrown, int status, String slug) {
        assertThat(thrown).isInstanceOfSatisfying(EmrProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.slug()).isEqualTo(slug);
        });
    }

    // ---- states and display numbers ----

    @Test
    void theStatesFollowTheOrderRemovedDoneMissedTodayPlannedAndAnUnlinkedSittingPlannedForTodayIsToday() {
        SittingRow done = sitting(S1, 1, "OPD", null, null, null, false, Instant.parse("2026-11-10T05:00:00Z"), null,
                null);
        SittingRow missed = sitting(S2, 2, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"),
                null);
        SittingRow today = todayOpd(S3, 3);
        SittingRow planned = plannedOpd(S4, 4);
        courseWith(5, done, missed, today, planned);

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(view.sittings()).extracting(SittingView::status).containsExactly("DONE", "MISSED", "TODAY",
                "PLANNED");
        assertThat(view.sittingCount()).isEqualTo(4);
        assertThat(view.doneCount()).isEqualTo(1);
        assertThat(view.sittings().get(0).doneSource()).isEqualTo("SIGN");
    }

    @Test
    void aDayCareSittingWithNoLinkStaysPlannedEvenWhenItsDateIsToday() {
        courseWith(1, sitting(S1, 1, "DAY_CARE", TODAY, null, null, true, null, null, null));

        assertThat(service.get(DOCTOR, COURSE).sittings().get(0).status()).isEqualTo("PLANNED");
    }

    @Test
    void aRemovedSittingIsNotShownNotCountedAndTheLaterOnesAreRenumbered() {
        SittingRow first = plannedOpd(S1, 1);
        SittingRow removed = sitting(S2, 2, "OPD", null, null, null, false, null, null, Instant.parse("2026-11-11T05:00:00Z"));
        SittingRow third = plannedOpd(S3, 3);
        courseWith(1, first, removed, third);

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(view.sittings()).extracting(SittingView::sittingNo).containsExactly(1, 3);
        assertThat(view.sittings()).extracting(SittingView::displayNo).containsExactly(1, 2);
        assertThat(view.sittingCount()).isEqualTo(2);
    }

    @Test
    void aMovedSittingNoLongerCarriesTheLabelOfTheOptionItWasAddedWith() {
        courseWith(1, sitting(S1, 1, "DAY_CARE", null, null, null, true, null, null, null));

        assertThat(service.get(DOCTOR, COURSE).sittings().get(0).encounterLabel()).isEqualTo("Day-care admission");

        SittingRow moved = new SittingRow(S1, COURSE, 1, "DAY_CARE", "OPD_SITTING", null, List.of(), 0, null, null, null,
                true, null, null, null, null, null, null);
        courseWith(1, moved);

        assertThat(service.get(DOCTOR, COURSE).sittings().get(0).encounterLabel()).isEqualTo("Day care");
    }

    @Test
    void anOldCourseWhoseNoteSaysTheEstimateWasSignedReadsSignedAndKeepsItsStoredNote() {
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(new CourseRow(COURSE, 412, PATIENT, "DENTAL", "SITTING",
                "Course", "Estimate Signed By Patient", DOCTOR_M, LocalDate.parse("2026-11-01"), "ACTIVE", 1, "hash",
                Instant.parse("2026-11-01T05:00:00Z"))));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of());

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(view.estimateSigned()).isTrue();
        assertThat(view.note()).isEqualTo("Estimate Signed By Patient");
    }

    @Test
    void aCourseWithTheFlagReadsSignedAndAnotherNoteIsKept() {
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(new CourseRow(COURSE, 412, PATIENT, "DENTAL", "SITTING",
                "Course", "Bring the old X-rays", DOCTOR_M, LocalDate.parse("2026-11-01"), "ACTIVE", 1, "hash",
                Instant.parse("2026-11-01T05:00:00Z"), null, true)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of());

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(view.estimateSigned()).isTrue();
        assertThat(view.note()).isEqualTo("Bring the old X-rays");
    }

    @Test
    void theListFiltersByKindAndRefusesAnUnknownKind() {
        CourseRow dental = course(0, "DENTAL");
        CourseRow chemo = new CourseRow(UUID.randomUUID(), 413, PATIENT, "CHEMO", "CYCLE", "Chemotherapy course", null,
                DOCTOR_M, LocalDate.parse("2026-11-02"), "ACTIVE", 0, "h2", Instant.parse("2026-11-02T05:00:00Z"));
        when(courses.forPatient(eq(TENANT), eq(PATIENT), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(chemo, dental));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of());

        assertThat(service.list(DOCTOR, PATIENT, "DENTAL")).extracting(s -> s.kind()).containsExactly("DENTAL");
        assertThat(service.list(DOCTOR, PATIENT, null)).extracting(s -> s.kind()).containsExactly("CHEMO", "DENTAL");
        assertThatThrownBy(() -> service.list(DOCTOR, PATIENT, "NOPE")).isInstanceOf(InvalidFieldsException.class);
    }

    // ---- allowed actions ----

    private static List<String> actionsOf(CourseView view, UUID sitting) {
        return view.sittings().stream().filter(s -> s.id().equals(sitting)).findFirst().orElseThrow().allowedActions();
    }

    @Test
    void theCoursesDoctorIsOfferedTheDrawnButtonsOfEachState() {
        SittingRow today = todayOpd(S1, 1);
        SittingRow missed = sitting(S2, 2, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"),
                null);
        SittingRow planned = plannedOpd(S3, 3);
        SittingRow done = sitting(S4, 4, "OPD", null, null, null, false, Instant.parse("2026-11-10T05:00:00Z"), null,
                null);
        courseWith(1, today, missed, planned, done);

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(actionsOf(view, S1)).containsExactly("MARK_DONE", "MISSED");
        assertThat(actionsOf(view, S2)).containsExactly("REBOOK");
        assertThat(actionsOf(view, S3)).containsExactly("RESCHEDULE", "REMOVE", "REQUEST_ADMISSION");
        assertThat(actionsOf(view, S4)).isEmpty();
    }

    @Test
    void aSittingLinkedToAVisitCannotBeRemovedOrMissed() {
        SittingRow linkedToday = sitting(S1, 1, "OPD", null, VISIT, null, false, null, null, null);
        SittingRow linkedEarlier = sitting(S2, 2, "OPD", null, UUID.randomUUID(), null, false, null, null, null);
        courseWith(1, linkedToday, linkedEarlier);
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, Instant.parse("2026-11-12T04:00:00Z"))));
        UUID earlier = linkedEarlier.opdVisitId();
        when(visits.findById(TENANT, earlier)).thenReturn(Optional.of(visit(earlier, Instant.parse("2026-11-01T04:00:00Z"))));

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(actionsOf(view, S1)).containsExactly("MARK_DONE");
        assertThat(actionsOf(view, S2)).containsExactly("RESCHEDULE");
    }

    @Test
    void frontDeskGetsOnlyAdmitNowOnAPlannedDayCareSittingAndBillingGetsNothing() {
        courseWith(1, sitting(S1, 1, "DAY_CARE", null, null, null, true, null, null, null), plannedOpd(S2, 2));
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.empty());

        CourseView desk = service.get(new ClinicUser(USER, TENANT, "FRONT_DESK"), COURSE);
        CourseView billing = service.get(new ClinicUser(USER, TENANT, "BILLING"), COURSE);

        assertThat(actionsOf(desk, S1)).containsExactly("ADMIT");
        assertThat(actionsOf(desk, S2)).isEmpty();
        assertThat(actionsOf(billing, S1)).isEmpty();
        assertThat(desk.canAdmit()).isTrue();
        assertThat(billing.canAdmit()).isFalse();
    }

    @Test
    void theOwnerWithDoctorDetailsGetsAdmitNowAndNotRequestAdmissionOnADayCareSitting() {
        courseWith(1, sitting(S1, 1, "DAY_CARE", null, null, null, true, null, null, null));
        TeamMember owner = new TeamMember(OTHER_M, "Owner", "OWNER", true, 0);
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(owner));
        when(team.doctorDetails(TENANT, OTHER_M)).thenReturn(Optional.of(mock(TeamMemberPort.DoctorDetails.class)));

        CourseView view = service.get(new ClinicUser(USER, TENANT, "OWNER"), COURSE);

        assertThat(actionsOf(view, S1)).containsExactly("RESCHEDULE", "REMOVE", "ADMIT");
    }

    @Test
    void aDoctorGetsRequestAdmissionOnADayCareSittingNotYetRequestedAndNothingForDayCareWhenIpdIsOff() {
        courseWith(1, sitting(S1, 1, "DAY_CARE", null, null, null, false, null, null, null));

        assertThat(actionsOf(service.get(DOCTOR, COURSE), S1)).containsExactly("RESCHEDULE", "REMOVE",
                "REQUEST_ADMISSION");

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThat(actionsOf(service.get(DOCTOR, COURSE), S1)).containsExactly("RESCHEDULE", "REMOVE");
    }

    @Test
    void anotherDoctorOfTheClinicHasNoActionOnTheCourse() {
        courseWith(1, todayOpd(S1, 1));
        TeamMember other = new TeamMember(OTHER_M, "Dr Iyer", "DOCTOR", true, 0);
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(other));

        CourseView view = service.get(DOCTOR, COURSE);

        assertThat(view.canPlan()).isFalse();
        assertThat(actionsOf(view, S1)).isEmpty();
    }

    // ---- mark done ----

    @Test
    void markDoneSetsDoneByHandBumpsTheRevisionAndAuditsIdsDatesAndStatusWordsOnly() {
        courseWith(3, todayOpd(S1, 1));

        service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, null));

        verify(courses).markDoneByHand(TENANT, S1, NOW, DOCTOR_M, USER);
        verify(courses).bumpRevision(TENANT, COURSE, USER);
        Map<String, Object> values = auditedValues("COURSE_SITTING_MARKED_DONE");
        assertThat(values).containsEntry("sittingId", S1).containsEntry("sittingNo", 1)
                .containsEntry("displayNo", 1).containsEntry("statusBefore", "TODAY")
                .containsEntry("statusAfter", "DONE").containsEntry("encounterKindBefore", "OPD");
        assertThat(values.toString()).doesNotContain("Root canal");
    }

    @Test
    void markDoneWithTheHostingVisitLinksItFirstAndAuditsTheLinkToo() {
        courseWith(3, todayOpd(S1, 1));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, Instant.parse("2026-11-12T04:00:00Z"))));
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.empty());

        service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, VISIT));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(courses);
        order.verify(courses).linkVisit(TENANT, S1, VISIT, USER);
        order.verify(courses).markDoneByHand(TENANT, S1, NOW, DOCTOR_M, USER);
        verify(audit).record(eq("COURSE_SITTING_VISIT_LINKED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null), any());
    }

    @Test
    void markDoneWithAVisitThatBelongsToAnotherSittingIs409AndWritesNothing() {
        courseWith(3, todayOpd(S1, 1));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, Instant.parse("2026-11-12T04:00:00Z"))));
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(plannedOpd(S2, 2)));

        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, VISIT)))
                .satisfies(e -> assertProblem(e, 409, "visit-already-linked"));
        verify(courses, never()).markDoneByHand(any(), any(), any(), any(), any());
        verify(courses, never()).linkVisit(any(), any(), any(), any());
    }

    @Test
    void markDoneIgnoresTheVisitWhenTheSittingIsAlreadyLinked() {
        courseWith(3, sitting(S1, 1, "OPD", null, VISIT, null, false, null, null, null));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, Instant.parse("2026-11-12T04:00:00Z"))));

        service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, UUID.randomUUID()));

        verify(courses, never()).linkVisit(any(), any(), any(), any());
        verify(courses).markDoneByHand(TENANT, S1, NOW, DOCTOR_M, USER);
    }

    @Test
    void markDoneOnASittingThatIsNotTodayIs409AndOnADoneOneSittingDone() {
        courseWith(3, plannedOpd(S1, 1),
                sitting(S2, 2, "OPD", null, null, null, false, Instant.parse("2026-11-10T05:00:00Z"), null, null),
                sitting(S3, 3, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"), null));

        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, null)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S2, new MarkDoneInput(3L, null)))
                .satisfies(e -> assertProblem(e, 409, "sitting-done"));
        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S3, new MarkDoneInput(3L, null)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        verify(courses, never()).markDoneByHand(any(), any(), any(), any(), any());
    }

    @Test
    void aStaleRevisionOrASecondPressIs409CourseChangedWithTheFixedSentence() {
        courseWith(4, todayOpd(S1, 1));

        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, null)))
                .satisfies(e -> {
                    assertProblem(e, 409, "course-changed");
                    assertThat(e.getMessage()).isEqualTo("This sitting changed. Reload.");
                });
        verify(courses, never()).markDoneByHand(any(), any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingRevisionIs400AndARemovedSittingIs404() {
        SittingRow removed = sitting(S1, 1, "OPD", null, null, null, false, null, null, Instant.parse("2026-11-11T05:00:00Z"));
        courseWith(3, removed);

        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(null, null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, null)))
                .satisfies(e -> assertProblem(e, 404, "sitting-not-found"));
    }

    @Test
    void onlyTheCoursesOwnDoctorOrTheOwnerMayAct() {
        courseWith(3, todayOpd(S1, 1));
        when(access.requireConsultationWrite(any())).thenReturn(new TeamMember(OTHER_M, "Dr Iyer", "DOCTOR", true, 0));

        assertThatThrownBy(() -> service.markDone(DOCTOR, COURSE, S1, new MarkDoneInput(3L, null)))
                .satisfies(e -> assertProblem(e, 403, "not-own-patient"));
        verify(courses, never()).markDoneByHand(any(), any(), any(), any(), any());
    }

    // ---- missed, rebook, reschedule ----

    @Test
    void patientDidNotComeMarksAnUnlinkedTodaySittingMissedAndAuditsIt() {
        courseWith(3, todayOpd(S1, 1));

        service.markMissed(DOCTOR, COURSE, S1, new RevisionInput(3L));

        verify(courses).markMissed(TENANT, S1, NOW, DOCTOR_M, USER);
        verify(courses).bumpRevision(TENANT, COURSE, USER);
        assertThat(auditedValues("COURSE_SITTING_MISSED")).containsEntry("statusBefore", "TODAY")
                .containsEntry("statusAfter", "MISSED");
    }

    @Test
    void aSittingWithALinkedVisitCannotBeMarkedMissedBecauseThePatientCame() {
        courseWith(3, sitting(S1, 1, "OPD", null, VISIT, null, false, null, null, null));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, Instant.parse("2026-11-12T04:00:00Z"))));

        assertThatThrownBy(() -> service.markMissed(DOCTOR, COURSE, S1, new RevisionInput(3L)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        verify(courses, never()).markMissed(any(), any(), any(), any(), any());
    }

    @Test
    void rebookClearsTheMissedMarkAndTheDateMustBeTodayOrLater() {
        courseWith(3, sitting(S1, 1, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"), null));

        service.rebook(DOCTOR, COURSE, S1, new DateInput(3L, LocalDate.parse("2026-11-20")));

        verify(courses).rebook(TENANT, S1, LocalDate.parse("2026-11-20"), USER);
        Map<String, Object> values = auditedValues("COURSE_SITTING_REBOOKED");
        assertThat(values).containsEntry("statusBefore", "MISSED").containsEntry("statusAfter", "PLANNED")
                .containsEntry("plannedDateAfter", "2026-11-20");
        assertThatThrownBy(() -> service.rebook(DOCTOR, COURSE, S1, new DateInput(3L, LocalDate.parse("2026-11-11"))))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors().get(0).message()).isEqualTo("Choose today or a later date."));
        assertThatThrownBy(() -> service.rebook(DOCTOR, COURSE, S1, new DateInput(3L, null)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void rebookingForTodayMakesTheAnUnlinkedOpdSittingTodayAndAnotherStatusIsRefused() {
        courseWith(3, sitting(S1, 1, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"), null),
                plannedOpd(S2, 2));

        service.rebook(DOCTOR, COURSE, S1, new DateInput(3L, TODAY));

        assertThat(auditedValues("COURSE_SITTING_REBOOKED")).containsEntry("statusAfter", "TODAY");
        assertThatThrownBy(() -> service.rebook(DOCTOR, COURSE, S2, new DateInput(3L, TODAY)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
    }

    @Test
    void rescheduleMovesOnlyThePlannedDateOfAPlannedSitting() {
        courseWith(3, plannedOpd(S1, 1), todayOpd(S2, 2));

        service.reschedule(DOCTOR, COURSE, S1, new DateInput(3L, LocalDate.parse("2026-12-24")));

        verify(courses).reschedule(TENANT, S1, LocalDate.parse("2026-12-24"), USER);
        verify(courses, never()).updateSitting(any(), any(), any(), any());
        assertThat(auditedValues("COURSE_SITTING_RESCHEDULED")).containsEntry("plannedDateBefore", "2026-12-20")
                .containsEntry("plannedDateAfter", "2026-12-24").containsEntry("statusAfter", "PLANNED");
        assertThatThrownBy(() -> service.reschedule(DOCTOR, COURSE, S2, new DateInput(3L, LocalDate.parse("2026-12-24"))))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
    }

    // ---- remove ----

    @Test
    void removeKeepsTheRowAndAuditsTheStatusBeforeWithoutItems() {
        courseWith(3, plannedOpd(S1, 1), plannedOpd(S2, 2));

        service.remove(DOCTOR, COURSE, S2, new RevisionInput(3L));

        verify(courses).remove(TENANT, S2, NOW, DOCTOR_M, USER);
        Map<String, Object> values = auditedValues("COURSE_SITTING_REMOVED");
        assertThat(values).containsEntry("sittingNo", 2).containsEntry("displayNo", 2)
                .containsEntry("statusBefore", "PLANNED").containsEntry("statusAfter", "REMOVED");
        assertThat(values.toString()).doesNotContain("Root canal");
    }

    @Test
    void removeIsRefusedForTodayMissedAndDoneAndForAPlannedSittingLinkedToAVisit() {
        UUID oldVisit = UUID.randomUUID();
        courseWith(3, todayOpd(S1, 1),
                sitting(S2, 2, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"), null),
                sitting(S3, 3, "OPD", null, null, null, false, Instant.parse("2026-11-10T05:00:00Z"), null, null),
                sitting(S4, 4, "OPD", null, oldVisit, null, false, null, null, null));
        when(visits.findById(TENANT, oldVisit)).thenReturn(Optional.of(visit(oldVisit, Instant.parse("2026-11-01T04:00:00Z"))));

        assertThatThrownBy(() -> service.remove(DOCTOR, COURSE, S1, new RevisionInput(3L)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        assertThatThrownBy(() -> service.remove(DOCTOR, COURSE, S2, new RevisionInput(3L)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        assertThatThrownBy(() -> service.remove(DOCTOR, COURSE, S3, new RevisionInput(3L)))
                .satisfies(e -> assertProblem(e, 409, "sitting-done"));
        assertThatThrownBy(() -> service.remove(DOCTOR, COURSE, S4, new RevisionInput(3L)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        verify(courses, never()).remove(any(), any(), any(), any(), any());
    }

    // ---- admission request ----

    @Test
    void anOpdSittingMovesToDayCareByDefaultOrToIpdWhenAsked() {
        courseWith(3, plannedOpd(S1, 1), plannedOpd(S2, 2));

        service.requestAdmission(DOCTOR, COURSE, S1, new AdmissionRequestInput(3L, null));
        service.requestAdmission(DOCTOR, COURSE, S2, new AdmissionRequestInput(3L, "IPD"));

        verify(courses).requestAdmission(TENANT, S1, "DAY_CARE", USER);
        verify(courses).requestAdmission(TENANT, S2, "IPD", USER);
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(audit, org.mockito.Mockito.times(2)).record(eq("COURSE_SITTING_ADMISSION_REQUESTED"), eq("TREATMENT_COURSE"),
                eq(COURSE), eq(null), values.capture());
        assertThat(values.getAllValues().get(0)).containsEntry("encounterKindBefore", "OPD")
                .containsEntry("encounterKindAfter", "DAY_CARE");
    }

    @Test
    void anAlreadyRequestedDayCareSittingAnswersTheCourseAndWritesNothing() {
        courseWith(3, sitting(S1, 1, "DAY_CARE", null, null, null, true, null, null, null));

        CourseView view = service.requestAdmission(DOCTOR, COURSE, S1, new AdmissionRequestInput(3L, null));

        assertThat(view.id()).isEqualTo(COURSE);
        verify(courses, never()).requestAdmission(any(), any(), any(), any());
        verify(courses, never()).bumpRevision(any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aDayCareSittingNotYetRequestedGetsTheRequestAndTheEncounterKindStays() {
        courseWith(3, sitting(S1, 1, "DAY_CARE", null, null, null, false, null, null, null));

        service.requestAdmission(DOCTOR, COURSE, S1, new AdmissionRequestInput(3L, "IPD"));

        verify(courses).requestAdmission(TENANT, S1, "DAY_CARE", USER);
    }

    @Test
    void withIpdOffTheRequestIs403ModuleOffAndABadKindIs400() {
        courseWith(3, plannedOpd(S1, 1));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.requestAdmission(DOCTOR, COURSE, S1, new AdmissionRequestInput(3L, null)))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.requestAdmission(DOCTOR, COURSE, S1, new AdmissionRequestInput(3L, "OPD")))
                .isInstanceOf(InvalidFieldsException.class);
        verify(courses, never()).requestAdmission(any(), any(), any(), any());
    }

    @Test
    void aTodayMissedDoneOrLinkedSittingCannotBeRequestedForAdmission() {
        courseWith(3, todayOpd(S1, 1),
                sitting(S2, 2, "OPD", null, null, null, false, Instant.parse("2026-11-10T05:00:00Z"), null, null));

        assertThatThrownBy(() -> service.requestAdmission(DOCTOR, COURSE, S1, new AdmissionRequestInput(3L, null)))
                .satisfies(e -> assertProblem(e, 409, "sitting-not-available"));
        assertThatThrownBy(() -> service.requestAdmission(DOCTOR, COURSE, S2, new AdmissionRequestInput(3L, null)))
                .satisfies(e -> assertProblem(e, 409, "sitting-done"));
    }

    // ---- the port (billing and ipd) ----

    @Test
    void theFactsBillingReadsCarryTheMissedStatusAndTheDisplayNumberAndNeverARemovedSitting() {
        SittingRow removed = sitting(S1, 1, "OPD", null, null, null, false, null, null, Instant.parse("2026-11-11T05:00:00Z"));
        SittingRow missed = sitting(S2, 2, "OPD", TODAY, null, null, false, null, Instant.parse("2026-11-12T05:00:00Z"), null);
        courseWith(3, removed, missed, plannedOpd(S3, 3));

        var facts = service.facts(TENANT, COURSE).orElseThrow();

        assertThat(facts.sittings()).extracting(s -> s.sittingNo()).containsExactly(2, 3);
        assertThat(facts.sittings()).extracting(s -> s.displayNo()).containsExactly(1, 2);
        assertThat(facts.sittings()).extracting(s -> s.status()).containsExactly("MISSED", "PLANNED");
    }

    @Test
    void linkAdmissionRefusesARemovedMissedOrDoneSittingAndWritesNothing() {
        SittingRow removed = sitting(S1, 1, "DAY_CARE", null, null, null, true, null, null, Instant.parse("2026-11-11T05:00:00Z"));
        SittingRow missed = sitting(S2, 2, "DAY_CARE", null, null, null, true, null, Instant.parse("2026-11-12T05:00:00Z"), null);
        SittingRow done = sitting(S3, 3, "DAY_CARE", null, null, null, true, Instant.parse("2026-11-10T05:00:00Z"), null, null);
        courseWith(3, removed, missed, done);

        for (UUID id : new UUID[] {S1, S2, S3}) {
            assertThatThrownBy(() -> service.linkAdmission(TENANT, USER, id, ADMISSION))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.getMessage()).contains("cannot be admitted"));
        }
        verify(courses, never()).linkAdmission(any(), any(), any(), any());
    }

    @Test
    void anAdmittableSittingIsNeverARemovedOrMissedOne() {
        SittingRow removed = sitting(S1, 1, "DAY_CARE", null, null, null, true, null, null, Instant.parse("2026-11-11T05:00:00Z"));
        SittingRow missed = sitting(S2, 2, "DAY_CARE", null, null, null, true, null, Instant.parse("2026-11-12T05:00:00Z"), null);
        SittingRow ok = sitting(S3, 3, "DAY_CARE", null, null, null, true, null, null, null);
        courseWith(3, removed, missed, ok);

        assertThat(service.admittable(TENANT, PATIENT, S1)).isEmpty();
        assertThat(service.admittable(TENANT, PATIENT, S2)).isEmpty();
        assertThat(service.admittable(TENANT, PATIENT, S3)).isPresent();
    }

    @Test
    void anAdmissionThatIsDischargedMakesTheSittingDoneEvenWhenItWasMissedFirst() {
        SittingRow linked = sitting(S1, 1, "DAY_CARE", null, null, ADMISSION, true, null, null, null);
        courseWith(3, linked);
        when(admissionSource.admissions(eq(TENANT), any())).thenReturn(Map.of(ADMISSION,
                new CourseAdmission(ADMISSION, "IP/26-27/0012", "DISCHARGED", 100_000, "12")));

        assertThat(service.get(DOCTOR, COURSE).sittings().get(0).status()).isEqualTo("DONE");
    }
}
