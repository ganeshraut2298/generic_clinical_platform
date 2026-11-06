package com.sahaj.opd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.opd.api.VisitBillingPort.VisitForBilling;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
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
import org.springframework.security.access.AccessDeniedException;

/** SAH-10: what {@code billing} reads and does through {@code opd.api.VisitBillingPort}. */
class VisitBillingAdapterTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID VISIT_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private VisitRepository visitRepository;
    private TeamMemberPort teamMemberPort;
    private AuditLog auditLog;
    private VisitBillingAdapter adapter;

    @BeforeEach
    void setUp() {
        visitRepository = mock(VisitRepository.class);
        teamMemberPort = mock(TeamMemberPort.class);
        auditLog = mock(AuditLog.class);
        adapter = new VisitBillingAdapter(visitRepository, teamMemberPort, auditLog, CLOCK);
        when(teamMemberPort.find(TENANT_ID, DOCTOR_ID))
                .thenReturn(Optional.of(new TeamMember(DOCTOR_ID, "Dr Rao", "DOCTOR", true, 50000)));
    }

    private VisitRow row(String stage) {
        return new VisitRow(VISIT_ID, PATIENT_ID, DOCTOR_ID, "NEW", stage, 7, 50000, CLOCK.instant(), 0);
    }

    @Test
    void findIncludesTheDoctorsNameFromTheTeamPort() {
        when(visitRepository.findById(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(row("BILLING")));

        Optional<VisitForBilling> found = adapter.find(TENANT_ID, VISIT_ID);

        assertThat(found).isPresent();
        assertThat(found.get().doctorName()).isEqualTo("Dr Rao");
        assertThat(found.get().feePaise()).isEqualTo(50000);
    }

    @Test
    void todayListsEveryVisitCreatedSinceMidnightIndia() {
        when(visitRepository.findSince(org.mockito.ArgumentMatchers.eq(TENANT_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(row("BILLING"), row("DONE")));

        List<VisitForBilling> today = adapter.today(TENANT_ID);

        assertThat(today).hasSize(2);
    }

    @Test
    void advanceToDoneMovesABillingVisitForward() {
        when(visitRepository.findById(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(row("BILLING")));

        adapter.advanceToDone(TENANT_ID, USER_ID, "FRONT_DESK", VISIT_ID);

        verify(visitRepository).updateStage(VISIT_ID, "DONE", USER_ID);
        verify(auditLog).record("VISIT_STAGE_MOVED", "VISIT", VISIT_ID,
                java.util.Map.of("stage", "BILLING"), java.util.Map.of("stage", "DONE"));
    }

    @Test
    void advanceToDoneRefusesAVisitNotInBilling() {
        when(visitRepository.findById(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(row("WITH_DOCTOR")));

        assertThatThrownBy(() -> adapter.advanceToDone(TENANT_ID, USER_ID, "OWNER", VISIT_ID))
                .isInstanceOf(InvalidInputException.class);
        verify(visitRepository, never()).updateStage(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void advanceToDoneRefusesARoleThatIsNotOwnerOrFrontDesk() {
        when(visitRepository.findById(TENANT_ID, VISIT_ID)).thenReturn(Optional.of(row("BILLING")));

        assertThatThrownBy(() -> adapter.advanceToDone(TENANT_ID, USER_ID, "NURSE", VISIT_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void advanceToDoneOnAnUnknownVisitIs404() {
        when(visitRepository.findById(TENANT_ID, VISIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.advanceToDone(TENANT_ID, USER_ID, "OWNER", VISIT_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void betweenAndFindAllGiveRevenueViewsWithTheDoctorNameResolvedOncePerDoctor() {
        Instant from = Instant.parse("2026-10-31T18:30:00Z");
        Instant to = Instant.parse("2026-11-01T18:30:00Z");
        when(visitRepository.findBetween(TENANT_ID, from, to)).thenReturn(List.of(row("DONE"), row("BILLING")));
        when(visitRepository.findByIds(TENANT_ID, List.of(VISIT_ID))).thenReturn(List.of(row("DONE")));

        var between = adapter.between(TENANT_ID, from, to);
        var byIds = adapter.findAll(TENANT_ID, List.of(VISIT_ID));

        assertThat(between).hasSize(2);
        assertThat(between.get(0).doctorName()).isEqualTo("Dr Rao");
        assertThat(between.get(0).doctorMembershipId()).isEqualTo(DOCTOR_ID);
        assertThat(between.get(1).stage()).isEqualTo("BILLING");
        assertThat(byIds).singleElement().satisfies(v -> assertThat(v.feePaise()).isEqualTo(50000));
        verify(teamMemberPort, org.mockito.Mockito.times(2)).find(TENANT_ID, DOCTOR_ID);
    }
}
