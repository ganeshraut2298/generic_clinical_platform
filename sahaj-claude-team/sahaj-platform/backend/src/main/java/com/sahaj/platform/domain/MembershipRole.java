package com.sahaj.platform.domain;

/** Mirrors the {@code ck_membership_role} check constraint (SAH-1, migration 1003). */
public enum MembershipRole {
    OWNER,
    DOCTOR,
    VISITING_DOCTOR,
    NURSE,
    FRONT_DESK,
    BILLING,
    PHARMACIST,
    COUNSELLOR
}
