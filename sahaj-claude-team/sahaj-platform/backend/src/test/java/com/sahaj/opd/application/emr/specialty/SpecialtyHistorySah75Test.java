package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-75-11 (ADR-026 D10): the specialty history gains earlier work per tooth (Dental), the pain score by session
 * (Physiotherapy) and the lesion sizes of each oncology cycle. All read from SIGNED records; empty until they are signed.
 */
class SpecialtyHistorySah75Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-14T06:30:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrRecordRepository recordRepository = mock(EmrRecordRepository.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final FakeChemoOrders orders = new FakeChemoOrders();
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private SpecialtyHistoryService history;
    private String starter = "dental";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SpecialtyRecordSupport support = new SpecialtyRecordSupport(() -> SpecialtyReferenceData.empty(),
                mock(ServiceRepository.class), team, patients, courses, orders, recordRepository, mock(AuditLog.class),
                MAPPER, mock(ObjectProvider.class));
        history = new SpecialtyHistoryService(records, support, orders, team, MAPPER);
        TeamMember doctor = new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0);
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(MEMBER, doctor));
        when(records.authorize(DOCTOR, RECORD)).thenAnswer(i -> new Authorized(doctor, row(RECORD, null), PATIENT));
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
    }

    private RecordRow row(UUID id, UUID visit) {
        return new RecordRow(id, "OPD_VISIT", visit == null ? UUID.randomUUID() : visit, null, null, null, PATIENT, MEMBER,
                starter, "D", starter, "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, "{}", null, null, false,
                null, 2, Instant.parse("2026-11-14T05:00:00Z"));
    }

    private static String doc(String section, String json) {
        try {
            ObjectNode values = MAPPER.createObjectNode();
            values.set("specialty", MAPPER.readTree(json.replace('\'', '"')));
            EmrRecordDocument doc = EmrRecordDocument.empty(MAPPER);
            doc.put(section, values, true, NOW, MEMBER);
            return doc.toJson();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ---- Dental: earlier work per tooth ----

    private static String planRow(int tooth, String text, String status, int planned, int current, String doneOn) {
        return "{'id':'" + UUID.randomUUID() + "','tooth':" + tooth + ",'procedureText':'" + text + "','status':'"
                + status + "','sittingsPlanned':" + planned + ",'currentSitting':" + current + ",'doneOn':"
                + (doneOn == null ? "null" : "'" + doneOn + "'") + "}";
    }

    @Test
    void theToothHistoryListsOnlyDonePlanRowsOfSignedDentalRecordsNewestFirstWithTheSittingWhenThereAreSeveral() {
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                new SignedCopy(UUID.randomUUID(), Instant.parse("2026-11-01T06:00:00Z"), doc("tx_plan",
                        "{'component':'DENTAL_PLAN','schemaVersion':1,'rows':[" + planRow(36, "Root canal treatment", "DONE",
                                3, 2, "2026-11-01") + "," + planRow(16, "Composite filling", "PLANNED", 1, 1, null) + "]}")),
                new SignedCopy(UUID.randomUUID(), Instant.parse("2026-10-10T06:00:00Z"), doc("tx_plan",
                        "{'component':'DENTAL_PLAN','schemaVersion':1,'rows':[" + planRow(36, "Composite filling", "DONE", 1, 1,
                                null) + "]}"))));

        JsonNode view = history.get(DOCTOR, RECORD);

        JsonNode list = view.path("dentalToothHistory");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("tooth").asText()).isEqualTo("36");
        assertThat(list.get(0).path("date").asText()).isEqualTo("2026-11-01");
        assertThat(list.get(0).path("text").asText()).isEqualTo("Root canal treatment · sitting 2 of 3");
        assertThat(list.get(1).path("text").asText()).isEqualTo("Composite filling");
        assertThat(list.get(1).path("date").asText()).isEqualTo("2026-10-10");
    }

    @Test
    void withNoSignedDentalRecordTheToothHistoryIsAnEmptyListAndOtherProfilesAlsoGetAnEmptyOne() {
        assertThat(history.get(DOCTOR, RECORD).path("dentalToothHistory")).isEmpty();

        starter = "gm";
        JsonNode view = history.get(DOCTOR, RECORD);

        assertThat(view.has("dentalToothHistory")).isTrue();
        assertThat(view.path("dentalToothHistory")).isEmpty();
    }

    @Test
    void aToothNumberOutsideTheFdiPatternIsNotListed() {
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                new SignedCopy(UUID.randomUUID(), Instant.parse("2026-11-01T06:00:00Z"), doc("tx_plan",
                        "{'component':'DENTAL_PLAN','schemaVersion':1,'rows':[" + planRow(90, "Extraction", "DONE", 1, 1,
                                null) + "]}"))));

        assertThat(history.get(DOCTOR, RECORD).path("dentalToothHistory")).isEmpty();
    }

    // ---- Physiotherapy: pain by session ----

    private static SittingRow session(int no, UUID visit) {
        return new SittingRow(UUID.randomUUID(), COURSE, no, "OPD", "EXTRA_SESSION", null, List.of(), 0, null, visit, null,
                false, null, null);
    }

    @Test
    void thePainScoresOfEarlierSignedSessionsAreListedByTheNumberShownForThatSession() {
        starter = "physio";
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(new CourseRow(COURSE, 3, PATIENT,
                "REHAB", "SESSION", "Rehab package", null, MEMBER, LocalDate.parse("2026-10-01"), "ACTIVE", 0, "h", NOW)));
        // session 2 of the stored numbers was removed from the plan, so the sittings are shown as sessions 1 and 2
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(session(1, v1), session(3, v2),
                session(4, UUID.randomUUID())));
        when(recordRepository.findById(TENANT, r1)).thenReturn(Optional.of(row(r1, v1)));
        when(recordRepository.findById(TENANT, r2)).thenReturn(Optional.of(row(r2, v2)));
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                new SignedCopy(r2, Instant.parse("2026-11-10T06:00:00Z"), doc("pain",
                        "{'component':'PAIN_SCORE','schemaVersion':1,'score':6}")),
                new SignedCopy(r1, Instant.parse("2026-11-03T06:00:00Z"), doc("pain",
                        "{'component':'PAIN_SCORE','schemaVersion':1,'score':7}"))));

        JsonNode progress = history.get(DOCTOR, RECORD).path("physioProgress");

        assertThat(progress.path("painBySession")).hasSize(2);
        assertThat(progress.path("painBySession").get(0).path("sessionNo").asInt()).isEqualTo(1);
        assertThat(progress.path("painBySession").get(0).path("score").asInt()).isEqualTo(7);
        assertThat(progress.path("painBySession").get(1).path("sessionNo").asInt()).isEqualTo(2);
        assertThat(progress.path("painBySession").get(1).path("score").asInt()).isEqualTo(6);
    }

    @Test
    void withNoEarlierSessionTheListIsEmptyNeverMissing() {
        starter = "physio";

        JsonNode progress = history.get(DOCTOR, RECORD).path("physioProgress");

        assertThat(progress.has("painBySession")).isTrue();
        assertThat(progress.path("painBySession")).isEmpty();
        assertThat(progress.path("onlyFirstVisit").asBoolean()).isTrue();
    }

    @Test
    void aRecordThatIsNotASittingOfTheActiveRehabCourseIsLeftOut() {
        starter = "physio";
        UUID r1 = UUID.randomUUID();
        when(courses.forPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(new CourseRow(COURSE, 3, PATIENT,
                "REHAB", "SESSION", "Rehab package", null, MEMBER, LocalDate.parse("2026-10-01"), "ACTIVE", 0, "h", NOW)));
        when(courses.sittingsOf(eq(TENANT), any())).thenReturn(List.of(session(1, UUID.randomUUID())));
        when(recordRepository.findById(TENANT, r1)).thenReturn(Optional.of(row(r1, null)));
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                new SignedCopy(r1, Instant.parse("2026-11-03T06:00:00Z"), doc("pain",
                        "{'component':'PAIN_SCORE','schemaVersion':1,'score':7}"))));

        assertThat(history.get(DOCTOR, RECORD).path("physioProgress").path("painBySession")).isEmpty();
    }

    // ---- Oncology: lesions of each cycle ----

    private ChemoOrderRow sent(UUID record, int cycle) {
        ChemoOrderRow order = new ChemoOrderRow();
        order.id = UUID.randomUUID();
        order.recordId = record;
        order.patientId = PATIENT;
        order.status = "SENT";
        order.protocolKey = "AC";
        order.cycleNo = cycle;
        order.doxorubicinMgM2 = BigDecimal.ZERO;
        order.priorOutsideMgM2 = BigDecimal.ZERO;
        order.sentAt = Instant.parse("2026-10-0" + cycle + "T05:00:00Z");
        order.snapshot = "{}";
        orders.rows.put(order.id, order);
        return order;
    }

    @Test
    void eachCycleCarriesTheLesionSizesOfItsSignedExaminationAndAnEmptyListWhenThereIsNone() {
        starter = "onco";
        UUID signedRecord = UUID.randomUUID();
        sent(signedRecord, 1);
        sent(UUID.randomUUID(), 2);
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(
                new SignedCopy(signedRecord, Instant.parse("2026-10-01T06:00:00Z"), doc("onc_bsa",
                        "{'component':'ONC_EXAM','schemaVersion':1,'lesions':[{'id':'a','name':'Left breast mass',"
                                + "'baselineCm':4.0,'todayCm':3.2},{'id':'b','name':'Axillary node','baselineCm':2.0,"
                                + "'todayCm':null}]}"))));

        JsonNode cycles = history.get(DOCTOR, RECORD).path("oncologyCycles");

        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(0).path("lesions")).hasSize(2);
        assertThat(cycles.get(0).path("lesions").get(0).path("name").asText()).isEqualTo("Left breast mass");
        assertThat(cycles.get(0).path("lesions").get(0).path("cm").asDouble()).isEqualTo(3.2);
        assertThat(cycles.get(0).path("lesions").get(1).path("cm").isNull()).isTrue();
        assertThat(cycles.get(1).has("lesions")).isTrue();
        assertThat(cycles.get(1).path("lesions")).isEmpty();
    }
}
