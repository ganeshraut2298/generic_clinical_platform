package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionViews.AdmittedCardAbilities;
import java.util.Set;

/**
 * SAH-36 (ADR-013 D3, D5) who sees what on the Admitted patient cards. Pure: no Spring, no I/O. Every set is checked
 * against the role in the token and the role read again from the active membership, both must be in it, so a role
 * changed on the server applies at the next reload and a button that is shown is not refused for the role. Each action
 * set is the narrower of the mockup's default card and what the server already allows (OQ-36-4).
 */
final class AdmittedCardRules {

    /** Who may read the list at all (token and membership). */
    static final Set<String> LIST_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR");
    /** Who receives the clinical facts of the card (escalation, score, vitals due, allergy): the Safety board roles. */
    static final Set<String> CLINICAL_ROLES = EscalationRules.READ_ROLES;
    /** The Nursing and the EMR sheet. */
    static final Set<String> NURSING_ROLES = VitalsRules.READ_ROLES;
    static final Set<String> EMR_ROLES = MedicineRules.EMR_ROLES;
    /** The Move row of the admission sheet (the server allows OWNER, FRONT_DESK, NURSE; the mockup also DOCTOR). */
    static final Set<String> TRANSFER_ROLES = AdmissionService.TRANSFER_ROLES;
    /** The discharge view (the mockup: not front desk; the server also lets front desk start it). */
    static final Set<String> DISCHARGE_ROLES = Set.of("OWNER", "DOCTOR");
    /** The "Deposit low" flag on the card (the mockup hides billing from DOCTOR and NURSE). */
    static final Set<String> DEPOSIT_FLAG_ROLES = Set.of("OWNER", "FRONT_DESK");

    static final String FORBIDDEN_MESSAGE = "You cannot open the admitted patients list.";

    private AdmittedCardRules() {
    }

    /** True when the role in the token and the role of the membership are both in {@code allowed}. */
    static boolean both(Set<String> allowed, String tokenRole, String memberRole) {
        return tokenRole != null && memberRole != null && allowed.contains(tokenRole) && allowed.contains(memberRole);
    }

    /** True when the caller receives {@code clinical} on every row (and the dose counts of {@code safety}). */
    static boolean clinical(String tokenRole, String memberRole) {
        return both(CLINICAL_ROLES, tokenRole, memberRole);
    }

    /**
     * The abilities for the whole list. {@code ipdState} is trial, on or readonly (off never reaches here): Nursing and
     * EMR also work in Read-only, Transfer and Discharge do not.
     */
    static AdmittedCardAbilities abilities(String tokenRole, String memberRole, String ipdState) {
        boolean writable = "trial".equals(ipdState) || "on".equals(ipdState);
        boolean readable = writable || "readonly".equals(ipdState);
        return new AdmittedCardAbilities(
                readable && both(NURSING_ROLES, tokenRole, memberRole),
                readable && both(EMR_ROLES, tokenRole, memberRole),
                writable && both(TRANSFER_ROLES, tokenRole, memberRole),
                writable && both(DISCHARGE_ROLES, tokenRole, memberRole),
                both(DEPOSIT_FLAG_ROLES, tokenRole, memberRole));
    }
}
