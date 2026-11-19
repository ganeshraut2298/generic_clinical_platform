package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.PurchaseOrderRepository;
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
 * JDBC over {@code pharmacy.purchase_order} and {@code pharmacy.purchase_order_line}. Generated columns are never inserted.
 * Every read of lines adds {@code not removed}. Quantities, rates and vendor data are never logged here.
 */
@Repository
public class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private static final String COLUMNS = """
            p.id, p.unit_id, p.store_id, p.vendor_id, p.doc_seq, p.doc_no, p.status, p.delivery_expected, p.credit_days,
            p.sent_on, p.approval_request_id, p.subtotal_paise, p.gst_paise, p.total_paise, p.created_at, p.created_by,
            p.updated_at, p.version
            """;
    private static final String FROM = " from pharmacy.purchase_order p where p.tenant_id = :tenantId";
    private static final String FIND = "select " + COLUMNS + FROM + " and p.store_id = :storeId and p.id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_BY_ID = "select " + COLUMNS + FROM + " and p.id = :id";
    private static final String FIND_FOR_UPDATE_BY_ID = "select " + COLUMNS + FROM + " and p.id = :id for update";
    private static final String LINE_COLUMNS = """
            l.id, l.product_id, l.sort, l.qty, l.rate, l.gst_rate, l.amount_paise, l.gst_paise, l.received_qty, l.closed_short
            """;
    private static final String LINES = "select " + LINE_COLUMNS + """
              from pharmacy.purchase_order_line l
             where l.tenant_id = :tenantId and l.purchase_order_id = :id and not l.removed
             order by l.sort, l.id
            """;
    private static final String LINES_FOR_UPDATE = LINES + " for update of l";
    private static final String INSERT = """
            insert into pharmacy.purchase_order (id, tenant_id, unit_id, store_id, vendor_id, delivery_expected, credit_days,
                   created_by)
            values (:id, :tenantId, :unitId, :storeId, :vendorId, :delivery, :creditDays, :actor)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.purchase_order_line (tenant_id, unit_id, purchase_order_id, product_id, sort, qty, rate, gst_rate,
                   amount_paise, gst_paise, created_by)
            values (:tenantId, :unitId, :id, :productId, :sort, :qty, :rate, :gstRate, :amount, :gst, :actor)
            """;
    private static final String UPDATE_LINE = """
            update pharmacy.purchase_order_line
               set sort = :sort, qty = :qty, rate = :rate, gst_rate = :gstRate, amount_paise = :amount, gst_paise = :gst,
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String REMOVE_LINE = """
            update pharmacy.purchase_order_line
               set removed = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String UPDATE_DRAFT = """
            update pharmacy.purchase_order
               set vendor_id = :vendorId, delivery_expected = :delivery, credit_days = :creditDays, subtotal_paise = :subtotal,
                   gst_paise = :gst, total_paise = :total, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String SUBMIT = """
            update pharmacy.purchase_order
               set status = :status, doc_seq = :docSeq, doc_no = :docNo, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String SET_STATUS = """
            update pharmacy.purchase_order
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version
            """;
    private static final String SET_STATUS_LOCKED = """
            update pharmacy.purchase_order
               set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = :expected
            """;
    private static final String ATTACH = """
            update pharmacy.purchase_order
               set approval_request_id = :requestId, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
            """;
    private static final String MARK_SENT = """
            update pharmacy.purchase_order
               set status = 'SENT', sent_on = :sentOn, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'APPROVED'
            """;
    private static final String CLOSE_LINES = """
            update pharmacy.purchase_order_line
               set closed_short = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and purchase_order_id = :id and not removed and not closed_short
               and received_qty < qty
            """;
    private static final String ADD_RECEIVED = """
            update pharmacy.purchase_order_line
               set received_qty = received_qty + :qty, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed
            """;
    private static final String CLOSE_LINE = """
            update pharmacy.purchase_order_line
               set closed_short = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :lineId and not removed and not closed_short
            """;
    private static final String CLOSE_HEADER = """
            update pharmacy.purchase_order
               set status = 'CLOSED_SHORT', updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'PARTLY_RECEIVED'
            """;
    private static final String SUPPLIES = """
            select exists (select 1 from pharmacy.vendor_store vs
                            where vs.tenant_id = :tenantId and vs.vendor_id = :vendorId and vs.store_id = :storeId and vs.active)
            """;
    private static final String OUTSTANDING = """
            exists (select 1 from pharmacy.purchase_order_line o
                     where o.tenant_id = p.tenant_id and o.purchase_order_id = p.id and not o.removed and not o.closed_short
                       and o.received_qty < o.qty)
            """;
    private static final String LIST_WHERE = " from pharmacy.purchase_order p"
            + " join pharmacy.vendor v on v.tenant_id = p.tenant_id and v.id = p.vendor_id"
            + " where p.tenant_id = :tenantId and p.store_id = :storeId and p.status <> 'DISCARDED'"
            + " and (cast(:statuses as text) is null or p.status = any (string_to_array(:statuses, ',')))"
            + " and (not :overdueOnly or (p.status in ('SENT', 'PARTLY_RECEIVED') and p.delivery_expected < :today and "
            + OUTSTANDING + "))";
    private static final String LIST = "select p.id, p.doc_no, v.name as vendor_name, p.total_paise, p.status, p.delivery_expected,"
            + " (select count(*) from pharmacy.purchase_order_line c where c.tenant_id = p.tenant_id and c.purchase_order_id = p.id"
            + " and not c.removed and c.closed_short) as short_lines, " + OUTSTANDING + " as outstanding"
            + LIST_WHERE + " order by p.created_at desc, p.id limit :limit offset :offset";
    private static final String COUNT = "select count(*)" + LIST_WHERE;
    private static final String TILES = """
            select
              count(*) filter (where p.status = 'DRAFT') as draft,
              count(*) filter (where p.status = 'WAITING_APPROVAL') as waiting,
              count(*) filter (where p.status = 'SENT') as sent,
              count(*) filter (where p.status = 'PARTLY_RECEIVED') as partly,
              count(*) filter (where p.status in ('SENT', 'PARTLY_RECEIVED') and p.delivery_expected < :today and
            """ + OUTSTANDING + """
              ) as overdue
              from pharmacy.purchase_order p
             where p.tenant_id = :tenantId and p.store_id = :storeId
            """;
    private static final String RECEIPTS = """
            select g.id, g.doc_no, g.posted_at
              from pharmacy.goods_receipt g
             where g.tenant_id = :tenantId and g.purchase_order_id = :id and g.status = 'POSTED'
             order by g.posted_at, g.id
            """;
    private static final String TOGETHER = """
            select p.id from pharmacy.purchase_order p join pharmacy.vendor v on v.tenant_id = p.tenant_id and v.id = p.vendor_id
             where p.tenant_id = :tenantId and p.store_id = :storeId and p.created_by = :actor and p.created_at = :at
             order by v.name, p.id
            """;
    private static final String SHORT_LINES = """
            select count(*) from pharmacy.purchase_order_line l
             where l.tenant_id = :tenantId and l.purchase_order_id = :id and not l.removed and l.closed_short
            """;
    /**
     * The Central Store's AVAILABLE unexpired stock of each ACTIVE product with a reorder level, and the open quantity of
     * APPROVED, SENT and PARTLY_RECEIVED orders (purchase units, times the conversion for issue units). Listed when both are at
     * or below the level.
     */
    private static final String REORDER_BASE = """
            with stock as (
              select sb.product_id, coalesce(sum(sb.qty), 0) as qty
                from pharmacy.stock_balance sb
                join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
               where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'AVAILABLE' and b.expiry_date >= :today
               group by sb.product_id),
            open_po as (
              select l.product_id, sum(l.qty - l.received_qty) as open_units, min(p.doc_no) as po_no
                from pharmacy.purchase_order p
                join pharmacy.purchase_order_line l on l.tenant_id = p.tenant_id and l.purchase_order_id = p.id
               where p.tenant_id = :tenantId and p.store_id = :storeId and p.status in ('APPROVED', 'SENT', 'PARTLY_RECEIVED')
                 and not l.removed and not l.closed_short and l.received_qty < l.qty
               group by l.product_id),
            candidates as (
              select ps.product_id, ps.reorder_level, coalesce(s.qty, 0) as cs_stock, coalesce(o.open_units, 0) as open_units,
                     o.po_no, coalesce(g.conversion, pr.conversion) as conversion, coalesce(g.name, pr.name) as name,
                     coalesce(o.open_units, 0) * coalesce(g.conversion, pr.conversion) as open_issue
                from pharmacy.product_store_setting ps
                join pharmacy.product pr on pr.tenant_id = ps.tenant_id and pr.id = ps.product_id
                left join pharmacy.catalogue_product g on g.id = pr.catalogue_product_id
                left join stock s on s.product_id = ps.product_id
                left join open_po o on o.product_id = ps.product_id
               where ps.tenant_id = :tenantId and ps.store_id = :storeId and ps.reorder_level is not null and pr.status = 'ACTIVE'
                 and coalesce(s.qty, 0) + coalesce(o.open_units, 0) * coalesce(g.conversion, pr.conversion) <= ps.reorder_level)
            """;
    private static final String REORDER = REORDER_BASE
            + "select product_id, cs_stock, reorder_level, open_issue, open_units, po_no from candidates"
            + " order by name, product_id limit :limit offset :offset";
    private static final String REORDER_COUNT = REORDER_BASE + "select count(*) from candidates";
    private static final String ALL_STORES = """
            select coalesce(sum(sb.qty), 0)::int
              from pharmacy.stock_balance sb
              join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
             where sb.tenant_id = :tenantId and sb.product_id = :productId and sb.state = 'AVAILABLE' and b.expiry_date >= :today
            """;
    private static final String LAST_RATE = """
            select l.net_rate
              from pharmacy.goods_receipt g
              join pharmacy.goods_receipt_line l on l.tenant_id = g.tenant_id and l.goods_receipt_id = g.id
             where g.tenant_id = :tenantId and g.vendor_id = :vendorId and g.status = 'POSTED' and l.product_id = :productId
               and not l.removed and l.net_rate is not null
             order by g.posted_at desc
             limit 1
            """;

    private final JdbcClient jdbc;

    public JdbcPurchaseOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcPurchaseOrderRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcPurchaseOrderRepository::map).optional();
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcPurchaseOrderRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdateById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcPurchaseOrderRepository::map).optional();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID purchaseOrderId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("id", purchaseOrderId)
                .query(JdbcPurchaseOrderRepository::mapLine).list();
    }

    @Override
    public List<Line> linesForUpdate(UUID tenantId, UUID purchaseOrderId) {
        return jdbc.sql(LINES_FOR_UPDATE).param("tenantId", tenantId).param("id", purchaseOrderId)
                .query(JdbcPurchaseOrderRepository::mapLine).list();
    }

    @Override
    public void insert(UUID tenantId, UUID unitId, UUID id, UUID storeId, UUID vendorId, LocalDate deliveryExpected,
                       int creditDays, UUID createdBy) {
        jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("id", id).param("storeId", storeId)
                .param("vendorId", vendorId).param("delivery", deliveryExpected == null ? null : java.sql.Date.valueOf(deliveryExpected),
                        java.sql.Types.DATE)
                .param("creditDays", creditDays).param("actor", createdBy).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, UUID purchaseOrderId, NewLine line, UUID actor) {
        jdbc.sql(INSERT_LINE).param("tenantId", tenantId).param("unitId", unitId).param("id", purchaseOrderId)
                .param("productId", line.productId()).param("sort", line.sort()).param("qty", line.qty())
                .param("rate", line.rate()).param("gstRate", line.gstRate()).param("amount", line.amountPaise())
                .param("gst", line.gstPaise()).param("actor", actor).update();
    }

    @Override
    public void updateLine(UUID tenantId, UUID lineId, NewLine line, UUID actor) {
        jdbc.sql(UPDATE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("sort", line.sort())
                .param("qty", line.qty()).param("rate", line.rate()).param("gstRate", line.gstRate())
                .param("amount", line.amountPaise()).param("gst", line.gstPaise()).param("actor", actor).update();
    }

    @Override
    public void removeLine(UUID tenantId, UUID lineId, UUID actor) {
        jdbc.sql(REMOVE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("actor", actor).update();
    }

    @Override
    public boolean updateDraft(UUID tenantId, UUID id, long version, UUID vendorId, LocalDate deliveryExpected, int creditDays,
                               long subtotalPaise, long gstPaise, long totalPaise, UUID actor) {
        return jdbc.sql(UPDATE_DRAFT).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("vendorId", vendorId).param("delivery", deliveryExpected == null ? null
                        : java.sql.Date.valueOf(deliveryExpected), java.sql.Types.DATE)
                .param("creditDays", creditDays).param("subtotal", subtotalPaise).param("gst", gstPaise)
                .param("total", totalPaise).param("actor", actor).update() == 1;
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
        return jdbc.sql(ATTACH).param("tenantId", tenantId).param("id", id).param("requestId", requestId)
                .param("actor", actor).update() == 1;
    }

    @Override
    public boolean markSent(UUID tenantId, UUID id, long version, LocalDate sentOn, UUID actor) {
        return jdbc.sql(MARK_SENT).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("sentOn", java.sql.Date.valueOf(sentOn)).param("actor", actor).update() == 1;
    }

    @Override
    public int closeShort(UUID tenantId, UUID id, long version, UUID actor) {
        int lines = jdbc.sql(CLOSE_LINES).param("tenantId", tenantId).param("id", id).param("actor", actor).update();
        if (jdbc.sql(CLOSE_HEADER).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("actor", actor).update() != 1) {
            return -1;
        }
        return lines;
    }

    @Override
    public void addReceived(UUID tenantId, UUID lineId, int qty, UUID actor) {
        jdbc.sql(ADD_RECEIVED).param("tenantId", tenantId).param("lineId", lineId).param("qty", qty).param("actor", actor)
                .update();
    }

    @Override
    public void closeLineShort(UUID tenantId, UUID lineId, UUID actor) {
        jdbc.sql(CLOSE_LINE).param("tenantId", tenantId).param("lineId", lineId).param("actor", actor).update();
    }

    @Override
    public boolean vendorSupplies(UUID tenantId, UUID vendorId, UUID storeId) {
        return Boolean.TRUE.equals(jdbc.sql(SUPPLIES).param("tenantId", tenantId).param("vendorId", vendorId)
                .param("storeId", storeId).query(Boolean.class).single());
    }

    @Override
    public Slice list(UUID tenantId, UUID storeId, List<String> statuses, boolean overdueOnly, LocalDate today, int limit,
                      int offset) {
        String csv = statuses == null || statuses.isEmpty() ? null : String.join(",", statuses);
        List<ListRow> items = jdbc.sql(LIST).param("tenantId", tenantId).param("storeId", storeId).param("statuses", csv)
                .param("overdueOnly", overdueOnly).param("today", java.sql.Date.valueOf(today)).param("limit", limit)
                .param("offset", offset).query((rs, n) -> new ListRow(rs.getObject("id", UUID.class), rs.getString("doc_no"),
                        rs.getString("vendor_name"), rs.getLong("total_paise"), rs.getString("status"),
                        date(rs, "delivery_expected"), rs.getInt("short_lines"), rs.getBoolean("outstanding"))).list();
        long total = jdbc.sql(COUNT).param("tenantId", tenantId).param("storeId", storeId).param("statuses", csv)
                .param("overdueOnly", overdueOnly).param("today", java.sql.Date.valueOf(today)).query(Long.class).single();
        return new Slice(items, total);
    }

    @Override
    public Tiles tiles(UUID tenantId, UUID storeId, LocalDate today) {
        return jdbc.sql(TILES).param("tenantId", tenantId).param("storeId", storeId).param("today", java.sql.Date.valueOf(today))
                .query((rs, n) -> new Tiles(rs.getInt("draft"), rs.getInt("waiting"), rs.getInt("sent"), rs.getInt("partly"),
                        rs.getInt("overdue"))).single();
    }

    @Override
    public List<Receipt> receipts(UUID tenantId, UUID purchaseOrderId) {
        return jdbc.sql(RECEIPTS).param("tenantId", tenantId).param("id", purchaseOrderId)
                .query((rs, n) -> new Receipt(rs.getObject("id", UUID.class), rs.getString("doc_no"),
                        rs.getTimestamp("posted_at").toInstant())).list();
    }

    @Override
    public List<UUID> createdTogether(UUID tenantId, UUID storeId, UUID createdBy, Instant createdAt) {
        return jdbc.sql(TOGETHER).param("tenantId", tenantId).param("storeId", storeId).param("actor", createdBy)
                .param("at", Timestamp.from(createdAt)).query((rs, n) -> rs.getObject("id", UUID.class)).list();
    }

    @Override
    public int shortLineCount(UUID tenantId, UUID purchaseOrderId) {
        return jdbc.sql(SHORT_LINES).param("tenantId", tenantId).param("id", purchaseOrderId).query(Integer.class).single();
    }

    @Override
    public List<ReorderRow> reorder(UUID tenantId, UUID storeId, LocalDate today, int limit, int offset) {
        return jdbc.sql(REORDER).param("tenantId", tenantId).param("storeId", storeId).param("today", java.sql.Date.valueOf(today))
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> new ReorderRow(rs.getObject("product_id", UUID.class), rs.getInt("cs_stock"),
                        rs.getInt("reorder_level"), rs.getInt("open_issue"), rs.getInt("open_units"), rs.getString("po_no")))
                .list();
    }

    @Override
    public long reorderTotal(UUID tenantId, UUID storeId, LocalDate today) {
        return jdbc.sql(REORDER_COUNT).param("tenantId", tenantId).param("storeId", storeId)
                .param("today", java.sql.Date.valueOf(today)).query(Long.class).single();
    }

    @Override
    public int allStoresStock(UUID tenantId, UUID productId, LocalDate today) {
        return jdbc.sql(ALL_STORES).param("tenantId", tenantId).param("productId", productId)
                .param("today", java.sql.Date.valueOf(today)).query(Integer.class).single();
    }

    @Override
    public Optional<BigDecimal> lastPostedRate(UUID tenantId, UUID vendorId, UUID productId) {
        return jdbc.sql(LAST_RATE).param("tenantId", tenantId).param("vendorId", vendorId).param("productId", productId)
                .query(BigDecimal.class).optional();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
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
                date(rs, "delivery_expected"), rs.getInt("credit_days"), date(rs, "sent_on"),
                rs.getObject("approval_request_id", UUID.class), rs.getLong("subtotal_paise"), rs.getLong("gst_paise"),
                rs.getLong("total_paise"), instant(rs, "created_at"), rs.getObject("created_by", UUID.class),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Line mapLine(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("sort"),
                rs.getInt("qty"), rs.getBigDecimal("rate"), rs.getBigDecimal("gst_rate"), rs.getLong("amount_paise"),
                rs.getLong("gst_paise"), rs.getInt("received_qty"), rs.getBoolean("closed_short"));
    }
}
