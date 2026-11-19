package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.OpeningStockRepository;
import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.OpeningStockRules;
import com.sahaj.pharmacy.domain.RejectReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code pharmacy.opening_stock_import}, {@code pharmacy.opening_stock_line} and
 * {@code pharmacy.opening_stock_line_issue}. Row level security scopes every statement to the bound tenant. No delete, and the
 * database refuses a line or issue write once the import has left DRAFT, so callers write lines, then issues, then the import
 * last. An issue is never changed except to mark it superseded. File text (batch numbers, quantities) is never logged here.
 */
@Repository
public class JdbcOpeningStockRepository implements OpeningStockRepository {

    private static final String LIVE_LINES = " from pharmacy.opening_stock_line l where l.tenant_id = i.tenant_id and l.import_id = i.id";
    /** Counts and totals: worked out from the lines while the import is a draft, the fixed columns afterwards. */
    private static final String IMPORT_COLUMNS = """
            i.id, i.store_id, i.status, i.row_count, i.uploaded_by, i.client_key, i.created_at, i.submitted_at, i.decided_by,
            i.decided_at, i.reject_reason_key, i.reject_note, i.replaced_by_import_id, i.version, i.posted_at, i.posted_by,
            (select count(*)""" + LIVE_LINES + """
            ) as received_rows,
            case when i.status = 'DRAFT' then (select count(*)""" + LIVE_LINES + """
            ) else i.line_count end as line_count,
            case when i.status = 'DRAFT' then (select coalesce(sum(l.qty), 0)""" + LIVE_LINES + """
            ) else i.total_quantity end as total_quantity,
            case when i.status = 'DRAFT' then (select coalesce(sum(l.value_paise), 0)""" + LIVE_LINES + """
            ) else i.total_value_paise end as total_value_paise,
            case when i.status = 'DRAFT' then (select count(distinct l.id)
                   from pharmacy.opening_stock_line l
                   join pharmacy.opening_stock_line_issue x on x.tenant_id = l.tenant_id and x.line_id = l.id
                        and not x.superseded and x.severity = 'ERROR'
                  where l.tenant_id = i.tenant_id and l.import_id = i.id)
                 else i.error_count end as error_count,
            case when i.status = 'DRAFT' then (select count(distinct l.id)
                   from pharmacy.opening_stock_line l
                   join pharmacy.opening_stock_line_issue x on x.tenant_id = l.tenant_id and x.line_id = l.id
                        and not x.superseded and x.severity = 'WARNING'
                  where l.tenant_id = i.tenant_id and l.import_id = i.id)
                 else i.warning_count end as warning_count
            """;
    private static final String IMPORT_SELECT = "select " + IMPORT_COLUMNS
            + " from pharmacy.opening_stock_import i where i.tenant_id = :tenantId";
    private static final String FIND = IMPORT_SELECT + " and i.store_id = :storeId and i.id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update of i";
    private static final String POSTING_LINES = """
            select l.id, l.row_no, l.product_id, coalesce(g.name, p.name) as product_name, l.batch_no, l.expiry_date, l.mrp,
                   l.purchase_rate, l.qty
              from pharmacy.opening_stock_line l
              join pharmacy.product p on p.tenant_id = l.tenant_id and p.id = l.product_id
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
             where l.tenant_id = :tenantId and l.import_id = :importId
             order by l.row_no
            """;
    private static final String MARK_POSTED = """
            update pharmacy.opening_stock_import
               set posted_at = now(), posted_by = :actor, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'APPROVED' and posted_at is null
            """;
    private static final String LOCK_UNIT_SHARED = "select id from pharmacy.unit where tenant_id = :tenantId for share";
    private static final String BY_CLIENT_KEY = IMPORT_SELECT + " and i.client_key = :clientKey";
    private static final String WITH_STATUS = IMPORT_SELECT + " and i.store_id = :storeId and i.status = :status";
    private static final String DRAFTS_OF = IMPORT_SELECT
            + " and i.store_id = :storeId and i.uploaded_by = :uploader and i.status = 'DRAFT' order by i.id for update of i";
    private static final String COUNT_STARTED = """
            select count(*) from pharmacy.opening_stock_import i
             where i.tenant_id = :tenantId and i.store_id = :storeId and i.uploaded_by = :uploader
               and i.created_at >= :since and i.created_at < :until
            """;
    private static final String LIST_STORE = IMPORT_SELECT
            + " and i.store_id = :storeId order by i.created_at desc, i.id limit :limit offset :offset";
    private static final String COUNT_STORE = """
            select count(*) from pharmacy.opening_stock_import i where i.tenant_id = :tenantId and i.store_id = :storeId
            """;
    private static final String LIST_ALL = IMPORT_SELECT
            + " and (:status = '' or i.status = :status) order by i.created_at desc, i.id limit :limit offset :offset";
    private static final String COUNT_ALL = """
            select count(*) from pharmacy.opening_stock_import i
             where i.tenant_id = :tenantId and (:status = '' or i.status = :status)
            """;

    private static final String INSERT = """
            insert into pharmacy.opening_stock_import (tenant_id, unit_id, store_id, row_count, uploaded_by, client_key,
                                                       created_by)
            values (:tenantId, :unitId, :storeId, :rowCount, :uploader, :clientKey, :uploader)
            returning id
            """;
    private static final String MARK_REPLACED = """
            update pharmacy.opening_stock_import
               set status = 'REPLACED', replaced_by_import_id = :replacedBy, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status in ('DRAFT', 'WAITING_APPROVAL')
            """;
    private static final String MARK_SUBMITTED = """
            update pharmacy.opening_stock_import
               set status = :status, line_count = :lineCount, total_quantity = :quantity, total_value_paise = :value,
                   error_count = :errors, warning_count = :warnings, submitted_at = cast(:submittedAt as timestamptz),
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'DRAFT'
            """;
    private static final String MARK_APPROVED = """
            update pharmacy.opening_stock_import
               set status = 'APPROVED', decided_by = :decidedBy, decided_at = :decidedAt, updated_at = now(),
                   updated_by = :decidedBy, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status = 'WAITING_APPROVAL'
            """;
    private static final String MARK_REJECTED = """
            update pharmacy.opening_stock_import
               set status = 'REJECTED', decided_by = :decidedBy, decided_at = :decidedAt, reject_reason_key = :reason,
                   reject_note = cast(:note as text), updated_at = now(), updated_by = :decidedBy, version = version + 1
             where tenant_id = :tenantId and id = :id and version = :version and status in ('DRAFT', 'WAITING_APPROVAL')
            """;
    private static final String MARK_PRODUCTS_HAS_STOCK = """
            update pharmacy.product
               set has_stock = true, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and not has_stock
               and id in (select l.product_id from pharmacy.opening_stock_line l
                           where l.tenant_id = :tenantId and l.import_id = :importId and l.product_id is not null)
            """;

    private static final String UPSERT_LINE = """
            insert into pharmacy.opening_stock_line (tenant_id, unit_id, import_id, row_no, store_code_raw, product_code_raw,
                                                     batch_raw, expiry_raw, mrp_raw, purchase_rate_raw, quantity_raw,
                                                     product_id, batch_no, expiry_date, mrp, purchase_rate, qty,
                                                     value_paise, created_by)
            values (:tenantId, :unitId, :importId, :rowNo, :storeCode, :productCode, :batch, :expiry, :mrpRaw, :rateRaw,
                    :quantityRaw, cast(:productId as uuid), cast(:batchNo as text), cast(:expiryDate as date),
                    cast(:mrp as numeric), cast(:rate as numeric), cast(:qty as integer), cast(:value as bigint), :actor)
            on conflict (tenant_id, import_id, row_no)
            do update set store_code_raw = excluded.store_code_raw, product_code_raw = excluded.product_code_raw,
                          batch_raw = excluded.batch_raw, expiry_raw = excluded.expiry_raw, mrp_raw = excluded.mrp_raw,
                          purchase_rate_raw = excluded.purchase_rate_raw, quantity_raw = excluded.quantity_raw,
                          product_id = excluded.product_id, batch_no = excluded.batch_no,
                          expiry_date = excluded.expiry_date, mrp = excluded.mrp, purchase_rate = excluded.purchase_rate,
                          qty = excluded.qty, value_paise = excluded.value_paise, updated_at = now(),
                          updated_by = excluded.created_by, version = pharmacy.opening_stock_line.version + 1
            returning id
            """;
    private static final String LINE_ROWS_WRITTEN = """
            select i.line_rows_written from pharmacy.opening_stock_import i
             where i.tenant_id = :tenantId and i.id = :importId
            """;
    /** DEC-077: the database freezes line_rows_written once the import has left DRAFT and never lets it go down. */
    private static final String ADD_LINE_ROWS = """
            update pharmacy.opening_stock_import
               set line_rows_written = line_rows_written + :rows, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :importId and status = 'DRAFT'
            """;
    private static final String LINES_WITH_INACTIVE_PRODUCT = """
            select l.id, l.row_no from pharmacy.opening_stock_line l
              join pharmacy.product p on p.tenant_id = l.tenant_id and p.id = l.product_id
             where l.tenant_id = :tenantId and l.import_id = :importId and p.status <> 'ACTIVE'
             order by l.row_no
            """;
    private static final String STORED_ROWS = """
            select l.id, l.row_no, l.store_code_raw, l.product_code_raw, l.batch_raw, l.expiry_raw, l.mrp_raw,
                   l.purchase_rate_raw, l.quantity_raw
              from pharmacy.opening_stock_line l
             where l.tenant_id = :tenantId and l.import_id = :importId and l.row_no in (:rowNos)
            """;
    /** An issue has no version: the only change it allows is false to true on {@code superseded} (trigger). */
    private static final String SUPERSEDE_ISSUES = """
            update pharmacy.opening_stock_line_issue
               set superseded = true
             where tenant_id = :tenantId and not superseded
               and line_id in (select l.id from pharmacy.opening_stock_line l
                                where l.tenant_id = :tenantId and l.import_id = :importId and l.row_no in (:rowNos))
            """;
    private static final String INSERT_ISSUE = """
            insert into pharmacy.opening_stock_line_issue (tenant_id, unit_id, line_id, field, code, severity, created_by)
            values (:tenantId, :unitId, :lineId, :field, :code, :severity, :actor)
            """;
    private static final String REPEATED_BATCH_LINES = """
            select l.id, l.row_no from pharmacy.opening_stock_line l
             where l.tenant_id = :tenantId and l.import_id = :importId and l.product_id is not null
               and l.batch_no is not null
               and exists (select 1 from pharmacy.opening_stock_line o
                            where o.tenant_id = :tenantId and o.import_id = l.import_id and o.product_id = l.product_id
                              and lower(o.batch_no) = lower(l.batch_no) and o.id <> l.id)
             order by l.row_no
            """;
    private static final String LINES_WITH_ISSUE = """
            select l.id from pharmacy.opening_stock_line l
              join pharmacy.opening_stock_line_issue x on x.tenant_id = l.tenant_id and x.line_id = l.id
                   and not x.superseded and x.code = :code
             where l.tenant_id = :tenantId and l.import_id = :importId
            """;
    private static final String LINES_WHERE = """
             from pharmacy.opening_stock_line l
             left join pharmacy.product p on p.tenant_id = l.tenant_id and p.id = l.product_id
             left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
            where l.tenant_id = :tenantId and l.import_id = :importId
              and (not :withIssues or exists (select 1 from pharmacy.opening_stock_line_issue x
                                               where x.tenant_id = l.tenant_id and x.line_id = l.id and not x.superseded))
            """;
    private static final String LINES = """
            select l.id, l.row_no, l.store_code_raw, l.product_code_raw, l.product_id, coalesce(g.name, p.name) as product_name,
                   l.batch_raw, l.expiry_date, l.mrp, l.purchase_rate, l.qty, l.value_paise
            """ + LINES_WHERE + " order by l.row_no limit :limit offset :offset";
    private static final String LINES_COUNT = "select count(*)" + LINES_WHERE;
    private static final String ISSUES_OF_LINES = """
            select x.line_id, l.row_no, x.field, x.code, x.severity
              from pharmacy.opening_stock_line_issue x
              join pharmacy.opening_stock_line l on l.tenant_id = x.tenant_id and l.id = x.line_id
             where x.tenant_id = :tenantId and not x.superseded and x.line_id in (:lineIds)
             order by l.row_no, x.created_at, x.field, x.code
            """;
    private static final String ERROR_ISSUES = """
            select x.line_id, l.row_no, x.field, x.code, x.severity
              from pharmacy.opening_stock_line_issue x
              join pharmacy.opening_stock_line l on l.tenant_id = x.tenant_id and l.id = x.line_id
             where x.tenant_id = :tenantId and l.import_id = :importId and not x.superseded and x.severity = 'ERROR'
             order by l.row_no, x.field, x.code
             limit :limit
            """;

    private final JdbcClient jdbc;

    public JdbcOpeningStockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ImportRow> find(UUID tenantId, UUID storeId, UUID importId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", importId)
                .query(JdbcOpeningStockRepository::mapImport).optional();
    }

    @Override
    public Optional<ImportRow> findForUpdate(UUID tenantId, UUID storeId, UUID importId) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("storeId", storeId).param("id", importId)
                .query(JdbcOpeningStockRepository::mapImport).optional();
    }

    @Override
    public List<ImportRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query(JdbcOpeningStockRepository::mapImport).list();
    }

    @Override
    public Optional<ImportRow> findWithStatus(UUID tenantId, UUID storeId, ImportStatus status) {
        return jdbc.sql(WITH_STATUS).param("tenantId", tenantId).param("storeId", storeId).param("status", status.name())
                .query(JdbcOpeningStockRepository::mapImport).optional();
    }

    @Override
    public List<PostingLine> postingLines(UUID tenantId, UUID importId) {
        return jdbc.sql(POSTING_LINES).param("tenantId", tenantId).param("importId", importId)
                .query((rs, n) -> new PostingLine(rs.getObject("id", UUID.class), rs.getInt("row_no"),
                        rs.getObject("product_id", UUID.class), rs.getString("product_name"), rs.getString("batch_no"),
                        rs.getObject("expiry_date", LocalDate.class), rs.getBigDecimal("mrp"),
                        rs.getBigDecimal("purchase_rate"), rs.getInt("qty"))).list();
    }

    @Override
    public boolean markPosted(UUID tenantId, UUID id, UUID postedBy) {
        return jdbc.sql(MARK_POSTED).param("tenantId", tenantId).param("id", id).param("actor", postedBy).update() == 1;
    }

    @Override
    public void lockUnitShared(UUID tenantId) {
        jdbc.sql(LOCK_UNIT_SHARED).param("tenantId", tenantId).query(UUID.class).optional();
    }

    @Override
    public List<ImportRow> draftsOf(UUID tenantId, UUID storeId, UUID uploadedBy) {
        return jdbc.sql(DRAFTS_OF).param("tenantId", tenantId).param("storeId", storeId).param("uploader", uploadedBy)
                .query(JdbcOpeningStockRepository::mapImport).list();
    }

    @Override
    public int countStarted(UUID tenantId, UUID storeId, UUID uploadedBy, Instant from, Instant to) {
        return jdbc.sql(COUNT_STARTED).param("tenantId", tenantId).param("storeId", storeId).param("uploader", uploadedBy)
                .param("since", Timestamp.from(from)).param("until", Timestamp.from(to)).query(Integer.class).single();
    }

    @Override
    public Slice<ImportRow> listForStore(UUID tenantId, UUID storeId, int limit, int offset) {
        List<ImportRow> items = jdbc.sql(LIST_STORE).param("tenantId", tenantId).param("storeId", storeId)
                .param("limit", limit).param("offset", offset).query(JdbcOpeningStockRepository::mapImport).list();
        long total = jdbc.sql(COUNT_STORE).param("tenantId", tenantId).param("storeId", storeId).query(Long.class)
                .single();
        return new Slice<>(items, total);
    }

    @Override
    public Slice<ImportRow> listAll(UUID tenantId, ImportStatus status, int limit, int offset) {
        String filter = status == null ? "" : status.name();
        List<ImportRow> items = jdbc.sql(LIST_ALL).param("tenantId", tenantId).param("status", filter)
                .param("limit", limit).param("offset", offset).query(JdbcOpeningStockRepository::mapImport).list();
        long total = jdbc.sql(COUNT_ALL).param("tenantId", tenantId).param("status", filter).query(Long.class).single();
        return new Slice<>(items, total);
    }

    @Override
    public UUID insert(UUID tenantId, UUID unitId, UUID storeId, int rowCount, UUID uploadedBy, UUID clientKey) {
        return jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("storeId", storeId)
                .param("rowCount", rowCount).param("uploader", uploadedBy).param("clientKey", clientKey)
                .query(UUID.class).single();
    }

    @Override
    public boolean markReplaced(UUID tenantId, UUID id, long version, UUID replacedByImportId, UUID actor) {
        return jdbc.sql(MARK_REPLACED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("replacedBy", replacedByImportId).param("actor", actor).update() == 1;
    }

    @Override
    public boolean markSubmitted(UUID tenantId, UUID id, long version, ImportStatus status, int lineCount,
                                 long totalQuantity, long totalValuePaise, int errorCount, int warningCount,
                                 Instant submittedAt, UUID actor) {
        return jdbc.sql(MARK_SUBMITTED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("status", status.name()).param("lineCount", lineCount).param("quantity", totalQuantity)
                .param("value", totalValuePaise).param("errors", errorCount).param("warnings", warningCount)
                .param("submittedAt", submittedAt).param("actor", actor).update() == 1;
    }

    @Override
    public boolean markApproved(UUID tenantId, UUID id, long version, UUID decidedBy, Instant decidedAt) {
        return jdbc.sql(MARK_APPROVED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("decidedBy", decidedBy).param("decidedAt", decidedAt).update() == 1;
    }

    @Override
    public boolean markRejected(UUID tenantId, UUID id, long version, UUID decidedBy, Instant decidedAt,
                                RejectReason reason, String note) {
        return jdbc.sql(MARK_REJECTED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("decidedBy", decidedBy).param("decidedAt", decidedAt).param("reason", reason.name())
                .param("note", note).update() == 1;
    }

    @Override
    public void markProductsHasStock(UUID tenantId, UUID importId, UUID actor) {
        jdbc.sql(MARK_PRODUCTS_HAS_STOCK).param("tenantId", tenantId).param("importId", importId).param("actor", actor)
                .update();
    }

    @Override
    public UUID upsertLine(UUID tenantId, UUID unitId, UUID importId, LineIn line, UUID actor) {
        OpeningStockRules.Raw raw = line.raw();
        OpeningStockRules.Parsed parsed = line.parsed();
        return jdbc.sql(UPSERT_LINE).param("tenantId", tenantId).param("unitId", unitId).param("importId", importId)
                .param("rowNo", line.rowNo()).param("storeCode", raw.storeCode()).param("productCode", raw.productCode())
                .param("batch", raw.batch()).param("expiry", raw.expiryDate()).param("mrpRaw", raw.mrp())
                .param("rateRaw", raw.purchaseRate()).param("quantityRaw", raw.quantity())
                .param("productId", line.productId()).param("batchNo", parsed.batchNo())
                .param("expiryDate", parsed.expiry()).param("mrp", parsed.mrp()).param("rate", parsed.purchaseRate())
                .param("qty", parsed.quantity()).param("value", parsed.valuePaise()).param("actor", actor)
                .query(UUID.class).single();
    }

    @Override
    public int lineRowsWritten(UUID tenantId, UUID importId) {
        return jdbc.sql(LINE_ROWS_WRITTEN).param("tenantId", tenantId).param("importId", importId).query(Integer.class)
                .single();
    }

    @Override
    public void addLineRowsWritten(UUID tenantId, UUID importId, int rows, UUID actor) {
        if (jdbc.sql(ADD_LINE_ROWS).param("tenantId", tenantId).param("importId", importId).param("rows", rows)
                .param("actor", actor).update() != 1) {
            throw new IllegalStateException("An opening stock import changed under its lock");
        }
    }

    @Override
    public List<LineRef> linesWithInactiveProduct(UUID tenantId, UUID importId) {
        return jdbc.sql(LINES_WITH_INACTIVE_PRODUCT).param("tenantId", tenantId).param("importId", importId)
                .query((rs, n) -> new LineRef(rs.getObject("id", UUID.class), rs.getInt("row_no"))).list();
    }

    @Override
    public List<StoredRow> storedRows(UUID tenantId, UUID importId, Collection<Integer> rowNos) {
        if (rowNos.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(STORED_ROWS).param("tenantId", tenantId).param("importId", importId).param("rowNos", rowNos)
                .query((rs, n) -> new StoredRow(rs.getObject("id", UUID.class), rs.getInt("row_no"),
                        new OpeningStockRules.Raw(rs.getString("store_code_raw"), rs.getString("product_code_raw"),
                                rs.getString("batch_raw"), rs.getString("expiry_raw"), rs.getString("mrp_raw"),
                                rs.getString("purchase_rate_raw"), rs.getString("quantity_raw"))))
                .list();
    }

    @Override
    public void supersedeIssues(UUID tenantId, UUID importId, Collection<Integer> rowNos) {
        if (rowNos.isEmpty()) {
            return;
        }
        jdbc.sql(SUPERSEDE_ISSUES).param("tenantId", tenantId).param("importId", importId).param("rowNos", rowNos)
                .update();
    }

    @Override
    public void insertIssue(UUID tenantId, UUID unitId, UUID lineId, String field, String code, String severity,
                            UUID actor) {
        jdbc.sql(INSERT_ISSUE).param("tenantId", tenantId).param("unitId", unitId).param("lineId", lineId)
                .param("field", field).param("code", code).param("severity", severity).param("actor", actor).update();
    }

    @Override
    public List<LineRef> repeatedBatchLines(UUID tenantId, UUID importId) {
        return jdbc.sql(REPEATED_BATCH_LINES).param("tenantId", tenantId).param("importId", importId)
                .query((rs, n) -> new LineRef(rs.getObject("id", UUID.class), rs.getInt("row_no"))).list();
    }

    @Override
    public List<UUID> linesWithIssue(UUID tenantId, UUID importId, String code) {
        return jdbc.sql(LINES_WITH_ISSUE).param("tenantId", tenantId).param("importId", importId).param("code", code)
                .query(UUID.class).list();
    }

    @Override
    public Slice<LineRow> lines(UUID tenantId, UUID importId, boolean withIssues, int limit, int offset) {
        List<LineRow> items = jdbc.sql(LINES).param("tenantId", tenantId).param("importId", importId)
                .param("withIssues", withIssues).param("limit", limit).param("offset", offset)
                .query(JdbcOpeningStockRepository::mapLine).list();
        long total = jdbc.sql(LINES_COUNT).param("tenantId", tenantId).param("importId", importId)
                .param("withIssues", withIssues).query(Long.class).single();
        return new Slice<>(items, total);
    }

    @Override
    public List<IssueRow> issuesOfLines(UUID tenantId, Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ISSUES_OF_LINES).param("tenantId", tenantId).param("lineIds", lineIds)
                .query(JdbcOpeningStockRepository::mapIssue).list();
    }

    @Override
    public List<IssueRow> errorIssues(UUID tenantId, UUID importId, int limit) {
        return jdbc.sql(ERROR_ISSUES).param("tenantId", tenantId).param("importId", importId).param("limit", limit)
                .query(JdbcOpeningStockRepository::mapIssue).list();
    }

    private static ImportRow mapImport(ResultSet rs, int rowNum) throws SQLException {
        String reason = rs.getString("reject_reason_key");
        return new ImportRow(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                ImportStatus.valueOf(rs.getString("status")), rs.getInt("row_count"),
                rs.getObject("uploaded_by", UUID.class), rs.getObject("client_key", UUID.class),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("submitted_at")),
                rs.getObject("decided_by", UUID.class), instant(rs.getTimestamp("decided_at")),
                reason == null ? null : RejectReason.valueOf(reason), rs.getString("reject_note"),
                rs.getObject("replaced_by_import_id", UUID.class), rs.getInt("received_rows"), rs.getInt("line_count"),
                rs.getLong("total_quantity"), rs.getLong("total_value_paise"), rs.getInt("error_count"),
                rs.getInt("warning_count"), rs.getLong("version"), instant(rs.getTimestamp("posted_at")),
                rs.getObject("posted_by", UUID.class));
    }

    private static LineRow mapLine(ResultSet rs, int rowNum) throws SQLException {
        int quantity = rs.getInt("qty");
        Integer qty = rs.wasNull() ? null : quantity;
        long value = rs.getLong("value_paise");
        Long paise = rs.wasNull() ? null : value;
        return new LineRow(rs.getObject("id", UUID.class), rs.getInt("row_no"), rs.getString("store_code_raw"),
                rs.getString("product_code_raw"), rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                rs.getString("batch_raw"), rs.getObject("expiry_date", LocalDate.class), rs.getBigDecimal("mrp"),
                rs.getBigDecimal("purchase_rate"), qty, paise);
    }

    private static IssueRow mapIssue(ResultSet rs, int rowNum) throws SQLException {
        return new IssueRow(rs.getObject("line_id", UUID.class), rs.getInt("row_no"), rs.getString("field"),
                rs.getString("code"), rs.getString("severity"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
