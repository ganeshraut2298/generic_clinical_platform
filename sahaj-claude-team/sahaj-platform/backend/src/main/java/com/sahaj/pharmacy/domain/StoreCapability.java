package com.sahaj.pharmacy.domain;

/** What a person can do inside a store (the access columns of mockup 16). Worked out on every read, never stored. */
public enum StoreCapability {
    RAISE_INDENT,
    APPROVE,
    RECEIVE,
    RECEIVE_SCHEDULE_X,
    CONSUME_RETURN,
    UPLOAD_OPENING_STOCK,
    /** SAH-79 (ADR-029 P8): any ACTIVE mapping; the owner at every store. */
    READ_STOCK,
    /** SAH-79-1 (OQ-79-30): the same people as {@link #UPLOAD_OPENING_STOCK}. */
    POST_OPENING_STOCK,
    /** SAH-79-5 (OQ-79-27): HOD, current delegate, PHARMACIST member, Central Store user, owner. */
    ADJUST_STOCK,
    /**
     * SAH-79-6 to 79-8 (OQ-79-31): buying at the Central Store: a Central Store user or the owner. Not part of the StoreAccess
     * columns (it needs the store type); {@link com.sahaj.pharmacy.application.StoreAccessGuard} works it out.
     */
    PURCHASE
}
