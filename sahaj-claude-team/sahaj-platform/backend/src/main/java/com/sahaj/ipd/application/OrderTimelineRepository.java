package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.OrderTimeline;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * SAH-76 (ADR-027 D2): the timeline read. One set read per table (versions, holds, head) for every medicine order of
 * the given admissions; no read per order. Owner: WP1 (real implementation {@code JdbcOrderTimelineRepository}).
 */
public interface OrderTimelineRepository {

    /**
     * @return the timeline of every medicine order of the admissions, keyed by order id (an admission with no order adds
     *         nothing; an empty input gives an empty map)
     */
    Map<UUID, OrderTimeline> findTimelines(UUID tenantId, Collection<UUID> admissionIds);

    /** The timeline of one order of the admission (three set reads of the admission, then the order is picked). */
    default OrderTimeline findTimeline(UUID tenantId, UUID admissionId, UUID orderId) {
        OrderTimeline timeline = findTimelines(tenantId, java.util.List.of(admissionId)).get(orderId);
        if (timeline == null) {
            throw new IllegalStateException("A medicine order has no timeline");
        }
        return timeline;
    }
}
