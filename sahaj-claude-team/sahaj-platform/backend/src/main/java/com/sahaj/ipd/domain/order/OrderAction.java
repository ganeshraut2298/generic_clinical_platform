package com.sahaj.ipd.domain.order;

/** SAH-76 (ADR-027 D1): the actions of the order lifecycle table. */
public enum OrderAction {
    /** A new order; the next status depends on whether the version needs the pharmacist check. */
    SIGN,
    VERIFY, REJECT, QUERY, REPLY, CHANGE, HOLD, RESUME, STOP
}
