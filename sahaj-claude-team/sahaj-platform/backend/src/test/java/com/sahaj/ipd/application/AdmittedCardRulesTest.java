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
                    .isEqualTo(card(true, true, true, true, true, true, "patient", "contact", "clinical", "vitals", "billing"));
        }
    }

    @Test
    void nurseHasNursingEmrAndTransferButNotDischargeOrTheDepositFlag() {
        assertThat(AdmittedCardRules.abilities("NURSE", "NURSE", "on"))
                .isEqualTo(card(true, true, true, false, false, false, "patient", "clinical", "vitals"));
    }

    @Test
    void doctorHasNursingEmrAndDischargeButNotTransferOrTheDepositFlag() {
        assertThat(AdmittedCardRules.abilities("DOCTOR", "DOCTOR", "on"))
                .isEqualTo(card(true, true, false, true, false, false, "patient", "clinical", "vitals"));
    }

    @Test
    void frontDeskHasOnlyTransferAndTheDepositFlag() {
        assertThat(AdmittedCardRules.abilities("FRONT_DESK", "FRONT_DESK", "on"))
                .isEqualTo(card(false, false, true, false, true, true, "patient", "contact", "billing"));
    }

    @Test
    void readOnlyKeepsNursingAndEmrForTheClinicalRolesAndRemovesTransferAndDischarge() {
        assertThat(AdmittedCardRules.abilities("OWNER", "OWNER", "readonly"))
                .isEqualTo(card(true, true, false, false, false, true, "patient", "contact", "clinical", "vitals", "billing"));
        assertThat(AdmittedCardRules.abilities("NURSE", "NURSE", "readonly"))
                .isEqualTo(card(true, true, false, false, false, false, "patient", "clinical", "vitals"));
        assertThat(AdmittedCardRules.abilities("DOCTOR", "DOCTOR", "readonly"))
                .isEqualTo(card(true, true, false, false, false, false, "patient", "clinical", "vitals"));
        assertThat(AdmittedCardRules.abilities("FRONT_DESK", "FRONT_DESK", "readonly"))
                .isEqualTo(card(false, false, false, false, false, true, "patient", "contact", "billing"));
    }

    @Test
    void aRoleChangedOnTheServerNarrowsTheActionsAtOnceEvenWithAnOldToken() {
        // Token still says NURSE, membership now says FRONT_DESK: only what both allow.
        assertThat(AdmittedCardRules.abilities("NURSE", "FRONT_DESK", "on"))
                .isEqualTo(card(false, false, true, false, false, false, "patient"));
        // Token says OWNER, membership says DOCTOR: the doctor set.
        assertThat(AdmittedCardRules.abilities("OWNER", "DOCTOR", "on"))
                .isEqualTo(card(true, true, false, true, false, false, "patient", "clinical", "vitals"));
    }

    @Test
    void offStateGivesNoActionThatNeedsIpd() {
        assertThat(AdmittedCardRules.abilities("OWNER", "OWNER", "off"))
                .isEqualTo(card(false, false, false, false, false, true, "patient", "contact", "clinical", "vitals", "billing"));
    }

    /**
     * SAH-37 (ADR-015 D3): the SAH-36 rule moved into CardAccessRule. The role sets of the services that guard the
     * sheets must still be the same sets, so a button that is shown is never refused by the endpoint it opens.
     */
    @Test
    void theMovedRuleStillMatchesTheServicesThatGuardTheSheets() {
        assertThat(com.sahaj.ipd.domain.card.CardAccessRule.NURSING).isEqualTo(VitalsRules.READ_ROLES);
        assertThat(com.sahaj.ipd.domain.card.CardAccessRule.EMR).isEqualTo(MedicineRules.EMR_ROLES);
        assertThat(com.sahaj.ipd.domain.card.CardAccessRule.CLINICAL).isEqualTo(EscalationRules.READ_ROLES);
        assertThat(com.sahaj.ipd.domain.card.CardAccessRule.TRANSFER).isEqualTo(AdmissionService.TRANSFER_ROLES);
        assertThat(com.sahaj.ipd.domain.card.CardAccessRule.LIST_ROLES).isEqualTo(DischargeRules.READ_ROLES);
        assertThat(AdmittedCardRules.DISCHARGE_ROLES).containsExactlyInAnyOrder("OWNER", "DOCTOR");
    }

    @Test
    void theDepositActionAndTheVisibleGroupsComeFromTheSameRule() {
        assertThat(AdmittedCardRules.abilities("FRONT_DESK", "FRONT_DESK", "on").deposit()).isTrue();
        assertThat(AdmittedCardRules.abilities("FRONT_DESK", "FRONT_DESK", "readonly").deposit()).isFalse();
        assertThat(AdmittedCardRules.abilities("NURSE", "NURSE", "on").visibleGroups())
                .isEqualTo(com.sahaj.ipd.domain.card.CardAccessRule.visibleGroups("NURSE", "NURSE"));
    }

    private static boolean isClinical(String role) {
        return "OWNER".equals(role) || "DOCTOR".equals(role) || "NURSE".equals(role);
    }

    private static AdmittedCardAbilities card(boolean nursing, boolean emr, boolean transfer, boolean discharge,
                                              boolean deposit, boolean depositLowFlag, String... groups) {
        return new AdmittedCardAbilities(nursing, emr, transfer, discharge, deposit, depositLowFlag,
                java.util.List.of(groups));
    }

    private static AdmittedCardAbilities none() {
        return card(false, false, false, false, false, false);
    }
}
