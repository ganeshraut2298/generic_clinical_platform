package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
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
import org.springframework.security.access.AccessDeniedException;

/** SAH-9 Queue and tokens: issue a token, advance a stage, backward move owner-only, my-tasks filter. */
class VisitServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private VisitRepository visitRepository;
    private WorkflowService workflowService;
    private FeeSettingsRepository feeSettingsRepository;
    private PatientLookupPort patientLookup;
    private TeamMemberPort teamMemberPort;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private VisitService service;

    @BeforeEach
    void setUp() {
        visitRepository = mock(VisitRepository.class);
        workflowService = mock(WorkflowService.class);
        feeSettingsRepository = mock(FeeSettingsRepository.class);
        patientLookup = mock(PatientLookupPort.class);
        teamMemberPort = mock(TeamMemberPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new VisitService(visitRepository, workflowService, feeSettingsRepository, patientLookup,
                teamMemberPort, ownerGuard, auditLog, CLOCK);

        when(feeSettingsRepository.find(TENANT_ID)).thenReturn(Optional.empty());
        when(patientLookup.find(TENANT_ID, PATIENT_ID))
                .thenReturn(Optional.of(new PatientSummary(PATIENT_ID, "Asha Rao", 30, "F")));
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
        when(visitRepository.nextTokenNo()).thenReturn(1);
        stubVitals(true);
        when(visitRepository.lastVisitAt(eq(TENANT_ID), any(), any())).thenReturn(Optional.empty());
    }

    private VisitRow rowAfterInsert(UUID id, String visitType, String stage, long feePaise) {
        return new VisitRow(id, PATIENT_ID, DOCTOR_ID, visitType, stage, 1, feePaise, CLOCK.instant(), 0);
    }

    @Test
    void aNonCreateRoleIs403() {
        assertThatThrownBy(() -> service.issueToken(TENANT_ID, USER_ID, "DOCTOR",
                new VisitCreateInput(PATIENT_ID, DOCTOR_ID))).isInstanceOf(AccessDeniedException.class);
        verify(visitRepository, never()).insert(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void anUnknownPatientIs404() {
        when(patientLookup.find(TENANT_ID, PATIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueToken(TENANT_ID, USER_ID, "FRONT_DESK",
                new VisitCreateInput(PATIENT_ID, DOCTOR_ID))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aMembershipThatIsNotAnActiveDoctorIs404() {
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Priya", "NURSE", true, 0)));

        assertThatThrownBy(() -> service.issueToken(TENANT_ID, USER_ID, "OWNER",
                new VisitCreateInput(PATIENT_ID, DOCTOR_ID))).isInstanceOf(NotFoundException.class);
    }

    /** {@code findById} echoes back whatever id the service asks for, as the real database would, after an insert. */
    private void stubFindByIdEchoingTheRequestedId(String visitType, String stage, long feePaise) {
        when(visitRepository.findById(eq(TENANT_ID), any())).thenAnswer(invocation -> {
            UUID calledId = invocation.getArgument(1);
            return Optional.of(rowAfterInsert(calledId, visitType, stage, feePaise));
        });
    }

    @Test
    void firstTimeVisitIsNewWithTheFullFeeAndWaitsIfVitalsIsOn() {
        stubVitals(true);
        stubFindByIdEchoingTheRequestedId("NEW", "VITALS", 50000);

        var visit = service.issueToken(TENANT_ID, USER_ID, "NURSE", new VisitCreateInput(PATIENT_ID, DOCTOR_ID));

        assertThat(visit.visitType()).isEqualTo("NEW");
        assertThat(visit.stage()).isEqualTo("VITALS");
        assertThat(visit.feePaise()).isEqualTo(50000);
        assertThat(visit.patientName()).isEqualTo("Asha Rao");
        assertThat(visit.doctorName()).isEqualTo("Dr Rao");
        verify(auditLog).record(eq("VISIT_CREATED"), eq("VISIT"), eq(visit.id()), eq(null), any());
    }

    @Test
    void whenVitalsIsOffTheFirstStageIsWithDoctor() {
        stubVitals(false);
        stubFindByIdEchoingTheRequestedId("NEW", "WITH_DOCTOR", 50000);

        var visit = service.issueToken(TENANT_ID, USER_ID, "OWNER", new VisitCreateInput(PATIENT_ID, DOCTOR_ID));

        assertThat(visit.stage()).isEqualTo("WITH_DOCTOR");
    }

    @Test
    void aVisitWithinTheFollowUpWindowIsFollowUpWithTheFollowUpFee() {
        stubVitals(false);
        when(feeSettingsRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new FeeRow("before", 7, "half", java.util.Map.of(), true)));
        when(visitRepository.lastVisitAt(TENANT_ID, PATIENT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(CLOCK.instant().minusSeconds(3600)));
        stubFindByIdEchoingTheRequestedId("FOLLOW_UP", "WITH_DOCTOR", 25000);

        var visit = service.issueToken(TENANT_ID, USER_ID, "OWNER", new VisitCreateInput(PATIENT_ID, DOCTOR_ID));

        assertThat(visit.visitType()).isEqualTo("FOLLOW_UP");
        assertThat(visit.feePaise()).isEqualTo(25000);
    }

    @Test
    void aVisitPastTheFollowUpWindowIsNewAgain() {
        stubVitals(false);
        when(feeSettingsRepository.find(TENANT_ID))
                .thenReturn(Optional.of(new FeeRow("before", 7, "free", java.util.Map.of(), true)));
        when(visitRepository.lastVisitAt(TENANT_ID, PATIENT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(CLOCK.instant().minus(java.time.Duration.ofDays(30))));
        stubFindByIdEchoingTheRequestedId("NEW", "WITH_DOCTOR", 50000);

        var visit = service.issueToken(TENANT_ID, USER_ID, "OWNER", new VisitCreateInput(PATIENT_ID, DOCTOR_ID));

        assertThat(visit.visitType()).isEqualTo("NEW");
        assertThat(visit.feePaise()).isEqualTo(50000);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theAuditSnapshotNeverCarriesAPatientOrDoctorName() {
        stubVitals(false);
        stubFindByIdEchoingTheRequestedId("NEW", "WITH_DOCTOR", 50000);

        var visit = service.issueToken(TENANT_ID, USER_ID, "OWNER", new VisitCreateInput(PATIENT_ID, DOCTOR_ID));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("VISIT_CREATED"), eq("VISIT"), eq(visit.id()), eq(null), captor.capture());
        Map<String, Object> snapshot = captor.getValue();
        assertThat(snapshot).doesNotContainKey("name").doesNotContainKey("patientName")
                .doesNotContainKey("doctorName").containsEntry("patientId", PATIENT_ID);
    }

    // --- Stage moves ---

    private VisitRow visitAt(String stage) {
        return new VisitRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, "NEW", stage, 1, 50000, CLOCK.instant(), 0);
    }

    @Test
    void nurseMovesForwardOnlyFromVitals() {
        stubVitals(true);
        VisitRow visit = visitAt("VITALS");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit))
                .thenReturn(Optional.of(new VisitRow(visit.id(), PATIENT_ID, DOCTOR_ID, "NEW", "WITH_DOCTOR", 1,
                        50000, CLOCK.instant(), 1)));

        var moved = service.moveStage(TENANT_ID, USER_ID, "NURSE", visit.id(), "WITH_DOCTOR");

        assertThat(moved.stage()).isEqualTo("WITH_DOCTOR");
        verify(visitRepository).updateStage(visit.id(), "WITH_DOCTOR", USER_ID);
    }

    @Test
    void nurseCannotMoveAWithDoctorCard() {
        VisitRow visit = visitAt("WITH_DOCTOR");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit));
        stubVitals(false);

        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "NURSE", visit.id(), "BILLING"))
                .isInstanceOf(AccessDeniedException.class);
        verify(visitRepository, never()).updateStage(any(), any(), any());
    }

    @Test
    void doctorMovesForwardOnlyFromWithDoctor() {
        VisitRow visit = visitAt("WITH_DOCTOR");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit))
                .thenReturn(Optional.of(new VisitRow(visit.id(), PATIENT_ID, DOCTOR_ID, "NEW", "BILLING", 1, 50000,
                        CLOCK.instant(), 1)));
        stubVitals(false);

        var moved = service.moveStage(TENANT_ID, USER_ID, "VISITING_DOCTOR", visit.id(), "BILLING");

        assertThat(moved.stage()).isEqualTo("BILLING");
    }

    @Test
    void ownerAndFrontDeskMayMoveAnyCardForward() {
        VisitRow visit = visitAt("WAITING");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit))
                .thenReturn(Optional.of(new VisitRow(visit.id(), PATIENT_ID, DOCTOR_ID, "NEW", "WITH_DOCTOR", 1,
                        50000, CLOCK.instant(), 1)));
        stubVitals(false);

        var moved = service.moveStage(TENANT_ID, USER_ID, "FRONT_DESK", visit.id(), "WITH_DOCTOR");

        assertThat(moved.stage()).isEqualTo("WITH_DOCTOR");
    }

    @Test
    void movingMoreThanOneStageAtOnceIs400() {
        VisitRow visit = visitAt("WAITING");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit));
        stubVitals(false);

        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "OWNER", visit.id(), "BILLING"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void anUnknownStageValueIs400() {
        VisitRow visit = visitAt("WAITING");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit));

        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "OWNER", visit.id(), "NOT_A_STAGE"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void movingBackwardRequiresTheOwnerCheckedAgainstTheDatabase() {
        VisitRow visit = visitAt("WITH_DOCTOR");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit))
                .thenReturn(Optional.of(new VisitRow(visit.id(), PATIENT_ID, DOCTOR_ID, "NEW", "WAITING", 1, 50000,
                        CLOCK.instant(), 1)));
        stubVitals(false);

        var moved = service.moveStage(TENANT_ID, USER_ID, "OWNER", visit.id(), "WAITING");

        assertThat(moved.stage()).isEqualTo("WAITING");
        verify(ownerGuard).requireOwner(TENANT_ID, USER_ID);
    }

    @Test
    void aFrontDeskCannotMoveACardBackward() {
        VisitRow visit = visitAt("WITH_DOCTOR");
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit));
        stubVitals(false);
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, USER_ID);

        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "FRONT_DESK", visit.id(), "WAITING"))
                .isInstanceOf(AccessDeniedException.class);
        verify(visitRepository, never()).updateStage(any(), any(), any());
    }

    @Test
    void aVisitFromAnotherTenantIs404() {
        UUID id = UUID.randomUUID();
        when(visitRepository.findById(TENANT_ID, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "OWNER", id, "WAITING"))
                .isInstanceOf(NotFoundException.class);
    }


    /** Stubs the clinic's current workflow (version 1) with the defaults, vitals switched as asked. */
    private void stubVitals(boolean vitalsOn) {
        stubWorkflow(1, WorkflowRules.defaults(vitalsOn));
    }

    private void stubWorkflow(int versionNo, List<WorkflowStep> steps) {
        when(workflowService.current(eq(TENANT_ID), any()))
                .thenReturn(new WorkflowVersionView(versionNo, steps, CLOCK.instant(), "Owner"));
        when(workflowService.currentSteps(TENANT_ID)).thenReturn(steps);
        when(workflowService.stepsForVersion(TENANT_ID, versionNo)).thenReturn(steps);
    }

    private static List<WorkflowStep> withCounsel(String counselOwner) {
        return List.of(new WorkflowStep("token", true, null), new WorkflowStep("queue", true, null),
                new WorkflowStep("vitals", true, "NURSE"), new WorkflowStep("consult", true, null),
                new WorkflowStep("counsel", true, counselOwner), new WorkflowStep("casepaper", true, null),
                new WorkflowStep("billing", true, null), new WorkflowStep("followup", true, null));
    }

    private VisitRow visitAtVersion(String stage, int versionNo) {
        return new VisitRow(UUID.randomUUID(), PATIENT_ID, DOCTOR_ID, "NEW", stage, 1, 50000, CLOCK.instant(), 0,
                versionNo);
    }

    @Test
    void aVisitIssuedBeforeAPublishKeepsItsOriginalVersionsStagesAfterThePublish() {
        // Visit was issued under v1 (counselling off). The clinic has since published v2 (counselling on).
        stubWorkflow(1, WorkflowRules.defaults(true));
        stubWorkflow(2, withCounsel("DOCTOR"));
        when(workflowService.current(eq(TENANT_ID), any()))
                .thenReturn(new WorkflowVersionView(2, withCounsel("DOCTOR"), CLOCK.instant(), "Owner"));
        VisitRow visit = visitAtVersion("WITH_DOCTOR", 1);
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit))
                .thenReturn(Optional.of(new VisitRow(visit.id(), PATIENT_ID, DOCTOR_ID, "NEW", "BILLING", 1, 50000,
                        CLOCK.instant(), 1, 1)));

        var moved = service.moveStage(TENANT_ID, USER_ID, "OWNER", visit.id(), "BILLING");

        assertThat(moved.stage()).isEqualTo("BILLING");
        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "OWNER", visit.id(), "COUNSEL"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aVisitIssuedAfterAPublishIsStampedWithTheNewVersionAndFollowsIt() {
        stubWorkflow(2, withCounsel("DOCTOR"));
        stubFindByIdEchoingTheRequestedId("NEW", "VITALS", 50000);

        service.issueToken(TENANT_ID, USER_ID, "OWNER", new VisitCreateInput(PATIENT_ID, DOCTOR_ID));

        verify(visitRepository).insert(any(), eq(PATIENT_ID), eq(DOCTOR_ID), eq("NEW"), eq("VITALS"), eq(1),
                eq(50000L), eq(2), eq(USER_ID));
    }

    @Test
    void whenCounsellingIsOnAVisitGoesFromWithDoctorToCounsellingBeforeBilling() {
        stubWorkflow(2, withCounsel("DOCTOR"));
        VisitRow visit = visitAtVersion("WITH_DOCTOR", 2);
        when(visitRepository.findById(TENANT_ID, visit.id())).thenReturn(Optional.of(visit))
                .thenReturn(Optional.of(new VisitRow(visit.id(), PATIENT_ID, DOCTOR_ID, "NEW", "COUNSEL", 1, 50000,
                        CLOCK.instant(), 1, 2)));

        var moved = service.moveStage(TENANT_ID, USER_ID, "DOCTOR", visit.id(), "COUNSEL");

        assertThat(moved.stage()).isEqualTo("COUNSEL");
        assertThat(VisitService.enabledStages(withCounsel("DOCTOR")))
                .containsExactly("WAITING", "VITALS", "WITH_DOCTOR", "COUNSEL", "BILLING", "DONE");
    }

    @Test
    void theCounsellingOwnerRoleDecidesWhoSeesAndMovesTheCounsellingCard() {
        stubWorkflow(2, withCounsel("NURSE"));
        VisitRow counsel = visitAtVersion("COUNSEL", 2);
        VisitRow withDoctor = visitAtVersion("WITH_DOCTOR", 2);
        when(visitRepository.findSince(eq(TENANT_ID), any())).thenReturn(List.of(counsel, withDoctor));
        when(visitRepository.findById(TENANT_ID, counsel.id())).thenReturn(Optional.of(counsel));

        List<VisitView> nurseMine = service.today(TENANT_ID, "NURSE", true);
        List<VisitView> doctorMine = service.today(TENANT_ID, "DOCTOR", true);

        assertThat(nurseMine).extracting(VisitView::stage).containsExactly("COUNSEL");
        assertThat(doctorMine).extracting(VisitView::stage).containsExactly("WITH_DOCTOR");
        assertThatThrownBy(() -> service.moveStage(TENANT_ID, USER_ID, "DOCTOR", counsel.id(), "BILLING"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theVitalsOwnerRoleCanBeReassignedToTheDoctor() {
        List<WorkflowStep> steps = List.of(new WorkflowStep("token", true, null), new WorkflowStep("queue", true, null),
                new WorkflowStep("vitals", true, "DOCTOR"), new WorkflowStep("consult", true, null),
                new WorkflowStep("counsel", false, "DOCTOR"), new WorkflowStep("casepaper", true, null),
                new WorkflowStep("billing", true, null), new WorkflowStep("followup", true, null));
        stubWorkflow(2, steps);
        when(visitRepository.findSince(eq(TENANT_ID), any())).thenReturn(List.of(visitAtVersion("VITALS", 2)));

        assertThat(service.today(TENANT_ID, "DOCTOR", true)).hasSize(1);
        assertThat(service.today(TENANT_ID, "NURSE", true)).isEmpty();
    }

    // --- Today / my tasks ---

    @Test
    void todayFiltersToTheStagesTheRoleOwnsWhenMineIsTrue() {
        VisitRow waiting = visitAt("WAITING");
        VisitRow vitals = visitAt("VITALS");
        VisitRow withDoctor = visitAt("WITH_DOCTOR");
        when(visitRepository.findSince(eq(TENANT_ID), any())).thenReturn(List.of(waiting, vitals, withDoctor));

        List<VisitView> mineOnly = service.today(TENANT_ID, "NURSE", true);

        assertThat(mineOnly).hasSize(1);
        assertThat(mineOnly.get(0).stage()).isEqualTo("VITALS");
    }

    @Test
    void ownerSeesEveryStageEvenWhenMineIsTrue() {
        VisitRow waiting = visitAt("WAITING");
        VisitRow withDoctor = visitAt("WITH_DOCTOR");
        when(visitRepository.findSince(eq(TENANT_ID), any())).thenReturn(List.of(waiting, withDoctor));

        List<VisitView> mineOnly = service.today(TENANT_ID, "OWNER", true);

        assertThat(mineOnly).hasSize(2);
    }

    @Test
    void everyoneSeesAllStagesWhenMineIsFalse() {
        VisitRow waiting = visitAt("WAITING");
        VisitRow vitals = visitAt("VITALS");
        when(visitRepository.findSince(eq(TENANT_ID), any())).thenReturn(List.of(waiting, vitals));

        List<VisitView> all = service.today(TENANT_ID, "NURSE", false);

        assertThat(all).hasSize(2);
    }
}
