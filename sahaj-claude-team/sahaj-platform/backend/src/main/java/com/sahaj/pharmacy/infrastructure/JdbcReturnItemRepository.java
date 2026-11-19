package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ReturnItemRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.return_item} and {@code pharmacy.return_item_note} (SAH-79-12 and 79-13). Every statement names the
 * tenant as well as relying on row level security. Notes and quantities are never logged here.
 */
@Repository
public class JdbcReturnItemRepository implements ReturnItemRepository {

    private static final String COLUMNS = """
            r.id, r.unit_id, r.doc_no, r.item_type, r.dispatch_line_id, r.store_return_id, r.damage_report_id, r.holding_store_id,
            r.accepting_store_id, r.product_id, r.batch_id, r.qty, r.reason_key, r.status, r.default_put_back_into, r.schedule_x,
            r.raised_by, r.raised_at, r.dispute_reason_key, r.dispute_by, r.dispute_at, r.accepted_by, r.accepted_at,
            r.second_signer_membership_id, r.result_note, r.version
            """;
    private static final String FIND = "select " + COLUMNS
            + " from pharmacy.return_item r where r.tenant_id = :tenantId and r.id = :id";
    private static final String BY_LINE = "select " + COLUMNS
            + " from pharmacy.return_item r where r.tenant_id = :tenantId and r.dispatch_line_id = :lineId";
    private static final String INSERT = """
            insert into pharmacy.return_item (id, tenant_id, unit_id, doc_no, item_type, dispatch_line_id, holding_store_id,
                   accepting_store_id, product_id, batch_id, qty, reason_key, default_put_back_into, schedule_x, raised_by,
                   created_by)
            values (:id, :tenantId, :unitId, :docNo, :type, :lineId, :holding, :accepting, :productId, :batchId, :qty, :reason,
                   :putBack, :scheduleX, :by, :by)
            """;
    private static final String NOTES = """
            select n.return_item_id, n.note, n.created_by, n.created_at
              from pharmacy.return_item_note n
             where n.tenant_id = :tenantId and n.return_item_id in (:ids)
             order by n.created_at, n.id
            """;
    private static final String INSERT_NOTE = """
            insert into pharmacy.return_item_note (tenant_id, unit_id, return_item_id, note, created_by)
            values (:tenantId, :unitId, :itemId, :note, :by)
            """;
    private static final String DISPUTE = """
            update pharmacy.return_item
               set status = 'DISPUTED', dispute_reason_key = :reason, dispute_by = :by, dispute_at = now(), updated_at = now(),
                   updated_by = :by, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'WAITING'
            """;
    private static final String ACCEPT = """
            update pharmacy.return_item
               set status = :status, accepted_by = :by, accepted_at = now(), second_signer_membership_id = cast(:signer as uuid),
                   result_note = :note, updated_at = now(), updated_by = :by, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status in ('WAITING', 'DISPUTED')
            """;
    private static final String PAGE_WHERE = " from pharmacy.return_item r where r.tenant_id = :tenantId"
            + " and ((:accepting = true and r.accepting_store_id = :storeId) or (:accepting = false and r.holding_store_id = :storeId))"
            + " and (cast(:status as text) is null or r.status = :status)";
    private static final String PAGE = "select " + COLUMNS + PAGE_WHERE
            + " order by r.raised_at desc, r.id limit :limit offset :offset";
    private static final String PAGE_COUNT = "select count(*)" + PAGE_WHERE;
    private static final String WAITING_FOR_INDENT = """
            select coalesce(sum(r.qty), 0)
              from pharmacy.return_item r
              join pharmacy.dispatch_line l on l.tenant_id = r.tenant_id and l.id = r.dispatch_line_id
              join pharmacy.dispatch d on d.tenant_id = l.tenant_id and d.id = l.dispatch_id
             where r.tenant_id = :tenantId and d.indent_id = :indentId and r.status in ('WAITING', 'DISPUTED')
            """;

    private final JdbcClient jdbc;

    public JdbcReturnItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcReturnItemRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND + " for update of r").param("tenantId", tenantId).param("id", id)
                .query(JdbcReturnItemRepository::map).optional();
    }

    @Override
    public Optional<Row> findByDispatchLine(UUID tenantId, UUID dispatchLineId) {
        return jdbc.sql(BY_LINE).param("tenantId", tenantId).param("lineId", dispatchLineId)
                .query(JdbcReturnItemRepository::map).optional();
    }

    @Override
    public void insert(UUID tenantId, NewItem i) {
        jdbc.sql(INSERT).param("id", i.id()).param("tenantId", tenantId).param("unitId", i.unitId()).param("docNo", i.docNo())
                .param("type", i.itemType()).param("lineId", i.dispatchLineId()).param("holding", i.holdingStoreId())
                .param("accepting", i.acceptingStoreId()).param("productId", i.productId()).param("batchId", i.batchId())
                .param("qty", i.qty()).param("reason", i.reasonKey()).param("putBack", i.defaultPutBackInto())
                .param("scheduleX", i.scheduleX()).param("by", i.raisedBy()).update();
    }

    @Override
    public List<Note> notes(UUID tenantId, UUID itemId) {
        return notesOf(tenantId, List.of(itemId)).getOrDefault(itemId, List.of());
    }

    @Override
    public Map<UUID, List<Note>> notesOf(UUID tenantId, Collection<UUID> itemIds) {
        Map<UUID, List<Note>> byItem = new LinkedHashMap<>();
        if (itemIds.isEmpty()) {
            return byItem;
        }
        jdbc.sql(NOTES).param("tenantId", tenantId).param("ids", itemIds)
                .query((rs, n) -> new Note(rs.getObject("return_item_id", UUID.class), rs.getString("note"),
                        rs.getObject("created_by", UUID.class), instant(rs.getTimestamp("created_at"))))
                .list().forEach(n -> byItem.computeIfAbsent(n.returnItemId(), k -> new ArrayList<>()).add(n));
        return byItem;
    }

    @Override
    public void insertNote(UUID tenantId, UUID unitId, UUID itemId, String note, UUID actor) {
        jdbc.sql(INSERT_NOTE).param("tenantId", tenantId).param("unitId", unitId).param("itemId", itemId).param("note", note)
                .param("by", actor).update();
    }

    @Override
    public boolean dispute(UUID tenantId, UUID id, long version, String reasonKey, UUID by) {
        return jdbc.sql(DISPUTE).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("reason", reasonKey).param("by", by).update() == 1;
    }

    @Override
    public boolean accept(UUID tenantId, UUID id, long version, String status, UUID by, UUID secondSigner, String resultNote) {
        return jdbc.sql(ACCEPT).param("tenantId", tenantId).param("id", id).param("version", version).param("status", status)
                .param("by", by).param("signer", secondSigner).param("note", resultNote).update() == 1;
    }

    @Override
    public Slice page(UUID tenantId, UUID storeId, boolean accepting, String status, int limit, int offset) {
        List<Row> items = jdbc.sql(PAGE).param("tenantId", tenantId).param("storeId", storeId).param("accepting", accepting)
                .param("status", status, java.sql.Types.VARCHAR).param("limit", limit).param("offset", offset)
                .query(JdbcReturnItemRepository::map).list();
        long total = jdbc.sql(PAGE_COUNT).param("tenantId", tenantId).param("storeId", storeId).param("accepting", accepting)
                .param("status", status, java.sql.Types.VARCHAR).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public int waitingQtyForIndent(UUID tenantId, UUID indentId) {
        return jdbc.sql(WAITING_FOR_INDENT).param("tenantId", tenantId).param("indentId", indentId).query(Long.class).single()
                .intValue();
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getString("doc_no"),
                rs.getString("item_type"), rs.getObject("dispatch_line_id", UUID.class),
                rs.getObject("store_return_id", UUID.class), rs.getObject("damage_report_id", UUID.class),
                rs.getObject("holding_store_id", UUID.class), rs.getObject("accepting_store_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("qty"),
                rs.getString("reason_key"), rs.getString("status"), rs.getString("default_put_back_into"),
                rs.getBoolean("schedule_x"), rs.getObject("raised_by", UUID.class), instant(rs.getTimestamp("raised_at")),
                rs.getString("dispute_reason_key"), rs.getObject("dispute_by", UUID.class),
                instant(rs.getTimestamp("dispute_at")), rs.getObject("accepted_by", UUID.class),
                instant(rs.getTimestamp("accepted_at")), rs.getObject("second_signer_membership_id", UUID.class),
                rs.getString("result_note"), rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
