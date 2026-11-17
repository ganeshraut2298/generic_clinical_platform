package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A recording {@link OrderNotices} for the order action tests: every notice written and every dose acknowledgement. */
class FakeOrderNotices implements OrderNotices {

    /** One written notice. */
    record Recorded(UUID admissionId, OrderRef order, NoticeKind kind, OrderPriority priority, UUID actor) {
    }

    final List<Recorded> recorded = new ArrayList<>();
    final List<UUID> acknowledgedByDose = new ArrayList<>();
    final Map<UUID, Integer> counts = new HashMap<>();
    final Map<UUID, AckState> acks = new HashMap<>();

    @Override
    public void record(UUID tenantId, UUID admissionId, OrderRef order, NoticeKind kind, OrderPriority priority,
                       UUID actor) {
        recorded.add(new Recorded(admissionId, order, kind, priority, actor));
    }

    @Override
    public int acknowledgeByDose(UUID tenantId, UUID orderId, UUID actor) {
        acknowledgedByDose.add(orderId);
        return 0;
    }

    @Override
    public Map<UUID, Integer> openCounts(UUID tenantId, Collection<UUID> admissionIds) {
        return counts;
    }

    @Override
    public Map<UUID, OpenCount> openStats(UUID tenantId, Collection<UUID> admissionIds, Instant asOf) {
        Map<UUID, OpenCount> stats = new HashMap<>();
        counts.forEach((id, count) -> stats.put(id, new OpenCount(count, 0, 0)));
        return stats;
    }

    @Override
    public Map<UUID, AckState> ackStates(UUID tenantId, UUID admissionId) {
        return acks;
    }

    List<NoticeKind> kinds() {
        List<NoticeKind> kinds = new ArrayList<>();
        for (Recorded r : recorded) {
            kinds.add(r.kind());
        }
        return kinds;
    }
}
