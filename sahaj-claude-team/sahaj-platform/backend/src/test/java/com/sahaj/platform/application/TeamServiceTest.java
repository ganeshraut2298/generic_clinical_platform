package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.PlanLimits;
import com.sahaj.platform.api.StaffPinException;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.domain.StaffPinRow;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/** SAH-6: the rules of Team and access, with every port mocked. */
class TeamServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID OWNER_MEMBERSHIP = UUID.randomUUID();
    private static final UUID NURSE_USER = UUID.randomUUID();
    private static final UUID NURSE_MEMBERSHIP = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private TeamRepository teamRepository;
    private AppUserRepository appUserRepository;
    private DoctorProfileRepository doctorProfileRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private AuditLog auditLog;
    private StaffPinRepository staffPinRepository;
    private StaffPinOps staffPinOps;
    private StaffPinLimits staffPinLimits;
    private TeamService service;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        doctorProfileRepository = mock(DoctorProfileRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        auditLog = mock(AuditLog.class);
        staffPinRepository = mock(StaffPinRepository.class);
        staffPinOps = mock(StaffPinOps.class);
        staffPinLimits = mock(StaffPinLimits.class);
        service = new TeamService(teamRepository, appUserRepository, doctorProfileRepository,
                refreshTokenRepository, auditLog, staffPinRepository, staffPinOps, staffPinLimits,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(teamRepository.findByUser(TENANT_ID, OWNER_USER))
                .thenReturn(Optional.of(row(OWNER_MEMBERSHIP, OWNER_USER, "Asha", MembershipRole.OWNER,
                        MembershipStatus.ACTIVE)));
    }

    private static TeamRow row(UUID id, UUID userId, String name, MembershipRole role, MembershipStatus status) {
        return new TeamRow(id, userId, name, "9876547410", role, status, null, false, null, null, null, 0L);
    }

    private void nurseExists(MembershipStatus status) {
        when(teamRepository.findById(TENANT_ID, NURSE_MEMBERSHIP))
                .thenReturn(Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, status)));
    }

    @Test
    void listMasksEveryMobileToItsLastFourDigits() {
        when(teamRepository.findAll(TENANT_ID)).thenReturn(List.of(
                row(OWNER_MEMBERSHIP, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.ACTIVE)));

        List<TeamMemberView> team = service.list(TENANT_ID, OWNER_USER);

        assertThat(team).singleElement().satisfies(member -> {
            assertThat(member.mobileMasked()).isEqualTo("••••••7410");
            assertThat(member.mobileMasked()).doesNotContain("9876");
        });
    }

    @Test
    void anOwnerCanAddAnewPersonWhoGetsTheirOwnNameAndAnAuditRow() {
        when(appUserRepository.findByMobile("9123456780")).thenReturn(Optional.empty());
        when(teamRepository.countActive(TENANT_ID)).thenReturn(1);
        when(teamRepository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(row(call.getArgument(1), UUID.randomUUID(), "Nisha", MembershipRole.NURSE,
                        MembershipStatus.ACTIVE)));

        TeamMemberView added = service.add(TENANT_ID, OWNER_USER,
                new TeamAddInput(" Nisha ", "9123456780", MembershipRole.NURSE, null));

        assertThat(added.role()).isEqualTo(MembershipRole.NURSE);
        verify(teamRepository).lockTenant();
        verify(appUserRepository).insert(any(UUID.class), eq("9123456780"), eq("Nisha"));
        verify(teamRepository).insert(any(UUID.class), any(UUID.class), eq(MembershipRole.NURSE), eq("Nisha"),
                eq(OWNER_USER));
        verify(auditLog).record(eq("TEAM_MEMBER_ADDED"), eq("MEMBERSHIP"), any(UUID.class), isNull(), anyMap());
    }

    @Test
    void aPersonAlreadyKnownToSahajIsReusedAndTheirGlobalNameIsNotTouched() {
        UUID existingUser = UUID.randomUUID();
        when(appUserRepository.findByMobile("9123456780"))
                .thenReturn(Optional.of(new AppUser(existingUser, "9123456780", "Nisha K")));
        when(teamRepository.findByUser(TENANT_ID, existingUser)).thenReturn(Optional.empty());
        when(teamRepository.countActive(TENANT_ID)).thenReturn(1);
        when(teamRepository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(row(call.getArgument(1), existingUser, "Nisha", MembershipRole.NURSE,
                        MembershipStatus.ACTIVE)));

        service.add(TENANT_ID, OWNER_USER, new TeamAddInput("Nisha", "9123456780", MembershipRole.NURSE, null));

        verify(appUserRepository, never()).insert(any(), any(), any());
        verify(teamRepository).insert(any(UUID.class), eq(existingUser), eq(MembershipRole.NURSE), eq("Nisha"),
                eq(OWNER_USER));
    }

    @Test
    void addingAMobileAlreadyOnTheTeamIsRefused() {
        UUID existingUser = UUID.randomUUID();
        when(appUserRepository.findByMobile("9123456780"))
                .thenReturn(Optional.of(new AppUser(existingUser, "9123456780", "Nisha")));
        when(teamRepository.findByUser(TENANT_ID, existingUser))
                .thenReturn(Optional.of(row(NURSE_MEMBERSHIP, existingUser, "Nisha", MembershipRole.NURSE,
                        MembershipStatus.REMOVED)));

        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER,
                new TeamAddInput("Nisha", "9123456780", MembershipRole.NURSE, null)))
                .isInstanceOfSatisfying(TeamConflictException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(TeamConflictException.Kind.DUPLICATE_MOBILE));
        verify(teamRepository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void addingBeyondThePlanLimitIsRefusedWithThePlanMessage() {
        when(appUserRepository.findByMobile("9123456780")).thenReturn(Optional.empty());
        when(teamRepository.countActive(TENANT_ID)).thenReturn(PlanLimits.MAX_USERS);

        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER,
                new TeamAddInput("Nisha", "9123456780", MembershipRole.NURSE, null)))
                .isInstanceOf(PlanLimitException.class)
                .hasMessageContaining("up to " + PlanLimits.MAX_USERS + " users");
        verify(teamRepository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void aDoctorGetsAProfileWithTheFeeInPaiseAndNoSpecialtyIsFine() {
        when(appUserRepository.findByMobile("9123456780")).thenReturn(Optional.empty());
        when(teamRepository.countActive(TENANT_ID)).thenReturn(1);
        when(teamRepository.findById(eq(TENANT_ID), any(UUID.class))).thenAnswer(call ->
                Optional.of(new TeamRow(call.getArgument(1), UUID.randomUUID(), "Dr Ravi", "9123456780",
                        MembershipRole.DOCTOR, MembershipStatus.ACTIVE, null, true, null, "MBBS", "MH-1", 50000L)));

        TeamMemberView added = service.add(TENANT_ID, OWNER_USER, new TeamAddInput("Dr Ravi", "9123456780",
                MembershipRole.DOCTOR, new DoctorInput(" ", "MBBS", "MH-1", 500)));

        verify(doctorProfileRepository).insert(any(UUID.class), eq(TENANT_ID), isNull(), eq(50000L), eq("MBBS"),
                eq("MH-1"));
        assertThat(added.doctor().feeRupees()).isEqualTo(500);
        assertThat(added.doctor().specialty()).isNull();
    }

    @Test
    void anInvalidNameOrMobileIsRefusedWithTheMockupMessages() {
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER,
                new TeamAddInput("  ", "9123456780", MembershipRole.NURSE, null)))
                .isInstanceOf(TeamInputInvalidException.class).hasMessage("Enter the name");
        assertThatThrownBy(() -> service.add(TENANT_ID, OWNER_USER,
                new TeamAddInput("Nisha", "12345", MembershipRole.NURSE, null)))
                .isInstanceOf(TeamInputInvalidException.class).hasMessage("Enter a 10-digit mobile number");
    }

    @Test
    void aNonOwnerCannotChangeAnythingEvenIfTheTokenSaysOwner() {
        UUID frontDesk = UUID.randomUUID();
        when(teamRepository.findByUser(TENANT_ID, frontDesk))
                .thenReturn(Optional.of(row(UUID.randomUUID(), frontDesk, "Fiona", MembershipRole.FRONT_DESK,
                        MembershipStatus.ACTIVE)));

        assertThatThrownBy(() -> service.deactivate(TENANT_ID, frontDesk, NURSE_MEMBERSHIP))
                .isInstanceOf(AccessRefusedException.class);
        assertThatThrownBy(() -> service.add(TENANT_ID, frontDesk,
                new TeamAddInput("Nisha", "9123456780", MembershipRole.NURSE, null)))
                .isInstanceOf(AccessRefusedException.class);
        verify(teamRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void deactivatingSwitchesThePersonOffEndsTheirSessionsAndAudits() {
        when(teamRepository.findById(TENANT_ID, NURSE_MEMBERSHIP)).thenReturn(
                Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.ACTIVE)),
                Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.INACTIVE)));

        TeamMemberView view = service.deactivate(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        assertThat(view.status()).isEqualTo(MembershipStatus.INACTIVE);
        verify(teamRepository).updateStatus(NURSE_MEMBERSHIP, MembershipStatus.INACTIVE, OWNER_USER);
        verify(refreshTokenRepository).revokeAllForUserInTenant(TENANT_ID, NURSE_USER, NOW);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("TEAM_MEMBER_DEACTIVATED"), eq("MEMBERSHIP"), eq(NURSE_MEMBERSHIP),
                before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("status", "ACTIVE").containsEntry("mobile", "••••••7410");
        assertThat(after.getValue()).containsEntry("status", "INACTIVE");
    }

    @Test
    void deactivatingTheLastActiveOwnerIsRefused() {
        when(teamRepository.findById(TENANT_ID, OWNER_MEMBERSHIP)).thenReturn(Optional.of(
                row(OWNER_MEMBERSHIP, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.ACTIVE)));
        when(teamRepository.countOtherActiveOwners(TENANT_ID, OWNER_MEMBERSHIP)).thenReturn(0);

        assertThatThrownBy(() -> service.deactivate(TENANT_ID, OWNER_USER, OWNER_MEMBERSHIP))
                .isInstanceOfSatisfying(TeamConflictException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(TeamConflictException.Kind.LAST_OWNER));
        assertThatThrownBy(() -> service.remove(TENANT_ID, OWNER_USER, OWNER_MEMBERSHIP))
                .isInstanceOf(TeamConflictException.class);
        assertThatThrownBy(() -> service.update(TENANT_ID, OWNER_USER, OWNER_MEMBERSHIP,
                new TeamUpdateInput(null, MembershipRole.NURSE, null)))
                .isInstanceOf(TeamConflictException.class);
        verify(teamRepository, never()).updateStatus(any(), any(), any());
        verify(refreshTokenRepository, never()).revokeAllForUserInTenant(any(), any(), any());
    }

    @Test
    void anotherClinicsMemberLooksLikeItDoesNotExist() {
        when(teamRepository.findById(TENANT_ID, NURSE_MEMBERSHIP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.remove(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removingAccessKeepsTheRowAndEndsSessions() {
        nurseExists(MembershipStatus.INACTIVE);

        service.remove(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        verify(teamRepository).updateStatus(NURSE_MEMBERSHIP, MembershipStatus.REMOVED, OWNER_USER);
        verify(refreshTokenRepository).revokeAllForUserInTenant(TENANT_ID, NURSE_USER, NOW);
    }

    @Test
    void reactivatingCountsAgainstThePlanLimit() {
        nurseExists(MembershipStatus.INACTIVE);
        when(teamRepository.countActive(TENANT_ID)).thenReturn(PlanLimits.MAX_USERS);

        assertThatThrownBy(() -> service.reactivate(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP))
                .isInstanceOf(PlanLimitException.class);
        verify(teamRepository).lockTenant();
        verify(teamRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void reactivatingFromRemovedWorksWhenThereIsRoom() {
        when(teamRepository.findById(TENANT_ID, NURSE_MEMBERSHIP)).thenReturn(
                Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.REMOVED)),
                Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
        when(teamRepository.countActive(TENANT_ID)).thenReturn(2);

        TeamMemberView view = service.reactivate(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        assertThat(view.status()).isEqualTo(MembershipStatus.ACTIVE);
        verify(teamRepository).updateStatus(NURSE_MEMBERSHIP, MembershipStatus.ACTIVE, OWNER_USER);
    }

    @Test
    void renamingWritesTheClinicsOwnNameOnlyAndAuditsBeforeAndAfter() {
        when(teamRepository.findById(TENANT_ID, NURSE_MEMBERSHIP)).thenReturn(
                Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.ACTIVE)),
                Optional.of(row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha K", MembershipRole.NURSE, MembershipStatus.ACTIVE)));

        TeamMemberView view = service.update(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP,
                new TeamUpdateInput("Nisha K", null, null));

        assertThat(view.name()).isEqualTo("Nisha K");
        verify(teamRepository).update(NURSE_MEMBERSHIP, null, "Nisha K", OWNER_USER);
        verify(appUserRepository, never()).insert(any(), any(), any());
        verify(auditLog).record(eq("TEAM_MEMBER_UPDATED"), eq("MEMBERSHIP"), eq(NURSE_MEMBERSHIP), anyMap(),
                anyMap());
    }

    @Test
    void anUpdateWithNothingNewWritesNothingAndAuditsNothing() {
        nurseExists(MembershipStatus.ACTIVE);

        service.update(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP, new TeamUpdateInput("Nisha", null, null));

        verify(teamRepository, never()).update(any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anUpdateKeepsDoctorFieldsThatWereNotSent() {
        UUID doctorMembership = UUID.randomUUID();
        when(teamRepository.findById(TENANT_ID, doctorMembership)).thenReturn(Optional.of(new TeamRow(
                doctorMembership, UUID.randomUUID(), "Dr Ravi", "9123456780", MembershipRole.DOCTOR,
                MembershipStatus.ACTIVE, null, true, "General medicine", "MBBS", null, 30000L)));

        service.update(TENANT_ID, OWNER_USER, doctorMembership,
                new TeamUpdateInput(null, null, new DoctorInput(null, null, "MH-2026-1", null)));

        verify(doctorProfileRepository).insert(doctorMembership, TENANT_ID, "General medicine", 30000L, "MBBS",
                "MH-2026-1");
    }

    // ---- SAH-31: staff PIN on the team list, reset, removal (ADR-010 D8, D12) ----

    private static StaffPinRow pin(UUID membershipId, String hash, Instant lockedUntil, String reason) {
        return new StaffPinRow(membershipId, hash, 1, 0, lockedUntil, lockedUntil == null ? 0 : 1,
                lockedUntil == null ? null : NOW.minusSeconds(60), hash == null ? null : NOW.minusSeconds(3600),
                reason, null, 0L, NOW);
    }

    private void twoMembers() {
        when(teamRepository.findAll(TENANT_ID)).thenReturn(List.of(
                row(OWNER_MEMBERSHIP, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.ACTIVE),
                row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
    }

    @Test
    void theOwnerSeesThePinStateOfEveryRowAndNeverTheHash() {
        twoMembers();
        when(staffPinRepository.findAll(TENANT_ID)).thenReturn(List.of(
                pin(OWNER_MEMBERSHIP, "$2a$12$" + "a".repeat(53), null, null),
                pin(NURSE_MEMBERSHIP, "$2a$12$" + "b".repeat(53), NOW.plusSeconds(600), null)));

        List<TeamMemberView> team = service.list(TENANT_ID, OWNER_USER);

        assertThat(team.get(0).pin().state()).isEqualTo(PinState.SET);
        assertThat(team.get(0).pin().setAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(team.get(0).pin().lockedUntil()).isNull();
        assertThat(team.get(1).pin().state()).isEqualTo(PinState.LOCKED);
        assertThat(team.get(1).pin().lockedUntil()).isEqualTo(NOW.plusSeconds(600));
        assertThat(team.toString()).doesNotContain("$2a$");
    }

    @Test
    void aPersonWithNoRowOrAnOffRowIsNotSet() {
        twoMembers();
        when(staffPinRepository.findAll(TENANT_ID)).thenReturn(List.of(
                pin(NURSE_MEMBERSHIP, null, null, "RESET_BY_OWNER")));

        List<TeamMemberView> team = service.list(TENANT_ID, OWNER_USER);

        assertThat(team.get(0).pin()).isEqualTo(new TeamMemberView.PinView(PinState.NOT_SET, null, null));
        assertThat(team.get(1).pin()).isEqualTo(new TeamMemberView.PinView(PinState.NOT_SET, null, null));
    }

    @Test
    void aLockInThePastIsJustSet() {
        twoMembers();
        when(staffPinRepository.findAll(TENANT_ID)).thenReturn(List.of(
                pin(NURSE_MEMBERSHIP, "$2a$12$" + "b".repeat(53), NOW.minusSeconds(5), null)));

        TeamMemberView nurse = service.list(TENANT_ID, OWNER_USER).get(1);

        assertThat(nurse.pin().state()).isEqualTo(PinState.SET);
        assertThat(nurse.pin().lockedUntil()).isNull();
    }

    @Test
    void anyoneElseSeesOnlyTheirOwnPinRow() {
        twoMembers();
        when(staffPinRepository.findAll(TENANT_ID)).thenReturn(List.of(
                pin(OWNER_MEMBERSHIP, "$2a$12$" + "a".repeat(53), NOW.plusSeconds(600), null),
                pin(NURSE_MEMBERSHIP, "$2a$12$" + "b".repeat(53), null, null)));

        List<TeamMemberView> team = service.list(TENANT_ID, NURSE_USER);

        assertThat(team.get(0).pin()).as("the owner's lock is not shown to a nurse").isNull();
        assertThat(team.get(1).pin().state()).isEqualTo(PinState.SET);
    }

    @Test
    void aViewerWhoIsNotAnOwnerInTheDatabaseNeverSeesAnotherRowsPin() {
        when(teamRepository.findAll(TENANT_ID)).thenReturn(List.of(
                row(OWNER_MEMBERSHIP, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.ACTIVE),
                row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.DOCTOR, MembershipStatus.ACTIVE)));

        assertThat(service.list(TENANT_ID, NURSE_USER).get(0).pin()).isNull();
    }

    @Test
    void theOwnerResetsAPinAndGetsTheMemberBackAsNotSet() {
        nurseExists(MembershipStatus.ACTIVE);
        when(staffPinOps.clear(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP)).thenReturn(true);
        when(staffPinRepository.find(TENANT_ID, NURSE_MEMBERSHIP, false))
                .thenReturn(Optional.of(pin(NURSE_MEMBERSHIP, null, null, "RESET_BY_OWNER")));

        TeamMemberView view = service.resetPin(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        verify(staffPinOps).clear(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);
        assertThat(view.id()).isEqualTo(NURSE_MEMBERSHIP);
        assertThat(view.pin().state()).isEqualTo(PinState.NOT_SET);
        assertThat(view.mobileMasked()).isEqualTo("••••••7410");
    }

    @Test
    void aResetOfAMemberWithNoPinStillAnswersTheMember() {
        nurseExists(MembershipStatus.INACTIVE);
        when(staffPinOps.clear(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP)).thenReturn(false);

        TeamMemberView view = service.resetPin(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        assertThat(view.pin().state()).isEqualTo(PinState.NOT_SET);
    }

    @Test
    void onlyTheOwnerMayResetAPinAndTheRoleIsReadAgain() {
        when(teamRepository.findByUser(TENANT_ID, NURSE_USER)).thenReturn(Optional.of(
                row(NURSE_MEMBERSHIP, NURSE_USER, "Nisha", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
        when(teamRepository.findByUser(TENANT_ID, OWNER_USER)).thenReturn(Optional.of(
                row(OWNER_MEMBERSHIP, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.INACTIVE)));

        for (UUID actor : new UUID[] {NURSE_USER, OWNER_USER, UUID.randomUUID()}) {
            assertThatThrownBy(() -> service.resetPin(TENANT_ID, actor, NURSE_MEMBERSHIP))
                    .isInstanceOfSatisfying(ForbiddenException.class, ex -> {
                        assertThat(ex.problemType()).isEqualTo("forbidden");
                        assertThat(ex.getMessage()).isEqualTo("Only the clinic owner can do this.");
                    });
        }
        verify(staffPinOps, never()).clear(any(), any(), any());
    }

    @Test
    void thirtyOneResetsAnHourAnswerRateLimit() {
        nurseExists(MembershipStatus.ACTIVE);
        when(staffPinLimits.resetWait(TENANT_ID, OWNER_USER)).thenReturn(120L);

        assertThatThrownBy(() -> service.resetPin(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP))
                .isInstanceOfSatisfying(StaffPinException.class, ex -> {
                    assertThat(ex.slug()).isEqualTo("rate-limit");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(120L);
                });
        verify(staffPinOps, never()).clear(any(), any(), any());
    }

    @Test
    void aMemberOfAnotherClinicCannotBeReset() {
        when(teamRepository.findById(TENANT_ID, NURSE_MEMBERSHIP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPin(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP))
                .isInstanceOf(NotFoundException.class);
        verify(staffPinOps, never()).clear(any(), any(), any());
        verifyNoInteractions(staffPinLimits);
    }

    @Test
    void removingAccessClearsThePinInTheSameTransaction() {
        nurseExists(MembershipStatus.ACTIVE);

        service.remove(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        verify(staffPinOps).clear(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);
    }

    @Test
    void switchingSomeoneOffKeepsTheirPin() {
        nurseExists(MembershipStatus.ACTIVE);

        service.deactivate(TENANT_ID, OWNER_USER, NURSE_MEMBERSHIP);

        verify(staffPinOps, never()).clear(any(), any(), any());
    }

    @Test
    void theDatabaseBackstopsBecomeCleanConflicts() {
        assertThat(TeamService.translate(new DataIntegrityViolationException("x", new SQLException("dup", "23505"))))
                .isInstanceOfSatisfying(TeamConflictException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(TeamConflictException.Kind.DUPLICATE_MOBILE));
        assertThat(TeamService.translate(new DataIntegrityViolationException("x",
                new SQLException("ERROR: last_owner: a clinic must keep at least one active owner", "23514"))))
                .isInstanceOfSatisfying(TeamConflictException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(TeamConflictException.Kind.LAST_OWNER));
        assertThat(TeamService.translate(new org.springframework.dao.CannotAcquireLockException("deadlock",
                new SQLException("deadlock detected", "40P01"))))
                .isInstanceOfSatisfying(TeamConflictException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(TeamConflictException.Kind.CONCURRENT_CHANGE));
        DataIntegrityViolationException other = new DataIntegrityViolationException("x",
                new SQLException("other check", "23514"));
        assertThat(TeamService.translate(other)).isSameAs(other);
    }
}
