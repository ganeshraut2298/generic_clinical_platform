package com.sahaj.pharmacy.domain;

import java.time.LocalDate;

/**
 * The store-role rules of ADR-028 D4, in one place and free of I/O: which role really counts today, whether a mapping
 * needs review, and the access columns derived from them (mockup 16). A switched-off member or a removed mapping has no
 * access: callers pass {@link StoreAccess#NONE} for those instead of calling {@link #access}.
 */
public final class StoreRules {

    private StoreRules() {
    }

    /** True when the stored store role is no longer allowed for the member's current team role. */
    public static boolean needsReview(String teamRole, StoreRole stored) {
        return !TeamRoles.allowedStoreRoles(teamRole).contains(stored);
    }

    /** CASHIER needs an ACTIVE store that allows sale and is not the Central Store (DEC-074 OQ-80-36). */
    public static boolean cashierStore(boolean saleAllowed, StoreType type) {
        return saleAllowed && type != null && !type.isCentral();
    }

    /** As {@link #needsReview(String, StoreRole)}; a CASHIER mapping also needs review when the store no longer takes a cashier. */
    public static boolean needsReview(String teamRole, StoreRole stored, boolean cashierStore) {
        return needsReview(teamRole, stored) || (stored == StoreRole.CASHIER && !cashierStore);
    }

    /** As {@link #effectiveRole(String, StoreRole, LocalDate, LocalDate, LocalDate)} with the store known. */
    public static StoreRole effectiveRole(String teamRole, StoreRole stored, LocalDate from, LocalDate to, LocalDate today,
                                          boolean cashierStore) {
        if (stored == StoreRole.CASHIER && !cashierStore) {
            return StoreRole.STORE_USER;
        }
        return effectiveRole(teamRole, stored, from, to, today);
    }

    /**
     * The role that counts today: a mapping that needs review counts as STORE_USER, and so does a DELEGATE outside its
     * dates ("Delegate · ended 30 Nov").
     */
    public static StoreRole effectiveRole(String teamRole, StoreRole stored, LocalDate from, LocalDate to,
                                          LocalDate today) {
        if (needsReview(teamRole, stored)) {
            return StoreRole.STORE_USER;
        }
        if (stored == StoreRole.DELEGATE && (from == null || to == null
                || DelegateState.of(from, to, today) != DelegateState.CURRENT)) {
            return StoreRole.STORE_USER;
        }
        return stored;
    }

    /**
     * The capability table of ADR-028 D4. OWNER has every capability in every store with no mapping, except Schedule X
     * (pharmacist-only actions stay out of the owner's reach). For the others, {@code effective} comes from
     * {@link #effectiveRole}; {@code consumptionAllowed} is the store's Consumption entry flag.
     *
     * <p>Receive Schedule X is receive AND team role PHARMACIST, so STORE_STAFF and OWNER never get it, and no store role
     * can give it to a person who is not a pharmacist.
     */
    public static StoreAccess access(String teamRole, StoreRole effective, boolean consumptionAllowed, boolean saleAllowed,
                                     boolean central) {
        StoreAccess base = access(teamRole, effective, consumptionAllowed);
        if (!saleAllowed) {
            return base;
        }
        boolean owner = TeamRoles.OWNER.equals(teamRole);
        boolean pharmacist = TeamRoles.PHARMACIST.equals(teamRole);
        boolean cashier = effective == StoreRole.CASHIER && !central;
        boolean hod = effective == StoreRole.APPROVER || effective == StoreRole.DELEGATE;
        boolean sell = owner || pharmacist || cashier;
        return base.withSale(sell, sell && pharmacist, owner || (pharmacist && sell), sell,
                owner || pharmacist || hod, pharmacist, pharmacist || effective == StoreRole.STORE_USER,
                owner || pharmacist || hod, sell);
    }

    /** The SAH-79 table; sale capabilities stay false (use the five-argument form when the store is known). */
    public static StoreAccess access(String teamRole, StoreRole effective, boolean consumptionAllowed) {
        if (TeamRoles.OWNER.equals(teamRole)) {
            return new StoreAccess(true, true, true, false, true, true, true, true, true, true, true, true, true, true, true, false);
        }
        boolean pharmacist = TeamRoles.PHARMACIST.equals(teamRole);
        switch (effective) {
            case APPROVER:
            case DELEGATE:
                return new StoreAccess(true, true, true, pharmacist, true, pharmacist, true, pharmacist, true, true, true, true,
                        true, true, true, pharmacist);
            case CENTRAL_STORE_USER:
                return new StoreAccess(false, false, true, pharmacist, consumptionAllowed, true, true, true, true, true, true,
                        consumptionAllowed, true, true, false, pharmacist);
            case CASHIER:
                // DEC-092 item 4: a cashier reads stock and sells (the sale columns come from the five-argument form); nothing else.
                // Schedule X still follows the team role alone (a store role never gives it), as in every other branch.
                return new StoreAccess(false, false, false, pharmacist, false, false, true, false, false, false, false, false,
                        false, false, false, pharmacist);
            case STORE_USER:
            default:
                return new StoreAccess(true, false, true, pharmacist, consumptionAllowed, pharmacist, true, pharmacist, pharmacist,
                        true, true, consumptionAllowed, pharmacist, true, false, pharmacist);
        }
    }
}
