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
            assertThat(withoutSecondChecks(NavigationPolicy.menuFor(role, "advanced", false)))
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
                assertThat(withoutSecondChecks(NavigationPolicy.menuFor(role, mode, IPD_OFF)))
                        .isEqualTo(NavigationPolicy.menuFor(role, mode));
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
                .containsExactly("today", "queue", "secondchecks", "patients", "emergency", "ipd", "staff");
        assertThat(NavigationPolicy.menuFor(MembershipRole.DOCTOR, "advanced", IPD_ON))
                .containsExactly("today", "queue", "secondchecks", "appointments", "patients", "followups", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.FRONT_DESK, "simple", IPD_ON))
                .containsExactly("today", "queue", "appointments", "patients", "billing", "ipd");
    }

    @Test
    void thePharmacistGetsIpdAfterTodayAndNeverEmergency() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", IPD_ON))
                .containsExactly("today", "ipd", "secondchecks");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "simple", IPD_ON))
                .containsExactly("today", "ipd", "secondchecks");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", IPD_READONLY))
                .containsExactly("today", "ipd", "secondchecks");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced", IPD_OFF))
                .containsExactly("today", "secondchecks");
        assertThat(NavigationPolicy.menuFor(MembershipRole.PHARMACIST, "advanced",
                new NavigationPolicy.ModuleFlags(true, true, true)))
                .containsExactly("today", "ipd", "secondchecks", "staff");
    }

    @Test
    void emergencyIsBeforeIpdForOwnerFrontDeskAndNurseInTheAdvancedMenuOnly() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.FRONT_DESK, "advanced", IPD_ON)).containsExactly("today",
                "queue", "appointments", "patients", "billing", "followups", "emergency", "ipd");
        assertThat(NavigationPolicy.menuFor(MembershipRole.NURSE, "advanced", IPD_ON))
                .containsExactly("today", "queue", "secondchecks", "patients", "emergency", "ipd");
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
                .containsExactly("today", "queue", "secondchecks", "patients", "staff");
    }

    private static List<String> withoutSecondChecks(List<String> menu) {
        return menu.stream().filter(key -> !"secondchecks".equals(key)).toList();
    }

    private static final NavigationPolicy.ModuleFlags NONE = new NavigationPolicy.ModuleFlags(false, false, false);

    @Test
    void secondChecksIsForTheCheckerRolesAndAnOwnerWithDoctorDetailsInBothModes() {
        for (MembershipRole role : MembershipRole.values()) {
            boolean checker = role == MembershipRole.NURSE || role == MembershipRole.PHARMACIST
                    || role == MembershipRole.DOCTOR || role == MembershipRole.VISITING_DOCTOR;
            for (String mode : new String[] {"simple", "advanced"}) {
                assertThat(NavigationPolicy.menuFor(role, mode, NONE, false).contains("secondchecks"))
                        .as(role + " " + mode).isEqualTo(checker);
                assertThat(NavigationPolicy.menuFor(role, mode, NONE, true).contains("secondchecks"))
                        .as(role + " " + mode + " with doctor details")
                        .isEqualTo(checker || role == MembershipRole.OWNER);
            }
        }
    }

    @Test
    void secondChecksSitsRightAfterQueueAndTheOwnerWithoutDoctorDetailsDoesNotGetIt() {
        assertThat(NavigationPolicy.menuFor(MembershipRole.DOCTOR, "advanced", NONE))
                .containsExactly("today", "queue", "secondchecks", "appointments", "patients", "followups");
        assertThat(NavigationPolicy.menuFor(MembershipRole.VISITING_DOCTOR, "simple", NONE))
                .containsExactly("today", "queue", "secondchecks", "appointments", "patients");
        assertThat(NavigationPolicy.menuFor(MembershipRole.NURSE, "advanced", NONE))
                .containsExactly("today", "queue", "secondchecks", "patients");
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", NONE, true)).containsExactly("today",
                "queue", "secondchecks", "appointments", "patients", "billing", "revenue", "followups", "reports",
                "config");
        assertThat(NavigationPolicy.menuFor(MembershipRole.OWNER, "advanced", NONE))
                .doesNotContain("secondchecks");
        assertThat(NavigationPolicy.menuFor(MembershipRole.FRONT_DESK, "advanced", NONE))
                .doesNotContain("secondchecks");
    }
}
