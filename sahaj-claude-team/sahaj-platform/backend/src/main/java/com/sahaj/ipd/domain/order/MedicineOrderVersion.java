package com.sahaj.ipd.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * SAH-76 (ADR-027 D1): one row of {@code ipd.medicine_order_version}. {@code endAt} null means until stopped;
 * {@code supersededAt} null means this is the current version. {@code indication} and {@code changeReason} are clinical
 * text: never log them. A version is "in force" from {@code startAt} until the earlier of {@code endAt} and
 * {@code supersededAt}.
 */
public record MedicineOrderVersion(
        UUID orderId,
        int versionNo,
        Kind kind,
        String frequencyKey,
        BigDecimal doseAmount,
        String doseUnit,
        String routeKey,
        Instant startAt,
        Instant endAt,
        String indication,
        OrderPriority priority,
        Integer minIntervalMin,
        Integer maxPer24h,
        String changeReason,
        UUID createdBy,
        Instant createdAt,
        Instant supersededAt,
        Verification verification,
        UUID verifiedBy,
        Instant verifiedAt) {

    /** SCHEDULED: fixed frequency; PRN: as needed; ONCE: one-time (STAT). */
    public enum Kind { SCHEDULED, PRN, ONCE }

    /** Whether this version needed the pharmacist check, decided at signing or change. */
    public enum Verification { REQUIRED, NOT_REQUIRED }

    public OrderRef ref() {
        return OrderRef.medicine(orderId, versionNo);
    }

    public boolean isCurrent() {
        return supersededAt == null;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    /** The instant after which the version gives no more slots: min(endAt, supersededAt), or null when open. */
    public Instant effectiveEnd() {
        if (endAt == null) {
            return supersededAt;
        }
        if (supersededAt == null) {
            return endAt;
        }
        return endAt.isBefore(supersededAt) ? endAt : supersededAt;
    }

    /** True when {@code instant} is in {@code [startAt, effectiveEnd)}. */
    public boolean inForceAt(Instant instant) {
        Instant end = effectiveEnd();
        return !instant.isBefore(startAt) && (end == null || instant.isBefore(end));
    }
}
