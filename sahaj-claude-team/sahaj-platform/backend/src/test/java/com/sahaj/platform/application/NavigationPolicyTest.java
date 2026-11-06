package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.domain.MembershipRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationPolicyTest {

    /** IPD Trial or On: on and writable. */
    private static final NavigationPolicy.ModuleFlags IPD_ON = new NavigationPolicy.ModuleFlags(false, true, true);
    /** IPD Read-only: on, not writable. */
    private static final NavigationPolicy.ModuleFlags IPD_READONLY = new NavigationPolicy.ModuleFlags(false, true, false);
    private static final NavigationPolicy.ModuleFlags IPD_OFF = new NavigationPolicy.ModuleFlags(false, false, false);

    @Test
    void hraOffLeavesTheBaseMenuUnchanged() {
        for (MembershipRole role : MembershipRole.values()) {
            assertThat(NavigationPolicy.menuFor(role, "advanced", false))
                    .isEqualTo(NavigationPolicy.menuFor(role, "advanced"));
        }
    }

    @Test
    void hraOnAddsStaffForEveryRoleInBothModes() {
        for (MembershipRole role : MembershipRole.values()) {
            assertThat(NavigationPolicy.menuFor(role, "advanced", true)).as(role.name()).contains("staff");
            assertThat(NavigationPolicy.menuFor(role, "simple", true)).as(role.name()).contains("staff");
        }
    }

    @Test
    void ipdIsAddedOnlyForOwnerFrontDeskNurseDoctorAndPharmacistWhenIpdIsNotOff() {
        for (MembershipRole role : MembershipRole.values()) {
            boolean expected = role == MembershipRole.OWNER || role == MembershipRole.FRONT_DESK
                    || role == MembershipRole.NURSE || role == MembershipRole.DOCTOR
                    || role == MembershipRole.PHARMACIST;
            for (String mode : new String[] {"simple", "advanced"}) {
                assertThat(NavigationPolicy.menuFor(role, mode, IPD_ON).contains("ipd")).as(role + " " + mode)
                        .isEqualTo(expected);
                assertThat(NavigationPolicy.menuFor(role, mode, IPD_READONLY).contains("ipd")).as(role + " ro " + mode)
                        .isEqualTo(expected);
                assertThat(NavigationPolicy.menuFor(role, mode, IPD_OFF)).isEqualTo(NavigationPolicy.menuFor(role, mode));
            }
        }
    }

    @Test
    void ipdFollowsFollowupsInTheAdvancedMenuAndSitsBeforeConfigInTheSimpleMenu() {
        NavigationPolicy.ModuleFlags both = new NavigationPolicy.ModuleFlags(true, true, true);
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", both)).containsExactly("today", "queue",
                "appointments", "patients", "billing", "revenue", "followups", "emergency", "ipd", "reports", "staff",
                "config");
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "simple", both)).containsExactly("today", "queue",
                "appointments", "patients", "billing", "ipd", "staff", "config");
        assertThat(NavigationPolicy.menuFor(MembershipRole.NURSE, "advanced", both))
                .containsExactly("today", "queue", "patients", "emergency", "ipd", "staff");
        assertThat(NavigationPolicy.menuFor(MembershipRole.DOCTOR, "advanced", IPD_ON))
                .containsExactly("today", "queue", "appointments", "patients", "followups", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.FRONT_DESK, "simple", IPD_ON))
                .containsExactly("today", "queue", "appointments", "patients", "billing", "ipd");
    }

    @Test
    void thePharmacistGetsIpdAfterTodayAndNeverEmergency() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", IPD_ON))
                .containsExactly("today", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "simple", IPD_ON))
                .containsExactly("today", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", IPD_READONLY))
                .containsExactly("today", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", IPD_OFF))
                .containsExactly("today");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced",
                new NavigationPolicy.ModuleFlags(true, true, true))).containsExactly("today", "ipd", "staff");
    }

    @Test
    void emergencyIsBeforeIpdForOwnerFrontDeskAndNurseInTheAdvancedMenuOnly() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.FRONT_DESK, "advanced", IPD_ON)).containsExactly("today",
                "queue", "appointments", "patients", "billing", "followups", "emergency", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.NURSE, "advanced", IPD_ON))
                .containsExactly("today", "queue", "patients", "emergency", "ipd");
        for (MembershipRole role : MembershipRole.values()) {
            assertThat(NavigationPolicy.menuFor(role, "simple", IPD_ON)).as(role + " simple").doesNotContain("emergency");
            boolean expected = role == MembershipRole.OWNER || role == MembershipRole.FRONT_DESK
                    || role == MembershipRole.NURSE;
            List<String> advanced = NavigationPolicy.menuFor(role, "advanced", IPD_ON);
            assertThat(advanced.contains("emergency")).as(role + " advanced").isEqualTo(expected);
            if (expected) {
                assertThat(advanced.indexOf("emergency") + 1).isEqualTo(advanced.indexOf("ipd"));
            }
        }
    }

    @Test
    void emergencyIsHiddenInReadOnlyAndOffButIpdStaysInReadOnly() {
        for (MembershipRole role : MembershipRole.values()) {
            assertThat(NavigationPolicy.menuFor(role, "advanced", IPD_READONLY)).as(role + " readonly")
                    .doesNotContain("emergency");
            assertThat(NavigationPolicy.menuFor(role, "advanced", IPD_OFF)).as(role + " off").doesNotContain("emergency");
        }
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", IPD_READONLY)).contains("ipd");
    }

    @Test
    void staffComesBeforeConfigAndAfterTheBaseItems() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", true)).containsExactly("today", "queue",
                "appointments", "patients", "billing", "revenue", "followups", "reports", "staff", "config");
        assertThat(NavigationPolicy.menuFor(MembershipRole.NURSE, "advanced", true))
                .containsExactly("today", "queue", "patients", "staff");
    }
}
