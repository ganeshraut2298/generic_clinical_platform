package com.sahaj.hra.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.hra.application.LeaveRepository.LeaveRow;
import com.sahaj.opd.api.DoctorOpdDaysPort;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
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
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

class LeaveServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID NURSE_USER = UUID.randomUUID();
    private static final UUID OWNER_M = UUID.randomUUID();
    private static final UUID NURSE_M = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    /** 2026-11-05 08:52:30 IST. */
    private static final Instant NOW = Instant.parse("2026-11-05T03:22:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-05T03:22:30.123456Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final String SECRET_REASON = "chemotherapy follow-up for my mother";

    private LeaveRepository leave;
    private TeamMemberPort team;
    private ModuleEntitlementPort entitlements;
    private DoctorOpdDaysPort opdDays;
    private ActorDirectoryPort actors;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private LeaveService service;

    @BeforeEach
    void setUp() {
        leave = mock(LeaveRepository.class);
        team = mock(TeamMemberPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        opdDays = mock(DoctorOpdDaysPort.class);
        actors = mock(ActorDirectoryPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new LeaveService(leave, team, entitlements, opdDays, actors, ownerGuard, auditLog,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", true, 25));
        TeamMember owner = new TeamMember(OWNER_M, "Asha", "OWNER", true, 0);
        TeamMember nurse = new TeamMember(NURSE_M, "Meera", "NURSE", true, 0);
        TeamMember doctor = new TeamMember(DOCTOR_M, "Dr Rao", "DOCTOR", true, 50000);
        when(team.findActiveMembers(TENANT)).thenReturn(List.of(owner, nurse, doctor));
        for (TeamMember m : List.of(owner, nurse, doctor)) {
            when(team.find(TENANT, m.membershipId())).thenReturn(Optional.of(m));
        }
        when(team.findActiveByUserId(TENANT, OWNER_USER)).thenReturn(Optional.of(owner));
        when(team.findActiveByUserId(TENANT, NURSE_USER)).thenReturn(Optional.of(nurse));
        when(leave.findRecent(TENANT, LeaveService.OWNER_LIST_LIMIT)).thenReturn(List.of());
        when(leave.findForMember(any(), any())).thenReturn(List.of());
        when(actors.displayName(any(), any())).thenReturn(Optional.of("Asha"));
    }

    private static LeaveRow row(UUID member, String type, LocalDate from, LocalDate to, String status) {
        boolean decided = !"pending".equals(status);
        return new LeaveRow(UUID.randomUUID(), member, type, from, to, SECRET_REASON, status, NOW_MICROS,
                decided ? NOW_MICROS : null, decided ? OWNER_USER : null);
    }

    private static LeaveInput input(UUID member, String type, LocalDate from, LocalDate to) {
        return new LeaveInput(member, type, from, to, SECRET_REASON);
    }

    @Test
    void everyCallRefusesWithModuleOffWhenHraIsOff() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.view(TENANT, OWNER_USER, "OWNER")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.create(TENANT, OWNER_USER, "OWNER", input(null, "CL", TODAY, TODAY)))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.approve(TENANT, OWNER_USER, "OWNER", id))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.decline(TENANT, OWNER_USER, "OWNER", id))
                .isInstanceOf(ModuleOffException.class);
    }

    @Test
    void ownerSeesEveryRequestEveryBalanceAndEveryApplicant() {
        LeaveRow pendingDoctor = row(DOCTOR_M, "CL", TODAY.plusDays(2), TODAY.plusDays(3), "pending");
        LeaveRow approvedNurse = row(NURSE_M, "SL", TODAY, TODAY, "approved");
        when(leave.findRecent(TENANT, LeaveService.OWNER_LIST_LIMIT)).thenReturn(List.of(pendingDoctor, approvedNurse));
        when(leave.findApprovedOverlapping(TENANT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(approvedNurse));
        when(leave.countPending(TENANT)).thenReturn(1L);
        when(opdDays.bookedAppointmentsByDay(eq(TENANT), eq(java.util.Set.of(DOCTOR_M)), eq(TODAY.plusDays(2)),
                eq(TODAY.plusDays(3)))).thenReturn(Map.of(DOCTOR_M, Map.of(TODAY.plusDays(2), 3,
                        TODAY.plusDays(3), 1, TODAY.plusDays(9), 7)));

        LeaveView view = service.view(TENANT, OWNER_USER, "OWNER");

        assertThat(view.canApprove()).isTrue();
        assertThat(view.pendingCount()).isEqualTo(1);
        assertThat(view.applicants()).extracting(LeaveView.Applicant::name).containsExactly("Asha", "Meera", "Dr Rao");
        assertThat(view.balances()).hasSize(3);
        assertThat(view.balances().get(1).remaining()).isEqualTo(new LeaveView.Remaining(8, 6, 15));
        LeaveView.Request doctorRequest = view.requests().get(0);
        assertThat(doctorRequest.name()).isEqualTo("Dr Rao");
        assertThat(doctorRequest.roleLabel()).isEqualTo("Doctor");
        assertThat(doctorRequest.typeName()).isEqualTo("Casual leave");
        assertThat(doctorRequest.days()).isEqualTo(2);
        assertThat(doctorRequest.affectedAppointments()).isEqualTo(4);
        assertThat(doctorRequest.decidedByName()).isNull();
        LeaveView.Request nurseRequest = view.requests().get(1);
        assertThat(nurseRequest.askedAt()).isEqualTo(NOW_MICROS);
        assertThat(nurseRequest.decidedByName()).isEqualTo("Asha");
        assertThat(nurseRequest.affectedAppointments()).isZero();
        assertThat(view.types()).extracting(LeaveView.TypeInfo::days).containsExactly(8, 7, 15);
    }

    @Test
    void otherRolesSeeOnlyTheirOwnRequestAndBalance() {
        LeaveRow mine = row(NURSE_M, "EL", TODAY.plusDays(1), TODAY.plusDays(1), "pending");
        when(leave.findForMember(TENANT, NURSE_M)).thenReturn(List.of(mine));

        LeaveView view = service.view(TENANT, NURSE_USER, "NURSE");

        verify(leave, never()).findRecent(any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(view.canApprove()).isFalse();
        assertThat(view.requests()).hasSize(1);
        assertThat(view.pendingCount()).isEqualTo(1);
        assertThat(view.balances()).extracting(LeaveView.Balance::membershipId).containsExactly(NURSE_M);
        assertThat(view.applicants()).extracting(LeaveView.Applicant::membershipId).containsExactly(NURSE_M);
        verify(opdDays, never()).bookedAppointmentsByDay(any(), any(), any(), any());
    }

    @Test
    void balanceCountsApprovedCalendarDaysInTheYearSplitsNewYearAndNeverGoesBelowZero() {
        LeaveRow overNewYear = row(NURSE_M, "CL", LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2), "approved");
        LeaveRow tooMany = row(NURSE_M, "SL", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 20), "approved");
        LeaveRow intoNextYear = row(NURSE_M, "EL", LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 5), "approved");
        when(leave.findApprovedOverlapping(TENANT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(overNewYear, tooMany, intoNextYear));

        LeaveView view = service.view(TENANT, NURSE_USER, "NURSE");

        assertThat(view.balances().get(0).remaining()).isEqualTo(new LeaveView.Remaining(6, 0, 13));
    }

    @Test
    void aRequestIsCreatedPendingForOneselfAuditedWithoutReasonOrNames() {
        LeaveView view = service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY, TODAY.plusDays(2)));

        InOrder order = inOrder(leave);
        order.verify(leave).lockMember(TENANT, NURSE_M);
        order.verify(leave).existsActiveOverlap(TENANT, NURSE_M, TODAY, TODAY.plusDays(2));
        order.verify(leave).insert(eq(TENANT), any(UUID.class), eq(NURSE_M), eq("CL"), eq(TODAY),
                eq(TODAY.plusDays(2)), eq(SECRET_REASON), eq(NURSE_USER), eq(NOW_MICROS));
        verify(ownerGuard, never()).requireOwner(any(), any());
        assertThat(view.canApprove()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("LEAVE_REQUESTED"), eq("LEAVE_REQUEST"), any(UUID.class), eq(null), after.capture());
        assertThat(after.getValue()).containsEntry("membershipId", NURSE_M).containsEntry("type", "CL")
                .containsEntry("from", "2026-11-05").containsEntry("to", "2026-11-07")
                .containsEntry("status", "pending");
        assertThat(after.getValue().toString()).doesNotContain(SECRET_REASON).doesNotContain("Meera");
    }

    @Test
    void aBlankReasonIsStoredAsNullAndTheReasonIsTrimmed() {
        service.create(TENANT, NURSE_USER, "NURSE", new LeaveInput(null, "SL", TODAY, TODAY, "   "));
        service.create(TENANT, NURSE_USER, "NURSE", new LeaveInput(null, "SL", TODAY.plusDays(5), TODAY.plusDays(5), " fever "));

        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), eq("SL"), eq(TODAY), eq(TODAY), eq(null), any(), any());
        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), eq("SL"), eq(TODAY.plusDays(5)), eq(TODAY.plusDays(5)),
                eq("fever"), any(), any());
    }

    @Test
    void anOwnerMayAskForAnotherActiveMemberAfterTheOwnerRecheck() {
        service.create(TENANT, OWNER_USER, "OWNER", input(NURSE_M, "EL", TODAY, TODAY));

        verify(ownerGuard).requireOwner(TENANT, OWNER_USER);
        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), eq("EL"), eq(TODAY), eq(TODAY), any(), eq(OWNER_USER),
                eq(NOW_MICROS));
    }

    @Test
    void namingYourselfExplicitlyIsAllowedForEveryone() {
        service.create(TENANT, NURSE_USER, "NURSE", input(NURSE_M, "CL", TODAY, TODAY));

        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aNonOwnerNamingSomeoneElseIs403AndNothingIsSaved() {
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(DOCTOR_M, "CL", TODAY, TODAY)))
                .isInstanceOf(AccessDeniedException.class);

        verify(leave, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownOrInactiveMemberIs404() {
        UUID gone = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(team.find(TENANT, gone)).thenReturn(Optional.empty());
        when(team.find(TENANT, inactive)).thenReturn(Optional.of(new TeamMember(inactive, "Old", "NURSE", false, 0)));

        assertThatThrownBy(() -> service.create(TENANT, OWNER_USER, "OWNER", input(gone, "CL", TODAY, TODAY)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(TENANT, OWNER_USER, "OWNER", input(inactive, "CL", TODAY, TODAY)))
                .isInstanceOf(NotFoundException.class);
        verify(leave, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anOverlapWithPendingOrApprovedLeaveIs409AndNothingIsSaved() {
        when(leave.existsActiveOverlap(TENANT, NURSE_M, TODAY, TODAY.plusDays(1))).thenReturn(true);

        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY, TODAY.plusDays(1))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("That person already has leave on some of these days.");

        verify(leave).lockMember(TENANT, NURSE_M);
        verify(leave, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void invalidDatesTypeAndReasonAre400() {
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, "XX", TODAY, TODAY)))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("type");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, null, TODAY, TODAY)))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("type");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", null, TODAY)))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("from");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY, null)))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("to");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE",
                input(null, "CL", TODAY.minusDays(1), TODAY)))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("from");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE",
                input(null, "CL", TODAY.plusDays(2), TODAY.plusDays(1))))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("to");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE",
                input(null, "CL", TODAY, TODAY.plusDays(61))))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("to");
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE",
                new LeaveInput(null, "CL", TODAY, TODAY, "x".repeat(201))))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("reason");
        verify(leave, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fromMoreThanSixtyDaysAheadIsAFieldErrorOnFrom() {
        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE",
                input(null, "CL", TODAY.plusDays(61), TODAY.plusDays(61))))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("from");
        service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY.plusDays(60), TODAY.plusDays(60)));
        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), eq("CL"), eq(TODAY.plusDays(60)), any(), any(), any(), any());
    }

    @Test
    void controlCharactersInTheReasonAreAFieldErrorOnReason() {
        for (String bad : List.of("a" + (char) 0 + "b", "line" + (char) 10 + "break", "tab" + (char) 9 + "here")) {
            assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE",
                    new LeaveInput(null, "CL", TODAY, TODAY, bad)))
                    .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("reason");
        }
        verify(leave, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void twentyPendingRequestsBlockTheNextOneWith409() {
        when(leave.countPendingForMember(TENANT, NURSE_M)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY, TODAY)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Too many pending requests. Wait for a decision.");
        verify(leave, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());

        when(leave.countPendingForMember(TENANT, NURSE_M)).thenReturn(19L);
        service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY, TODAY));
        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordsDoNotPrintTheReason() {
        LeaveRow leaveRow = row(NURSE_M, "CL", TODAY, TODAY, "pending");
        assertThat(leaveRow.toString()).doesNotContain(SECRET_REASON);
        LeaveView.Request request = new LeaveView.Request(UUID.randomUUID(), NURSE_M, "Meera", "Nurse", "CL",
                "Casual leave", TODAY, TODAY, 1, SECRET_REASON, "pending", NOW_MICROS, null, null, 0);
        assertThat(request.toString()).doesNotContain(SECRET_REASON).doesNotContain("Meera");
    }

    @Test
    void sixtyDaysAfterFromAndTwoHundredCharactersAreAccepted() {
        service.create(TENANT, NURSE_USER, "NURSE",
                new LeaveInput(null, "CL", TODAY, TODAY.plusDays(60), "x".repeat(200)));

        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), eq("CL"), eq(TODAY), eq(TODAY.plusDays(60)), any(),
                any(), any());
    }

    @Test
    void todayIsTheIstDateNotUtc() {
        service = new LeaveService(leave, team, entitlements, opdDays, actors, ownerGuard, auditLog,
                Clock.fixed(Instant.parse("2026-11-05T20:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY, TODAY)))
                .isInstanceOf(InvalidInputException.class);
        service.create(TENANT, NURSE_USER, "NURSE", input(null, "CL", TODAY.plusDays(1), TODAY.plusDays(1)));
        verify(leave).insert(eq(TENANT), any(), eq(NURSE_M), eq("CL"), eq(TODAY.plusDays(1)), any(), any(), any(), any());
    }

    @Test
    void ownerApprovesAndTheDecisionIsAuditedWithoutReasonOrNames() {
        LeaveRow pending = row(NURSE_M, "CL", TODAY.plusDays(1), TODAY.plusDays(2), "pending");
        when(leave.findById(TENANT, pending.id())).thenReturn(Optional.of(pending));
        when(leave.decide(TENANT, pending.id(), "approved", OWNER_USER, NOW_MICROS)).thenReturn(true);

        service.approve(TENANT, OWNER_USER, "OWNER", pending.id());

        verify(ownerGuard).requireOwner(TENANT, OWNER_USER);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("LEAVE_APPROVED"), eq("LEAVE_REQUEST"), eq(pending.id()), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("status", "pending");
        assertThat(after.getValue()).containsEntry("status", "approved").containsEntry("type", "CL")
                .containsEntry("from", "2026-11-06").containsEntry("to", "2026-11-07");
        assertThat(after.getValue().toString()).doesNotContain(SECRET_REASON).doesNotContain("Meera");
    }

    @Test
    void ownerDeclinesAndItIsAudited() {
        LeaveRow pending = row(NURSE_M, "CL", TODAY, TODAY, "pending");
        when(leave.findById(TENANT, pending.id())).thenReturn(Optional.of(pending));
        when(leave.decide(TENANT, pending.id(), "declined", OWNER_USER, NOW_MICROS)).thenReturn(true);

        service.decline(TENANT, OWNER_USER, "OWNER", pending.id());

        verify(auditLog).record(eq("LEAVE_DECLINED"), eq("LEAVE_REQUEST"), eq(pending.id()), any(), any());
    }

    @Test
    void aNonOwnerIsRefusedByTheOwnerGuardBeforeAnythingChanges() {
        UUID id = UUID.randomUUID();
        doThrow(new AccessDeniedException("owner only")).when(ownerGuard).requireOwner(TENANT, NURSE_USER);

        assertThatThrownBy(() -> service.approve(TENANT, NURSE_USER, "NURSE", id))
                .isInstanceOf(AccessDeniedException.class);
        verify(leave, never()).decide(any(), any(), anyString(), any(), any());
    }

    @Test
    void anUnknownOrOtherClinicsRequestIs404() {
        UUID id = UUID.randomUUID();
        when(leave.findById(TENANT, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(TENANT, OWNER_USER, "OWNER", id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.decline(TENANT, OWNER_USER, "OWNER", id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDecidedRequestCannotBeChangedAgain() {
        LeaveRow decided = row(NURSE_M, "CL", TODAY, TODAY, "declined");
        when(leave.findById(TENANT, decided.id())).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> service.approve(TENANT, OWNER_USER, "OWNER", decided.id()))
                .isInstanceOf(ConflictException.class).hasMessage("This request was already decided.");
        verify(leave, never()).decide(any(), any(), anyString(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aLostRaceOnTheStatusUpdateIsAlso409() {
        LeaveRow pending = row(NURSE_M, "CL", TODAY, TODAY, "pending");
        when(leave.findById(TENANT, pending.id())).thenReturn(Optional.of(pending));
        when(leave.decide(any(), any(), anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.decline(TENANT, OWNER_USER, "OWNER", pending.id()))
                .isInstanceOf(ConflictException.class);
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }
}
