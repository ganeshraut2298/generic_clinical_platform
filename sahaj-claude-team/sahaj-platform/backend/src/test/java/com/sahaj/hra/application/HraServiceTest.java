package com.sahaj.hra.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.hra.application.AttendanceRepository.AttendanceRow;
import com.sahaj.hra.application.RosterRepository.ShiftRow;
import com.sahaj.opd.api.DoctorOpdDaysPort;
import com.sahaj.platform.api.AuditLog;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class HraServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OWNER_M = UUID.randomUUID();
    private static final UUID DOCTOR_M = UUID.randomUUID();
    private static final UUID NURSE_M = UUID.randomUUID();
    private static final UUID DESK_M = UUID.randomUUID();
    /** 2026-11-05 08:52:30 IST. */
    private static final Instant NOW = Instant.parse("2026-11-05T03:22:30.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-05T03:22:30.123456Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private RosterRepository roster;
    private AttendanceRepository attendance;
    private LeaveRepository leave;
    private TeamMemberPort team;
    private ModuleEntitlementPort entitlements;
    private DoctorOpdDaysPort opdDays;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private HraService service;

    @BeforeEach
    void setUp() {
        roster = mock(RosterRepository.class);
        attendance = mock(AttendanceRepository.class);
        leave = mock(LeaveRepository.class);
        team = mock(TeamMemberPort.class);
        entitlements = mock(ModuleEntitlementPort.class);
        opdDays = mock(DoctorOpdDaysPort.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        service = new HraService(roster, attendance, leave, team, entitlements, opdDays, ownerGuard, auditLog,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", true, 25));
        TeamMember owner = new TeamMember(OWNER_M, "Asha", "OWNER", true, 0);
        TeamMember doctor = new TeamMember(DOCTOR_M, "Dr Rao", "DOCTOR", true, 50000);
        TeamMember nurse = new TeamMember(NURSE_M, "Meera", "NURSE", true, 0);
        TeamMember desk = new TeamMember(DESK_M, "Ravi", "FRONT_DESK", true, 0);
        when(team.findActiveMembers(TENANT)).thenReturn(List.of(owner, doctor, nurse, desk));
        for (TeamMember m : List.of(owner, doctor, nurse, desk)) {
            when(team.find(TENANT, m.membershipId())).thenReturn(Optional.of(m));
        }
        when(roster.findPublishedAt(TENANT)).thenReturn(Optional.empty());
        when(roster.findShift(eq(TENANT), any(), any())).thenReturn(Optional.empty());
        when(attendance.find(eq(TENANT), any(), any())).thenReturn(Optional.empty());
    }

    /** The mark call re-reads the caller's role from the team (security review L1), not the token. */
    private HraService asRole(String role) {
        when(team.findActiveByUserId(TENANT, ACTOR))
                .thenReturn(Optional.of(new TeamMember(UUID.randomUUID(), "Caller", role, true, 0)));
        return service;
    }

    @Test
    void everyCallRefusesWithModuleOffWhenHraIsOff() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThatThrownBy(() -> service.summary(TENANT, "OWNER")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.roster(TENANT)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.attendance(TENANT, "OWNER")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.publish(TENANT, ACTOR)).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY, "M")))
                .isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> asRole("OWNER").mark(TENANT, ACTOR, "OWNER", NURSE_M, "in"))
                .isInstanceOf(ModuleOffException.class);
        verify(roster, never()).upsertShift(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void summaryCountsPeopleAndPresentAndLateOfActiveMembersOnly() {
        when(attendance.findForDate(TENANT, TODAY)).thenReturn(List.of(
                new AttendanceRow(NURSE_M, TODAY, "in", LocalTime.of(8, 50)),
                new AttendanceRow(DESK_M, TODAY, "late", LocalTime.of(9, 20)),
                new AttendanceRow(OWNER_M, TODAY, "abs", null),
                new AttendanceRow(UUID.randomUUID(), TODAY, "in", LocalTime.of(8, 0))));

        assertThat(service.summary(TENANT, "OWNER")).isEqualTo(new StaffSummaryView(4, 1, 3, 2, 1, 0));
    }

    @Test
    void rosterHasSevenIstDaysStaffDefaultOffAndDoctorOpdFromSlots() {
        when(roster.findBetween(TENANT, TODAY, TODAY.plusDays(6)))
                .thenReturn(List.of(new ShiftRow(NURSE_M, TODAY.plusDays(1), "N")));
        when(opdDays.opdDates(eq(TENANT), eq(DOCTOR_M), any())).thenReturn(Set.of(TODAY));
        Instant publishedAt = Instant.parse("2026-11-04T10:00:00Z");
        when(roster.findPublishedAt(TENANT)).thenReturn(Optional.of(publishedAt));

        RosterView view = service.roster(TENANT);

        assertThat(view.days()).hasSize(7).startsWith(TODAY);
        assertThat(view.shifts()).extracting(RosterView.ShiftInfo::key).containsExactly("M", "E", "N", "X");
        assertThat(view.published()).isTrue();
        assertThat(view.publishedAt()).isEqualTo(publishedAt);
        RosterView.Row doctor = view.rows().get(1);
        assertThat(doctor.doctor()).isTrue();
        assertThat(doctor.roleLabel()).isEqualTo("Doctor");
        assertThat(doctor.cells()).extracting(RosterView.Cell::kind)
                .containsExactly("OPD", "OFF", "OFF", "OFF", "OFF", "OFF", "OFF");
        assertThat(doctor.cells()).extracting(RosterView.Cell::shift).containsOnlyNulls();
        RosterView.Row nurse = view.rows().get(2);
        assertThat(nurse.roleLabel()).isEqualTo("Nurse");
        assertThat(nurse.cells().get(0).kind()).isEqualTo("SHIFT");
        assertThat(nurse.cells()).extracting(RosterView.Cell::shift)
                .containsExactly("X", "N", "X", "X", "X", "X", "X");
        assertThat(view.rows().get(0).roleLabel()).isEqualTo("Owner");
        assertThat(view.rows().get(3).roleLabel()).isEqualTo("Front desk");
    }

    @Test
    void rosterDaysFollowTheIstDateNotUtc() {
        service = new HraService(roster, attendance, leave, team, entitlements, opdDays, ownerGuard, auditLog,
                Clock.fixed(Instant.parse("2026-11-05T20:00:00Z"), ZoneOffset.UTC));

        assertThat(service.roster(TENANT).days().get(0)).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void ownerSetsACellAndItIsAuditedWithoutNames() {
        RosterView view = service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY.plusDays(2), "E"));

        verify(ownerGuard).requireOwner(TENANT, ACTOR);
        verify(roster).upsertShift(TENANT, NURSE_M, TODAY.plusDays(2), "E", ACTOR, NOW_MICROS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("ROSTER_CELL_CHANGED"), eq("ROSTER_SHIFT"), eq(NURSE_M), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("shift", "X").containsEntry("date", "2026-11-07");
        assertThat(after.getValue()).containsEntry("shift", "E");
        assertThat(after.getValue().values()).doesNotContain("Meera");
        assertThat(view.rows()).hasSize(4);
    }

    @Test
    void settingACellToOffUpsertsX() {
        service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY, "X"));

        verify(roster).upsertShift(eq(TENANT), eq(NURSE_M), eq(TODAY), eq("X"), eq(ACTOR), any());
    }

    @Test
    void aCellForADoctorIsRefused() {
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(DOCTOR_M, TODAY, "M")))
                .isInstanceOf(InvalidInputException.class);
        verify(roster, never()).upsertShift(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void aCellOutsideTheSevenDaysOrWithABadShiftIsRefused() {
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY.minusDays(1), "M")))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("date");
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY.plusDays(7), "M")))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("date");
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY, "Z")))
                .isInstanceOf(InvalidInputException.class).extracting("field").isEqualTo("shift");
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY, null)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(null, TODAY, "M")))
                .isInstanceOf(InvalidInputException.class);
        service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY.plusDays(6), "M"));
        verify(roster).upsertShift(eq(TENANT), eq(NURSE_M), eq(TODAY.plusDays(6)), eq("M"), eq(ACTOR), any());
    }

    @Test
    void aCellForAnUnknownOrInactiveMemberIs404() {
        UUID unknown = UUID.randomUUID();
        when(team.find(TENANT, unknown)).thenReturn(Optional.empty());
        UUID gone = UUID.randomUUID();
        when(team.find(TENANT, gone)).thenReturn(Optional.of(new TeamMember(gone, "Old", "NURSE", false, 0)));

        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(unknown, TODAY, "M")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(gone, TODAY, "M")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDemotedOwnerCannotEditTheRosterOrPublish() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, ACTOR);

        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY, "M")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.publish(TENANT, ACTOR)).isInstanceOf(AccessDeniedException.class);
        verify(roster, never()).publish(any(), any(), any());
    }

    @Test
    void publishRecordsTheFirstTimeOnlyAndIsIdempotent() {
        when(roster.findPublishedAt(TENANT)).thenReturn(Optional.empty(), Optional.of(NOW_MICROS));

        RosterView first = service.publish(TENANT, ACTOR);

        verify(roster).publish(TENANT, ACTOR, NOW_MICROS);
        verify(auditLog).record(eq("ROSTER_PUBLISHED"), eq("ROSTER_STATE"), eq(TENANT), any(), any());
        assertThat(first.published()).isTrue();

        when(roster.findPublishedAt(TENANT)).thenReturn(Optional.of(NOW_MICROS));
        service.publish(TENANT, ACTOR);

        verify(roster).publish(any(), any(), any());
        verify(auditLog).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void attendanceListsEveryActiveMemberWithTodaysMarkAndCanMarkByRole() {
        when(attendance.findForDate(TENANT, TODAY)).thenReturn(List.of(
                new AttendanceRow(NURSE_M, TODAY, "in", LocalTime.of(8, 52, 10)),
                new AttendanceRow(DESK_M, TODAY, "abs", null)));

        AttendanceView asNurse = service.attendance(TENANT, "NURSE");

        assertThat(asNurse.date()).isEqualTo(TODAY);
        assertThat(asNurse.lateAfterMinutes()).isEqualTo(15);
        assertThat(asNurse.canMark()).isFalse();
        assertThat(service.attendance(TENANT, "FRONT_DESK").canMark()).isTrue();
        assertThat(service.attendance(TENANT, "OWNER").canMark()).isTrue();
        assertThat(asNurse.rows()).hasSize(4);
        AttendanceView.Row nurse = asNurse.rows().get(2);
        assertThat(nurse.status()).isEqualTo("in");
        assertThat(nurse.markedTime()).isEqualTo("08:52");
        AttendanceView.Row desk = asNurse.rows().get(3);
        assertThat(desk.status()).isEqualTo("abs");
        assertThat(desk.markedTime()).isNull();
        assertThat(asNurse.rows().get(0).status()).isNull();
    }

    @Test
    void presentAndLateRecordTheIstClockTimeAndAbsentRecordsNone() {
        asRole("FRONT_DESK").mark(TENANT, ACTOR, "FRONT_DESK", NURSE_M, "late");
        verify(attendance).upsert(TENANT, NURSE_M, TODAY, "late", LocalTime.of(8, 52, 30), ACTOR, NOW_MICROS);

        asRole("OWNER").mark(TENANT, ACTOR, "OWNER", NURSE_M, "abs");
        verify(attendance).upsert(TENANT, NURSE_M, TODAY, "abs", null, ACTOR, NOW_MICROS);
    }

    @Test
    void changingTheSameDaysMarkAuditsBeforeAndAfterWithoutNames() {
        when(attendance.find(TENANT, NURSE_M, TODAY))
                .thenReturn(Optional.of(new AttendanceRow(NURSE_M, TODAY, "in", LocalTime.of(8, 0))));

        asRole("OWNER").mark(TENANT, ACTOR, "OWNER", NURSE_M, "abs");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("ATTENDANCE_MARKED"), eq("ATTENDANCE"), eq(NURSE_M), before.capture(),
                after.capture());
        assertThat(before.getValue()).containsEntry("status", "in").containsEntry("date", "2026-11-05");
        assertThat(after.getValue()).containsEntry("status", "abs");
    }

    @Test
    void markingNeedsOwnerOrFrontDeskAValidStatusAndAnActiveMember() {
        assertThatThrownBy(() -> asRole("NURSE").mark(TENANT, ACTOR, "NURSE", NURSE_M, "in"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> asRole("OWNER").mark(TENANT, ACTOR, "OWNER", NURSE_M, "present"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> asRole("OWNER").mark(TENANT, ACTOR, "OWNER", NURSE_M, null))
                .isInstanceOf(InvalidInputException.class);
        UUID unknown = UUID.randomUUID();
        when(team.find(TENANT, unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> asRole("OWNER").mark(TENANT, ACTOR, "OWNER", unknown, "in"))
                .isInstanceOf(NotFoundException.class);
        UUID gone = UUID.randomUUID();
        when(team.find(TENANT, gone)).thenReturn(Optional.of(new TeamMember(gone, "Old", "NURSE", false, 0)));
        assertThatThrownBy(() -> asRole("OWNER").mark(TENANT, ACTOR, "OWNER", gone, "in"))
                .isInstanceOf(NotFoundException.class);
        verify(attendance, never()).upsert(any(), any(), any(), anyString(), any(), any(), any());
    }

    private static LeaveRepository.LeaveRow approved(UUID member, LocalDate from, LocalDate to) {
        return new LeaveRepository.LeaveRow(UUID.randomUUID(), member, "CL", from, to, "private reason", "approved",
                NOW_MICROS, NOW_MICROS, ACTOR);
    }

    @Test
    void aTokenSayingOwnerButATeamRoleOfNurseCannotMark() {
        assertThatThrownBy(() -> asRole("NURSE").mark(TENANT, ACTOR, "OWNER", NURSE_M, "in"))
                .isInstanceOf(AccessDeniedException.class);
        when(team.findActiveByUserId(TENANT, ACTOR)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.mark(TENANT, ACTOR, "OWNER", NURSE_M, "in"))
                .isInstanceOf(AccessDeniedException.class);
        verify(attendance, never()).upsert(any(), any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void aRosterEditOnAnApprovedLeaveDayIs409OnLeave() {
        when(leave.findApprovedOverlappingForMember(TENANT, NURSE_M, TODAY.plusDays(1), TODAY.plusDays(1)))
                .thenReturn(List.of(approved(NURSE_M, TODAY, TODAY.plusDays(2))));

        assertThatThrownBy(() -> service.setCell(TENANT, ACTOR, new RosterCellInput(NURSE_M, TODAY.plusDays(1), "M")))
                .isInstanceOf(com.sahaj.platform.api.ConflictException.class);
        verify(roster, never()).upsertShift(any(), any(), any(), anyString(), any(), any());
        verify(auditLog, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void summaryShowsPendingLeaveToTheOwnerOnly() {
        when(leave.countPending(TENANT)).thenReturn(3L);

        assertThat(service.summary(TENANT, "OWNER").leaveRequests()).isEqualTo(3);
        assertThat(service.summary(TENANT, "FRONT_DESK").leaveRequests()).isZero();
    }

    @Test
    void approvedLeaveMakesTheRosterCellLeaveForStaffAndDoctors() {
        when(leave.findApprovedOverlapping(TENANT, TODAY, TODAY.plusDays(6))).thenReturn(List.of(
                approved(NURSE_M, TODAY.plusDays(1), TODAY.plusDays(2)),
                approved(DOCTOR_M, TODAY, TODAY)));
        when(opdDays.opdDates(eq(TENANT), eq(DOCTOR_M), any())).thenReturn(Set.of(TODAY, TODAY.plusDays(1)));

        RosterView view = service.roster(TENANT);

        assertThat(view.rows().get(1).cells()).extracting(RosterView.Cell::kind)
                .containsExactly("LEAVE", "OPD", "OFF", "OFF", "OFF", "OFF", "OFF");
        assertThat(view.rows().get(2).cells()).extracting(RosterView.Cell::kind)
                .containsExactly("SHIFT", "LEAVE", "LEAVE", "SHIFT", "SHIFT", "SHIFT", "SHIFT");
        assertThat(view.rows().get(2).cells().get(1).shift()).isNull();
    }

    @Test
    void attendanceFlagsOnLeaveAndMarkingThatPersonIsRefusedWith409() {
        when(leave.findApprovedOverlapping(TENANT, TODAY, TODAY)).thenReturn(List.of(approved(NURSE_M, TODAY, TODAY)));
        when(leave.findApprovedOverlappingForMember(TENANT, NURSE_M, TODAY, TODAY))
                .thenReturn(List.of(approved(NURSE_M, TODAY, TODAY)));

        AttendanceView view = service.attendance(TENANT, "OWNER");

        assertThat(view.rows()).extracting(AttendanceView.Row::onLeave).containsExactly(false, false, true, false);
        assertThatThrownBy(() -> asRole("OWNER").mark(TENANT, ACTOR, "OWNER", NURSE_M, "in"))
                .isInstanceOf(com.sahaj.platform.api.ConflictException.class);
        verify(attendance, never()).upsert(any(), any(), any(), anyString(), any(), any(), any());
    }
}
