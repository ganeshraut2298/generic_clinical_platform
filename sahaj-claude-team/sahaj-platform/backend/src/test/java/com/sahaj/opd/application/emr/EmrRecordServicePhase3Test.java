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
import com.sahaj.opd.application.emr.EmrInputs.CopyItemsInput;
import com.sahaj.opd.application.emr.EmrInputs.PrintPreferencesInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordSectionInput;
import com.sahaj.opd.application.emr.EmrInputs.UiStateInput;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrMedicineRepository.AllergyClassRow;
import com.sahaj.opd.application.emr.EmrMedicineRepository.MedicineRow;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.EmrViews.CopyItemsResult;
import com.sahaj.opd.application.emr.finish.FinishModels;
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

/** The phase 3 parts of the record service (ADR-022 D2 to D8): component saves, allergy check, bill lines, copy. */
class EmrRecordServicePhase3Test {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID SULFA_DRUG = UUID.randomUUID();
    private static final UUID PLAIN_DRUG = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
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
    private final ServiceLookupPort services = mock(ServiceLookupPort.class);
    private final EmrVisitChargePort charges = mock(EmrVisitChargePort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmrVisitChargePort> chargeProvider = mock(ObjectProvider.class);
    private EmrRecordService service;

    private static FieldDefinition component(String key, String type, String kind) {
        return new FieldDefinition(key, key, "SEARCH_LIST", EmrValueKind.COMPONENT, "", false, "NONE", null, true,
                List.of(), List.of(), null, null, new ComponentRef(type, kind));
    }

    @BeforeEach
    void setUp() {
        List<LayoutSection> layout = List.of(
                EmrTestData.section("allergy", true, false, EmrTestData.field("drug_allergy", "SEARCH_LIST", false)),
                EmrTestData.section("rx", true, false, component("drug", "PRESCRIPTION", null)),
                EmrTestData.section("procedures", true, false, component("procedure", "EMR_LIST", "SERVICES")),
                EmrTestData.section("exam", true, true, component("map", "BODY_MAP", null)),
                EmrTestData.section("complaints", true, true, component("complaint", "EMR_LIST", "COMPLAINTS"),
                        new FieldDefinition("duration", "Duration", "NUMBER_UNIT", EmrValueKind.COVERED, "", false,
                                "NONE", null, false, List.of(), List.of(), null, null)));
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(team.findAll(any(), any())).thenReturn(Map.of(MEMBER, doctor));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1, layout));
        when(layouts.find(TENANT, "gm")).thenReturn(Optional.empty());
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-1", "Meena Patil",
                41, "F", "None known", null)));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, MEMBER, "NEW",
                "WAITING", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1)));
        when(medicines.allergyClasses()).thenReturn(List.of(new AllergyClassRow("SULFA", "Sulfa drugs"),
                new AllergyClassRow("PENICILLIN", "Penicillin")));
        when(medicines.find(TENANT, SULFA_DRUG)).thenReturn(Optional.of(new MedicineRow(SULFA_DRUG,
                "Co-trimoxazole 960 mg", "Tablet", "1-0-1", "SULFA", "1-0-1", 5, "AFTER_FOOD", false, 0)));
        when(medicines.find(TENANT, PLAIN_DRUG)).thenReturn(Optional.of(new MedicineRow(PLAIN_DRUG,
                "Paracetamol 650 mg", "Tablet", "1-0-1", null, "1-0-1", 3, "AFTER_FOOD", false, 0)));
        when(services.find(TENANT, SERVICE)).thenReturn(Optional.of(new ServiceLookupPort.ServiceSummary(SERVICE,
                "Dressing", 15000, true)));
        when(chargeProvider.getIfAvailable()).thenReturn(charges);
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);
        service = new EmrRecordService(access, records, mock(EmrDepartmentResolver.class), layouts, visits,
                mock(VisitService.class), patients, team, mock(EmrEncounterSource.class), entitlements, audit,
                EmrTestData.MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), new EmrComponentValues(EmrTestData.MAPPER),
                medicines, services, chargeProvider, mock(com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport.class), EmrTestData.finishRepositoryWithFreeHold(),
                EmrTestData.noBean(), EmrTestData.noBean());
    }

    private RecordRow record(String status, String sections) {
        return new RecordRow(RECORD, "OPD_VISIT", VISIT, null, null, null, PATIENT, MEMBER, "gm", "General Medicine",
                "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", status, null, sections, "ASSESSMENT", null, false,
                null, 4, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private RecordDraftInput draft(RecordSectionInput... sections) {
        return new RecordDraftInput(4L, new UiStateInput("ASSESSMENT", null), List.of(sections));
    }

    private RecordSectionInput section(String key, String values) {
        return new RecordSectionInput(key, json(values), null);
    }

    private static String rxRow(UUID medicine, String review) {
        return "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":[{\"id\":\""
                + UUID.nameUUIDFromBytes(medicine.toString().getBytes()) + "\",\"medicineId\":\"" + medicine
                + "\",\"name\":\"x\",\"form\":null,\"frequency\":\"1-0-1\",\"days\":5,\"when\":\"AFTER_FOOD\","
                + "\"source\":\"SEARCH\",\"allergyReview\":" + review + "}]}}";
    }

    private static final String SULFA_ALLERGY = "{\"drug_allergy\":[{\"itemKey\":\"SULFA\",\"text\":\"Sulfa drugs\","
            + "\"newToList\":false}]}";

    @Test
    void aSulfaDrugWithASulfaAllergyBlocksReadyToCompleteUntilTheRowIsReviewed() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("allergy", (ObjectNode) json(SULFA_ALLERGY), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", stored.toJson())));

        service.saveDraft(DOCTOR, RECORD, draft(section("rx", rxRow(SULFA_DRUG, "null"))));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), eq(USER));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", saved.getValue())));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.allergyAlerts()).hasSize(1);
        assertThat(view.allergyAlerts().get(0).allergyClassLabel()).isEqualTo("Sulfa drugs");
        assertThat(view.allergyAlerts().get(0).reviewed()).isFalse();
        assertThat(view.completeBlockers()).containsExactly("ALLERGY_ALERT");
        assertThat(view.readyToComplete()).isFalse();
        assertThat(view.prescriptionIsOrder()).isFalse();
        assertThat(view.lastSignedPrescription()).isNull();
    }

    @Test
    void reviewingTheAlertIsStampedAuditedOnceAndTheBlockGoes() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("allergy", (ObjectNode) json(SULFA_ALLERGY), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", stored.toJson())));

        service.saveDraft(DOCTOR, RECORD, draft(section("rx",
                rxRow(SULFA_DRUG, "{\"allergyClass\":\"SULFA\",\"confirmed\":true}"))));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), eq(USER));
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_ALLERGY_ALERT_REVIEWED"), eq("EMR_RECORD"), eq(RECORD), eq(null), values.capture());
        assertThat(values.getValue()).containsEntry("allergyClass", "SULFA").doesNotContainKey("medicineName");
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", saved.getValue())));
        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.allergyAlerts().get(0).reviewed()).isTrue();
        assertThat(view.allergyAlerts().get(0).reviewedByName()).isEqualTo("Dr Test");
        assertThat(view.completeBlockers()).isEmpty();
    }

    @Test
    void changingTheAllergyClassesInASaveClearsEveryReview() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("allergy", (ObjectNode) json(SULFA_ALLERGY), false, NOW, MEMBER);
        stored.put("rx", (ObjectNode) json(rxRow(SULFA_DRUG, "{\"allergyClass\":\"SULFA\",\"confirmed\":true,"
                + "\"reviewedAt\":\"2026-11-12T05:00:00Z\"}")), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", stored.toJson())));

        service.saveDraft(DOCTOR, RECORD, draft(section("allergy",
                "{\"drug_allergy\":[{\"itemKey\":\"SULFA\",\"text\":\"Sulfa drugs\",\"newToList\":false},"
                        + "{\"itemKey\":\"PENICILLIN\",\"text\":\"Penicillin\",\"newToList\":false}]}")));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, saved.getValue());
        assertThat(doc.values("rx").path("drug").path("rows").get(0).path("allergyReview").isNull()).isTrue();
    }

    @Test
    void aReviewSentInTheSameSaveAsTheFirstAllergyClassIsClearedAndWritesNoAuditEntry() {
        // DEC-051 Q2: the class set changed, so the review is cleared; an entry for a review that no longer exists is not written
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));

        service.saveDraft(DOCTOR, RECORD, draft(section("allergy", SULFA_ALLERGY),
                section("rx", rxRow(SULFA_DRUG, "{\"allergyClass\":\"SULFA\",\"confirmed\":true}"))));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(4L), saved.capture(), anyString(), any(), eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, saved.getValue());
        assertThat(doc.values("rx").path("drug").path("rows").get(0).path("allergyReview").isNull()).isTrue();
        verify(audit, never()).record(eq("EMR_ALLERGY_ALERT_REVIEWED"), anyString(), any(), any(), any());
        verify(audit).record(eq("EMR_RECORD_DRAFT_SAVED"), eq("EMR_RECORD"), eq(RECORD), eq(null), any());
    }

    @Test
    void aReviewSentInASaveThatKeepsTheAllergyClassesStillWritesOneAuditEntry() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("allergy", (ObjectNode) json(SULFA_ALLERGY), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", stored.toJson())));

        service.saveDraft(DOCTOR, RECORD, draft(section("allergy", SULFA_ALLERGY),
                section("rx", rxRow(SULFA_DRUG, "{\"allergyClass\":\"SULFA\",\"confirmed\":true}"))));

        verify(audit, org.mockito.Mockito.times(1)).record(eq("EMR_ALLERGY_ALERT_REVIEWED"), eq("EMR_RECORD"), eq(RECORD),
                eq(null), any());
    }

    @Test
    void aBadComponentValueIsRefusedWithTheFieldNamedAndNothingIsSaved() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        String zeroDays = rxRow(PLAIN_DRUG, "null").replace("\"days\":5", "\"days\":0");

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(section("rx", zeroDays))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors().get(0).field())
                        .isEqualTo("sections[0].values.drug"));
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void aCoveredSiblingCannotBeFilledInAndStoresNothing() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(section("complaints",
                "{\"duration\":{\"value\":2,\"unit\":\"days\"}}")))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aFinishBillLineKeepsTheLineTotalInAmountPaiseAndCarriesTheUnitPriceAndTheQuantity() {
        // DEC-051 Q4: amountPaise = quantity x unit price; unitPaise and the quantity are added for display
        String item = "{\"procedure\":{\"component\":\"EMR_LIST\",\"kind\":\"SERVICES\",\"schemaVersion\":1,"
                + "\"items\":[{\"id\":\"" + UUID.randomUUID() + "\",\"itemKey\":\"" + SERVICE
                + "\",\"text\":\"Dressing\",\"newToList\":false,\"attr1\":\"2\",\"attr2\":\"BILL_TODAY\"}]}}";
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("procedures", (ObjectNode) json(item), false, NOW, MEMBER);
        when(charges.syncEmrLines(eq(TENANT), eq(USER), eq(VISIT), any())).thenReturn(EmrVisitChargePort.Outcome.SYNCED);
        EmrRecordService.Encounter encounter = new EmrRecordService.Encounter("OPD_VISIT", VISIT,
                new VisitRow(VISIT, PATIENT, MEMBER, "NEW", "WAITING", 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1),
                null, null, false, "on");

        EmrRecordService.BillSummary bill = service.syncBillLines(DOCTOR, encounter, record("DRAFT", stored.toJson()));

        assertThat(bill.totalPaise()).isEqualTo(30000L);
        assertThat(bill.items()).hasSize(1);
        FinishModels.FinishBillLine line = bill.items().get(0);
        assertThat(line.qty()).isEqualTo(2);
        assertThat(line.unitPaise()).isEqualTo(15000L);
        assertThat(line.amountPaise()).isEqualTo(30000L);
    }

    @Test
    void aTickedBillTodayServiceSyncsTheVisitBillAndAPaidBillRollsTheSaveBack() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        String item = "{\"procedure\":{\"component\":\"EMR_LIST\",\"kind\":\"SERVICES\",\"schemaVersion\":1,"
                + "\"items\":[{\"id\":\"" + UUID.randomUUID() + "\",\"itemKey\":\"" + SERVICE
                + "\",\"text\":\"Dressing\",\"newToList\":false,\"attr1\":\"2\",\"attr2\":\"BILL_TODAY\"},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"itemKey\":\"" + SERVICE
                + "\",\"text\":\"Free dressing\",\"newToList\":false,\"attr1\":\"1\",\"attr2\":\"NO_CHARGE\"}]}}";
        when(charges.syncEmrLines(eq(TENANT), eq(USER), eq(VISIT), any())).thenReturn(
                EmrVisitChargePort.Outcome.SYNCED);

        service.saveDraft(DOCTOR, RECORD, draft(section("procedures", item)));

        ArgumentCaptor<List<EmrVisitChargePort.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(charges).syncEmrLines(eq(TENANT), eq(USER), eq(VISIT), lines.capture());
        assertThat(lines.getValue()).hasSize(1);
        assertThat(lines.getValue().get(0).qty()).isEqualTo(2);
        assertThat(lines.getValue().get(0).serviceId()).isEqualTo(SERVICE);

        when(charges.syncEmrLines(eq(TENANT), eq(USER), eq(VISIT), any())).thenReturn(
                EmrVisitChargePort.Outcome.ALREADY_BILLED);
        org.mockito.Mockito.clearInvocations(records);
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(section("procedures", item))))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("already-billed");
                    assertThat(ex.status()).isEqualTo(409);
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void aSaveWithoutTheProceduresSectionNeverTouchesTheBill() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));

        service.saveDraft(DOCTOR, RECORD, draft(section("rx", rxRow(PLAIN_DRUG, "null"))));

        verify(charges, never()).syncEmrLines(any(), any(), any(), any());
    }

    @Test
    void aPhaseTwoItemArrayIsReadAsAnActiveListAndCountsAsDone() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("complaints", (ObjectNode) json("{\"complaint\":[{\"itemKey\":\"k\",\"text\":\"Fever\","
                + "\"newToList\":false}]}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", stored.toJson())));

        RecordView view = service.get(DOCTOR, RECORD);

        JsonNode values = view.sections().stream().filter(s -> s.key().equals("complaints")).findFirst().orElseThrow()
                .values();
        assertThat(values.path("complaint").path("component").asText()).isEqualTo("EMR_LIST");
        assertThat(view.stillNeeded()).doesNotContain("complaints").contains("exam");
    }

    @Test
    void aBodyMapWithAnMlcPinMakesTheHeaderMlc() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("exam", (ObjectNode) json("{\"map\":{\"component\":\"BODY_MAP\",\"schemaVersion\":1,\"pins\":["
                + "{\"no\":1,\"mlc\":true}],\"nextPinNo\":2,\"noFindingsConfirmed\":false}}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", stored.toJson())));

        assertThat(service.get(DOCTOR, RECORD).header().mlc()).isTrue();
    }

    // ---- copy actions ----

    private EmrRecordDocument signedRx(String name) {
        EmrRecordDocument doc = EmrRecordDocument.empty(EmrTestData.MAPPER);
        doc.put("rx", (ObjectNode) json("{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":[{\"id\":\""
                + UUID.randomUUID() + "\",\"medicineId\":null,\"name\":\"" + name
                + "\",\"form\":null,\"frequency\":\"1-0-1\",\"days\":5,\"when\":\"AFTER_FOOD\",\"source\":\"TYPED\"}]}}"),
                false, NOW, MEMBER);
        return doc;
    }

    @Test
    void repeatLastRxCopiesTheMedicinesOnceAndADoubleTapSkipsThem() {
        UUID signedId = UUID.randomUUID();
        when(records.findByIdForUpdate(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        when(records.signedForPatient(TENANT, PATIENT, 20)).thenReturn(List.of(new SignedCopy(signedId, NOW,
                signedRx("Aspirin").toJson())));
        when(records.saveSectionsLocked(any(), any(), anyString(), any())).thenReturn(true);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));

        CopyItemsResult first = service.copyItems(DOCTOR, RECORD,
                new CopyItemsInput("LAST_SIGNED_PRESCRIPTION", null, "MEDICINES"));

        assertThat(first.added()).isEqualTo(1);
        assertThat(first.skipped()).isZero();
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveSectionsLocked(eq(TENANT), eq(RECORD), saved.capture(), eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, saved.getValue());
        assertThat(doc.values("rx").path("drug").path("rows").get(0).path("source").asText()).isEqualTo("TYPED");
        ArgumentCaptor<Map<String, Object>> auditValues = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_ITEMS_COPIED"), eq("EMR_RECORD"), eq(RECORD), eq(null), auditValues.capture());
        assertThat(auditValues.getValue()).containsEntry("added", 1).containsEntry("sourceRecordId", signedId)
                .doesNotContainKey("name");

        org.mockito.Mockito.clearInvocations(records);
        when(records.findByIdForUpdate(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", saved.getValue())));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT", saved.getValue())));
        CopyItemsResult second = service.copyItems(DOCTOR, RECORD,
                new CopyItemsInput("LAST_SIGNED_PRESCRIPTION", null, "MEDICINES"));
        assertThat(second.added()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        verify(records, never()).saveSectionsLocked(any(), any(), anyString(), any());
    }

    @Test
    void copyWithNoSourceIs400OnSourceAndNotAnUnhandledError() {
        // DEC-051 M4: Set.of(..).contains(null) threw NullPointerException (500)
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, new CopyItemsInput(null, null, "MEDICINES")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(m -> m.field()).containsExactly("source"));
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, new CopyItemsInput(null, null, null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, null)).isInstanceOf(InvalidFieldsException.class);
        verify(records, never()).saveSectionsLocked(any(), any(), anyString(), any());
    }

    @Test
    void copyRefusesBadInputASignedTargetAndASourceThatIsNotSignedOrNotThePatients() {
        when(records.findByIdForUpdate(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, new CopyItemsInput("PAST_RECORD", null, "TESTS")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD,
                new CopyItemsInput("LAST_SIGNED_PRESCRIPTION", UUID.randomUUID(), "MEDICINES")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD,
                new CopyItemsInput("LAST_SIGNED_PRESCRIPTION", null, "MEDICINES")))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("past-visit-not-found"));

        UUID other = UUID.randomUUID();
        RecordRow notSigned = new RecordRow(other, "OPD_VISIT", UUID.randomUUID(), null, null, null, PATIENT, MEMBER,
                "gm", "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "X", "DRAFT", null, "{}", "ASSESSMENT",
                null, false, null, 1, NOW);
        when(records.findById(TENANT, other)).thenReturn(Optional.of(notSigned));
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, new CopyItemsInput("PAST_RECORD", other, "TESTS")))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("past-visit-not-found"));

        when(records.findByIdForUpdate(TENANT, RECORD)).thenReturn(Optional.of(record("SIGNED", "{}")));
        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, new CopyItemsInput("PAST_RECORD", other, "TESTS")))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("emr-record-signed"));
    }

    @Test
    void testsAreCopiedOnlyWhereTheLayoutHasAnInvestigationsList() {
        UUID signedId = UUID.randomUUID();
        when(records.findByIdForUpdate(TENANT, RECORD)).thenReturn(Optional.of(record("DRAFT",
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        RecordRow signed = new RecordRow(signedId, "OPD_VISIT", UUID.randomUUID(), null, null, null, PATIENT, MEMBER,
                "gm", "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "X", "SIGNED", NOW, "{}", "ASSESSMENT",
                null, false, null, 1, NOW);
        when(records.findById(TENANT, signedId)).thenReturn(Optional.of(signed));

        assertThatThrownBy(() -> service.copyItems(DOCTOR, RECORD, new CopyItemsInput("PAST_RECORD", signedId, "TESTS")))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("section-not-in-layout"));
    }

    // ---- print preferences ----

    @Test
    void printPreferencesAreCheckedAndStoredWithoutARevisionOrAnAudit() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("SIGNED", "{}")));

        EmrViews.PrintPreferences saved = service.setPrintPreferences(DOCTOR, RECORD,
                new PrintPreferencesInput("MR", "A5"));

        assertThat(saved).isEqualTo(new EmrViews.PrintPreferences("MR", "A5"));
        verify(records).savePrintPreferences(TENANT, RECORD, "MR", "A5", USER);
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> service.setPrintPreferences(DOCTOR, RECORD, new PrintPreferencesInput("FR", null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.setPrintPreferences(DOCTOR, RECORD, new PrintPreferencesInput(null, "A3")))
                .isInstanceOf(InvalidFieldsException.class);
        service.setPrintPreferences(DOCTOR, RECORD, new PrintPreferencesInput(null, null));
        verify(records).savePrintPreferences(TENANT, RECORD, null, null, USER);
    }
}
