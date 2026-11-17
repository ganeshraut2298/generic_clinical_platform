package com.sahaj.ipd.domain.order;

import java.util.Objects;
import java.util.UUID;

/** SAH-76 (ADR-027 D1): the key of one order version across kinds: (kind, order id, version number, 1-based). */
public record OrderRef(OrderKind kind, UUID id, int version) {

    public OrderRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("version must be 1 or more");
        }
    }

    public static OrderRef medicine(UUID id, int version) {
        return new OrderRef(OrderKind.MEDICINE, id, version);
    }
}
