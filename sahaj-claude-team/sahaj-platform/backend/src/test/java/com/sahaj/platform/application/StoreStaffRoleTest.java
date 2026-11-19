package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.PlanLimits;
import com.sahaj.platform.api.RoleLabels;
import com.sahaj.platform.application.RolePermissionRepository.Cell;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78 (ADR-028 D4, platform.yaml 0.2.0): the Store staff team role, as the platform sees it. It is a normal role value
 * with a label, it counts toward the plan user limit like every member, GET /role-permissions never lists it and a PUT row
 * for it is refused 400. The existing role and ModuleEntitlement shapes do not change.
 */
class StoreStaffRoleTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    private static TeamRow ownerRow() {
        return new TeamRow(UUID.randomUUID(), OWNER_USER, "Test Owner", "9876547410", MembershipRole.OWNER,
                MembershipStatus.ACTIVE, null, false, null, null, null, 0L);
    }

    // ---- the value and its label ----

    @Test
    void storeStaffIsARoleWithTheLabelStoreStaffAndTheOldRolesAreUnchanged() {
        assertThat(MembershipRole.valueOf("STORE_STAFF")).isEqualTo(MembershipRole.STORE_STAFF);
        assertThat(RoleLabels.of("STORE_STAFF")).isEqualTo("Store staff");
        assertThat(Arrays.stream(MembershipRole.values()).map(Enum::name).limit(8)).containsExactly("OWNER", "DOCTOR",
                "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR");
        assertThat(RoleLabels.of("FRONT_DESK")).isEqualTo("Front desk");
        assertThat(RoleLabels.of("PHARMACIST")).isEqualTo("Pharmacist");
    }

    @Test
    void everyRoleHasAPlainEnglishLabelNotItsConstantName() {
        for (MembershipRole role : MembershipRole.values()) {
            assertThat(RoleLabels.of(role.name())).as(role.name()).isNotEqualTo(role.name());
        }
    }

    // ---- the plan user limit ----

    private TeamService teamService(TeamRepository teamRepository, AppUserRepository appUserRepository) {
        return new TeamService(teamRepository, appUserRepository, mock(DoctorProfileRepository.class),
                mock(RefreshTokenRepository.class), mock(AuditLog.class), mock(StaffPinRepository.class),
                mock(StaffPinOps.class), mock(StaffPinLimits.class), mock(StaffPinColleagueEventRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aStoreStaffMemberCountsAgainstThePlanUserLimitLikeEveryone() {
        TeamRepository teamRepository = mock(TeamRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        when(teamRepository.findByUser(TENANT_ID, OWNER_USER)).thenReturn(Optional.of(ownerRow()));
        when(appUserRepository.findByMobile("9123456780")).thenReturn(Optional.empty());
        when(teamRepository.countActive(TENANT_ID)).thenReturn(PlanLimits.MAX_USERS);

        assertThatThrownBy(() -> teamService(teamRepository, appUserRepository).add(TENANT_ID, OWNER_USER,
                new TeamAddInput("Test Store Staff", "9123456780", MembershipRole.STORE_STAFF, null)))
                .isInstanceOf(PlanLimitException.class);
        verify(teamRepository, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void aStoreStaffMemberCanBeAddedBelowTheLimitWithNoDoctorProfile() {
        TeamRepository teamRepository = mock(TeamRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        DoctorProfileRepository doctors = mock(DoctorProfileRepository.class);
        when(teamRepository.findByUser(TENANT_ID, OWNER_USER)).thenReturn(Optional.of(ownerRow()));
        when(appUserRepository.findByMobile("9123456780")).thenReturn(Optional.of(
                new AppUser(UUID.randomUUID(), "9123456780", "Test Store Staff")));
        when(teamRepository.countActive(TENANT_ID)).thenReturn(1);
        when(teamRepository.findById(any(), any())).thenAnswer(call -> Optional.of(new TeamRow(call.getArgument(1),
                UUID.randomUUID(), "Test Store Staff", "9123456780", MembershipRole.STORE_STAFF, MembershipStatus.ACTIVE,
                null, false, null, null, null, 0L)));
        TeamService service = new TeamService(teamRepository, appUserRepository, doctors,
                mock(RefreshTokenRepository.class), mock(AuditLog.class), mock(StaffPinRepository.class),
                mock(StaffPinOps.class), mock(StaffPinLimits.class), mock(StaffPinColleagueEventRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TeamMemberView added = service.add(TENANT_ID, OWNER_USER,
                new TeamAddInput("Test Store Staff", "9123456780", MembershipRole.STORE_STAFF, null));

        assertThat(added.role()).isEqualTo(MembershipRole.STORE_STAFF);
        verify(teamRepository).insert(any(), any(), org.mockito.ArgumentMatchers.eq(MembershipRole.STORE_STAFF), any(),
                any());
        org.mockito.Mockito.verifyNoInteractions(doctors);
    }

    // ---- Who can do what ----

    private static RolePermissionService permissions(RolePermissionRepository repository) {
        TeamRepository teamRepository = mock(TeamRepository.class);
        when(teamRepository.findByUser(TENANT_ID, OWNER_USER)).thenReturn(Optional.of(ownerRow()));
        return new RolePermissionService(repository, teamRepository, mock(AuditLog.class));
    }

    @Test
    void theMatrixNeverListsStoreStaffEvenIfADataRowExisted() {
        RolePermissionRepository repository = mock(RolePermissionRepository.class);
        when(repository.effective()).thenReturn(List.of(new Cell(MembershipRole.NURSE, "token", true),
                new Cell(MembershipRole.STORE_STAFF, "token", true)));

        assertThat(permissions(repository).get()).extracting(Cell::role).containsExactly(MembershipRole.NURSE);
    }

    @Test
    void aPutRowForStoreStaffIsRefusedAndNothingIsWritten() {
        RolePermissionRepository repository = mock(RolePermissionRepository.class);
        when(repository.effective()).thenReturn(List.of(new Cell(MembershipRole.NURSE, "token", true)));

        assertThatThrownBy(() -> permissions(repository).save(TENANT_ID, OWNER_USER,
                List.of(new Cell(MembershipRole.NURSE, "token", true), new Cell(MembershipRole.STORE_STAFF, "token", true))))
                .isInstanceOf(TeamInputInvalidException.class)
                .hasMessageContaining("Store staff");
        verify(repository, never()).upsert(any(), any(), anyBoolean(), any());
    }

    // ---- the entitlement port ----

    @Test
    void theModuleEntitlementRecordIsUnchangedAndThePortDefaultsToPharmacyOff() {
        assertThat(ModuleEntitlementPort.ModuleEntitlement.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("ipdState", "hraOn", "bedLimit");
        ModuleEntitlementPort legacy = tenant -> new ModuleEntitlementPort.ModuleEntitlement("off", false, 25);
        assertThat(legacy.pharmacyOn(TENANT_ID)).isFalse();
    }

    @Test
    void theAdapterAnswersPharmacyOnFromTheClinicsRowAndFalseWithoutOne() {
        ModuleEntitlementRepository repository = mock(ModuleEntitlementRepository.class);
        ModuleEntitlementAdapter adapter = new ModuleEntitlementAdapter(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.pharmacyOn(TENANT_ID)).thenReturn(true);
        assertThat(adapter.pharmacyOn(TENANT_ID)).isTrue();
        UUID other = UUID.randomUUID();
        when(repository.pharmacyOn(other)).thenReturn(false);
        assertThat(adapter.pharmacyOn(other)).isFalse();
        // forTenant is untouched: it still answers from the five-column row
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());
        assertThat(adapter.forTenant(TENANT_ID).ipdState()).isEqualTo("off");
    }
}
