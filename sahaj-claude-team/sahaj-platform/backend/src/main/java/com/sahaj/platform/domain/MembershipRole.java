package com.sahaj.platform.domain;

/** Mirrors the {@code ck_membership_role} check constraint (SAH-1, migration 1003; SAH-78 adds {@code STORE_STAFF}). */
public enum MembershipRole {
    OWNER,
    DOCTOR,
    VISITING_DOCTOR,
    NURSE,
    FRONT_DESK,
    BILLING,
    PHARMACIST,
    COUNSELLOR,
    /** SAH-78 (DEC-062 Q3, ADR-028 D4): store assistants and keepers; reaches only Pharmacy & Inventory, through store mappings. */
    STORE_STAFF
}
