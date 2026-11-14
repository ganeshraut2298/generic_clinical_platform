package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.Option;
import com.sahaj.opd.application.emr.EmrFormModel.ShowIf;
import com.sahaj.opd.application.emr.EmrInputs.OpenInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordSectionInput;
import com.sahaj.opd.application.emr.EmrInputs.SwitchDepartmentInput;
import com.sahaj.opd.application.emr.EmrInputs.UiStateInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrMedicineRepository.AllergyClassRow;
import com.sahaj.opd.application.emr.EmrMedicineRepository.MedicineRow;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.NewRecord;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.EmrViews.OpenResult;
import com.sahaj.opd.application.emr.EmrViews.RecordSection;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * SAH-59 (ADR-022 D11): clinic forms inside an EMR record (values, Done, allergy check, MLC, copy from last visit, the
 * form references stored at open) and the response-only parts of the component values (SAH-56, SAH-57).
 */
class EmrRecordServiceFormsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID SULFA_DRUG = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final String FORM = "f_abcdefghij";
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final EmrLayouts layouts = mock(EmrLayouts.class);
    private final EmrDepartmentResolver resolver = mock(EmrDepartmentResolver.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final EmrMedicineRepository medicines = mock(EmrMedicineRepository.class);
    private final ServiceLookupPort services = mock(ServiceLookupPort.class);
    private final EmrVisitChargePort charges = mock(EmrVisitChargePort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmrVisitChargePort> chargeProvider = mock(ObjectProvider.class);
    private EmrRecordService service;
    private LayoutSection formSection;
    private List<LayoutSection> layout;

    private static Field field(String key, String type, boolean required, boolean copy, ShowIf showIf) {
        return new Field(key, type, "Label " + key, null, null, null, required, 3, null, type.equals("NUMBER")
                ? new BigDecimal("0") : null, type.equals("NUMBER") ? new BigDecimal("10") : null, null, null,
                List.of(new Option("o0", "Zero", null, null, 0)), true, copy, List.of("DOCTOR"), null, showIf, null,
                null);
    }

    private static Definition footForm() {
        return new Definition(1, "Foot check", null, List.of(field("a", "SHORT_TEXT", true, true, null),
                field("b", "NUMBER", false, false, null), field("ulcer", "YES_NO", false, false, null),
                field("site", "SHORT_TEXT", false, false, new ShowIf("ulcer", "yes")),
                field("tests", "LAB_TESTS", false, true, null), field("rx", "MEDICINE_LIST", false, false, null),
                field("map", "BODY_MAP", false, false, null)));
    }

    private static FieldDefinition component(String key, String type, String kind) {
        return new FieldDefinition(key, key, "SEARCH_LIST", EmrValueKind.COMPONENT, "", false, "NONE", null, true,
                List.of(), List.of(), null, null, new ComponentRef(type, kind));
    }

    @BeforeEach
    void setUp() {
        formSection = new LayoutSection(FORM, "FORM", "Foot check", "SPECIALTY", "Custom form", "ASSESSMENT", 9, true,
                true, true, false, false, List.of("DOCTOR"), false, null, List.of(), FORM, 2, footForm());
        layout = List.of(
                EmrTestData.section("allergy", true, false, EmrTestData.field("drug_allergy", "SEARCH_LIST", false)),
                EmrTestData.section("procedures", true, false, component("procedure", "EMR_LIST", "SERVICES")),
                EmrTestData.section("exam", true, false, component("map", "BODY_MAP", null)), formSection);
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(team.findAll(any(), any())).thenReturn(Map.of(MEMBER, doctor));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1, layout));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                41, "F", "None known", null)));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, MEMBER, "NEW",
                "WAITING", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1)));
        when(medicines.allergyClasses()).thenReturn(List.of(new AllergyClassRow("SULFA", "Sulfa drugs")));
        when(medicines.find(TENANT, SULFA_DRUG)).thenReturn(Optional.of(new MedicineRow(SULFA_DRUG,
                "Co-trimoxazole 960 mg", "Tablet", "1-0-1", "SULFA", "1-0-1", 5, "AFTER_FOOD", false, 0)));
        when(services.find(TENANT, SERVICE)).thenReturn(Optional.of(new ServiceLookupPort.ServiceSummary(SERVICE,
                "Dressing", 15000, true)));
        when(chargeProvider.getIfAvailable()).thenReturn(charges);
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);
        service = new EmrRecordService(access, records, resolver, layouts, visits, mock(VisitService.class), patients,
                team, mock(EmrEncounterSource.class), entitlements, audit, EmrTestData.MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC), new EmrComponentValues(EmrTestData.MAPPER), medicines, services,
                chargeProvider, mock(com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport.class), EmrTestData.finishRepositoryWithFreeHold(),
                EmrTestData.noBean(), EmrTestData.noBean());
    }

    private RecordRow record(String sections) {
        return new RecordRow(RECORD, "OPD_VISIT", VISIT, null, null, null, PATIENT, MEMBER, "gm", "General Medicine",
                "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "DRAFT", null, sections, "ASSESSMENT", null,
                false, null, 4, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private RecordDraftInput draft(String values) {
        return new RecordDraftInput(4L, new UiStateInput("ASSESSMENT", null),
                List.of(new RecordSectionInput(FORM, json(values), null)));
    }

    private RecordSection section(RecordView view, String key) {
        return view.sections().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    // ---- values ----

    @Test
    void formValuesAreCheckedAgainstTheFormVersionAndAHiddenFieldIsDroppedOnSave() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("{}")));

        service.saveDraft(DOCTOR, RECORD, draft("{\"a\":\"ok\",\"b\":4,\"ulcer\":false,\"site\":\"toe\"}"));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, saved.getValue());
        assertThat(doc.values(FORM).path("a").asText()).isEqualTo("ok");
        assertThat(doc.values(FORM).path("b").asInt()).isEqualTo(4);
        assertThat(doc.values(FORM).has("site")).isFalse();
    }

    @Test
    void aNumberOutsideTheFormsLimitsIs400OutsideMinMaxAndNothingIsSaved() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("{}")));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft("{\"b\":11}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> {
                    assertThat(ex.errors().get(0).field()).isEqualTo("sections[0].values.b");
                    assertThat(ex.errors().get(0).message()).isEqualTo("Outside 0–10");
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void aFormThatIsNotInThisRecordIsRefused() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("{}")));
        RecordDraftInput other = new RecordDraftInput(4L, new UiStateInput("ASSESSMENT", null),
                List.of(new RecordSectionInput("f_zzzzzzzzzz", json("{\"a\":\"x\"}"), null)));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, other)).isInstanceOf(InvalidFieldsException.class);
    }

    // ---- Done ----

    @Test
    void aRequiredFormIsRequiredUntilItsRequiredFieldIsFilledThenDoneAndReady() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("{}")));

        RecordView empty = service.get(DOCTOR, RECORD);
        assertThat(section(empty, FORM).status()).isEqualTo("REQUIRED");
        assertThat(empty.stillNeeded()).contains(FORM);
        assertThat(empty.readyToComplete()).isFalse();

        EmrRecordDocument filled = EmrRecordDocument.empty(EmrTestData.MAPPER);
        filled.put(FORM, (ObjectNode) json("{\"a\":\"ok\"}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record(filled.toJson())));
        RecordView done = service.get(DOCTOR, RECORD);
        assertThat(section(done, FORM).status()).isEqualTo("DONE");
        assertThat(done.stillNeeded()).doesNotContain(FORM);
        assertThat(done.layout().sections()).anyMatch(s -> s.formSection() && s.formVersionNo() == 2);
    }

    // ---- allergy check and MLC through form components ----

    @Test
    void aSulfaDrugInAFormsMedicineListRaisesTheAllergyAlertOfTheRecord() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("allergy", (ObjectNode) json("{\"drug_allergy\":[{\"itemKey\":\"SULFA\",\"text\":\"Sulfa drugs\","
                + "\"newToList\":false}]}"), false, NOW, MEMBER);
        stored.put(FORM, (ObjectNode) json("{\"rx\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":["
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"medicineId\":\"" + SULFA_DRUG + "\",\"name\":\"x\","
                + "\"frequency\":\"1-0-1\",\"days\":5,\"when\":\"AFTER_FOOD\",\"source\":\"SEARCH\","
                + "\"allergyReview\":null}]}}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record(stored.toJson())));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.allergyAlerts()).singleElement().satisfies(a -> {
            assertThat(a.sectionKey()).isEqualTo(FORM);
            assertThat(a.fieldKey()).isEqualTo("rx");
        });
        assertThat(view.completeBlockers()).containsExactly("ALLERGY_ALERT");
    }

    @Test
    void aRoadAccidentPinOnAFormsBodyMapSetsTheRecordsMlcFlag() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put(FORM, (ObjectNode) json("{\"map\":{\"component\":\"BODY_MAP\",\"schemaVersion\":1,\"nextPinNo\":2,"
                + "\"noFindingsConfirmed\":false,\"pins\":[{\"no\":1,\"side\":\"FRONT\",\"x\":0.5,\"y\":0.5,"
                + "\"region\":\"head\",\"where\":\"\",\"finding\":\"WOUND\",\"cause\":\"ROAD_ACCIDENT\","
                + "\"severity\":\"MILD\",\"sizeCm\":0,\"note\":\"\",\"mlc\":true}]}}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record(stored.toJson())));

        assertThat(service.get(DOCTOR, RECORD).header().mlc()).isTrue();
    }

    // ---- response-only parts ----

    @Test
    void listItemsAndPinsCarryTheNamesThePriceAndTheBillStateOnTheWayOut() {
        UUID itemId = UUID.randomUUID();
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("procedures", (ObjectNode) json("{\"procedure\":{\"component\":\"EMR_LIST\",\"kind\":\"SERVICES\","
                + "\"schemaVersion\":1,\"items\":[{\"id\":\"" + itemId + "\",\"itemKey\":\"" + SERVICE
                + "\",\"text\":\"Dressing\",\"newToList\":false,\"attr1\":\"1\",\"attr2\":\"BILL_TODAY\","
                + "\"active\":true,\"addedAt\":\"2026-11-12T05:00:00Z\",\"addedByMembershipId\":\"" + MEMBER
                + "\"},{\"id\":\"" + UUID.randomUUID() + "\",\"itemKey\":null,\"text\":\"Typed\",\"newToList\":true,"
                + "\"attr1\":\"1\",\"attr2\":\"NO_CHARGE\",\"active\":true}]}}"), false, NOW, MEMBER);
        stored.put("exam", (ObjectNode) json("{\"map\":{\"component\":\"BODY_MAP\",\"schemaVersion\":1,"
                + "\"nextPinNo\":2,\"noFindingsConfirmed\":false,\"pins\":[{\"no\":1,\"side\":\"FRONT\",\"x\":0.5,"
                + "\"y\":0.5,\"region\":\"head\",\"where\":\"\",\"finding\":\"WOUND\",\"cause\":\"FALL\","
                + "\"severity\":\"MILD\",\"sizeCm\":0,\"note\":\"\",\"mlc\":false,\"recordedAt\":"
                + "\"2026-11-12T05:00:00Z\",\"recordedByMembershipId\":\"" + MEMBER + "\"}]}}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record(stored.toJson())));
        when(charges.billedState(TENANT, VISIT)).thenReturn(Map.of(itemId.toString(), "ON_PAID_BILL"));

        RecordView view = service.get(DOCTOR, RECORD);

        JsonNode items = section(view, "procedures").values().path("procedure").path("items");
        assertThat(items.get(0).path("addedByName").asText()).isEqualTo("Dr Test");
        assertThat(items.get(0).path("unitPricePaise").asLong()).isEqualTo(15000);
        assertThat(items.get(0).path("billed").asText()).isEqualTo("ON_PAID_BILL");
        assertThat(items.get(1).path("addedByName").isNull()).isTrue();
        assertThat(items.get(1).path("unitPricePaise").isNull()).isTrue();
        assertThat(items.get(1).path("billed").isNull()).isTrue();
        JsonNode pin = section(view, "exam").values().path("map").path("pins").get(0);
        assertThat(pin.path("recordedByName").asText()).isEqualTo("Dr Test");
    }

    @Test
    void theResponseOnlyPartsAreNeverStoredWhenTheClientSendsThemBack() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("{}")));
        UUID itemId = UUID.randomUUID();
        String item = "{\"procedure\":{\"component\":\"EMR_LIST\",\"kind\":\"SERVICES\",\"schemaVersion\":1,"
                + "\"items\":[{\"id\":\"" + itemId + "\",\"itemKey\":\"" + SERVICE + "\",\"text\":\"Dressing\","
                + "\"newToList\":false,\"attr1\":\"1\",\"attr2\":\"NO_CHARGE\",\"addedByName\":\"Someone else\","
                + "\"unitPricePaise\":1,\"billed\":\"ON_BILL\"}]}}";
        RecordDraftInput input = new RecordDraftInput(4L, new UiStateInput("ASSESSMENT", null),
                List.of(new RecordSectionInput("procedures", json(item), null)));

        service.saveDraft(DOCTOR, RECORD, input);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), eq(USER));
        assertThat(saved.getValue()).doesNotContain("Someone else").doesNotContain("unitPricePaise")
                .doesNotContain("ON_BILL");
    }

    // ---- open: form references and copy from last visit ----

    private void openSetup(List<LayoutSection> atOpen) {
        Department gm = new Department(null, "gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of(), 0, 0, null);
        Published published = new Published("STARTER", null, null, 1, layout);
        when(resolver.choose(TENANT, MEMBER)).thenReturn(new Resolution(gm, published, "DOCTOR_SPECIALTY"));
        when(layouts.sectionsAtOpen(TENANT, layout, "OPD_VISIT")).thenReturn(atOpen);
        when(layouts.formRefsJson(any())).thenReturn("[{\"formKey\":\"" + FORM + "\"}]");
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(record("{}")));
    }

    @Test
    void aNewRecordStoresTheFormReferencesAtOpen() {
        openSetup(layout);

        OpenResult result = service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        assertThat(created.getValue().formRefs()).isEqualTo("[{\"formKey\":\"" + FORM + "\"}]");
    }

    @Test
    void aFieldWithCopyFromLastVisitIsPrefilledFromThePatientsLatestSignedEntryOfTheSameForm() {
        openSetup(layout);
        EmrRecordDocument earlier = EmrRecordDocument.empty(EmrTestData.MAPPER);
        earlier.put(FORM, (ObjectNode) json("{\"a\":\"from last time\",\"b\":7,\"tests\":{\"component\":\"EMR_LIST\","
                + "\"kind\":\"INVESTIGATIONS\",\"schemaVersion\":1,\"items\":[]}}"), true, NOW, MEMBER);
        UUID earlierId = UUID.randomUUID();
        when(records.signedForPatient(TENANT, PATIENT, 20)).thenReturn(List.of(new SignedCopy(earlierId,
                Instant.parse("2026-10-30T06:00:00Z"), earlier.toJson())));

        service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, created.getValue().sections());
        assertThat(doc.values(FORM).path("a").asText()).isEqualTo("from last time");
        assertThat(doc.values(FORM).has("b")).isFalse();
        assertThat(doc.values(FORM).has("tests")).isFalse();
        assertThat(doc.copiedFromRecord(FORM)).isEqualTo(earlierId);
        assertThat(doc.copyPending(FORM)).isTrue();
    }

    @Test
    void nothingIsCopiedWhenThePatientHasNoSignedEntryOfThatForm() {
        openSetup(layout);
        EmrRecordDocument earlier = EmrRecordDocument.empty(EmrTestData.MAPPER);
        earlier.put("allergy", (ObjectNode) json("{}"), true, NOW, MEMBER);
        when(records.signedForPatient(TENANT, PATIENT, 20)).thenReturn(List.of(new SignedCopy(UUID.randomUUID(),
                Instant.parse("2026-10-30T06:00:00Z"), earlier.toJson())));

        service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        assertThat(EmrRecordDocument.parse(EmrTestData.MAPPER, created.getValue().sections()).has(FORM)).isFalse();
    }

    // ---- switch department ----

    @Test
    void switchingTheDepartmentReplacesTheFormReferences() {
        Department dental = new Department(null, "dental", "Dental", "DN", "#2E7D32", "dental", "STARTER", null,
                List.of(), 0, 0, null);
        List<LayoutSection> dentalLayout = List.of(EmrTestData.section("vitals", true, false));
        Published published = new Published("STARTER", null, null, 1, dentalLayout);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("{}")));
        when(resolver.switchTo(TENANT, "dental")).thenReturn(new Resolution(dental, published, "SWITCHED"));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1, layout));
        when(records.switchLayout(eq(TENANT), eq(RECORD), eq(4L), eq("dental"), eq("Dental"), eq("dental"), any(),
                any(), any(), any(), eq(USER))).thenReturn(true);
        when(layouts.sectionsAtOpen(TENANT, dentalLayout, "OPD_VISIT")).thenReturn(dentalLayout);
        when(layouts.formRefsJson(any())).thenReturn("[]");

        service.switchDepartment(DOCTOR, RECORD, new SwitchDepartmentInput("dental", 4L));

        verify(records).saveFormRefs(TENANT, RECORD, "[]", USER);
    }
}
