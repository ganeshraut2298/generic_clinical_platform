package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.IndentRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.indent} and {@code pharmacy.indent_line} (SAH-79-9 and 79-10). Every read of lines adds
 * {@code not removed}. Quantities are issue units and are never logged here.
 */
@Repository
public class JdbcIndentRepository implements IndentRepository {

    private static final String COLUMNS = """
            i.id, i.unit_id, i.from_store_id, i.to_store_id, i.doc_seq, i.doc_no, i.priority, i.status, i.approval_request_id,
            i.raised_at, i.cancel_reason_key, i.cancel_note, i.created_at, i.created_by, i.version
            """;
    private static final String FROM = " from pharmacy.indent i where i.tenant_id = :tenantId";
    private static final String FIND = "select " + COLUMNS + FROM + " and i.id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String LINE_COLUMNS = """
            l.id, l.product_id, l.sort, l.requested_qty, l.approved_qty, l.hod_remark_reason_key, l.dispatched_qty, l.received_qty,
            l.rejected_qty, l.short_qty, l.closed_qty, l.balance_action, l.close_reason_key, l.replacement_product_id,
            l.replacement_reason_key
            """;
    private static final String LINES = "select " + LINE_COLUMNS + """
              from pharmacy.indent_line l
             where l.tenant_id = :tenantId and l.indent_id = :id and not l.removed
             order by l.sort, l.id
            """;
    private static final String LINES_FOR_UPDATE = "select " + LINE_COLUMNS + """
              from pharmacy.indent_line l
             where l.tenant_id = :tenantId and l.indent_id = :id and not l.removed
             order by l.id
               for update of l
            """;
    private static final String APPLY_DISPATCH = """
            update pharmacy.indent_line
               set dispatched_qty = dispatched_qty + :dispatched, closed_qty = closed_qty + :closed,
                   balance_action = coalesce(cast(:action as text), balance_action),
                   close_reason_key = coalesce(cast(:closeReason as text), close_reason_key),
                   replacement_product_id = coalesce(cast(:replacement as uuid), replacement_product_id),
                   replacement_reason_key = coalesce(cast(:replacementReason as text), replacement_reason_key),
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String APPLY_RECEIPT = """
            update pharmacy.indent_line
               set received_qty = received_qty + :received, rejected_qty = rejected_qty + :rejected,
                   short_qty = short_qty + :short, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String INSERT = """
            insert into pharmacy.indent (id, tenant_id, unit_id, from_store_id, to_store_id, priority, created_by)
            values (:id, :tenantId, :unitId, :fromStoreId, :toStoreId, :priority, :actor)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.indent_line (tenant_id, unit_id, indent_id, product_id, sort, requested_qty, created_by)
            values (:tenantId, :unitId, :id, :productId, :sort, :qty, :actor)
            """;
    private static final String UPDATE_LINE = """
            update pharmacy.indent_line
               set sort = :sort, requested_qty = :qty, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String REMOVE_LINE = """
            update pharmacy.indent_line
               set removed = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String UPDATE_DRAFT = """
            update pharmacy.indent
               set priority = :priority, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String SUBMIT = """
            update pharmacy.indent
               set status = :status, doc_seq = :docSeq, doc_no = :docNo, raised_at = now(), updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String SET_STATUS = """
            update pharmacy.indent
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SET_STATUS_LOCKED = """
            update pharmacy.indent
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = :expected
            """;
    private static final String ATTACH = """
            update pharmacy.indent
               set approval_request_id = :requestId, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
            """;
    private static final String CANCEL = """
            update pharmacy.indent
               set status = 'CANCELLED', cancel_reason_key = :reasonKey, cancel_note = :note, updated_at = now(),
                   updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status in ('WAITING_APPROVAL', 'APPROVED')
            """;
    private static final String SET_APPROVED = """
            update pharmacy.indent_line
               set approved_qty = :approved, hod_remark_reason_key = :remark, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed and dispatched_qty = 0
            """;
    private static final String ITEMS = " (select count(*) from pharmacy.indent_line c where c.tenant_id = i.tenant_id"
            + " and c.indent_id = i.id and not c.removed) as items";
    private static final String LIST_COLUMNS = "select i.id, i.doc_no, i.from_store_id, i.to_store_id, i.priority, i.status,"
            + " i.created_by, i.raised_at," + ITEMS;
    private static final String RAISED_WHERE = FROM + " and i.from_store_id = :storeId and i.status <> 'DISCARDED'"
            + " and (i.status <> 'DRAFT' or i.created_by = :viewer)"
            + " and (cast(:status as text) is null or i.status = :status)";
    private static final String RAISED = LIST_COLUMNS + RAISED_WHERE + " order by i.created_at desc, i.id limit :limit offset :offset";
    private static final String RAISED_COUNT = "select count(*)" + RAISED_WHERE;
    private static final String DISPATCH_WHERE = FROM + " and i.to_store_id = :storeId"
            + " and i.status in ('APPROVED', 'PARTLY_DISPATCHED') and (cast(:status as text) is null or i.status = :status)";
    private static final String DISPATCH = LIST_COLUMNS + DISPATCH_WHERE
            + " order by (i.priority = 'URGENT') desc, i.raised_at, i.id limit :limit offset :offset";
    private static final String DISPATCH_COUNT = "select count(*)" + DISPATCH_WHERE;
    private static final String LATEST_RATE = """
            select b.purchase_rate
              from pharmacy.batch b
             where b.tenant_id = :tenantId and b.product_id = :productId and b.purchase_rate is not null
             order by b.first_received_at desc, b.id
             limit 1
            """;

    private final JdbcClient jdbc;

    public JdbcIndentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcIndentRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdateById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", id).query(JdbcIndentRepository::map).optional();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID indentId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("id", indentId).query(JdbcIndentRepository::mapLine).list();
    }

    @Override
    public List<Line> linesForUpdate(UUID tenantId, UUID indentId) {
        return jdbc.sql(LINES_FOR_UPDATE).param("tenantId", tenantId).param("id", indentId).query(JdbcIndentRepository::mapLine)
                .list();
    }

    @Override
    public void applyDispatch(UUID tenantId, UUID lineId, int dispatchedDelta, int closedDelta, String balanceAction,
                              String closeReasonKey, UUID replacementProductId, String replacementReasonKey, UUID actor) {
        jdbc.sql(APPLY_DISPATCH).param("tenantId", tenantId).param("lineId", lineId).param("dispatched", dispatchedDelta)
                .param("closed", closedDelta).param("action", balanceAction).param("closeReason", closeReasonKey)
                .param("replacement", replacementProductId).param("replacementReason", replacementReasonKey)
                .param("actor", actor).update();
    }

    @Override
    public void applyReceipt(UUID tenantId, UUID lineId, int receivedDelta, int rejectedDelta, int shortDelta, UUID actor) {
        jdbc.sql(APPLY_RECEIPT).param("tenantId", tenantId).param("lineId", lineId).param("received", receivedDelta)
                .param("rejected", rejectedDelta).param("short", shortDelta).param("actor", actor).update();
    }

    @Override
    public void insert(UUID tenantId, UUID unitId, UUID id, UUID fromStoreId, UUID toStoreId, String priority, UUID actor) {
        jdbc.sql(INSERT).param("id", id).param("tenantId", tenantId).param("unitId", unitId).param("fromStoreId", fromStoreId)
                .param("toStoreId", toStoreId).param("priority", priority).param("actor", actor).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, UUID indentId, NewLine line, UUID actor) {
        jdbc.sql(INSERT_LINE).param("tenantId", tenantId).param("unitId", unitId).param("id", indentId)
                .param("productId", line.productId()).param("sort", line.sort()).param("qty", line.requestedQty())
                .param("actor", actor).update();
    }

    @Override
    public void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor) {
        jdbc.sql(UPDATE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("sort", line.sort())
                .param("qty", line.requestedQty()).param("actor", actor).update();
    }

    @Override
    public void removeLine(UUID tenantId, UUID lineId, UUID actor) {
        jdbc.sql(REMOVE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("actor", actor).update();
    }

    @Override
    public boolean updateDraft(UUID tenantId, UUID id, long version, String priority, UUID actor) {
        return jdbc.sql(UPDATE_DRAFT).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("priority", priority).param("actor", actor).update() == 1;
    }

    @Override
    public boolean submit(UUID tenantId, UUID id, long version, String status, long docSeq, String docNo, UUID actor) {
        return jdbc.sql(SUBMIT).param("tenantId", tenantId).param("id", id).param("version", version).param("status", status)
                .param("docSeq", docSeq).param("docNo", docNo).param("actor", actor).update() == 1;
    }

    @Override
    public boolean setStatus(UUID tenantId, UUID id, long version, String status, UUID actor) {
        return jdbc.sql(SET_STATUS).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("status", status).param("actor", actor).update() == 1;
    }

    @Override
    public boolean setStatusLocked(UUID tenantId, UUID id, String expectedStatus, String status, UUID actor) {
        return jdbc.sql(SET_STATUS_LOCKED).param("tenantId", tenantId).param("id", id).param("expected", expectedStatus)
                .param("status", status).param("actor", actor).update() == 1;
    }

    @Override
    public boolean attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor) {
        return jdbc.sql(ATTACH).param("tenantId", tenantId).param("id", id).param("requestId", requestId).param("actor", actor)
                .update() == 1;
    }

    @Override
    public boolean cancel(UUID tenantId, UUID id, long version, String reasonKey, String note, UUID actor) {
        return jdbc.sql(CANCEL).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("reasonKey", reasonKey).param("note", note, java.sql.Types.VARCHAR).param("actor", actor).update() == 1;
    }

    @Override
    public void setApproved(UUID tenantId, UUID lineId, int approvedQty, String remarkReasonKey, UUID actor) {
        jdbc.sql(SET_APPROVED).param("tenantId", tenantId).param("lineId", lineId).param("approved", approvedQty)
                .param("remark", remarkReasonKey, java.sql.Types.VARCHAR).param("actor", actor).update();
    }

    @Override
    public Slice listRaised(UUID tenantId, UUID storeId, UUID viewer, String status, int limit, int offset) {
        List<ListRow> items = jdbc.sql(RAISED).param("tenantId", tenantId).param("storeId", storeId).param("viewer", viewer)
                .param("status", status, java.sql.Types.VARCHAR).param("limit", limit).param("offset", offset)
                .query(JdbcIndentRepository::mapList).list();
        long total = jdbc.sql(RAISED_COUNT).param("tenantId", tenantId).param("storeId", storeId).param("viewer", viewer)
                .param("status", status, java.sql.Types.VARCHAR).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public Slice listToDispatch(UUID tenantId, UUID storeId, String status, int limit, int offset) {
        List<ListRow> items = jdbc.sql(DISPATCH).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status, java.sql.Types.VARCHAR).param("limit", limit).param("offset", offset)
                .query(JdbcIndentRepository::mapList).list();
        long total = jdbc.sql(DISPATCH_COUNT).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status, java.sql.Types.VARCHAR).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public Optional<BigDecimal> latestPurchaseRate(UUID tenantId, UUID productId) {
        return jdbc.sql(LATEST_RATE).param("tenantId", tenantId).param("productId", productId).query(BigDecimal.class).optional();
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("from_store_id", UUID.class), rs.getObject("to_store_id", UUID.class), longOrNull(rs, "doc_seq"),
                rs.getString("doc_no"), rs.getString("priority"), rs.getString("status"),
                rs.getObject("approval_request_id", UUID.class), instant(rs, "raised_at"), rs.getString("cancel_reason_key"),
                rs.getString("cancel_note"), instant(rs, "created_at"), rs.getObject("created_by", UUID.class),
                rs.getLong("version"));
    }

    private static Line mapLine(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("sort"),
                rs.getInt("requested_qty"), intOrNull(rs, "approved_qty"), rs.getString("hod_remark_reason_key"),
                rs.getInt("dispatched_qty"), rs.getInt("received_qty"), rs.getInt("rejected_qty"), rs.getInt("short_qty"),
                rs.getInt("closed_qty"), rs.getString("balance_action"), rs.getString("close_reason_key"),
                rs.getObject("replacement_product_id", UUID.class), rs.getString("replacement_reason_key"));
    }

    private static ListRow mapList(ResultSet rs, int n) throws SQLException {
        return new ListRow(rs.getObject("id", UUID.class), rs.getString("doc_no"), rs.getObject("from_store_id", UUID.class),
                rs.getObject("to_store_id", UUID.class), rs.getInt("items"), rs.getString("priority"), rs.getString("status"),
                rs.getObject("created_by", UUID.class), instant(rs, "raised_at"));
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
