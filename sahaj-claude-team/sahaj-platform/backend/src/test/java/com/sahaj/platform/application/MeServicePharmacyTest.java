package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.PharmacyAccessPort;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.SetupProgress;
import com.sahaj.platform.domain.TenantProfile;
import com.sahaj.platform.domain.UserPreference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SAH-78 (ADR-028 D11, table row 7): GET /me asks the clinic's pharmacy switch and, only for a role that is neither OWNER
 * nor PHARMACIST, the pharmacy module's port. With the switch off the port is never asked and the menu is as before.
 */
class MeServicePharmacyTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

    private TeamRepository teamRepository;
    private ModuleEntitlementRepository entitlements;
    private PharmacyAccessPort port;
    private ObjectProvider<PharmacyAccessPort> provider;
    private MeService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        ClinicProfileRepository clinicProfileRepository = mock(ClinicProfileRepository.class);
        SetupProgressRepository setupProgressRepository = mock(SetupProgressRepository.class);
        UserPreferenceRepository userPreferenceRepository = mock(UserPreferenceRepository.class);
        entitlements = mock(ModuleEntitlementRepository.class);
        port = mock(PharmacyAccessPort.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(port);
        when(entitlements.find(TENANT_ID)).thenReturn(Optional.empty());
        service = new MeService(teamRepository, tenantRepository, clinicProfileRepository, setupProgressRepository,
                userPreferenceRepository, entitlements, Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC),
                provider);
        when(tenantRepository.findProfile(TENANT_ID)).thenReturn(Optional.of(new TenantProfile("Test Clinic", "teal",
                "FREE", Instant.parse("2026-12-17T10:00:00Z"))));
        when(setupProgressRepository.find(TENANT_ID)).thenReturn(Optional.of(new SetupProgress(5, Map.of(), Instant.now())));
        when(userPreferenceRepository.find(TENANT_ID, USER_ID)).thenReturn(Optional.of(UserPreference.defaults()));
    }

    /** The clinic's entitlement row, with the Pharmacy and Inventory switch as the same row carries it (code review F-6). */
    private void pharmacySwitch(boolean on) {
        when(entitlements.find(TENANT_ID)).thenReturn(Optional.of(new ModuleEntitlementRepository.EntitlementRow("off",
                null, false, 25, null, on)));
    }

    private void signedInAs(MembershipRole role) {
        when(teamRepository.findByUser(TENANT_ID, USER_ID)).thenReturn(Optional.of(new TeamRepository.TeamRow(
                MEMBERSHIP_ID, USER_ID, "Test Person", "9123456789", role, MembershipStatus.ACTIVE, null, false, null,
                null, null, 0L)));
    }

    @Test
    void withTheSwitchOffThePortIsNeverAskedAndThereIsNoPharmacyEntry() {
        pharmacySwitch(false);
        for (MembershipRole role : MembershipRole.values()) {
            signedInAs(role);
            assertThat(service.getMe(TENANT_ID, USER_ID, role.name()).menu()).as(role.name()).doesNotContain("pharmacy");
        }
        verifyNoInteractions(port);
    }

    /** Code review F-6: /me reads the switch with the entitlement row, in one statement, with the flag On or Off. */
    @Test
    void theSwitchIsReadWithTheEntitlementRowInOneStatementWhateverItsValue() {
        for (boolean on : new boolean[] {false, true}) {
            org.mockito.Mockito.clearInvocations(entitlements);
            pharmacySwitch(on);
            signedInAs(MembershipRole.OWNER);

            service.getMe(TENANT_ID, USER_ID, "OWNER");

            verify(entitlements, org.mockito.Mockito.times(1)).find(TENANT_ID);
            verify(entitlements, org.mockito.Mockito.never()).pharmacyOn(any());
            verifyNoMoreInteractions(entitlements);
        }
    }

    @Test
    void aClinicWithNoEntitlementRowHasPharmacyOffAndOneStatement() {
        when(entitlements.find(TENANT_ID)).thenReturn(Optional.empty());
        signedInAs(MembershipRole.OWNER);

        assertThat(service.getMe(TENANT_ID, USER_ID, "OWNER").menu()).doesNotContain("pharmacy");
        verify(entitlements, org.mockito.Mockito.times(1)).find(TENANT_ID);
        verify(entitlements, org.mockito.Mockito.never()).pharmacyOn(any());
    }

    @Test
    void anOwnerAndAPharmacistGetTheEntryWithoutAskingThePort() {
        pharmacySwitch(true);
        signedInAs(MembershipRole.OWNER);
        assertThat(service.getMe(TENANT_ID, USER_ID, "OWNER").menu()).contains("pharmacy");
        signedInAs(MembershipRole.PHARMACIST);
        assertThat(service.getMe(TENANT_ID, USER_ID, "PHARMACIST").menu()).contains("pharmacy");
        verifyNoInteractions(port);
    }

    @Test
    void aMappedNurseGetsTheEntryAndAnUnmappedOneDoesNot() {
        pharmacySwitch(true);
        signedInAs(MembershipRole.NURSE);
        when(port.hasActiveMapping(TENANT_ID, MEMBERSHIP_ID)).thenReturn(true);
        assertThat(service.getMe(TENANT_ID, USER_ID, "NURSE").menu()).endsWith("pharmacy");

        when(port.hasActiveMapping(TENANT_ID, MEMBERSHIP_ID)).thenReturn(false);
        assertThat(service.getMe(TENANT_ID, USER_ID, "NURSE").menu()).doesNotContain("pharmacy");
        verify(port, org.mockito.Mockito.times(2)).hasActiveMapping(TENANT_ID, MEMBERSHIP_ID);
    }

    @Test
    void aStoreStaffMemberSeesOnlyPharmacyWhenMappedAndAnEmptyMenuWhenNot() {
        pharmacySwitch(true);
        signedInAs(MembershipRole.STORE_STAFF);
        when(port.hasActiveMapping(TENANT_ID, MEMBERSHIP_ID)).thenReturn(true);
        assertThat(service.getMe(TENANT_ID, USER_ID, "STORE_STAFF").menu()).containsExactly("pharmacy");

        when(port.hasActiveMapping(TENANT_ID, MEMBERSHIP_ID)).thenReturn(false);
        assertThat(service.getMe(TENANT_ID, USER_ID, "STORE_STAFF").menu()).isEmpty();
    }

    @Test
    void aSwitchedOnClinicWithNoPharmacyModuleWiredShowsNoEntryToAMappedRole() {
        // the constructor of before SAH-78: no port provider at all
        TeamRepository team = teamRepository;
        MeService legacy = new MeService(team, mock(TenantRepository.class), mock(ClinicProfileRepository.class),
                mock(SetupProgressRepository.class), mock(UserPreferenceRepository.class), entitlements,
                Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC));
        assertThat(legacy).isNotNull();
        // and the provider-less path of the main constructor answers "nobody is mapped"
        when(provider.getIfAvailable(any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        pharmacySwitch(true);
        signedInAs(MembershipRole.NURSE);
        assertThat(service.getMe(TENANT_ID, USER_ID, "NURSE").menu()).doesNotContain("pharmacy");
    }
}
