package com.sahaj.ipd.domain.order;

/** SAH-76 (ADR-027 D1): the kind of an order. Only MEDICINE exists; later order kinds add a value and their own tables. */
public enum OrderKind {
    MEDICINE
}
