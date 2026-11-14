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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrEncounterSource.EmergencyCaseFacts;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrInputs.AdmissionAdviceInput;
import com.sahaj.opd.application.emr.EmrInputs.OpenInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordSectionInput;
import com.sahaj.opd.application.emr.EmrInputs.SwitchDepartmentInput;
import com.sahaj.opd.application.emr.EmrInputs.UiStateInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.NewRecord;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.EmrViews.DepartmentRef;
import com.sahaj.opd.application.emr.EmrViews.OpenResult;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.patient.api.AllergyStatus;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
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
import org.springframework.security.access.AccessDeniedException;

class EmrRecordServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final EmrRecordRepository records = mock(EmrRecordRepository.class);
    private final EmrDepartmentResolver resolver = mock(EmrDepartmentResolver.class);
    private final EmrLayouts layouts = mock(EmrLayouts.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final EmrEncounterSource encounters = mock(EmrEncounterSource.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final EmrMedicineRepository medicines = mock(EmrMedicineRepository.class);
    private final com.sahaj.opd.api.ServiceLookupPort services = mock(com.sahaj.opd.api.ServiceLookupPort.class);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<com.sahaj.opd.api.EmrVisitChargePort> charges =
            mock(org.springframework.beans.factory.ObjectProvider.class);

    private EmrRecordService service;
    private List<LayoutSection> layout;

    @BeforeEach
    void setUp() {
        layout = List.of(
                EmrTestData.section("complaints", "COMMON", "ASSESSMENT", true, true, false,
                        EmrTestData.field("complaint", "SEARCH_LIST", true), EmrTestData.field("note", "TEXT", false)),
                EmrTestData.section("vitals", "COMMON", "ASSESSMENT", true, false, false,
                        EmrTestData.field("pulse", "NUMBER", false), EmrTestData.field("trend", "CHART", false)),
                EmrTestData.section("hidden_one", "COMMON", "TREATMENT", false, false, false,
                        EmrTestData.field("x", "TEXT", false)));
        TeamMember doctor = new TeamMember(MEMBER, "Dr Test", "DOCTOR", true, 0);
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(doctor);
        when(team.find(TENANT, MEMBER)).thenReturn(Optional.of(doctor));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        Department gm = new Department(null, "gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of("Room 2", "Room 3"), 0, 0, null);
        Published published = new Published("STARTER", null, null, 1, layout);
        when(resolver.choose(TENANT, MEMBER)).thenReturn(new Resolution(gm, published, "DOCTOR_SPECIALTY"));
        when(resolver.switchOptions(TENANT)).thenReturn(List.of(new DepartmentRef("gm", "General Medicine")));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1, layout));
        when(layouts.find(TENANT, "gm")).thenReturn(Optional.of(gm));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-000123",
                "Meena Patil", 41, "F", "None known", "Type 2 diabetes")));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(MEMBER, "WAITING")));
        when(visits.previousVisitAt(eq(TENANT), eq(PATIENT), any())).thenReturn(Optional.of(
                Instant.parse("2026-10-30T06:00:00Z")));
        when(visits.countVisitsUpTo(eq(TENANT), eq(PATIENT), any())).thenReturn(3);
        when(visitService.startConsultation(TENANT, USER, VISIT)).thenReturn("MOVED");
        service = new EmrRecordService(access, records, resolver, layouts, visits, visitService, patients, team,
                encounters, entitlements, audit, EmrTestData.MAPPER, CLOCK, new EmrComponentValues(EmrTestData.MAPPER),
                medicines, services, charges, mock(com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport.class), EmrTestData.finishRepositoryWithFreeHold(),
                EmrTestData.noBean(), EmrTestData.noBean());
    }

    private static VisitRow visit(UUID doctor, String stage) {
        return new VisitRow(VISIT, PATIENT, doctor, "NEW", stage, 7, 50000, Instant.parse("2026-11-12T04:00:00Z"), 1, 1);
    }

    private RecordRow record(String type, String status, long revision, String sections) {
        return new RecordRow(RECORD, type, "OPD_VISIT".equals(type) ? VISIT : null,
                "IPD_ROUND".equals(type) ? ADMISSION : null, "IPD_ROUND".equals(type) ? LocalDate.of(2026, 11, 12) : null,
                "ER_ASSESSMENT".equals(type) ? CASE : null, PATIENT, MEMBER, "gm", "General Medicine", "gm",
                "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", status, null, sections, "ASSESSMENT", null, false, null,
                revision, Instant.parse("2026-11-12T05:00:00Z"));
    }

    private RecordRow opd(long revision) {
        return record("OPD_VISIT", "DRAFT", revision, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson());
    }

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ---- open: OPD ----

    @Test
    void openingAnOpdVisitFirstTimeStoresAReferenceToTheLayoutMovesTheVisitAndAuditsOnce() {
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(opd(0)));

        OpenResult result = service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        assertThat(result.created()).isTrue();
        assertThat(result.stageOutcome()).isEqualTo("MOVED");
        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).lockEncounter(TENANT, VISIT);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        NewRecord r = created.getValue();
        assertThat(r.encounterType()).isEqualTo("OPD_VISIT");
        assertThat(r.opdVisitId()).isEqualTo(VISIT);
        assertThat(r.ipdAdmissionId()).isNull();
        assertThat(r.patientId()).isEqualTo(PATIENT);
        assertThat(r.openedByMembershipId()).isEqualTo(MEMBER);
        assertThat(r.departmentKey()).isEqualTo("gm");
        assertThat(r.layoutKind()).isEqualTo("STARTER_LAYOUT");
        assertThat(r.departmentVersionId()).isNull();
        assertThat(r.starterLayoutVersion()).isEqualTo(1);
        assertThat(r.choiceSource()).isEqualTo("DOCTOR_SPECIALTY");
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_RECORD_OPENED"), eq("EMR_RECORD"), eq(RECORD), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("created", true).containsEntry("encounterType", "OPD_VISIT")
                .containsEntry("departmentKey", "gm").containsEntry("encounterId", VISIT);
        assertThat(after.getValue().toString()).doesNotContain("Meena");
    }

    @Test
    void openingAgainFindsTheSameRecordWritesNothingNewAndStillAudits() {
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.of(opd(4)));
        when(visitService.startConsultation(TENANT, USER, VISIT)).thenReturn("ALREADY_WITH_DOCTOR");

        OpenResult result = service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        assertThat(result.created()).isFalse();
        assertThat(result.stageOutcome()).isEqualTo("ALREADY_WITH_DOCTOR");
        assertThat(result.record().id()).isEqualTo(RECORD);
        assertThat(result.record().revision()).isEqualTo(4);
        verify(records, never()).insert(any(), any(), any());
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_RECORD_OPENED"), eq("EMR_RECORD"), eq(RECORD), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("created", false);
    }

    @Test
    void aDoctorOpensOnlyTheirOwnPatientsButAnOwnerOpensAny() {
        UUID otherDoctor = UUID.randomUUID();
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(otherDoctor, "WAITING")));

        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.slug()).isEqualTo("not-own-patient");
                });
        verify(records, never()).insert(any(), any(), any());
        verifyNoInteractions(audit);
        verifyNoInteractions(visitService);

        UUID ownerMember = UUID.randomUUID();
        ClinicUser owner = new ClinicUser(USER, TENANT, "OWNER");
        when(access.requireConsultationWrite(owner)).thenReturn(new TeamMember(ownerMember, "Dr Owner", "OWNER", true, 0));
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.of(opd(0)));
        when(visitService.startConsultation(TENANT, USER, VISIT)).thenReturn("MOVED");
        assertThat(service.open(owner, new OpenInput("OPD_VISIT", VISIT)).created()).isFalse();
    }

    @Test
    void aVisitOfAnotherClinicIsNotFoundAndABadBodyIsValidation() {
        UUID other = UUID.randomUUID();
        when(visits.findById(TENANT, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("OPD_VISIT", other)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("encounter-not-found"));
        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("WHATEVER", VISIT)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("OPD_VISIT", null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.open(DOCTOR, null)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theRoleCheckComesFirstAndAWrongRoleTouchesNothing() {
        ClinicUser nurse = new ClinicUser(USER, TENANT, "NURSE");
        when(access.requireConsultationWrite(nurse)).thenThrow(new AccessDeniedException("no"));

        assertThatThrownBy(() -> service.open(nurse, new OpenInput("OPD_VISIT", VISIT)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.get(nurse, RECORD)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.saveDraft(nurse, RECORD, null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(records, visits, audit, visitService);
    }

    @Test
    void copyFromLastVisitPrefillsOnlyCopySectionsAndWaitsForConfirmation() {
        List<LayoutSection> withCopy = List.of(
                layout.get(0).withSettings(new EmrModel.SectionSettings("complaints", "ASSESSMENT", true, true, false,
                        false, false, List.of("DOCTOR")), 1),
                layout.get(1).withSettings(new EmrModel.SectionSettings("vitals", "ASSESSMENT", true, false, false,
                        true, false, List.of("DOCTOR")), 2));
        Department stored = layouts.find(TENANT, "gm").orElseThrow();
        // a department with no specialty screen: its sections copy by the generic rule (a specialty component copies by its own)
        Department gm = new Department(stored.row(), stored.key(), stored.name(), stored.initials(), stored.colour(),
                "general", stored.sourceKind(), stored.copiedFromKey(), stored.rooms(), stored.publishedVersion(),
                stored.revision(), stored.draftLayout());
        when(resolver.choose(TENANT, MEMBER)).thenReturn(new Resolution(gm,
                new Published("STARTER", null, null, 1, withCopy), "DOCTOR_SPECIALTY"));
        EmrRecordDocument previous = EmrRecordDocument.empty(EmrTestData.MAPPER);
        previous.put("vitals", (com.fasterxml.jackson.databind.node.ObjectNode) json("{\"pulse\":70}"), true, NOW, MEMBER);
        previous.put("complaints", (com.fasterxml.jackson.databind.node.ObjectNode) json("{\"note\":\"old\"}"), true,
                NOW, MEMBER);
        UUID last = UUID.randomUUID();
        when(records.latestSigned(TENANT, PATIENT, "gm")).thenReturn(Optional.of(new SignedCopy(last,
                Instant.parse("2026-10-01T05:00:00Z"), previous.toJson())));
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(opd(0)));

        service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));

        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, created.getValue().sections());
        assertThat(doc.has("vitals")).isTrue();
        assertThat(doc.values("vitals").path("pulse").asInt()).isEqualTo(70);
        assertThat(doc.copiedFromRecord("vitals")).isEqualTo(last);
        assertThat(doc.copiedFromDate("vitals")).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(doc.copyPending("vitals")).isTrue();
        assertThat(doc.has("complaints")).isFalse();
    }

    /** The layout of the copy tests: Menstrual with the 0.7.1 choice rows and a plain Notes section, both copying. */
    private List<LayoutSection> copyLayout(String lastMenstrual, String lastNotes) {
        List<LayoutSection> copying = List.of(
                new LayoutSection("menstrual", "LIBRARY", "Menstrual", "SPECIALTY", "", "ASSESSMENT", 1, true, false,
                        false, true, false, List.of("DOCTOR"), false, null,
                        List.of(EmrTestData.choice("cycle", false, "regular", "irregular"),
                                EmrTestData.choice("flow", false, "scanty", "normal", "heavy", "with_clots"),
                                EmrTestData.field("lmp", "DATE", false))),
                new LayoutSection("notes", "LIBRARY", "Notes", "COMMON", "", "ASSESSMENT", 2, true, false, false, true,
                        false, List.of("DOCTOR"), false, null, List.of(EmrTestData.field("note", "TEXT", false))));
        Department stored = layouts.find(TENANT, "gm").orElseThrow();
        Department gm = new Department(stored.row(), stored.key(), stored.name(), stored.initials(), stored.colour(),
                "general", stored.sourceKind(), stored.copiedFromKey(), stored.rooms(), stored.publishedVersion(),
                stored.revision(), stored.draftLayout());
        when(resolver.choose(TENANT, MEMBER)).thenReturn(new Resolution(gm,
                new Published("STARTER", null, null, 3, copying), "DOCTOR_SPECIALTY"));
        EmrRecordDocument previous = EmrRecordDocument.empty(EmrTestData.MAPPER);
        if (lastMenstrual != null) {
            previous.put("menstrual", (com.fasterxml.jackson.databind.node.ObjectNode) json(lastMenstrual), true, NOW,
                    MEMBER);
        }
        if (lastNotes != null) {
            previous.put("notes", (com.fasterxml.jackson.databind.node.ObjectNode) json(lastNotes), true, NOW, MEMBER);
        }
        when(records.latestSigned(TENANT, PATIENT, "gm")).thenReturn(Optional.of(new SignedCopy(UUID.randomUUID(),
                Instant.parse("2026-10-01T05:00:00Z"), previous.toJson())));
        when(records.findOpd(TENANT, VISIT)).thenReturn(Optional.empty());
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(opd(0)));
        return copying;
    }

    private EmrRecordDocument openAndReadTheCopy() {
        service.open(DOCTOR, new OpenInput("OPD_VISIT", VISIT));
        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        return EmrRecordDocument.parse(EmrTestData.MAPPER, created.getValue().sections());
    }

    @Test
    void copyFromLastVisitDropsAValueThatNoLongerFitsAndKeepsTheOnesThatDo() {
        copyLayout("{\"cycle\":\"Regular, 28 days\",\"flow\":\"heavy\",\"lmp\":\"2026-09-20\"}", null);

        EmrRecordDocument doc = openAndReadTheCopy();

        assertThat(doc.has("menstrual")).isTrue();
        assertThat(doc.values("menstrual").has("cycle")).isFalse();
        assertThat(doc.values("menstrual").path("flow").asText()).isEqualTo("heavy");
        assertThat(doc.values("menstrual").path("lmp").asText()).isEqualTo("2026-09-20");
        assertThat(doc.copyPending("menstrual")).isTrue();
    }

    @Test
    void aSectionWhereNothingFitsIsNotCopiedAndOtherSectionsStillAre() {
        copyLayout("{\"flow\":\"spotting\",\"cycle\":true}", "{\"note\":\"old\"}");

        EmrRecordDocument doc = openAndReadTheCopy();

        assertThat(doc.has("menstrual")).isFalse();
        assertThat(doc.values("notes").path("note").asText()).isEqualTo("old");
    }

    @Test
    void whatIsCopiedPassesTheFirstDraftSaveWithoutAnError() {
        List<LayoutSection> copying = copyLayout("{\"cycle\":\"Regular, 28 days\",\"flow\":\"normal\"}", null);
        EmrRecordDocument doc = openAndReadTheCopy();
        List<InvalidFieldsException.FieldMessage> errors = new java.util.ArrayList<>();

        com.fasterxml.jackson.databind.node.ObjectNode cleaned = service.cleanValues("sections[0]", copying.get(0),
                doc.values("menstrual"), errors, null, new EmrComponentValues.Context(NOW, MEMBER, null, null),
                new java.util.ArrayList<>());

        assertThat(errors).isEmpty();
        assertThat(cleaned.path("flow").asText()).isEqualTo("normal");
    }

    // ---- open: IPD and Emergency ----

    private AdmissionFacts admission(String status, Access access) {
        return new AdmissionFacts(ADMISSION, PATIENT, "26-27/0001", status, 3, MEMBER, access);
    }

    @Test
    void anIpdOpenNeedsIpdOnAndTheIpdScopeAndNeverMovesAnOpdStage() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)))
                .isInstanceOf(ModuleOffException.class);

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("trial", false, 25));
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("encounter-not-found"));

        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED", Access.OUTSIDE_SCOPE)));
        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(403);
                    assertThat(ex.slug()).isEqualTo("outside-scope");
                });

        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED", Access.FORBIDDEN)));
        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)))
                .isInstanceOf(AccessDeniedException.class);

        verify(records, never()).insert(any(), any(), any());
        verifyNoInteractions(audit, visitService);
    }

    @Test
    void anAdmittedPatientGetsOneRecordPerDayWithTheRoundDateInIndiaTime() {
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED", Access.ALLOWED)));
        when(records.findIpd(TENANT, ADMISSION, LocalDate.of(2026, 11, 12))).thenReturn(Optional.empty());
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(
                record("IPD_ROUND", "DRAFT", 0, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));

        OpenResult result = service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION));

        assertThat(result.created()).isTrue();
        assertThat(result.stageOutcome()).isEqualTo("NOT_APPLICABLE");
        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        assertThat(created.getValue().ipdAdmissionId()).isEqualTo(ADMISSION);
        assertThat(created.getValue().opdVisitId()).isNull();
        assertThat(created.getValue().roundDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(result.record().header().admission().dayNumber()).isEqualTo(3);
        assertThat(result.record().header().admission().ipNumber()).isEqualTo("26-27/0001");
        assertThat(result.record().header().visitOrdinal()).isNull();
        verifyNoInteractions(visitService);
    }

    @Test
    void aDischargedAdmissionOpensAnExistingRecordReadOnlyButNeverStartsANewOne() {
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("DISCHARGED", Access.ALLOWED)));
        when(records.latestIpd(TENANT, ADMISSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("encounter-closed"));

        when(records.latestIpd(TENANT, ADMISSION)).thenReturn(Optional.of(
                record("IPD_ROUND", "DRAFT", 2, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        RecordView view = service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)).record();

        assertThat(view.readOnly()).isTrue();
        assertThat(view.readOnlyReason()).isEqualTo("ENCOUNTER_CLOSED");
        assertThat(view.departmentOptions()).isEmpty();
    }

    @Test
    void whenIpdIsReadOnlyNoNewIpdRecordStartsButAnExistingOneOpensReadOnly() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 25));
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED", Access.ALLOWED)));
        when(records.findIpd(TENANT, ADMISSION, LocalDate.of(2026, 11, 12))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("ipd-read-only"));

        when(records.findIpd(TENANT, ADMISSION, LocalDate.of(2026, 11, 12))).thenReturn(Optional.of(
                record("IPD_ROUND", "DRAFT", 0, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        RecordView view = service.open(DOCTOR, new OpenInput("IPD_ROUND", ADMISSION)).record();
        assertThat(view.readOnlyReason()).isEqualTo("IPD_READ_ONLY");
        assertThat(view.ipd().state()).isEqualTo("READ_ONLY");
    }

    @Test
    void anEmergencyCaseWithNoPatientShowsTheCaseNameAgeAndSexAndNoPastVisits() {
        EmergencyCaseFacts facts = new EmergencyCaseFacts(CASE, null, "WAITING", 1, "Red", "Unknown male, about 40",
                40, "M", Access.ALLOWED);
        when(encounters.emergencyCase(TENANT, CASE)).thenReturn(Optional.of(facts));
        when(records.findEmergency(TENANT, CASE)).thenReturn(Optional.empty());
        RecordRow stored = new RecordRow(RECORD, "ER_ASSESSMENT", null, null, null, CASE, null, MEMBER, "gm",
                "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "CLINIC_DEFAULT", "DRAFT", null,
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson(), "ASSESSMENT", null, false, null, 0,
                Instant.parse("2026-11-12T05:00:00Z"));
        when(records.findById(eq(TENANT), any(UUID.class))).thenReturn(Optional.of(stored));

        OpenResult result = service.open(DOCTOR, new OpenInput("ER_ASSESSMENT", CASE));

        ArgumentCaptor<NewRecord> created = ArgumentCaptor.forClass(NewRecord.class);
        verify(records).insert(eq(TENANT), created.capture(), eq(USER));
        assertThat(created.getValue().patientId()).isNull();
        assertThat(created.getValue().erCaseId()).isEqualTo(CASE);
        EmrViews.RecordHeader header = result.record().header();
        assertThat(header.patientId()).isNull();
        assertThat(header.allergyStatus()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(header.allergyText()).isNull();
        assertThat(header.patientName()).isEqualTo("Unknown male, about 40");
        assertThat(header.age()).isEqualTo(40);
        assertThat(header.sex()).isEqualTo("M");
        assertThat(header.uhid()).isNull();
        assertThat(header.lastVisitDaysAgo()).isNull();
        assertThat(header.emergency().triageLevelName()).isEqualTo("Red");
        verifyNoInteractions(patients, visitService);
    }

    @Test
    void aClosedEmergencyCaseWithNoRecordIsRefused() {
        when(encounters.emergencyCase(TENANT, CASE)).thenReturn(Optional.of(new EmergencyCaseFacts(CASE, null,
                "CLOSED", 3, "Green", "x", null, null, Access.ALLOWED)));
        when(records.findEmergency(TENANT, CASE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(DOCTOR, new OpenInput("ER_ASSESSMENT", CASE)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("encounter-closed"));
    }

    // ---- the view ----

    @Test
    void theRecordViewHoldsShownSectionsTheHeaderTagsAndTheDoneState() {
        EmrRecordDocument doc = EmrRecordDocument.empty(EmrTestData.MAPPER);
        doc.put("vitals", (com.fasterxml.jackson.databind.node.ObjectNode) json("{\"pulse\":72}"), false, NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 5, doc.toJson())));

        RecordView view = service.get(DOCTOR, RECORD);

        assertThat(view.encounterType()).isEqualTo("OPD_VISIT");
        assertThat(view.encounterId()).isEqualTo(VISIT);
        assertThat(view.revision()).isEqualTo(5);
        assertThat(view.readOnly()).isFalse();
        assertThat(view.layout().sections()).extracting(LayoutSection::key).containsExactly("complaints", "vitals");
        assertThat(view.layout().layoutSource()).isEqualTo("STARTER");
        assertThat(view.layout().versionNo()).isEqualTo(1);
        assertThat(view.layout().takenAt()).isEqualTo(Instant.parse("2026-11-12T05:00:00Z"));
        assertThat(view.sections()).extracting(EmrViews.RecordSection::status).containsExactly("REQUIRED", "DONE");
        assertThat(view.sections().get(1).values().path("pulse").asInt()).isEqualTo(72);
        assertThat(view.stillNeeded()).containsExactly("complaints");
        assertThat(view.readyToComplete()).isFalse();
        assertThat(view.parts().get(0).requiredTotal()).isEqualTo(1);
        assertThat(view.uiState().part()).isEqualTo("ASSESSMENT");
        assertThat(view.departmentOptions()).extracting(DepartmentRef::departmentKey).containsExactly("gm");
        assertThat(view.ipd().state()).isEqualTo("ON");
        assertThat(view.ipd().canSwitchOn()).isFalse();
        EmrViews.RecordHeader header = view.header();
        assertThat(header.patientName()).isEqualTo("Meena Patil");
        assertThat(header.initials()).isEqualTo("MP");
        assertThat(header.allergyText()).isNull();
        assertThat(header.allergyStatus()).isEqualTo(AllergyStatus.NONE_RECORDED);
        assertThat(header.chronicText()).isEqualTo("Type 2 diabetes");
        assertThat(header.uhid()).isEqualTo("UH-000123");
        assertThat(header.visitOrdinal()).isEqualTo(3);
        assertThat(header.lastVisitDaysAgo()).isEqualTo(13);
        assertThat(header.visitType()).isEqualTo("NEW");
        assertThat(header.doctor().name()).isEqualTo("Dr Test");
        assertThat(header.doctor().room()).isEqualTo("Room 2");
        assertThat(header.admission()).isNull();
        assertThat(header.toString()).doesNotContain("Meena");
    }

    @Test
    void aSignedRecordIsReadOnlyAndAnUnknownRecordIs404() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "SIGNED", 9, null)));

        RecordView view = service.get(DOCTOR, RECORD);
        assertThat(view.readOnly()).isTrue();
        assertThat(view.readOnlyReason()).isEqualTo("SIGNED");
        assertThat(view.departmentOptions()).isEmpty();

        UUID missing = UUID.randomUUID();
        when(records.findById(TENANT, missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(DOCTOR, missing)).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("record-not-found"));
    }

    @Test
    void anotherDoctorsOpdRecordCannotBeRead() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(0)));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(visit(UUID.randomUUID(), "WITH_DOCTOR")));

        assertThatThrownBy(() -> service.get(DOCTOR, RECORD)).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("not-own-patient"));
    }

    private EmrViews.RecordHeader headerWithAllergy(String allergyText) {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-000123",
                "Meena Patil", 41, "F", allergyText, null)));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(1)));
        return service.get(DOCTOR, RECORD).header();
    }

    @Test
    void theHeaderAllergyStatusIsRecordedOnlyForARealAllergyAndTheTextIsNullOtherwise() {
        EmrViews.RecordHeader recorded = headerWithAllergy("Penicillin");
        assertThat(recorded.allergyStatus()).isEqualTo(AllergyStatus.RECORDED);
        assertThat(recorded.allergyText()).isEqualTo("Penicillin");

        for (String none : new String[] {"No known allergy", "no known drug allergies.", "NKDA", " None ",
            "none known"}) {
            EmrViews.RecordHeader header = headerWithAllergy(none);
            assertThat(header.allergyStatus()).as(none).isEqualTo(AllergyStatus.NONE_RECORDED);
            assertThat(header.allergyText()).as(none).isNull();
        }
        for (String nothing : new String[] {null, "", "   ", "Not recorded", "Unknown", "?"}) {
            EmrViews.RecordHeader header = headerWithAllergy(nothing);
            assertThat(header.allergyStatus()).as(String.valueOf(nothing)).isEqualTo(AllergyStatus.NOT_RECORDED);
            assertThat(header.allergyText()).as(String.valueOf(nothing)).isNull();
        }
    }

    @Test
    void placeholderAllergyTextAndInitialsAreWorkedOutForTheTags() {
        assertThat(EmrRecordService.clean(null)).isNull();
        assertThat(EmrRecordService.clean("  ")).isNull();
        assertThat(EmrRecordService.clean("None known")).isNull();
        assertThat(EmrRecordService.clean("Not recorded")).isNull();
        assertThat(EmrRecordService.clean(" Penicillin ")).isEqualTo("Penicillin");
        assertThat(EmrRecordService.initials("Meena Patil")).isEqualTo("MP");
        assertThat(EmrRecordService.initials("Asha")).isEqualTo("A");
        assertThat(EmrRecordService.initials("Unknown male, about 40")).isEqualTo("UM");
        assertThat(EmrRecordService.initials("123")).isEqualTo("?");
    }

    // ---- Save draft ----

    private RecordDraftInput draft(long revision, RecordSectionInput... sections) {
        return new RecordDraftInput(revision, new UiStateInput("TREATMENT", "vitals"), List.of(sections));
    }

    private RecordSectionInput section(String key, String values, Boolean confirm) {
        return new RecordSectionInput(key, json(values), confirm);
    }

    @Test
    void savingStoresTheChangedSectionsKeepsTheOthersAndAuditsTheKeysOnly() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.put("complaints", (com.fasterxml.jackson.databind.node.ObjectNode) json("{\"note\":\"kept\"}"), false,
                NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 5, stored.toJson())));
        when(records.saveDraft(eq(TENANT), eq(RECORD), eq(5L), anyString(), anyString(), any(), eq(USER)))
                .thenReturn(true);

        service.saveDraft(DOCTOR, RECORD, draft(5, section("vitals", "{\"pulse\":72,\"trend\":null}", null)));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(5L), saved.capture(), eq("TREATMENT"), eq("vitals"),
                eq(USER));
        EmrRecordDocument doc = EmrRecordDocument.parse(EmrTestData.MAPPER, saved.getValue());
        assertThat(doc.values("vitals").path("pulse").asInt()).isEqualTo(72);
        assertThat(doc.values("vitals").has("trend")).isFalse();
        assertThat(doc.values("complaints").path("note").asText()).isEqualTo("kept");
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_RECORD_DRAFT_SAVED"), eq("EMR_RECORD"), eq(RECORD), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("revision", 6L).containsEntry("sectionKeys", List.of("vitals"));
        assertThat(after.getValue().toString()).doesNotContain("72");
    }

    @Test
    void aSaveWithNoSectionsSavesThePositionOnly() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(0)));
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.saveDraft(DOCTOR, RECORD, new RecordDraftInput(0L, new UiStateInput("ASSESSMENT", null), List.of()));

        verify(records).saveDraft(eq(TENANT), eq(RECORD), eq(0L), anyString(), eq("ASSESSMENT"), eq(null), eq(USER));
    }

    @Test
    void confirmCopyConfirmsCopiedValues() {
        EmrRecordDocument stored = EmrRecordDocument.empty(EmrTestData.MAPPER);
        stored.putCopied("vitals", json("{\"pulse\":70}"), UUID.randomUUID(), LocalDate.of(2026, 10, 1), NOW, MEMBER);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "DRAFT", 1, stored.toJson())));
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.saveDraft(DOCTOR, RECORD, draft(1, section("vitals", "{\"pulse\":70}", true)));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(records).saveDraft(any(), any(), anyLong(), saved.capture(), anyString(), any(), any());
        assertThat(EmrRecordDocument.parse(EmrTestData.MAPPER, saved.getValue()).copyPending("vitals")).isFalse();
    }

    @Test
    void valuesThatDoNotFitAreRefusedTogetherWithFieldNamesAndNothingIsSaved() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(0)));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(0,
                section("vitals", "{\"pulse\":\"fast\",\"trend\":5,\"nothere\":1}", null),
                section("hidden_one", "{\"x\":\"a\"}", null),
                section("complaints", "[1]", null))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> {
                    assertThat(ex.errors()).extracting(e -> e.field()).containsExactlyInAnyOrder(
                            "sections[0].values.pulse", "sections[0].values.trend", "sections[0].values",
                            "sections[1].key", "sections[2].values");
                    assertThat(ex.errors().toString()).doesNotContain("fast");
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void theUiPositionMustBeValid() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(0)));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, new RecordDraftInput(0L,
                new UiStateInput("MIDDLE", "hidden_one"), List.of()))).isInstanceOfSatisfying(
                InvalidFieldsException.class, ex -> assertThat(ex.errors()).extracting(e -> e.field())
                        .containsExactly("uiState.part", "uiState.sectionKey"));
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, new RecordDraftInput(0L, null, List.of())))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aStaleRevisionIs409AndALostRaceIs409() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(5)));

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(4))).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("emr-record-changed");
                });
        when(records.saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(5))).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("emr-record-changed"));
        verifyNoInteractions(audit);
    }

    @Test
    void aSignedRecordAndADischargedIpdRecordCannotBeSaved() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "SIGNED", 1, null)));
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(1))).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("emr-record-signed"));

        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(
                record("IPD_ROUND", "DRAFT", 1, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("DISCHARGED", Access.ALLOWED)));
        // ADR-025 D5: IPD and Emergency drafts are switched on; a closed encounter is what refuses the save now
        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, draft(1))).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.slug()).isEqualTo("encounter-closed");
                });
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void aSectionOverThirtyTwoKilobytesIsRefused() {
        LayoutSection big = EmrTestData.section("big", "COMMON", "ASSESSMENT", true, false, false,
                EmrTestData.field("a", "RICH_TEXT", false), EmrTestData.field("b", "RICH_TEXT", false),
                EmrTestData.field("c", "RICH_TEXT", false), EmrTestData.field("d", "RICH_TEXT", false));
        when(layouts.snapshot(eq(TENANT), any(RecordRow.class))).thenReturn(new Snapshot("STARTER", 1,
                List.of(big)));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(0)));
        String text = "x".repeat(9000);
        String values = "{\"a\":\"" + text + "\",\"b\":\"" + text + "\",\"c\":\"" + text + "\",\"d\":\"" + text
                + "\"}";

        assertThatThrownBy(() -> service.saveDraft(DOCTOR, RECORD, new RecordDraftInput(0L,
                new UiStateInput("ASSESSMENT", null), List.of(section("big", values, null)))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field()).containsExactly("sections[0].values"));
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    // ---- switch department ----

    @Test
    void switchingReplacesTheLayoutReferenceKeepsTheValuesAndAudits() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(2)));
        Department dental = new Department(null, "dental", "Dental", "DE", "#2E7D32", "dental", "STARTER", null,
                List.of(), 0, 0, null);
        when(resolver.switchTo(TENANT, "dental")).thenReturn(new Resolution(dental,
                new Published("STARTER", null, null, 4, layout), "SWITCHED"));
        when(records.switchLayout(eq(TENANT), eq(RECORD), eq(2L), eq("dental"), eq("Dental"), eq("dental"),
                eq("STARTER_LAYOUT"), eq(null), eq(4), eq("SWITCHED"), eq(USER))).thenReturn(true);

        service.switchDepartment(DOCTOR, RECORD, new SwitchDepartmentInput("dental", 2L));

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_RECORD_DEPARTMENT_SWITCHED"), eq("EMR_RECORD"), eq(RECORD), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("departmentKey", "gm");
        assertThat(after.getValue()).containsEntry("departmentKey", "dental").containsEntry("versionNo", 4);
        verify(records, never()).saveDraft(any(), any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void switchingToThePresentDepartmentWritesNothingAndASignedOrStaleRecordIsRefused() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(2)));
        Department dental = new Department(null, "dental", "Dental", "DE", "#2E7D32", "dental", "STARTER", null,
                List.of(), 0, 0, null);
        when(resolver.switchTo(TENANT, "dental")).thenReturn(new Resolution(dental,
                new Published("STARTER", null, null, 4, layout), "SWITCHED"));
        service.switchDepartment(DOCTOR, RECORD, new SwitchDepartmentInput("gm", 2L));
        verify(records, never()).switchLayout(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(),
                any());
        verifyNoInteractions(audit);

        assertThatThrownBy(() -> service.switchDepartment(DOCTOR, RECORD, new SwitchDepartmentInput("dental", 1L)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("emr-record-changed"));

        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "SIGNED", 2, null)));
        assertThatThrownBy(() -> service.switchDepartment(DOCTOR, RECORD, new SwitchDepartmentInput("dental", 2L)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("emr-record-signed"));
        assertThatThrownBy(() -> service.switchDepartment(DOCTOR, RECORD, new SwitchDepartmentInput(null, 2L)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    // ---- admission advice ----

    @Test
    void adviceSetsTheFlagAndTheReferralAndAuditsOnlyThatThereIsAReferral() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(3)));
        when(records.adviseAdmission(TENANT, RECORD, 3L, "Refer to Dr Kale at the district hospital", USER))
                .thenReturn(true);

        service.adviseAdmission(DOCTOR, RECORD, new AdmissionAdviceInput(3L,
                " Refer to Dr Kale at the district hospital "));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("EMR_ADMISSION_ADVISED"), eq("EMR_RECORD"), eq(RECORD), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("withReferral", true);
        assertThat(after.getValue().toString()).doesNotContain("Kale");
    }

    @Test
    void repeatingTheSameAdviceChangesNothing() {
        RecordRow advised = new RecordRow(RECORD, "OPD_VISIT", VISIT, null, null, null, PATIENT, MEMBER, "gm",
                "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "DRAFT", null,
                EmrRecordDocument.empty(EmrTestData.MAPPER).toJson(), "ASSESSMENT", null, true, "Refer", 4,
                Instant.parse("2026-11-12T05:00:00Z"));
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(advised));

        RecordView view = service.adviseAdmission(DOCTOR, RECORD, new AdmissionAdviceInput(4L, "Refer"));

        assertThat(view.admissionAdvised()).isTrue();
        verify(records, never()).adviseAdmission(any(), any(), anyLong(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void adviceDoesNotApplyToAnIpdRoundAndTheReferralLineHasALimit() {
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(
                record("IPD_ROUND", "DRAFT", 1, EmrRecordDocument.empty(EmrTestData.MAPPER).toJson())));
        when(encounters.admission(TENANT, ADMISSION)).thenReturn(Optional.of(admission("ADMITTED", Access.ALLOWED)));
        assertThatThrownBy(() -> service.adviseAdmission(DOCTOR, RECORD, new AdmissionAdviceInput(1L, null)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("not-applicable"));

        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(opd(1)));
        assertThatThrownBy(() -> service.adviseAdmission(DOCTOR, RECORD,
                new AdmissionAdviceInput(1L, "x".repeat(301)))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.adviseAdmission(DOCTOR, RECORD, new AdmissionAdviceInput(1L, "  ")))
                .isInstanceOf(InvalidFieldsException.class);
        when(records.findById(TENANT, RECORD)).thenReturn(Optional.of(record("OPD_VISIT", "SIGNED", 1, null)));
        assertThatThrownBy(() -> service.adviseAdmission(DOCTOR, RECORD, new AdmissionAdviceInput(1L, null)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("emr-record-signed"));
        verify(records, never()).adviseAdmission(any(), any(), anyLong(), any(), any());
    }
}
