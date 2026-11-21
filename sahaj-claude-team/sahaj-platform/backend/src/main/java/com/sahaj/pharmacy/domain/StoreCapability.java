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
    /** SAH-79-14 (OQ-79-22): every ACTIVE mapping and the owner; the Central Store rule (damage report only) is the service's. */
    RETURN_DAMAGE,
    /** SAH-79-15 (0.3.6): any ACTIVE mapping and the owner (the sending side of a transfer). */
    DISPATCH_TRANSFER,
    /** SAH-79-16: the store's Consumption entry is Yes (same people as consumeReturn). */
    CONSUME,
    /** SAH-79-17: HOD, current delegate, Central Store user, PHARMACIST member, owner. */
    START_COUNT,
    /** SAH-79-17: any ACTIVE mapping and the owner. */
    COUNT,
    /** SAH-79-17: HOD, current delegate, owner. */
    REVIEW_COUNT,
    /** 0.3.6: the Schedule X handling flag (the set of receiveScheduleX; never the owner). */
    HANDLE_SCHEDULE_X,
    /**
     * SAH-79-6 to 79-8 (OQ-79-31): buying at the Central Store: a Central Store user or the owner. Not part of the StoreAccess
     * columns (it needs the store type); {@link com.sahaj.pharmacy.application.StoreAccessGuard} works it out.
     */
    PURCHASE,
    /** SAH-80 (ADR-030 S2): sale-allowed store and a mapped PHARMACIST, a CASHIER, or the owner. */
    SELL,
    /** Sell and a mapped PHARMACIST (schedule H, H1, X lines). Never the owner or a cashier. */
    SELL_SCHEDULED,
    /** Equals {@link #SELL_SCHEDULED}. */
    SUBSTITUTE,
    /** Equals {@link #SELL_SCHEDULED} (allergy and H1 overrides). */
    OVERRIDE_BLOCK,
    /** The owner, or a mapped PHARMACIST with sell (the bill's IST day is checked per bill by the service). */
    CANCEL_BILL,
    /** Sell. */
    SALES_RETURN,
    /** Sale-allowed store; a mapped PHARMACIST, the HOD or current delegate, or the owner. */
    ACCEPT_SALES_RETURN,
    /** Sale-allowed store and a mapped PHARMACIST. */
    DISPENSE,
    /** Sale-allowed store; a mapped PHARMACIST or a store user. */
    RECORD_WARD_RETURN,
    /** Equals {@link #DISPENSE}. */
    ACCEPT_WARD_RETURN,
    /** Sale-allowed store; the owner, a mapped PHARMACIST, the HOD or current delegate. */
    READ_REGISTERS,
    /** Sell. */
    DAY_CLOSE
}
