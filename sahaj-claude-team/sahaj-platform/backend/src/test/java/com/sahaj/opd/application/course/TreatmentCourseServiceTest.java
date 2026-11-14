package com.sahaj.opd.application.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.CourseAdmissionSource;
import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.api.TreatmentCoursePort.AdmittableSitting;
import com.sahaj.opd.api.TreatmentCoursePort.CourseFacts;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.CourseInputs.AddSittingInput;
import com.sahaj.opd.application.course.CourseInputs.ChangeSittingInput;
import com.sahaj.opd.application.course.CourseInputs.CreateInput;
import com.sahaj.opd.application.course.CourseInputs.LinkVisitInput;
import com.sahaj.opd.application.course.CourseInputs.SittingPlanInput;
import com.sahaj.opd.application.course.CourseViews.Changed;
import com.sahaj.opd.application.course.CourseViews.CourseView;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewCourse;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingChange;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
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
import org.springframework.security.access.AccessDeniedException;

/** SAH-58: the treatment course plan (ADR-022 D12). Rule tag B-1: values from the EMR mockup, not clinically reviewed. */
class TreatmentCourseServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final UUID OTHER_M = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");
    private static final String KEY = "key-0000001";

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

    private static CourseRow course(long revision) {
        return new CourseRow(COURSE, 412, PATIENT, "DENTAL", "SITTING", "Dental treatment course", "estimate signed",
                DOCTOR_M, LocalDate.parse("2026-11-01"), "ACTIVE", revision, "hash", Instant.parse("2026-11-01T05:00:00Z"));
    }

    private static SittingRow sitting(UUID id, int no, String kind, long charge, UUID visit, UUID admission) {
        return new SittingRow(id, COURSE, no, kind, kind.equals("OPD") ? "OPD_SITTING" : "IPD_ADMISSION", null,
                List.of("Root canal"), charge, null, visit, admission, !kind.equals("OPD"), null, null);
    }

    private void courseWith(long revision, SittingRow... sittings) {
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(course(revision)));
        when(courses.findForUpdate(TENANT, COURSE)).thenReturn(Optional.of(course(revision)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sittings));
        for (SittingRow s : sittings) {
            when(courses.sittingForUpdate(TENANT, s.id())).thenReturn(Optional.of(s));
        }
    }

    private static RecordRow record(String status, Instant signedAt) {
        return new RecordRow(UUID.randomUUID(), "OPD_VISIT", VISIT, null, null, null, PATIENT, DOCTOR_M, "dental",
                "Dental", "dental", "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", status, signedAt, "{}", null, null,
                false, null, 0, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private static VisitRow visit(UUID id, UUID patient, Instant createdAt) {
        return new VisitRow(id, patient, DOCTOR_M, "NEW", "CONSULTING", 12, 50000, createdAt, 0, 1);
    }

    // ---- create ----

    private Authorized authorized(UUID patient) {
        Authorized authorized = new Authorized(doctorMember, record("DRAFT", null), patient);
        when(records.authorize(eq(DOCTOR), any())).thenReturn(authorized);
        return authorized;
    }

    private CreateInput createInput() {
        return new CreateInput(UUID.randomUUID(), "DENTAL", null, "estimate signed",
                List.of(new SittingPlanInput("opd_sitting", LocalDate.parse("2026-11-15"), List.of("Root canal"),
                        250_000L, null), new SittingPlanInput("day_care_admission", null, List.of(), 0L, null)));
    }

    @Test
    void startingACourseTakesTheNextNumberTheKindsDefaultNameAndNumbersTheSittingsInOrder() {
        authorized(PATIENT);
        when(courses.nextCourseNo(TENANT)).thenReturn(412);
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        courseWith(0, sitting(S1, 1, "OPD", 250_000, null, null), sitting(S2, 2, "DAY_CARE", 0, null, null));

        Changed result = service.create(DOCTOR, KEY, createInput());

        assertThat(result.created()).isTrue();
        assertThat(result.course().courseNo()).isEqualTo("TC-0412");
        ArgumentCaptor<NewCourse> course = ArgumentCaptor.forClass(NewCourse.class);
        verify(courses).insertCourse(eq(TENANT), course.capture(), eq(USER));
        assertThat(course.getValue().name()).isEqualTo("Dental treatment course");
        assertThat(course.getValue().unit()).isEqualTo("SITTING");
        assertThat(course.getValue().doctorMembershipId()).isEqualTo(DOCTOR_M);
        assertThat(course.getValue().patientId()).isEqualTo(PATIENT);
        assertThat(course.getValue().idempotencyKey()).isEqualTo(KEY);
        assertThat(course.getValue().startedOn()).isEqualTo(LocalDate.parse("2026-11-12"));
        ArgumentCaptor<NewSitting> sittings = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses, org.mockito.Mockito.times(2)).insertSitting(eq(TENANT), sittings.capture(), eq(USER));
        assertThat(sittings.getAllValues()).extracting(NewSitting::sittingNo).containsExactly(1, 2);
        assertThat(sittings.getAllValues()).extracting(NewSitting::addOptionKey).containsExactly("OPD_SITTING",
                "DAY_CARE_ADMISSION");
        assertThat(sittings.getAllValues().get(1).note()).isEqualTo("Admission request. Rate set at admission.");
    }

    @Test
    void aNewSittingWithNoItemsIsAcceptedAndStartsWithAnEmptyList() {
        authorized(PATIENT);
        when(courses.nextCourseNo(TENANT)).thenReturn(412);
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        courseWith(0, sitting(S1, 1, "OPD", 250_000, null, null));

        Changed result = service.create(DOCTOR, KEY, new CreateInput(UUID.randomUUID(), "DENTAL", null, null,
                List.of(new SittingPlanInput("opd_sitting", LocalDate.parse("2026-11-15"), null, 250_000L, null))));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<NewSitting> sittings = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), sittings.capture(), eq(USER));
        assertThat(sittings.getValue().items()).isEmpty();
    }

    @Test
    void aNewSittingWithAChargeThatIsNotWholeRupeesIsStillRefusedOnTheChargeAndNotOnTheMissingItems() {
        authorized(PATIENT);

        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(UUID.randomUUID(), "DENTAL", null, null,
                List.of(new SittingPlanInput("opd_sitting", null, null, 150L, null)))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).containsExactly("sittings[0].chargePaise"));
    }

    @Test
    void changingASittingStillNeedsTheItemsList() {
        courseWith(4, sitting(S1, 1, "OPD", 100_000, null, null));

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1,
                new ChangeSittingInput(4L, "OPD", null, null, 100_000L, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).contains("items"));
    }

    @Test
    void theAuditOfACreateHoldsIdsKindsAndMoneyButNoItemText() {
        authorized(PATIENT);
        when(courses.nextCourseNo(TENANT)).thenReturn(7);
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        courseWith(0);

        service.create(DOCTOR, KEY, createInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("TREATMENT_COURSE_CREATED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null),
                after.capture());
        assertThat(after.getValue()).containsEntry("courseNo", "TC-0007").containsEntry("kind", "DENTAL")
                .containsEntry("sittingCount", 2).containsEntry("estimatePaise", 250_000L);
        assertThat(after.getValue().toString()).doesNotContain("Root canal").doesNotContain("estimate signed");
    }

    @Test
    void theSameKeyAndBodyReturnsTheFirstCourseAndWritesNothing() {
        authorized(PATIENT);
        when(courses.nextCourseNo(TENANT)).thenReturn(1);
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        courseWith(0);
        CreateInput input = createInput();
        service.create(DOCTOR, KEY, input);
        ArgumentCaptor<NewCourse> first = ArgumentCaptor.forClass(NewCourse.class);
        verify(courses).insertCourse(eq(TENANT), first.capture(), eq(USER));
        CourseRow stored = new CourseRow(COURSE, 1, PATIENT, "DENTAL", "SITTING", "x", null, DOCTOR_M,
                LocalDate.now(), "ACTIVE", 0, first.getValue().requestHash(), NOW);
        when(courses.findByIdempotencyKey(TENANT, KEY)).thenReturn(Optional.of(stored));
        org.mockito.Mockito.clearInvocations(courses, audit);

        Changed again = service.create(DOCTOR, KEY, input);

        assertThat(again.created()).isFalse();
        verify(courses, never()).insertCourse(any(), any(), any());
        verify(courses, never()).insertSitting(any(), any(), any());
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void theSameKeyWithAnotherBodyIs409IdempotencyConflict() {
        CourseRow stored = new CourseRow(COURSE, 1, PATIENT, "DENTAL", "SITTING", "x", null, DOCTOR_M,
                LocalDate.now(), "ACTIVE", 0, "some other hash", NOW);
        when(courses.findByIdempotencyKey(TENANT, KEY)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.create(DOCTOR, KEY, createInput()))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("idempotency-conflict");
                });
    }

    @Test
    void aDayCareSittingNeedsIpdAndIsRefusedWhenIpdIsOffWithNothingWritten() {
        authorized(PATIENT);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.create(DOCTOR, KEY, createInput())).isInstanceOf(ModuleOffException.class);
        verify(courses, never()).insertCourse(any(), any(), any());
    }

    @Test
    void aBadRequestIsRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> service.create(DOCTOR, "short", createInput()))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(UUID.randomUUID(), "GOUT", null, null,
                List.of()))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(UUID.randomUUID(), "DENTAL", null, null,
                List.of(new SittingPlanInput("ipd_admission", null, List.of("x"), 150L, null)))))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(UUID.randomUUID(), "DENTAL", "<b>x</b>",
                null, List.of()))).isInstanceOf(InvalidFieldsException.class);
        verify(courses, never()).insertCourse(any(), any(), any());
    }

    @Test
    void aRecordWithNoPatientCannotStartACourse() {
        authorized(null);

        assertThatThrownBy(() -> service.create(DOCTOR, KEY, createInput())).isInstanceOf(InvalidFieldsException.class);
        verify(courses, never()).insertCourse(any(), any(), any());
    }

    // ---- add a sitting ----

    @Test
    void anOpdChoiceCopiesTheLastSittingsChargeAndTakesTheNextNumberUnderTheLock() {
        courseWith(3, sitting(S1, 1, "OPD", 100_000, null, null), sitting(S2, 2, "OPD", 250_000, null, null));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(3);

        Changed result = service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(3L, "opd_sitting",
                LocalDate.parse("2026-12-01")));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<NewSitting> added = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), added.capture(), eq(USER));
        assertThat(added.getValue().sittingNo()).isEqualTo(3);
        assertThat(added.getValue().chargePaise()).isEqualTo(250_000);
        assertThat(added.getValue().encounterKind()).isEqualTo("OPD");
        assertThat(added.getValue().idempotencyKey()).isEqualTo(KEY);
        verify(courses).bumpRevision(TENANT, COURSE, USER);
        verify(audit).record(eq("COURSE_SITTING_ADDED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null), any());
    }

    @Test
    void anIpdChoiceHasChargeZeroAndTheAdmissionRequestNote() {
        courseWith(0, sitting(S1, 1, "OPD", 100_000, null, null));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(2);

        service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(0L, "ipd_admission", null));

        ArgumentCaptor<NewSitting> added = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), added.capture(), eq(USER));
        assertThat(added.getValue().chargePaise()).isZero();
        assertThat(added.getValue().encounterKind()).isEqualTo("IPD");
        assertThat(added.getValue().note()).isEqualTo("Admission request. Rate set at admission.");
    }

    @Test
    void theSameAddTappedTwiceMakesOneSittingAndAStaleRevisionDoesNotRefuseAnAdd() {
        courseWith(5, sitting(S1, 1, "OPD", 100_000, null, null));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(2);
        service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(1L, "opd_sitting", null));
        ArgumentCaptor<NewSitting> first = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), first.capture(), eq(USER));
        SittingRow stored = new SittingRow(S2, COURSE, 2, "OPD", "OPD_SITTING", null, List.of(), 100_000, null, null,
                null, false, null, first.getValue().requestHash());
        when(courses.sittingByIdempotencyKey(TENANT, KEY)).thenReturn(Optional.of(stored));
        org.mockito.Mockito.clearInvocations(courses);

        Changed again = service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(1L, "opd_sitting", null));

        assertThat(again.created()).isFalse();
        verify(courses, never()).insertSitting(any(), any(), any());
        verify(courses, never()).bumpRevision(any(), any(), any());
    }

    @Test
    void aKeyUsedForAnotherAddOrAnotherCourseIs409() {
        courseWith(0, sitting(S1, 1, "OPD", 100_000, null, null));
        SittingRow other = new SittingRow(S2, UUID.randomUUID(), 1, "OPD", "OPD_SITTING", null, List.of(), 0, null,
                null, null, false, null, "hash");
        when(courses.sittingByIdempotencyKey(TENANT, KEY)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(0L, "opd_sitting", null)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("idempotency-conflict"));
    }

    @Test
    void anotherDoctorCannotAddButTheOwnerCan() {
        courseWith(0, sitting(S1, 1, "OPD", 100_000, null, null));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(2);
        TeamMember other = new TeamMember(OTHER_M, "Dr Other", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(any())).thenReturn(other);

        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(0L, "opd_sitting", null)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.slug()).isEqualTo("not-own-patient");
                });

        TeamMember owner = new TeamMember(OTHER_M, "Owner", "OWNER", true, 0);
        when(access.requireConsultationWrite(any())).thenReturn(owner);
        assertThat(service.addSitting(new ClinicUser(USER, TENANT, "OWNER"), COURSE, KEY,
                new AddSittingInput(0L, "opd_sitting", null)).created()).isTrue();
    }

    @Test
    void anAdmissionChoiceIsRefusedWhenIpdIsOffAndAnUnknownChoiceIs400() {
        courseWith(0, sitting(S1, 1, "OPD", 100_000, null, null));
        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(0L, "cancel_all", null)))
                .isInstanceOf(InvalidFieldsException.class);

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(0L, "ipd_admission", null)))
                .isInstanceOf(ModuleOffException.class);
        verify(courses, never()).insertSitting(any(), any(), any());
    }

    @Test
    void anAddToAnotherClinicsCourseIs404() {
        when(courses.findForUpdate(TENANT, COURSE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(0L, "opd_sitting", null)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(404);
                    assertThat(ex.slug()).isEqualTo("course-not-found");
                });
    }

    // ---- change a sitting ----

    private ChangeSittingInput change(long revision, String kind, long charge) {
        return new ChangeSittingInput(revision, kind, LocalDate.parse("2026-12-05"), List.of("Crown"), charge,
                "Admission deposit is due by 36 weeks.");
    }

    @Test
    void aPlannedSittingCanChangeDateItemsChargeAndNoteAndTheAuditHoldsNoItemText() {
        courseWith(4, sitting(S1, 1, "OPD", 100_000, null, null));

        CourseView view = service.changeSitting(DOCTOR, COURSE, S1, change(4, "OPD", 300_000));

        assertThat(view).isNotNull();
        ArgumentCaptor<SittingChange> changed = ArgumentCaptor.forClass(SittingChange.class);
        verify(courses).updateSitting(eq(TENANT), eq(S1), changed.capture(), eq(USER));
        assertThat(changed.getValue().chargePaise()).isEqualTo(300_000);
        assertThat(changed.getValue().items()).containsExactly("Crown");
        verify(courses).bumpRevision(TENANT, COURSE, USER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("COURSE_SITTING_CHANGED"), eq("TREATMENT_COURSE"), eq(COURSE), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("chargePaise", 100_000L).containsEntry("status", "PLANNED");
        assertThat(after.getValue()).containsEntry("chargePaise", 300_000L);
        assertThat(after.getValue().toString()).doesNotContain("Crown").doesNotContain("36 weeks");
    }

    @Test
    void aDoneSittingCannotChange() {
        SittingRow done = sitting(S1, 1, "OPD", 100_000, VISIT, null);
        courseWith(4, done);
        when(emrRecords.findOpd(TENANT, VISIT)).thenReturn(Optional.of(record("SIGNED", NOW)));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, PATIENT, NOW)));

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, change(4, "OPD", 100_000)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("sitting-done");
                });
        verify(courses, never()).updateSitting(any(), any(), any(), any());
    }

    @Test
    void aStaleRevisionIs409CourseChangedAndNothingIsWritten() {
        courseWith(5, sitting(S1, 1, "OPD", 100_000, null, null));

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, change(4, "OPD", 100_000)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("course-changed"));
        verify(courses, never()).updateSitting(any(), any(), any(), any());
    }

    @Test
    void theKindChangesBetweenOpdAndAdmissionOnlyBeforeAnythingIsLinked() {
        courseWith(1, sitting(S1, 1, "OPD", 100_000, null, null));
        service.changeSitting(DOCTOR, COURSE, S1, change(1, "DAY_CARE", 100_000));
        verify(courses).updateSitting(eq(TENANT), eq(S1), any(), eq(USER));

        courseWith(1, sitting(S2, 2, "DAY_CARE", 0, null, ADMISSION));
        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S2, change(1, "OPD", 0)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("sitting-not-available"));
    }

    @Test
    void changingToAnAdmissionIsRefusedWhenIpdIsOff() {
        courseWith(1, sitting(S1, 1, "OPD", 100_000, null, null));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, change(1, "IPD", 0)))
                .isInstanceOf(ModuleOffException.class);
    }

    @Test
    void aChargeThatIsNotWholeRupeesOrAnUnknownKindIs400() {
        courseWith(1, sitting(S1, 1, "OPD", 100_000, null, null));

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, change(1, "OPD", 150)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, change(1, "HOME", 100)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, new ChangeSittingInput(1L, "OPD", null,
                null, 100L, null))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aSittingOfAnotherCourseIs404SittingNotFound() {
        courseWith(1, sitting(S1, 1, "OPD", 100_000, null, null));
        UUID foreign = UUID.randomUUID();
        when(courses.sittingForUpdate(TENANT, foreign)).thenReturn(Optional.of(new SittingRow(foreign,
                UUID.randomUUID(), 1, "OPD", "OPD_SITTING", null, List.of(), 0, null, null, null, false, null, null)));

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, foreign, change(1, "OPD", 0)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("sitting-not-found"));
    }

    // ---- link a visit ----

    @Test
    void theDoctorCanLinkTodaysVisitToAPlannedOpdSitting() {
        courseWith(2, sitting(S1, 1, "OPD", 100_000, null, null));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, PATIENT, NOW)));

        service.linkVisit(DOCTOR, COURSE, S1, new LinkVisitInput(2L, VISIT));

        verify(courses).linkVisit(TENANT, S1, VISIT, USER);
        verify(courses).bumpRevision(TENANT, COURSE, USER);
        verify(audit).record(eq("COURSE_SITTING_VISIT_LINKED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null), any());
    }

    @Test
    void linkingTheSameVisitAgainIs200AndWritesNothingEvenWithAStaleRevision() {
        courseWith(9, sitting(S1, 1, "OPD", 100_000, VISIT, null));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, PATIENT, NOW)));

        CourseView view = service.linkVisit(DOCTOR, COURSE, S1, new LinkVisitInput(2L, VISIT));

        assertThat(view.sittings().get(0).status()).isEqualTo("TODAY");
        verify(courses, never()).linkVisit(any(), any(), any(), any());
    }

    @Test
    void aVisitOfAnotherPatientIs404EncounterNotFound() {
        courseWith(2, sitting(S1, 1, "OPD", 100_000, null, null));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, UUID.randomUUID(), NOW)));

        assertThatThrownBy(() -> service.linkVisit(DOCTOR, COURSE, S1, new LinkVisitInput(2L, VISIT)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("encounter-not-found"));
    }

    @Test
    void aVisitAlreadyLinkedToAnotherSittingIs409AndASittingThatIsNotAPlannedOpdOneIs409() {
        courseWith(2, sitting(S1, 1, "OPD", 100_000, null, null), sitting(S2, 2, "DAY_CARE", 0, null, null));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(VISIT, PATIENT, NOW)));
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting(UUID.randomUUID(), 7, "OPD", 0,
                VISIT, null)));

        assertThatThrownBy(() -> service.linkVisit(DOCTOR, COURSE, S1, new LinkVisitInput(2L, VISIT)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("visit-already-linked"));

        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.linkVisit(DOCTOR, COURSE, S2, new LinkVisitInput(2L, VISIT)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("sitting-not-available"));
        verify(courses, never()).linkVisit(any(), any(), any(), any());
    }

    // ---- reads and statuses ----

    @Test
    void theStatusIsComputedOnReadPlannedTodayOrDoneAndTheEncounterRefIsShown() {
        UUID signedVisit = UUID.randomUUID();
        UUID todayVisit = UUID.randomUUID();
        UUID oldVisit = UUID.randomUUID();
        courseWith(1, sitting(S1, 1, "OPD", 100_000, signedVisit, null), sitting(S2, 2, "OPD", 100_000, todayVisit, null),
                sitting(UUID.randomUUID(), 3, "OPD", 100_000, null, null),
                sitting(UUID.randomUUID(), 4, "OPD", 100_000, oldVisit, null));
        when(visits.findById(TENANT, signedVisit)).thenReturn(Optional.of(visit(signedVisit, PATIENT,
                Instant.parse("2026-11-12T04:00:00Z"))));
        when(visits.findById(TENANT, todayVisit)).thenReturn(Optional.of(visit(todayVisit, PATIENT, NOW)));
        when(visits.findById(TENANT, oldVisit)).thenReturn(Optional.of(visit(oldVisit, PATIENT,
                Instant.parse("2026-11-01T04:00:00Z"))));
        when(emrRecords.findOpd(TENANT, signedVisit)).thenReturn(Optional.of(record("SIGNED", NOW)));

        CourseView view = service.get(new ClinicUser(USER, TENANT, "FRONT_DESK"), COURSE);

        assertThat(view.sittings()).extracting(s -> s.status()).containsExactly("DONE", "TODAY", "PLANNED", "PLANNED");
        assertThat(view.sittings().get(1).encounterRef()).isEqualTo("Token 12");
        assertThat(view.sittings().get(2).encounterRef()).isNull();
        assertThat(view.sittingCount()).isEqualTo(4);
        assertThat(view.doneCount()).isEqualTo(1);
        assertThat(view.accountNote()).isEqualTo("One running account: advances and part payments carry across visits.");
    }

    @Test
    void anAdmissionLinkedSittingIsSettledInIpdAndShowsTheIpNumberAndTheBillTotal() {
        courseWith(1, sitting(S1, 1, "IPD", 0, null, ADMISSION));
        when(admissionSource.admissions(eq(TENANT), any())).thenReturn(Map.of(ADMISSION,
                new CourseAdmission(ADMISSION, "IP/26-27/0012", "ADMITTED", 4_50_000)));

        CourseView view = service.get(new ClinicUser(USER, TENANT, "BILLING"), COURSE);

        CourseViews.SittingView s = view.sittings().get(0);
        assertThat(s.status()).isEqualTo("TODAY");
        assertThat(s.settledInIpd()).isTrue();
        assertThat(s.encounterRef()).isEqualTo("IP/26-27/0012");
        assertThat(s.ipdBillTotalPaise()).isEqualTo(450_000L);
        assertThat(s.admissionId()).isEqualTo(ADMISSION);
    }

    @Test
    void aDischargedAdmissionMakesTheSittingDone() {
        courseWith(1, sitting(S1, 1, "IPD", 0, null, ADMISSION));
        when(admissionSource.admissions(eq(TENANT), any())).thenReturn(Map.of(ADMISSION,
                new CourseAdmission(ADMISSION, "IP/26-27/0012", "DISCHARGED", 100_000)));

        assertThat(service.get(new ClinicUser(USER, TENANT, "OWNER"), COURSE).sittings().get(0).status())
                .isEqualTo("DONE");
    }

    @Test
    void theAddChoicesFollowTheKindAndDropAdmissionsWhenIpdIsOffAndTheFlagsFollowTheRole() {
        courseWith(1, sitting(S1, 1, "OPD", 100_000, null, null));

        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(new TeamMember(OTHER_M, "Desk",
                "FRONT_DESK", true, 0)));
        CourseView on = service.get(new ClinicUser(USER, TENANT, "FRONT_DESK"), COURSE);
        assertThat(on.addOptions()).extracting(CourseViews.AddOptionView::key).containsExactly("opd_sitting",
                "day_care_admission", "ipd_admission");
        assertThat(on.canAdmit()).isTrue();
        assertThat(on.canPlan()).isFalse();
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(doctorMember));
        assertThat(service.get(new ClinicUser(USER, TENANT, "DOCTOR"), COURSE).canPlan()).isTrue();
        assertThat(service.get(new ClinicUser(USER, TENANT, "DOCTOR"), COURSE).canAdmit()).isFalse();

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        CourseView off = service.get(new ClinicUser(USER, TENANT, "OWNER"), COURSE);
        assertThat(off.addOptions()).extracting(CourseViews.AddOptionView::key).containsExactly("opd_sitting");
        assertThat(off.canAdmit()).isFalse();
    }

    @Test
    void nurseAndPharmacistCannotReadACourseAndAnotherClinicsCourseIs404() {
        courseWith(1);
        for (String role : List.of("NURSE", "PHARMACIST", "COUNSELLOR")) {
            assertThatThrownBy(() -> service.get(new ClinicUser(USER, TENANT, role), COURSE))
                    .isInstanceOf(AccessDeniedException.class);
        }
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(new ClinicUser(USER, TENANT, "OWNER"), COURSE))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("course-not-found"));
    }

    @Test
    void theListNeedsAPatientAndShowsCountsPerCourse() {
        courseWith(1, sitting(S1, 1, "OPD", 100_000, null, null));
        when(courses.forPatient(TENANT, PATIENT, 200)).thenReturn(List.of(course(1)));

        assertThat(service.list(new ClinicUser(USER, TENANT, "OWNER"), PATIENT)).singleElement().satisfies(c -> {
            assertThat(c.courseNo()).isEqualTo("TC-0412");
            assertThat(c.sittingCount()).isEqualTo(1);
            assertThat(c.doneCount()).isZero();
        });
        assertThatThrownBy(() -> service.list(new ClinicUser(USER, TENANT, "OWNER"), null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.list(new ClinicUser(USER, TENANT, "NURSE"), PATIENT))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- what billing and ipd read ----

    @Test
    void theFactsForBillingCarryStatusSettledFlagIpNumberAndDoneTime() {
        UUID signedVisit = UUID.randomUUID();
        courseWith(1, sitting(S1, 1, "OPD", 100_000, signedVisit, null), sitting(S2, 2, "IPD", 0, null, ADMISSION));
        when(visits.findById(TENANT, signedVisit)).thenReturn(Optional.of(visit(signedVisit, PATIENT, NOW)));
        when(emrRecords.findOpd(TENANT, signedVisit)).thenReturn(Optional.of(record("SIGNED",
                Instant.parse("2026-11-12T05:30:00Z"))));
        when(admissionSource.admissions(eq(TENANT), any())).thenReturn(Map.of(ADMISSION,
                new CourseAdmission(ADMISSION, "IP/1", "ADMITTED", 10_000)));

        CourseFacts facts = service.facts(TENANT, COURSE).orElseThrow();

        assertThat(facts.courseNo()).isEqualTo("TC-0412");
        assertThat(facts.unit()).isEqualTo("SITTING");
        assertThat(facts.sittings().get(0).status()).isEqualTo("DONE");
        assertThat(facts.sittings().get(0).doneAt()).isEqualTo(Instant.parse("2026-11-12T05:30:00Z"));
        assertThat(facts.sittings().get(0).settledInIpd()).isFalse();
        assertThat(facts.sittings().get(1).settledInIpd()).isTrue();
        assertThat(facts.sittings().get(1).ipNumber()).isEqualTo("IP/1");
        assertThat(facts.toString()).doesNotContain("Dental").doesNotContain("Root");
    }

    @Test
    void aSittingIsAdmittableOnlyWhenPlannedUnlinkedAdmissionKindOfThisPatientsActiveCourse() {
        courseWith(1, sitting(S1, 1, "IPD", 0, null, null), sitting(S2, 2, "OPD", 0, null, null));

        Optional<AdmittableSitting> ok = service.admittable(TENANT, PATIENT, S1);
        assertThat(ok).isPresent();
        assertThat(ok.get().courseNo()).isEqualTo("TC-0412");
        assertThat(service.admittable(TENANT, UUID.randomUUID(), S1)).isEmpty();
        assertThat(service.admittable(TENANT, PATIENT, S2)).isEmpty();
        assertThat(service.admittable(TENANT, PATIENT, UUID.randomUUID())).isEmpty();
        courseWith(1, sitting(S1, 1, "IPD", 0, null, ADMISSION));
        assertThat(service.admittable(TENANT, PATIENT, S1)).isEmpty();
    }

    @Test
    void linkingAnAdmissionWritesTheLinkBumpsTheRevisionAndAuditsIdsOnly() {
        courseWith(1, sitting(S1, 1, "IPD", 0, null, null));

        service.linkAdmission(TENANT, USER, S1, ADMISSION);

        verify(courses).linkAdmission(TENANT, S1, ADMISSION, USER);
        verify(courses).bumpRevision(TENANT, COURSE, USER);
        verify(audit).record(eq("COURSE_SITTING_ADMISSION_LINKED"), eq("TREATMENT_COURSE"), eq(COURSE), eq(null),
                any());
    }
}
