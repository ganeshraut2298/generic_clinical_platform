package com.sahaj.ipd.domain.order;

/** SAH-76 (ADR-027 D1): the status of an order head, describing its current version. */
public enum OrderStatus {
    PENDING, QUERIED, ACTIVE, ON_HOLD, REJECTED, STOPPED;

    /** True when no action moves the order any more. */
    public boolean isFinal() {
        return this == REJECTED || this == STOPPED;
    }
}
