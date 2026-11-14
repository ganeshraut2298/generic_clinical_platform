package com.sahaj.opd.application.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.api.CourseAdmissionSource;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.course.CourseInputs.CreateInput;
import com.sahaj.opd.application.course.CourseInputs.SittingPlanInput;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewCourse;
import com.sahaj.opd.application.course.TreatmentCourseRepository.NewSitting;
import com.sahaj.opd.application.emr.EmrAccess;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.DentalCoursePlanner;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-49-2, SAH-69-1 (ADR-023 D7, H11): a DENTAL course starts from the record's treatment plan: one sitting per planned
 * sitting of each open row, in plan order, the charge the row's share of the estimate (equal parts rounded to Rs 50, the
 * last takes the rest: tagged B-2, analyst reading), the note "estimate signed by patient" when the patient signed.
 */
class TreatmentCourseFromPlanTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");
    private static final String KEY = "key-0000001";

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordService records = mock(EmrRecordService.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final EmrRecordRepository emrRecords = mock(EmrRecordRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private TreatmentCourseService service;
    private TeamMember doctor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doctor = new TeamMember(DOCTOR_M, "Dr Rao", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(any())).thenReturn(doctor);
        when(team.find(TENANT, DOCTOR_M)).thenReturn(Optional.of(doctor));
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(doctor));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        ObjectProvider<CourseAdmissionSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(CourseAdmissionSource.class));
        when(courses.nextCourseNo(TENANT)).thenReturn(412);
        when(courses.insertCourse(eq(TENANT), any(), eq(USER))).thenReturn(COURSE);
        when(courses.find(TENANT, COURSE)).thenReturn(Optional.of(new CourseRow(COURSE, 412, PATIENT, "DENTAL",
                "SITTING", "Dental treatment course", null, DOCTOR_M, LocalDate.parse("2026-11-12"), "ACTIVE", 0, "h",
                NOW)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of());
        service = new TreatmentCourseService(access, records, courses, mock(VisitRepository.class), emrRecords, team,
                entitlements, provider, audit, CLOCK, new DentalCoursePlanner(emrRecords, MAPPER));
    }

    private static String row(String id, int tooth, String text, String status, int planned, int current,
                              String shares) {
        return "{\"id\":\"" + id + "\",\"tooth\":" + tooth + ",\"procedureKey\":null,\"procedureText\":\"" + text
                + "\",\"phase\":1,\"status\":\"" + status + "\",\"sittingsPlanned\":" + planned + ",\"currentSitting\":"
                + current + ",\"sittingSharesPaise\":" + shares + "}";
    }

    private String dentalSections(String rows, String state) {
        return "{\"tx_plan\":{\"values\":{\"specialty\":{\"component\":\"DENTAL_PLAN\",\"schemaVersion\":1,\"rows\":["
                + rows + "],\"estimate\":{\"state\":\"" + state + "\",\"totalPaise\":0}}},\"copiedFrom\":null,"
                + "\"copyConfirmed\":false,\"updatedAt\":\"2026-11-12T05:00:00Z\",\"updatedBy\":\"" + DOCTOR_M + "\"}}";
    }

    private RecordRow record(String starter, String sections) {
        return new RecordRow(RECORD, "OPD_VISIT", UUID.randomUUID(), null, null, null, PATIENT, DOCTOR_M, starter, "D",
                starter, "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, sections, null, null, false, null,
                2, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private void authorize(RecordRow row) {
        when(records.authorize(eq(DOCTOR), eq(RECORD))).thenReturn(new Authorized(doctor, row, PATIENT));
    }

    private static CreateInput fromPlan() {
        return new CreateInput(RECORD, "DENTAL", null, null, null, true);
    }

    @Test
    void openRowsBecomeSittingsAndTheSignedEstimateIsNotedOnTheCourse() {
        String rows = row("a1", 16, "Composite filling (O)", "PLANNED", 1, 1, "[150000]") + ","
                + row("b2", 26, "Root canal treatment", "PLANNED", 3, 1, "[150000,150000,150000]") + ","
                + row("c3", 31, "Extraction", "DONE", 1, 1, "[120000]") + ","
                + row("d4", 36, "PFM crown", "IN_PROGRESS", 3, 2, "[200000,200000,200000]");
        authorize(record("dental", dentalSections(rows, "SIGNED")));

        assertThat(service.create(DOCTOR, KEY, fromPlan()).created()).isTrue();
        ArgumentCaptor<NewCourse> course = ArgumentCaptor.forClass(NewCourse.class);
        verify(courses).insertCourse(eq(TENANT), course.capture(), eq(USER));
        assertThat(course.getValue().note()).isEqualTo("estimate signed by patient");
        assertThat(course.getValue().originRecordId()).isEqualTo(RECORD);
        ArgumentCaptor<NewSitting> sittings = ArgumentCaptor.forClass(NewSitting.class);
        verify(courses, times(6)).insertSitting(eq(TENANT), sittings.capture(), eq(USER));
        List<NewSitting> made = sittings.getAllValues();
        assertThat(made).extracting(NewSitting::sittingNo).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(made).extracting(NewSitting::addOptionKey).containsOnly("OPD_SITTING");
        assertThat(made).extracting(NewSitting::chargePaise).containsExactly(150_000L, 150_000L, 150_000L, 150_000L,
                200_000L, 200_000L);
        assertThat(made.get(0).items()).containsExactly("Composite filling (O) · tooth 16 · sitting 1 of 1");
        assertThat(made.get(1).items()).containsExactly("Root canal treatment · tooth 26 · sitting 1 of 3");
        assertThat(made.get(4).items()).as("an in-progress row at sitting 2 plans sittings 2 and 3")
                .containsExactly("PFM crown · tooth 36 · sitting 2 of 3");
        assertThat(made).extracting(NewSitting::items).noneMatch(i -> i.get(0).contains("Extraction"));
        verify(emrRecords).saveSectionsLocked(eq(TENANT), eq(RECORD), org.mockito.ArgumentMatchers.argThat(
                json -> json != null && json.contains(COURSE.toString())), eq(USER));
    }

    @Test
    void anUnsignedEstimateLeavesTheNoteEmptyAndANoteTheDoctorTypedWins() {
        authorize(record("dental", dentalSections(row("a1", 16, "Extraction", "PLANNED", 1, 1, "[120000]"),
                "NOT_SIGNED")));
        service.create(DOCTOR, KEY, fromPlan());
        ArgumentCaptor<NewCourse> course = ArgumentCaptor.forClass(NewCourse.class);
        verify(courses).insertCourse(eq(TENANT), course.capture(), eq(USER));
        assertThat(course.getValue().note()).isNull();
    }

    @Test
    void fromPlanIsRefusedWithSittingsForAnotherKindOnANonDentalRecordOrWithNothingOpen() {
        authorize(record("dental", dentalSections(row("a1", 16, "Extraction", "PLANNED", 1, 1, "[120000]"), "NOT_SIGNED")));
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(RECORD, "DENTAL", null, null,
                List.of(new SittingPlanInput("opd_sitting", null, List.of(), 0L, null)), true)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, new CreateInput(RECORD, "CHEMO", null, null, null, true)))
                .isInstanceOf(InvalidFieldsException.class);
        authorize(record("gm", dentalSections(row("a1", 16, "Extraction", "PLANNED", 1, 1, "[120000]"), "NOT_SIGNED")));
        assertThatThrownBy(() -> service.create(DOCTOR, KEY, fromPlan())).isInstanceOf(InvalidFieldsException.class);
        authorize(record("dental", dentalSections(row("a1", 16, "Extraction", "DONE", 1, 1, "[120000]"), "NOT_SIGNED")));
        assertThatThrownBy(() -> service.create(DOCTOR, "key-0000002", fromPlan())).isInstanceOf(
                InvalidFieldsException.class);
        verify(courses, never()).insertCourse(any(), any(), any());
        verify(emrRecords, never()).saveSectionsLocked(any(), any(), anyString(), any());
    }

}
