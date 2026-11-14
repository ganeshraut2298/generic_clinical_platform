package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.CancerJourneyRepository.JourneyRow;
import com.sahaj.opd.application.emr.specialty.CancerJourneyService.JourneyView;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
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
import org.springframework.dao.DuplicateKeyException;

/**
 * SAH-75-7 (ADR-026 D7): the Cancer journey is the patient's, appended as versions from an oncology draft record. Lists and
 * limits are ours, not clinically reviewed (H6). Nothing clinical reaches an audit value or a message.
 */
class CancerJourneyServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-14T06:30:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final CancerJourneyRepository journeys = mock(CancerJourneyRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private CancerJourneyService service;

    @BeforeEach
    void setUp() {
        service = new CancerJourneyService(records, journeys, team, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        TeamMember member = new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(member));
        authorize("onco", "DRAFT", PATIENT);
    }

    private void authorize(String starter, String status, UUID patient) {
        RecordRow row = new RecordRow(RECORD, "OPD_VISIT", UUID.randomUUID(), null, null, null, patient, MEMBER, starter,
                "Oncology", starter, "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", status, null, "{}", null, null, false,
                null, 2, Instant.parse("2026-11-14T05:00:00Z"));
        when(records.authorize(eq(DOCTOR), eq(RECORD))).thenReturn(new Authorized(
                new TeamMember(MEMBER, "Dr Rao", "DOCTOR", true, 0), row, patient));
    }

    private static JourneyRow stored(int version) {
        return new JourneyRow(UUID.randomUUID(), PATIENT, version, "IIB", "cT2 cN2 M0", "ER+ PR+ HER2−", 28,
                "BRCA pending", "CURATIVE", "NEOADJUVANT", 1, LocalDate.of(2026, 7, 26), RECORD, MEMBER, USER,
                Instant.parse("2026-11-10T05:00:00Z"));
    }

    private static JsonNode body(String json) {
        try {
            return MAPPER.readTree(json.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private JourneyRow inserted() {
        ArgumentCaptor<JourneyRow> row = ArgumentCaptor.forClass(JourneyRow.class);
        verify(journeys).insert(eq(TENANT), row.capture());
        return row.getValue();
    }

    private static void assertProblem(Throwable thrown, int status, String slug) {
        assertThat(thrown).isInstanceOfSatisfying(EmrProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.slug()).isEqualTo(slug);
        });
    }

    @Test
    void theFirstSaveIsVersionOneFromTheSentValuesWithTheDoctorAndTheRecordAsSource() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty(), Optional.of(stored(1)));

        JourneyView view = service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'stageGroup':'IIB','tnmText':"
                + "'cT2 cN2 M0','ki67Pct':28,'intent':'CURATIVE','setting':'NEOADJUVANT','lineNo':1,'boardDate':"
                + "'2026-07-26'}"));

        JourneyRow row = inserted();
        assertThat(row.version()).isEqualTo(1);
        assertThat(row.patientId()).isEqualTo(PATIENT);
        assertThat(row.stageGroup()).isEqualTo("IIB");
        assertThat(row.markersText()).isNull();
        assertThat(row.sourceRecordId()).isEqualTo(RECORD);
        assertThat(row.recordedByMembershipId()).isEqualTo(MEMBER);
        assertThat(row.recordedByUserId()).isEqualTo(USER);
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.recordedByName()).isEqualTo("Dr Rao");
    }

    @Test
    void aFieldLeftOutKeepsTheEarlierValueAndAFieldSentNullClearsIt() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.of(stored(2)));

        service.save(DOCTOR, RECORD, body("{'expectedVersion':2,'intent':'PALLIATIVE','geneticsText':null}"));

        JourneyRow row = inserted();
        assertThat(row.version()).isEqualTo(3);
        assertThat(row.intent()).isEqualTo("PALLIATIVE");
        assertThat(row.geneticsText()).isNull();
        assertThat(row.stageGroup()).isEqualTo("IIB");
        assertThat(row.tnmText()).isEqualTo("cT2 cN2 M0");
        assertThat(row.boardDate()).isEqualTo(LocalDate.of(2026, 7, 26));
        assertThat(row.lineNo()).isEqualTo(1);
    }

    @Test
    void theAuditHoldsTheVersionAndTheNamesOfTheChangedFieldsNeverAValue() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.of(stored(2)));

        service.save(DOCTOR, RECORD, body("{'expectedVersion':2,'stageGroup':'IIIA','geneticsText':null,'lineNo':1}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("ONC_JOURNEY_UPDATED"), eq("ONC_JOURNEY"), eq(PATIENT), before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("version", 2);
        assertThat(after.getValue()).containsEntry("version", 3);
        assertThat(after.getValue().get("changedFields")).isEqualTo(List.of("stageGroup", "geneticsText"));
        assertThat(after.getValue().toString()).doesNotContain("IIIA").doesNotContain("BRCA");
    }

    @Test
    void aStaleVersionIs409JourneyChangedAndNothingIsWritten() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.of(stored(3)));

        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':2,'intent':'CURATIVE'}")))
                .satisfies(e -> {
                    assertProblem(e, 409, "journey-changed");
                    assertThat(e.getMessage()).isEqualTo("The journey was changed. Reload.");
                });
        verify(journeys, never()).insert(any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aRacingSaveThatLosesOnTheUniqueKeyIsAlso409JourneyChanged() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());
        when(journeys.insert(eq(TENANT), any())).thenThrow(new DuplicateKeyException("uq_emr_cancer_journey"));

        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'intent':'CURATIVE'}")))
                .satisfies(e -> assertProblem(e, 409, "journey-changed"));
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aSignedRecordIs409AndARecordThatIsNotOncologyOrHasNoPatientIs400() {
        authorize("onco", "SIGNED", PATIENT);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0}")))
                .satisfies(e -> assertProblem(e, 409, "emr-record-signed"));

        authorize("gm", "DRAFT", PATIENT);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0}")))
                .satisfies(e -> assertProblem(e, 400, "not-applicable"));

        authorize("onco", "DRAFT", null);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0}")))
                .satisfies(e -> assertProblem(e, 400, "not-applicable"));
        verify(journeys, never()).insert(any(), any());
    }

    private static List<String> fieldsOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(InvalidFieldsException.class);
        return ((InvalidFieldsException) thrown).errors().stream().map(f -> f.field()).toList();
    }

    @Test
    void valuesOutsideTheListsOrLimitsAreRefusedAsAWholeWithTheirFieldNames() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'stageGroup':'V','ki67Pct':101,"
                + "'lineNo':11,'intent':'X','setting':'X','tnmText':'" + "t".repeat(41) + "','markersText':'"
                + "m".repeat(81) + "','geneticsText':'" + "g".repeat(61) + "','boardDate':'2027-12-31'}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactlyInAnyOrder("stageGroup", "ki67Pct", "lineNo",
                        "intent", "setting", "tnmText", "markersText", "geneticsText", "boardDate"));
        verify(journeys, never()).insert(any(), any());
    }

    @Test
    void theEdgesOfTheLimitsAreAccepted() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());

        service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'stageGroup':'0','ki67Pct':100,'lineNo':10,'tnmText':'"
                + "t".repeat(40) + "','markersText':'" + "m".repeat(80) + "','geneticsText':'" + "g".repeat(60)
                + "','boardDate':'2027-11-14'}"));

        JourneyRow row = inserted();
        assertThat(row.ki67Pct()).isEqualTo(100);
        assertThat(row.lineNo()).isEqualTo(10);
        assertThat(row.stageGroup()).isEqualTo("0");
        assertThat(row.boardDate()).isEqualTo(LocalDate.of(2027, 11, 14));
    }

    @Test
    void aBoardDateMoreThanAYearAheadOrBefore1900IsRefused() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'boardDate':'2027-11-15'}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("boardDate"));
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'boardDate':'1899-12-31'}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("boardDate"));
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'boardDate':'tomorrow'}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("boardDate"));
    }

    @Test
    void theBodyIsClosedAndNeedsTheVersionYetAnIgnoredClinicFieldIsAccepted() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'intent':'CURATIVE'}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("expectedVersion"));
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':-1}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("expectedVersion"));
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'colour':'red'}")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("colour"));
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("[1]")))
                .satisfies(e -> assertThat(fieldsOf(e)).containsExactly("expectedVersion"));

        service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'tenantId':'" + UUID.randomUUID() + "','clinicId':'"
                + UUID.randomUUID() + "'}"));
        assertThat(inserted().version()).isEqualTo(1);
    }

    @Test
    void anEmptyTextIsStoredAsNullAndMarkupIsRefusedWithoutEchoingIt() {
        when(journeys.latest(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'tnmText':'<script>x'}")))
                .satisfies(e -> {
                    assertThat(fieldsOf(e)).containsExactly("tnmText");
                    assertThat(((InvalidFieldsException) e).errors().get(0).message()).doesNotContain("script");
                });

        service.save(DOCTOR, RECORD, body("{'expectedVersion':0,'markersText':'   '}"));

        assertThat(inserted().markersText()).isNull();
    }

    @Test
    void theViewNeverShowsMoreThanTheVersionInAStrayLog() {
        JourneyView view = new JourneyView(2, "IIB", "cT2", "ER+", 30, "BRCA", "CURATIVE", "ADJUVANT", 1, null, NOW, "Dr");

        assertThat(view.toString()).isEqualTo("JourneyView[version=2]");
        assertThat(stored(4).toString()).contains("version=4").doesNotContain("cT2").doesNotContain("BRCA");
    }
}
