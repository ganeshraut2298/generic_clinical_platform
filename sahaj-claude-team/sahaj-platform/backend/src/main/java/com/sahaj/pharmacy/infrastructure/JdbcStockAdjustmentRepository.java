package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StockAdjustmentRepository;
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
 * JDBC over {@code pharmacy.stock_adjustment}. Never writes the generated {@code difference}. The reason and the note are text
 * typed by a person and are never logged here.
 */
@Repository
public class JdbcStockAdjustmentRepository implements StockAdjustmentRepository {

    private static final String COLUMNS = """
            a.id, a.unit_id, a.doc_seq, a.doc_no, a.store_id, a.product_id, a.batch_id, a.book_qty, a.physical_qty,
            a.difference, a.value_paise, a.reason_key, a.note, a.corrects_ledger_id, a.second_signer_membership_id,
            a.status, a.approval_request_id, a.posted_at, a.created_at, a.created_by, a.version
            """;
    private static final String FROM = " from pharmacy.stock_adjustment a where a.tenant_id = :tenantId";
    private static final String FIND = "select " + COLUMNS + FROM + " and a.store_id = :storeId and a.id = :id";
    private static final String BY_ID = "select " + COLUMNS + FROM + " and a.id = :id";
    private static final String LIST = "select " + COLUMNS + FROM
            + " and a.store_id = :storeId and (cast(:status as text) is null or a.status = :status)"
            + " order by a.created_at desc, a.id limit :limit offset :offset";
    private static final String COUNT = "select count(*)" + FROM
            + " and a.store_id = :storeId and (cast(:status as text) is null or a.status = :status)";
    private static final String WAITING = """
            select exists (select 1 from pharmacy.stock_adjustment a
                            where a.tenant_id = :tenantId and a.store_id = :storeId and a.batch_id = :batchId
                              and a.status = 'WAITING_APPROVAL')
            """;
    private static final String INSERT = """
            insert into pharmacy.stock_adjustment (id, tenant_id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id, book_qty,
                   physical_qty, value_paise, reason_key, note, corrects_ledger_id, second_signer_membership_id, status,
                   posted_at, created_by)
            values (:id, :tenantId, :unitId, :docSeq, :docNo, :storeId, :productId, :batchId, :bookQty, :physicalQty, :valuePaise,
                   :reasonKey, cast(:note as text), cast(:corrects as uuid), cast(:signer as uuid), :status,
                   case when :status = 'POSTED' then now() end, :createdBy)
            returning id
            """;
    private static final String ATTACH = """
            update pharmacy.stock_adjustment
               set approval_request_id = :requestId, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
            """;
    private static final String SET_STATUS = """
            update pharmacy.stock_adjustment
               set status = :status, posted_at = case when :status = 'POSTED' then now() end, updated_at = now(),
                   updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
            """;

    private final JdbcClient jdbc;

    public JdbcStockAdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId, UUID adjustmentId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", adjustmentId)
                .query(JdbcStockAdjustmentRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID adjustmentId) {
        return jdbc.sql(BY_ID + " for update of a").param("tenantId", tenantId).param("id", adjustmentId)
                .query(JdbcStockAdjustmentRepository::map).optional();
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID adjustmentId) {
        return jdbc.sql(BY_ID).param("tenantId", tenantId).param("id", adjustmentId)
                .query(JdbcStockAdjustmentRepository::map).optional();
    }

    @Override
    public Slice list(UUID tenantId, UUID storeId, String status, int limit, int offset) {
        List<Row> items = jdbc.sql(LIST).param("tenantId", tenantId).param("storeId", storeId).param("status", status)
                .param("limit", limit).param("offset", offset).query(JdbcStockAdjustmentRepository::map).list();
        long total = jdbc.sql(COUNT).param("tenantId", tenantId).param("storeId", storeId).param("status", status)
                .query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public boolean waitingFor(UUID tenantId, UUID storeId, UUID batchId) {
        return Boolean.TRUE.equals(jdbc.sql(WAITING).param("tenantId", tenantId).param("storeId", storeId)
                .param("batchId", batchId).query(Boolean.class).single());
    }

    @Override
    public UUID insert(UUID tenantId, NewRow r) {
        return jdbc.sql(INSERT).param("id", r.id()).param("tenantId", tenantId).param("unitId", r.unitId()).param("docSeq", r.docSeq())
                .param("docNo", r.docNo()).param("storeId", r.storeId()).param("productId", r.productId())
                .param("batchId", r.batchId()).param("bookQty", r.bookQty()).param("physicalQty", r.physicalQty())
                .param("valuePaise", r.valuePaise()).param("reasonKey", r.reasonKey()).param("note", r.note())
                .param("corrects", r.correctsLedgerId()).param("signer", r.secondSignerMembershipId())
                .param("status", r.status()).param("createdBy", r.createdBy()).query(UUID.class).single();
    }

    @Override
    public void attachRequest(UUID tenantId, UUID adjustmentId, UUID requestId, UUID actorMembershipId) {
        jdbc.sql(ATTACH).param("tenantId", tenantId).param("id", adjustmentId).param("requestId", requestId)
                .param("actor", actorMembershipId).update();
    }

    @Override
    public boolean setStatus(UUID tenantId, UUID adjustmentId, String status, UUID actorMembershipId) {
        return jdbc.sql(SET_STATUS).param("tenantId", tenantId).param("id", adjustmentId).param("status", status)
                .param("actor", actorMembershipId).update() == 1;
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getLong("doc_seq"),
                rs.getString("doc_no"), rs.getObject("store_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getInt("book_qty"), rs.getInt("physical_qty"),
                rs.getInt("difference"), rs.getLong("value_paise"), rs.getString("reason_key"), rs.getString("note"),
                rs.getObject("corrects_ledger_id", UUID.class), rs.getObject("second_signer_membership_id", UUID.class),
                rs.getString("status"), rs.getObject("approval_request_id", UUID.class), instant(rs.getTimestamp("posted_at")),
                instant(rs.getTimestamp("created_at")), rs.getObject("created_by", UUID.class), rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
