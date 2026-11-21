package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StockAuditRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.stock_audit} and {@code pharmacy.stock_audit_line}. Every write of the audit row adds one to
 * version. The reason and note typed by a person are never logged here.
 */
@Repository
public class JdbcStockAuditRepository implements StockAuditRepository {

    private static final String A_COLUMNS = """
            a.id, a.unit_id, a.doc_seq, a.doc_no, a.store_id, a.scope, a.status, a.submitted_by, a.submitted_at,
            a.approval_request_id, a.second_signer_membership_id, a.started_at, a.created_by, a.version
            """;
    private static final String A_FROM = " from pharmacy.stock_audit a where a.tenant_id = :tenantId";
    private static final String L_COLUMNS = """
            l.id, l.stock_audit_id, l.product_id, l.batch_id, l.counted_qty, l.not_counted, l.not_counted_reason_key,
            l.counted_by, l.counted_at, l.recount_requested, l.book_qty, l.book_occurred_at, l.variance_reason_key,
            l.variance_note, l.posted_at
            """;
    private static final String L_FROM = " from pharmacy.stock_audit_line l join pharmacy.product p on p.tenant_id = l.tenant_id"
            + " and p.id = l.product_id join pharmacy.batch b on b.tenant_id = l.tenant_id and b.id = l.batch_id"
            + " where l.tenant_id = :tenantId and l.stock_audit_id = :auditId";
    private static final String L_ORDER = " order by lower(p.name), b.expiry_date, l.id";

    private final JdbcClient jdbc;

    public JdbcStockAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Audit> find(UUID tenantId, UUID storeId, UUID auditId) {
        return jdbc.sql("select " + A_COLUMNS + A_FROM + " and a.store_id = :storeId and a.id = :id")
                .param("tenantId", tenantId).param("storeId", storeId).param("id", auditId)
                .query(JdbcStockAuditRepository::audit).optional();
    }

    @Override
    public Optional<Audit> findById(UUID tenantId, UUID auditId) {
        return jdbc.sql("select " + A_COLUMNS + A_FROM + " and a.id = :id").param("tenantId", tenantId).param("id", auditId)
                .query(JdbcStockAuditRepository::audit).optional();
    }

    @Override
    public Optional<Audit> findForUpdate(UUID tenantId, UUID auditId) {
        return jdbc.sql("select " + A_COLUMNS + A_FROM + " and a.id = :id for update").param("tenantId", tenantId)
                .param("id", auditId).query(JdbcStockAuditRepository::audit).optional();
    }

    @Override
    public Optional<Audit> openOf(UUID tenantId, UUID storeId) {
        return jdbc.sql("select " + A_COLUMNS + A_FROM + " and a.store_id = :storeId and a.status <> 'CLOSED'")
                .param("tenantId", tenantId).param("storeId", storeId).query(JdbcStockAuditRepository::audit).optional();
    }

    @Override
    public Set<UUID> openAuditProducts(UUID tenantId, UUID storeId) {
        List<UUID> ids = jdbc.sql("""
                select distinct l.product_id from pharmacy.stock_audit a
                  join pharmacy.stock_audit_line l on l.tenant_id = a.tenant_id and l.stock_audit_id = a.id
                 where a.tenant_id = :tenantId and a.store_id = :storeId and a.status <> 'CLOSED'
                """).param("tenantId", tenantId).param("storeId", storeId).query(UUID.class).list();
        return new HashSet<>(ids);
    }

    @Override
    public Slice list(UUID tenantId, UUID storeId, String status, int limit, int offset) {
        String where = " and a.store_id = :storeId and (cast(:status as text) is null or a.status = :status)";
        List<Audit> items = jdbc.sql("select " + A_COLUMNS + A_FROM + where
                        + " order by a.started_at desc, a.id limit :limit offset :offset")
                .param("tenantId", tenantId).param("storeId", storeId).param("status", status).param("limit", limit)
                .param("offset", offset).query(JdbcStockAuditRepository::audit).list();
        long total = jdbc.sql("select count(*)" + A_FROM + where).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public List<Seed> availableBatches(UUID tenantId, UUID storeId, Collection<UUID> productIdsOrNull) {
        String filter = productIdsOrNull == null ? "" : " and sb.product_id in (:products)";
        var spec = jdbc.sql("""
                select sb.product_id, sb.batch_id, sb.qty from pharmacy.stock_balance sb
                 where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'AVAILABLE' and sb.qty > 0
                """ + filter + " order by sb.product_id, sb.batch_id").param("tenantId", tenantId).param("storeId", storeId);
        if (productIdsOrNull != null) {
            spec = spec.param("products", productIdsOrNull);
        }
        return spec.query((rs, n) -> new Seed(rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class),
                rs.getInt("qty"))).list();
    }

    @Override
    public Set<UUID> knownProducts(UUID tenantId, Collection<UUID> productIds) {
        return new HashSet<>(jdbc.sql("select id from pharmacy.product where tenant_id = :tenantId and id in (:ids)")
                .param("tenantId", tenantId).param("ids", productIds).query(UUID.class).list());
    }

    @Override
    public void insert(UUID tenantId, UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, String scope,
                       UUID createdBy) {
        jdbc.sql("""
                insert into pharmacy.stock_audit (id, tenant_id, unit_id, doc_seq, doc_no, store_id, scope, created_by)
                values (:id, :tenantId, :unitId, :docSeq, :docNo, :storeId, :scope, :createdBy)
                """).param("id", id).param("tenantId", tenantId).param("unitId", unitId).param("docSeq", docSeq)
                .param("docNo", docNo).param("storeId", storeId).param("scope", scope).param("createdBy", createdBy).update();
    }

    @Override
    public void insertLines(UUID tenantId, UUID unitId, UUID auditId, List<Seed> seeds, UUID createdBy) {
        for (Seed seed : seeds) {
            jdbc.sql("""
                    insert into pharmacy.stock_audit_line (tenant_id, unit_id, stock_audit_id, product_id, batch_id, created_by)
                    values (:tenantId, :unitId, :auditId, :productId, :batchId, :createdBy)
                    """).param("tenantId", tenantId).param("unitId", unitId).param("auditId", auditId)
                    .param("productId", seed.productId()).param("batchId", seed.batchId()).param("createdBy", createdBy).update();
        }
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID auditId, boolean onlyRecount, int limit, int offset) {
        return jdbc.sql("select " + L_COLUMNS + L_FROM + (onlyRecount ? " and l.recount_requested" : "") + L_ORDER
                        + " limit :limit offset :offset").param("tenantId", tenantId).param("auditId", auditId)
                .param("limit", limit).param("offset", offset).query(JdbcStockAuditRepository::line).list();
    }

    @Override
    public long countLines(UUID tenantId, UUID auditId, boolean onlyRecount) {
        return jdbc.sql("select count(*) from pharmacy.stock_audit_line l where l.tenant_id = :tenantId and l.stock_audit_id = :auditId"
                        + (onlyRecount ? " and l.recount_requested" : "")).param("tenantId", tenantId).param("auditId", auditId)
                .query(Long.class).single();
    }

    @Override
    public List<Line> allLines(UUID tenantId, UUID auditId) {
        return jdbc.sql("select " + L_COLUMNS + L_FROM + L_ORDER).param("tenantId", tenantId).param("auditId", auditId)
                .query(JdbcStockAuditRepository::line).list();
    }

    @Override
    public List<Line> linesForUpdate(UUID tenantId, UUID auditId, Collection<UUID> ids) {
        return jdbc.sql("select " + L_COLUMNS + " from pharmacy.stock_audit_line l where l.tenant_id = :tenantId"
                        + " and l.stock_audit_id = :auditId and l.id in (:ids) order by l.id for update")
                .param("tenantId", tenantId).param("auditId", auditId).param("ids", ids)
                .query(JdbcStockAuditRepository::line).list();
    }

    @Override
    public Optional<Line> findLine(UUID tenantId, UUID lineId) {
        return jdbc.sql("select " + L_COLUMNS + " from pharmacy.stock_audit_line l where l.tenant_id = :tenantId and l.id = :id")
                .param("tenantId", tenantId).param("id", lineId).query(JdbcStockAuditRepository::line).optional();
    }

    @Override
    public void saveCount(UUID tenantId, UUID lineId, Integer counted, boolean notCounted, String reasonKey, UUID countedBy) {
        jdbc.sql("""
                update pharmacy.stock_audit_line
                   set counted_qty = cast(:counted as integer), not_counted = :notCounted,
                       not_counted_reason_key = cast(:reason as text), counted_by = :by, counted_at = now(),
                       recount_requested = false, book_qty = null, book_ledger_id = null, book_occurred_at = null,
                       updated_at = now(), updated_by = :by, version = version + 1
                 where tenant_id = :tenantId and id = :id
                """).param("tenantId", tenantId).param("id", lineId).param("counted", counted)
                .param("notCounted", notCounted).param("reason", reasonKey).param("by", countedBy).update();
    }

    @Override
    public void requestRecount(UUID tenantId, Collection<UUID> lineIds, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit_line
                   set counted_qty = null, not_counted = false, not_counted_reason_key = null, counted_by = null,
                       counted_at = null, recount_requested = true, variance_reason_key = null, variance_note = null,
                       book_qty = null, book_ledger_id = null, book_occurred_at = null, updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id in (:ids)
                """).param("tenantId", tenantId).param("ids", lineIds).param("actor", actor).update();
    }

    @Override
    public void fixBook(UUID tenantId, UUID lineId, int bookQty, UUID ledgerId, Instant occurredAt, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit_line
                   set book_qty = :book, book_ledger_id = cast(:ledger as uuid), book_occurred_at = :at,
                       updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id
                """).param("tenantId", tenantId).param("id", lineId).param("book", bookQty).param("ledger", ledgerId)
                .param("at", Timestamp.from(occurredAt)).param("actor", actor).update();
    }

    @Override
    public void saveVariance(UUID tenantId, UUID lineId, String reasonKey, String note, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit_line
                   set variance_reason_key = :reason, variance_note = cast(:note as text), updated_at = now(),
                       updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id
                """).param("tenantId", tenantId).param("id", lineId).param("reason", reasonKey).param("note", note)
                .param("actor", actor).update();
    }

    @Override
    public void markPosted(UUID tenantId, UUID lineId, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit_line set posted_at = now(), updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id and posted_at is null
                """).param("tenantId", tenantId).param("id", lineId).param("actor", actor).update();
    }

    @Override
    public boolean bump(UUID tenantId, UUID auditId, long version, UUID actor) {
        return jdbc.sql("""
                update pharmacy.stock_audit set updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id and version = :version
                """).param("tenantId", tenantId).param("id", auditId).param("version", version).param("actor", actor)
                .update() == 1;
    }

    @Override
    public boolean setStatus(UUID tenantId, UUID auditId, long version, String status, UUID submittedBy, UUID actor) {
        return jdbc.sql("""
                update pharmacy.stock_audit
                   set status = :status,
                       submitted_by = coalesce(cast(:submittedBy as uuid), submitted_by),
                       submitted_at = case when cast(:submittedBy as uuid) is null then submitted_at else now() end,
                       closed_at = case when :status = 'CLOSED' then now() else closed_at end,
                       updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id and version = :version
                """).param("tenantId", tenantId).param("id", auditId).param("version", version).param("status", status)
                .param("submittedBy", submittedBy).param("actor", actor).update() == 1;
    }

    @Override
    public void attachRequest(UUID tenantId, UUID auditId, UUID requestId, UUID signerOrNull, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit
                   set approval_request_id = :requestId,
                       second_signer_membership_id = coalesce(cast(:signer as uuid), second_signer_membership_id),
                       updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id
                """).param("tenantId", tenantId).param("id", auditId).param("requestId", requestId)
                .param("signer", signerOrNull).param("actor", actor).update();
    }

    @Override
    public boolean setStatusFromApproval(UUID tenantId, UUID auditId, String status, UUID actor) {
        return jdbc.sql("""
                update pharmacy.stock_audit
                   set status = :status, closed_at = case when :status = 'CLOSED' then now() else closed_at end,
                       updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
                """).param("tenantId", tenantId).param("id", auditId).param("status", status).param("actor", actor)
                .update() == 1;
    }

    @Override
    public void moveTo(UUID tenantId, UUID auditId, String status, UUID submittedBy, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit
                   set status = :status,
                       submitted_by = coalesce(cast(:submittedBy as uuid), submitted_by),
                       submitted_at = case when cast(:submittedBy as uuid) is null then submitted_at else now() end,
                       closed_at = case when :status = 'CLOSED' then now() else closed_at end,
                       updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id
                """).param("tenantId", tenantId).param("id", auditId).param("status", status)
                .param("submittedBy", submittedBy).param("actor", actor).update();
    }

    @Override
    public void setSigner(UUID tenantId, UUID auditId, UUID signerMembershipId, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit
                   set second_signer_membership_id = :signer, updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id and second_signer_membership_id is null
                """).param("tenantId", tenantId).param("id", auditId).param("signer", signerMembershipId)
                .param("actor", actor).update();
    }

    @Override
    public long countAnswered(UUID tenantId, UUID auditId) {
        return jdbc.sql("""
                select count(*) from pharmacy.stock_audit_line
                 where tenant_id = :tenantId and stock_audit_id = :auditId and (counted_qty is not null or not_counted)
                """).param("tenantId", tenantId).param("auditId", auditId).query(Long.class).single();
    }

    @Override
    public void lowerBook(UUID tenantId, UUID lineId, int by, UUID actor) {
        jdbc.sql("""
                update pharmacy.stock_audit_line
                   set book_qty = greatest(book_qty - :by, 0), updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :tenantId and id = :id and book_qty is not null
                """).param("tenantId", tenantId).param("id", lineId).param("by", by).param("actor", actor).update();
    }

    @Override
    public Optional<LedgerMark> lastLedger(UUID tenantId, UUID storeId, UUID batchId) {
        return jdbc.sql("""
                select id, occurred_at from pharmacy.stock_ledger
                 where tenant_id = :tenantId and store_id = :storeId and batch_id = :batchId
                 order by occurred_at desc, id desc limit 1
                """).param("tenantId", tenantId).param("storeId", storeId).param("batchId", batchId)
                .query((rs, n) -> new LedgerMark(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant()))
                .optional();
    }

    @Override
    public boolean movedSince(UUID tenantId, UUID storeId, UUID batchId, Instant after, UUID auditLineId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                select exists (select 1 from pharmacy.stock_ledger sl
                                where sl.tenant_id = :tenantId and sl.store_id = :storeId and sl.batch_id = :batchId
                                  and sl.state = 'AVAILABLE' and sl.occurred_at > :after
                                  and not exists (select 1 from pharmacy.consumption c
                                                   where c.tenant_id = sl.tenant_id and c.id = sl.doc_id
                                                     and c.stock_audit_line_id = :lineId))
                """).param("tenantId", tenantId).param("storeId", storeId).param("batchId", batchId)
                .param("after", Timestamp.from(after)).param("lineId", auditLineId).query(Boolean.class).single());
    }

    private static Audit audit(ResultSet rs, int n) throws SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new Audit(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getLong("doc_seq"),
                rs.getString("doc_no"), rs.getObject("store_id", UUID.class), rs.getString("scope"), rs.getString("status"),
                rs.getObject("submitted_by", UUID.class), submitted == null ? null : submitted.toInstant(),
                rs.getObject("approval_request_id", UUID.class), rs.getObject("second_signer_membership_id", UUID.class),
                rs.getTimestamp("started_at").toInstant(), rs.getObject("created_by", UUID.class), rs.getLong("version"));
    }

    private static Line line(ResultSet rs, int n) throws SQLException {
        Timestamp counted = rs.getTimestamp("counted_at");
        Timestamp book = rs.getTimestamp("book_occurred_at");
        Timestamp posted = rs.getTimestamp("posted_at");
        return new Line(rs.getObject("id", UUID.class), rs.getObject("stock_audit_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class),
                (Integer) rs.getObject("counted_qty"), rs.getBoolean("not_counted"), rs.getString("not_counted_reason_key"),
                rs.getObject("counted_by", UUID.class), counted == null ? null : counted.toInstant(),
                rs.getBoolean("recount_requested"), (Integer) rs.getObject("book_qty"),
                book == null ? null : book.toInstant(), rs.getString("variance_reason_key"), rs.getString("variance_note"),
                posted == null ? null : posted.toInstant());
    }
}
