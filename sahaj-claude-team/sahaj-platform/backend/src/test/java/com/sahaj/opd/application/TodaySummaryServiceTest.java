package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.VisitRepository.VisitRow;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-15 Today dashboard figures. Uses the real VisitService so "my tasks" is the Queue's own rule. */
class TodaySummaryServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_A = UUID.randomUUID();
    private static final UUID DOCTOR_B = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    /** 15:30 IST on 4 Nov 2026. */
    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private VisitRepository visitRepository;
    private WorkflowService workflowService;
    private FollowUpService followUpService;
    private PatientLookupPort patientLookup;
    private TeamMemberPort teamMemberPort;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        visitRepository = mock(VisitRepository.class);
        workflowService = mock(WorkflowService.class);
        followUpService = mock(FollowUpService.class);
        patientLookup = mock(PatientLookupPort.class);
        teamMemberPort = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);

        when(patientLookup.find(any(), any())).thenReturn(
                Optional.of(new PatientSummary(PATIENT_ID, "Asha Rao", 30, "F", "9000000001")));
        when(teamMemberPort.find(TENANT_ID, DOCTOR_A))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_A, "Dr Rao", "DOCTOR", true, 50000)));
        when(teamMemberPort.find(TENANT_ID, DOCTOR_B))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_B, "Dr Iyer", "DOCTOR", true, 50000)));
        when(teamMemberPort.findActiveDoctors(TENANT_ID)).thenReturn(List.of(
                new TeamMember(DOCTOR_A, "Dr Rao", "DOCTOR", true, 50000),
                new TeamMember(DOCTOR_B, "Dr Iyer", "DOCTOR", true, 50000)));
        // Every visit is read under the workflow version it was issued under (version 1 in these fixtures).
        when(workflowService.stepsForVersion(TENANT_ID, 1)).thenReturn(List.of(
                new WorkflowStep("vitals", true, "NURSE"), new WorkflowStep("counsel", false, "COUNSELLOR")));
        when(followUpService.counts(TENANT_ID)).thenReturn(new FollowUpView.Counts(2, 3, 4, 99));
        when(followUpService.returnedToday(TENANT_ID)).thenReturn(5);
        when(visitRepository.findSince(any(), any())).thenReturn(List.of());
    }

    private TodaySummaryService serviceAt(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        VisitService visitService = new VisitService(visitRepository, workflowService,
                mock(FeeSettingsRepository.class), patientLookup, teamMemberPort, mock(OwnerGuard.class), auditLog,
                followUpService, clock);
        return new TodaySummaryService(visitRepository, visitService, followUpService, patientLookup,
                teamMemberPort, clock);
    }

    private static VisitRow visit(int token, UUID doctor, String type, String stage, int minutesAgo) {
        return new VisitRow(UUID.randomUUID(), PATIENT_ID, doctor, type, stage, token, 50000,
                NOW.minusSeconds(minutesAgo * 60L), 0);
    }

    private void visits(VisitRow... rows) {
        when(visitRepository.findSince(any(), any())).thenReturn(List.of(rows));
    }

    @Test
    void anEmptyDayIsAllZerosWithEveryDoctorListedAsFree() {
        TodaySummaryView summary = serviceAt(NOW).summary(TENANT_ID, "OWNER");

        assertThat(summary.patientsTotal()).isZero();
        assertThat(summary.longestWaitMinutes()).isZero();
        assertThat(summary.myTasks()).isEmpty();
        assertThat(summary.doctors()).hasSize(2);
        assertThat(summary.doctors()).allSatisfy(d -> {
            assertThat(d.nowWithDoctor()).isNull();
            assertThat(d.nextTokens()).isEmpty();
        });
    }

    @Test
    void splitsNewAndFollowUpAndCountsWaitingAndInOpd() {
        visits(visit(1, DOCTOR_A, "NEW", "WAITING", 10), visit(2, DOCTOR_A, "FOLLOW_UP", "VITALS", 5),
                visit(3, DOCTOR_B, "NEW", "WITH_DOCTOR", 30), visit(4, DOCTOR_B, "FOLLOW_UP", "BILLING", 40),
                visit(5, DOCTOR_B, "NEW", "DONE", 90));

        TodaySummaryView summary = serviceAt(NOW).summary(TENANT_ID, "OWNER");

        assertThat(summary.patientsTotal()).isEqualTo(5);
        assertThat(summary.newCount()).isEqualTo(3);
        assertThat(summary.followUpCount()).isEqualTo(2);
        assertThat(summary.waitingCount()).isEqualTo(2);
        assertThat(summary.inOpdCount()).isEqualTo(4);
    }

    @Test
    void longestWaitIsTheOldestWaitingOrVitalsVisitOnly() {
        visits(visit(1, DOCTOR_A, "NEW", "WAITING", 12), visit(2, DOCTOR_A, "NEW", "VITALS", 25),
                visit(3, DOCTOR_B, "NEW", "WITH_DOCTOR", 120));

        assertThat(serviceAt(NOW).summary(TENANT_ID, "OWNER").longestWaitMinutes()).isEqualTo(25);
    }

    @Test
    void followUpFiguresComeFromTheFollowUpCounts() {
        TodaySummaryView summary = serviceAt(NOW).summary(TENANT_ID, "OWNER");

        assertThat(summary.followUpsDue()).isEqualTo(2);
        assertThat(summary.followUpsMissed()).isEqualTo(3);
        assertThat(summary.followUpsReturned()).isEqualTo(5);
    }

    @Test
    void aDoctorWithNobodyIsFreeAndOneInConsultShowsTheOldestInConsult() {
        visits(visit(1, DOCTOR_A, "NEW", "WITH_DOCTOR", 40), visit(2, DOCTOR_A, "FOLLOW_UP", "WITH_DOCTOR", 20),
                visit(3, DOCTOR_A, "NEW", "DONE", 100));

        List<TodaySummaryView.Doctor> doctors = serviceAt(NOW).summary(TENANT_ID, "OWNER").doctors();

        TodaySummaryView.Doctor rao = doctors.get(0);
        assertThat(rao.doctorName()).isEqualTo("Dr Rao");
        assertThat(rao.nowWithDoctor().tokenNo()).isEqualTo(1);
        assertThat(rao.nowWithDoctor().patientName()).isEqualTo("Asha Rao");
        assertThat(rao.nowWithDoctor().age()).isEqualTo(30);
        assertThat(rao.nowWithDoctor().sex()).isEqualTo("F");
        assertThat(rao.nowWithDoctor().visitType()).isEqualTo("NEW");
        assertThat(doctors.get(1).nowWithDoctor()).isNull();
        assertThat(doctors.get(1).nextTokens()).isEmpty();
    }

    @Test
    void nextTokensAreWaitingAndVitalsForThatDoctorOldestFirst() {
        visits(visit(9, DOCTOR_A, "NEW", "WAITING", 5), visit(4, DOCTOR_A, "NEW", "VITALS", 30),
                visit(6, DOCTOR_A, "NEW", "WAITING", 15), visit(7, DOCTOR_B, "NEW", "WAITING", 8),
                visit(2, DOCTOR_A, "NEW", "BILLING", 50));

        List<TodaySummaryView.Doctor> doctors = serviceAt(NOW).summary(TENANT_ID, "OWNER").doctors();

        assertThat(doctors.get(0).nextTokens()).containsExactly(4, 6, 9);
        assertThat(doctors.get(1).nextTokens()).containsExactly(7);
    }

    @Test
    void myTasksForANurseAreVitalsOnlyWithWaitMinutes() {
        visits(visit(1, DOCTOR_A, "NEW", "WAITING", 5), visit(2, DOCTOR_A, "NEW", "VITALS", 18),
                visit(3, DOCTOR_A, "NEW", "WITH_DOCTOR", 30));

        List<TodaySummaryView.Task> tasks = serviceAt(NOW).summary(TENANT_ID, "NURSE").myTasks();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).tokenNo()).isEqualTo(2);
        assertThat(tasks.get(0).stage()).isEqualTo("VITALS");
        assertThat(tasks.get(0).doctorName()).isEqualTo("Dr Rao");
        assertThat(tasks.get(0).patientName()).isEqualTo("Asha Rao");
        assertThat(tasks.get(0).waitMinutes()).isEqualTo(18);
    }

    @Test
    void myTasksForADoctorAreTheConsultationsAndOtherRolesGetNone() {
        visits(visit(1, DOCTOR_A, "NEW", "VITALS", 5), visit(2, DOCTOR_A, "NEW", "WITH_DOCTOR", 30));

        assertThat(serviceAt(NOW).summary(TENANT_ID, "DOCTOR").myTasks())
                .extracting(TodaySummaryView.Task::tokenNo).containsExactly(2);
        assertThat(serviceAt(NOW).summary(TENANT_ID, "PHARMACIST").myTasks()).isEmpty();
        assertThat(serviceAt(NOW).summary(TENANT_ID, "BILLING").myTasks()).isEmpty();
    }

    @Test
    void ownerAndFrontDeskSeeEveryOpenVisitButNeverFinishedOnes() {
        visits(visit(1, DOCTOR_A, "NEW", "WAITING", 5), visit(2, DOCTOR_A, "NEW", "BILLING", 30),
                visit(3, DOCTOR_A, "NEW", "DONE", 60));

        assertThat(serviceAt(NOW).summary(TENANT_ID, "FRONT_DESK").myTasks()).hasSize(2);
        assertThat(serviceAt(NOW).summary(TENANT_ID, "OWNER").myTasks()).hasSize(2);
    }

    @Test
    void todayStartsAtMidnightIstNotUtc() {
        // 00:10 IST on 4 Nov is 18:40 UTC on 3 Nov: the day began at 18:30 UTC on 3 Nov.
        serviceAt(Instant.parse("2026-11-03T18:40:00Z")).summary(TENANT_ID, "OWNER");
        verify(visitRepository, atLeastOnce()).findSince(TENANT_ID, Instant.parse("2026-11-03T18:30:00Z"));

        // 23:50 IST on 3 Nov is 18:20 UTC on 3 Nov: still the 3 Nov IST day, which began 18:30 UTC on 2 Nov.
        serviceAt(Instant.parse("2026-11-03T18:20:00Z")).summary(TENANT_ID, "OWNER");
        verify(visitRepository, atLeastOnce()).findSince(TENANT_ID, Instant.parse("2026-11-02T18:30:00Z"));
    }

    @Test
    void readingTheSummaryWritesNoAuditRows() {
        visits(visit(1, DOCTOR_A, "NEW", "WAITING", 5));

        serviceAt(NOW).summary(TENANT_ID, "OWNER");

        verifyNoInteractions(auditLog);
    }
}
