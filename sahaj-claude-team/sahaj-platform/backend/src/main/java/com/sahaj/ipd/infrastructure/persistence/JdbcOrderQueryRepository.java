package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.OrderQueryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.medicine_order_query} (SAH-76, docs/database/SAH-76.md section 1.6): the insert names the four columns
 * the role may write; the close updates {@code reply, replied_by, closed_by} and the guard trigger stamps
 * {@code replied_at} and {@code closed_at}. The question and the reply are clinical text: bind parameters only, never
 * logged. Fixed statements only.
 */
@Repository
public class JdbcOrderQueryRepository implements OrderQueryRepository {

    private static final String COLUMNS = """
            q.id, q.order_id, q.order_version, q.question, q.asked_by, q.asked_at, q.reply, q.replied_by,
            q.replied_at, q.closed_by, q.closed_at
            """;
    private static final String INSERT = """
            insert into ipd.medicine_order_query (order_id, order_version, question, asked_by)
            values (:orderId, :orderVersion, :question, :askedBy)
            returning id, asked_at
            """;
    private static final String FIND_OPEN = "select " + COLUMNS + """
            from ipd.medicine_order_query q
            where q.tenant_id = :tenantId and q.order_id = :orderId and q.closed_at is null
            """;
    private static final String FIND_LATEST = "select distinct on (q.order_id) " + COLUMNS + """
            from ipd.medicine_order_query q
            where q.tenant_id = :tenantId and q.order_id in (:orderIds)
            order by q.order_id, q.asked_at desc, q.id
            """;
    private static final String CLOSE = """
            update ipd.medicine_order_query
            set reply = :reply, replied_by = :repliedBy, closed_by = :closedBy
            where tenant_id = :tenantId and order_id = :orderId and closed_at is null
            """;

    private final JdbcClient jdbc;

    public JdbcOrderQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public QueryRow insert(UUID tenantId, UUID orderId, int orderVersion, String question, UUID askedBy) {
        return jdbc.sql(INSERT).param("orderId", orderId).param("orderVersion", orderVersion)
                .param("question", question).param("askedBy", askedBy)
                .query((rs, rowNum) -> new QueryRow(rs.getObject("id", UUID.class), orderId, orderVersion, question,
                        askedBy, rs.getObject("asked_at", OffsetDateTime.class).toInstant(), null, null, null, null,
                        null))
                .single();
    }

    @Override
    public Optional<QueryRow> findOpen(UUID tenantId, UUID orderId) {
        return jdbc.sql(FIND_OPEN).param("tenantId", tenantId).param("orderId", orderId)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public Map<UUID, QueryRow> findLatest(UUID tenantId, Collection<UUID> orderIds) {
        Map<UUID, QueryRow> found = new HashMap<>();
        if (orderIds.isEmpty()) {
            return found;
        }
        for (QueryRow row : jdbc.sql(FIND_LATEST).param("tenantId", tenantId).param("orderIds", orderIds)
                .query((rs, rowNum) -> map(rs)).list()) {
            found.put(row.orderId(), row);
        }
        return found;
    }

    @Override
    public boolean close(UUID tenantId, UUID orderId, String closedBy, String reply, UUID repliedBy) {
        return jdbc.sql(CLOSE).param("tenantId", tenantId).param("orderId", orderId).param("closedBy", closedBy)
                .param("reply", reply).param("repliedBy", repliedBy).update() == 1;
    }

    private static QueryRow map(ResultSet rs) throws SQLException {
        return new QueryRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getInt("order_version"), rs.getString("question"), rs.getObject("asked_by", UUID.class),
                instant(rs, "asked_at"), rs.getString("reply"), rs.getObject("replied_by", UUID.class),
                instant(rs, "replied_at"), rs.getString("closed_by"), instant(rs, "closed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
