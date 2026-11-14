package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.emr.EmrInputs.OncDecisionInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordSectionInput;
import com.sahaj.opd.application.emr.EmrInputs.ScreenInput;
import com.sahaj.opd.application.emr.EmrInputs.UiStateInput;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.specialty.ChemoOrderRepository;
import com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceData;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceRepository;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
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
 * The record service with a specialty profile (ADR-023 D1 to D5): the editor of an anchor section, the COVERED library
 * fields, the specialty view, the footer items, the screen facts and the Oncology decision. Rule values are tagged
 * (DEC-039, not clinically reviewed).
 */
class EmrRecordServiceSpecialtyTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final EmrLayouts layouts = mock(EmrLayouts.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final EmrMedicineRepository medicines = mock(EmrMedicineRepository.class);
    private final ChemoOrderRepository chemoOrders = mock(ChemoOrderRepository.class);
    private final SpecialtyRecordSupport.ChemoOrderHook hook = mock(SpecialtyRecordSupport.ChemoOrderHook.class);

    private EmrRecordService service;
    private List<LayoutSection> layout;
    private String starter = "gm";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        layout = List.of(
                EmrTestData.section("complaints", "COMMON", "ASSESSMENT", true, true, false,
                        EmrTestData.field("note", "TEXT", false)),
                EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, true, false,
                        EmrTestData.field("bp_systolic", "NUMBER", true), EmrTestData.field("pulse", "NUMBER", false)),
                EmrTestData.section("exam", "COMMON", "ASSESSMENT", true, false, false,
                        EmrTestData.field("cvs", "TEXT", false)),
                EmrTestData.section("anc", "SPECIALTY", "ASSESSMENT", true, false, false,
                        EmrTestData.field("fundal_height", "NUMBER", false)),
                EmrTestData.section("onc_ecog", "SPECIALTY", "ASSESSMENT", true, false, false,
                        EmrTestData.field("ecog", "TEXT", false)),
                EmrTestData.section("onc_tox", "SPECIALTY", "ASSESSMENT", true, false, false,
                        EmrTestData.field("side_effect", "TEXT", false)),
                EmrTestData.section("onc_bsa", "SPECIALTY", "ASSESSMENT", true, false, false,
                        EmrTestData.field("bsa", "TEXT", false)),
                EmrTestData.section("onc_regimen", "SPECIALTY", "TREATMENT", true, false, false,
                        EmrTestData.field("regimen", "TEXT", false)));
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(team.findAll(eq(TENANT), any())).thenReturn(Map.of(MEMBER, doctor));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenAnswer(i -> new Snapshot("STARTER", 1, layout));
        when(layouts.find(TENANT, "gm")).thenReturn(Optional.empty());
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-000123",
                "Meena Patil", 41, "F", "None known", "Type 2 diabetes", LocalDate.of(2025, 5, 9))));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, MEMBER, "NEW",
                "WITH_DOCTOR", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1)));
        when(visits.previousVisitAt(eq(TENANT), eq(PATIENT), any())).thenReturn(Optional.empty());
        when(visits.countVisitsUpTo(eq(TENANT), eq(PATIENT), any())).thenReturn(1);
        when(chemoOrders.findByRecord(eq(TENANT), any())).thenReturn(Optional.empty());
        when(chemoOrders.sentForPatient(eq(TENANT), any())).thenReturn(List.of());
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);
        ObjectProvider<SpecialtyRecordSupport.ChemoOrderHook> hookProvider = mock(ObjectProvider.class);
        when(hookProvider.getIfAvailable()).thenReturn(hook);
        SpecialtyReferenceData reference = new SpecialtyReferenceData(List.of(), List.of(),
                List.of(new GrowthBand("WEIGHT", null, 18, 18, null, "Above 97th centile", true, 1)), List.of(),
                List.of(), List.of());
        SpecialtyReferenceRepository referenceRepository = () -> reference;
        SpecialtyRecordSupport support = new SpecialtyRecordSupport(referenceRepository, mock(ServiceRepository.class),
                team, patients, mock(TreatmentCourseRepository.class), chemoOrders, records, audit,
                EmrTestData.MAPPER, hookProvider);
        service = new EmrRecordService(access, records, mock(EmrDepartmentResolver.class), layouts, visits,
                mock(VisitService.class), patients, team, mock(EmrEncounterSource.class), entitlements, audit,
                EmrTestData.MAPPER, CLOCK, new EmrComponentValues(EmrTestData.MAPPER), medicines,
                mock(ServiceLookupPort.class), mock(ObjectProvider.class), support, EmrTestData.finishRepositoryWithFreeHold(),
                EmrTestData.noBean(), EmrTestData.noBean());
    }

    private RecordRow row(String type, String sections, String screenState, long revision) {
        return new RecordRow(RECORD, type, "OPD_VISIT".equals(type) ? VISIT : null,
                "IPD_ROUND".equals(type) ? UUID.randomUUID() : null, null, null, PATIENT, MEMBER, starter,
                "Dept", starter, "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "DRAFT", null, sections, "ASSESSMENT",
                null, false, null, revision, Instant.parse("2026-11-12T05:00:00Z"), null, null, "[]", screenState);
    }

    private RecordRow opd(String sections) {
        return row("OPD_VISIT", sections, null, 4);
    }

    private static JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private RecordDraftInput draft(long revision, ScreenInput screen, String key, String values) {
        List<RecordSectionInput> sections = key == null ? List.of()
                : List.of(new RecordSectionInput(key, json(values), false));
        return new RecordDraftInput(revision, new UiStateInput("ASSESSMENT", key), sections, screen);
    }

    private void saveReturns(RecordRow after) {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(after));
    }

    private static final String EMPTY = "{}";
    private static final String VITALS = "{'specialty':{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'"
            + UUID.randomUUID() + "','bpSystolic':150,'bpDiastolic':95,'pulse':104}]}}";

    // ---- the view ----

    @Test
    void aGeneralMedicineVisitGetsTheEditorsOnVitalsAndExamAndTheirLibraryFieldsAreCovered() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(EMPTY)));

        RecordView view = service.get(DOCTOR, RECORD);

        LayoutSection vitals = view.layout().sections().stream().filter(s -> s.key().equals("vitals")).findFirst().get();
        assertThat(vitals.specialtyEditor().component()).isEqualTo("VITALS_LOG");
        assertThat(vitals.specialtyEditor().profile()).isEqualTo("gm");
        assertThat(vitals.fields()).allMatch(f -> f.valueKind() == EmrValueKind.COVERED);
        LayoutSection complaints = view.layout().sections().stream().filter(s -> s.key().equals("complaints"))
                .findFirst().get();
        assertThat(complaints.specialtyEditor()).as("a generic section keeps the generic renderer").isNull();
        assertThat(view.layout().sections().stream().filter(s -> s.key().equals("exam")).findFirst().get()
                .specialtyEditor().component()).isEqualTo("EXAM_GENERAL");
        assertThat(view.specialty().path("profile").asText()).isEqualTo("gm");
        assertThat(view.specialty().path("tagNote").asText()).isEqualTo(
                "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed");
    }

    @Test
    void aStarterWithoutAScreenUsesTheGenericRenderer() {
        starter = "ortho";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(EMPTY)));
        RecordView generic = service.get(DOCTOR, RECORD);
        assertThat(generic.specialty()).isNull();
        assertThat(generic.layout().sections()).allMatch(s -> s.specialtyEditor() == null);
        assertThat(generic.layout().sections().stream().filter(s -> s.key().equals("vitals")).findFirst().get().fields())
                .noneMatch(f -> f.valueKind() == EmrValueKind.COVERED);
    }

    @Test
    void legacyGenericValuesStayReadableUntilTheFirstSpecialtySave() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(
                "{\"vitals\":{\"values\":{\"bp_systolic\":120,\"pulse\":72},\"copiedFrom\":null,\"copyConfirmed\":false,"
                        + "\"updatedAt\":\"2026-11-12T05:00:00Z\",\"updatedBy\":\"" + MEMBER + "\"}}")));
        RecordView view = service.get(DOCTOR, RECORD);
        EmrViews.RecordSection vitals = view.sections().stream().filter(s -> s.key().equals("vitals")).findFirst().get();
        assertThat(vitals.values().size()).isZero();
        assertThat(vitals.legacyValues().path("bp_systolic").asInt()).isEqualTo(120);
    }

    // ---- saving ----

    @Test
    void aSpecialtyValueIsStoredStampedAndTheViewFlagsTheLatestEntryTaggedC1() {
        saveReturns(opd(EMPTY));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        when(records.saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), any(), eq(USER)))
                .thenReturn(true);

        service.saveDraft(DOCTOR, RECORD, draft(4, null, "vitals", VITALS));

        JsonNode stored = json(saved.getValue().replace('"', '\'')).path("vitals").path("values").path("specialty");
        assertThat(stored.path("component").asText()).isEqualTo("VITALS_LOG");
        assertThat(stored.path("entries").get(0).path("recorded").path("byMembershipId").asText())
                .isEqualTo(MEMBER.toString());
        assertThat(stored.path("entries").get(0).path("how").asText()).isEqualTo("TYPED");

        saveReturns(opd(saved.getValue()));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.specialty().path("vitalsFlags")).extracting(f -> f.path("field").asText() + "="
                + f.path("label").asText()).containsExactly("bp=High", "pulse=High");
        EmrViews.RecordSection section = view.sections().stream().filter(s -> s.key().equals("vitals")).findFirst().get();
        assertThat(section.status()).isEqualTo("DONE");
        assertThat(section.values().path("specialty").path("entries").get(0).path("recorded").path("byName").asText())
                .isEqualTo("Dr Test");
        assertThat(view.stillNeeded()).containsExactly("complaints");
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::label).containsExactly("complaints");
        assertThat(view.readyToComplete()).isFalse();
    }

    @Test
    void aLibraryFieldKeyOnAnAnchorSectionIsRefusedAsCovered() {
        saveReturns(opd(EMPTY));
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, null, "vitals", "{'pulse':72}")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, null, "vitals",
                "{'specialty':{'component':'EXAM_GENERAL','schemaVersion':1,'signs':{},'systems':{}}}")))
                .isInstanceOf(InvalidFieldsException.class);
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aClosedShapeErrorIsNamedByTheFieldPathAndNeverEchoesTheValue() {
        saveReturns(opd(EMPTY));
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, null, "vitals",
                "{'specialty':{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'" + UUID.randomUUID()
                        + "','pulse':999}]}}"))).isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
            assertThat(e.errors().get(0).field()).isEqualTo("sections[0].values.specialty.entries[0].pulse");
            assertThat(e.errors().get(0).message()).doesNotContain("999");
        });
    }

    @Test
    void aSaveOfAGenericSectionWithAProfileKeepsTheOldPath() {
        saveReturns(opd(EMPTY));
        service.saveDraft(DOCTOR, RECORD, draft(4, null, "complaints", "{'note':'Cough'}"));
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), anyString(), anyString(), any(), eq(null), eq(USER));
    }

    // ---- the screen facts ----

    @Test
    void theVisitModeIsStoredForObstetricsAndRefusedOutsideItsList() {
        starter = "og";
        saveReturns(opd(EMPTY));
        ArgumentCaptor<String> screen = ArgumentCaptor.forClass(String.class);
        when(records.saveDraft(eq(TENANT), eq(RECORD), eq(4L), anyString(), anyString(), any(), screen.capture(),
                eq(USER))).thenReturn(true);
        service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput("GYNAE", null), null, null));
        JsonNode stored = json(screen.getValue().replace('"', '\''));
        assertThat(stored.path("mode").asText()).isEqualTo("GYNAE");
        assertThat(stored.path("profile").asText()).isEqualTo("og");
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput("SESSION", null), null,
                null))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aScreenObjectOnARecordWithoutAProfileIsNotApplicable() {
        starter = "ortho";
        saveReturns(opd(EMPTY));
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput("ANC", null), null, null)))
                .isInstanceOf(EmrProblemException.class)
                .satisfies(e -> assertThat(((EmrProblemException) e).slug()).isEqualTo("not-applicable"));
        service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput(null, null), null, null));
    }

    private static final String ECOG_3 = "{'specialty':{'component':'ONC_ECOG','schemaVersion':1,'grade':3}}";

    private String recordWithEcog(int grade) {
        return "{\"onc_ecog\":{\"values\":{\"specialty\":{\"component\":\"ONC_ECOG\",\"schemaVersion\":1,\"grade\":" + grade
                + "}},\"copiedFrom\":null,\"copyConfirmed\":false,\"updatedAt\":\"2026-11-12T05:00:00Z\",\"updatedBy\":\""
                + MEMBER + "\"}}";
    }

    @Test
    void aDecisionThatDiffersFromTheSuggestionNeedsAReasonTaggedC8() {
        starter = "onco";
        saveReturns(row("OPD_VISIT", recordWithEcog(3), null, 4));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", recordWithEcog(3), null, 4)));
        // ECOG 3 suggests Stop chemo: giving as planned without a reason is refused
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput(null,
                new OncDecisionInput("GIVE_AS_PLANNED", null)), null, null))).isInstanceOf(EmrProblemException.class)
                .satisfies(e -> {
                    assertThat(((EmrProblemException) e).slug()).isEqualTo("decision-reason-required");
                    assertThat(((EmrProblemException) e).status()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("Pick a reason (saved with the note).");
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput(null,
                new OncDecisionInput("MAYBE", null)), null, null))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput(null,
                new OncDecisionInput("STOP", "BECAUSE")), null, null))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theDecisionIsStoredWithTheSuggestionAtThatMomentAndAuditedWithKeysOnlyTaggedC8() {
        starter = "onco";
        RecordRow before = row("OPD_VISIT", recordWithEcog(3), null, 4);
        saveReturns(before);
        ArgumentCaptor<String> screen = ArgumentCaptor.forClass(String.class);
        when(records.saveDraft(eq(TENANT), eq(RECORD), eq(4L), anyString(), anyString(), any(), screen.capture(),
                eq(USER))).thenReturn(true);

        service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput(null, new OncDecisionInput("GIVE_REDUCED",
                "CLINICAL_JUDGEMENT")), null, null));

        JsonNode decision = json(screen.getValue().replace('"', '\'')).path("oncDecision");
        assertThat(decision.path("choice").asText()).isEqualTo("GIVE_REDUCED");
        assertThat(decision.path("reasonKey").asText()).isEqualTo("CLINICAL_JUDGEMENT");
        assertThat(decision.path("suggestionAtDecision").asText()).isEqualTo("STOP");
        assertThat(decision.path("decidedByMembershipId").asText()).isEqualTo(MEMBER.toString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_ONC_DECISION_RECORDED"), eq("EMR_RECORD"), eq(RECORD), eq(null), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("suggestion", "choice", "reasonKey")
                .containsEntry("suggestion", "STOP").containsEntry("choice", "GIVE_REDUCED");

        // the view says facts changed once the picture moves on: ECOG 1 now suggests Give as planned
        String moved = recordWithEcog(1);
        saveReturns(row("OPD_VISIT", moved, screen.getValue(), 5));
        JsonNode onco = service.get(DOCTOR, RECORD).specialty().path("onco");
        assertThat(onco.path("suggestion").asText()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(onco.path("decision").path("choice").asText()).isEqualTo("GIVE_REDUCED");
        assertThat(onco.path("factsChanged").asBoolean()).isTrue();
    }

    @Test
    void theSameDecisionAgainWritesNoSecondAuditEntry() {
        starter = "onco";
        RecordRow before = row("OPD_VISIT", recordWithEcog(1), null, 4);
        saveReturns(before);
        service.saveDraft(DOCTOR, RECORD, draft(4, new ScreenInput(null, new OncDecisionInput("GIVE_AS_PLANNED",
                null)), null, null));
        String stored = "{\"schemaVersion\":1,\"profile\":\"onco\",\"oncDecision\":{\"choice\":\"GIVE_AS_PLANNED\","
                + "\"reasonKey\":null,\"suggestionAtDecision\":\"GIVE_AS_PLANNED\",\"decidedAt\":\"2026-11-12T05:00:00Z\","
                + "\"decidedByMembershipId\":\"" + MEMBER + "\"}}";
        saveReturns(row("OPD_VISIT", recordWithEcog(1), stored, 5));
        service.saveDraft(DOCTOR, RECORD, draft(5, new ScreenInput(null, new OncDecisionInput("GIVE_AS_PLANNED",
                null)), null, null));
        verify(audit, times(1)).record(eq("EMR_ONC_DECISION_RECORDED"), anyString(), any(), any(), any());
    }

    // ---- the footer ----

    @Test
    void theOncologyFooterListsGradesExamDecisionAndOrderWhateverTheRequiredSettingTaggedH2() {
        starter = "onco";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, null, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::key).contains("onc_tox", "onc_bsa",
                "ONC_DECISION", "onc_regimen");
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::label).contains(
                "9 side effect grade(s)", "systemic exam", "today's decision", "send chemo order");
        assertThat(view.stillNeeded()).contains("ONC_DECISION");
        assertThat(view.readyToComplete()).isFalse();
        assertThat(view.specialty().path("onco").path("orderStatus").asText()).isEqualTo("NONE");
    }

    @Test
    void aDelayDecisionChangesTheLastFooterItemToRecordTheHold() {
        starter = "onco";
        String screen = "{\"schemaVersion\":1,\"profile\":\"onco\",\"oncDecision\":{\"choice\":\"DELAY_1_WEEK\","
                + "\"reasonKey\":\"CLINICAL_JUDGEMENT\",\"suggestionAtDecision\":null,\"decidedAt\":\"" + NOW
                + "\",\"decidedByMembershipId\":\"" + MEMBER + "\"}}";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, screen, 4)));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::label).contains("record the hold")
                .doesNotContain("send chemo order", "today's decision");
    }

    @Test
    void theSurgeryPlanFooterItemIsCalledOtBooking() {
        starter = "surgery";
        layout = List.of(EmrTestData.section("sx_plan", "SPECIALTY", "ASSESSMENT", true, true, false,
                EmrTestData.field("procedure", "TEXT", false)));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, null, 4)));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::label).containsExactly("OT booking");
        assertThat(view.specialty().path("surgery").path("bookState").asText()).isEqualTo("NOT_BOOKED");
    }

    @Test
    void thePhysioSessionFooterAsksForATreatmentGivenToday() {
        starter = "physio";
        layout = List.of(EmrTestData.section("modalities", "SPECIALTY", "TREATMENT", true, false, false,
                EmrTestData.field("modality", "TEXT", false)));
        String screen = "{\"schemaVersion\":1,\"profile\":\"physio\",\"mode\":\"SESSION\"}";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, screen, 4)));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::key).containsExactly(
                "PHYSIO_TREATMENT_TODAY");
        assertThat(view.stillNeededItems().get(0).label()).isEqualTo("Select at least one treatment given today.");
        assertThat(view.specialty().path("mode").asText()).isEqualTo("SESSION");
    }

    // ---- the chemotherapy order follows the record ----

    @Test
    void anOncologySaveRecomputesTheOrderInTheSameTransaction() {
        starter = "onco";
        saveReturns(row("OPD_VISIT", EMPTY, null, 4));
        service.saveDraft(DOCTOR, RECORD, draft(4, null, "onc_ecog", ECOG_3));
        verify(hook).onRecordSaved(eq(TENANT), eq(USER), eq(MEMBER), any(RecordRow.class), any(EmrRecordDocument.class));
    }

    @Test
    void otherProfilesDoNotTouchTheChemotherapyOrder() {
        saveReturns(opd(EMPTY));
        service.saveDraft(DOCTOR, RECORD, draft(4, null, "vitals", VITALS));
        verify(hook, never()).onRecordSaved(any(), any(), any(), any(), any());
    }

    @Test
    void anEarlierStyleServiceWithoutTheSpecialtySupportIsUnchanged() {
        EmrRecordService plain = new EmrRecordService(access, records, mock(EmrDepartmentResolver.class), layouts, visits,
                mock(VisitService.class), patients, team, mock(EmrEncounterSource.class), entitlements, audit,
                EmrTestData.MAPPER, CLOCK, new EmrComponentValues(EmrTestData.MAPPER), medicines,
                mock(ServiceLookupPort.class), mock(ObjectProvider.class), mock(SpecialtyRecordSupport.class),
                EmrTestData.finishRepositoryWithFreeHold(), EmrTestData.noBean(), EmrTestData.noBean());
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(EMPTY)));
        RecordView view = plain.get(DOCTOR, RECORD);
        assertThat(view.specialty()).isNull();
        assertThat(view.layout().sections()).allMatch(s -> s.specialtyEditor() == null);
        assertThat(view.stillNeeded()).containsExactly("complaints", "vitals");
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::key).containsExactly("complaints",
                "vitals");
    }

    // ---- sections hidden by the visit mode (opd.yaml 0.5.1, ADR-023 D13.3) ----

    private static final String ANC_WITH_FORM_F_ORDER = "{\"anc\":{\"values\":{\"specialty\":{\"component\":\"ANC_CHECK\","
            + "\"schemaVersion\":1,\"orders\":[{\"key\":\"GROWTH_SCAN_FORM_F\"}]}},\"copiedFrom\":null,"
            + "\"copyConfirmed\":false,\"updatedAt\":\"2026-11-12T05:00:00Z\",\"updatedBy\":\"" + MEMBER + "\"}}";

    private void obstetricsLayout() {
        starter = "og";
        layout = List.of(
                EmrTestData.section("anc", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("fundal_height", "NUMBER", false)),
                EmrTestData.section("obstetric", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("gravida", "NUMBER", false)),
                EmrTestData.section("pcpndt", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("form_f", "TEXT", false)),
                EmrTestData.section("gyn_exam", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("per_abdomen", "TEXT", false)));
    }

    @Test
    void obstetricsAncModeIsTheDefaultAndHidesTheGynaeSectionsWhateverTheirRequiredSetting() {
        obstetricsLayout();
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, null, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.specialty().path("mode").asText()).isEqualTo("ANC");
        assertThat(view.specialty().path("hiddenSectionKeys")).extracting(JsonNode::asText).containsExactly("gyn_exam");
        assertThat(view.stillNeeded()).containsExactly("anc", "obstetric");
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::key).doesNotContain("gyn_exam");
    }

    @Test
    void obstetricsGynaeModeHidesAncObstetricAndPcpndtAndTheyNeverBlockReadyToComplete() {
        obstetricsLayout();
        String screen = "{\"schemaVersion\":1,\"profile\":\"og\",\"mode\":\"GYNAE\"}";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, screen, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.specialty().path("mode").asText()).isEqualTo("GYNAE");
        assertThat(view.specialty().path("hiddenSectionKeys")).extracting(JsonNode::asText)
                .containsExactly("anc", "obstetric", "pcpndt");
        assertThat(view.stillNeeded()).containsExactly("gyn_exam");
        assertThat(view.readyToComplete()).isFalse();
        assertThat(view.sections()).extracting(EmrViews.RecordSection::key).as("a hidden section is still in the record")
                .containsExactly("anc", "obstetric", "pcpndt", "gyn_exam");
    }

    @Test
    void aHiddenSectionKeepsItsStoredValuesAndOnlyTheVisibleOnesDecideReadyToComplete() {
        obstetricsLayout();
        layout = List.of(layout.get(0), layout.get(1));
        String screen = "{\"schemaVersion\":1,\"profile\":\"og\",\"mode\":\"GYNAE\"}";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", ANC_WITH_FORM_F_ORDER, screen, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.specialty().path("hiddenSectionKeys")).extracting(JsonNode::asText).containsExactly("anc",
                "obstetric");
        assertThat(view.stillNeededItems()).isEmpty();
        assertThat(view.readyToComplete()).isTrue();
        assertThat(view.sections().get(0).values().path("specialty").path("orders").get(0).path("key").asText())
                .isEqualTo("GROWTH_SCAN_FORM_F");
    }

    @Test
    void formFStaysShownAndInTheFooterInGynaeModeWhileAScanIsOrderedAndNotMarkedH21() {
        obstetricsLayout();
        String screen = "{\"schemaVersion\":1,\"profile\":\"og\",\"mode\":\"GYNAE\"}";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", ANC_WITH_FORM_F_ORDER, screen, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.specialty().path("hiddenSectionKeys")).extracting(JsonNode::asText)
                .containsExactly("anc", "obstetric").doesNotContain("pcpndt");
        assertThat(view.specialty().path("og").path("formFRequired").asBoolean()).isTrue();
        assertThat(view.stillNeeded()).containsExactly("pcpndt", "gyn_exam");
    }

    @Test
    void physioFirstVisitIsTheDefaultAndHidesModalitiesAndSessions() {
        starter = "physio";
        layout = List.of(
                EmrTestData.section("special_tests", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("test", "TEXT", false)),
                EmrTestData.section("modalities", "SPECIALTY", "TREATMENT", true, true, false,
                        EmrTestData.field("modality", "TEXT", false)),
                EmrTestData.section("sessions", "SPECIALTY", "TREATMENT", true, true, false,
                        EmrTestData.field("note", "TEXT", false)));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, null, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.specialty().path("mode").asText()).isEqualTo("FIRST_VISIT");
        assertThat(view.specialty().path("hiddenSectionKeys")).extracting(JsonNode::asText)
                .containsExactly("modalities", "sessions");
        assertThat(view.stillNeeded()).containsExactly("special_tests");
    }

    @Test
    void physioSessionModeHidesSpecialTestsAndPostureAndAsksForATreatment() {
        starter = "physio";
        layout = List.of(
                EmrTestData.section("posture", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("gait", "TEXT", false)),
                EmrTestData.section("special_tests", "SPECIALTY", "ASSESSMENT", true, true, false,
                        EmrTestData.field("test", "TEXT", false)),
                EmrTestData.section("modalities", "SPECIALTY", "TREATMENT", true, false, false,
                        EmrTestData.field("modality", "TEXT", false)));
        String screen = "{\"schemaVersion\":1,\"profile\":\"physio\",\"mode\":\"SESSION\"}";
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(row("OPD_VISIT", EMPTY, screen, 4)));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.specialty().path("hiddenSectionKeys")).extracting(JsonNode::asText)
                .containsExactly("posture", "special_tests");
        assertThat(view.stillNeededItems()).extracting(EmrViews.StillNeededItem::key)
                .containsExactly("PHYSIO_TREATMENT_TODAY");
    }

    @Test
    void otherProfilesHaveNoModeAndNoHiddenSections() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(EMPTY)));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.specialty().path("mode").isNull()).isTrue();
        assertThat(view.specialty().path("hiddenSectionKeys").isArray()).isTrue();
        assertThat(view.specialty().path("hiddenSectionKeys")).isEmpty();
    }
}
