package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * SAH-76 (ADR-027 D1, D4; docs/database/SAH-76.md sections 1.5, 1.6, 6): the writes of {@code ipd.medicine_order_version}
 * and {@code ipd.order_hold}. The reads of the same rows are {@link OrderTimelineRepository}. Every method is scoped to
 * the tenant and runs in the caller's transaction, under the admission row lock. The times {@code superseded_at},
 * {@code verified_at}, {@code held_at} and {@code resumed_at} are stamped by the database; the methods return what was
 * stamped. A refusal by a database check or the guard trigger is a code fault and is never swallowed.
 */
public interface OrderVersionRepository {

    /** Inserts one version (the next number); returns its {@code created_at}. */
    Instant insert(UUID tenantId, NewVersion version);

    /** Stamps {@code superseded_at} on the version (once). */
    void markSuperseded(UUID tenantId, UUID orderId, int versionNo);

    /** Stamps the verify pair of the version (REQUIRED only, never the orderer); returns {@code verified_at}. */
    Instant markVerified(UUID tenantId, UUID orderId, int versionNo, UUID verifiedBy);

    /** Opens a hold (the order is ACTIVE); returns {@code held_at}. */
    Instant openHold(UUID tenantId, UUID orderId, UUID heldBy, String reason);

    /** Closes the open hold; false when there is none. */
    boolean closeHold(UUID tenantId, UUID orderId, UUID resumedBy);

    /**
     * The values of one version. {@code indication} and {@code changeReason} are clinical text: hidden from
     * {@code toString}.
     */
    record NewVersion(UUID orderId, int versionNo, Kind kind, String frequencyKey, BigDecimal doseAmount,
                      String doseUnit, String routeKey, Instant startAt, Instant endAt, String indication,
                      OrderPriority priority, Integer minIntervalMin, Integer maxPer24h, String changeReason,
                      UUID createdBy, Verification verification) {

        @Override
        public String toString() {
            return "NewVersion[orderId=" + orderId + ", versionNo=" + versionNo + ", kind=" + kind + "]";
        }
    }
}
