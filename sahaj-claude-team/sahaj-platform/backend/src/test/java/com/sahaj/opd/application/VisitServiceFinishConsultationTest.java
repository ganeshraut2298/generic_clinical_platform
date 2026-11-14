package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService.FinishMove;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
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

/**
 * SAH-51-2 and SAH-68-2 (ADR-025 D6, D14): the doctor's sign moves the visit through its own workflow version, the fee of a
 * changed visit type follows the SAH-9 rule inside the follow-up window only, and a Send-to-OPD token needs an OPD doctor.
 */
class VisitServiceFinishConsultationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private final VisitRepository visits = mock(VisitRepository.class);
    private final WorkflowService workflow = mock(WorkflowService.class);
    private final FeeSettingsRepository fees = mock(FeeSettingsRepository.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final FollowUpService followUps = mock(FollowUpService.class);
    private VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitService(visits, workflow, fees, patients, team, mock(OwnerGuard.class), audit, followUps, CLOCK);
    }

    private void visitAt(String stage, boolean counselOn, boolean followUpOn) {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, DOCTOR, "NEW", stage, 1,
                50000, CLOCK.instant(), 0, 3)));
        when(workflow.stepsForVersion(TENANT, 3)).thenReturn(List.of(new WorkflowStep("vitals", true, "NURSE"),
                new WorkflowStep("consult", true, "DOCTOR"), new WorkflowStep("counsel", counselOn, "COUNSELLOR"),
                new WorkflowStep("followup", followUpOn, "FRONT_DESK"), new WorkflowStep("billing", true, "FRONT_DESK")));
    }

    @Test
    void aVisitWithTheDoctorMovesOneStepToBillingAndRunsTheFollowUpStepWhenOn() {
        visitAt("WITH_DOCTOR", false, true);
        when(visits.moveStageIfCurrent(any(), any(), any(), any())).thenReturn(true);

        FinishMove move = service.finishConsultation(TENANT, USER, VISIT);

        assertThat(move.outcome()).isEqualTo("MOVED");
        assertThat(move.to()).isEqualTo("BILLING");
        verify(visits).moveStageIfCurrent(VISIT, "WITH_DOCTOR", "BILLING", USER);
        verify(followUps).createForConsultDone(any(), any(), any());
    }

    @Test
    void aClinicWithCounsellingOnMovesToCounselAndASkippedFollowUpStepCreatesNothing() {
        visitAt("WITH_DOCTOR", true, false);
        when(visits.moveStageIfCurrent(any(), any(), any(), any())).thenReturn(true);

        assertThat(service.finishConsultation(TENANT, USER, VISIT).to()).isEqualTo("COUNSEL");
        verify(followUps, never()).createForConsultDone(any(), any(), any());
    }

    @Test
    void aVisitStillWaitingWalksToWithDoctorThenOneStepForward() {
        visitAt("WAITING", false, false);
        when(visits.moveStageIfCurrent(any(), any(), any(), any())).thenReturn(true);

        assertThat(service.finishConsultation(TENANT, USER, VISIT).outcome()).isEqualTo("MOVED");

        verify(visits).moveStageIfCurrent(VISIT, "WAITING", "VITALS", USER);
        verify(visits).moveStageIfCurrent(VISIT, "VITALS", "WITH_DOCTOR", USER);
        verify(visits).moveStageIfCurrent(VISIT, "WITH_DOCTOR", "BILLING", USER);
    }

    @Test
    void aCardAlreadyPastWithDoctorIsReportedAndNotMovedAgain() {
        visitAt("BILLING", false, false);

        FinishMove move = service.finishConsultation(TENANT, USER, VISIT);

        assertThat(move.outcome()).isEqualTo("ALREADY_MOVED");
        verify(visits, never()).moveStageIfCurrent(any(), any(), any(), any());
    }

    @Test
    void aMoveThatSomeoneElseMadeFirstRefusesTheSignWithWorkflowMoveRefused() {
        visitAt("WITH_DOCTOR", false, false);
        when(visits.moveStageIfCurrent(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.finishConsultation(TENANT, USER, VISIT)).isInstanceOf(EmrProblemException.class)
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("workflow-move-refused");
    }

    @Test
    void theNextStageIsPreviewedWithoutMovingAnything() {
        visitAt("WITH_DOCTOR", false, false);

        assertThat(service.nextStageAfterConsultation(TENANT, VISIT)).contains("BILLING");
        visitAt("DONE", false, false);
        assertThat(service.nextStageAfterConsultation(TENANT, VISIT)).isEmpty();
    }

    // ---- fee of a changed visit type (H11) ----

    private void feeSetup(String followUpFee, int windowDays) {
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr A", "DOCTOR", true, 80000)));
        when(fees.find(TENANT)).thenReturn(Optional.of(new FeeSettingsRepository.FeeRow("onBill", windowDays, followUpFee, Map.of(), false)));
    }

    private VisitRow visitCreated(Instant at) {
        return new VisitRow(VISIT, PATIENT, DOCTOR, "NEW", "WITH_DOCTOR", 1, 80000, at, 0);
    }

    @Test
    void followUpGetsTheFollowUpFeeOnlyInsideTheWindowOfTheSameDoctor() {
        feeSetup("half", 14);
        VisitRow visit = visitCreated(CLOCK.instant());
        when(visits.lastVisitBefore(TENANT, PATIENT, DOCTOR, visit.createdAt()))
                .thenReturn(Optional.of(CLOCK.instant().minusSeconds(5L * 86400)));
        when(followUps.lastDischargeAt(TENANT, PATIENT, DOCTOR)).thenReturn(Optional.empty());

        assertThat(service.feeForType(TENANT, visit, "FOLLOW_UP")).isEqualTo(40000);
    }

    @Test
    void followUpOutsideTheWindowOrWithoutAnEarlierVisitPaysTheFullFee() {
        feeSetup("half", 14);
        VisitRow visit = visitCreated(CLOCK.instant());
        when(followUps.lastDischargeAt(TENANT, PATIENT, DOCTOR)).thenReturn(Optional.empty());
        when(visits.lastVisitBefore(TENANT, PATIENT, DOCTOR, visit.createdAt()))
                .thenReturn(Optional.of(CLOCK.instant().minusSeconds(40L * 86400)));
        assertThat(service.feeForType(TENANT, visit, "FOLLOW_UP")).isEqualTo(80000);

        when(visits.lastVisitBefore(TENANT, PATIENT, DOCTOR, visit.createdAt())).thenReturn(Optional.empty());
        assertThat(service.feeForType(TENANT, visit, "FOLLOW_UP")).isEqualTo(80000);
    }

    @Test
    void newRevisitAndEmergencyAlwaysPayTheDoctorsFullFee() {
        feeSetup("free", 14);
        VisitRow visit = visitCreated(CLOCK.instant());

        assertThat(service.feeForType(TENANT, visit, "NEW")).isEqualTo(80000);
        assertThat(service.feeForType(TENANT, visit, "REVISIT")).isEqualTo(80000);
        assertThat(service.feeForType(TENANT, visit, "EMERGENCY")).isEqualTo(80000);
    }

    // ---- Send to OPD token (SAH-67-2) ----

    @Test
    void aTokenForTheSigningDoctorIsIssuedWithTheWalkInLogic() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Pt", 30, "F", "9876543210", false)));
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr A", "OWNER", true, 80000)));
        when(fees.find(TENANT)).thenReturn(Optional.empty());
        when(workflow.current(TENANT, USER)).thenReturn(new WorkflowVersionView(3, List.of(
                new WorkflowStep("consult", true, "DOCTOR"), new WorkflowStep("billing", true, "FRONT_DESK")), null, null));
        when(visits.nextTokenNo()).thenReturn(7);
        when(visits.findById(any(), any())).thenReturn(Optional.of(new VisitRow(VISIT, PATIENT, DOCTOR, "NEW", "WAITING",
                7, 80000, CLOCK.instant(), 0, 3)));

        VisitView view = service.issueTokenForDoctor(TENANT, USER, PATIENT, DOCTOR);

        assertThat(view.tokenNo()).isEqualTo(7);
        verify(followUps).closeOnNewVisit(any(), any(), any(), any(), any());
    }

    @Test
    void aPatientOfAnotherClinicIs404PatientNotFound() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueTokenForDoctor(TENANT, USER, PATIENT, DOCTOR))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("patient-not-found");
                    assertThat(e.status()).isEqualTo(404);
                });
    }

    @Test
    void aSignerWhoIsNotAnActiveOpdDoctorIsRefused() {
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Pt", 30, "F", "9876543210", false)));
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Nurse", "NURSE", true, 0)));

        assertThatThrownBy(() -> service.issueTokenForDoctor(TENANT, USER, PATIENT, DOCTOR))
                .isInstanceOf(EmrProblemException.class).extracting(e -> ((EmrProblemException) e).slug())
                .isEqualTo("opd-doctor-not-available");
    }
}
