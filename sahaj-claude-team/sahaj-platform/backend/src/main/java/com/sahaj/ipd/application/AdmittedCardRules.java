package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionViews.AdmittedCardAbilities;
import com.sahaj.ipd.domain.card.CardAccessRule;
import java.util.Set;

/**
 * SAH-36 (ADR-013 D3, D5) who sees what on the Admitted patient cards. SAH-37 (ADR-015 D3) moved the rule into
 * {@link CardAccessRule} (the one copy, also behind the catalogue of {@code GET /card-setup}) with no change of
 * behaviour for the SAH-36 abilities; this class keeps the names the list code and the SAH-36 tests use and builds the
 * answer record. Every set is checked against the role in the token and the role read again from the active membership,
 * both must be in it. Pure: no Spring, no I/O.
 */
final class AdmittedCardRules {

    /** Who may read the list at all (token and membership). */
    static final Set<String> LIST_ROLES = CardAccessRule.LIST_ROLES;
    /** Who receives the clinical facts of the card (escalation, score, vitals due, allergy): the Safety board roles. */
    static final Set<String> CLINICAL_ROLES = CardAccessRule.CLINICAL;
    /** The Nursing and the EMR sheet. */
    static final Set<String> NURSING_ROLES = CardAccessRule.NURSING;
    static final Set<String> EMR_ROLES = CardAccessRule.EMR;
    /** The Move row of the admission sheet (the server allows OWNER, FRONT_DESK, NURSE; the mockup also DOCTOR). */
    static final Set<String> TRANSFER_ROLES = CardAccessRule.TRANSFER;
    /** The discharge view (the mockup: not front desk; the server also lets front desk start it). */
    static final Set<String> DISCHARGE_ROLES = CardAccessRule.DISCHARGE;
    /** The "Deposit low" flag on the card (the mockup hides billing from DOCTOR and NURSE). */
    static final Set<String> DEPOSIT_FLAG_ROLES = CardAccessRule.DEPOSIT_FLAG;

    static final String FORBIDDEN_MESSAGE = "You cannot open the admitted patients list.";

    private AdmittedCardRules() {
    }

    /** True when the role in the token and the role of the membership are both in {@code allowed}. */
    static boolean both(Set<String> allowed, String tokenRole, String memberRole) {
        return CardAccessRule.both(allowed, tokenRole, memberRole);
    }

    /** True when the caller receives {@code clinical} on every row (and the dose counts of {@code safety}). */
    static boolean clinical(String tokenRole, String memberRole) {
        return CardAccessRule.clinical(tokenRole, memberRole);
    }

    /**
     * The abilities for the whole list. {@code ipdState} is trial, on or readonly (off never reaches here): Nursing and
     * EMR also work in Read-only, Transfer, Discharge and Deposit do not.
     */
    static AdmittedCardAbilities abilities(String tokenRole, String memberRole, String ipdState) {
        CardAccessRule.Abilities a = CardAccessRule.abilities(tokenRole, memberRole, ipdState);
        return new AdmittedCardAbilities(a.nursing(), a.emr(), a.transfer(), a.discharge(), a.deposit(),
                a.depositLowFlag(), a.visibleGroups());
    }
}
