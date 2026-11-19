package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DebitNoteRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.debit_note}, {@code pharmacy.debit_note_line} and {@code pharmacy.debit_note_credit}. Every read of
 * lines adds {@code not removed}. The representative's name, quantities and amounts are never logged here.
 */
@Repository
public class JdbcDebitNoteRepository implements DebitNoteRepository {

    private static final String COLUMNS = """
            d.id, d.unit_id, d.store_id, d.vendor_id, d.doc_seq, d.doc_no, d.status, d.prepared_from_grn_id, d.reason_key,
            d.note, d.credit_total_paise, d.gst_reversal_paise, d.approval_request_id, d.picked_at, d.picked_by,
            d.representative_name, d.handed_over_at, d.handed_over_by, d.second_signer_membership_id, d.created_at,
            d.created_by, d.version
            """;
    private static final String FROM = " from pharmacy.debit_note d where d.tenant_id = :tenantId";
    private static final String FIND = "select " + COLUMNS + FROM + " and d.store_id = :storeId and d.id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_BY_ID = "select " + COLUMNS + FROM + " and d.id = :id";
    private static final String FIND_FOR_UPDATE_BY_ID = "select " + COLUMNS + FROM + " and d.id = :id for update";
    private static final String PREPARED = "select " + COLUMNS + FROM
            + " and d.prepared_from_grn_id = :grnId and d.status <> 'DISCARDED' order by d.created_at desc limit 1";
    private static final String LINE_COLUMNS = """
            l.id, l.line_type, l.product_id, l.sort, l.batch_id, l.source_state, l.goods_receipt_line_id, l.qty, l.rate,
            l.credit_percent, l.credit_percent_changed, l.credit_paise, l.gst_rate, l.gst_reversal_paise
            """;
    private static final String LINES = "select " + LINE_COLUMNS + """
              from pharmacy.debit_note_line l
             where l.tenant_id = :tenantId and l.debit_note_id = :id and not l.removed
             order by l.sort, l.id
            """;
    private static final String INSERT = """
            insert into pharmacy.debit_note (id, tenant_id, unit_id, store_id, vendor_id, prepared_from_grn_id, reason_key, note,
                   created_by)
            values (:id, :tenantId, :unitId, :storeId, :vendorId, :grnId, :reasonKey, :note, :actor)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.debit_note_line (tenant_id, unit_id, debit_note_id, sort, line_type, product_id, batch_id,
                   source_state, goods_receipt_line_id, qty, rate, credit_percent, credit_percent_changed, credit_paise,
                   gst_rate, gst_reversal_paise, created_by)
            values (:tenantId, :unitId, :id, :sort, :type, :productId, :batchId, :sourceState, :grnLineId, :qty, :rate,
                   :creditPercent, :changed, :credit, :gstRate, :gstReversal, :actor)
            """;
    private static final String UPDATE_LINE = """
            update pharmacy.debit_note_line
               set sort = :sort, line_type = :type, product_id = :productId, batch_id = :batchId, source_state = :sourceState,
                   goods_receipt_line_id = :grnLineId, qty = :qty, rate = :rate, credit_percent = :creditPercent,
                   credit_percent_changed = :changed, credit_paise = :credit, gst_rate = :gstRate,
                   gst_reversal_paise = :gstReversal, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String REMOVE_LINE = """
            update pharmacy.debit_note_line
               set removed = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String UPDATE_DRAFT = """
            update pharmacy.debit_note
               set reason_key = :reasonKey, note = :note, credit_total_paise = :credit, gst_reversal_paise = :gstReversal,
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String SET_STATUS = """
            update pharmacy.debit_note
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SET_STATUS_LOCKED = """
            update pharmacy.debit_note
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = :expected
            """;
    private static final String SUBMIT = """
            update pharmacy.debit_note
               set status = :status, doc_seq = :docSeq, doc_no = :docNo, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String ATTACH = """
            update pharmacy.debit_note
               set approval_request_id = :requestId, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
            """;
    private static final String MARK_PICKED = """
            update pharmacy.debit_note
               set status = 'PICKED', picked_at = now(), picked_by = :actor, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'CREATED'
            """;
    private static final String MARK_HANDED_OVER = """
            update pharmacy.debit_note
               set status = 'HANDED_OVER', representative_name = :representative, handed_over_at = now(), handed_over_by = :actor,
                   second_signer_membership_id = cast(:signer as uuid), updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status in ('CREATED', 'PICKED')
            """;
    private static final String INSERT_CREDIT = """
            insert into pharmacy.debit_note_credit (tenant_id, unit_id, debit_note_id, credit_note_no, credit_note_date,
                   amount_paise, difference_paise, applied_paise, unapplied_credit_paise, created_by)
            values (:tenantId, :unitId, :id, :no, :date, :amount, :difference, :applied, :unapplied, :actor)
            """;
    private static final String CREDIT = """
            select c.credit_note_no, c.credit_note_date, c.amount_paise, c.difference_paise, c.applied_paise,
                   c.unapplied_credit_paise, c.created_at, c.created_by
              from pharmacy.debit_note_credit c
             where c.tenant_id = :tenantId and c.debit_note_id = :id
            """;
    private static final String LIST_WHERE = """
              from pharmacy.debit_note d
              join pharmacy.vendor v on v.tenant_id = d.tenant_id and v.id = d.vendor_id
             where d.tenant_id = :tenantId and d.store_id = :storeId and d.status <> 'DISCARDED'
               and (cast(:status as text) is null or d.status = cast(:status as text))
               and (cast(:vendorId as uuid) is null or d.vendor_id = cast(:vendorId as uuid))
            """;
    private static final String LIST = """
            select d.id, d.doc_no, v.name as vendor_name, d.credit_total_paise, d.status, d.created_at
            """ + LIST_WHERE + " order by d.created_at desc, d.id limit :limit offset :offset";
    private static final String COUNT = "select count(*)" + LIST_WHERE;
    private static final String CANDIDATES = """
            select sb.product_id, sb.batch_id, sb.state, sb.qty, b.batch_no, b.expiry_date, b.mrp, b.purchase_rate,
                   coalesce((select sum(d.qty) from pharmacy.debit_note_line d
                               join pharmacy.debit_note n on n.tenant_id = d.tenant_id and n.id = d.debit_note_id
                              where d.tenant_id = :tenantId and d.batch_id = sb.batch_id and d.source_state = sb.state
                                and d.line_type = 'STOCK_RETURN' and not d.removed and n.status = 'WAITING_APPROVAL'
                                and (cast(:exceptNote as uuid) is null or n.id <> cast(:exceptNote as uuid))), 0) as reserved,
                   (select x.txn_type from pharmacy.stock_ledger x
                     where x.tenant_id = :tenantId and x.store_id = sb.store_id and x.batch_id = sb.batch_id
                       and x.state = 'QUARANTINE' and x.qty_in > 0 order by x.occurred_at desc limit 1) as cause_txn,
                   (select x.reason_key from pharmacy.stock_ledger x
                     where x.tenant_id = :tenantId and x.store_id = sb.store_id and x.batch_id = sb.batch_id
                       and x.state = 'QUARANTINE' and x.qty_in > 0 order by x.occurred_at desc limit 1) as cause_reason
              from pharmacy.stock_balance sb
              join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.qty > 0 and b.vendor_id = :vendorId
               and (sb.state = 'QUARANTINE'
                    or (sb.state = 'AVAILABLE' and cast(:returnDays as integer) is not null
                        and b.expiry_date >= cast(:today as date)
                        and b.expiry_date <= cast(:today as date) + cast(:returnDays as integer)))
             order by b.expiry_date, b.id, sb.state
            """;

    private final JdbcClient jdbc;

    public JdbcDebitNoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcDebitNoteRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcDebitNoteRepository::map).optional();
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id).query(JdbcDebitNoteRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdateById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcDebitNoteRepository::map).optional();
    }

    @Override
    public Optional<Row> findPreparedFromReceipt(UUID tenantId, UUID goodsReceiptId) {
        return jdbc.sql(PREPARED).param("tenantId", tenantId).param("grnId", goodsReceiptId)
                .query(JdbcDebitNoteRepository::map).optional();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID debitNoteId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("id", debitNoteId).query(JdbcDebitNoteRepository::mapLine).list();
    }

    @Override
    public void insert(UUID tenantId, UUID unitId, UUID id, UUID storeId, UUID vendorId, UUID preparedFromGrnId,
                       String reasonKey, String note, UUID createdBy) {
        jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("id", id).param("storeId", storeId)
                .param("vendorId", vendorId).param("grnId", preparedFromGrnId, java.sql.Types.OTHER)
                .param("reasonKey", reasonKey, java.sql.Types.VARCHAR).param("note", note, java.sql.Types.VARCHAR)
                .param("actor", createdBy).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, UUID debitNoteId, NewLine line, UUID actor) {
        bind(jdbc.sql(INSERT_LINE).param("tenantId", tenantId).param("unitId", unitId).param("id", debitNoteId), line, actor)
                .update();
    }

    @Override
    public void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor) {
        bind(jdbc.sql(UPDATE_LINE).param("tenantId", tenantId).param("lineId", lineId), line, actor).update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, NewLine line, UUID actor) {
        return spec.param("sort", line.sort()).param("type", line.lineType()).param("productId", line.productId())
                .param("batchId", line.batchId(), java.sql.Types.OTHER)
                .param("sourceState", line.sourceState(), java.sql.Types.VARCHAR)
                .param("grnLineId", line.goodsReceiptLineId(), java.sql.Types.OTHER).param("qty", line.qty())
                .param("rate", line.rate()).param("creditPercent", line.creditPercent())
                .param("changed", line.creditPercentChanged()).param("credit", line.creditPaise())
                .param("gstRate", line.gstRate()).param("gstReversal", line.gstReversalPaise()).param("actor", actor);
    }

    @Override
    public void removeLine(UUID tenantId, UUID lineId, UUID actor) {
        jdbc.sql(REMOVE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("actor", actor).update();
    }

    @Override
    public boolean updateDraft(UUID tenantId, UUID id, long version, String reasonKey, String note, long creditTotalPaise,
                               long gstReversalPaise, UUID actor) {
        return jdbc.sql(UPDATE_DRAFT).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("reasonKey", reasonKey, java.sql.Types.VARCHAR).param("note", note, java.sql.Types.VARCHAR)
                .param("credit", creditTotalPaise).param("gstReversal", gstReversalPaise).param("actor", actor).update() == 1;
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
    public boolean submit(UUID tenantId, UUID id, long version, String status, long docSeq, String docNo, UUID actor) {
        return jdbc.sql(SUBMIT).param("tenantId", tenantId).param("id", id).param("version", version).param("status", status)
                .param("docSeq", docSeq).param("docNo", docNo).param("actor", actor).update() == 1;
    }

    @Override
    public boolean attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor) {
        return jdbc.sql(ATTACH).param("tenantId", tenantId).param("id", id).param("requestId", requestId)
                .param("actor", actor).update() == 1;
    }

    @Override
    public boolean markPicked(UUID tenantId, UUID id, long version, UUID actor) {
        return jdbc.sql(MARK_PICKED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("actor", actor).update() == 1;
    }

    @Override
    public boolean markHandedOver(UUID tenantId, UUID id, long version, String representativeName, UUID handedOverBy,
                                  UUID signer) {
        return jdbc.sql(MARK_HANDED_OVER).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("representative", representativeName).param("actor", handedOverBy)
                .param("signer", signer, java.sql.Types.OTHER).update() == 1;
    }

    @Override
    public void insertCredit(UUID tenantId, UUID unitId, UUID debitNoteId, String creditNoteNo, LocalDate creditNoteDate,
                             long amountPaise, long differencePaise, long appliedPaise, long unappliedPaise, UUID actor) {
        jdbc.sql(INSERT_CREDIT).param("tenantId", tenantId).param("unitId", unitId).param("id", debitNoteId)
                .param("no", creditNoteNo).param("date", java.sql.Date.valueOf(creditNoteDate)).param("amount", amountPaise)
                .param("difference", differencePaise).param("applied", appliedPaise).param("unapplied", unappliedPaise)
                .param("actor", actor).update();
    }

    @Override
    public Optional<Credit> credit(UUID tenantId, UUID debitNoteId) {
        return jdbc.sql(CREDIT).param("tenantId", tenantId).param("id", debitNoteId)
                .query((rs, n) -> new Credit(rs.getString("credit_note_no"), rs.getDate("credit_note_date").toLocalDate(),
                        rs.getLong("amount_paise"), rs.getLong("difference_paise"), rs.getLong("applied_paise"),
                        rs.getLong("unapplied_credit_paise"), instant(rs, "created_at"), rs.getObject("created_by", UUID.class)))
                .optional();
    }

    @Override
    public Slice list(UUID tenantId, UUID storeId, String status, UUID vendorId, int limit, int offset) {
        List<ListRow> items = jdbc.sql(LIST).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status, java.sql.Types.VARCHAR).param("vendorId", vendorId, java.sql.Types.OTHER)
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> new ListRow(rs.getObject("id", UUID.class), rs.getString("doc_no"),
                        rs.getString("vendor_name"), rs.getLong("credit_total_paise"), rs.getString("status"),
                        instant(rs, "created_at"))).list();
        long total = jdbc.sql(COUNT).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status, java.sql.Types.VARCHAR).param("vendorId", vendorId, java.sql.Types.OTHER)
                .query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public List<StockCandidate> stockCandidates(UUID tenantId, UUID storeId, UUID vendorId, LocalDate today, Integer returnDays,
                                                UUID exceptNoteId) {
        return jdbc.sql(CANDIDATES).param("tenantId", tenantId).param("storeId", storeId).param("vendorId", vendorId)
                .param("today", java.sql.Date.valueOf(today)).param("returnDays", returnDays, java.sql.Types.INTEGER)
                .param("exceptNote", exceptNoteId, java.sql.Types.OTHER)
                .query((rs, n) -> new StockCandidate(rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class),
                        rs.getString("state"), rs.getInt("qty"), rs.getInt("reserved"), rs.getString("batch_no"),
                        rs.getDate("expiry_date").toLocalDate(), rs.getBigDecimal("mrp"), rs.getBigDecimal("purchase_rate"),
                        rs.getString("cause_txn"), rs.getString("cause_reason"))).list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        long seq = rs.getLong("doc_seq");
        Long docSeq = rs.wasNull() ? null : seq;
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("vendor_id", UUID.class), docSeq, rs.getString("doc_no"), rs.getString("status"),
                rs.getObject("prepared_from_grn_id", UUID.class), rs.getString("reason_key"), rs.getString("note"),
                rs.getLong("credit_total_paise"), rs.getLong("gst_reversal_paise"), rs.getObject("approval_request_id", UUID.class),
                instant(rs, "picked_at"), rs.getObject("picked_by", UUID.class), rs.getString("representative_name"),
                instant(rs, "handed_over_at"), rs.getObject("handed_over_by", UUID.class),
                rs.getObject("second_signer_membership_id", UUID.class), instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), rs.getLong("version"));
    }

    private static Line mapLine(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getString("line_type"), rs.getObject("product_id", UUID.class),
                rs.getInt("sort"), rs.getObject("batch_id", UUID.class), rs.getString("source_state"),
                rs.getObject("goods_receipt_line_id", UUID.class), rs.getInt("qty"), rs.getBigDecimal("rate"),
                rs.getBigDecimal("credit_percent"), rs.getBoolean("credit_percent_changed"), rs.getLong("credit_paise"),
                rs.getBigDecimal("gst_rate"), rs.getLong("gst_reversal_paise"));
    }
}
