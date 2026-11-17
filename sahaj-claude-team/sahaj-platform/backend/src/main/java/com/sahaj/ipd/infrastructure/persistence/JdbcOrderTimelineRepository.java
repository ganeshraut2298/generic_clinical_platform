package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.OrderTimelineRepository;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SAH-76 (ADR-027 D2): the timeline of every medicine order of a set of admissions in three set reads (the heads, the
 * versions, the holds), whatever the number of orders. The versions and the holds are found through the head's
 * admission, so the statements use the indexes of the head (admission, ordered) and the unique key of the version.
 * The indication, the change reason and the hold reason are clinical text: read here for the views, never logged.
 */
@Repository
public class JdbcOrderTimelineRepository implements OrderTimelineRepository {

    private static final String HEADS = """
            select o.id, o.admission_id, o.status, o.stopped_at
            from ipd.medicine_order o
            where o.tenant_id = :tenantId and o.admission_id in (:admissionIds)
            order by o.ordered_at, o.id
            """;
    private static final String VERSIONS = """
            select v.order_id, v.version_no, v.kind, v.frequency_key, v.dose_amount, v.dose_unit, v.route_key,
                   v.start_at, v.end_at, v.indication, v.priority, v.min_interval_min, v.max_per_24h, v.change_reason,
                   v.created_by, v.created_at, v.superseded_at, v.verification, v.verified_by, v.verified_at
            from ipd.medicine_order_version v
            join ipd.medicine_order o on o.tenant_id = v.tenant_id and o.id = v.order_id
            where v.tenant_id = :tenantId and o.admission_id in (:admissionIds)
            order by v.order_id, v.version_no
            """;
    private static final String HOLDS = """
            select h.medicine_order_id, h.held_at, h.held_by, h.hold_reason, h.resumed_at, h.resumed_by
            from ipd.order_hold h
            join ipd.medicine_order o on o.tenant_id = h.tenant_id and o.id = h.medicine_order_id
            where h.tenant_id = :tenantId and o.admission_id in (:admissionIds)
            order by h.medicine_order_id, h.held_at
            """;

    private record Head(UUID orderId, UUID admissionId, OrderStatus status, Instant stoppedAt) {
    }

    private final JdbcClient jdbc;

    public JdbcOrderTimelineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, OrderTimeline> findTimelines(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, OrderTimeline> timelines = new LinkedHashMap<>();
        if (admissionIds.isEmpty()) {
            return timelines;
        }
        List<Head> heads = jdbc.sql(HEADS).param("tenantId", tenantId).param("admissionIds", admissionIds)
                .query((rs, rowNum) -> new Head(rs.getObject("id", UUID.class),
                        rs.getObject("admission_id", UUID.class), OrderStatus.valueOf(rs.getString("status")),
                        instant(rs, "stopped_at"))).list();
        Map<UUID, List<MedicineOrderVersion>> versions = new HashMap<>();
        for (MedicineOrderVersion v : jdbc.sql(VERSIONS).param("tenantId", tenantId)
                .param("admissionIds", admissionIds).query((rs, rowNum) -> version(rs)).list()) {
            versions.computeIfAbsent(v.orderId(), k -> new ArrayList<>()).add(v);
        }
        Map<UUID, List<OrderHoldInterval>> holds = new HashMap<>();
        jdbc.sql(HOLDS).param("tenantId", tenantId).param("admissionIds", admissionIds).query((rs, rowNum) -> {
            holds.computeIfAbsent(rs.getObject("medicine_order_id", UUID.class), k -> new ArrayList<>())
                    .add(new OrderHoldInterval(instant(rs, "held_at"), rs.getObject("held_by", UUID.class),
                            rs.getString("hold_reason"), instant(rs, "resumed_at"),
                            rs.getObject("resumed_by", UUID.class)));
            return 1;
        }).list();
        for (Head head : heads) {
            List<MedicineOrderVersion> own = versions.get(head.orderId());
            if (own == null || own.isEmpty()) {
                throw new IllegalStateException("A medicine order has no version row");
            }
            timelines.put(head.orderId(), new OrderTimeline(head.orderId(), head.admissionId(), head.status(), own,
                    holds.getOrDefault(head.orderId(), List.of()), head.stoppedAt()));
        }
        return timelines;
    }

    private static MedicineOrderVersion version(ResultSet rs) throws SQLException {
        return new MedicineOrderVersion(rs.getObject("order_id", UUID.class), rs.getInt("version_no"),
                Kind.valueOf(rs.getString("kind")), rs.getString("frequency_key"), rs.getBigDecimal("dose_amount"),
                rs.getString("dose_unit"), rs.getString("route_key"), instant(rs, "start_at"), instant(rs, "end_at"),
                rs.getString("indication"), OrderPriority.valueOf(rs.getString("priority")),
                (Integer) rs.getObject("min_interval_min"), (Integer) rs.getObject("max_per_24h"),
                rs.getString("change_reason"), rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "superseded_at"), Verification.valueOf(rs.getString("verification")),
                rs.getObject("verified_by", UUID.class), instant(rs, "verified_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
