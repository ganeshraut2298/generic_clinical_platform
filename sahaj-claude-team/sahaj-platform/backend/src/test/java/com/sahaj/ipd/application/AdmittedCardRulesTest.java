package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.AdmissionViews.AdmittedCardAbilities;
import org.junit.jupiter.api.Test;

/** SAH-36 (ADR-013 D3, D5): who gets the clinical facts and which card actions, by role pair and IPD state. */
class AdmittedCardRulesTest {

    private static final String[] LIST_ROLES = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"};

    @Test
    void clinicalFactsNeedTheTokenRoleAndTheMembershipRoleBothOwnerDoctorOrNurse() {
        for (String token : LIST_ROLES) {
            for (String member : LIST_ROLES) {
                boolean expected = isClinical(token) && isClinical(member);
                assertThat(AdmittedCardRules.clinical(token, member)).as(token + "/" + member).isEqualTo(expected);
            }
        }
    }

    @Test
    void nullOrUnknownRolesNeverGetClinicalFactsOrActions() {
        assertThat(AdmittedCardRules.clinical(null, "NURSE")).isFalse();
        assertThat(AdmittedCardRules.clinical("NURSE", null)).isFalse();
        assertThat(AdmittedCardRules.clinical("BILLING", "BILLING")).isFalse();
        assertThat(AdmittedCardRules.abilities(null, "OWNER", "on")).isEqualTo(none());
        assertThat(AdmittedCardRules.abilities("OWNER", null, "on")).isEqualTo(none());
        assertThat(AdmittedCardRules.abilities("PHARMACIST", "PHARMACIST", "on")).isEqualTo(none());
    }

    @Test
    void ownerHasEveryActionInTrialAndOn() {
        for (String state : new String[] {"trial", "on"}) {
            assertThat(AdmittedCardRules.abilities("OWNER", "OWNER", state))
                    .isEqualTo(new AdmittedCardAbilities(true, true, true, true, true));
        }
    }

    @Test
    void nurseHasNursingEmrAndTransferButNotDischargeOrTheDepositFlag() {
        assertThat(AdmittedCardRules.abilities("NURSE", "NURSE", "on"))
                .isEqualTo(new AdmittedCardAbilities(true, true, true, false, false));
    }

    @Test
    void doctorHasNursingEmrAndDischargeButNotTransferOrTheDepositFlag() {
        assertThat(AdmittedCardRules.abilities("DOCTOR", "DOCTOR", "on"))
                .isEqualTo(new AdmittedCardAbilities(true, true, false, true, false));
    }

    @Test
    void frontDeskHasOnlyTransferAndTheDepositFlag() {
        assertThat(AdmittedCardRules.abilities("FRONT_DESK", "FRONT_DESK", "on"))
                .isEqualTo(new AdmittedCardAbilities(false, false, true, false, true));
    }

    @Test
    void readOnlyKeepsNursingAndEmrForTheClinicalRolesAndRemovesTransferAndDischarge() {
        assertThat(AdmittedCardRules.abilities("OWNER", "OWNER", "readonly"))
                .isEqualTo(new AdmittedCardAbilities(true, true, false, false, true));
        assertThat(AdmittedCardRules.abilities("NURSE", "NURSE", "readonly"))
                .isEqualTo(new AdmittedCardAbilities(true, true, false, false, false));
        assertThat(AdmittedCardRules.abilities("DOCTOR", "DOCTOR", "readonly"))
                .isEqualTo(new AdmittedCardAbilities(true, true, false, false, false));
        assertThat(AdmittedCardRules.abilities("FRONT_DESK", "FRONT_DESK", "readonly"))
                .isEqualTo(new AdmittedCardAbilities(false, false, false, false, true));
    }

    @Test
    void aRoleChangedOnTheServerNarrowsTheActionsAtOnceEvenWithAnOldToken() {
        // Token still says NURSE, membership now says FRONT_DESK: only what both allow.
        assertThat(AdmittedCardRules.abilities("NURSE", "FRONT_DESK", "on"))
                .isEqualTo(new AdmittedCardAbilities(false, false, true, false, false));
        // Token says OWNER, membership says DOCTOR: the doctor set.
        assertThat(AdmittedCardRules.abilities("OWNER", "DOCTOR", "on"))
                .isEqualTo(new AdmittedCardAbilities(true, true, false, true, false));
    }

    @Test
    void offStateGivesNoActionThatNeedsIpd() {
        assertThat(AdmittedCardRules.abilities("OWNER", "OWNER", "off"))
                .isEqualTo(new AdmittedCardAbilities(false, false, false, false, true));
    }

    private static boolean isClinical(String role) {
        return "OWNER".equals(role) || "DOCTOR".equals(role) || "NURSE".equals(role);
    }

    private static AdmittedCardAbilities none() {
        return new AdmittedCardAbilities(false, false, false, false, false);
    }
}
