package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.ActionResult;
import com.sahaj.ipd.application.SafetyViews.ResponseInput;
import com.sahaj.ipd.application.SafetyViews.SeniorCallInput;
import com.sahaj.ipd.application.SafetyViews.VersionInput;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.ResponseAction;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-33 Acknowledge, Close escalation and Call senior: the lock order, the answers of ADR-007 D7 in their order,
 * replays, roles read again from the membership, events, audit and privacy.
 */
class EscalationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OTHER_DOCTOR = UUID.randomUUID();
    private static final UUID RAISER = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID ESC = UUID.randomUUID();
    private static final String SECRET_NAME = "Ramesh Kulkarni";
    private static final String PLAN = "Oxygen increased to 6 L, repeat vitals in 30 minutes";
    /** 11 Nov 2026, 11:00 AM IST. */
    private static final Instant RAISED_AT = Instant.parse("2026-11-11T05:30:00Z");
    /** 11:20:10 AM IST: the database clock of every answer. */
    private static final Instant DB_NOW = Instant.parse("2026-11-11T05:50:10Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private EscalationBoardRepository board;
    private AdmissionRepository admissions;
    private ClinicalEventRepository events;
    private PatientLookupPort patients;
    private ActorDirectoryPort actors;
    private TeamMemberPort team;
    private AuditLog auditLog;
    private ModuleEntitlementPort entitlements;
    private SeniorCallRepository seniorCalls;
    private ScopeGuard guard = ScopeStubs.unscoped();
    private EscalationService service;
    private String memberRole = "DOCTOR";
    private boolean memberActive = true;
    private String admissionStatus = "ADMITTED";

    @BeforeEach
    void setUp() {
        board = mock(EscalationBoardRepository.class);
        admissions = mock(AdmissionRepository.class);
        events = mock(ClinicalEventRepository.class);
        patients = mock(PatientLookupPort.class);
        actors = mock(ActorDirectoryPort.class);
        team = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(DB_NOW, ZoneOffset.UTC));
        seniorCalls = mock(SeniorCallRepository.class);
        service = new EscalationService(board, admissions, events, patients, actors, team, auditLog, gate,
                seniorCalls, guard);

        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 25));
        when(board.databaseNow()).thenReturn(DB_NOW);
        when(team.findActiveByUserId(eq(TENANT), eq(ACTOR))).thenAnswer(call -> memberRole == null ? Optional.empty()
                : Optional.of(new TeamMember(UUID.randomUUID(), "Member", memberRole, memberActive, 0L)));
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenAnswer(call -> Optional.of(new AdmissionRow(
                ADMISSION, "IP/26-27/0042", PATIENT, UUID.randomUUID(), "202", "general", "General ward",
                UUID.randomUUID(), "planned", "Self", "Chest pain", admissionStatus,
                Instant.parse("2026-11-09T05:30:00Z"), 0L)));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, SECRET_NAME, 52, "M",
                "9876543210")));
        Map<UUID, String> names = new HashMap<>();
        names.put(RAISER, "Sr. Anjali Pawar");
        names.put(ACTOR, "Dr Meera Joshi");
        names.put(OTHER_DOCTOR, "Dr Ravi Shah");
        when(actors.displayNames(eq(TENANT), any())).thenReturn(names);
    }

    // ---- fixtures ----

    private static JsonNode num(long value) {
        return JSON.getNodeFactory().numberNode(value);
    }

    private static VersionInput version(long value) {
        return new VersionInput(num(value));
    }

    private static ResponseInput response(long version, ResponseAction action, String note) {
        return new ResponseInput(num(version), action.name(), note);
    }

    private static SeniorCallInput call(long version, int calls) {
        return new SeniorCallInput(num(version), num(calls));
    }

    private static Detail open(long version, int calls) {
        return new Detail(ESC, ADMISSION, version, 8, Risk.HIGH, 15, Status.OPEN, RAISED_AT, RAISER, null, null, null,
                null, null, calls, null, null);
    }

    private static Detail acked(long version, UUID by) {
        return new Detail(ESC, ADMISSION, version, 8, Risk.HIGH, 15, Status.ACK, RAISED_AT, RAISER, null, by,
                Instant.parse("2026-11-11T05:37:45Z"), null, null, 0, null, null);
    }

    private static Detail closed(long version, UUID by, ResponseAction action, String note) {
        return new Detail(ESC, ADMISSION, version, 8, Risk.HIGH, 15, Status.DONE, RAISED_AT, RAISER, null, by,
                Instant.parse("2026-11-11T05:40:00Z"), action.name(), note, 0, by,
                Instant.parse("2026-11-11T05:40:00Z"));
    }

    /** The row as read before the lock twice, then as it is after the write. */
    private void rows(Detail before, Detail after) {
        when(board.findById(TENANT, ESC)).thenReturn(Optional.of(before), Optional.of(before), Optional.of(after));
    }

    private void row(Detail only) {
        when(board.findById(TENANT, ESC)).thenReturn(Optional.of(only));
    }

    private void verifyNothingWritten() {
        verify(board, never()).acknowledge(any(), any(), any(), anyLong());
        verify(board, never()).close(any(), any(), any(), any(), any(), any(), anyLong());
        verify(board, never()).callSenior(any(), any(), anyLong(), anyInt());
        verify(seniorCalls, never()).insertCalled(any(), any(), any(), anyInt());
        verify(seniorCalls, never()).insertAlreadyCalled(any(), any(), any());
        verify(events, never()).insert(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- acknowledge ----

    @Test
    void anOpenEscalationIsAcknowledgedWithTheDoctorTheVersionTheEventAndTheAudit() {
        rows(open(3, 0), acked(4, ACTOR));
        when(board.acknowledge(TENANT, ESC, ACTOR, 3)).thenReturn(true);

        ActionResult result = service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3));

        assertThat(result.replayed()).isFalse();
        assertThat(result.asOf()).isEqualTo(DB_NOW);
        assertThat(result.escalation().status()).isEqualTo(Status.ACK);
        assertThat(result.escalation().version()).isEqualTo(4);
        assertThat(result.escalation().acknowledgedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(result.escalation().patientName()).isEqualTo(SECRET_NAME);
        assertThat(result.escalation().bedCode()).isEqualTo("202");
        assertThat(result.escalation().canAcknowledge()).isFalse();
        assertThat(result.escalation().canRespond()).isTrue();
        assertThat(result.escalation().canCallSenior()).isFalse();
        assertThat(result.escalation().pastTarget()).isFalse();
        verify(board).acknowledge(TENANT, ESC, ACTOR, 3);
        // raised 11:00:00, acknowledged by the database at 11:07:45: 7 whole minutes
        verify(events).insert(TENANT, ADMISSION, "esc", "Acknowledged escalation after 7 min", ACTOR);
    }

    @Test
    void theMinutesToAcknowledgeAreMeasuredFromTheReopenWhenThereIsOne() {
        Detail reopened = new Detail(ESC, ADMISSION, 6, 9, Risk.HIGH, 15, Status.OPEN, RAISED_AT, RAISER,
                Instant.parse("2026-11-11T05:36:00Z"), null, null, null, null, 0, null, null);
        Detail after = new Detail(ESC, ADMISSION, 7, 9, Risk.HIGH, 15, Status.ACK, RAISED_AT, RAISER,
                Instant.parse("2026-11-11T05:36:00Z"), ACTOR, Instant.parse("2026-11-11T05:37:45Z"), null, null, 0,
                null, null);
        rows(reopened, after);
        when(board.acknowledge(TENANT, ESC, ACTOR, 6)).thenReturn(true);

        service.acknowledge(TENANT, ACTOR, "OWNER", ESC, version(6));

        verify(events).insert(TENANT, ADMISSION, "esc", "Acknowledged escalation after 1 min", ACTOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAcknowledgeAuditHoldsIdsScoreRiskAndMinutesOnly() {
        rows(open(3, 0), acked(4, ACTOR));
        when(board.acknowledge(TENANT, ESC, ACTOR, 3)).thenReturn(true);

        service.acknowledge(TENANT, ACTOR, "OWNER", ESC, version(3));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ESCALATION_ACKNOWLEDGED"), eq("IPD_EWS_ESCALATION"), eq(ESC), isNull(),
                after.capture());
        assertThat(after.getValue()).containsOnlyKeys("escalationId", "admissionId", "ewsScore", "ewsRisk",
                "minutesToAcknowledge");
        assertThat(after.getValue()).containsEntry("escalationId", ESC).containsEntry("admissionId", ADMISSION)
                .containsEntry("ewsScore", 8).containsEntry("ewsRisk", "HIGH")
                .containsEntry("minutesToAcknowledge", 7);
        assertThat(after.getValue().toString()).doesNotContain(SECRET_NAME).doesNotContain("Meera");
    }

    @Test
    void theLockIsTakenOnTheBareAdmissionRowAfterTheRoleRereadAndTheEscalationIsReadAgainAfterIt() {
        rows(open(3, 0), acked(4, ACTOR));
        when(board.acknowledge(TENANT, ESC, ACTOR, 3)).thenReturn(true);

        service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3));

        InOrder order = inOrder(team, board, admissions, patients, events, auditLog);
        order.verify(team).findActiveByUserId(TENANT, ACTOR);
        order.verify(board).findById(TENANT, ESC);
        order.verify(admissions).findForNoKeyUpdate(TENANT, ADMISSION);
        order.verify(board).findById(TENANT, ESC);
        order.verify(patients).find(TENANT, PATIENT);
        order.verify(board).acknowledge(TENANT, ESC, ACTOR, 3);
        order.verify(board).findById(TENANT, ESC);
        order.verify(events).insert(any(), any(), any(), any(), any());
        order.verify(auditLog).record(any(), any(), any(), any(), any());
        verify(admissions, never()).findForUpdate(any(), any());
        verify(admissions, never()).find(any(), any());
    }

    @Test
    void aStaleVersionOnAnOpenEscalationIs409EscalationChangedWithTheCurrentScoreAndNothingIsWritten() {
        row(open(5, 0));

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-changed");
                    assertThat(e.getMessage()).isEqualTo("The score is now EWS 8 (high). Review it and try again.");
                });

        verifyNothingWritten();
    }

    @Test
    void anEscalationAcknowledgedByAnotherDoctorIs409EscalationStateWithTheirName() {
        row(acked(4, OTHER_DOCTOR));

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-state");
                    assertThat(e.getMessage()).isEqualTo("Dr Ravi Shah already acknowledged this escalation.");
                });

        verifyNothingWritten();
    }

    @Test
    void anUnknownAcknowledgingDoctorIsNamedTeamMemberAndNeverBlank() {
        row(acked(4, UUID.randomUUID()));

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Team member already acknowledged this escalation."));
    }

    @Test
    void aClosedEscalationIs409EscalationStateAlreadyClosed() {
        row(closed(5, OTHER_DOCTOR, ResponseAction.NO_CHANGE, PLAN));

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(4)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-state");
                    assertThat(e.getMessage()).isEqualTo("This escalation is already closed.");
                });

        verifyNothingWritten();
    }

    @Test
    void theStateAnswerComesBeforeTheChangedAnswerWhenBothApply() {
        row(acked(9, OTHER_DOCTOR));

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(1)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("escalation-state"));
    }

    @Test
    void theSameDoctorRepeatingAfterALostAnswerGets200ReplayedAndNothingIsWrittenOrAudited() {
        row(acked(4, ACTOR));

        ActionResult result = service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3));

        assertThat(result.replayed()).isTrue();
        assertThat(result.asOf()).isEqualTo(DB_NOW);
        assertThat(result.escalation().status()).isEqualTo(Status.ACK);
        verifyNothingWritten();
    }

    @Test
    void aFailedAcknowledgeUpdateStopsBeforeTheEventAndTheAudit() {
        row(open(3, 0));
        when(board.acknowledge(TENANT, ESC, ACTOR, 3)).thenReturn(false);

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(IllegalStateException.class);

        verify(events, never()).insert(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- roles, module state and the body, in order ----

    @Test
    void acknowledgeAndRespondAreRefusedForEveryRoleButOwnerAndDoctorBeforeAnythingIsRead() {
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "X", null}) {
            assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, role, ESC, version(3)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.respond(TENANT, ACTOR, role, ESC, response(3, ResponseAction.NO_CHANGE,
                    PLAN))).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(board, admissions, team, entitlements, events, auditLog);
    }

    @Test
    void callSeniorIsRefusedForEveryRoleButNurseBeforeAnythingIsRead() {
        for (String role : new String[] {"OWNER", "DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST",
            null}) {
            assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, role, ESC, call(3, 0)))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(board, admissions, team, entitlements, events, auditLog);
    }

    @Test
    void theRoleIsCheckedBeforeTheModuleStateAndTheModuleStateBeforeTheBody() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "NURSE", ESC, version(3)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, new VersionInput(null)))
                .isInstanceOf(ModuleOffException.class);
        verifyNoInteractions(board, admissions, team);
    }

    @Test
    void aBadBodyIs400BeforeTheMembershipIsReadAndBeforeAnyLock() {
        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, new VersionInput(num(-1))))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, new ResponseInput(num(3), "NO_CHANGE",
                "  "))).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, new SeniorCallInput(num(1), null)))
                .isInstanceOf(InvalidFieldsException.class);

        verifyNoInteractions(board, admissions, team);
    }

    @Test
    void theRoleIsReadAgainFromTheMembershipAndAChangedRoleIsRefusedAtOnceWithNothingLocked() {
        for (String now : new String[] {"NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", null}) {
            memberRole = now;
            assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "OWNER", ESC, version(3)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                    PLAN))).isInstanceOf(AccessDeniedException.class);
        }
        memberRole = "DOCTOR";
        memberActive = false;
        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(board, admissions);
    }

    @Test
    void aNurseWhoIsNoLongerANurseCannotCallTheSeniorDoctor() {
        memberRole = "DOCTOR";

        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(AccessDeniedException.class);
        memberRole = null;
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(board, admissions);
    }

    @Test
    void readOnlyAndTrialIpdAllowEveryActionButOffIsModuleOff() {
        for (String state : new String[] {"readonly", "trial", "on"}) {
            when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(state, false, 25));
            rows(open(3, 0), acked(4, ACTOR));
            when(board.acknowledge(TENANT, ESC, ACTOR, 3)).thenReturn(true);

            assertThat(service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)).replayed()).as(state).isFalse();
        }
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "OWNER", ESC, version(3)))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "OWNER", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(ModuleOffException.class);
    }

    // ---- 404 ----

    @Test
    void anEscalationThatDoesNotExistOrBelongsToAnotherClinicIs404AndNothingIsLocked() {
        when(board.findById(TENANT, ESC)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(NotFoundException.class);
        verify(admissions, never()).findForNoKeyUpdate(any(), any());
        verifyNothingWritten();
    }

    @Test
    void anAdmissionThatCannotBeLockedIs404() {
        row(open(3, 0));
        when(admissions.findForNoKeyUpdate(TENANT, ADMISSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(NotFoundException.class);
        verifyNothingWritten();
    }

    @Test
    void aDischargedPatientIs404ForEveryAction() {
        row(open(3, 0));
        admissionStatus = "DISCHARGED";

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOf(NotFoundException.class);
        memberRole = "NURSE";
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(NotFoundException.class);
        verifyNothingWritten();
    }

    @Test
    void aDischargedPatientOutsideHerScopeIs403OutsideScopeBeforeTheNotAdmittedAnswer() {
        row(open(3, 0));
        admissionStatus = "DISCHARGED";
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("DOCTOR"),
                eq("DOCTOR"), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(OutsideScopeException.class);
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOf(OutsideScopeException.class);
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("NURSE"),
                eq("NURSE"), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());
        memberRole = "NURSE";
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(OutsideScopeException.class);
        verifyNothingWritten();
    }

    @Test
    void aMissingPatientRecordIs404AndNeverAPlaceholderNameInAnActionAnswer() {
        row(open(3, 0));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(NotFoundException.class);
        verifyNothingWritten();
    }

    // ---- respond ----

    @Test
    void closingAnOpenEscalationStoresTheActionAndPlanAndWritesNoAcknowledgedEvent() {
        rows(open(3, 0), closed(4, ACTOR, ResponseAction.TREATMENT_CHANGED, PLAN));
        when(board.close(TENANT, ESC, Status.OPEN, ACTOR, ResponseAction.TREATMENT_CHANGED, PLAN, 3))
                .thenReturn(true);

        ActionResult result = service.respond(TENANT, ACTOR, "OWNER", ESC,
                response(3, ResponseAction.TREATMENT_CHANGED, "  " + PLAN + "  "));

        assertThat(result.replayed()).isFalse();
        assertThat(result.escalation().status()).isEqualTo(Status.DONE);
        assertThat(result.escalation().response().action()).isEqualTo("TREATMENT_CHANGED");
        assertThat(result.escalation().response().actionLabel()).isEqualTo("Treatment changed");
        assertThat(result.escalation().response().note()).isEqualTo(PLAN);
        assertThat(result.escalation().response().closedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(result.escalation().acknowledgedByName()).isEqualTo("Dr Meera Joshi");
        assertThat(result.escalation().canAcknowledge()).isFalse();
        assertThat(result.escalation().canRespond()).isFalse();
        assertThat(result.escalation().pastTarget()).isFalse();
        verify(events).insert(TENANT, ADMISSION, "esc", "Escalation closed: Treatment changed", ACTOR);
        verify(events, never()).insert(any(), any(), any(), eq("Acknowledged escalation after 20 min"), any());
    }

    @Test
    void closingAnAcknowledgedEscalationPassesTheStatusItWasIn() {
        rows(acked(4, OTHER_DOCTOR), closed(5, ACTOR, ResponseAction.MOVED_ICU_HDU, PLAN));
        when(board.close(TENANT, ESC, Status.ACK, ACTOR, ResponseAction.MOVED_ICU_HDU, PLAN, 4)).thenReturn(true);

        ActionResult result = service.respond(TENANT, ACTOR, "DOCTOR", ESC,
                response(4, ResponseAction.MOVED_ICU_HDU, PLAN));

        assertThat(result.replayed()).isFalse();
        verify(board).close(TENANT, ESC, Status.ACK, ACTOR, ResponseAction.MOVED_ICU_HDU, PLAN, 4);
        verify(events).insert(TENANT, ADMISSION, "esc", "Escalation closed: Moved to ICU or HDU", ACTOR);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCloseAuditHoldsIdsScoreRiskActionAndStatusBeforeButNeverThePlanOrAName() {
        rows(acked(4, ACTOR), closed(5, ACTOR, ResponseAction.RAPID_RESPONSE, PLAN));
        when(board.close(any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(true);

        service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(4, ResponseAction.RAPID_RESPONSE, PLAN));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ESCALATION_CLOSED"), eq("IPD_EWS_ESCALATION"), eq(ESC), isNull(),
                after.capture());
        assertThat(after.getValue()).containsOnlyKeys("escalationId", "admissionId", "ewsScore", "ewsRisk", "action",
                "statusBefore");
        assertThat(after.getValue()).containsEntry("action", "RAPID_RESPONSE").containsEntry("statusBefore", "ACK");
        assertThat(after.getValue().toString()).doesNotContain(PLAN).doesNotContain(SECRET_NAME)
                .doesNotContain("Meera");
    }

    @Test
    void theEventHoldsTheActionLabelOnlyNeverThePlan() {
        rows(open(3, 0), closed(4, ACTOR, ResponseAction.NO_CHANGE, PLAN));
        when(board.close(any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(true);

        service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE, PLAN));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq("esc"), text.capture(), eq(ACTOR));
        assertThat(text.getValue()).isEqualTo("Escalation closed: Reviewed, no change needed")
                .doesNotContain("Oxygen").doesNotContain(SECRET_NAME);
    }

    @Test
    void aStaleVersionOnRespondIs409ChangedWithTheNewScoreOrTheOtherDoctorsName() {
        row(open(5, 0));
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-changed");
                    assertThat(e.getMessage()).isEqualTo("The score is now EWS 8 (high). Review it and try again.");
                });

        row(acked(5, OTHER_DOCTOR));
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-changed");
                    assertThat(e.getMessage()).isEqualTo("Dr Ravi Shah already acknowledged this escalation.");
                });

        row(acked(5, ACTOR));
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessage())
                        .startsWith("The score is now EWS 8"));
        verifyNothingWritten();
    }

    @Test
    void closingAnAlreadyClosedEscalationIs409StateUnlessItIsTheSameDoctorsRepeat() {
        row(closed(5, OTHER_DOCTOR, ResponseAction.NO_CHANGE, PLAN));
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(4, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-state");
                    assertThat(e.getMessage()).isEqualTo("This escalation is already closed.");
                });

        row(closed(5, ACTOR, ResponseAction.NO_CHANGE, PLAN));
        ActionResult replay = service.respond(TENANT, ACTOR, "DOCTOR", ESC,
                response(4, ResponseAction.NO_CHANGE, PLAN));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.escalation().response().note()).isEqualTo(PLAN);

        // the same doctor, another plan: not a replay, the record is never edited
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(4, ResponseAction.NO_CHANGE,
                "Another plan"))).isInstanceOf(ConflictException.class);
        verifyNothingWritten();
    }

    @Test
    void anInvalidResponseIs400WithEveryFieldErrorAndNothingIsLocked() {
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC,
                new ResponseInput(null, "CLOSED_AT_DISCHARGE", "")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field()).containsExactly("version", "action", "note"));

        verifyNoInteractions(admissions, team);
    }

    @Test
    void aFailedCloseUpdateStopsBeforeTheEventAndTheAudit() {
        row(open(3, 0));
        when(board.close(any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(3, ResponseAction.NO_CHANGE,
                PLAN))).isInstanceOf(IllegalStateException.class);

        verify(events, never()).insert(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    // ---- call senior ----

    @Test
    void aNurseCallRecordsOneCallTheEventWithTheWaitingMinutesAndTheAudit() {
        memberRole = "NURSE";
        Detail after = open(3, 1);
        rows(open(3, 0), after);
        when(board.callSenior(TENANT, ESC, 3, 0)).thenReturn(true);

        ActionResult result = service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0));

        assertThat(result.replayed()).isFalse();
        assertThat(result.escalation().seniorCalls()).isEqualTo(1);
        assertThat(result.escalation().version()).isEqualTo(3);
        assertThat(result.escalation().canCallSenior()).isTrue();
        assertThat(result.escalation().canAcknowledge()).isFalse();
        assertThat(result.escalation().canRespond()).isFalse();
        // raised 11:00:00, the database clock 11:20:10: 20 whole minutes, past the 15 minute target
        assertThat(result.escalation().pastTarget()).isTrue();
        verify(board).callSenior(TENANT, ESC, 3, 0);
        verify(events).insert(TENANT, ADMISSION, "esc", "Called the senior doctor: escalation not seen after 20 min",
                ACTOR);
        verify(seniorCalls).insertCalled(TENANT, ESC, ACTOR, 1);
        verify(seniorCalls, never()).insertAlreadyCalled(any(), any(), any());
        verify(auditLog).record(eq("IPD_ESCALATION_SENIOR_CALLED"), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSeniorCallAuditHoldsTheCountAfterTheCall() {
        memberRole = "NURSE";
        rows(open(3, 1), open(3, 2));
        when(board.callSenior(TENANT, ESC, 3, 1)).thenReturn(true);

        service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 1));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_ESCALATION_SENIOR_CALLED"), eq("IPD_EWS_ESCALATION"), eq(ESC), isNull(),
                after.capture());
        assertThat(after.getValue()).containsOnlyKeys("escalationId", "admissionId", "ewsScore", "ewsRisk",
                "seniorCalls");
        assertThat(after.getValue()).containsEntry("seniorCalls", 2);
        assertThat(after.getValue().toString()).doesNotContain(SECRET_NAME);
    }

    @Test
    void aSeniorCallWritesTheBoardUpdateThenTheEntryThenTheEventThenTheAudit() {
        memberRole = "NURSE";
        rows(open(3, 4), open(3, 5));
        when(board.callSenior(TENANT, ESC, 3, 4)).thenReturn(true);

        service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 4));

        InOrder order = inOrder(board, seniorCalls, events, auditLog);
        order.verify(seniorCalls).hasEnteredWithin(TENANT, ESC, ACTOR, 60);
        order.verify(board).callSenior(TENANT, ESC, 3, 4);
        order.verify(seniorCalls).insertCalled(TENANT, ESC, ACTOR, 5);
        order.verify(events).insert(eq(TENANT), eq(ADMISSION), eq("esc"), any(), eq(ACTOR));
        order.verify(auditLog).record(eq("IPD_ESCALATION_SENIOR_CALLED"), any(), any(), any(), any());
    }

    @Test
    void aStoredCountLowerThanTheSentOneIs409EscalationChangedAndNothingIsWritten() {
        memberRole = "NURSE";
        row(open(3, 1));

        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 5)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-changed");
                    assertThat(e.getMessage()).isEqualTo("The score is now EWS 8 (high). Review it and try again.");
                });

        verifyNothingWritten();
    }

    @Test
    void aCallersOwnCallAboveTheSentCountIsAReplayThatWritesNothing() {
        memberRole = "NURSE";
        row(open(3, 1));
        when(seniorCalls.hasCalledAbove(TENANT, ESC, ACTOR, 0)).thenReturn(true);

        ActionResult result = service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0));

        assertThat(result.replayed()).isTrue();
        assertThat(result.escalation().seniorCalls()).isEqualTo(1);
        verifyNothingWritten();
    }

    @Test
    void anotherNursesCallWritesOneAlreadyCalledEntryAndTheFixedEventButNoAuditAndNoCount() {
        memberRole = "NURSE";
        row(open(3, 1));

        ActionResult result = service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0));

        assertThat(result.replayed()).isTrue();
        assertThat(result.escalation().seniorCalls()).isEqualTo(1);
        assertThat(result.escalation().version()).isEqualTo(3);
        verify(seniorCalls).insertAlreadyCalled(TENANT, ESC, ACTOR);
        verify(events).insert(TENANT, ADMISSION, "esc",
                "Senior doctor already called \u00b7 seen by another team member \u00b7 no new call recorded",
                ACTOR);
        verify(seniorCalls, never()).insertCalled(any(), any(), any(), anyInt());
        verify(board, never()).callSenior(any(), any(), anyLong(), anyInt());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void theAlreadyCalledEventHoldsNoNameAndNoRole() {
        memberRole = "NURSE";
        row(open(3, 2));

        service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 1));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq("esc"), text.capture(), eq(ACTOR));
        assertThat(text.getValue()).doesNotContain(SECRET_NAME).doesNotContain("Anjali").doesNotContain("NURSE")
                .doesNotContain("Meera");
    }

    @Test
    void aTapInsideTheSixtySecondThrottleIsAReplayThatWritesNothingWhateverTheCount() {
        memberRole = "NURSE";
        when(seniorCalls.hasEnteredWithin(TENANT, ESC, ACTOR, 60)).thenReturn(true);
        row(open(3, 1));

        assertThat(service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 1)).replayed()).isTrue();
        assertThat(service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)).replayed()).isTrue();

        verifyNothingWritten();
    }

    @Test
    void theCapAnswers409EscalationStateWithTheContractTextAndWritesNothing() {
        memberRole = "NURSE";
        row(open(3, 32_767));

        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 32_767)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-state");
                    assertThat(e.getMessage()).isEqualTo(
                            "This escalation cannot record more senior calls. Phone the senior doctor now.");
                });

        verifyNothingWritten();
    }

    @Test
    void theThrottleIsCheckedBeforeTheCap() {
        memberRole = "NURSE";
        row(open(3, 32_767));
        when(seniorCalls.hasEnteredWithin(TENANT, ESC, ACTOR, 60)).thenReturn(true);

        assertThat(service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 32_767)).replayed()).isTrue();
        verifyNothingWritten();
    }

    @Test
    void aCallOnAnAcknowledgedOrClosedOrChangedEscalationIs409AndNothingIsWritten() {
        memberRole = "NURSE";
        row(acked(4, OTHER_DOCTOR));
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-state");
                    assertThat(e.getMessage()).isEqualTo("Dr Ravi Shah already acknowledged this escalation.");
                });

        row(closed(5, OTHER_DOCTOR, ResponseAction.NO_CHANGE, PLAN));
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("escalation-state");
                    assertThat(e.getMessage()).isEqualTo("This escalation is already closed.");
                });

        row(open(4, 0));
        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("escalation-changed"));
        verifyNothingWritten();
        verifyNoInteractions(seniorCalls);
    }

    @Test
    void aFailedCallUpdateStopsBeforeTheEventAndTheAudit() {
        memberRole = "NURSE";
        row(open(3, 0));
        when(board.callSenior(TENANT, ESC, 3, 0)).thenReturn(false);

        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(IllegalStateException.class);

        verify(events, never()).insert(any(), any(), any(), any(), any());
        verify(seniorCalls, never()).insertCalled(any(), any(), any(), anyInt());
    }

    // ---- privacy ----

    @Test
    void noMessageOfAProblemEverCarriesAPatientNameOrThePlan() {
        row(closed(5, OTHER_DOCTOR, ResponseAction.NO_CHANGE, PLAN));
        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC, response(4, ResponseAction.NO_CHANGE,
                PLAN))).satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_NAME).doesNotContain("Oxygen"));
        row(acked(5, OTHER_DOCTOR));
        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_NAME).doesNotContain("9876543210"));
    }

    @Test
    void theResultAndItsRowsHideNamesAndThePlanFromToString() {
        rows(open(3, 0), closed(4, ACTOR, ResponseAction.NO_CHANGE, PLAN));
        when(board.close(any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(true);

        ActionResult result = service.respond(TENANT, ACTOR, "DOCTOR", ESC,
                response(3, ResponseAction.NO_CHANGE, PLAN));

        assertThat(result.escalation().toString()).doesNotContain(SECRET_NAME).doesNotContain("Meera");
        assertThat(result.escalation().response().toString()).doesNotContain(PLAN).doesNotContain("Meera");
        assertThat(response(3, ResponseAction.NO_CHANGE, PLAN).toString()).doesNotContain("Oxygen");
    }

    // ---- transactions ----

    @Test
    void everyActionRunsInAnExplicitReadCommittedWriteTransaction() throws NoSuchMethodException {
        java.lang.reflect.Method[] methods = {
            EscalationService.class.getMethod("acknowledge", UUID.class, UUID.class, String.class, UUID.class,
                    VersionInput.class),
            EscalationService.class.getMethod("respond", UUID.class, UUID.class, String.class, UUID.class,
                    ResponseInput.class),
            EscalationService.class.getMethod("callSenior", UUID.class, UUID.class, String.class, UUID.class,
                    SeniorCallInput.class)};
        for (java.lang.reflect.Method method : methods) {
            org.springframework.transaction.annotation.Transactional tx = method
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.isolation()).as(method.getName())
                    .isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
            assertThat(tx.readOnly()).as(method.getName()).isFalse();
        }
    }

    // ---- SAH-34 patient scope ----

    @Test
    void acknowledgeOutsideScopeWritesNothing() {
        row(open(3, 0));
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("DOCTOR"),
                eq("DOCTOR"), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(OutsideScopeException.class);

        verify(board, never()).acknowledge(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verifyNothingWritten();
    }

    @Test
    void respondOutsideScopeWritesNothing() {
        row(open(3, 0));
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("DOCTOR"),
                eq("DOCTOR"), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.respond(TENANT, ACTOR, "DOCTOR", ESC,
                response(3, ResponseAction.NO_CHANGE, PLAN))).isInstanceOf(OutsideScopeException.class);

        verifyNothingWritten();
    }

    @Test
    void callSeniorOutsideScopeWritesNothing() {
        memberRole = "NURSE";
        row(open(3, 0));
        org.mockito.Mockito.doThrow(new OutsideScopeException()).when(guard).requireAdmissionLocked(eq(TENANT), eq(ACTOR), eq("NURSE"),
                eq("NURSE"), eq(ADMISSION), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.callSenior(TENANT, ACTOR, "NURSE", ESC, call(3, 0)))
                .isInstanceOf(OutsideScopeException.class);

        verify(board, never()).callSenior(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(seniorCalls);
        verifyNothingWritten();
    }

    @Test
    void anEscalationOfAnotherClinicIsNotFoundAndNeverReachesTheGuard() {
        when(board.findById(TENANT, ESC)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(TENANT, ACTOR, "DOCTOR", ESC, version(3)))
                .isInstanceOf(NotFoundException.class);

        verify(guard, never()).requireAdmissionLocked(any(), any(), any(), any(), any(), any());
    }
}
