package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.MedicineOrderRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.medicine_order} (SAH-31, docs/database/SAH-31.md). The insert names exactly the seven columns the
 * database role may write; the three transition updates name exactly the columns it may change and never send a
 * time (the guard trigger stamps it). Statements are built from fixed fragments only. The warning lines are clinical
 * text: they travel as a quoted array literal in a bind parameter and are never logged.
 */
@Repository
public class JdbcMedicineOrderRepository implements MedicineOrderRepository {

    private static final String COLUMNS = """
            o.id, o.admission_id, o.client_key, o.drug_key, o.frequency_key, o.status, o.ordered_by, o.ordered_at,
            o.override_reason_key, o.warnings, o.verified_by, o.verified_at, o.rejected_by, o.rejected_at,
            o.reject_reason_key, o.stopped_by, o.stopped_at, o.version
            """;
    private static final String CLOCK = "select clock_timestamp() as db_now";
    private static final String FIND_BY_ID = "select " + COLUMNS + """
            from ipd.medicine_order o
            where o.tenant_id = :tenantId and o.id = :id
            """;
    private static final String FIND_BY_CLIENT_KEY = "select " + COLUMNS + """
            from ipd.medicine_order o
            where o.tenant_id = :tenantId and o.client_key = :clientKey
            """;
    private static final String FIND_BY_ADMISSION = "select " + COLUMNS + """
            from ipd.medicine_order o
            where o.tenant_id = :tenantId and o.admission_id = :admissionId
            order by o.ordered_at, o.id
            """;
    private static final String FIND_LIVE = "select " + COLUMNS + """
            from ipd.medicine_order o
            where o.tenant_id = :tenantId and o.admission_id in (:admissionIds)
              and o.status in ('PENDING', 'ACTIVE')
            order by o.admission_id, o.ordered_at, o.id
            """;
    private static final String INSERT = """
            insert into ipd.medicine_order (admission_id, client_key, drug_key, frequency_key, ordered_by,
                                            override_reason_key, warnings)
            values (:admissionId, :clientKey, :drugKey, :frequencyKey, :orderedBy, :reason,
                    cast(:warnings as text[]))
            returning id, ordered_at, version
            """;
    private static final String MARK_ACTIVE = """
            update ipd.medicine_order
            set status = 'ACTIVE', verified_by = :user, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'PENDING'
            """;
    private static final String MARK_REJECTED = """
            update ipd.medicine_order
            set status = 'REJECTED', rejected_by = :user, reject_reason_key = :reason, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'PENDING'
            """;
    private static final String MARK_STOPPED = """
            update ipd.medicine_order
            set status = 'STOPPED', stopped_by = :user, version = version + 1
            where tenant_id = :tenantId and id = :id and status in ('PENDING', 'ACTIVE')
            """;
    private static final String PENDING_HEAD = "select " + COLUMNS + """
            , a.patient_id, b.code as bed_code
            from ipd.medicine_order o
            join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id and a.status = 'ADMITTED'
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            where o.tenant_id = :tenantId and o.status = 'PENDING'
            """;
    private static final String PENDING_AFTER = " and (o.ordered_at, o.id) > (:afterAt, :afterId)\n";
    private static final String PENDING_TAIL = " order by o.ordered_at, o.id limit :limit";
    private static final String WAITING_KEYS = """
            select o.id, o.version
            from ipd.medicine_order o
            join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id and a.status = 'ADMITTED'
            where o.tenant_id = :tenantId and o.status = 'PENDING'
            """;

    private static final String COUNT_WAITING = """
            select count(*)
            from ipd.medicine_order o
            join ipd.admission a on a.tenant_id = o.tenant_id and a.id = o.admission_id and a.status = 'ADMITTED'
            where o.tenant_id = :tenantId and o.status = 'PENDING'
            """;

    private final JdbcClient jdbc;

    public JdbcMedicineOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant databaseNow() {
        return jdbc.sql(CLOCK).query((rs, rowNum) -> rs.getObject("db_now", OffsetDateTime.class).toInstant())
                .single();
    }

    @Override
    public Optional<OrderRow> findById(UUID tenantId, UUID orderId) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", orderId)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public Optional<OrderRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(FIND_BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public List<OrderRow> findByAdmission(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_BY_ADMISSION).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, rowNum) -> map(rs)).list();
    }

    @Override
    public List<OrderRow> findLiveByAdmissions(UUID tenantId, Collection<UUID> admissionIds) {
        if (admissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FIND_LIVE).param("tenantId", tenantId).param("admissionIds", admissionIds)
                .query((rs, rowNum) -> map(rs)).list();
    }

    @Override
    public OrderRow insert(UUID tenantId, NewOrder order) {
        return jdbc.sql(INSERT).param("admissionId", order.admissionId()).param("clientKey", order.clientKey())
                .param("drugKey", order.drugKey()).param("frequencyKey", order.frequencyKey())
                .param("orderedBy", order.orderedBy()).param("reason", order.overrideReasonKey())
                .param("warnings", arrayLiteral(order.warnings()))
                .query((rs, rowNum) -> new OrderRow(rs.getObject("id", UUID.class), order.admissionId(),
                        order.clientKey(), order.drugKey(), order.frequencyKey(), PENDING, order.orderedBy(),
                        rs.getObject("ordered_at", OffsetDateTime.class).toInstant(), order.overrideReasonKey(),
                        order.warnings(), null, null, null, null, null, null, null, rs.getLong("version")))
                .single();
    }

    @Override
    public boolean markActive(UUID tenantId, UUID orderId, UUID verifiedBy) {
        return jdbc.sql(MARK_ACTIVE).param("tenantId", tenantId).param("id", orderId).param("user", verifiedBy)
                .update() == 1;
    }

    @Override
    public boolean markRejected(UUID tenantId, UUID orderId, UUID rejectedBy, String reasonKey) {
        return jdbc.sql(MARK_REJECTED).param("tenantId", tenantId).param("id", orderId).param("user", rejectedBy)
                .param("reason", reasonKey).update() == 1;
    }

    @Override
    public boolean markStopped(UUID tenantId, UUID orderId, UUID stoppedBy) {
        return jdbc.sql(MARK_STOPPED).param("tenantId", tenantId).param("id", orderId).param("user", stoppedBy)
                .update() == 1;
    }

    @Override
    public List<PendingRow> findPending(UUID tenantId, Position after, int limit) {
        String sql = PENDING_HEAD + (after == null ? "" : PENDING_AFTER) + PENDING_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit);
        if (after != null) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(after.orderedAt(), ZoneOffset.UTC))
                    .param("afterId", after.id());
        }
        return spec.query((rs, rowNum) -> new PendingRow(map(rs), rs.getObject("patient_id", UUID.class),
                rs.getString("bed_code"))).list();
    }

    @Override
    public List<WaitingKey> findWaitingKeys(UUID tenantId) {
        return jdbc.sql(WAITING_KEYS).param("tenantId", tenantId)
                .query((rs, rowNum) -> new WaitingKey(rs.getObject("id", UUID.class), rs.getLong("version")))
                .list();
    }

    @Override
    public int countWaiting(UUID tenantId) {
        return jdbc.sql(COUNT_WAITING).param("tenantId", tenantId).query(Integer.class).single();
    }

    // ---- mapping ----

    private static OrderRow map(ResultSet rs) throws SQLException {
        return new OrderRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("client_key", UUID.class), rs.getString("drug_key"), rs.getString("frequency_key"),
                rs.getString("status"), rs.getObject("ordered_by", UUID.class), instant(rs, "ordered_at"),
                rs.getString("override_reason_key"), strings(rs.getArray("warnings")),
                rs.getObject("verified_by", UUID.class), instant(rs, "verified_at"),
                rs.getObject("rejected_by", UUID.class), instant(rs, "rejected_at"), rs.getString("reject_reason_key"),
                rs.getObject("stopped_by", UUID.class), instant(rs, "stopped_at"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> strings(Array array) throws SQLException {
        List<String> result = new ArrayList<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                result.add((String) value);
            }
        }
        return result;
    }

    /**
     * A PostgreSQL array literal with every element in double quotes, backslash and double quote escaped: whatever
     * the text holds, it is one element. The value is bound as a parameter and cast to text[]; it is never part of
     * the SQL text.
     */
    static String arrayLiteral(List<String> values) {
        StringBuilder literal = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append('"');
            for (char c : values.get(i).toCharArray()) {
                if (c == '\\' || c == '"') {
                    literal.append('\\');
                }
                literal.append(c);
            }
            literal.append('"');
        }
        return literal.append('}').toString();
    }
}
