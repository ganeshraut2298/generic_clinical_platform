package com.sahaj.pharmacy.domain;

/**
 * The role a person has in one store (ADR-028 D4). There is no PHARMACIST store role: pharmacist powers come from the
 * team role PHARMACIST (so nobody can be given them by a mapping). Phase 3 adds CASHIER.
 */
public enum StoreRole {
    STORE_USER,
    CENTRAL_STORE_USER,
    /** The store's HOD; at most one ACTIVE per store. */
    APPROVER,
    /** Dates required (at most 90 days); at most one ACTIVE per store. */
    DELEGATE,
    /**
     * SAH-80 (DEC-074 OQ-80-36): team roles BILLING and STORE_STAFF only, at an ACTIVE sale-allowed store that is not the
     * Central Store.
     */
    CASHIER
}
