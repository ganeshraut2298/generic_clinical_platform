package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.EmrVitalsSource;
import com.sahaj.opd.api.EmrVitalsSource.VitalsReading;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrInputs.PrefillInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordSectionInput;
import com.sahaj.opd.application.emr.EmrInputs.UiStateInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrViews.DepartmentRef;
import com.sahaj.opd.application.emr.EmrViews.RecordSection;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
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
 * SAH-68-1 and SAH-51-6 (ADR-025 D6, D12; docs/api/opd.yaml 0.6.0): the nurse-vitals stamp holds the recorder's membership and
 * is kept only while every vitals field the section can hold still equals the nurse's entry; the answer carries it as the
 * section's {@code prefilledFrom} with the recorder's name; the old follow-up values of phase 2 are read-only
 * {@code legacyValues}. All values are made-up test data.
 */
@SuppressWarnings("unchecked")
class EmrRecordServiceNurseVitalsAndLegacyTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID NURSE_USER = UUID.randomUUID();
    private static final UUID NURSE_MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();
    private static final LocalDate ROUND_DAY = LocalDate.of(2026, 11, 12);
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Instant RECORDED = Instant.parse("2026-11-12T03:15:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final EmrDepartmentResolver resolver = mock(EmrDepartmentResolver.class);
    private final EmrLayouts layouts = mock(EmrLayouts.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final EmrEncounterSource encounters = mock(EmrEncounterSource.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final EmrFinishRepository finish = mock(EmrFinishRepository.class);
    private final EmrVitalsSource vitalsSource = mock(EmrVitalsSource.class);
    private EmrRecordService service;
    private List<LayoutSection> layout;

    @BeforeEach
    void setUp() {
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = BigDecimal.valueOf(300);
        LayoutSection vitals = EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, false, false,
                EmrTestData.number("bp_systolic", low, high), EmrTestData.number("bp_diastolic", low, high),
                EmrTestData.number("pulse", low, high), EmrTestData.number("spo2", low, high),
                EmrTestData.number("temperature", low, high));
        LayoutSection followUp = EmrTestData.section("followup", "COMMON", "PLAN", true, false, false,
                EmrTestData.field("next_visit", "DATE", false)
                        .asComponent(new ComponentRef(EmrComponentBinding.FOLLOW_UP, null)),
                EmrTestData.field("reason", "TEXT", false).asCovered());
        layout = List.of(vitals, followUp);
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        TeamMember nurse = new TeamMember(NURSE_MEMBER, "Asha Nurse", "NURSE", true, 0);
        when(team.find(TENANT, NURSE_MEMBER)).thenReturn(Optional.of(nurse));
        when(team.findActiveByUserId(TENANT, NURSE_USER)).thenReturn(Optional.of(nurse));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        Department gm = new Department(null, "gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of("Room 2"), 0, 0, null);
        when(resolver.choose(TENANT, MEMBER)).thenReturn(new Resolution(gm, new Published("STARTER", null, null, 1, layout),
                "DOCTOR_SPECIALTY"));
        when(resolver.switchOptions(TENANT)).thenReturn(List.of(new DepartmentRef("gm", "General Medicine")));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1, layout));
        when(layouts.find(TENANT, "gm")).thenReturn(Optional.of(gm));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil", 41,
                "F", null, null)));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, MEMBER, "NEW",
                "WITH_DOCTOR", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1)));
        when(visits.previousVisitAt(eq(TENANT), eq(PATIENT), any())).thenReturn(Optional.empty());
        when(visits.countVisitsUpTo(eq(TENANT), eq(PATIENT), any())).thenReturn(1);
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(new AdmissionFacts(ADMISSION, PATIENT,
                "IP/26-27/0001", "ADMITTED", 2, MEMBER, Access.ALLOWED)));
        when(finish.state(eq(TENANT), eq(RECORD))).thenReturn(Optional.of(new RecordState(RECORD, "DRAFT", null, null, null,
                null, null, MEMBER, null, NOW.plusSeconds(100), true, null)));
        when(finish.takeHold(eq(TENANT), eq(RECORD), eq(MEMBER), eq(USER), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        ObjectProvider<EmrVisitChargePort> charges = mock(ObjectProvider.class);
        ObjectProvider<EmrVitalsSource> vitals$ = mock(ObjectProvider.class);
        when(vitals$.getIfAvailable()).thenReturn(vitalsSource);
        service = new EmrRecordService(access, records, resolver, layouts, visits, mock(VisitService.class), patients, team,
                encounters, entitlements, audit, EmrTestData.MAPPER, Clock.fixed(NOW, ZoneOffset.UTC),
                new EmrComponentValues(EmrTestData.MAPPER), mock(EmrMedicineRepository.class), mock(ServiceLookupPort.class),
                charges, mock(com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport.class), finish, vitals$,
                mock(ObjectProvider.class));
    }

    private static RecordRow record(String type, String sectionsJson) {
        boolean ipd = "IPD_ROUND".equals(type);
        return new RecordRow(RECORD, type, ipd ? null : VISIT, ipd ? ADMISSION : null, ipd ? ROUND_DAY : null, null,
                PATIENT, MEMBER, "gm", "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "DRAFT",
                null, sectionsJson, "ASSESSMENT", null, false, null, 2, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private static ObjectNode nurseValues() {
        ObjectNode values = EmrTestData.MAPPER.createObjectNode();
        values.put("bp_systolic", 120);
        values.put("bp_diastolic", 80);
        values.put("pulse", 72);
        values.put("spo2", 98);
        values.put("temperature", 98.6);
        return values;
    }

    private static VitalsReading reading() {
        return new VitalsReading(ENTRY, NURSE_USER, RECORDED, 120, 80, 72, 98, new BigDecimal("37.0"), 16);
    }

    private String savedSections(ObjectNode values) {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("IPD_ROUND",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        when(vitalsSource.latestNursing(TENANT, ADMISSION, ROUND_DAY)).thenReturn(Optional.of(reading()));
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);
        RecordSectionInput section = new RecordSectionInput("vitals", values, null,
                new PrefillInput("IPD_NURSING_CHART", ENTRY));

        service.saveDraft(DOCTOR, RECORD, new RecordDraftInput(2L, new UiStateInput("ASSESSMENT", null), List.of(section),
                null, "MANUAL"));

        ArgumentCaptor<String> sections = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(2L), sections.capture(), anyString(), any(), eq(USER));
        return sections.getValue();
    }

    // ---- the stamp ----

    @Test
    void theStampHoldsTheRecordersMembershipAndTheEntryNotAUserId() throws Exception {
        String stored = savedSections(nurseValues());

        JsonNode stamp = EmrTestData.MAPPER.readTree(stored).path("vitals").path("values").path("prefilledFrom");
        assertThat(stamp.path("source").asText()).isEqualTo("IPD_NURSING_CHART");
        assertThat(stamp.path("entryId").asText()).isEqualTo(ENTRY.toString());
        assertThat(stamp.path("recordedByMembershipId").asText()).isEqualTo(NURSE_MEMBER.toString());
        assertThat(stamp.has("recordedByUserId")).isFalse();
        assertThat(stamp.path("recordedAt").asText()).isEqualTo(RECORDED.toString());
    }

    @Test
    void anyChangedFieldMeansNoStamp() throws Exception {
        for (String field : List.of("bp_systolic", "bp_diastolic", "pulse", "spo2")) {
            ObjectNode values = nurseValues();
            values.put(field, 121);
            assertThat(EmrTestData.MAPPER.readTree(savedSectionsFresh(values)).path("vitals").path("values")
                    .has("prefilledFrom")).as(field).isFalse();
        }
        ObjectNode temperature = nurseValues();
        temperature.put("temperature", 99.1);
        assertThat(EmrTestData.MAPPER.readTree(savedSectionsFresh(temperature)).path("vitals").path("values")
                .has("prefilledFrom")).isFalse();
        ObjectNode cleared = nurseValues();
        cleared.remove("temperature");
        assertThat(EmrTestData.MAPPER.readTree(savedSectionsFresh(cleared)).path("vitals").path("values")
                .has("prefilledFrom")).isFalse();
    }

    private String savedSectionsFresh(ObjectNode values) {
        org.mockito.Mockito.clearInvocations(records);
        return savedSections(values);
    }

    @Test
    void theAnswerHasTheStampAsTheSectionsPrefilledFromWithTheRecordersNameAndNotInTheValues() {
        String stored = savedSections(nurseValues());
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("IPD_ROUND", stored)));

        RecordView view = service.get(DOCTOR, RECORD);

        RecordSection vitals = view.sections().stream().filter(s -> "vitals".equals(s.key())).findFirst().orElseThrow();
        assertThat(vitals.prefilledFrom()).isNotNull();
        assertThat(vitals.prefilledFrom().source()).isEqualTo("IPD_NURSING_CHART");
        assertThat(vitals.prefilledFrom().sourceEntryId()).isEqualTo(ENTRY);
        assertThat(vitals.prefilledFrom().recordedByName()).isEqualTo("Asha Nurse");
        assertThat(vitals.prefilledFrom().recordedAt()).isEqualTo(RECORDED);
        assertThat(vitals.values().has("prefilledFrom")).isFalse();
        assertThat(vitals.values().path("pulse").asInt()).isEqualTo(72);
        assertThat(vitals.prefilledFrom().toString()).doesNotContain("Asha");
    }

    // ---- sameVitals ----

    private LayoutSection vitalsLayout(String... extraKeys) {
        java.util.List<EmrModel.FieldDefinition> fields = new java.util.ArrayList<>(layout.get(0).fields());
        for (String key : extraKeys) {
            fields.add(EmrTestData.number(key, BigDecimal.ZERO, BigDecimal.valueOf(100)));
        }
        return EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, false, false, fields.toArray(
                new EmrModel.FieldDefinition[0]));
    }

    @Test
    void everyFieldTheSectionCanHoldIsCompared() {
        LayoutSection plain = vitalsLayout();
        assertThat(EmrRecordService.sameVitals(nurseValues(), plain, reading())).isTrue();

        // °C 37.0 is 98.6 °F; one decimal as the client converts it
        ObjectNode close = nurseValues();
        close.put("temperature", 98.64);
        assertThat(EmrRecordService.sameVitals(close, plain, reading())).isTrue();
        close.put("temperature", 98.9);
        assertThat(EmrRecordService.sameVitals(close, plain, reading())).isFalse();

        // a reading without a temperature must not be stamped when the doctor typed one
        VitalsReading noTemperature = new VitalsReading(ENTRY, NURSE_USER, RECORDED, 120, 80, 72, 98, null, 16);
        assertThat(EmrRecordService.sameVitals(nurseValues(), plain, noTemperature)).isFalse();
        ObjectNode without = nurseValues();
        without.remove("temperature");
        assertThat(EmrRecordService.sameVitals(without, plain, noTemperature)).isTrue();
    }

    @Test
    void theRespiratoryRateIsComparedOnlyWhenTheLayoutHasAFieldForIt() {
        ObjectNode values = nurseValues();
        assertThat(EmrRecordService.sameVitals(values, vitalsLayout(), reading())).isTrue();

        LayoutSection withRate = vitalsLayout("respiratory_rate");
        assertThat(EmrRecordService.sameVitals(values, withRate, reading())).isFalse();
        values.put("respiratory_rate", 16);
        assertThat(EmrRecordService.sameVitals(values, withRate, reading())).isTrue();
        values.put("respiratory_rate", 18);
        assertThat(EmrRecordService.sameVitals(values, withRate, reading())).isFalse();
    }

    @Test
    void aSectionWithNoTemperatureFieldIgnoresTheReadingsTemperature() {
        LayoutSection noTemperature = EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, false, false,
                EmrTestData.number("bp_systolic", BigDecimal.ZERO, BigDecimal.valueOf(300)),
                EmrTestData.number("bp_diastolic", BigDecimal.ZERO, BigDecimal.valueOf(300)),
                EmrTestData.number("pulse", BigDecimal.ZERO, BigDecimal.valueOf(300)),
                EmrTestData.number("spo2", BigDecimal.ZERO, BigDecimal.valueOf(300)));
        ObjectNode values = nurseValues();
        values.remove("temperature");

        assertThat(EmrRecordService.sameVitals(values, noTemperature, reading())).isTrue();
    }

    // ---- the old follow-up values ----

    private RecordView opdView(String sectionsJson) {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", sectionsJson)));
        return service.get(DOCTOR, RECORD);
    }

    private static String followUpSections(String valuesJson) {
        return "{\"followup\":{\"values\":" + valuesJson + ",\"updatedAt\":\"2026-11-12T05:30:00Z\","
                + "\"updatedBy\":\"" + MEMBER + "\"}}";
    }

    @Test
    void anOldFollowUpDateAndReasonAreReadOnlyLegacyValuesAndNotInTheSectionsValues() {
        RecordView view = opdView(followUpSections("{\"next_visit\":\"2026-11-20\",\"reason\":\"Review after fever\"}"));

        RecordSection section = view.sections().stream().filter(s -> "followup".equals(s.key())).findFirst().orElseThrow();
        assertThat(section.legacyValues()).isNotNull();
        assertThat(section.legacyValues().path("next_visit").asText()).isEqualTo("2026-11-20");
        assertThat(section.legacyValues().path("reason").asText()).isEqualTo("Review after fever");
        assertThat(section.values().has("next_visit")).isFalse();
        assertThat(section.values().has("reason")).isFalse();
    }

    @Test
    void aFollowUpIntervalOfTheNewComponentIsTheValueAndThereIsNoLegacy() {
        RecordView view = opdView(followUpSections("{\"next_visit\":{\"component\":\"FOLLOW_UP\",\"schemaVersion\":1,"
                + "\"interval\":\"D7\"}}"));

        RecordSection section = view.sections().stream().filter(s -> "followup".equals(s.key())).findFirst().orElseThrow();
        assertThat(section.legacyValues()).isNull();
        assertThat(section.values().path("next_visit").path("interval").asText()).isEqualTo("D7");
    }

    @Test
    void aReasonAloneIsLegacyToo() {
        RecordView view = opdView(followUpSections("{\"reason\":\"Review\"}"));

        RecordSection section = view.sections().stream().filter(s -> "followup".equals(s.key())).findFirst().orElseThrow();
        assertThat(section.legacyValues().path("reason").asText()).isEqualTo("Review");
        assertThat(section.legacyValues().has("next_visit")).isFalse();
    }
}
