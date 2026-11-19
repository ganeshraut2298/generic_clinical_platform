package com.sahaj.pharmacy.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The clinic team roles as pharmacy sees them (strings from {@code platform.api.TeamMemberPort}; the module may not
 * import platform's domain enum) and the rules that depend on them (ADR-028 D4 "Who may hold which store role").
 */
public final class TeamRoles {

    public static final String OWNER = "OWNER";
    public static final String PHARMACIST = "PHARMACIST";
    public static final String STORE_STAFF = "STORE_STAFF";

    private static final Set<StoreRole> ALL = Collections.unmodifiableSet(EnumSet.allOf(StoreRole.class));
    private static final Set<StoreRole> USER_ROLES = Collections
            .unmodifiableSet(EnumSet.of(StoreRole.STORE_USER, StoreRole.CENTRAL_STORE_USER));
    private static final Set<StoreRole> STORE_USER_ONLY = Collections.unmodifiableSet(EnumSet.of(StoreRole.STORE_USER));

    private TeamRoles() {
    }

    /**
     * The store roles this team role may hold. OWNER, DOCTOR, NURSE and PHARMACIST: all four. BILLING and STORE_STAFF:
     * Store user and Central Store user only. VISITING_DOCTOR, FRONT_DESK and COUNSELLOR: Store user only. An unknown
     * role (a future one) gets the narrowest set.
     */
    public static Set<StoreRole> allowedStoreRoles(String teamRole) {
        if (teamRole == null) {
            return STORE_USER_ONLY;
        }
        switch (teamRole) {
            case OWNER:
            case "DOCTOR":
            case "NURSE":
            case PHARMACIST:
                return ALL;
            case "BILLING":
            case STORE_STAFF:
                return USER_ROLES;
            default:
                return STORE_USER_ONLY;
        }
    }

    /** Who can be a store's HOD or delegate (OQ-78-6): the roles that may hold APPROVER. */
    public static boolean mayApprove(String teamRole) {
        return allowedStoreRoles(teamRole).contains(StoreRole.APPROVER);
    }
}
