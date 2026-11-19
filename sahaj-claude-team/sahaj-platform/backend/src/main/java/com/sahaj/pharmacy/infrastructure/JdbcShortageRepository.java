package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ShortageRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.shortage} (SAH-79-12 and 79-13). Every statement names the tenant as well as relying on row level
 * security. Quantities and notes are never logged here.
 */
@Repository
public class JdbcShortageRepository implements ShortageRepository {

    private static final String COLUMNS = """
            s.id, s.unit_id, s.dispatch_line_id, d.reference, s.receiving_store_id, s.settling_store_id, s.product_id, s.batch_id,
            s.qty, s.status, s.approval_request_id, s.result_note, s.second_signer_membership_id, s.settled_by, s.settled_at,
            s.raised_by, s.created_at, s.version
            """;
    private static final String FROM = """
              from pharmacy.shortage s
              join pharmacy.dispatch_line l on l.tenant_id = s.tenant_id and l.id = s.dispatch_line_id
              join pharmacy.dispatch d on d.tenant_id = l.tenant_id and d.id = l.dispatch_id
             where s.tenant_id = :tenantId
            """;
    private static final String FIND = "select " + COLUMNS + FROM + " and s.id = :id";
    private static final String INSERT = """
            insert into pharmacy.shortage (id, tenant_id, unit_id, dispatch_line_id, receiving_store_id, settling_store_id,
                   product_id, batch_id, qty, raised_by, created_by)
            values (:id, :tenantId, :unitId, :lineId, :receiving, :settling, :productId, :batchId, :qty, :by, :by)
            """;
    private static final String MOVE = """
            update pharmacy.shortage
               set status = :status, result_note = cast(:note as text), second_signer_membership_id = cast(:signer as uuid),
                   settled_by = cast(:settledBy as uuid), settled_at = case when cast(:settledBy as uuid) is null then null else now() end,
                   approval_request_id = coalesce(cast(:requestId as uuid), approval_request_id), updated_at = now(),
                   updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String PAGE_WHERE = FROM + " and s.settling_store_id = :storeId"
            + " and (cast(:status as text) is null or s.status = :status)";
    private static final String PAGE = "select " + COLUMNS + PAGE_WHERE
            + " order by (s.status in ('FOUND', 'WRITTEN_OFF')), s.created_at, s.id limit :limit offset :offset";
    private static final String PAGE_COUNT = "select count(*)" + PAGE_WHERE;
    private static final String UNSETTLED_FOR_INDENT = """
            select coalesce(sum(s.qty), 0)
              from pharmacy.shortage s
              join pharmacy.dispatch_line l on l.tenant_id = s.tenant_id and l.id = s.dispatch_line_id
              join pharmacy.dispatch d on d.tenant_id = l.tenant_id and d.id = l.dispatch_id
             where s.tenant_id = :tenantId and d.indent_id = :indentId
               and s.status in ('OPEN', 'CLAIM_OPEN', 'WAITING_WRITE_OFF')
            """;

    private final JdbcClient jdbc;

    public JdbcShortageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcShortageRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND + " for update of s").param("tenantId", tenantId).param("id", id)
                .query(JdbcShortageRepository::map).optional();
    }

    @Override
    public void insert(UUID tenantId, NewShortage s) {
        jdbc.sql(INSERT).param("id", s.id()).param("tenantId", tenantId).param("unitId", s.unitId())
                .param("lineId", s.dispatchLineId()).param("receiving", s.receivingStoreId())
                .param("settling", s.settlingStoreId()).param("productId", s.productId()).param("batchId", s.batchId())
                .param("qty", s.qty()).param("by", s.raisedBy()).update();
    }

    @Override
    public boolean move(UUID tenantId, UUID id, long version, String status, String resultNote, UUID secondSigner,
                        UUID settledBy, UUID approvalRequestId, UUID actor) {
        return jdbc.sql(MOVE).param("tenantId", tenantId).param("id", id).param("version", version).param("status", status)
                .param("note", resultNote).param("signer", secondSigner).param("settledBy", settledBy)
                .param("requestId", approvalRequestId).param("actor", actor).update() == 1;
    }

    @Override
    public Slice page(UUID tenantId, UUID settlingStoreId, String status, int limit, int offset) {
        List<Row> items = jdbc.sql(PAGE).param("tenantId", tenantId).param("storeId", settlingStoreId)
                .param("status", status, java.sql.Types.VARCHAR).param("limit", limit).param("offset", offset)
                .query(JdbcShortageRepository::map).list();
        long total = jdbc.sql(PAGE_COUNT).param("tenantId", tenantId).param("storeId", settlingStoreId)
                .param("status", status, java.sql.Types.VARCHAR).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public int unsettledQtyForIndent(UUID tenantId, UUID indentId) {
        return jdbc.sql(UNSETTLED_FOR_INDENT).param("tenantId", tenantId).param("indentId", indentId).query(Long.class)
                .single().intValue();
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("dispatch_line_id", UUID.class), rs.getString("reference"),
                rs.getObject("receiving_store_id", UUID.class), rs.getObject("settling_store_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("qty"),
                rs.getString("status"), rs.getObject("approval_request_id", UUID.class), rs.getString("result_note"),
                rs.getObject("second_signer_membership_id", UUID.class), rs.getObject("settled_by", UUID.class),
                instant(rs.getTimestamp("settled_at")), rs.getObject("raised_by", UUID.class),
                instant(rs.getTimestamp("created_at")), rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
