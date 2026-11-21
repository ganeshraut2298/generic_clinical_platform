package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.TransferRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.transfer} and {@code transfer_line} (SAH-79-15). Every statement names the tenant as well as relying on row level security. */
@Repository("pharmacyJdbcTransferRepository")
public class JdbcTransferRepository implements TransferRepository {

    private static final String COLUMNS = """
            id, unit_id, doc_seq, doc_no, from_store_id, to_store_id, reason_key, note, status, approval_request_id,
            total_value_paise, created_at, created_by, version
            """;
    private static final String FIND = "select " + COLUMNS + " from pharmacy.transfer where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String LINES = """
            select id, transfer_id, product_id, quantity, value_paise from pharmacy.transfer_line
             where tenant_id = :tenantId and transfer_id = :transferId order by created_at, id
            """;
    private static final String INSERT = """
            insert into pharmacy.transfer (id, tenant_id, unit_id, doc_seq, doc_no, from_store_id, to_store_id, reason_key, note,
                   status, total_value_paise, created_by)
            values (:id, :tenantId, :unitId, :docSeq, :docNo, :fromStoreId, :toStoreId, :reason, :note, :status, :total, :by)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.transfer_line (id, tenant_id, unit_id, transfer_id, product_id, quantity, value_paise, created_by)
            values (:id, :tenantId, :unitId, :transferId, :productId, :quantity, :value, :by)
            """;
    private static final String ATTACH = """
            update pharmacy.transfer set approval_request_id = :requestId, updated_at = now(), updated_by = :by,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL' and approval_request_id is null
            """;
    private static final String SET_STATUS = """
            update pharmacy.transfer set status = :status, updated_at = now(), updated_by = :by, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String OPEN_FILTER = " and t.status in ('WAITING_APPROVAL', 'APPROVED', 'IN_TRANSIT')";
    private static final String LIST_BASE = """
            from pharmacy.transfer t
             where t.tenant_id = :tenantId and (t.from_store_id = :storeId or t.to_store_id = :storeId)
            """;
    private static final String LIST = """
            select t.id, t.doc_no, t.from_store_id, t.to_store_id, t.status, t.created_at,
                   (select count(*) from pharmacy.transfer_line l where l.tenant_id = t.tenant_id and l.transfer_id = t.id) as item_count,
                   (select d.dispatched_at from pharmacy.dispatch d where d.tenant_id = t.tenant_id and d.transfer_id = t.id
                       and d.source_type = 'TRANSFER') as dispatched_at
            """;

    private final JdbcClient jdbc;

    public JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcTransferRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", id).query(JdbcTransferRepository::map).optional();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID transferId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("transferId", transferId)
                .query((rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getObject("transfer_id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getInt("quantity"), rs.getLong("value_paise"))).list();
    }

    @Override
    public void insert(UUID tenantId, NewRow r) {
        jdbc.sql(INSERT).param("id", r.id()).param("tenantId", tenantId).param("unitId", r.unitId()).param("docSeq", r.docSeq())
                .param("docNo", r.docNo()).param("fromStoreId", r.fromStoreId()).param("toStoreId", r.toStoreId())
                .param("reason", r.reasonKey()).param("note", r.note()).param("status", r.status())
                .param("total", r.totalValuePaise()).param("by", r.createdBy()).update();
    }

    @Override
    public void insertLine(UUID tenantId, NewLine l) {
        jdbc.sql(INSERT_LINE).param("id", l.id()).param("tenantId", tenantId).param("unitId", l.unitId())
                .param("transferId", l.transferId()).param("productId", l.productId()).param("quantity", l.quantity())
                .param("value", l.valuePaise()).param("by", l.createdBy()).update();
    }

    @Override
    public void attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor) {
        if (jdbc.sql(ATTACH).param("tenantId", tenantId).param("id", id).param("requestId", requestId).param("by", actor)
                .update() != 1) {
            throw new IllegalStateException("A transfer changed before its request was attached");
        }
    }

    @Override
    public boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actor) {
        return jdbc.sql(SET_STATUS).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("status", status).param("by", actor).update() == 1;
    }

    @Override
    public Slice listOfStore(UUID tenantId, UUID storeId, boolean open, int limit, int offset) {
        String filter = open ? OPEN_FILTER : "";
        long total = jdbc.sql("select count(*) " + LIST_BASE + filter).param("tenantId", tenantId).param("storeId", storeId)
                .query(Long.class).single();
        List<ListRow> items = jdbc.sql(LIST + LIST_BASE + filter + " order by t.created_at desc, t.id limit :limit offset :offset")
                .param("tenantId", tenantId).param("storeId", storeId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new ListRow(rs.getObject("id", UUID.class), rs.getString("doc_no"),
                        rs.getObject("from_store_id", UUID.class), rs.getObject("to_store_id", UUID.class),
                        rs.getInt("item_count"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("dispatched_at")))).list();
        return new Slice(items, total);
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getLong("doc_seq"),
                rs.getString("doc_no"), rs.getObject("from_store_id", UUID.class), rs.getObject("to_store_id", UUID.class),
                rs.getString("reason_key"), rs.getString("note"), rs.getString("status"),
                rs.getObject("approval_request_id", UUID.class), rs.getLong("total_value_paise"),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("created_by", UUID.class), rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
