package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sahaj.opd.application.emr.EmrProblemException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The specialty history (ADR-023 D2, ADR-022 D10 pattern): earlier values read on demand. Empty until SAH-51 signs
 * records, except the oncology cycles, which come from SENT orders. Flags are tagged (DEC-039, not clinically reviewed).
 */
class SpecialtyHistoryServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID EARLIER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrRecordRepository recordRepository = mock(EmrRecordRepository.class);
    private final FakeChemoOrders orders = new FakeChemoOrders();
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private SpecialtyHistoryService history;
    private String starter = "gm";
    private String sections = "{}";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SpecialtyRecordSupport support = new SpecialtyRecordSupport(() -> SpecialtyReferenceData.empty(),
                mock(ServiceRepository.class), team, patients,
                mock(TreatmentCourseRepository.class), orders, recordRepository, mock(AuditLog.class), MAPPER,
                mock(ObjectProvider.class));
        history = new SpecialtyHistoryService(records, support, orders, team, MAPPER);
        TeamMember doctor = new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0);
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(MEMBER, doctor));
        when(records.authorize(DOCTOR, RECORD)).thenAnswer(i -> new Authorized(doctor, row(), PATIENT));
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of());
    }

    private RecordRow row() {
        return new RecordRow(RECORD, "OPD_VISIT", UUID.randomUUID(), null, null, null, PATIENT, MEMBER, starter, "D",
                starter, "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null, sections, null, null, false, null, 2,
                Instant.parse("2026-11-12T05:00:00Z"));
    }

    private static ObjectNode specialty(String json) {
        try {
            ObjectNode values = MAPPER.createObjectNode();
            values.set("specialty", MAPPER.readTree(json.replace('\'', '"')));
            return values;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String doc(String section, String json) {
        EmrRecordDocument doc = EmrRecordDocument.empty(MAPPER);
        doc.put(section, specialty(json), true, NOW, MEMBER);
        return doc.toJson();
    }

    private void earlier(String section, String json) {
        when(recordRepository.signedForPatient(eq(TENANT), eq(PATIENT), anyInt())).thenReturn(List.of(new SignedCopy(
                EARLIER, Instant.parse("2026-10-20T06:00:00Z"), doc(section, json))));
    }

    @Test
    void aRecordWithoutAProfileHasNoSpecialtyHistory() {
        starter = "ortho";
        assertThatThrownBy(() -> history.get(DOCTOR, RECORD)).isInstanceOf(EmrProblemException.class)
                .satisfies(e -> assertThat(((EmrProblemException) e).slug()).isEqualTo("not-applicable"));
    }

    @Test
    void vitalsListThisRecordsEntriesFirstThenEarlierSignedOnesWithFlagsAndATrendTaggedC1() {
        sections = doc("vitals", "{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'a','bpSystolic':120,"
                + "'bpDiastolic':80,'recorded':{'at':'2026-11-12T05:10:00Z','byMembershipId':'" + MEMBER + "'}},"
                + "{'id':'b','bpSystolic':150,'bpDiastolic':95,'pulse':104,'temperature':{'value':100.0,'unit':'F'},"
                + "'recorded':{'at':'2026-11-12T05:40:00Z','byMembershipId':'" + MEMBER + "'}}]}");
        earlier("vitals", "{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'c','bpSystolic':138,"
                + "'bpDiastolic':88,'recorded':{'at':'2026-10-20T05:00:00Z','byMembershipId':'" + MEMBER + "'}}]}");

        JsonNode view = history.get(DOCTOR, RECORD);

        assertThat(view.path("profile").asText()).isEqualTo("gm");
        assertThat(view.path("someHidden").asBoolean()).isFalse();
        JsonNode vitals = view.path("vitals");
        assertThat(vitals).hasSize(3);
        assertThat(vitals.get(0).path("bp").asText()).isEqualTo("150/95");
        assertThat(vitals.get(0).path("recordedByName").asText()).isEqualTo("Dr Rao");
        assertThat(vitals.get(0).path("temperature").asText()).isEqualTo("100.0 °F");
        assertThat(vitals.get(0).path("flags")).extracting(f -> f.path("label").asText()).contains("High", "Fever");
        assertThat(vitals.get(2).path("bp").asText()).isEqualTo("138/88");
        JsonNode trend = view.path("trend");
        assertThat(trend.path("bp")).hasSize(3);
        assertThat(trend.path("bp").get(0).path("systolic").asInt()).isEqualTo(150);
        assertThat(trend.path("sugar")).isEmpty();
    }

    @Test
    void withNothingSignedYetTheHistoryIsEmptyNotAnError() {
        JsonNode view = history.get(DOCTOR, RECORD);
        assertThat(view.path("vitals")).isEmpty();
        assertThat(view.path("trend").path("bp")).isEmpty();
    }

    @Test
    void anAncVisitTableShowsThisPregnanciesEarlierVisitsAndTodaysRowLast() {
        starter = "og";
        UUID course = UUID.randomUUID();
        sections = doc("anc", "{'component':'ANC_CHECK','schemaVersion':1,'pregnancyCourseId':'" + course
                + "','today':{'weightKg':61.5,'bpSystolic':118,'bpDiastolic':76,'hbGdl':10.6},'planNote':'Next ANC at 30 weeks'}");
        earlier("anc", "{'component':'ANC_CHECK','schemaVersion':1,'pregnancyCourseId':'" + course
                + "','today':{'weightKg':60.0,'bpSystolic':110,'bpDiastolic':70,'fundalHeightCm':19}}");
        JsonNode visits = history.get(DOCTOR, RECORD).path("ancVisits");
        assertThat(visits).hasSize(2);
        assertThat(visits.get(0).path("today").asBoolean()).isFalse();
        assertThat(visits.get(0).path("date").asText()).isEqualTo("2026-10-20");
        assertThat(visits.get(0).path("fundalHeightCm").asInt()).isEqualTo(19);
        assertThat(visits.get(1).path("today").asBoolean()).isTrue();
        assertThat(visits.get(1).path("bp").asText()).isEqualTo("118/76");
        assertThat(visits.get(1).path("note").asText()).isEqualTo("Next ANC at 30 weeks");
        earlier("anc", "{'component':'ANC_CHECK','schemaVersion':1,'pregnancyCourseId':'" + UUID.randomUUID()
                + "','today':{'weightKg':58.0}}");
        assertThat(history.get(DOCTOR, RECORD).path("ancVisits")).as("another pregnancy").hasSize(1);
    }

    @Test
    void growthPointsAreEarlierSignedWeightsThenToday() {
        starter = "paeds";
        sections = doc("growth", "{'component':'GROWTH','schemaVersion':1,'weightKg':10.2,'lengthCm':80.0}");
        earlier("growth", "{'component':'GROWTH','schemaVersion':1,'weightKg':9.4,'headCircCm':46.0}");
        JsonNode points = history.get(DOCTOR, RECORD).path("growthPoints");
        assertThat(points).hasSize(2);
        assertThat(points.get(0).path("weightKg").asDouble()).isEqualTo(9.4);
        assertThat(points.get(0).path("headCircCm").asDouble()).isEqualTo(46.0);
        assertThat(points.get(1).path("weightKg").asDouble()).isEqualTo(10.2);
        assertThat(points.get(1).path("date").asText()).isEqualTo("2026-11-12");
    }

    @Test
    void growthPointsCarryTheAgeInMonthsFromTheDateOfBirthTaggedAdr024D7() {
        starter = "paeds";
        sections = doc("growth", "{'component':'GROWTH','schemaVersion':1,'weightKg':10.2}");
        earlier("growth", "{'component':'GROWTH','schemaVersion':1,'weightKg':9.4}");
        when(patients.find(TENANT, PATIENT)).thenReturn(java.util.Optional.of(new PatientClinicalPort.PatientClinical(
                PATIENT, "UH-1", "Child", 1, "F", null, null, java.time.LocalDate.of(2025, 5, 12))));
        JsonNode points = history.get(DOCTOR, RECORD).path("growthPoints");
        assertThat(points.get(0).path("ageMonths").decimalValue()).isEqualByComparingTo(new BigDecimal("17.3"));
        assertThat(points.get(1).path("ageMonths").decimalValue()).isEqualByComparingTo(new BigDecimal("18.0"));
        assertThat(points.toString()).doesNotContain("2025-05-12");
    }

    @Test
    void growthPointsHaveNoAgeWithoutADateOfBirthOrBeforeItAndAreNeverMadeUpFromTheStoredAge() {
        starter = "paeds";
        sections = doc("growth", "{'component':'GROWTH','schemaVersion':1,'weightKg':10.2}");
        earlier("growth", "{'component':'GROWTH','schemaVersion':1,'weightKg':9.4}");
        when(patients.find(TENANT, PATIENT)).thenReturn(java.util.Optional.of(new PatientClinicalPort.PatientClinical(
                PATIENT, "UH-1", "Child", 1, "F", null, null, null)));
        JsonNode points = history.get(DOCTOR, RECORD).path("growthPoints");
        assertThat(points).hasSize(2).allSatisfy(p -> assertThat(p.path("ageMonths").isNull()).isTrue());
        when(patients.find(TENANT, PATIENT)).thenReturn(java.util.Optional.of(new PatientClinicalPort.PatientClinical(
                PATIENT, "UH-1", "Child", 1, "F", null, null, java.time.LocalDate.of(2026, 10, 25))));
        JsonNode beforeBirth = history.get(DOCTOR, RECORD).path("growthPoints");
        assertThat(beforeBirth.get(0).path("ageMonths").isNull()).as("the earlier point is before the birth").isTrue();
        assertThat(beforeBirth.get(1).path("ageMonths").isNull()).isFalse();
    }

    @Test
    void physioSaysOnlyTheFirstVisitIsRecordedUntilThereIsAnEarlierSession() {
        starter = "physio";
        JsonNode first = history.get(DOCTOR, RECORD).path("physioProgress");
        assertThat(first.path("onlyFirstVisit").asBoolean()).isTrue();
        assertThat(first.path("sessionsDone").asInt()).isZero();
        earlier("pain", "{'component':'PAIN_SCORE','schemaVersion':1,'score':7}");
        JsonNode later = history.get(DOCTOR, RECORD).path("physioProgress");
        assertThat(later.path("onlyFirstVisit").asBoolean()).isFalse();
        assertThat(later.path("painFirst").asInt()).isEqualTo(7);
        assertThat(later.path("sessionsDone").asInt()).isEqualTo(1);
    }

    @Test
    void oncologyCyclesComeFromSentOrdersWithTheirDoseLevelAndWeightEvenBeforeAnySigning() {
        starter = "onco";
        ChemoOrderRow sent = new ChemoOrderRow();
        sent.id = UUID.randomUUID();
        sent.recordId = UUID.randomUUID();
        sent.patientId = PATIENT;
        sent.status = "SENT";
        sent.protocolKey = "AC";
        sent.levelPct = 90;
        sent.cycleNo = 1;
        sent.sentAt = Instant.parse("2026-10-22T05:00:00Z");
        sent.sentBy = USER;
        sent.snapshot = "{\"weightKg\":60.0,\"bsa\":{\"used\":1.63}}";
        sent.inputs = "{}";
        sent.fingerprint = "a".repeat(64);
        sent.prescriberMembershipId = MEMBER;
        sent.lastEditedByMembershipId = MEMBER;
        sent.confirmedAt = sent.sentAt;
        sent.confirmedFingerprint = sent.fingerprint;
        sent.confirmedByMembershipId = UUID.randomUUID();
        sent.checkerMembershipId = sent.confirmedByMembershipId;
        sent.confirmedByUserId = UUID.randomUUID();
        sent.confirmedVia = "SIGN_IN";
        sent.reductionReasonKey = "FRAILTY";
        sent.doxorubicinMgM2 = BigDecimal.valueOf(54);
        sent.priorOutsideMgM2 = BigDecimal.ZERO;
        orders.rows.put(sent.id, sent);

        JsonNode cycles = history.get(DOCTOR, RECORD).path("oncologyCycles");

        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).path("cycleNo").asInt()).isEqualTo(1);
        assertThat(cycles.get(0).path("date").asText()).isEqualTo("2026-10-22");
        assertThat(cycles.get(0).path("protocolKey").asText()).isEqualTo("AC");
        assertThat(cycles.get(0).path("levelPct").asInt()).isEqualTo(90);
        assertThat(cycles.get(0).path("weightKg").asDouble()).isEqualTo(60.0);
        assertThat(cycles.get(0).path("bsaUsed").asDouble()).isEqualTo(1.63);
        assertThat(cycles.get(0).path("grades").isNull()).as("grades come from signed records").isTrue();
    }
}
