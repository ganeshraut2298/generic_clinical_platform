package com.sahaj.opd.application.emr.specialty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ActionInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.CheckerInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ConfirmInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.FinalDose;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.HoldInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.OrderInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.PinInput;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Drug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Premed;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
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
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

/**
 * The chemotherapy order and its independent second check (SAH-66, SAH-70; ADR-023 D6) against an in-memory table that
 * refuses what the database refuses. Every number is tagged (DEC-039, not clinically reviewed). The PIN is the only secret
 * here: it is checked to reach the platform port and nowhere else.
 */
class ChemoOrderServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID DOCTOR_USER = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final UUID NURSE_USER = UUID.randomUUID();
    private static final UUID NURSE_M = UUID.randomUUID();
    private static final UUID OTHER_NURSE_USER = UUID.randomUUID();
    private static final UUID OTHER_NURSE_M = UUID.randomUUID();
    private static final UUID FRONT_USER = UUID.randomUUID();
    private static final UUID FRONT_M = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ClinicUser DOCTOR = new ClinicUser(DOCTOR_USER, TENANT, "DOCTOR");
    private static final ClinicUser NURSE = new ClinicUser(NURSE_USER, TENANT, "NURSE");
    private static final ClinicUser OTHER_NURSE = new ClinicUser(OTHER_NURSE_USER, TENANT, "NURSE");
    private static final ClinicUser FRONT = new ClinicUser(FRONT_USER, TENANT, "FRONT_DESK");

    private static final TeamMember DOCTOR_MEMBER = new TeamMember(DOCTOR_M, "Dr Rao", "DOCTOR", true, 0);
    private static final TeamMember NURSE_MEMBER = new TeamMember(NURSE_M, "Sr Mary", "NURSE", true, 0);
    private static final TeamMember OTHER_NURSE_MEMBER = new TeamMember(OTHER_NURSE_M, "Sr Joy", "NURSE", true, 0);
    private static final TeamMember FRONT_MEMBER = new TeamMember(FRONT_M, "Anita", "FRONT_DESK", true, 0);

    private static final Protocol AC = new Protocol("AC", "AC", "Doxorubicin + cyclophosphamide · every 21 days", 21,
            "HIGH", false, List.of(new Drug("doxorubicin", "Doxorubicin", 60, 15, 5, true, true, null),
            new Drug("cyclophosphamide", "Cyclophosphamide", 600, 30, 20, false, false, null)),
            List.of(new Premed("ondansetron_iv", "Ondansetron 8 mg IV", 15)));

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrRecordRepository recordRepository = mock(EmrRecordRepository.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final StaffPinPort staffPin = mock(StaffPinPort.class);
    private final TreatmentCourseRepository courses = mock(TreatmentCourseRepository.class);
    private final PatientClinicalPort patients = mock(PatientClinicalPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final FakeChemoOrders orders = new FakeChemoOrders();

    private ChemoOrderService service;
    private ChemoCheckService checks;
    private ChemoSecondCheckService secondCheck;
    private SpecialtyRecordSupport support;
    private String sections;
    private String screenState;

    @BeforeEach
    void setUp() {
        SpecialtyReferenceData data = new SpecialtyReferenceData(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(AC));
        SpecialtyReferenceRepository reference = () -> data;
        @SuppressWarnings("unchecked")
        ObjectProvider<SpecialtyRecordSupport.ChemoOrderHook> hook = mock(ObjectProvider.class);
        support = new SpecialtyRecordSupport(reference, mock(ServiceRepository.class), team, patients, courses, orders,
                recordRepository, audit, MAPPER, hook);
        ChemoOrderLogic logic = new ChemoOrderLogic(reference, support, MAPPER, team);
        service = new ChemoOrderService(records, recordRepository, orders, logic, visits, team, staffPin, courses,
                audit, MAPPER, CLOCK);
        checks = new ChemoCheckService(team, orders, logic, recordRepository, visits, patients, service, audit, MAPPER,
                CLOCK);
        secondCheck = new ChemoSecondCheckService(checks, staffPin);

        sections = oncologyRecord("1800").toJson();
        screenState = decision("GIVE_AS_PLANNED");
        when(records.authorize(DOCTOR, RECORD)).thenAnswer(i -> new Authorized(DOCTOR_MEMBER, row(), PATIENT));
        when(recordRepository.findById(eq(TENANT), eq(RECORD))).thenAnswer(i -> Optional.of(row()));
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, DOCTOR_M, "NEW",
                "WITH_DOCTOR", 4, 50000, NOW.minusSeconds(7200), 1, 1)));
        when(team.findActiveByUserId(TENANT, DOCTOR_USER)).thenReturn(Optional.of(DOCTOR_MEMBER));
        when(team.findActiveByUserId(TENANT, NURSE_USER)).thenReturn(Optional.of(NURSE_MEMBER));
        when(team.findActiveByUserId(TENANT, OTHER_NURSE_USER)).thenReturn(Optional.of(OTHER_NURSE_MEMBER));
        when(team.findActiveByUserId(TENANT, FRONT_USER)).thenReturn(Optional.of(FRONT_MEMBER));
        for (TeamMember member : List.of(DOCTOR_MEMBER, NURSE_MEMBER, OTHER_NURSE_MEMBER, FRONT_MEMBER)) {
            when(team.find(TENANT, member.membershipId())).thenReturn(Optional.of(member));
        }
        when(team.findActiveMembers(TENANT)).thenReturn(List.of(DOCTOR_MEMBER, NURSE_MEMBER, OTHER_NURSE_MEMBER,
                FRONT_MEMBER));
        when(team.findAll(eq(TENANT), any())).thenAnswer(i -> {
            Map<UUID, TeamMember> all = new java.util.HashMap<>();
            for (TeamMember m : List.of(DOCTOR_MEMBER, NURSE_MEMBER, OTHER_NURSE_MEMBER, FRONT_MEMBER)) {
                all.put(m.membershipId(), m);
            }
            all.keySet().retainAll((java.util.Collection<UUID>) i.getArgument(1));
            return all;
        });
        when(team.doctorDetails(eq(TENANT), any())).thenReturn(Optional.empty());
        when(staffPin.membershipsWithPin(eq(TENANT), any())).thenReturn(java.util.Set.of(NURSE_M));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientClinical(PATIENT, "UH-000123",
                "Meena Patil", 48, "F", null, null)));
        when(courses.forPatient(eq(TENANT), eq(PATIENT), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
    }

    // ---- the record ----

    private static ObjectNode specialty(String json) {
        try {
            ObjectNode values = MAPPER.createObjectNode();
            values.set("specialty", MAPPER.readTree(json.replace('\'', '"')));
            return values;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static EmrRecordDocument oncologyRecord(String anc) {
        EmrRecordDocument doc = EmrRecordDocument.empty(MAPPER);
        doc.put("vitals", specialty("{'component':'VITALS_LOG','schemaVersion':1,'entries':[{'id':'"
                + UUID.randomUUID() + "','temperature':{'value':36.8,'unit':'C'}}]}"), true, NOW, DOCTOR_M);
        doc.put("onc_bsa", specialty("{'component':'ONC_EXAM','schemaVersion':1,'heightCm':160,'weightKg':60,"
                + "'capAt2':true,'signs':{},'lesions':[],'newLesionSeen':false,'breastSkin':[],'systems':{},"
                + "'line':{'device':'PORT','checks':['FLUSHES_FREELY','BLOOD_RETURN','NO_REDNESS_SWELLING']}}"), true,
                NOW, DOCTOR_M);
        doc.put("onc_labs", specialty("{'component':'ONC_LABS','schemaVersion':1,'drawnAt':'2026-11-12T04:00:00Z',"
                + "'values':{'hb':10.5,'anc':" + anc + ",'plt':200,'cr':0.8,'bili':0.7,'alt':30,'lvef':62}}"), true, NOW,
                DOCTOR_M);
        doc.put("onc_ecog", specialty("{'component':'ONC_ECOG','schemaVersion':1,'grade':1}"), true, NOW, DOCTOR_M);
        doc.put("onc_tox", specialty("{'component':'ONC_TOX','schemaVersion':1,'symptoms':[{'key':'NAUSEA',"
                + "'patientGrade':1,'confirmedGrade':1}],'events':[]}"), true, NOW, DOCTOR_M);
        return doc;
    }

    private static String decision(String choice) {
        return "{\"schemaVersion\":1,\"profile\":\"onco\",\"oncDecision\":{\"choice\":\"" + choice
                + "\",\"reasonKey\":null,\"suggestionAtDecision\":\"" + choice + "\",\"decidedAt\":\""
                + NOW + "\",\"decidedByMembershipId\":\"" + DOCTOR_M + "\"}}";
    }

    private RecordRow row() {
        return new RecordRow(RECORD, "OPD_VISIT", VISIT, null, (LocalDate) null, null, PATIENT, DOCTOR_M, "onco",
                "Medical Oncology", "onco", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "DRAFT", null, sections,
                "ASSESSMENT", null, false, null, 3, NOW.minusSeconds(7200), null, null, "[]", screenState);
    }

    private static OrderInput order(long revision) {
        return new OrderInput(revision, "AC", 100, null, null, List.of(), List.of(), true, true, "APPROVED", null);
    }

    private ObjectNode saved() {
        return service.save(DOCTOR, RECORD, order(0));
    }

    private ChemoOrderRow stored() {
        return orders.findByRecord(TENANT, RECORD).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captor() {
        return (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
    }

    private static String problem(Throwable e) {
        return ((EmrProblemException) e).slug();
    }

    // ---- the sheet ----

    @Test
    void beforeTheFirstSaveTheSheetIsWorkedOutFromTheDecisionAndTheDefaultProtocol() {
        ObjectNode view = service.get(DOCTOR, RECORD);
        assertThat(view.path("status").asText()).isEqualTo("NONE");
        assertThat(view.path("protocolKey").isNull()).isTrue();
        assertThat(view.path("decisionRecorded").asBoolean()).isTrue();
        assertThat(view.path("levelPct").asInt()).isEqualTo(100);
        assertThat(view.path("drugs")).hasSize(2);
        assertThat(view.path("totalCount").asInt()).as("nine checks and the line check for a vesicant").isEqualTo(10);
        assertThat(view.path("stages").path("chair").asText()).isEqualTo("LATER");
        assertThat(view.path("fingerprint").asText()).matches("^[0-9a-f]{64}$");
        assertThat(orders.rows).isEmpty();
    }

    @Test
    void aGiveReducedDecisionPreselectsTheNextLevelDownTaggedC8() {
        screenState = decision("GIVE_REDUCED");
        assertThat(service.get(DOCTOR, RECORD).path("levelPct").asInt()).isEqualTo(90);
    }

    @Test
    void theFirstSaveCreatesTheOrderWithTheServersDosesAndAuditsIdsOnlyTaggedC8() {
        ObjectNode view = saved();
        assertThat(view.path("status").asText()).isEqualTo("DRAFT");
        assertThat(view.path("protocolKey").asText()).isEqualTo("AC");
        assertThat(view.path("passedCount").asInt()).isEqualTo(10);
        JsonNode dox = view.path("drugs").get(0);
        assertThat(dox.path("calculatedMg").asDouble()).isEqualTo(97.8);
        assertThat(dox.path("finalMg").asDouble()).isEqualTo(100.0);
        assertThat(dox.path("lifetime").path("totalMgM2").asDouble()).isEqualTo(60.0);
        assertThat(dox.path("lifetime").path("limitMgM2").asInt()).isEqualTo(450);
        assertThat(view.path("secondCheck").path("state").asText()).isEqualTo("NOT_NAMED");
        assertThat(view.path("send").path("ready").asBoolean()).isFalse();
        ChemoOrderRow row = stored();
        assertThat(row.prescriberMembershipId).isEqualTo(DOCTOR_M);
        assertThat(row.lastEditedByMembershipId).isEqualTo(DOCTOR_M);
        assertThat(row.cycleNo).isEqualTo(1);
        assertThat(row.fingerprint).isEqualTo(view.path("fingerprint").asText());
        assertThat(row.doxorubicinMgM2.doubleValue()).isEqualTo(60.0);
        ArgumentCaptor<Map<String, Object>> after = captor();
        verify(audit).record(eq("CHEMO_ORDER_SAVED"), eq("CHEMO_ORDER"), eq(row.id), eq(null), after.capture());
        assertThat(after.getValue()).containsOnlyKeys("recordId", "protocolKey", "levelPct", "checksPassed");
        assertThat(after.getValue().values().toString()).doesNotContain("Meena", "doxorubicin", "100.0");
    }

    @Test
    void aSaveWithAStaleRevisionIsRefused() {
        saved();
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, order(7))).isInstanceOf(EmrProblemException.class)
                .satisfies(e -> assertThat(problem(e)).isEqualTo("chemo-order-changed"));
    }

    @Test
    void theFirstSaveWithARevisionOtherThanZeroIsStale() {
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, order(2))).satisfies(e -> assertThat(problem(e))
                .isEqualTo("chemo-order-changed"));
    }

    @Test
    void inputsAreCheckedAndNeverEchoed() {
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, new OrderInput(0L, "XX", 100, null, null, List.of(),
                List.of(), true, true, "APPROVED", null))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, new OrderInput(0L, "AC", 85, null, null, List.of(),
                List.of(), true, true, "APPROVED", null))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, new OrderInput(0L, "AC", 100, "NOT_A_REASON", null,
                List.of(), List.of(), true, true, "APPROVED", null))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, new OrderInput(0L, "AC", 100, null, null,
                List.of(new FinalDose("cisplatin", 50.0)), List.of(), true, true, "APPROVED", null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, new OrderInput(0L, "AC", 100, null, null, List.of(),
                List.of("nope"), true, true, "APPROVED", null))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, new OrderInput(0L, "AC", 100, null, null, List.of(),
                List.of(), true, true, "SOMETHING", null))).isInstanceOf(InvalidFieldsException.class);
        assertThat(orders.rows).isEmpty();
    }

    @Test
    void aRecordThatIsNotOncologyIsNotApplicable() {
        RecordRow gm = new RecordRow(RECORD, "OPD_VISIT", VISIT, null, null, null, PATIENT, DOCTOR_M, "gm",
                "General Medicine", "gm", "STARTER_LAYOUT", null, 1, "DOCTOR_SPECIALTY", "DRAFT", null, sections,
                "ASSESSMENT", null, false, null, 3, NOW);
        when(records.authorize(DOCTOR, RECORD)).thenReturn(new Authorized(DOCTOR_MEMBER, gm, PATIENT));
        assertThatThrownBy(() -> service.get(DOCTOR, RECORD)).satisfies(e -> assertThat(problem(e))
                .isEqualTo("not-applicable"));
    }

    // ---- naming the checker ----

    @Test
    void theCheckerOptionsAreTheNursesAndPharmacistsNeverThePrescriber() {
        saved();
        List<Map<String, Object>> options = service.checkerOptions(DOCTOR, RECORD);
        assertThat(options).extracting(o -> o.get("name")).containsExactlyInAnyOrder("Sr Mary", "Sr Joy");
        assertThat(options).filteredOn(o -> o.get("name").equals("Sr Mary")).first().satisfies(
                o -> assertThat(o.get("hasPin")).isEqualTo(true));
        assertThat(options).filteredOn(o -> o.get("name").equals("Sr Joy")).first().satisfies(
                o -> assertThat(o.get("hasPin")).isEqualTo(false));
        assertThat(options).extracting(o -> o.get("membershipId")).doesNotContain(DOCTOR_M, FRONT_M);
    }

    @Test
    void theCheckerCannotBeNamedWhileACheckIsRed() {
        saved();
        sections = oncologyRecord("1000").toJson();
        assertThatThrownBy(() -> service.nameChecker(DOCTOR, RECORD, new CheckerInput(stored().revision, NURSE_M)))
                .satisfies(e -> {
                    assertThat(problem(e)).isEqualTo("checks-failing");
                    assertThat(((EmrProblemException) e).getMessage()).isEqualTo(
                            "Clear the red checks before the second check.");
                });
    }

    @Test
    void thePrescriberAnAnyoneOutsideTheListOrAnUnknownIdCannotBeNamed() {
        saved();
        long revision = stored().revision;
        assertThatThrownBy(() -> service.nameChecker(DOCTOR, RECORD, new CheckerInput(revision, DOCTOR_M)))
                .isInstanceOf(InvalidFieldsException.class)
                .satisfies(e -> assertThat(((InvalidFieldsException) e).errors().get(0).message())
                        .isEqualTo("The second check must be done by someone other than the prescriber."));
        assertThatThrownBy(() -> service.nameChecker(DOCTOR, RECORD, new CheckerInput(revision, FRONT_M)))
                .isInstanceOf(InvalidFieldsException.class)
                .satisfies(e -> assertThat(((InvalidFieldsException) e).errors().get(0).message())
                        .isEqualTo("Choose a checker from the list."));
        assertThatThrownBy(() -> service.nameChecker(DOCTOR, RECORD, new CheckerInput(revision, UUID.randomUUID())))
                .isInstanceOf(InvalidFieldsException.class);
        assertThat(stored().checkerMembershipId).isNull();
    }

    @Test
    void namingACheckerWaitsForTheConfirmationAndChangesTheFingerprint() {
        String before = saved().path("fingerprint").asText();
        ObjectNode view = service.nameChecker(DOCTOR, RECORD, new CheckerInput(stored().revision, NURSE_M));
        assertThat(view.path("secondCheck").path("state").asText()).isEqualTo("WAITING");
        assertThat(view.path("checker").path("name").asText()).isEqualTo("Sr Mary");
        assertThat(view.path("checker").path("role").asText()).isEqualTo("NURSE");
        assertThat(view.path("stages").path("secondCheck").asBoolean()).isFalse();
        assertThat(view.path("send").path("note").asText()).isEqualTo("Waiting for the second check");
        assertThat(view.path("fingerprint").asText()).isNotEqualTo(before);
        assertThat(stored().checkerRole).isEqualTo("NURSE");
        verify(audit).record(eq("CHEMO_CHECKER_NAMED"), eq("CHEMO_ORDER"), any(), eq(null),
                org.mockito.ArgumentMatchers.argThat(m -> m != null && m.keySet().equals(java.util.Set.of(
                        "checkerMembershipId", "role"))));
    }

    @Test
    void theNamedCheckerCannotChangeTheOrderThroughTheSheet() {
        saved();
        service.nameChecker(DOCTOR, RECORD, new CheckerInput(stored().revision, NURSE_M));
        ChemoOrderRow row = stored();
        row.checkerMembershipId = DOCTOR_M;
        // the doctor as checker (an owner who is also the prescriber is refused when the service saves)
        orders.rows.put(row.id, row);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, order(row.revision))).isInstanceOf(
                InvalidFieldsException.class);
    }

    // ---- way (a): the checker's own sign-in ----

    private String namedFingerprint() {
        saved();
        return service.nameChecker(DOCTOR, RECORD, new CheckerInput(stored().revision, NURSE_M)).path("fingerprint")
                .asText();
    }

    @Test
    void theNamedCheckerSeesAReadOnlySummaryAndConfirmsWithTheFingerprintTheyRead() {
        namedFingerprint();
        UUID orderId = stored().id;
        ObjectNode list = checks.list(NURSE, 0);
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("pageSize").asInt()).isEqualTo(20);
        assertThat(list.path("items").get(0).path("patientName").asText()).isEqualTo("Meena Patil");
        assertThat(list.path("items").get(0).path("protocolLabel").asText()).isEqualTo("AC");
        assertThat(list.path("items").get(0).path("prescriberName").asText()).isEqualTo("Dr Rao");

        ObjectNode summary = checks.summary(NURSE, orderId);
        assertThat(summary.path("canConfirm").asBoolean()).isTrue();
        assertThat(summary.path("drugs").get(0).path("finalMg").asDouble()).isEqualTo(100.0);
        assertThat(summary.path("secondCheck").path("state").asText()).isEqualTo("WAITING");
        ObjectNode confirmed = checks.confirm(NURSE, orderId, new ConfirmInput(summary.path("fingerprint").asText()));
        assertThat(confirmed.path("secondCheck").path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("canConfirm").asBoolean()).isFalse();
        ChemoOrderRow row = stored();
        assertThat(row.confirmedVia).isEqualTo("SIGN_IN");
        assertThat(row.confirmedByMembershipId).isEqualTo(NURSE_M);
        assertThat(row.confirmedByUserId).isEqualTo(NURSE_USER);
        assertThat(row.confirmedFingerprint).isEqualTo(row.fingerprint);
        assertThat(checks.list(NURSE, 0).path("total").asLong()).as("no longer waiting").isZero();
        verify(audit).record(eq("CHEMO_SECOND_CHECK_CONFIRMED"), eq("CHEMO_ORDER"), eq(orderId), eq(null),
                org.mockito.ArgumentMatchers.argThat(m -> m != null && m.get("way").equals("SIGN_IN")
                        && m.get("checkerUserId").equals(NURSE_USER)));
    }

    @Test
    void onlyTheNamedCheckerMayReadOrConfirmAndThePrescriberIsRefusedWithItsOwnReason() {
        namedFingerprint();
        UUID orderId = stored().id;
        assertThatThrownBy(() -> checks.summary(OTHER_NURSE, orderId)).satisfies(e -> {
            assertThat(problem(e)).isEqualTo("not-named-checker");
            assertThat(((EmrProblemException) e).status()).isEqualTo(403);
        });
        assertThatThrownBy(() -> checks.confirm(OTHER_NURSE, orderId, new ConfirmInput("0".repeat(64))))
                .satisfies(e -> assertThat(problem(e)).isEqualTo("not-named-checker"));
        assertThatThrownBy(() -> checks.summary(DOCTOR, orderId)).satisfies(e -> {
            assertThat(problem(e)).isEqualTo("checker-is-prescriber");
            assertThat(((EmrProblemException) e).status()).isEqualTo(403);
        });
        assertThatThrownBy(() -> checks.list(FRONT, 0)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> checks.summary(NURSE, UUID.randomUUID())).satisfies(e -> {
            assertThat(problem(e)).isEqualTo("order-not-found");
            assertThat(((EmrProblemException) e).status()).isEqualTo(404);
        });
        assertThat(stored().confirmedAt).isNull();
    }

    @Test
    void aConfirmationWithAStaleFingerprintOrWhileACheckIsRedIsRefused() {
        namedFingerprint();
        UUID orderId = stored().id;
        assertThatThrownBy(() -> checks.confirm(NURSE, orderId, new ConfirmInput("a".repeat(64)))).satisfies(
                e -> assertThat(problem(e)).isEqualTo("chemo-order-changed"));
        assertThatThrownBy(() -> checks.confirm(NURSE, orderId, new ConfirmInput("not a hash")))
                .isInstanceOf(InvalidFieldsException.class);
        String current = checks.summary(NURSE, orderId).path("fingerprint").asText();
        sections = oncologyRecord("1000").toJson();
        assertThatThrownBy(() -> checks.confirm(NURSE, orderId, new ConfirmInput(current))).satisfies(
                e -> assertThat(problem(e)).isEqualTo("checks-failing"));
        assertThat(stored().confirmedAt).isNull();
    }

    @Test
    void confirmingTwiceWithTheSameFingerprintWritesNothingTheSecondTime() {
        namedFingerprint();
        UUID orderId = stored().id;
        String fingerprint = checks.summary(NURSE, orderId).path("fingerprint").asText();
        checks.confirm(NURSE, orderId, new ConfirmInput(fingerprint));
        int updates = orders.updates;
        checks.confirm(NURSE, orderId, new ConfirmInput(fingerprint));
        assertThat(orders.updates).isEqualTo(updates);
        verify(audit, org.mockito.Mockito.times(1)).record(eq("CHEMO_SECOND_CHECK_CONFIRMED"), anyString(), any(), any(),
                any());
    }

    // ---- send ----

    private ObjectNode confirmedOrder() {
        namedFingerprint();
        String fingerprint = checks.summary(NURSE, stored().id).path("fingerprint").asText();
        checks.confirm(NURSE, stored().id, new ConfirmInput(fingerprint));
        return service.get(DOCTOR, RECORD);
    }

    @Test
    void anOrderCannotBeSentWithoutAConfirmedSecondCheckTheDatabaseAgrees() {
        namedFingerprint();
        ObjectNode view = service.get(DOCTOR, RECORD);
        assertThatThrownBy(() -> service.send(DOCTOR, RECORD, new ActionInput(stored().revision,
                view.path("fingerprint").asText()))).satisfies(e -> {
            assertThat(problem(e)).isEqualTo("second-check-not-confirmed");
            assertThat(((EmrProblemException) e).getMessage()).isEqualTo("The second check is not confirmed yet.");
        });
        assertThat(stored().status).isEqualTo("DRAFT");
        // and the table itself refuses a SENT row without a confirmation
        ChemoOrderRow row = stored();
        row.status = "SENT";
        row.sentAt = NOW;
        row.sentBy = DOCTOR_USER;
        assertThatThrownBy(() -> orders.update(TENANT, row, row.revision, DOCTOR_USER)).hasMessageContaining(
                "ck_chemo_order_sent");
    }

    @Test
    void aConfirmedOrderIsSentOnceAndARetryAnswersTheSameOrderTaggedC8() {
        ObjectNode view = confirmedOrder();
        assertThat(view.path("send").path("ready").asBoolean()).isTrue();
        assertThat(view.path("send").path("note").asText()).isEqualTo("All checks passed. Ready to send.");
        assertThat(view.path("secondCheck").path("confirmedByName").asText()).isEqualTo("Sr Mary");
        assertThat(view.path("stages").path("secondCheck").asBoolean()).isTrue();
        ObjectNode sent = service.send(DOCTOR, RECORD, new ActionInput(stored().revision,
                view.path("fingerprint").asText()));
        assertThat(sent.path("status").asText()).isEqualTo("SENT");
        assertThat(sent.path("stages").path("pharmacy").asText()).isEqualTo("SENT");
        assertThat(sent.path("sentAt").asText()).isEqualTo(NOW.toString());
        assertThat(sent.path("sentByName").asText()).isEqualTo("Dr Rao");
        assertThat(sent.path("drugs").get(0).path("finalMg").asDouble()).as("the sent dose stays").isEqualTo(100.0);
        ChemoOrderRow row = stored();
        assertThat(row.status).isEqualTo("SENT");
        assertThat(row.sentBy).isEqualTo(DOCTOR_USER);
        ArgumentCaptor<Map<String, Object>> after = captor();
        verify(audit).record(eq("CHEMO_ORDER_SENT"), eq("CHEMO_ORDER"), eq(row.id), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("way", "SIGN_IN").containsEntry("role", "NURSE")
                .containsEntry("checkerUserId", NURSE_USER).containsEntry("protocolKey", "AC");

        int updates = orders.updates;
        ObjectNode retry = service.send(DOCTOR, RECORD, new ActionInput(row.revision, view.path("fingerprint")
                .asText()));
        assertThat(retry.path("status").asText()).isEqualTo("SENT");
        assertThat(orders.updates).as("a retry makes one order").isEqualTo(updates);
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, order(row.revision))).satisfies(
                e -> assertThat(problem(e)).isEqualTo("chemo-order-sent"));
        assertThatThrownBy(() -> service.send(DOCTOR, RECORD, new ActionInput(row.revision, "b".repeat(64))))
                .satisfies(e -> assertThat(problem(e)).isEqualTo("chemo-order-changed"));
    }

    // ---- SAH-75-7 (ADR-026 D7): a sent order sets the regimen of the cycle sitting linked to the visit ----

    private static com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow linkedCycle(String regimen,
                                                                                                    Instant removedAt) {
        return new com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow(UUID.randomUUID(), UUID.randomUUID(),
                2, "DAY_CARE", "CHEMO_CYCLE", null, List.of(), 0, null, VISIT, null, true, null, null, null, removedAt,
                null, regimen);
    }

    private com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow courseOf(String kind) {
        return new com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow(UUID.randomUUID(), 4, PATIENT, kind,
                "CYCLE", "Course", null, DOCTOR_M, java.time.LocalDate.parse("2026-10-01"), "ACTIVE", 0, "h", NOW);
    }

    private void sendWithLinkedSitting(com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow sitting,
                                       String courseKind) {
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.of(sitting));
        when(courses.find(TENANT, sitting.courseId())).thenReturn(Optional.of(courseOf(courseKind)));
        ObjectNode view = confirmedOrder();
        service.send(DOCTOR, RECORD, new ActionInput(stored().revision, view.path("fingerprint").asText()));
    }

    @Test
    void sendingTheOrderSetsTheRegimenOfTheLinkedCycleWhenItHasNone() {
        var sitting = linkedCycle(null, null);

        sendWithLinkedSitting(sitting, "CHEMO");

        verify(courses).setRegimenIfEmpty(TENANT, sitting.id(), "AC", DOCTOR_USER);
        verify(courses).bumpRevision(TENANT, sitting.courseId(), DOCTOR_USER);
    }

    @Test
    void aCycleThatAlreadyHasARegimenIsLeftAlone() {
        sendWithLinkedSitting(linkedCycle("TC", null), "CHEMO");

        verify(courses, org.mockito.Mockito.never()).setRegimenIfEmpty(any(), any(), any(), any());
    }

    @Test
    void aRemovedLinkedSittingIsLeftAlone() {
        sendWithLinkedSitting(linkedCycle(null, NOW), "CHEMO");

        verify(courses, org.mockito.Mockito.never()).setRegimenIfEmpty(any(), any(), any(), any());
    }

    @Test
    void aSittingOfAnotherKindOfCourseIsLeftAlone() {
        sendWithLinkedSitting(linkedCycle(null, null), "SURGERY");

        verify(courses, org.mockito.Mockito.never()).setRegimenIfEmpty(any(), any(), any(), any());
    }

    @Test
    void anOrderForAVisitWithNoLinkedSittingTouchesNoCourse() {
        when(courses.sittingByVisit(TENANT, VISIT)).thenReturn(Optional.empty());
        ObjectNode view = confirmedOrder();

        service.send(DOCTOR, RECORD, new ActionInput(stored().revision, view.path("fingerprint").asText()));

        verify(courses, org.mockito.Mockito.never()).setRegimenIfEmpty(any(), any(), any(), any());
        verify(courses, org.mockito.Mockito.never()).bumpRevision(any(), any(), any());
    }

    @Test
    void aChangeAfterTheSecondCheckClearsItAuditsItAndBlocksTheSendTaggedC8() {
        confirmedOrder();
        // the doctor changes the labs in the record; the draft save recomputes the order in the same transaction
        sections = oncologyRecord("1900").toJson();
        support.afterSave(TENANT, DOCTOR_USER, DOCTOR_M, row(), "onco", EmrRecordDocument.parse(MAPPER, sections));
        ObjectNode hookView = service.get(DOCTOR, RECORD);
        assertThat(hookView.path("secondCheck").path("state").asText()).isEqualTo("WAITING");
        // the hook is reached through the bean; call it directly here as the record service does
        service.onRecordSaved(TENANT, DOCTOR_USER, DOCTOR_M, row(), EmrRecordDocument.parse(MAPPER, sections));
        ChemoOrderRow row = stored();
        assertThat(row.confirmedAt).isNull();
        assertThat(row.confirmedFingerprint).isNull();
        assertThat(row.confirmationClearedAt).isEqualTo(NOW);
        verify(audit).record(eq("CHEMO_SECOND_CHECK_CLEARED"), eq("CHEMO_ORDER"), eq(row.id), eq(null),
                org.mockito.ArgumentMatchers.argThat(m -> m != null && m.get("reasonKey").equals("ORDER_CHANGED")));
        ObjectNode view = service.get(DOCTOR, RECORD);
        assertThat(view.path("secondCheck").path("state").asText()).isEqualTo("CLEARED");
        assertThat(view.path("send").path("ready").asBoolean()).isFalse();
        assertThatThrownBy(() -> service.send(DOCTOR, RECORD, new ActionInput(row.revision,
                view.path("fingerprint").asText()))).satisfies(e -> assertThat(problem(e))
                .isEqualTo("second-check-not-confirmed"));
        // the checker sees the order again, waiting
        assertThat(checks.list(NURSE, 0).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void aRecordSaveThatDoesNotChangeTheFingerprintLeavesTheOrderAndItsRevisionAlone() {
        confirmedOrder();
        long revision = stored().revision;
        service.onRecordSaved(TENANT, DOCTOR_USER, DOCTOR_M, row(), EmrRecordDocument.parse(MAPPER, sections));
        assertThat(stored().revision).isEqualTo(revision);
        assertThat(stored().confirmedAt).isNotNull();
    }

    @Test
    void aSheetSaveThatChangesADoseAlsoClearsTheConfirmation() {
        confirmedOrder();
        service.save(DOCTOR, RECORD, new OrderInput(stored().revision, "AC", 100, null, null,
                List.of(new FinalDose("doxorubicin", 95.0)), List.of(), true, true, "APPROVED", null));
        assertThat(stored().confirmedAt).isNull();
        assertThat(service.get(DOCTOR, RECORD).path("secondCheck").path("state").asText()).isEqualTo("CLEARED");
        assertThat(service.get(DOCTOR, RECORD).path("drugs").get(0).path("moreThan5Pct").asBoolean()).isFalse();
    }

    @Test
    void aSendIsRefusedWhenACheckHasTurnedRedSinceTheConfirmation() {
        ObjectNode view = confirmedOrder();
        sections = oncologyRecord("1000").toJson();
        assertThatThrownBy(() -> service.send(DOCTOR, RECORD, new ActionInput(stored().revision,
                view.path("fingerprint").asText()))).satisfies(e -> assertThat(problem(e)).isEqualTo("checks-failing"));
        assertThat(stored().status).isEqualTo("DRAFT");
    }

    // ---- ADR-023 D14: the fingerprint covers the inputs a check text relies on (chemo-fp-v2) ----

    private OrderInput input(long revision, String scheme, boolean consent, List<String> premeds, Double prior) {
        return new OrderInput(revision, "AC", 100, null, null, List.of(), premeds, true, consent, scheme, prior);
    }

    /** An earlier SENT order of the protocol for the patient (so a consent tick is not needed for the check to pass). */
    private void earlierSentOrder() {
        ChemoOrderRow earlier = new ChemoOrderRow();
        earlier.id = UUID.randomUUID();
        earlier.recordId = UUID.randomUUID();
        earlier.patientId = PATIENT;
        earlier.status = "SENT";
        earlier.protocolKey = "AC";
        earlier.doxorubicinMgM2 = java.math.BigDecimal.valueOf(60);
        earlier.priorOutsideMgM2 = java.math.BigDecimal.valueOf(100);
        earlier.sentAt = NOW.minusSeconds(21 * 86400L);
        orders.rows.put(earlier.id, earlier);
    }

    /**
     * Saves {@code first}, names and confirms the checker, then saves {@code changed}: every check is still green, yet the
     * fingerprint changed, so the confirmation is cleared in the same update, audited, and a send with the old fingerprint
     * is refused.
     */
    private void assertTheChangeClearsTheConfirmation(OrderInput first, java.util.function.LongFunction<OrderInput> changed) {
        service.save(DOCTOR, RECORD, first);
        service.nameChecker(DOCTOR, RECORD, new CheckerInput(stored().revision, NURSE_M));
        String oldFingerprint = checks.summary(NURSE, stored().id).path("fingerprint").asText();
        checks.confirm(NURSE, stored().id, new ConfirmInput(oldFingerprint));
        assertThat(stored().confirmedFingerprint).isEqualTo(oldFingerprint);
        int updates = orders.updates;

        ObjectNode view = service.save(DOCTOR, RECORD, changed.apply(stored().revision));

        ChemoOrderRow row = stored();
        assertThat(view.path("passedCount").asInt()).as("every check is still green").isEqualTo(10);
        assertThat(row.fingerprint).isNotEqualTo(oldFingerprint).isEqualTo(view.path("fingerprint").asText());
        assertThat(row.confirmedAt).isNull();
        assertThat(row.confirmedFingerprint).isNull();
        assertThat(row.confirmationClearedAt).isEqualTo(NOW);
        assertThat(orders.updates).as("the fingerprint and the clearing are one update").isEqualTo(updates + 1);
        assertThat(view.path("secondCheck").path("state").asText()).isEqualTo("CLEARED");
        verify(audit).record(eq("CHEMO_SECOND_CHECK_CLEARED"), eq("CHEMO_ORDER"), eq(row.id), eq(null),
                org.mockito.ArgumentMatchers.argThat(m -> m != null && "ORDER_CHANGED".equals(m.get("reasonKey"))));
        assertThatThrownBy(() -> service.send(DOCTOR, RECORD, new ActionInput(row.revision, oldFingerprint)))
                .satisfies(e -> {
                    assertThat(problem(e)).isEqualTo("chemo-order-changed");
                    assertThat(((EmrProblemException) e).status()).isEqualTo(409);
                });
        assertThat(stored().status).isEqualTo("DRAFT");
    }

    @Test
    void changingTheSchemeFromPreauthRaisedToApprovedClearsTheSecondCheckTaggedC8() {
        assertTheChangeClearsTheConfirmation(input(0, "PREAUTH_RAISED", true, List.of(), null),
                revision -> input(revision, "APPROVED", true, List.of(), null));
    }

    @Test
    void tickingNewConsentSignedClearsTheSecondCheckEvenWhenAnEarlierOrderKeepsTheCheckGreenTaggedC8() {
        earlierSentOrder();
        assertTheChangeClearsTheConfirmation(input(0, "APPROVED", false, List.of(), null),
                revision -> input(revision, "APPROVED", true, List.of(), null));
    }

    @Test
    void untickingNewConsentSignedClearsTheSecondCheckEvenWhenAnEarlierOrderKeepsTheCheckGreenTaggedC8() {
        earlierSentOrder();
        assertTheChangeClearsTheConfirmation(input(0, "APPROVED", true, List.of(), null),
                revision -> input(revision, "APPROVED", false, List.of(), null));
    }

    @Test
    void changingThePreMedicinesNotGivenClearsTheSecondCheckTaggedC8() {
        assertTheChangeClearsTheConfirmation(input(0, "APPROVED", true, List.of(), null),
                revision -> input(revision, "APPROVED", true, List.of("ondansetron_iv"), null));
    }

    @Test
    void changingEarlierOutsideDoxorubicinUnderTheLimitClearsTheSecondCheckTaggedC8() {
        earlierSentOrder();
        assertTheChangeClearsTheConfirmation(input(0, "APPROVED", true, List.of(), 100.0),
                revision -> input(revision, "APPROVED", true, List.of(), 120.0));
    }

    @Test
    void aSaveThatChangesNothingKeepsTheFingerprintAndTheConfirmation() {
        confirmedOrder();
        String fingerprint = stored().fingerprint;
        service.save(DOCTOR, RECORD, order(stored().revision));
        assertThat(stored().fingerprint).isEqualTo(fingerprint);
        assertThat(stored().confirmedFingerprint).isEqualTo(fingerprint);
        assertThat(stored().confirmedAt).isNotNull();
        verify(audit, never()).record(eq("CHEMO_SECOND_CHECK_CLEARED"), anyString(), any(), any(), any());
    }

    // ---- hold ----

    @Test
    void aDelayDecisionRecordsTheHoldWithoutASecondCheckAndFreezesTheOrder() {
        screenState = decision("DELAY_1_WEEK");
        ObjectNode view = service.hold(DOCTOR, RECORD, new HoldInput(0L, null));
        assertThat(view.path("status").asText()).isEqualTo("HELD");
        assertThat(view.path("hold").path("kind").asText()).isEqualTo("DELAY");
        assertThat(view.path("hold").path("reason").asText()).isNotBlank();
        assertThat(view.path("hold").path("date").asText()).isEqualTo("2026-11-12");
        assertThat(view.path("send").path("holdMode").asBoolean()).isTrue();
        ChemoOrderRow row = stored();
        assertThat(row.holdKind).isEqualTo("DELAY");
        assertThat(row.confirmedAt).isNull();
        verify(audit).record(eq("CHEMO_HOLD_RECORDED"), eq("CHEMO_ORDER"), eq(row.id), eq(null),
                org.mockito.ArgumentMatchers.argThat(m -> m != null && m.get("kind").equals("DELAY")));
        // repeating answers the same hold; the held order cannot be changed
        assertThat(service.hold(DOCTOR, RECORD, new HoldInput(row.revision, null)).path("status").asText())
                .isEqualTo("HELD");
        assertThatThrownBy(() -> service.save(DOCTOR, RECORD, order(row.revision))).satisfies(
                e -> assertThat(problem(e)).isEqualTo("chemo-order-sent"));
    }

    @Test
    void aStopDecisionHoldsWithAnOwnReasonText() {
        screenState = decision("STOP");
        ObjectNode view = service.hold(DOCTOR, RECORD, new HoldInput(0L, "Palliative care chosen"));
        assertThat(view.path("hold").path("kind").asText()).isEqualTo("STOP");
        assertThat(view.path("hold").path("reason").asText()).isEqualTo("Palliative care chosen");
    }

    @Test
    void theHoldNeedsADelayOrStopDecision() {
        assertThatThrownBy(() -> service.hold(DOCTOR, RECORD, new HoldInput(0L, null))).satisfies(e -> {
            assertThat(problem(e)).isEqualTo("checks-failing");
            assertThat(((EmrProblemException) e).getMessage()).isEqualTo("Record the hold needs a Delay or Stop decision.");
        });
        assertThat(orders.rows).isEmpty();
    }

    // ---- way (b): the colleague PIN ----

    private PinInput pin(String fingerprint, long revision, UUID checker) {
        return new PinInput(revision, fingerprint, checker, "4821");
    }

    @Test
    void theColleaguePinIsCheckedBetweenTwoTransactionsAndTheConfirmationRecordsThePath() {
        String fingerprint = namedFingerprint();
        long revision = stored().revision;
        when(staffPin.checkColleague(eq(TENANT), eq(DOCTOR_USER), eq(NURSE_M), eq("NURSE"), eq("4821")))
                .thenReturn(ColleaguePinCheck.ok(NURSE_USER));
        ObjectNode view = secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, revision, NURSE_M));
        assertThat(view.path("secondCheck").path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(view.path("secondCheck").path("via").asText()).isEqualTo("PIN");
        ChemoOrderRow row = stored();
        assertThat(row.confirmedVia).isEqualTo("PIN");
        assertThat(row.confirmedByUserId).isEqualTo(NURSE_USER);
        assertThat(row.confirmedFingerprint).isEqualTo(fingerprint);
        InOrder order = inOrder(staffPin, audit);
        order.verify(staffPin).checkColleague(TENANT, DOCTOR_USER, NURSE_M, "NURSE", "4821");
        order.verify(audit).record(eq("CHEMO_SECOND_CHECK_CONFIRMED"), eq("CHEMO_ORDER"), any(), eq(null),
                org.mockito.ArgumentMatchers.argThat(m -> m != null && m.get("way").equals("PIN")
                        && !m.values().toString().contains("4821")));
    }

    @Test
    void aWrongPinAnswersTheNeutralCheckerTextAndConfirmsNothing() {
        String fingerprint = namedFingerprint();
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any())).thenReturn(ColleaguePinCheck.failed(
                new PinCheck(PinCheck.Outcome.WRONG, 3, null, 0)));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, stored().revision, NURSE_M)))
                .isInstanceOf(StaffPinException.class).satisfies(e -> {
                    StaffPinException pinProblem = (StaffPinException) e;
                    assertThat(pinProblem.slug()).isEqualTo("wrong-pin");
                    assertThat(pinProblem.getMessage()).isEqualTo("That PIN is not right.");
                    assertThat(pinProblem.subject()).isEqualTo(StaffPinException.Subject.CHECKER);
                });
        assertThat(stored().confirmedAt).isNull();
    }

    @Test
    void theColleagueLimitAndLockoutTextsNeverSayNurseForTheChecker() {
        String fingerprint = namedFingerprint();
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any())).thenReturn(ColleaguePinCheck.failed(
                new PinCheck(PinCheck.Outcome.PAIR_LIMIT, 0, null, 0)));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, stored().revision, NURSE_M)))
                .satisfies(e -> {
                    assertThat(((StaffPinException) e).slug()).isEqualTo("colleague-pin-limit");
                    assertThat(e.getMessage()).isEqualTo("Too many wrong PIN tries for this person. Choose another checker.");
                });
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any())).thenReturn(ColleaguePinCheck.failed(
                new PinCheck(PinCheck.Outcome.CHECK_FAILED, 0, null, 0)));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, stored().revision, NURSE_M)))
                .satisfies(e -> {
                    assertThat(((StaffPinException) e).slug()).isEqualTo("pin-check-failed");
                    assertThat(e.getMessage()).isEqualTo("We could not check the PIN. Try again.");
                });
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any())).thenReturn(ColleaguePinCheck.failed(
                new PinCheck(PinCheck.Outcome.NOT_SET, 0, null, 0)));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, stored().revision, NURSE_M)))
                .satisfies(e -> assertThat(e.getMessage()).isEqualTo(
                        "The checker has not set a PIN yet. Choose another checker."));
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any())).thenReturn(ColleaguePinCheck.failed(
                new PinCheck(PinCheck.Outcome.LOCKED, 0, NOW.plusSeconds(600), 600)));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, stored().revision, NURSE_M)))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("nurse").contains("checker"));
    }

    @Test
    void aPersonWhoCannotBeNamedIsAChooseFromTheListAnswer() {
        String fingerprint = namedFingerprint();
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any())).thenReturn(
                ColleaguePinCheck.notEligible());
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, stored().revision, NURSE_M)))
                .isInstanceOf(InvalidFieldsException.class).satisfies(e -> assertThat(
                        ((InvalidFieldsException) e).errors().get(0).message()).isEqualTo("Choose a checker from the list."));
    }

    @Test
    void theColleaguePinIsNeverCheckedForAStaleOrWrongRequest() {
        String fingerprint = namedFingerprint();
        long revision = stored().revision;
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin("c".repeat(64), revision, NURSE_M)))
                .satisfies(e -> assertThat(problem(e)).isEqualTo("chemo-order-changed"));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, revision + 3, NURSE_M)))
                .satisfies(e -> assertThat(problem(e)).isEqualTo("chemo-order-changed"));
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, revision,
                OTHER_NURSE_M))).satisfies(e -> assertThat(problem(e)).isEqualTo("chemo-order-changed"));
        sections = oncologyRecord("1000").toJson();
        assertThatThrownBy(() -> secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, revision, NURSE_M)))
                .satisfies(e -> assertThat(problem(e)).isEqualTo("checks-failing"));
        verify(staffPin, never()).checkColleague(any(), any(), any(), anyString(), any());
    }

    @Test
    void aPinConfirmationOfAnAlreadyConfirmedOrderAnswersWithoutTheColleaguePin() {
        String fingerprint = namedFingerprint();
        long revision = stored().revision;
        when(staffPin.checkColleague(any(), any(), any(), anyString(), any()))
                .thenReturn(ColleaguePinCheck.ok(NURSE_USER));
        secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, revision, NURSE_M));
        org.mockito.Mockito.clearInvocations(staffPin);
        ObjectNode again = secondCheck.confirmByPin(DOCTOR, RECORD, pin(fingerprint, revision, NURSE_M));
        assertThat(again.path("secondCheck").path("state").asText()).isEqualTo("CONFIRMED");
        verify(staffPin, never()).checkColleague(any(), any(), any(), anyString(), any());
    }

    @Test
    void theToStringsNeverShowAPinOrAClinicalValue() {
        assertThat(pin("d".repeat(64), 1, NURSE_M).toString()).doesNotContain("4821");
        saved();
        assertThat(stored().toString()).startsWith("ChemoOrderRow[id=").doesNotContain("doxorubicin");
    }
}
