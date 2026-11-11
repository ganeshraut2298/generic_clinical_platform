package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.PatientScope.Kind;
import com.sahaj.ipd.application.WardScopeRepository.Facts;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-34 AC 1 to 7, 12, 13, 24: the guard builds the scope from the signed-in user, the switch, the live wards and the live
 * grants only; it never falls back to "all"; it refuses by-id reads and (under the lock) writes; a refusal reads and
 * writes nothing else.
 */
class ScopeGuardTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID WARD_MINE = UUID.randomUUID();
    private static final UUID WARD_OTHER = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Instant ENDS = Instant.parse("2026-11-10T06:30:00Z");

    private TeamMemberPort team;
    private WardScopeRepository repository;
    private ScopeGuard guard;

    @BeforeEach
    void setUp() {
        team = mock(TeamMemberPort.class);
        repository = mock(WardScopeRepository.class);
        guard = new ScopeGuard(team, repository);
        member("NURSE", true);
    }

    private void member(String role, boolean active) {
        when(team.findActiveByUserId(TENANT, USER))
                .thenReturn(Optional.of(new TeamMember(MEMBERSHIP, "Member", role, active, 0L)));
    }

    private Facts facts(UUID ward, String status) {
        return new Facts(ADMISSION, UUID.randomUUID(), UUID.randomUUID(), "B1", ward, DOCTOR, status);
    }

    // ---- building the scope ----

    @Test
    void anUnscopedRoleWithAKnownMembershipRoleCostsNoRead() {
        for (String role : new String[] {"OWNER", "DOCTOR", "FRONT_DESK", "BILLING", "PHARMACIST"}) {
            PatientScope scope = guard.scope(TENANT, USER, role, role);
            assertThat(scope.isAll()).as(role).isTrue();
        }
        verifyNoInteractions(team, repository);
    }

    @Test
    void aTokenOnlyEndpointReadsTheMembershipOnceAndStaysAllForAnOwner() {
        member("OWNER", true);

        PatientScope scope = guard.scope(TENANT, USER, "OWNER", null);

        assertThat(scope.isAll()).isTrue();
        verify(team).findActiveByUserId(TENANT, USER);
        verifyNoInteractions(repository);
    }

    @Test
    void anOwnerTokenWhoseMembershipIsNowANurseIsScopedAsANurse() {
        member("NURSE", true);
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of());

        PatientScope scope = guard.scope(TENANT, USER, "OWNER", null);

        assertThat(scope.kind()).isEqualTo(Kind.WARDS);
        assertThat(scope.memberRole()).isEqualTo("NURSE");
    }

    @Test
    void aNurseWithTheSwitchOffSeesAllButKeepsHerWardsForMine() {
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(false);

        PatientScope scope = guard.scope(TENANT, USER, "NURSE", "NURSE");

        assertThat(scope.isAll()).isTrue();
        assertThat(scope.wardIds()).containsExactly(WARD_MINE);
        assertThat(scope.hasWard()).isTrue();
        assertThat(scope.membershipId()).isEqualTo(MEMBERSHIP);
        verify(repository, never()).liveGrants(any(), any(), any());
    }

    @Test
    void aNurseWithTheSwitchOnAndWardsIsScopedToThemAndHerLiveGrants() {
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of(ADMISSION, ENDS));

        PatientScope scope = guard.scope(TENANT, USER, "NURSE", "NURSE");

        assertThat(scope.kind()).isEqualTo(Kind.WARDS);
        assertThat(scope.wardIds()).containsExactly(WARD_MINE);
        assertThat(scope.grants()).containsEntry(ADMISSION, ENDS);
    }

    @Test
    void aNurseWithTheSwitchOnAndNoWardSeesNobodyAndNeverEverybody() {
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of());
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of());

        PatientScope scope = guard.scope(TENANT, USER, "NURSE", "NURSE");

        assertThat(scope.kind()).isEqualTo(Kind.NONE);
        assertThat(scope.allows(WARD_MINE, DOCTOR, ADMISSION)).isFalse();
    }

    @Test
    void aVisitingDoctorIsScopedToHerOwnPatients() {
        member("VISITING_DOCTOR", true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "VISITING_DOCTOR")).thenReturn(Map.of());

        PatientScope scope = guard.scope(TENANT, USER, "VISITING_DOCTOR", "VISITING_DOCTOR");

        assertThat(scope.kind()).isEqualTo(Kind.OWN);
        assertThat(scope.membershipId()).isEqualTo(MEMBERSHIP);
        assertThat(scope.allows(WARD_MINE, MEMBERSHIP, ADMISSION)).isTrue();
        assertThat(scope.allows(WARD_MINE, DOCTOR, ADMISSION)).isFalse();
    }

    @Test
    void aNurseWithNoActiveMembershipFailsInsteadOfFallingBackToAll() {
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.scope(TENANT, USER, "NURSE", "NURSE")).isInstanceOf(AccessDeniedException.class);

        member("NURSE", false);
        assertThatThrownBy(() -> guard.scope(TENANT, USER, "NURSE", null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnreadableSettingFailsTheRequestAndNeverFallsBackToAll() {
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenThrow(new IllegalStateException("db"));

        assertThatThrownBy(() -> guard.scope(TENANT, USER, "NURSE", "NURSE")).isInstanceOf(IllegalStateException.class)
                .hasMessage("The patient scope could not be built");
    }

    @Test
    void unreadableAssignmentsAndGrantsFailTheRequestToo() {
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> guard.scope(TENANT, USER, "NURSE", "NURSE")).isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.reset(repository);
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> guard.scope(TENANT, USER, "NURSE", "NURSE")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMembershipLessOwnerTokenKeepsAllLikeBeforeThisStory() {
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(guard.scope(TENANT, USER, "OWNER", null).isAll()).isTrue();
    }

    // ---- the lists ----

    @Test
    void visibleAdmissionIdsIsNullForAllAndTheScopedSetOtherwise() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID granted = UUID.randomUUID();
        when(repository.findAdmittedFacts(TENANT)).thenReturn(List.of(
                new Facts(mine, UUID.randomUUID(), UUID.randomUUID(), "A1", WARD_MINE, DOCTOR, "ADMITTED"),
                new Facts(other, UUID.randomUUID(), UUID.randomUUID(), "B1", WARD_OTHER, DOCTOR, "ADMITTED"),
                new Facts(granted, UUID.randomUUID(), UUID.randomUUID(), "C1", WARD_OTHER, DOCTOR, "ADMITTED")));
        PatientScope scope = new PatientScope(Kind.WARDS, USER, "NURSE", "NURSE", MEMBERSHIP, Set.of(WARD_MINE),
                Map.of(granted, ENDS));

        assertThat(guard.visibleAdmissionIds(TENANT, scope)).containsExactlyInAnyOrder(mine, granted);
        assertThat(guard.visibleAdmissionIds(TENANT, PatientScope.all())).isNull();
    }

    // ---- by-id reads ----

    private PatientScope scoped(Map<UUID, Instant> grants) {
        return new PatientScope(Kind.WARDS, USER, "NURSE", "NURSE", MEMBERSHIP, Set.of(WARD_MINE), grants);
    }

    @Test
    void allReadsNothingAndRefusesNothing() {
        guard.requireAdmission(TENANT, PatientScope.all(), ADMISSION, "route");

        verifyNoInteractions(repository);
    }

    @Test
    void aPatientInTheNursesWardIsReadable() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_MINE, "ADMITTED")));

        guard.requireAdmission(TENANT, scoped(Map.of()), ADMISSION, "route");
    }

    @Test
    void aPatientInAnotherWardIsRefusedWithTheFixedProblem() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "ADMITTED")));

        assertThatThrownBy(() -> guard.requireAdmission(TENANT, scoped(Map.of()), ADMISSION, "route"))
                .isInstanceOf(OutsideScopeException.class)
                .hasMessage("This patient is not in your wards.")
                .satisfies(e -> {
                    OutsideScopeException refused = (OutsideScopeException) e;
                    assertThat(refused.problemType()).isEqualTo("outside-scope");
                    assertThat(refused.title()).isEqualTo("Outside your scope");
                });
    }

    @Test
    void aDischargedPatientIsInScopeByHerLastBedAndNeverByAGrant() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_MINE, "DISCHARGED")));
        guard.requireAdmission(TENANT, scoped(Map.of()), ADMISSION, "route");

        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "DISCHARGED")));
        assertThatThrownBy(() -> guard.requireAdmission(TENANT, scoped(Map.of(ADMISSION, ENDS)), ADMISSION, "route"))
                .isInstanceOf(OutsideScopeException.class);
    }

    @Test
    void aLiveGrantOpensAnAdmittedPatientOutsideHerWards() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "ADMITTED")));

        guard.requireAdmission(TENANT, scoped(Map.of(ADMISSION, ENDS)), ADMISSION, "route");
    }

    @Test
    void anAdmissionThatDoesNotExistIsA404NotAnOutsideScope() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireAdmission(TENANT, scoped(Map.of()), ADMISSION, "route"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theByIdReadBuildsTheScopeFromTheSignedInUser() {
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of());
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "ADMITTED")));

        assertThatThrownBy(() -> guard.requireAdmission(TENANT, USER, "NURSE", "NURSE", ADMISSION, "route"))
                .isInstanceOf(OutsideScopeException.class);
    }

    // ---- writes under the lock ----

    @Test
    void aWriteReadsTheGrantAgainOnTheDatabaseClockAndIgnoresTheCachedOne() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "ADMITTED")));
        when(repository.hasLiveGrant(TENANT, MEMBERSHIP, "NURSE", ADMISSION)).thenReturn(false);
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        // The scope built at the start of the request still holds the grant; it expired before the lock was taken.
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of(ADMISSION, ENDS));

        assertThatThrownBy(() -> guard.requireAdmissionLocked(TENANT, USER, "NURSE", "NURSE", ADMISSION, "route"))
                .isInstanceOf(OutsideScopeException.class);
        verify(repository).hasLiveGrant(TENANT, MEMBERSHIP, "NURSE", ADMISSION);
    }

    @Test
    void aWriteIsAllowedWhileTheGrantIsStillLiveUnderTheLock() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "ADMITTED")));
        when(repository.hasLiveGrant(TENANT, MEMBERSHIP, "NURSE", ADMISSION)).thenReturn(true);
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of(ADMISSION, ENDS));

        guard.requireAdmissionLocked(TENANT, USER, "NURSE", "NURSE", ADMISSION, "route");
    }

    @Test
    void aWriteNeverUsesAGrantOnAnAdmissionThatIsNoLongerAdmitted() {
        when(repository.findFacts(TENANT, ADMISSION)).thenReturn(Optional.of(facts(WARD_OTHER, "DISCHARGED")));
        when(repository.liveWardIds(TENANT, MEMBERSHIP)).thenReturn(Set.of(WARD_MINE));
        when(repository.nurseWardScope(TENANT)).thenReturn(true);
        when(repository.liveGrants(TENANT, MEMBERSHIP, "NURSE")).thenReturn(Map.of());

        assertThatThrownBy(() -> guard.requireAdmissionLocked(TENANT, USER, "NURSE", "NURSE", ADMISSION, "route"))
                .isInstanceOf(OutsideScopeException.class);
        verify(repository, never()).hasLiveGrant(eq(TENANT), any(), any(), any());
    }

    @Test
    void aWriteByAnOwnerReadsNoPlacement() {
        guard.requireAdmissionLocked(TENANT, USER, "OWNER", "OWNER", ADMISSION, "route");

        verifyNoInteractions(repository);
    }
}
