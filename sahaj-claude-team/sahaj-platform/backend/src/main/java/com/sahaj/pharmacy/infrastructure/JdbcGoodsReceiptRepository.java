package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.GoodsReceiptRepository;
import java.math.BigDecimal;
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
 * JDBC over {@code pharmacy.goods_receipt} and {@code pharmacy.goods_receipt_line}. Generated columns are never inserted. Every
 * read of lines adds {@code not removed}. Batch numbers, quantities and amounts are never logged here.
 */
@Repository
public class JdbcGoodsReceiptRepository implements GoodsReceiptRepository {

    private static final String COLUMNS = """
            g.id, g.unit_id, g.store_id, g.purchase_order_id, g.vendor_id, g.doc_seq, g.doc_no, g.status, g.invoice_no,
            g.invoice_date, g.credit_days, g.invoice_total_paise, g.approval_request_id, g.posted_at, g.posted_by, g.created_at,
            g.created_by, g.version
            """;
    private static final String FROM = " from pharmacy.goods_receipt g where g.tenant_id = :tenantId";
    private static final String FIND = "select " + COLUMNS + FROM + " and g.store_id = :storeId and g.id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_BY_ID = "select " + COLUMNS + FROM + " and g.id = :id";
    private static final String FIND_FOR_UPDATE_BY_ID = "select " + COLUMNS + FROM + " and g.id = :id for update";
    private static final String LINE_COLUMNS = """
            l.id, l.purchase_order_line_id, l.product_id, l.sort, l.batch_no, l.expiry_date, l.qty, l.billed_qty, l.free_qty,
            l.ptr, l.mrp, l.discount_percent, l.gst_rate, l.taxable_paise, l.gst_paise, l.net_rate, l.reference_rate,
            l.rate_basis, l.rate_percent, l.short_qty, l.short_action, l.claim_rate_difference, l.batch_id, l.landed_rate,
            l.second_signer_membership_id
            """;
    private static final String LINES = "select " + LINE_COLUMNS + """
              from pharmacy.goods_receipt_line l
             where l.tenant_id = :tenantId and l.goods_receipt_id = :id and not l.removed
             order by l.sort, l.id
            """;
    private static final String INSERT = """
            insert into pharmacy.goods_receipt (id, tenant_id, unit_id, store_id, purchase_order_id, vendor_id, invoice_no,
                   invoice_date, credit_days, invoice_total_paise, created_by)
            values (:id, :tenantId, :unitId, :storeId, :purchaseOrderId, :vendorId, :invoiceNo, :invoiceDate, :creditDays,
                   :total, :actor)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.goods_receipt_line (tenant_id, unit_id, goods_receipt_id, purchase_order_line_id, product_id,
                   sort, batch_no, expiry_date, qty, billed_qty, free_qty, ptr, mrp, discount_percent, gst_rate, taxable_paise,
                   gst_paise, net_rate, reference_rate, rate_basis, rate_percent, short_qty, short_action,
                   claim_rate_difference, created_by)
            values (:tenantId, :unitId, :id, :poLineId, :productId, :sort, :batchNo, :expiry, :qty, :billed, :free, :ptr, :mrp,
                   :discount, :gstRate, :taxable, :gst, :netRate, :referenceRate, :rateBasis, :ratePercent, :shortQty,
                   :shortAction, :claim, :actor)
            """;
    private static final String UPDATE_LINE = """
            update pharmacy.goods_receipt_line
               set purchase_order_line_id = :poLineId, product_id = :productId, sort = :sort, batch_no = :batchNo,
                   expiry_date = :expiry, qty = :qty, billed_qty = :billed, free_qty = :free, ptr = :ptr, mrp = :mrp,
                   discount_percent = :discount, gst_rate = :gstRate, taxable_paise = :taxable, gst_paise = :gst,
                   net_rate = :netRate, reference_rate = :referenceRate, rate_basis = :rateBasis,
                   rate_percent = :ratePercent, short_qty = :shortQty, short_action = :shortAction,
                   claim_rate_difference = :claim, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String REMOVE_LINE = """
            update pharmacy.goods_receipt_line
               set removed = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String UPDATE_DRAFT = """
            update pharmacy.goods_receipt
               set vendor_id = :vendorId, invoice_no = :invoiceNo, invoice_date = :invoiceDate, credit_days = :creditDays,
                   invoice_total_paise = :total, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String SET_STATUS = """
            update pharmacy.goods_receipt
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SET_STATUS_LOCKED = """
            update pharmacy.goods_receipt
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = :expected
            """;
    private static final String SEND_FOR_APPROVAL = """
            update pharmacy.goods_receipt
               set status = 'WAITING_APPROVAL', approval_request_id = :requestId, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String MARK_POSTED = """
            update pharmacy.goods_receipt
               set status = 'POSTED', doc_seq = :docSeq, doc_no = :docNo, posted_at = now(), posted_by = :actor,
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status in ('DRAFT', 'APPROVED')
            """;
    private static final String SET_LINE_POSTING = """
            update pharmacy.goods_receipt_line
               set batch_id = :batchId, landed_rate = :landed, second_signer_membership_id = cast(:signer as uuid),
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String INVOICE_TAKEN = """
            select exists (select 1 from pharmacy.goods_receipt g
                            where g.tenant_id = :tenantId and g.vendor_id = :vendorId and g.status <> 'DISCARDED'
                              and lower(btrim(g.invoice_no)) = lower(btrim(:invoiceNo))
                              and (cast(:exceptId as uuid) is null or g.id <> cast(:exceptId as uuid)))
            """;
    private static final String LIST_WHERE = """
              from pharmacy.goods_receipt g
              join pharmacy.vendor v on v.tenant_id = g.tenant_id and v.id = g.vendor_id
             where g.tenant_id = :tenantId and g.store_id = :storeId and g.status <> 'DISCARDED'
               and (cast(:status as text) is null or g.status = cast(:status as text))
            """;
    private static final String LIST = """
            select g.id, g.doc_no, v.name as vendor_name, g.invoice_no, g.invoice_total_paise, g.status, g.created_at
            """ + LIST_WHERE + " order by g.created_at desc, g.id limit :limit offset :offset";
    private static final String COUNT = "select count(*)" + LIST_WHERE;
    private static final String SHORT_COLUMNS = """
            select l.id, g.id as grn_id, g.doc_no, l.product_id, l.batch_id, (l.billed_qty - l.qty) as short_units,
                   coalesce((select sum(d.qty) from pharmacy.debit_note_line d
                               join pharmacy.debit_note n on n.tenant_id = d.tenant_id and n.id = d.debit_note_id
                              where d.tenant_id = :tenantId and d.goods_receipt_line_id = l.id and d.line_type = 'SHORT_SUPPLY'
                                and not d.removed and n.status not in ('DISCARDED', 'CANCELLED', 'REJECTED')
                                and (cast(:exceptNote as uuid) is null or n.id <> cast(:exceptNote as uuid))), 0) as claimed,
                   l.net_rate, coalesce(c.conversion, p.conversion) as conversion
              from pharmacy.goods_receipt g
              join pharmacy.goods_receipt_line l on l.tenant_id = g.tenant_id and l.goods_receipt_id = g.id
              join pharmacy.product p on p.tenant_id = l.tenant_id and p.id = l.product_id
              left join pharmacy.catalogue_product c on c.id = p.catalogue_product_id
             where g.tenant_id = :tenantId and g.status = 'POSTED' and not l.removed and l.billed_qty > l.qty
            """;
    private static final String SHORT_OF_VENDOR = SHORT_COLUMNS + " and g.vendor_id = :vendorId order by g.posted_at desc, l.sort";
    private static final String SHORT_ONE = SHORT_COLUMNS + " and g.store_id = :storeId and l.id = :lineId";
    private static final String RECEIPT_NO_OF_LINE = """
            select g.doc_no from pharmacy.goods_receipt_line l
              join pharmacy.goods_receipt g on g.tenant_id = l.tenant_id and g.id = l.goods_receipt_id
             where l.tenant_id = :tenantId and l.id = :lineId
            """;

    private final JdbcClient jdbc;

    public JdbcGoodsReceiptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcGoodsReceiptRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcGoodsReceiptRepository::map).optional();
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id).query(JdbcGoodsReceiptRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdateById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcGoodsReceiptRepository::map).optional();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID goodsReceiptId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("id", goodsReceiptId)
                .query(JdbcGoodsReceiptRepository::mapLine).list();
    }

    @Override
    public void insert(UUID tenantId, UUID unitId, UUID id, UUID storeId, UUID purchaseOrderId, UUID vendorId, String invoiceNo,
                       LocalDate invoiceDate, int creditDays, long invoiceTotalPaise, UUID createdBy) {
        jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("id", id).param("storeId", storeId)
                .param("purchaseOrderId", purchaseOrderId, java.sql.Types.OTHER).param("vendorId", vendorId)
                .param("invoiceNo", invoiceNo).param("invoiceDate", java.sql.Date.valueOf(invoiceDate))
                .param("creditDays", creditDays).param("total", invoiceTotalPaise).param("actor", createdBy).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, UUID goodsReceiptId, NewLine line, UUID actor) {
        bind(jdbc.sql(INSERT_LINE).param("tenantId", tenantId).param("unitId", unitId).param("id", goodsReceiptId), line, actor)
                .update();
    }

    @Override
    public void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor) {
        bind(jdbc.sql(UPDATE_LINE).param("tenantId", tenantId).param("lineId", lineId), line, actor).update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, NewLine line, UUID actor) {
        return spec.param("poLineId", line.purchaseOrderLineId(), java.sql.Types.OTHER).param("productId", line.productId())
                .param("sort", line.sort()).param("batchNo", line.batchNo()).param("expiry", java.sql.Date.valueOf(line.expiryDate()))
                .param("qty", line.qty()).param("billed", line.billedQty()).param("free", line.freeQty())
                .param("ptr", line.ptr()).param("mrp", line.mrp()).param("discount", line.discountPercent())
                .param("gstRate", line.gstRate()).param("taxable", line.taxablePaise()).param("gst", line.gstPaise())
                .param("netRate", line.netRate()).param("referenceRate", line.referenceRate(), java.sql.Types.NUMERIC)
                .param("rateBasis", line.rateBasis(), java.sql.Types.VARCHAR)
                .param("ratePercent", line.ratePercent(), java.sql.Types.NUMERIC)
                .param("shortQty", line.shortQty(), java.sql.Types.INTEGER)
                .param("shortAction", line.shortAction(), java.sql.Types.VARCHAR).param("claim", line.claimRateDifference())
                .param("actor", actor);
    }

    @Override
    public void removeLine(UUID tenantId, UUID lineId, UUID actor) {
        jdbc.sql(REMOVE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("actor", actor).update();
    }

    @Override
    public boolean updateDraft(UUID tenantId, UUID id, long version, UUID vendorId, String invoiceNo, LocalDate invoiceDate,
                               int creditDays, long invoiceTotalPaise, UUID actor) {
        return jdbc.sql(UPDATE_DRAFT).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("vendorId", vendorId).param("invoiceNo", invoiceNo).param("invoiceDate", java.sql.Date.valueOf(invoiceDate))
                .param("creditDays", creditDays).param("total", invoiceTotalPaise).param("actor", actor).update() == 1;
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
    public boolean sendForApproval(UUID tenantId, UUID id, long version, UUID requestId, UUID actor) {
        return jdbc.sql(SEND_FOR_APPROVAL).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("requestId", requestId).param("actor", actor).update() == 1;
    }

    @Override
    public boolean markPosted(UUID tenantId, UUID id, long version, long docSeq, String docNo, UUID postedBy) {
        return jdbc.sql(MARK_POSTED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("docSeq", docSeq).param("docNo", docNo).param("actor", postedBy).update() == 1;
    }

    @Override
    public void setLinePosting(UUID tenantId, UUID lineId, UUID batchId, BigDecimal landedRate, UUID secondSigner, UUID actor) {
        jdbc.sql(SET_LINE_POSTING).param("tenantId", tenantId).param("lineId", lineId).param("batchId", batchId)
                .param("landed", landedRate).param("signer", secondSigner, java.sql.Types.OTHER).param("actor", actor).update();
    }

    @Override
    public boolean invoiceTaken(UUID tenantId, UUID vendorId, String invoiceNo, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.sql(INVOICE_TAKEN).param("tenantId", tenantId).param("vendorId", vendorId)
                .param("invoiceNo", invoiceNo).param("exceptId", exceptId, java.sql.Types.OTHER).query(Boolean.class).single());
    }

    @Override
    public Slice list(UUID tenantId, UUID storeId, String status, int limit, int offset) {
        List<ListRow> items = jdbc.sql(LIST).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status, java.sql.Types.VARCHAR).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new ListRow(rs.getObject("id", UUID.class), rs.getString("doc_no"),
                        rs.getString("vendor_name"), rs.getString("invoice_no"), rs.getLong("invoice_total_paise"),
                        rs.getString("status"), instant(rs, "created_at"))).list();
        long total = jdbc.sql(COUNT).param("tenantId", tenantId).param("storeId", storeId)
                .param("status", status, java.sql.Types.VARCHAR).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public List<ShortLine> shortLinesOfVendor(UUID tenantId, UUID vendorId, UUID exceptNoteId) {
        return jdbc.sql(SHORT_OF_VENDOR).param("tenantId", tenantId).param("vendorId", vendorId)
                .param("exceptNote", exceptNoteId, java.sql.Types.OTHER).query(JdbcGoodsReceiptRepository::mapShort).list();
    }

    @Override
    public Optional<ShortLine> shortLine(UUID tenantId, UUID storeId, UUID lineId, UUID exceptNoteId) {
        return jdbc.sql(SHORT_ONE).param("tenantId", tenantId).param("storeId", storeId).param("lineId", lineId)
                .param("exceptNote", exceptNoteId, java.sql.Types.OTHER).query(JdbcGoodsReceiptRepository::mapShort).optional();
    }

    @Override
    public Optional<String> receiptNoOfLine(UUID tenantId, UUID lineId) {
        return jdbc.sql(RECEIPT_NO_OF_LINE).param("tenantId", tenantId).param("lineId", lineId).query(String.class).optional();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        long seq = rs.getLong("doc_seq");
        Long docSeq = rs.wasNull() ? null : seq;
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("purchase_order_id", UUID.class), rs.getObject("vendor_id", UUID.class), docSeq,
                rs.getString("doc_no"), rs.getString("status"), rs.getString("invoice_no"), date(rs, "invoice_date"),
                rs.getInt("credit_days"), rs.getLong("invoice_total_paise"), rs.getObject("approval_request_id", UUID.class),
                instant(rs, "posted_at"), rs.getObject("posted_by", UUID.class), instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class), rs.getLong("version"));
    }

    private static Line mapLine(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("purchase_order_line_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getInt("sort"), rs.getString("batch_no"), date(rs, "expiry_date"),
                rs.getInt("qty"), rs.getInt("billed_qty"), rs.getInt("free_qty"), rs.getBigDecimal("ptr"),
                rs.getBigDecimal("mrp"), rs.getBigDecimal("discount_percent"), rs.getBigDecimal("gst_rate"),
                rs.getLong("taxable_paise"), rs.getLong("gst_paise"), rs.getBigDecimal("net_rate"),
                rs.getBigDecimal("reference_rate"), rs.getString("rate_basis"), rs.getBigDecimal("rate_percent"),
                integer(rs, "short_qty"), rs.getString("short_action"), rs.getBoolean("claim_rate_difference"),
                rs.getObject("batch_id", UUID.class), rs.getBigDecimal("landed_rate"),
                rs.getObject("second_signer_membership_id", UUID.class));
    }

    private static ShortLine mapShort(ResultSet rs, int n) throws SQLException {
        return new ShortLine(rs.getObject("id", UUID.class), rs.getObject("grn_id", UUID.class), rs.getString("doc_no"),
                rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("short_units"),
                rs.getInt("claimed"), rs.getBigDecimal("net_rate"), rs.getInt("conversion"));
    }
}
