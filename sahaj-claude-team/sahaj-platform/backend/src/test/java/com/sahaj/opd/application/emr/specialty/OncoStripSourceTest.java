package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.api.CourseAdmissionSource;
import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.specialty.CancerJourneyRepository.JourneyRow;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Cycle;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.OncoExtra;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-75-7 and SAH-75-11 (ADR-026 D7): the facts of the oncology strip, read from the patient's journey, the newest ACTIVE
 * chemotherapy course (cycle states from the same function as the course view), the SENT orders and ipd's bed label.
 */
class OncoStripSourceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-14T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private final CancerJourneyRepository journeys = mock(CancerJourneyRepository.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CourseAdmissionSource> provider = mock(ObjectProvider.class);
    private final CourseAdmissionSource admissions = mock(CourseAdmissionSource.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final FakeChemoOrders orders = new FakeChemoOrders();
    private OncoStripSource source;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(admissions);
        when(admissions.admissions(eq(TENANT), any())).thenReturn(Map.of());
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0)));
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
        when(records.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
        source = new OncoStripSource(journeys, courses, visits, records, provider, team, orders, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RecordRow row(UUID patient) {
        return new RecordRow(RECORD, "OPD_VISIT", UUID.randomUUID(), null, null, null, patient, MEMBER, "onco", "Oncology",
                "onco", "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, "{}", null, null, false, null, 2,
                Instant.parse("2026-11-14T05:00:00Z"));
    }

    private static CourseRow course(String kind, String status) {
        return new CourseRow(COURSE, 7, PATIENT, kind, "CYCLE", "Chemotherapy course", null, MEMBER,
                LocalDate.of(2026, 9, 1), status, 4, "h", Instant.parse("2026-09-01T05:00:00Z"));
    }

    private static SittingRow cycle(int no, String regimen, LocalDate planned, UUID visit, UUID admission, Instant doneAt,
                                    Instant missedAt, String optionKey) {
        return new SittingRow(UUID.randomUUID(), COURSE, no, "DAY_CARE", optionKey, planned, List.of(), 0, null, visit,
                admission, true, doneAt, null, missedAt, null, doneAt == null ? null : "SIGN", regimen);
    }

    private ChemoOrderRow sent(UUID record, String dox, String outside) {
        ChemoOrderRow order = new ChemoOrderRow();
        order.id = UUID.randomUUID();
        order.recordId = record;
        order.patientId = PATIENT;
        order.status = "SENT";
        order.protocolKey = "AC";
        order.doxorubicinMgM2 = new BigDecimal(dox);
        order.priorOutsideMgM2 = new BigDecimal(outside);
        order.sentAt = Instant.parse("2026-10-01T05:00:00Z");
        order.snapshot = "{}";
        orders.rows.put(order.id, order);
        return order;
    }

    @Test
    void aRecordWithNoPatientHasNoJourneyPlanWatchOrBedButKeepsItsOwnDiagnosis() {
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);

        OncoExtra extra = source.load(TENANT, row(null), document);

        assertThat(extra.journey()).isNull();
        assertThat(extra.plan()).isNull();
        assertThat(extra.doxorubicinGivenMgM2()).isNull();
        assertThat(extra.dayCareBed()).isNull();
    }

    @Test
    void theLatestJourneyVersionIsCarriedWithTheEditorsNameFromTheTeamDirectory() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.of(new JourneyRow(UUID.randomUUID(), PATIENT, 3, "IIB",
                "cT2 cN2 M0", null, 28, null, "CURATIVE", "NEOADJUVANT", 1, LocalDate.of(2026, 7, 26), RECORD, MEMBER,
                UUID.randomUUID(), Instant.parse("2026-11-10T05:00:00Z"))));

        OncoExtra extra = source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER));

        assertThat(extra.journey().version()).isEqualTo(3);
        assertThat(extra.journey().stageGroup()).isEqualTo("IIB");
        assertThat(extra.journey().recordedByName()).isEqualTo("Dr Rao");
        assertThat(extra.journey().boardDate()).isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void thePlanIsTheNewestActiveChemoCourseWithOnlyItsCycleSittingsAndTheirStates() {
        UUID todayVisit = UUID.randomUUID();
        SittingRow given = cycle(1, "AC", LocalDate.of(2026, 10, 3), null, null, Instant.parse("2026-10-03T05:00:00Z"), null,
                "CHEMO_CYCLE");
        SittingRow today = cycle(2, "AC", LocalDate.of(2026, 11, 14), todayVisit, null, null, null, "CHEMO_CYCLE");
        SittingRow missed = cycle(3, "AC", LocalDate.of(2026, 11, 28), null, null, null,
                Instant.parse("2026-11-12T05:00:00Z"), "CHEMO_CYCLE");
        SittingRow planned = cycle(4, null, LocalDate.of(2026, 12, 12), null, null, null, null, "CHEMO_CYCLE");
        SittingRow review = cycle(5, null, LocalDate.of(2026, 12, 20), null, null, null, null, "REVIEW_OPD");
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(course("DENTAL", "ACTIVE"),
                course("CHEMO", "ACTIVE")));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(given, today, missed, planned, review));
        when(visits.findById(TENANT, todayVisit)).thenReturn(Optional.of(new VisitRow(todayVisit, PATIENT, MEMBER, "NEW",
                "CONSULTING", 1, 0, Instant.parse("2026-11-14T04:00:00Z"), 0, 1)));

        OncoExtra extra = source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER));

        assertThat(extra.plan().courseId()).isEqualTo(COURSE);
        assertThat(extra.plan().courseName()).isEqualTo("Chemotherapy course");
        assertThat(extra.plan().cycles()).extracting(Cycle::state).containsExactly("GIVEN", "TODAY", "MISSED",
                "PLANNED");
        assertThat(extra.plan().cycles()).extracting(Cycle::regimenKey).containsExactly("AC", "AC", "AC", null);
    }

    @Test
    void aCompletedOrOtherKindOfCourseGivesNoPlan() {
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(course("CHEMO", "COMPLETED"),
                course("REHAB", "ACTIVE")));

        assertThat(source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER)).plan()).isNull();
    }

    @Test
    void theBedLabelComesFromIpdForTodaysCycleLinkedToAnAdmissionThatIsNotDischarged() {
        SittingRow today = cycle(1, "AC", null, null, ADMISSION, null, null, "CHEMO_CYCLE");
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(course("CHEMO", "ACTIVE")));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(today));
        when(admissions.admissions(eq(TENANT), any())).thenReturn(Map.of(ADMISSION,
                new CourseAdmission(ADMISSION, "IP/26-27/0044", "ADMITTED", 0, "D-4")));

        OncoExtra extra = source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER));

        assertThat(extra.dayCareBed()).isEqualTo("D-4");
        assertThat(extra.plan().cycles().get(0).state()).isEqualTo("TODAY");
    }

    @Test
    void aDischargedAdmissionIsGivenAndShowsNoBed() {
        SittingRow linked = cycle(1, "AC", null, null, ADMISSION, null, null, "CHEMO_CYCLE");
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(course("CHEMO", "ACTIVE")));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(linked));
        when(admissions.admissions(eq(TENANT), any())).thenReturn(Map.of(ADMISSION,
                new CourseAdmission(ADMISSION, "IP/26-27/0044", "DISCHARGED", 0, "D-4")));

        OncoExtra extra = source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER));

        assertThat(extra.dayCareBed()).isNull();
        assertThat(extra.plan().cycles().get(0).state()).isEqualTo("GIVEN");
    }

    @Test
    void theWatchChipIsTheOutsideDoseOfTheLatestSentOrderPlusTheSentOrdersWithoutThisRecordsOwn() {
        sent(UUID.randomUUID(), "60.0", "180.0");
        sent(UUID.randomUUID(), "60.0", "180.0");
        sent(RECORD, "60.0", "180.0");

        OncoExtra extra = source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER));

        // 180 outside (latest order) + 60 + 60 of the two other orders; this record's own 60 is today's cycle
        assertThat(extra.doxorubicinGivenMgM2()).isEqualTo(300.0);
    }

    @Test
    void withNoSentOrderTheWatchChipIsNullAndASingleOwnSentOrderShowsOnlyTheOutsideDose() {
        assertThat(source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER)).doxorubicinGivenMgM2()).isNull();

        sent(RECORD, "60.0", "180.0");

        assertThat(source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER)).doxorubicinGivenMgM2())
                .isEqualTo(180.0);
    }

    // ---- the diagnosis text ----

    private static String sections(String text, boolean oncology) {
        return "{" + (oncology ? "\"onc_regimen\":{\"values\":{}}," : "") + "\"diagnosis\":{\"values\":{\"dx\":{"
                + "\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\",\"items\":[{\"text\":\"" + text
                + "\",\"active\":false},{\"text\":\"" + text + " (active)\",\"active\":true}]}}}}";
    }

    private static EmrRecordDocument document(String text, boolean oncology) {
        return EmrRecordDocument.parse(MAPPER, sections(text, oncology));
    }

    @Test
    void theDiagnosisIsThisRecordsFirstActiveItem() {
        OncoExtra extra = source.load(TENANT, row(PATIENT), document("Carcinoma left breast", true));

        assertThat(extra.diagnosisText()).isEqualTo("Carcinoma left breast (active)");
    }

    @Test
    void withNoneOnThisRecordItIsTheLatestSignedOncologyRecordsAndAGeneralRecordIsSkipped() {
        when(records.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                new SignedCopy(UUID.randomUUID(), Instant.parse("2026-11-01T05:00:00Z"), sections("Hypertension", false)),
                new SignedCopy(UUID.randomUUID(), Instant.parse("2026-10-01T05:00:00Z"), sections("Carcinoma", true))));

        OncoExtra extra = source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER));

        assertThat(extra.diagnosisText()).isEqualTo("Carcinoma (active)");
    }

    @Test
    void noActiveDiagnosisAnywhereLeavesItNull() {
        assertThat(source.load(TENANT, row(PATIENT), EmrRecordDocument.empty(MAPPER)).diagnosisText()).isNull();
    }
}
