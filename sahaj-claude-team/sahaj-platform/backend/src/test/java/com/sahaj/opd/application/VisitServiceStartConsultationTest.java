package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** SAH-46-2 (ADR-020 D10): opening the EMR moves a Waiting or Vitals visit to With doctor and is safe to repeat. */
class VisitServiceStartConsultationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private final VisitRepository visits = mock(VisitRepository.class);
    private final WorkflowService workflow = mock(WorkflowService.class);
    private final AuditLog audit = mock(AuditLog.class);
    private VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitService(visits, workflow, mock(FeeSettingsRepository.class), mock(PatientLookupPort.class),
                mock(TeamMemberPort.class), mock(OwnerGuard.class), audit, mock(FollowUpService.class), CLOCK);
    }

    private void visitAt(String stage, boolean vitalsOn) {
        when(visits.findById(TENANT, VISIT)).thenReturn(Optional.of(new VisitRow(VISIT, UUID.randomUUID(),
                UUID.randomUUID(), "NEW", stage, 1, 50000, CLOCK.instant(), 0, 3)));
        when(workflow.stepsForVersion(TENANT, 3)).thenReturn(List.of(new WorkflowStep("vitals", vitalsOn, "NURSE"),
                new WorkflowStep("consult", true, "DOCTOR"), new WorkflowStep("billing", true, "FRONT_DESK")));
    }

    @Test
    void aWaitingVisitWithVitalsOnMovesThroughVitalsToWithDoctorOneAuditedStepAtATime() {
        visitAt("WAITING", true);
        when(visits.moveStageIfCurrent(any(), any(), any(), any())).thenReturn(true);

        assertThat(service.startConsultation(TENANT, USER, VISIT)).isEqualTo("MOVED");

        InOrder order = inOrder(visits, audit);
        order.verify(visits).moveStageIfCurrent(VISIT, "WAITING", "VITALS", USER);
        order.verify(audit).record("VISIT_STAGE_MOVED", "VISIT", VISIT, Map.of("stage", "WAITING"),
                Map.of("stage", "VITALS"));
        order.verify(visits).moveStageIfCurrent(VISIT, "VITALS", "WITH_DOCTOR", USER);
        order.verify(audit).record("VISIT_STAGE_MOVED", "VISIT", VISIT, Map.of("stage", "VITALS"),
                Map.of("stage", "WITH_DOCTOR"));
    }

    @Test
    void aClinicWithVitalsOffMovesStraightToWithDoctor() {
        visitAt("WAITING", false);
        when(visits.moveStageIfCurrent(any(), any(), any(), any())).thenReturn(true);

        assertThat(service.startConsultation(TENANT, USER, VISIT)).isEqualTo("MOVED");

        verify(visits).moveStageIfCurrent(VISIT, "WAITING", "WITH_DOCTOR", USER);
    }

    @Test
    void aVisitAlreadyWithTheDoctorChangesNothing() {
        visitAt("WITH_DOCTOR", true);

        assertThat(service.startConsultation(TENANT, USER, VISIT)).isEqualTo("ALREADY_WITH_DOCTOR");

        verify(visits, never()).moveStageIfCurrent(any(), any(), any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aLaterStageIsNotApplicable() {
        visitAt("BILLING", true);

        assertThat(service.startConsultation(TENANT, USER, VISIT)).isEqualTo("NOT_APPLICABLE");
        verify(visits, never()).moveStageIfCurrent(any(), any(), any(), any());
    }

    @Test
    void ifSomeoneMovedItFirstTheOpenStillWorksAndSaysTheQueueWasAlreadyUpdated() {
        visitAt("WAITING", true);
        when(visits.moveStageIfCurrent(VISIT, "WAITING", "VITALS", USER)).thenReturn(false);

        assertThat(service.startConsultation(TENANT, USER, VISIT)).isEqualTo("ALREADY_UPDATED");

        verify(audit, never()).record(any(), any(), any(), any(), any());
    }
}
