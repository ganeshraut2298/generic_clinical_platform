package com.sahaj.ipd.domain.order;

import java.time.Instant;
import java.util.UUID;

/** SAH-76 (ADR-027 D4): one hold of an order, from {@code heldAt} until {@code resumedAt} (null while still on hold). */
public record OrderHoldInterval(Instant heldAt, UUID heldBy, String holdReason, Instant resumedAt, UUID resumedBy) {

    public boolean isOpen() {
        return resumedAt == null;
    }

    /** True when {@code instant} is in {@code [heldAt, resumedAt)}; open holds run on. */
    public boolean covers(Instant instant) {
        return !instant.isBefore(heldAt) && (resumedAt == null || instant.isBefore(resumedAt));
    }
}
