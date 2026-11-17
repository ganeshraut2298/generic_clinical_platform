package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.OrderNoticeRepository;
import com.sahaj.ipd.application.OrderNotices.AckState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.order_notice} (SAH-76, docs/database/SAH-76.md section 1.6). The insert names the six granted columns;
 * the acknowledgement updates {@code acknowledged_by, acknowledged_via} only and the guard trigger stamps
 * {@code acknowledged_at}. Every read is by tenant and uses the partial index on open notices
 * {@code (tenant_id, admission_id, created_at) where acknowledged_at is null} or the admission index. A list is one
 * statement (plus one for the acknowledged tail): the order head, the admission, the bed and the "live dose exists"
 * test are joined, never read per row. Statements are fixed text; the optional admission filter and the cursor are
 * fixed fragments with bind parameters.
 */
@Repository
public class JdbcOrderNoticeRepository implements OrderNoticeRepository {

    private static final String HAS_LIVE_DOSE = """
            exists (select 1 from ipd.dose_administration d
                    where d.tenant_id = n.tenant_id and d.order_id = n.medicine_order_id
                      and d.order_version = n.order_version
                      and not exists (select 1 from ipd.dose_correction c
                                      where c.tenant_id = d.tenant_id and c.dose_id = d.id))
            """;
    private static final String COLUMNS = """
            n.id, n.admission_id, n.medicine_order_id as order_id, n.order_version, n.kind, n.priority, n.created_by,
            n.created_at, n.acknowledged_by, n.acknowledged_at, n.acknowledged_via, o.drug_key,
            o.status as order_status, o.current_version as order_current_version, o.stop_reason,
            """ + HAS_LIVE_DOSE + """
             as has_live_dose, a.patient_id, b.code as bed_code, a.status as admission_status
            """;
    private static final String FROM = """
            from ipd.order_notice n
            join ipd.medicine_order o on o.tenant_id = n.tenant_id and o.id = n.medicine_order_id
            join ipd.admission a on a.tenant_id = n.tenant_id and a.id = n.admission_id
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            """;
    private static final String INSERT = """
            insert into ipd.order_notice (admission_id, medicine_order_id, order_version, kind, priority, created_by)
            values (:admissionId, :orderId, :orderVersion, :kind, :priority, :createdBy)
            returning id, created_at
            """;
    private static final String FIND_BY_ID = "select " + COLUMNS + FROM + """
            where n.tenant_id = :tenantId and n.id = :id
            """;
    private static final String ACKNOWLEDGE = """
            update ipd.order_notice
            set acknowledged_by = :user, acknowledged_via = :via
            where tenant_id = :tenantId and id = :id and acknowledged_at is null
            """;
    private static final String ACKNOWLEDGE_BY_ORDER = """
            update ipd.order_notice
            set acknowledged_by = :user, acknowledged_via = 'DOSE'
            where tenant_id = :tenantId and medicine_order_id = :orderId and acknowledged_at is null
            """;
    private static final String FOR_ADMISSION_OPEN = "select " + COLUMNS + FROM + """
            where n.tenant_id = :tenantId and n.admission_id = :admissionId and n.acknowledged_at is null
            order by (n.priority = 'STAT') desc, n.created_at, n.id
            """;
    private static final String FOR_ADMISSION_ACKED = "select " + COLUMNS + FROM + """
            where n.tenant_id = :tenantId and n.admission_id = :admissionId and n.acknowledged_at is not null
            order by n.acknowledged_at desc, n.id
            limit :limit
            """;
    private static final String PAGE_HEAD = "select " + COLUMNS + FROM + """
            where n.tenant_id = :tenantId and n.acknowledged_at is null and a.status = 'ADMITTED'
            """;
    private static final String PAGE_IDS = " and n.admission_id in (:admissionIds)\n";
    private static final String PAGE_AFTER = """
             and ((case when n.priority = 'STAT' then 0 else 1 end), n.created_at, n.id) > (:rank, :afterAt, :afterId)
            """;
    private static final String PAGE_TAIL = """
             order by (case when n.priority = 'STAT' then 0 else 1 end), n.created_at, n.id
             limit :limit
            """;
    private static final String COUNT_OPEN = """
            select n.admission_id, count(*) as cnt
            from ipd.order_notice n
            where n.tenant_id = :tenantId and n.acknowledged_at is null and n.admission_id in (:admissionIds)
            group by n.admission_id
            """;
    private static final String STATS_HEAD = """
            select n.admission_id, count(*) as cnt,
                   count(*) filter (where n.priority = 'STAT') as stat,
                   count(*) filter (where (n.priority = 'STAT' and n.created_at <= :statCut)
                                       or (n.priority <> 'STAT' and n.created_at <= :cut)) as overdue,
                   min(v.created_at) filter (where n.priority = 'STAT' and v.kind = 'ONCE'
                                             and o.status in ('PENDING', 'QUERIED', 'ACTIVE', 'ON_HOLD')
                                             and not
            """ + HAS_LIVE_DOSE + """
                                           ) as stat_slot
            from ipd.order_notice n
            join ipd.medicine_order o on o.tenant_id = n.tenant_id and o.id = n.medicine_order_id
            join ipd.medicine_order_version v on v.tenant_id = n.tenant_id and v.order_id = n.medicine_order_id
                                              and v.version_no = n.order_version
            join ipd.admission a on a.tenant_id = n.tenant_id and a.id = n.admission_id and a.status = 'ADMITTED'
            where n.tenant_id = :tenantId and n.acknowledged_at is null
            """;
    private static final String STATS_TAIL = " group by n.admission_id";
    private static final String OPEN_DIGEST_HEAD = """
            select md5(coalesce(string_agg(n.id::text, ',' order by n.id), '')) as digest
            from ipd.order_notice n
            join ipd.admission a on a.tenant_id = n.tenant_id and a.id = n.admission_id and a.status = 'ADMITTED'
            where n.tenant_id = :tenantId and n.acknowledged_at is null
            """;
    private static final String ACK_STATES = """
            select n.medicine_order_id as order_id,
                   bool_or(n.acknowledged_at is null) as open,
                   (array_agg(n.acknowledged_by order by n.acknowledged_at desc)
                       filter (where n.acknowledged_at is not null and n.kind in ('NEW', 'CHANGED')))[1] as ack_by,
                   max(n.acknowledged_at) filter (where n.acknowledged_at is not null
                                                    and n.kind in ('NEW', 'CHANGED')) as ack_at
            from ipd.order_notice n
            where n.tenant_id = :tenantId and n.admission_id = :admissionId
            group by n.medicine_order_id
            """;

    private final JdbcClient jdbc;

    public JdbcOrderNoticeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Created insert(UUID tenantId, UUID admissionId, UUID orderId, int orderVersion, String kind,
                          String priority, UUID createdBy) {
        return jdbc.sql(INSERT).param("admissionId", admissionId).param("orderId", orderId)
                .param("orderVersion", orderVersion).param("kind", kind).param("priority", priority)
                .param("createdBy", createdBy)
                .query((rs, rowNum) -> new Created(rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    @Override
    public Optional<NoticeRow> findById(UUID tenantId, UUID noticeId) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", noticeId)
                .query((rs, rowNum) -> map(rs)).optional();
    }

    @Override
    public boolean acknowledge(UUID tenantId, UUID noticeId, UUID by) {
        return jdbc.sql(ACKNOWLEDGE).param("tenantId", tenantId).param("id", noticeId).param("user", by)
                .param("via", "TAP").update() == 1;
    }

    @Override
    public int acknowledgeByOrder(UUID tenantId, UUID orderId, UUID by) {
        return jdbc.sql(ACKNOWLEDGE_BY_ORDER).param("tenantId", tenantId).param("orderId", orderId)
                .param("user", by).update();
    }

    @Override
    public List<NoticeRow> findForAdmission(UUID tenantId, UUID admissionId, int ackedLimit) {
        List<NoticeRow> rows = new ArrayList<>(jdbc.sql(FOR_ADMISSION_OPEN).param("tenantId", tenantId)
                .param("admissionId", admissionId).query((rs, rowNum) -> map(rs)).list());
        rows.addAll(jdbc.sql(FOR_ADMISSION_ACKED).param("tenantId", tenantId).param("admissionId", admissionId)
                .param("limit", ackedLimit).query((rs, rowNum) -> map(rs)).list());
        return rows;
    }

    @Override
    public List<NoticeRow> findOpenPage(UUID tenantId, Collection<UUID> admissionIds, Cursor after, int limit) {
        String sql = PAGE_HEAD + (admissionIds == null ? "" : PAGE_IDS) + (after == null ? "" : PAGE_AFTER) + PAGE_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId).param("limit", limit);
        if (admissionIds != null) {
            spec = spec.param("admissionIds", admissionIds);
        }
        if (after != null) {
            spec = spec.param("rank", after.rank())
                    .param("afterAt", OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
                    .param("afterId", after.id());
        }
        return spec.query((rs, rowNum) -> map(rs)).list();
    }

    @Override
    public Map<UUID, Integer> countOpen(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (admissionIds.isEmpty()) {
            return counts;
        }
        jdbc.sql(COUNT_OPEN).param("tenantId", tenantId).param("admissionIds", admissionIds).query((rs, rowNum) -> {
            counts.put(rs.getObject("admission_id", UUID.class), rs.getInt("cnt"));
            return 1;
        }).list();
        return counts;
    }

    @Override
    public Map<UUID, Stats> stats(UUID tenantId, Collection<UUID> admissionIds, Instant overdueCut,
                                  Instant overdueStatCut) {
        Map<UUID, Stats> stats = new LinkedHashMap<>();
        if (admissionIds != null && admissionIds.isEmpty()) {
            return stats;
        }
        String sql = STATS_HEAD + (admissionIds == null ? "" : PAGE_IDS) + STATS_TAIL;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId)
                .param("cut", OffsetDateTime.ofInstant(overdueCut, ZoneOffset.UTC))
                .param("statCut", OffsetDateTime.ofInstant(overdueStatCut, ZoneOffset.UTC));
        if (admissionIds != null) {
            spec = spec.param("admissionIds", admissionIds);
        }
        spec.query((rs, rowNum) -> {
            stats.put(rs.getObject("admission_id", UUID.class), new Stats(rs.getInt("cnt"), rs.getInt("stat"),
                    rs.getInt("overdue"), instant(rs, "stat_slot")));
            return 1;
        }).list();
        return stats;
    }

    @Override
    public String openDigest(UUID tenantId, Collection<UUID> admissionIds) {
        if (admissionIds != null && admissionIds.isEmpty()) {
            return EMPTY_DIGEST;
        }
        String sql = OPEN_DIGEST_HEAD + (admissionIds == null ? "" : PAGE_IDS);
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenantId", tenantId);
        if (admissionIds != null) {
            spec = spec.param("admissionIds", admissionIds);
        }
        return spec.query((rs, rowNum) -> rs.getString("digest")).single();
    }

    @Override
    public Map<UUID, AckState> findAckStates(UUID tenantId, UUID admissionId) {
        Map<UUID, AckState> states = new HashMap<>();
        jdbc.sql(ACK_STATES).param("tenantId", tenantId).param("admissionId", admissionId).query((rs, rowNum) -> {
            states.put(rs.getObject("order_id", UUID.class), new AckState(rs.getBoolean("open"),
                    rs.getObject("ack_by", UUID.class), instant(rs, "ack_at")));
            return 1;
        }).list();
        return states;
    }

    private static NoticeRow map(ResultSet rs) throws SQLException {
        return new NoticeRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getInt("order_version"), rs.getString("kind"),
                rs.getString("priority"), rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                rs.getObject("acknowledged_by", UUID.class), instant(rs, "acknowledged_at"),
                rs.getString("acknowledged_via"), rs.getString("drug_key"), rs.getString("order_status"),
                rs.getInt("order_current_version"), rs.getString("stop_reason"), rs.getBoolean("has_live_dose"),
                rs.getObject("patient_id", UUID.class), rs.getString("bed_code"), rs.getString("admission_status"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
