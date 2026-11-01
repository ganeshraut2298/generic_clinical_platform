package com.sahaj.platform.domain;

/** Mirrors the {@code ck_membership_status} check constraint (SAH-1, migration 1003). */
public enum MembershipStatus {
    ACTIVE,
    INACTIVE,
    REMOVED
}
