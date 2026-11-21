package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-028 D4: the capability table, the allowed store roles per team role, and the two ways a role stops counting. */
class StoreRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final List<String> TEAM_ROLES = List.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK",
            "BILLING", "PHARMACIST", "COUNSELLOR", "STORE_STAFF");

    // ---- who may hold which store role ----

    @Test
    void ownerDoctorNurseAndPharmacistMayHoldEveryStoreRole() {
        for (String role : List.of("OWNER", "DOCTOR", "NURSE", "PHARMACIST")) {
            assertThat(TeamRoles.allowedStoreRoles(role)).as(role).containsExactlyInAnyOrder(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER,
                    StoreRole.APPROVER, StoreRole.DELEGATE);
            assertThat(TeamRoles.mayApprove(role)).as(role).isTrue();
        }
    }

    @Test
    void billingAndStoreStaffMayBeStoreUserCentralStoreUserOrCashierOnly() {
        for (String role : List.of("BILLING", "STORE_STAFF")) {
            assertThat(TeamRoles.allowedStoreRoles(role)).as(role)
                    .containsExactlyInAnyOrder(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER, StoreRole.CASHIER);
            assertThat(TeamRoles.mayApprove(role)).as(role).isFalse();
        }
    }

    @Test
    void visitingDoctorFrontDeskAndCounsellorMayBeStoreUserOnly() {
        for (String role : List.of("VISITING_DOCTOR", "FRONT_DESK", "COUNSELLOR")) {
            assertThat(TeamRoles.allowedStoreRoles(role)).as(role).containsExactly(StoreRole.STORE_USER);
        }
    }

    @Test
    void anUnknownOrMissingRoleGetsTheNarrowestSet() {
        assertThat(TeamRoles.allowedStoreRoles("SOMETHING_NEW")).containsExactly(StoreRole.STORE_USER);
        assertThat(TeamRoles.allowedStoreRoles(null)).containsExactly(StoreRole.STORE_USER);
    }

    @Test
    void thereIsNoPharmacistStoreRoleSoNobodyCanBeMappedAsAPharmacistWhoIsNotOne() {
        assertThat(StoreRole.values()).extracting(Enum::name)
                .containsExactly("STORE_USER", "CENTRAL_STORE_USER", "APPROVER", "DELEGATE", "CASHIER");
        for (String role : TEAM_ROLES) {
            for (StoreRole stored : StoreRole.values()) {
                for (boolean consumption : new boolean[] {true, false}) {
                    StoreAccess access = StoreRules.access(role, stored, consumption);
                    if (!TeamRoles.PHARMACIST.equals(role)) {
                        assertThat(access.receiveScheduleX()).as(role + " " + stored + " receiveScheduleX").isFalse();
                    }
                }
            }
        }
    }

    @Test
    void theSettingsAMappingCannotGiveAreAlwaysDerivedFromTheTeamRole() {
        // a pharmacist gets Schedule X with any store role that may receive; nobody else with any role
        for (StoreRole stored : StoreRole.values()) {
            assertThat(StoreRules.access("PHARMACIST", stored, false).receiveScheduleX()).as(stored.name()).isTrue();
            assertThat(StoreRules.access("STORE_STAFF", stored, true).receiveScheduleX()).as(stored.name()).isFalse();
            assertThat(StoreRules.access("OWNER", stored, true).receiveScheduleX()).as(stored.name()).isFalse();
        }
    }

    // ---- the capability table ----

    @Test
    void approverAndCurrentDelegateCanDoEverythingExceptUploadWhichIsPharmacistOnly() {
        for (StoreRole role : List.of(StoreRole.APPROVER, StoreRole.DELEGATE)) {
            StoreAccess nurse = StoreRules.access("NURSE", role, false);
            assertThat(nurse).isEqualTo(new StoreAccess(true, true, true, false, true, false, true, false, true, true, true, true, true, true, true, false));
            StoreAccess pharmacist = StoreRules.access("PHARMACIST", role, false);
            assertThat(pharmacist).isEqualTo(new StoreAccess(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true));
        }
    }

    @Test
    void storeUserRaisesAndReceivesButDoesNotApproveAndConsumesOnlyWhereTheStoreAllowsIt() {
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.STORE_USER, true))
                .isEqualTo(new StoreAccess(true, false, true, false, true, false, true, false, false, true, true, true, false, true, false, false));
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.STORE_USER, false))
                .isEqualTo(new StoreAccess(true, false, true, false, false, false, true, false, false, true, true, false, false, true, false, false));
        assertThat(StoreRules.access("PHARMACIST", StoreRole.STORE_USER, false))
                .isEqualTo(new StoreAccess(true, false, true, true, false, true, true, true, true, true, true, false, true, true, false, true));
    }

    @Test
    void centralStoreUserReceivesAndUploadsButCannotRaiseAnIndentOrApprove() {
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.CENTRAL_STORE_USER, false))
                .isEqualTo(new StoreAccess(false, false, true, false, false, true, true, true, true, true, true, false, true, true, false, false));
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.CENTRAL_STORE_USER, true).consumeReturn()).isTrue();
    }

    @Test
    void theOwnerHasEveryCapabilityInEveryStoreExceptScheduleXWhateverTheMapping() {
        for (StoreRole stored : StoreRole.values()) {
            assertThat(StoreRules.access("OWNER", stored, false)).isEqualTo(new StoreAccess(true, true, true, false, true, true, true, true, true, true, true, true, true, true, true, false));
        }
    }

    @Test
    void hasMapsEachCapabilityToItsColumn() {
        StoreAccess access = new StoreAccess(true, false, true, false, true, false, true, false, false);
        assertThat(access.has(StoreCapability.RAISE_INDENT)).isTrue();
        assertThat(access.has(StoreCapability.APPROVE)).isFalse();
        assertThat(access.has(StoreCapability.RECEIVE)).isTrue();
        assertThat(access.has(StoreCapability.RECEIVE_SCHEDULE_X)).isFalse();
        assertThat(access.has(StoreCapability.CONSUME_RETURN)).isTrue();
        assertThat(access.has(StoreCapability.UPLOAD_OPENING_STOCK)).isFalse();
        assertThat(StoreAccess.NONE.has(StoreCapability.RECEIVE)).isFalse();
    }

    @Test
    void theSevenAccessFlagsOf036FollowTheContractRules() {
        StoreAccess owner = StoreRules.access("OWNER", StoreRole.APPROVER, false);
        assertThat(owner.has(StoreCapability.START_COUNT)).isTrue();
        assertThat(owner.has(StoreCapability.REVIEW_COUNT)).isTrue();
        assertThat(owner.has(StoreCapability.HANDLE_SCHEDULE_X)).isFalse();
        StoreAccess hod = StoreRules.access("PHARMACIST", StoreRole.APPROVER, true);
        assertThat(hod.has(StoreCapability.REVIEW_COUNT)).isTrue();
        assertThat(hod.has(StoreCapability.HANDLE_SCHEDULE_X)).isTrue();
        StoreAccess user = StoreRules.access("NURSE", StoreRole.STORE_USER, true);
        assertThat(user.has(StoreCapability.RETURN_DAMAGE)).isTrue();
        assertThat(user.has(StoreCapability.DISPATCH_TRANSFER)).isTrue();
        assertThat(user.has(StoreCapability.COUNT)).isTrue();
        assertThat(user.has(StoreCapability.CONSUME)).isEqualTo(user.consumeReturn());
        assertThat(user.has(StoreCapability.START_COUNT)).isFalse();
        assertThat(user.has(StoreCapability.REVIEW_COUNT)).isFalse();
        assertThat(user.has(StoreCapability.HANDLE_SCHEDULE_X)).isFalse();
        assertThat(StoreRules.access("NURSE", StoreRole.STORE_USER, false).has(StoreCapability.CONSUME)).isFalse();
        assertThat(StoreRules.access("STORE_STAFF", StoreRole.CENTRAL_STORE_USER, false).has(StoreCapability.START_COUNT)).isTrue();
        assertThat(StoreRules.access("PHARMACIST", StoreRole.STORE_USER, false).has(StoreCapability.START_COUNT)).isTrue();
        assertThat(StoreAccess.NONE.has(StoreCapability.COUNT)).isFalse();
        assertThat(StoreAccess.NONE.has(StoreCapability.DISPATCH_TRANSFER)).isFalse();
    }

    // ---- the role that counts today ----

    @Test
    void aMappingThatNoLongerFitsTheTeamRoleNeedsReviewAndCountsAsStoreUser() {
        assertThat(StoreRules.needsReview("FRONT_DESK", StoreRole.APPROVER)).isTrue();
        assertThat(StoreRules.needsReview("STORE_STAFF", StoreRole.DELEGATE)).isTrue();
        assertThat(StoreRules.needsReview("NURSE", StoreRole.APPROVER)).isFalse();
        assertThat(StoreRules.effectiveRole("FRONT_DESK", StoreRole.APPROVER, null, null, TODAY))
                .isEqualTo(StoreRole.STORE_USER);
        assertThat(StoreRules.effectiveRole("BILLING", StoreRole.CENTRAL_STORE_USER, null, null, TODAY))
                .isEqualTo(StoreRole.CENTRAL_STORE_USER);
        assertThat(StoreRules.effectiveRole("COUNSELLOR", StoreRole.CENTRAL_STORE_USER, null, null, TODAY))
                .isEqualTo(StoreRole.STORE_USER);
    }

    @Test
    void aDelegateCountsOnlyInsideItsDatesInclusive() {
        LocalDate from = TODAY;
        LocalDate to = TODAY.plusDays(3);
        assertThat(StoreRules.effectiveRole("NURSE", StoreRole.DELEGATE, from, to, TODAY)).isEqualTo(StoreRole.DELEGATE);
        assertThat(StoreRules.effectiveRole("NURSE", StoreRole.DELEGATE, from, to, to)).isEqualTo(StoreRole.DELEGATE);
        assertThat(StoreRules.effectiveRole("NURSE", StoreRole.DELEGATE, from, to, to.plusDays(1)))
                .isEqualTo(StoreRole.STORE_USER);
        assertThat(StoreRules.effectiveRole("NURSE", StoreRole.DELEGATE, from, to, from.minusDays(1)))
                .isEqualTo(StoreRole.STORE_USER);
        assertThat(StoreRules.effectiveRole("NURSE", StoreRole.DELEGATE, null, null, TODAY)).isEqualTo(StoreRole.STORE_USER);
    }

    @Test
    void delegateStateIsUpcomingCurrentOrEnded() {
        assertThat(DelegateState.of(TODAY.plusDays(1), TODAY.plusDays(5), TODAY)).isEqualTo(DelegateState.UPCOMING);
        assertThat(DelegateState.of(TODAY, TODAY.plusDays(5), TODAY)).isEqualTo(DelegateState.CURRENT);
        assertThat(DelegateState.of(TODAY.minusDays(5), TODAY, TODAY)).isEqualTo(DelegateState.CURRENT);
        assertThat(DelegateState.of(TODAY.minusDays(5), TODAY.minusDays(1), TODAY)).isEqualTo(DelegateState.ENDED);
    }

    // ---- the unit code ----

    @Test
    void theUnitCodeIsTheFirstThreeLettersOrDigitsOfTheWebAddressPaddedWithX() {
        assertThat(UnitCodes.derive("sunrise-baner")).isEqualTo("SUN");
        assertThat(UnitCodes.derive("a-b-c-d")).isEqualTo("ABC");
        assertThat(UnitCodes.derive("9-lives")).isEqualTo("9LI");
        assertThat(UnitCodes.derive("ab")).isEqualTo("ABX");
        assertThat(UnitCodes.derive("--")).isEqualTo("XXX");
        assertThat(UnitCodes.derive(null)).isEqualTo("XXX");
        assertThat(UnitCodes.derive("")).isEqualTo("XXX");
    }
}
