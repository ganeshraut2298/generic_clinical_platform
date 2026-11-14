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
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.course.CourseInputs.AddSittingInput;
import com.sahaj.opd.application.course.CourseInputs.ChangeSittingInput;
import com.sahaj.opd.application.course.CourseInputs.CreateInput;
import com.sahaj.opd.application.course.CourseInputs.SittingPlanInput;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewCourse;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingChange;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceData;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceRepository;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
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
 * SAH-75-3 and SAH-75-7 (ADR-026 D3, D7): the Surgery kind starts from the embedded panel, a chemotherapy sitting carries a
 * regimen (an existing protocol key, chemotherapy courses only) and a new cycle copies the previous cycle's regimen.
 */
class CourseRegimenAndKindTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-14T06:00:00Z");
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
    private final SpecialtyReferenceRepository reference = mock(SpecialtyReferenceRepository.class);
    private TreatmentCourseService service;

    @BeforeEach
    void setUp() {
        TeamMember doctor = new TeamMember(DOCTOR_M, "Dr Rao", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(any())).thenReturn(doctor);
        when(team.find(TENANT, DOCTOR_M)).thenReturn(Optional.of(doctor));
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(doctor));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(provider.getIfAvailable()).thenReturn(mock(CourseAdmissionSource.class));
        Protocol ac = new Protocol("AC", "AC", "", 21, "HIGH", false, List.of(), List.of());
        when(reference.load()).thenReturn(new SpecialtyReferenceData(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(ac)));
        service = new TreatmentCourseService(access, records, courses, visits, emrRecords, team, entitlements, provider,
                mock(AuditLog.class), Clock.fixed(NOW, ZoneOffset.UTC), null, reference);
        RecordRow row = new RecordRow(RECORD, "OPD_VISIT", UUID.randomUUID(), null, null, null, PATIENT, DOCTOR_M, "surgery",
                "Surgery", "surgery", "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, "{}", null, null, false,
                null, 2, Instant.parse("2026-11-14T05:00:00Z"));
        when(records.authorize(eq(DOCTOR), any())).thenReturn(new Authorized(doctor, row, PATIENT));
        when(courses.nextCourseNo(TENANT)).thenReturn(9);
    }

    private static CourseRow course(String kind) {
        return new CourseRow(COURSE, 9, PATIENT, kind, "STEP", "Course", null, DOCTOR_M, LocalDate.parse("2026-11-14"),
                "ACTIVE", 2, "h", Instant.parse("2026-11-14T05:00:00Z"));
    }

    private void givenCourse(String kind, SittingRow... sittings) {
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(course(kind)));
        when(courses.findForUpdate(TENANT, COURSE)).thenReturn(Optional.of(course(kind)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(sittings));
        for (SittingRow s : sittings) {
            when(courses.sittingForUpdate(TENANT, s.id())).thenReturn(Optional.of(s));
        }
    }

    private static SittingRow cycle(UUID id, int no, String regimen) {
        return new SittingRow(id, COURSE, no, "DAY_CARE", "CHEMO_CYCLE", null, List.of(), 0, null, null, null, true, null,
                null, null, null, null, regimen);
    }

    @Test
    void aSurgeryCourseStartsFromTheEmbeddedPanelWithTheKindsDefaultNameAndStepUnit() {
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        givenCourse("SURGERY");

        var result = service.create(DOCTOR, KEY, new CreateInput(RECORD, "SURGERY", null, null, null));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<NewCourse> made = ArgumentCaptor.forClass(NewCourse.class);
        verify(courses).insertCourse(eq(TENANT), made.capture(), eq(USER));
        assertThat(made.getValue().kind()).isEqualTo("SURGERY");
        assertThat(made.getValue().unit()).isEqualTo("STEP");
        assertThat(made.getValue().name()).isEqualTo("Surgery episode");
        assertThat(made.getValue().originRecordId()).isEqualTo(RECORD);
        assertThat(made.getValue().estimateSigned()).isFalse();
    }

    @Test
    void aChemoSittingStartsWithAnExistingProtocolsRegimen() {
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        givenCourse("CHEMO");

        service.create(DOCTOR, KEY, new CreateInput(RECORD, "CHEMO", null, null, List.of(
                new SittingPlanInput("chemo_cycle", LocalDate.parse("2026-11-20"), List.of(), 100_000L, null, "AC"))));

        ArgumentCaptor<NewSitting> sitting = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), sitting.capture(), eq(USER));
        assertThat(sitting.getValue().regimenKey()).isEqualTo("AC");
    }

    @Test
    void aRegimenOnAnotherKindOfCourseOrOutsideTheProtocolLibraryIs400() {
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(RECORD, "DENTAL", null, null, List.of(
                new SittingPlanInput("opd_sitting", null, List.of(), 0L, null, "AC")))))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors().get(0).field()).isEqualTo("sittings[0].regimenKey"));
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(RECORD, "CHEMO", null, null, List.of(
                new SittingPlanInput("chemo_cycle", null, List.of(), 0L, null, "NOPE")))))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors().get(0).field()).isEqualTo("sittings[0].regimenKey"));
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(RECORD, "CHEMO", null, null, List.of(
                new SittingPlanInput("chemo_cycle", null, List.of(), 0L, null, "ac")))))
                .isInstanceOf(InvalidFieldsException.class);
        verify(courses, never()).insertCourse(any(), any(), any());
    }

    @Test
    void aNewChemoCycleWithNoRegimenCopiesThePreviousCyclesRegimen() {
        givenCourse("CHEMO", cycle(S1, 1, "AC"), cycle(S2, 2, null));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(3);

        service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(2L, "chemo_cycle", null));

        ArgumentCaptor<NewSitting> sitting = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses).insertSitting(eq(TENANT), sitting.capture(), eq(USER));
        assertThat(sitting.getValue().regimenKey()).isEqualTo("AC");
    }

    @Test
    void aSentRegimenWinsOverTheCopyAndAnotherOptionNeverCopiesOne() {
        givenCourse("CHEMO", cycle(S1, 1, "AC"));
        when(courses.nextSittingNo(TENANT, COURSE)).thenReturn(2);

        service.addSitting(DOCTOR, COURSE, "key-0000002", new AddSittingInput(2L, "chemo_cycle", null, "AC"));
        service.addSitting(DOCTOR, COURSE, "key-0000003", new AddSittingInput(2L, "review_opd", null));

        ArgumentCaptor<NewSitting> sitting = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses, org.mockito.Mockito.times(2)).insertSitting(eq(TENANT), sitting.capture(), eq(USER));
        assertThat(sitting.getAllValues().get(0).regimenKey()).isEqualTo("AC");
        assertThat(sitting.getAllValues().get(1).regimenKey()).isNull();
    }

    @Test
    void anAddWithABadRegimenOrOnANonChemoCourseIs400() {
        givenCourse("CHEMO", cycle(S1, 1, null));
        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, KEY, new AddSittingInput(2L, "chemo_cycle", null,
                "NOPE"))).isInstanceOf(InvalidFieldsException.class);

        givenCourse("DENTAL");
        assertThatThrownBy(() -> service.addSitting(DOCTOR, COURSE, "key-0000002", new AddSittingInput(2L, "opd_sitting",
                null, "AC"))).isInstanceOf(InvalidFieldsException.class);
        verify(courses, never()).insertSitting(any(), any(), any());
    }

    @Test
    void changingASittingPassesTheRegimenOnAndAnAbsentOneKeepsTheStoredOne() {
        givenCourse("CHEMO", cycle(S1, 1, "AC"));

        service.changeSitting(DOCTOR, COURSE, S1, new ChangeSittingInput(2L, "DAY_CARE", null, List.of(), 0L, null, "AC"));
        service.changeSitting(DOCTOR, COURSE, S1, new ChangeSittingInput(2L, "DAY_CARE", null, List.of(), 0L, null));

        ArgumentCaptor<SittingChange> change = ArgumentCaptor.forClass(SittingChange.class);
        verify(courses, org.mockito.Mockito.times(2)).updateSitting(eq(TENANT), eq(S1), change.capture(), eq(USER));
        assertThat(change.getAllValues().get(0).regimenKey()).isEqualTo("AC");
        assertThat(change.getAllValues().get(1).regimenKey()).isNull();
    }

    @Test
    void aMissedSittingCannotBeChangedUntilItIsRebooked() {
        SittingRow missed = new SittingRow(S1, COURSE, 1, "OPD", "OPD_SITTING", LocalDate.parse("2026-11-14"), List.of(),
                0, null, null, null, false, null, null, Instant.parse("2026-11-14T05:00:00Z"), null, null, null);
        givenCourse("DENTAL", missed);

        assertThatThrownBy(() -> service.changeSitting(DOCTOR, COURSE, S1, new ChangeSittingInput(2L, "OPD", null,
                List.of(), 0L, null))).isInstanceOfSatisfying(com.sahaj.opd.application.emr.EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("sitting-not-available");
                    assertThat(e.getMessage()).isEqualTo("This entry was missed. Rebook it first.");
                });
        verify(courses, never()).updateSitting(any(), any(), any(), any());
    }

    @Test
    void aTodaySittingPlannedForTodayCanBeLinkedToTheOpenVisit() {
        SittingRow today = new SittingRow(S1, COURSE, 1, "OPD", "OPD_SITTING", LocalDate.parse("2026-11-14"), List.of(), 0,
                null, null, null, false, null, null, null, null, null, null);
        givenCourse("DENTAL", today);
        UUID visitId = UUID.randomUUID();
        when(visits.findById(TENANT, visitId)).thenReturn(Optional.of(new VisitRepository.VisitRow(visitId, PATIENT,
                DOCTOR_M, "NEW", "CONSULTING", 3, 0, Instant.parse("2026-11-14T04:00:00Z"), 0, 1)));
        when(courses.sittingByVisit(TENANT, visitId)).thenReturn(Optional.empty());

        service.linkVisit(DOCTOR, COURSE, S1, new CourseInputs.LinkVisitInput(2L, visitId));

        verify(courses).linkVisit(TENANT, S1, visitId, USER);
        assertThat(Map.of()).isEmpty();
    }
}
