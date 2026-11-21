package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DispatchRepository;
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
 * JDBC over {@code pharmacy.dispatch}, {@code dispatch_line} and {@code dispatch_receipt_line} (SAH-79-11 and 79-12). Every
 * statement names the tenant as well as relying on row level security. Quantities and notes are never logged here.
 */
@Repository
public class JdbcDispatchRepository implements DispatchRepository {

    private static final String COLUMNS = """
            d.id, d.unit_id, d.source_type, d.indent_id, d.transfer_id, d.dispatch_seq, d.reference, d.from_store_id,
            d.to_store_id, d.status, d.dispatched_by, d.dispatched_at, d.second_signer_membership_id, d.received_by,
            d.received_at, d.receipt_second_signer_membership_id, d.rejected_note, d.short_note, d.version
            """;
    private static final String FIND = "select " + COLUMNS
            + " from pharmacy.dispatch d where d.tenant_id = :tenantId and d.id = :id";
    private static final String BY_INDENT = "select " + COLUMNS + """
              from pharmacy.dispatch d where d.tenant_id = :tenantId and d.indent_id = :indentId
             order by d.dispatch_seq, d.id
            """;
    private static final String LINE_COLUMNS = """
            l.id, l.dispatch_id, l.indent_line_id, l.product_id, l.batch_id, l.dispatched_qty, l.replaced_by_cs,
            l.change_reason_key, l.change_note, l.back_ordered_qty, l.schedule_x
            """;
    private static final String LINES = "select " + LINE_COLUMNS + """
              from pharmacy.dispatch_line l where l.tenant_id = :tenantId and l.dispatch_id = :dispatchId
             order by l.created_at, l.id
            """;
    private static final String UNRECEIVED = "select " + LINE_COLUMNS + """
              from pharmacy.dispatch_line l
             where l.tenant_id = :tenantId and l.dispatch_id = :dispatchId
               and not exists (select 1 from pharmacy.dispatch_receipt_line r
                                where r.tenant_id = l.tenant_id and r.dispatch_line_id = l.id)
             order by l.created_at, l.id
            """;
    private static final String FIND_LINE = "select " + LINE_COLUMNS
            + " from pharmacy.dispatch_line l where l.tenant_id = :tenantId and l.id = :id";
    private static final String RECEIPTS = """
            select r.dispatch_line_id, r.accepted_qty, r.rejected_qty, r.reject_reason_key, r.note, r.received_by, r.received_at,
                   r.second_signer_membership_id
              from pharmacy.dispatch_receipt_line r where r.tenant_id = :tenantId and r.dispatch_id = :dispatchId
             order by r.received_at, r.id
            """;
    private static final String BY_TRANSFER = "select " + COLUMNS
            + " from pharmacy.dispatch d where d.tenant_id = :tenantId and d.transfer_id = :transferId and d.source_type = 'TRANSFER'";
    private static final String INSERT_TRANSFER = """
            insert into pharmacy.dispatch (id, tenant_id, unit_id, source_type, transfer_id, reference, from_store_id, to_store_id,
                   dispatched_by, second_signer_membership_id, created_by)
            values (:id, :tenantId, :unitId, 'TRANSFER', :transferId, :reference, :fromStoreId, :toStoreId, :by,
                   cast(:signer as uuid), :by)
            """;
    private static final String INSERT_TRANSFER_LINE = """
            insert into pharmacy.dispatch_line (id, tenant_id, unit_id, dispatch_id, transfer_line_id, product_id, batch_id,
                   dispatched_qty, replaced_by_cs, back_ordered_qty, schedule_x, created_by)
            values (:id, :tenantId, :unitId, :dispatchId, :transferLineId, :productId, :batchId, :qty, false, 0, :scheduleX, :by)
            """;
    private static final String NEXT_SEQ = """
            select coalesce(max(d.dispatch_seq), 0) + 1 from pharmacy.dispatch d
             where d.tenant_id = :tenantId and d.indent_id = :indentId
            """;
    private static final String INSERT = """
            insert into pharmacy.dispatch (id, tenant_id, unit_id, source_type, indent_id, dispatch_seq, reference, from_store_id,
                   to_store_id, dispatched_by, second_signer_membership_id, created_by)
            values (:id, :tenantId, :unitId, 'INDENT', :indentId, :seq, :reference, :fromStoreId, :toStoreId, :by,
                   cast(:signer as uuid), :by)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.dispatch_line (id, tenant_id, unit_id, dispatch_id, indent_line_id, product_id, batch_id,
                   dispatched_qty, replaced_by_cs, change_reason_key, change_note, back_ordered_qty, schedule_x, created_by)
            values (:id, :tenantId, :unitId, :dispatchId, :indentLineId, :productId, :batchId, :qty, :replaced,
                   cast(:reason as text), cast(:note as text), :backOrdered, :scheduleX, :by)
            """;
    private static final String INSERT_RECEIPT = """
            insert into pharmacy.dispatch_receipt_line (tenant_id, unit_id, dispatch_id, dispatch_line_id, dispatched_qty,
                   accepted_qty, rejected_qty, reject_reason_key, note, received_by, second_signer_membership_id)
            values (:tenantId, :unitId, :dispatchId, :lineId, :dispatched, :accepted, :rejected, cast(:reason as text),
                   cast(:note as text), :by, cast(:signer as uuid))
            """;
    private static final String MARK_RECEIVED = """
            update pharmacy.dispatch
               set status = :status, received_by = :by, received_at = now(),
                   receipt_second_signer_membership_id = cast(:signer as uuid), rejected_note = cast(:rejectedNote as text),
                   short_note = cast(:shortNote as text), updated_at = now(), updated_by = :by, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String INCOMING_WHERE = " from pharmacy.dispatch d where d.tenant_id = :tenantId and d.to_store_id = :storeId"
            + " and ((:received = true and d.status = 'RECEIVED') or (:received = false and d.status <> 'RECEIVED'))";
    private static final String INCOMING = "select d.id, d.reference, d.source_type, d.from_store_id, d.status, d.dispatched_at,"
            + " (select count(*) from pharmacy.dispatch_line l where l.tenant_id = d.tenant_id and l.dispatch_id = d.id) as items"
            + INCOMING_WHERE + " order by d.dispatched_at, d.id limit :limit offset :offset";
    private static final String INCOMING_COUNT = "select count(*)" + INCOMING_WHERE;
    private static final String CANDIDATES = """
            with wanted as (
              select lower(regexp_replace(trim(coalesce(g.salt, p.salt)), '\\s+', ' ', 'g')) as salt,
                     coalesce(p.schedule, g.schedule) as schedule
                from pharmacy.product p left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
               where p.tenant_id = :tenantId and p.id = :productId)
            select p.id
              from pharmacy.product p
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id, wanted w
             where p.tenant_id = :tenantId and p.id <> :productId and p.status = 'ACTIVE' and (g.id is null or g.active)
               and w.salt <> ''
               and lower(regexp_replace(trim(coalesce(g.salt, p.salt)), '\\s+', ' ', 'g')) = w.salt
               and coalesce(p.schedule, g.schedule) is not distinct from w.schedule
             order by coalesce(g.name, p.name), p.id
             limit :limit
            """;
    private static final String SX_WAITING = """
            select count(*)
              from pharmacy.dispatch d
              join pharmacy.dispatch_line l on l.tenant_id = d.tenant_id and l.dispatch_id = d.id
             where d.tenant_id = :tenantId and d.indent_id = :indentId and d.status = 'PARTLY_RECEIVED' and l.schedule_x
               and not exists (select 1 from pharmacy.dispatch_receipt_line r
                                where r.tenant_id = l.tenant_id and r.dispatch_line_id = l.id)
            """;

    private final JdbcClient jdbc;

    public JdbcDispatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcDispatchRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND + " for update of d").param("tenantId", tenantId).param("id", id)
                .query(JdbcDispatchRepository::map).optional();
    }

    @Override
    public List<Row> byIndent(UUID tenantId, UUID indentId) {
        return jdbc.sql(BY_INDENT).param("tenantId", tenantId).param("indentId", indentId).query(JdbcDispatchRepository::map)
                .list();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID dispatchId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("dispatchId", dispatchId)
                .query(JdbcDispatchRepository::mapLine).list();
    }

    @Override
    public List<Line> unreceivedLines(UUID tenantId, UUID dispatchId) {
        return jdbc.sql(UNRECEIVED).param("tenantId", tenantId).param("dispatchId", dispatchId)
                .query(JdbcDispatchRepository::mapLine).list();
    }

    @Override
    public Optional<Line> findLine(UUID tenantId, UUID lineId) {
        return jdbc.sql(FIND_LINE).param("tenantId", tenantId).param("id", lineId).query(JdbcDispatchRepository::mapLine)
                .optional();
    }

    @Override
    public List<Receipt> receipts(UUID tenantId, UUID dispatchId) {
        return jdbc.sql(RECEIPTS).param("tenantId", tenantId).param("dispatchId", dispatchId)
                .query((rs, n) -> new Receipt(rs.getObject("dispatch_line_id", UUID.class), rs.getInt("accepted_qty"),
                        rs.getInt("rejected_qty"), rs.getString("reject_reason_key"), rs.getString("note"),
                        rs.getObject("received_by", UUID.class), instant(rs.getTimestamp("received_at")),
                        rs.getObject("second_signer_membership_id", UUID.class))).list();
    }

    @Override
    public int nextSeq(UUID tenantId, UUID indentId) {
        return jdbc.sql(NEXT_SEQ).param("tenantId", tenantId).param("indentId", indentId).query(Integer.class).single();
    }

    @Override
    public void insert(UUID tenantId, NewDispatch d) {
        jdbc.sql(INSERT).param("id", d.id()).param("tenantId", tenantId).param("unitId", d.unitId())
                .param("indentId", d.indentId()).param("seq", d.seq()).param("reference", d.reference())
                .param("fromStoreId", d.fromStoreId()).param("toStoreId", d.toStoreId()).param("by", d.dispatchedBy())
                .param("signer", d.secondSignerMembershipId()).update();
    }

    @Override
    public Optional<Row> byTransfer(UUID tenantId, UUID transferId) {
        return jdbc.sql(BY_TRANSFER).param("tenantId", tenantId).param("transferId", transferId)
                .query(JdbcDispatchRepository::map).optional();
    }

    @Override
    public void insertTransfer(UUID tenantId, NewTransferDispatch d) {
        jdbc.sql(INSERT_TRANSFER).param("id", d.id()).param("tenantId", tenantId).param("unitId", d.unitId())
                .param("transferId", d.transferId()).param("reference", d.reference()).param("fromStoreId", d.fromStoreId())
                .param("toStoreId", d.toStoreId()).param("by", d.dispatchedBy()).param("signer", d.secondSignerMembershipId())
                .update();
    }

    @Override
    public void insertTransferLine(UUID tenantId, NewTransferLine l) {
        jdbc.sql(INSERT_TRANSFER_LINE).param("id", l.id()).param("tenantId", tenantId).param("unitId", l.unitId())
                .param("dispatchId", l.dispatchId()).param("transferLineId", l.transferLineId()).param("productId", l.productId())
                .param("batchId", l.batchId()).param("qty", l.dispatchedQty()).param("scheduleX", l.scheduleX())
                .param("by", l.createdBy()).update();
    }

    @Override
    public void insertLine(UUID tenantId, NewLine l) {
        jdbc.sql(INSERT_LINE).param("id", l.id()).param("tenantId", tenantId).param("unitId", l.unitId())
                .param("dispatchId", l.dispatchId()).param("indentLineId", l.indentLineId()).param("productId", l.productId())
                .param("batchId", l.batchId()).param("qty", l.dispatchedQty()).param("replaced", l.replacedByCs())
                .param("reason", l.changeReasonKey()).param("note", l.changeNote()).param("backOrdered", l.backOrderedQty())
                .param("scheduleX", l.scheduleX()).param("by", l.createdBy()).update();
    }

    @Override
    public void insertReceipt(UUID tenantId, NewReceipt r) {
        jdbc.sql(INSERT_RECEIPT).param("tenantId", tenantId).param("unitId", r.unitId()).param("dispatchId", r.dispatchId())
                .param("lineId", r.dispatchLineId()).param("dispatched", r.dispatchedQty()).param("accepted", r.acceptedQty())
                .param("rejected", r.rejectedQty()).param("reason", r.rejectReasonKey()).param("note", r.note())
                .param("by", r.receivedBy()).param("signer", r.secondSignerMembershipId()).update();
    }

    @Override
    public boolean markReceived(UUID tenantId, UUID id, long version, String status, UUID receivedBy, UUID signer,
                                String rejectedNote, String shortNote) {
        return jdbc.sql(MARK_RECEIVED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("status", status).param("by", receivedBy).param("signer", signer)
                .param("rejectedNote", rejectedNote).param("shortNote", shortNote).update() == 1;
    }

    @Override
    public Slice incoming(UUID tenantId, UUID toStoreId, boolean received, int limit, int offset) {
        List<ListRow> items = jdbc.sql(INCOMING).param("tenantId", tenantId).param("storeId", toStoreId)
                .param("received", received).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new ListRow(rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("source_type"), rs.getObject("from_store_id", UUID.class), rs.getInt("items"),
                        rs.getString("status"), instant(rs.getTimestamp("dispatched_at")))).list();
        long total = jdbc.sql(INCOMING_COUNT).param("tenantId", tenantId).param("storeId", toStoreId)
                .param("received", received).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public List<UUID> replacementCandidates(UUID tenantId, UUID productId, int limit) {
        return jdbc.sql(CANDIDATES).param("tenantId", tenantId).param("productId", productId).param("limit", limit)
                .query(UUID.class).list();
    }

    @Override
    public int scheduleXWaiting(UUID tenantId, UUID indentId) {
        return jdbc.sql(SX_WAITING).param("tenantId", tenantId).param("indentId", indentId).query(Integer.class).single();
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        int seq = rs.getInt("dispatch_seq");
        Integer dispatchSeq = rs.wasNull() ? null : seq;
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getString("source_type"),
                rs.getObject("indent_id", UUID.class), rs.getObject("transfer_id", UUID.class), dispatchSeq,
                rs.getString("reference"), rs.getObject("from_store_id", UUID.class), rs.getObject("to_store_id", UUID.class),
                rs.getString("status"), rs.getObject("dispatched_by", UUID.class), instant(rs.getTimestamp("dispatched_at")),
                rs.getObject("second_signer_membership_id", UUID.class), rs.getObject("received_by", UUID.class),
                instant(rs.getTimestamp("received_at")), rs.getObject("receipt_second_signer_membership_id", UUID.class),
                rs.getString("rejected_note"), rs.getString("short_note"), rs.getLong("version"));
    }

    private static Line mapLine(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("dispatch_id", UUID.class),
                rs.getObject("indent_line_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getInt("dispatched_qty"), rs.getBoolean("replaced_by_cs"),
                rs.getString("change_reason_key"), rs.getString("change_note"), rs.getInt("back_ordered_qty"),
                rs.getBoolean("schedule_x"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
