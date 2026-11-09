package com.sahaj.ipd.domain.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAH-37 (ADR-015 D3) who sees which data group on the patient card and who may use which quick action: the one copy.
 * {@code AdmissionService} computes the abilities and the field gating of {@code GET /admissions} from it, and the
 * catalogue of {@code GET /card-setup} is serialised from the same constants, so the "who" line of the setup page and
 * the preview's role rule are never a second copy.
 *
 * <p>The action sets are the SAH-36 abilities moved here unchanged (ADR-013 D3, D5; OQ-36-4), and {@code deposit} is
 * new. Every check needs the role in the token and the role read again from the active membership to be in the set,
 * so a role changed on the server applies at the next reload and a button that is shown is not refused for the role.
 * Pure: no Spring, no I/O.
 */
public final class CardAccessRule {

    /** Who may read the Admitted list (token and membership). */
    public static final Set<String> LIST_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR");
    /** The "View as" choices of the preview, in the order of the select. */
    public static final List<String> VIEW_AS_ROLES = List.of("OWNER", "DOCTOR", "NURSE", "FRONT_DESK");

    /** Who receives the clinical facts of the card (the Safety board roles). */
    public static final Set<String> CLINICAL = Set.of("OWNER", "DOCTOR", "NURSE");
    /** The Nursing sheet. */
    public static final Set<String> NURSING = Set.of("OWNER", "NURSE", "DOCTOR");
    /** The EMR sheet. */
    public static final Set<String> EMR = Set.of("OWNER", "DOCTOR", "NURSE");
    /** The Move row of the admission sheet (the server allows OWNER, FRONT_DESK, NURSE; the mockup also DOCTOR). */
    public static final Set<String> TRANSFER = Set.of("OWNER", "FRONT_DESK", "NURSE");
    /** The discharge view (the mockup: not front desk; the server also lets front desk start it). */
    public static final Set<String> DISCHARGE = Set.of("OWNER", "DOCTOR");
    /** The Deposit action (SAH-37, AC40): the roles that may collect a deposit. */
    public static final Set<String> DEPOSIT = Set.of("OWNER", "FRONT_DESK");
    /** The "Deposit low" flag on the card (the mockup hides billing from DOCTOR and NURSE). */
    public static final Set<String> DEPOSIT_FLAG = Set.of("OWNER", "FRONT_DESK");

    private static final Set<String> PATIENT_GROUP = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR");
    private static final Set<String> CONTACT_GROUP = Set.of("OWNER", "FRONT_DESK");
    private static final Set<String> BILLING_GROUP = Set.of("OWNER", "FRONT_DESK");

    /** What the card offers one caller, once for the whole list. */
    public record Abilities(boolean nursing, boolean emr, boolean transfer, boolean discharge, boolean deposit,
                            boolean depositLowFlag, List<String> visibleGroups) {
    }

    private CardAccessRule() {
    }

    /** True when the role in the token and the role of the membership are both in {@code allowed}. */
    public static boolean both(Set<String> allowed, String tokenRole, String memberRole) {
        return tokenRole != null && memberRole != null && allowed.contains(tokenRole) && allowed.contains(memberRole);
    }

    /** True when the caller receives {@code clinical} on every row (and the dose counts of {@code safety}). */
    public static boolean clinical(String tokenRole, String memberRole) {
        return both(CLINICAL, tokenRole, memberRole);
    }

    /** The roles that may see a data group (unknown group: none). */
    public static Set<String> groupRolesSet(String group) {
        switch (group) {
            case CardCatalogue.G_PATIENT:
                return PATIENT_GROUP;
            case CardCatalogue.G_CONTACT:
                return CONTACT_GROUP;
            case CardCatalogue.G_CLINICAL:
            case CardCatalogue.G_VITALS:
                return CLINICAL;
            case CardCatalogue.G_BILLING:
                return BILLING_GROUP;
            default:
                return Set.of();
        }
    }

    /** The roles of a group, in the order of {@link #VIEW_AS_ROLES}. */
    public static List<String> groupRoles(String group) {
        return roles(groupRolesSet(group));
    }

    /** A role set as a list in the order of {@link #VIEW_AS_ROLES} (the order of the "who" line). */
    public static List<String> roles(Set<String> set) {
        List<String> list = new ArrayList<>();
        for (String role : VIEW_AS_ROLES) {
            if (set.contains(role)) {
                list.add(role);
            }
        }
        return List.copyOf(list);
    }

    /** True when the caller (token role and membership role both) may see the data group. */
    public static boolean mayView(String group, String tokenRole, String memberRole) {
        return both(groupRolesSet(group), tokenRole, memberRole);
    }

    /** The data groups this caller may see, in catalogue order. */
    public static List<String> visibleGroups(String tokenRole, String memberRole) {
        List<String> groups = new ArrayList<>();
        for (CardCatalogue.GroupDef group : CardCatalogue.GROUPS) {
            if (mayView(group.key(), tokenRole, memberRole)) {
                groups.add(group.key());
            }
        }
        return List.copyOf(groups);
    }

    /**
     * The abilities for the whole list. {@code ipdState} is trial, on or readonly (off never reaches here): Nursing and
     * EMR also work in Read-only, Transfer, Discharge and Deposit do not.
     */
    public static Abilities abilities(String tokenRole, String memberRole, String ipdState) {
        boolean writable = "trial".equals(ipdState) || "on".equals(ipdState);
        boolean readable = writable || "readonly".equals(ipdState);
        return new Abilities(
                readable && both(NURSING, tokenRole, memberRole),
                readable && both(EMR, tokenRole, memberRole),
                writable && both(TRANSFER, tokenRole, memberRole),
                writable && both(DISCHARGE, tokenRole, memberRole),
                writable && both(DEPOSIT, tokenRole, memberRole),
                both(DEPOSIT_FLAG, tokenRole, memberRole),
                visibleGroups(tokenRole, memberRole));
    }
}
