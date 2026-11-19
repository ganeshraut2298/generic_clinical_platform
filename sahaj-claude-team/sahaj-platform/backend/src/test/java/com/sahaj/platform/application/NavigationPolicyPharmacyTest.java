package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.application.NavigationPolicy.ModuleFlags;
import com.sahaj.platform.domain.MembershipRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-78 (ADR-028 D11, platform.yaml 0.2.0): the menu key {@code pharmacy}. With the switch off every menu is exactly as
 * before; with it on the key is for OWNER, PHARMACIST and a member with an active store mapping; it sits before staff and
 * config; STORE_STAFF has no other key.
 */
class NavigationPolicyPharmacyTest {

    private static final ModuleFlags OFF = new ModuleFlags(false, false, false);
    private static final ModuleFlags ON_NOT_MAPPED = new ModuleFlags(false, false, false, true, false);
    private static final ModuleFlags ON_MAPPED = new ModuleFlags(false, false, false, true, true);

    @Test
    void theThreeArgumentFlagsLeaveThePharmacySwitchOffAndMappingFalse() {
        ModuleFlags flags = new ModuleFlags(true, true, false);
        assertThat(flags.pharmacyOn()).isFalse();
        assertThat(flags.pharmacyMapped()).isFalse();
    }

    @Test
    void withTheSwitchOffNoRoleSeesPharmacyEvenWhenMappedAndEveryMenuIsUnchanged() {
        ModuleFlags offButMapped = new ModuleFlags(false, false, false, false, true);
        for (MembershipRole role : MembershipRole.values()) {
            for (String mode : new String[] {"advanced", "simple"}) {
                assertThat(NavigationPolicy.menuFor(role, mode, offButMapped, true))
                        .as(role + " " + mode).doesNotContain("pharmacy")
                        .isEqualTo(NavigationPolicy.menuFor(role, mode, OFF, true));
            }
        }
    }

    @Test
    void withTheSwitchOffAStoreStaffMenuIsEmpty() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, "advanced", OFF, false)).isEmpty();
        assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, "simple", OFF, false)).isEmpty();
        assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, "advanced")).isEmpty();
    }

    @Test
    void ownerAndPharmacistSeeTheEntryWithoutAMapping() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", ON_NOT_MAPPED, false)).contains("pharmacy");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", ON_NOT_MAPPED, false))
                .contains("pharmacy");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "simple", ON_NOT_MAPPED, false))
                .contains("pharmacy");
    }

    @Test
    void everyOtherRoleNeedsAnActiveMapping() {
        for (MembershipRole role : MembershipRole.values()) {
            if (role == MembershipRole.OWNER || role == MembershipRole.PHARMACIST) {
                continue;
            }
            for (String mode : new String[] {"advanced", "simple"}) {
                assertThat(NavigationPolicy.menuFor(role, mode, ON_NOT_MAPPED, false)).as(role + " " + mode + " unmapped")
                        .doesNotContain("pharmacy");
                assertThat(NavigationPolicy.menuFor(role, mode, ON_MAPPED, false)).as(role + " " + mode + " mapped")
                        .contains("pharmacy");
            }
        }
    }

    @Test
    void aStoreStaffMenuIsOnlyPharmacyAndOnlyWithAMapping() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, "advanced", ON_MAPPED, false))
                .containsExactly("pharmacy");
        assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, "simple", ON_MAPPED, false))
                .containsExactly("pharmacy");
        assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, "advanced", ON_NOT_MAPPED, false)).isEmpty();
    }

    /** SAH-78 security L6, DEC-068 Q2 A: Store staff never gets the HRA staff key, whatever else is on. */
    @Test
    void aStoreStaffMemberNeverGetsTheStaffMenuKeyEvenWithHraOn() {
        for (String mode : new String[] {"advanced", "simple"}) {
            assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, mode, new ModuleFlags(true, false, false, true, true),
                    false)).as(mode + " mapped").containsExactly("pharmacy");
            assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, mode, new ModuleFlags(true, true, true, true, false),
                    false)).as(mode + " unmapped").isEmpty();
            assertThat(NavigationPolicy.menuFor(MembershipRole.STORE_STAFF, mode, new ModuleFlags(true, true, true, false, false),
                    false)).as(mode + " pharmacy off").doesNotContain("staff");
        }
    }

    @Test
    void everyOtherRoleStillGetsStaffWithHraOnAndPharmacyOn() {
        for (MembershipRole role : MembershipRole.values()) {
            if (role == MembershipRole.STORE_STAFF) {
                continue;
            }
            assertThat(NavigationPolicy.menuFor(role, "advanced", new ModuleFlags(true, false, false, true, true), false))
                    .as(role.name()).contains("staff");
        }
    }

    @Test
    void pharmacySitsBeforeStaffAndBeforeConfigInBothModes() {
        ModuleFlags hraAndPharmacy = new ModuleFlags(true, false, false, true, false);
        List<String> advanced = NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", hraAndPharmacy, false);
        assertThat(advanced).containsExactly("today", "queue", "appointments", "patients", "billing", "revenue",
                "followups", "reports", "pharmacy", "staff", "config");
        List<String> simple = NavigationPolicy.menuFor(MembershipRole.OWNER, "simple", ON_NOT_MAPPED, false);
        assertThat(simple).containsExactly("today", "queue", "appointments", "patients", "billing", "pharmacy",
                "config");
    }

    @Test
    void aRoleWithNoConfigGetsPharmacyAtTheEnd() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.NURSE, "advanced", ON_MAPPED, false))
                .containsExactly("today", "queue", "secondchecks", "patients", "pharmacy");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", ON_NOT_MAPPED, false))
                .containsExactly("today", "secondchecks", "pharmacy");
    }

    @Test
    void ipdAndEmergencyKeepTheirPlacesWhenPharmacyIsOn() {
        ModuleFlags ipdAndPharmacy = new ModuleFlags(false, true, true, true, false);
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", ipdAndPharmacy, false)).containsExactly(
                "today", "queue", "appointments", "patients", "billing", "revenue", "followups", "emergency", "ipd",
                "reports", "pharmacy", "config");
    }
}
