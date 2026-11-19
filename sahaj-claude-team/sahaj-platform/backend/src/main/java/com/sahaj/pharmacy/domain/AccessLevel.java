package com.sahaj.pharmacy.domain;

/** The access level an operation needs (ADR-028 D4 layer 2; {@code x-required-role} of docs/api/pharmacy.yaml). */
public enum AccessLevel {
    /** OWNER only. */
    SETUP_EDIT,
    /** OWNER, any PHARMACIST member, any member with an ACTIVE Central Store user mapping. */
    SETUP_VIEW,
    /** OWNER, any PHARMACIST member, any member with at least one ACTIVE mapping to an ACTIVE store. */
    PHARMACY_ACCESS
}
