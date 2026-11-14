package com.sahaj.opd.application.emr.finish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrAdmissionRequestPort;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionRef;
import com.sahaj.opd.application.FollowUpRepository;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.emr.EmrCasePaperService;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Encounter;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrViews;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx;
import com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport;
import com.sahaj.opd.application.emr.specialty.SpecialtyReferenceService;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.NewRequest;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RequestRow;
import com.sahaj.opd.application.emr.finish.FinishModels.AdmissionRequestInput;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-67-3 and SAH-51-6 (ADR-025 D10, D15, H12): the admission request from the EMR (one OPEN per record, idempotent, audited
 * with ids only, closed by an admission) and the reprint of a signed prescription by role.
 */
class EmrAdmissionAndReprintTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-13T05:30:00Z");
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrFinishRepository finish = mock(EmrFinishRepository.class);
    private final EmrEncounterSource encounters = mock(EmrEncounterSource.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final TeamMemberPort teamPort = mock(TeamMemberPort.class);
    private EmrAdmissionService service;
    private TeamMember member;
    private RecordRow row;

    @BeforeEach
    void setUp() {
        service = new EmrAdmissionService(records, finish, encounters, entitlements, patients, teamPort, audit);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 0));
        member = new TeamMember(MEMBERSHIP, "Dr Test", "DOCTOR", true, 0);
        row = mock(RecordRow.class);
        when(row.id()).thenReturn(RECORD);
        when(row.encounterType()).thenReturn("OPD_VISIT");
        when(row.status()).thenReturn("DRAFT");
        when(row.opdVisitId()).thenReturn(VISIT);
        when(row.createdAt()).thenReturn(NOW);
        EmrRecordDocument document = EmrRecordDocument.empty(MAPPER);
        ObjectNode values = MAPPER.createObjectNode();
        ObjectNode list = values.putObject("final");
        list.put("component", "EMR_LIST");
        list.put("kind", "DIAGNOSIS");
        list.putArray("items").addObject().put("text", "Appendicitis").put("active", true);
        document.put("diagnosis", values, false, NOW, MEMBERSHIP);
        VisitRow visit = new VisitRow(VISIT, PATIENT, MEMBERSHIP, "NEW", "WITH_DOCTOR", 1, 0, NOW, 0);
        SignContext context = new SignContext(member, row, new Encounter("OPD_VISIT", VISIT, visit, null, null, false, "on"),
                null, document);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(context);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(false))).thenReturn(context);
        when(teamPort.find(TENANT, OTHER_DOCTOR)).thenReturn(Optional.of(new TeamMember(OTHER_DOCTOR, "Dr Other", "DOCTOR",
                true, 0)));
    }

    private static final UUID OTHER_DOCTOR = UUID.randomUUID();

    private void encounterIs(Encounter encounter, TeamMember caller) {
        SignContext context = new SignContext(caller, row, encounter, null, EmrRecordDocument.empty(MAPPER));
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(context);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(false))).thenReturn(context);
    }

    private RequestRow stored(String status, String hash) {
        return new RequestRow(UUID.randomUUID(), RECORD, "OPD_VISIT", VISIT, null, PATIENT, MEMBERSHIP, "planned", 3,
                "Appendicitis", null, null, status, null, NOW, "key-0001-abcd", hash);
    }

    // ---- the request ----

    @Test
    void aNewRequestStoresTheTypeStayDiagnosesAndNoteMarksAdvisedAndAuditsIdsOnly() {
        when(finish.requestByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());
        when(encounters.openAdmissionOf(TENANT, PATIENT)).thenReturn(Optional.empty());
        RequestRow row = stored("OPEN", "h");
        when(finish.insertRequest(eq(TENANT), any(NewRequest.class), eq(USER))).thenReturn(row);

        EmrAdmissionService.RequestResult result = service.request(DOCTOR, RECORD, "key-0001-abcd",
                new AdmissionRequestInput("planned", 3, " Bed near the nurses "));

        assertThat(result.created()).isTrue();
        org.mockito.ArgumentCaptor<NewRequest> request = org.mockito.ArgumentCaptor.forClass(NewRequest.class);
        verify(finish).insertRequest(eq(TENANT), request.capture(), eq(USER));
        assertThat(request.getValue().reasonText()).isEqualTo("Appendicitis");
        assertThat(request.getValue().note()).isEqualTo("Bed near the nurses");
        verify(finish).markAdvised(TENANT, RECORD, USER);
        verify(audit).record("EMR_ADMISSION_REQUESTED", "EMR_RECORD", RECORD, null,
                Map.of("requestId", row.id(), "admissionType", "planned"));
    }

    @Test
    void anOpenRequestOfTheRecordIsReturnedInsteadOfASecondOne() {
        RequestRow open = stored("OPEN", "h");
        when(finish.requestByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.of(open));

        EmrAdmissionService.RequestResult result = service.request(DOCTOR, RECORD, "key-0002-abcd",
                new AdmissionRequestInput("emergency", null, null));

        assertThat(result.created()).isFalse();
        assertThat(result.view().id()).isEqualTo(open.id());
        verify(finish, never()).insertRequest(any(), any(), any());
    }

    @Test
    void aPatientAlreadyAdmittedAnsweredWithTheIpNumberAndASignedRecordOrIpdOffIsRefused() {
        when(finish.requestByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());
        when(encounters.openAdmissionOf(TENANT, PATIENT)).thenReturn(Optional.of(new AdmissionRef(UUID.randomUUID(),
                "IP/26-27/0042")));

        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", new AdmissionRequestInput("planned", 1, null)))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("already-admitted");
                    assertThat(e.getMessage()).contains("IP/26-27/0042");
                });

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 0));
        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", new AdmissionRequestInput("planned", 1, null)))
                .isInstanceOf(ModuleOffException.class);
    }

    @Test
    void theSameKeyWithAnotherBodyIsAConflictAndABadBodyIsAValidationError() {
        when(finish.requestByKey(TENANT, "key-0001-abcd")).thenReturn(Optional.of(stored("OPEN", "0".repeat(64))));

        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", new AdmissionRequestInput("planned", 2, null)))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("idempotency-conflict");
        for (AdmissionRequestInput bad : List.of(new AdmissionRequestInput("maternity", null, null),
                new AdmissionRequestInput("planned", 61, null), new AdmissionRequestInput("planned", 0, null),
                new AdmissionRequestInput("planned", 1, "x".repeat(301)), new AdmissionRequestInput(null, null, null))) {
            assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", bad))
                    .isInstanceOf(InvalidFieldsException.class);
        }
    }

    private static final AdmissionRequestInput PLANNED = new AdmissionRequestInput("planned", 3, null);

    private static RecordState draftState(UUID holder, boolean active, Instant expires) {
        return new RecordState(RECORD, "DRAFT", null, null, null, null, null, holder, null, expires, active, null);
    }

    @Test
    void aKeyThatStartsWithSignIsReservedForTheSignAndRefusedFromAClient() {
        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "sign-abcdef123456", PLANNED))
                .isInstanceOf(InvalidFieldsException.class);
        verify(finish, never()).insertRequest(any(), any(), any());
    }

    @Test
    void aKeyUsedOnAnotherRecordIsAConflictAndNothingOfThatRecordIsReturned() {
        RequestRow otherRecords = new RequestRow(UUID.randomUUID(), UUID.randomUUID(), "OPD_VISIT", UUID.randomUUID(), null,
                UUID.randomUUID(), MEMBERSHIP, "planned", 3, "Secret diagnosis", null, null, "OPEN", null, NOW,
                "key-0001-abcd", "x");
        when(finish.requestByKey(TENANT, "key-0001-abcd")).thenReturn(Optional.of(otherRecords));

        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("idempotency-conflict");
                    assertThat(e.getMessage()).doesNotContain("Secret");
                });
    }

    @Test
    void theSameKeyOnTheSameRecordAndBodyAnswersTheFirstRequestEvenAfterTheRecordIsSigned() {
        when(finish.requestByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());
        when(finish.insertRequest(eq(TENANT), any(NewRequest.class), eq(USER))).thenReturn(stored("OPEN", "h"));
        service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED);
        org.mockito.ArgumentCaptor<NewRequest> first = org.mockito.ArgumentCaptor.forClass(NewRequest.class);
        verify(finish).insertRequest(eq(TENANT), first.capture(), eq(USER));

        RequestRow stored = stored("OPEN", first.getValue().requestHash());
        when(finish.requestByKey(TENANT, "key-0001-abcd")).thenReturn(Optional.of(stored));
        when(row.status()).thenReturn("SIGNED");

        EmrAdmissionService.RequestResult replay = service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED);

        assertThat(replay.created()).isFalse();
        assertThat(replay.view().id()).isEqualTo(stored.id());
    }

    @Test
    void aClosedEncounterIsRefusedAndASignedRecordIsRefusedBeforeIt() {
        when(finish.requestByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        encounterIs(new Encounter("ER_ASSESSMENT", UUID.randomUUID(), null, null,
                new EmrEncounterSource.EmergencyCaseFacts(UUID.randomUUID(), PATIENT, "CLOSED", 1, "Red", null, null, null,
                        EmrEncounterSource.Access.ALLOWED), true, "on"), member);
        when(row.encounterType()).thenReturn("ER_ASSESSMENT");

        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("encounter-closed");
                });
        when(row.status()).thenReturn("SIGNED");
        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-signed");
        verify(finish, never()).insertRequest(any(), any(), any());
    }

    @Test
    void anotherDoctorsActiveEditHoldRefusesTheRequestWithTheirNameButAnExpiredOrOwnHoldDoesNot() {
        when(finish.requestByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());
        when(encounters.openAdmissionOf(TENANT, PATIENT)).thenReturn(Optional.empty());
        when(finish.insertRequest(eq(TENANT), any(NewRequest.class), eq(USER))).thenReturn(stored("OPEN", "h"));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(draftState(OTHER_DOCTOR, true, NOW.plusSeconds(60))));

        assertThatThrownBy(() -> service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("emr-record-held");
                    assertThat(e.extensions().get("holderName")).isEqualTo("Dr. Dr Other");
                });
        verify(finish, never()).insertRequest(any(), any(), any());

        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(draftState(OTHER_DOCTOR, false, NOW.minusSeconds(60))));
        assertThat(service.request(DOCTOR, RECORD, "key-0001-abcd", PLANNED).created()).isTrue();
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(draftState(MEMBERSHIP, true, NOW.plusSeconds(60))));
        assertThat(service.request(DOCTOR, RECORD, "key-0002-abcd", PLANNED).created()).isTrue();
    }

    @Test
    void theRequestTheSignMakesUsesAReservedKeyDerivedFromTheRecord() {
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());
        when(finish.insertRequest(eq(TENANT), any(NewRequest.class), eq(USER))).thenReturn(stored("OPEN", "h"));
        SignContext context = records.signContext(DOCTOR, RECORD, true);

        service.createInSign(DOCTOR, context, new AdmissionRequestInput("emergency", null, null));

        org.mockito.ArgumentCaptor<NewRequest> made = org.mockito.ArgumentCaptor.forClass(NewRequest.class);
        verify(finish).insertRequest(eq(TENANT), made.capture(), eq(USER));
        assertThat(made.getValue().idempotencyKey()).isEqualTo("sign-" + RECORD.toString().replace("-", ""))
                .hasSize(37).matches("^sign-[0-9a-f]{32}$");

        RequestRow open = stored("OPEN", "h");
        when(finish.openRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.of(open));
        assertThat(service.createInSign(DOCTOR, context, new AdmissionRequestInput("emergency", null, null)))
                .isEqualTo(open.id());
    }

    @Test
    void thePatientMobileOfThePrefillGoesOnlyToACallerWhoCanAdmitDirectly() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Asha Rao", 30, "F",
                "9000000000", false)));
        when(finish.latestRequestOfRecord(TENANT, RECORD)).thenReturn(Optional.empty());

        assertThat(service.prefill(DOCTOR, RECORD).patient().mobile()).isNull();
        assertThat(service.prefill(DOCTOR, RECORD).canAdmitDirectly()).isFalse();

        TeamMember owner = new TeamMember(MEMBERSHIP, "Owner", "OWNER", true, 0);
        VisitRow visit = new VisitRow(VISIT, PATIENT, MEMBERSHIP, "NEW", "WITH_DOCTOR", 1, 0, NOW, 0);
        encounterIs(new Encounter("OPD_VISIT", VISIT, visit, null, null, false, "on"), owner);
        EmrAdmissionService.Prefill forOwner = service.prefill(new ClinicUser(USER, TENANT, "OWNER"), RECORD);
        assertThat(forOwner.patient().mobile()).isEqualTo("9000000000");
        assertThat(forOwner.canAdmitDirectly()).isTrue();
        assertThat(forOwner.patient().toString()).doesNotContain("9000000000").doesNotContain("Asha");
    }

    @Test
    void theStayOfAnOtBookingComesFromThePlansAdmissionChoice() {
        assertThat(EmrAdmissionService.plannedStayDays("IPD_1_DAY")).isEqualTo(1);
        assertThat(EmrAdmissionService.plannedStayDays("IPD_1_2_DAYS")).isEqualTo(2);
        assertThat(EmrAdmissionService.plannedStayDays("IPD_2_DAYS")).isEqualTo(2);
        assertThat(EmrAdmissionService.plannedStayDays("IPD_4_5_DAYS")).isEqualTo(5);
        assertThat(EmrAdmissionService.plannedStayDays("DAY_CARE")).isNull();
        assertThat(EmrAdmissionService.plannedStayDays(null)).isNull();
    }

    // ---- ipd's side of the port ----

    @Test
    void anAdmissionClosesEveryOpenRequestOfThePatientAndLinksTheEmergencyRecord() {
        UUID admission = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID erRecord = UUID.randomUUID();
        RequestRow first = stored("OPEN", "h");
        RequestRow second = stored("OPEN", "h");
        when(finish.openRequestsOf(TENANT, PATIENT, caseId)).thenReturn(List.of(first, second));
        when(finish.erRecordOfCase(TENANT, caseId)).thenReturn(Optional.of(erRecord));
        EmrAdmissionRequestPort port = new EmrAdmissionRequestPortAdapter(finish);

        port.onAdmitted(TENANT, USER, new EmrAdmissionRequestPort.AdmittedFacts(admission, PATIENT, caseId, first.id()));

        verify(finish).markRequestAdmitted(TENANT, first.id(), admission, USER);
        verify(finish).markRequestAdmitted(TENANT, second.id(), admission, USER);
        verify(finish).linkPatient(TENANT, erRecord, PATIENT);
    }

    @Test
    void theListIsTheOpenRequestsAndALockedRequestCarriesNoPatientNameInItsToString() {
        RequestRow open = stored("OPEN", "h");
        when(finish.listOpenRequests(TENANT)).thenReturn(List.of(open));
        when(finish.lockOpenRequest(TENANT, open.id())).thenReturn(Optional.of(open));
        EmrAdmissionRequestPort port = new EmrAdmissionRequestPortAdapter(finish);

        assertThat(port.listOpen(TENANT)).hasSize(1);
        assertThat(port.lockOpen(TENANT, open.id())).isPresent();
        assertThat(port.lockOpen(TENANT, open.id()).get().toString()).doesNotContain("Appendicitis");
    }

    // ---- reprint ----

    private EmrReprintService reprintFor(String role, UUID visitDoctor, String recordStatus) {
        TeamMemberPort team = mock(TeamMemberPort.class);
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(new TeamMember(MEMBERSHIP, "Someone", role, true, 0)));
        VisitRepository visits = mock(VisitRepository.class);
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, visitDoctor, "NEW", "DONE",
                1, 0, NOW, 0)));
        EmrRecordRepository rows = mock(EmrRecordRepository.class);
        RecordRow row = mock(RecordRow.class);
        when(row.status()).thenReturn(recordStatus);
        when(row.id()).thenReturn(RECORD);
        when(rows.findOpd(TENANT, VISIT)).thenReturn(Optional.of(row));
        return new EmrReprintService(records, rows, mock(EmrCasePaperService.class), visits,
                mock(FollowUpRepository.class), team, audit, support, reference, MAPPER);
    }

    private final SpecialtyRecordSupport support = mock(SpecialtyRecordSupport.class);
    private final SpecialtyReferenceService reference = mock(SpecialtyReferenceService.class);
    private static final Instant SIGNED_AT = Instant.parse("2026-11-10T18:45:00Z");

    /** A signed OPD record of the given starter with one printed and one unprinted section, ready for a successful reprint. */
    private EmrReprintService signedReprint(String role, String starterKey, EmrCasePaperService casePaper) {
        TeamMemberPort team = mock(TeamMemberPort.class);
        TeamMember member = new TeamMember(MEMBERSHIP, "Someone", role, true, 0);
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(member));
        VisitRepository visits = mock(VisitRepository.class);
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, MEMBERSHIP, "NEW", "DONE",
                1, 0, NOW, 0)));
        EmrRecordRepository rows = mock(EmrRecordRepository.class);
        RecordRow row = mock(RecordRow.class);
        when(row.status()).thenReturn("SIGNED");
        when(row.id()).thenReturn(RECORD);
        when(row.encounterType()).thenReturn("OPD_VISIT");
        when(row.starterKey()).thenReturn(starterKey);
        when(row.signedAt()).thenReturn(SIGNED_AT);
        when(row.createdAt()).thenReturn(Instant.parse("2026-11-10T17:00:00Z"));
        when(row.sections()).thenReturn(EmrRecordDocument.empty(MAPPER).toJson());
        when(rows.findOpd(TENANT, VISIT)).thenReturn(Optional.of(row));
        LayoutSection printed = section("complaints", "Complaints", true);
        LayoutSection unprinted = section("history", "History", false);
        ObjectNode complaintValues = MAPPER.createObjectNode().put("note", "free text");
        EmrViews.Snapshot snapshot = new EmrViews.Snapshot("gm", "General", starterKey, "STARTER", 1, "CLINIC_DEFAULT", NOW,
                List.of(unprinted, printed));
        RecordView view = new RecordView(RECORD, "OPD_VISIT", VISIT, null, "SIGNED", true, "SIGNED", 4, null, snapshot,
                List.of(new EmrViews.RecordSection("history", "DONE", MAPPER.createObjectNode().put("note", "x"), null,
                        false, null), new EmrViews.RecordSection("complaints", "DONE", complaintValues, null, false, null)),
                List.of(), List.of(), true, null, false, null, List.of(), null, List.of(), List.of(), null, null, false,
                List.of(), null);
        when(records.viewForReprint(any(ClinicUser.class), eq(member), eq(VISIT))).thenReturn(view);
        when(records.addendaOf(TENANT, RECORD)).thenReturn(List.of());
        return new EmrReprintService(records, rows, casePaper, visits, mock(FollowUpRepository.class), team, audit, support,
                reference, MAPPER);
    }

    private static LayoutSection section(String key, String name, boolean print) {
        return new LayoutSection(key, "LIBRARY", name, "COMMON", "", "ASSESSMENT", 1, true, false, print, false, false,
                List.of("DOCTOR"), false, null, List.of());
    }

    @Test
    void theReprintCarriesTheSnapshotOfThePrintedSectionsTheProfileBlockAsOfTheSigningDayAndItsLabels() {
        for (String role : List.of("OWNER", "FRONT_DESK", "PHARMACIST")) {
            EmrCasePaperService casePaper = mock(EmrCasePaperService.class);
            EmrReprintService service = signedReprint(role, "paeds", casePaper);
            SpecialtyCtx ctx = mock(SpecialtyCtx.class);
            ObjectNode specialtyView = MAPPER.createObjectNode().put("profile", "paeds");
            ObjectNode referenceLabels = MAPPER.createObjectNode().put("profile", "paeds");
            when(support.context(eq(TENANT), any(RecordRow.class), eq("paeds"), eq(MEMBERSHIP), eq(role), eq(SIGNED_AT),
                    any(EmrRecordDocument.class), eq(SIGNED_AT))).thenReturn(ctx);
            when(support.view(eq("paeds"), eq(ctx), any(RecordRow.class), any())).thenReturn(specialtyView);
            when(reference.forProfile(TENANT, "paeds")).thenReturn(referenceLabels);

            EmrReprintService.Reprint reprint = service.reprint(DOCTOR, VISIT);

            assertThat(reprint.sections()).extracting(EmrReprintService.PrintSection::key).containsExactly("complaints");
            assertThat(reprint.layout().sections()).extracting(LayoutSection::key).containsExactly("complaints");
            assertThat(reprint.layout().departmentKey()).isEqualTo("gm");
            if ("OWNER".equals(role)) {
                assertThat(reprint.specialty()).isSameAs(specialtyView);
            } else {
                assertThat(reprint.specialty()).isNotSameAs(specialtyView).isEqualTo(specialtyView);
            }
            assertThat(reprint.specialtyReference()).isSameAs(referenceLabels);
            assertThat(reprint.signedAt()).isEqualTo(SIGNED_AT);
        }
        verify(support, org.mockito.Mockito.times(3)).context(eq(TENANT), any(RecordRow.class), eq("paeds"), eq(MEMBERSHIP),
                anyString(), eq(SIGNED_AT), any(EmrRecordDocument.class), eq(SIGNED_AT));
        verify(audit, org.mockito.Mockito.times(3)).record(eq("EMR_PRESCRIPTION_REPRINTED"), any(), any(), any(), any());
    }

    @Test
    void aRecordWithoutAProfileHasTheSnapshotButNoSpecialtyAndNoReference() {
        EmrReprintService service = signedReprint("FRONT_DESK", "ortho", mock(EmrCasePaperService.class));

        EmrReprintService.Reprint reprint = service.reprint(DOCTOR, VISIT);

        assertThat(reprint.layout().sections()).hasSize(1);
        assertThat(reprint.specialty()).isNull();
        assertThat(reprint.specialtyReference()).isNull();
        verify(support, never()).context(any(), any(), anyString(), any(), anyString(), any(), any(), any());
        verify(reference, never()).forProfile(any(), anyString());
    }

    @Test
    void nurseBillingAndCounsellorCannotReprintAndADoctorOnlyTheirOwnVisit() {
        for (String role : List.of("NURSE", "BILLING", "COUNSELLOR")) {
            assertThatThrownBy(() -> reprintFor(role, MEMBERSHIP, "SIGNED").reprint(DOCTOR, VISIT))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> reprintFor("DOCTOR", UUID.randomUUID(), "SIGNED").reprint(DOCTOR, VISIT))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> assertThat(e.slug()).isEqualTo("not-own-patient"));
        verify(audit, never()).record(eq("EMR_PRESCRIPTION_REPRINTED"), any(), any(), any(), any());
    }

    @Test
    void aVisitWhoseRecordIsStillADraftIsNotSignedAndFrontDeskAndPharmacistReachAnyVisit() {
        for (String role : List.of("OWNER", "FRONT_DESK", "PHARMACIST")) {
            assertThatThrownBy(() -> reprintFor(role, UUID.randomUUID(), "DRAFT").reprint(DOCTOR, VISIT))
                    .isInstanceOfSatisfying(EmrProblemException.class,
                            e -> assertThat(e.slug()).isEqualTo("emr-record-not-signed"));
        }
    }

    // ---- DEC-048 H32: the front desk and the pharmacist get only what the printed sections use ----

    private ObjectNode frame(String profile) {
        ObjectNode view = MAPPER.createObjectNode();
        view.put("profile", profile);
        view.putNull("mode");
        view.putArray("hiddenSectionKeys");
        view.put("tagNote", "tag");
        return view;
    }

    /** A view whose block has every given key set to a marker value. */
    private ObjectNode viewWith(String profile, String block, String... keys) {
        ObjectNode view = frame(profile);
        ObjectNode node = view.putObject(block);
        for (String key : keys) {
            node.put(key, "clinical");
        }
        return view;
    }

    private static List<String> keysOf(com.fasterxml.jackson.databind.JsonNode view, String block) {
        List<String> keys = new java.util.ArrayList<>();
        view.path(block).fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<LayoutSection> printing(String... keys) {
        List<LayoutSection> sections = new java.util.ArrayList<>();
        for (String key : keys) {
            sections.add(section(key, key, true));
        }
        return sections;
    }

    private static final String[] OG_KEYS = {"gestationalAge", "edd", "eddSource", "daysToEdd", "obstetricScore",
        "weightChangeKg", "notes", "alerts", "riskChips", "scanStates", "dueOrders", "formFRequired", "formFCount"};
    private static final List<String> OG_FORM_F = List.of("formFRequired", "formFCount");

    @Test
    void anObstetricsReprintForFrontDeskHasNoDatingAncOrFormFUnlessTheirOwnSectionPrints() {
        ObjectNode view = viewWith("og", "og", OG_KEYS);

        com.fasterxml.jackson.databind.JsonNode none = EmrReprintService.pruneSpecialty("og", view,
                printing("complaints"));
        assertThat(none.has("og")).isFalse();
        assertThat(none.toString()).doesNotContain("edd").doesNotContain("formF");
        assertThat(none.path("profile").asText()).isEqualTo("og");
        assertThat(none.has("tagNote")).isTrue();

        // F-8: the ANC section prints: the ANC keys stay, the Form F keys do not
        com.fasterxml.jackson.databind.JsonNode ancOnly = EmrReprintService.pruneSpecialty("og", view, printing("anc"));
        assertThat(keysOf(ancOnly, "og")).contains("edd", "gestationalAge", "scanStates", "alerts", "riskChips")
                .doesNotContainAnyElementsOf(OG_FORM_F).hasSize(OG_KEYS.length - 2);

        // the PCPNDT section prints: only the Form F keys stay
        com.fasterxml.jackson.databind.JsonNode formFOnly = EmrReprintService.pruneSpecialty("og", view,
                printing("pcpndt"));
        assertThat(keysOf(formFOnly, "og")).containsExactlyInAnyOrderElementsOf(OG_FORM_F);

        com.fasterxml.jackson.databind.JsonNode both = EmrReprintService.pruneSpecialty("og", view,
                printing("anc", "pcpndt"));
        assertThat(keysOf(both, "og")).hasSize(OG_KEYS.length);
    }

    @Test
    void anOncologyBlockKeepsEachKeyOnlyWithItsOwnSectionAndTheLabFlagsAndDecisionFollowTheirSections() {
        ObjectNode view = viewWith("onco", "onco", "bsa", "labFails", "labsBadge", "decision", "suggestion", "worstGrade",
                "ecogText", "nextCycle", "fever");
        view.putArray("vitalsFlags").addObject().put("field", "temp");

        com.fasterxml.jackson.databind.JsonNode none = EmrReprintService.pruneSpecialty("onco", view, printing("complaints"));
        assertThat(none.has("onco")).isFalse();
        assertThat(none.has("vitalsFlags")).isFalse();
        assertThat(none.size()).isEqualTo(4);

        assertThat(keysOf(EmrReprintService.pruneSpecialty("onco", view, printing("onc_labs")), "onco"))
                .containsExactlyInAnyOrder("labFails", "labsBadge");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("onco", view, printing("onc_regimen")), "onco"))
                .containsExactlyInAnyOrder("decision", "suggestion", "nextCycle");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("onco", view, printing("onc_bsa")), "onco"))
                .containsExactly("bsa");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("onco", view, printing("onc_ecog")), "onco"))
                .containsExactly("ecogText");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("onco", view, printing("onc_tox")), "onco"))
                .containsExactlyInAnyOrder("worstGrade", "fever");
        assertThat(EmrReprintService.pruneSpecialty("onco", view, printing("vitals")).has("vitalsFlags")).isTrue();
        assertThat(keysOf(EmrReprintService.pruneSpecialty("onco", view, printing("vitals")), "onco"))
                .containsExactly("fever");
    }

    @Test
    void aSurgeryBlockKeepsEachKeyOnlyWithItsOwnSection() {
        ObjectNode view = viewWith("surgery", "surgery", "fitness", "depositPaise", "payerChip", "bookState", "bookMissing",
                "consentCount");

        assertThat(EmrReprintService.pruneSpecialty("surgery", view, List.of()).has("surgery")).isFalse();
        assertThat(keysOf(EmrReprintService.pruneSpecialty("surgery", view, printing("sx_plan")), "surgery"))
                .containsExactlyInAnyOrder("depositPaise", "payerChip", "bookState", "bookMissing");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("surgery", view, printing("sx_fitness")), "surgery"))
                .containsExactly("fitness");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("surgery", view, printing("sx_preauth")), "surgery"))
                .containsExactly("payerChip");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("surgery", view, printing("sx_consent")), "surgery"))
                .containsExactly("consentCount");
    }

    @Test
    void aDentalAndAPhysioBlockKeepEachKeyOnlyWithItsOwnSection() {
        ObjectNode dental = viewWith("dental", "dental", "estimateTotalPaise", "estimateState", "doneTodayRowIds",
                "childTabDefault");
        assertThat(EmrReprintService.pruneSpecialty("dental", dental, printing("dental_chart")).has("dental")).isFalse();
        assertThat(keysOf(EmrReprintService.pruneSpecialty("dental", dental, printing("tx_plan")), "dental")).hasSize(4);
        assertThat(keysOf(EmrReprintService.pruneSpecialty("dental", dental, printing("dental_done")), "dental"))
                .containsExactlyInAnyOrder("doneTodayRowIds", "childTabDefault");

        ObjectNode physio = viewWith("physio", "physio", "sessionNo", "sessionsPlanned", "packageName", "romPercents",
                "outcomeScales");
        assertThat(EmrReprintService.pruneSpecialty("physio", physio, printing("complaints")).has("physio")).isFalse();
        assertThat(keysOf(EmrReprintService.pruneSpecialty("physio", physio, printing("rom")), "physio"))
                .containsExactly("romPercents");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("physio", physio, printing("func_scales")), "physio"))
                .containsExactly("outcomeScales");
        assertThat(keysOf(EmrReprintService.pruneSpecialty("physio", physio, printing("sessions")), "physio"))
                .containsExactlyInAnyOrder("sessionNo", "sessionsPlanned", "packageName");
    }

    @Test
    void aGeneralMedicineVitalsFlagsListFollowsTheVitalsSection() {
        ObjectNode view = frame("gm");
        view.putArray("vitalsFlags").addObject().put("field", "bp");

        assertThat(EmrReprintService.pruneSpecialty("gm", view, printing("complaints")).has("vitalsFlags")).isFalse();
        assertThat(EmrReprintService.pruneSpecialty("gm", view, printing("vitals")).has("vitalsFlags")).isTrue();
    }

    @Test
    void aKeyAddedToABlockWithoutAnOwnerIsNotSentToTheFrontDesk() {
        ObjectNode view = viewWith("og", "og", "edd", "newKeyNobodyOwns");

        assertThat(keysOf(EmrReprintService.pruneSpecialty("og", view, printing("anc", "pcpndt")), "og"))
                .containsExactly("edd");
    }

    @Test
    void aPaediatricsReprintKeepsTheVaccinationDataAndDropsGrowthDoseAndMilestoneFactsThatDoNotPrint() {
        ObjectNode view = frame("paeds");
        ObjectNode paeds = view.putObject("paeds");
        paeds.putArray("vaccines").addObject().put("vaccineKey", "bcg");
        paeds.put("dueTodayCount", 1);
        paeds.putArray("growthNotes").addObject().put("label", "Low weight");
        paeds.put("doseWeightKg", 9.5);
        paeds.put("milestonesSetUp", true);

        com.fasterxml.jackson.databind.JsonNode pruned = EmrReprintService.pruneSpecialty("paeds", view,
                List.of(section("complaints", "Complaints", true)));

        assertThat(pruned.path("paeds").has("vaccines")).isTrue();
        assertThat(pruned.path("paeds").has("dueTodayCount")).isTrue();
        assertThat(pruned.path("paeds").has("growthNotes")).isFalse();
        assertThat(pruned.path("paeds").has("doseWeightKg")).isFalse();
        assertThat(pruned.path("paeds").has("milestonesSetUp")).isFalse();

        com.fasterxml.jackson.databind.JsonNode withGrowth = EmrReprintService.pruneSpecialty("paeds", view,
                List.of(section("growth", "Growth", true)));
        assertThat(withGrowth.path("paeds").has("growthNotes")).isTrue();
        assertThat(withGrowth.path("paeds").has("doseWeightKg")).isFalse();
    }

    @Test
    void anInactiveItemIsLeftOutOfThePrintedValues() {
        ObjectNode values = MAPPER.createObjectNode();
        ObjectNode list = values.putObject("final");
        list.put("component", "EMR_LIST");
        list.putArray("items").addObject().put("text", "Kept").put("active", true);
        list.withArray("items").addObject().put("text", "Removed").put("active", false);
        values.put("note", "free text");

        assertThat(EmrReprintService.activeOnly(values).toString()).contains("Kept").contains("free text")
                .doesNotContain("Removed");
        assertThat(values.toString()).contains("Removed");
    }
}
