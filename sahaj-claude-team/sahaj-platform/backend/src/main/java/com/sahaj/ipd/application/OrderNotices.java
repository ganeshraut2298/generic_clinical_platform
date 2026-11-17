package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * SAH-76 (ADR-027 D5): the nurse notice port. Owner: WP2. Used by the order actions (WP1: {@link #record}), the dose
 * and correction paths (WP3: {@link #acknowledgeByDose}) and the card, sheet and MAR counts ({@link #openCounts}).
 * Writes run inside the caller's transaction under the admission row lock.
 */
public interface OrderNotices {

    /** Creates one notice for the order version. A STOPPED notice is created even when NEW was never acknowledged. */
    void record(UUID tenantId, UUID admissionId, OrderRef order, NoticeKind kind, OrderPriority priority, UUID actor);

    /** A recorded dose acknowledges every open notice of the order (via DOSE). Returns how many were closed. */
    int acknowledgeByDose(UUID tenantId, UUID orderId, UUID actor);

    /** Open notice count per admission id; an admission with none is absent from the map (read it as 0). */
    Map<UUID, Integer> openCounts(UUID tenantId, Collection<UUID> admissionIds);

    /**
     * Added by WP2 (day-0 rule: the owner may add methods). The badge numbers of {@link #openCounts} with the STAT
     * and the overdue figures (the settings in force at {@code asOf}); an admission with no open notice is absent.
     */
    Map<UUID, OpenCount> openStats(UUID tenantId, Collection<UUID> admissionIds, Instant asOf);

    /**
     * Added by WP2 for the execution line of the EMR sheet (story 76-5): per order of the admission with any notice,
     * whether one is still open and who acknowledged the latest acknowledged NEW or CHANGED notice. An order with no
     * notice is absent (read it as acknowledged, nobody named).
     */
    Map<UUID, AckState> ackStates(UUID tenantId, UUID admissionId);

    /** Open notices of one admission: all, STAT, and those not acknowledged for longer than the setting. */
    record OpenCount(int count, int stat, int overdue) {

        public static final OpenCount NONE = new OpenCount(0, 0, 0);
    }

    /** Acknowledgement state of one order; the name is looked up by the caller. */
    record AckState(boolean open, UUID acknowledgedBy, Instant acknowledgedAt) {
    }
}
