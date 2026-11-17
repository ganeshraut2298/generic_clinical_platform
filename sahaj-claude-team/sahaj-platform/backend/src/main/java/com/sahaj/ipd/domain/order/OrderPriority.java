package com.sahaj.ipd.domain.order;

/** SAH-76 (ADR-027 D1): priority of an order version. STAT makes a one-time (ONCE) medicine version. */
public enum OrderPriority {
    ROUTINE, URGENT, STAT
}
