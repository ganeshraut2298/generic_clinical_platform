package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StockRepository;
import com.sahaj.pharmacy.domain.StockState;
import java.math.BigDecimal;
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
 * JDBC over {@code pharmacy.batch}, {@code pharmacy.stock_balance} and {@code pharmacy.stock_ledger}. Row level security scopes
 * every statement to the bound clinic. The generated column {@code batch.batch_key} is never written; the ledger takes its
 * {@code occurred_at} from the database. Batch numbers, quantities and names are never logged here.
 */
@Repository
public class JdbcStockRepository implements StockRepository {

    private static final String BAL_COLUMNS = """
            sb.id, sb.store_id, sb.product_id, sb.batch_id, sb.state, sb.qty, b.batch_no, b.expiry_date, b.first_received_at,
            b.mrp, b.purchase_rate
            """;
    private static final String BAL_FROM = """
              from pharmacy.stock_balance sb
              join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
            """;
    private static final String LOCK_BALANCES = "select " + BAL_COLUMNS + BAL_FROM + """
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.product_id in (:products)
             order by sb.product_id, sb.batch_id, sb.state
               for update of sb
            """;
    private static final String ENSURE_BALANCE = """
            insert into pharmacy.stock_balance (tenant_id, unit_id, store_id, product_id, batch_id, state, qty, created_by)
            values (:tenantId, :unitId, :storeId, :productId, :batchId, :state, 0, :actor)
            on conflict (tenant_id, store_id, batch_id, state) do nothing
            """;
    private static final String LOCK_ONE_BALANCE = "select " + BAL_COLUMNS + BAL_FROM + """
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.batch_id = :batchId and sb.state = :state
               for update of sb
            """;
    private static final String LOCK_EXPIRED = "select " + BAL_COLUMNS + BAL_FROM + """
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'AVAILABLE' and sb.qty > 0
               and b.expiry_date < :today
             order by sb.product_id, sb.batch_id
               for update of sb skip locked
            """;
    private static final String SET_BALANCE = """
            update pharmacy.stock_balance
               set qty = :qty, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id
            """;
    private static final String INSERT_LEDGER = """
            insert into pharmacy.stock_ledger (tenant_id, unit_id, movement_id, txn_type, doc_type, doc_id, doc_no, doc_line_id,
                   store_id, state, product_id, batch_id, qty_in, qty_out, balance_after, actor_kind, actor_membership_id,
                   second_signer_membership_id, approved_by_membership_id, reason_key, reverses_ledger_id, admission_id)
            values (:tenantId, :unitId, :movementId, :txnType, cast(:docType as text), cast(:docId as uuid),
                   cast(:docNo as text), cast(:docLineId as uuid), :storeId, :state, :productId, :batchId, :qtyIn, :qtyOut,
                   :balanceAfter, :actorKind, cast(:actor as uuid), cast(:signer as uuid), cast(:approver as uuid),
                   cast(:reason as text), cast(:reverses as uuid), cast(:admission as uuid))
            """;
    private static final String FIND_LEDGER = """
            select id, store_id, batch_id, product_id, state from pharmacy.stock_ledger
             where tenant_id = :tenantId and id = :id limit 1
            """;
    private static final String ENSURE_BATCH = """
            insert into pharmacy.batch (tenant_id, unit_id, product_id, batch_no, expiry_date, mrp, purchase_rate, source, created_by)
            values (:tenantId, :unitId, :productId, :batchNo, :expiry, :mrp, cast(:rate as numeric), :source, :actor)
            on conflict on constraint uq_batch_identity do nothing
            """;
    private static final String BATCH_COLUMNS = """
            select b.id, b.product_id, b.batch_no, b.expiry_date, b.mrp, b.purchase_rate, b.vendor_id, b.first_received_at
              from pharmacy.batch b
            """;
    private static final String BY_IDENTITY = BATCH_COLUMNS + """
             where b.tenant_id = :tenantId and b.product_id = :productId and b.batch_key = upper(btrim(:batchNo))
               and b.expiry_date = :expiry
            """;
    private static final String BATCH_BY_ID = BATCH_COLUMNS + " where b.tenant_id = :tenantId and b.id = :id";
    private static final String SET_SUPPLY = """
            update pharmacy.batch set purchase_rate = :rate, vendor_id = :vendorId, updated_at = now(), updated_by = :actor,
                   version = version + 1
             where tenant_id = :tenantId and id = :id
            """;
    private static final String LATEST_MRP = """
            select b.mrp from pharmacy.batch b where b.tenant_id = :tenantId and b.product_id = :productId
             order by b.first_received_at desc, b.id limit 1
            """;
    private static final String SET_RATE = """
            update pharmacy.batch set purchase_rate = :rate, updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id
            """;

    private static final String PRODUCT_COLUMNS = """
            p.id as product_id, coalesce(g.code, p.code) as code, coalesce(g.name, p.name) as name,
            coalesce(g.strength, p.strength) as strength, coalesce(g.form, p.form) as form,
            coalesce(p.schedule, g.schedule) as schedule, coalesce(g.purchase_unit, p.purchase_unit) as purchase_unit,
            coalesce(g.issue_unit, p.issue_unit) as issue_unit, coalesce(g.conversion, p.conversion) as conversion,
            coalesce(g.gst_rate, p.gst_rate) as gst_percent
            """;
    private static final String PRODUCT_JOIN = """
              join pharmacy.product p on p.tenant_id = sb.tenant_id and p.id = sb.product_id
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
            """;

    private static final String OVERVIEW = """
            select
              (select count(distinct sb.product_id) from pharmacy.stock_balance sb
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.qty > 0) as sku_count,
              (select count(distinct sb.batch_id) from pharmacy.stock_balance sb
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.qty > 0) as batch_count,
              (select coalesce(sum(x.value), 0) from (
                  select round(sum(sb.qty) * b.purchase_rate * 100) as value
                    from pharmacy.stock_balance sb
                    join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                   where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.qty > 0 and b.purchase_rate is not null
                   group by b.id, b.purchase_rate) x) as stock_value,
              (select count(*) from pharmacy.stock_balance sb
                 join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'AVAILABLE' and sb.qty > 0
                  and b.expiry_date between cast(:today as date) and cast(:today as date) + 30) as near30,
              (select count(*) from pharmacy.stock_balance sb
                 join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'AVAILABLE' and sb.qty > 0
                  and b.expiry_date between cast(:today as date) and cast(:today as date) + 60) as near60,
              (select count(*) from pharmacy.stock_balance sb
                 join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'AVAILABLE' and sb.qty > 0
                  and b.expiry_date between cast(:today as date) and cast(:today as date) + 90) as near90,
              (select count(*) from pharmacy.stock_balance sb
                 join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.state = 'QUARANTINE' and sb.qty > 0
                  and b.expiry_date < cast(:today as date)) as expired_quarantined,
              (select count(*) from pharmacy.product_store_setting s
                 join pharmacy.product p on p.tenant_id = s.tenant_id and p.id = s.product_id and p.status = 'ACTIVE'
                where s.tenant_id = :tenantId and s.store_id = :storeId and s.reorder_level is not null
                  and coalesce((select sum(sb.qty) from pharmacy.stock_balance sb
                                  join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                                 where sb.tenant_id = s.tenant_id and sb.store_id = s.store_id and sb.product_id = s.product_id
                                   and sb.state = 'AVAILABLE' and b.expiry_date >= cast(:today as date)), 0)
                      <= s.reorder_level) as low_stock,
              (select count(distinct sb.product_id) from pharmacy.stock_balance sb
                 join pharmacy.product p on p.tenant_id = sb.tenant_id and p.id = sb.product_id
                 left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
                where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.qty > 0
                  and coalesce(p.schedule, g.schedule) in ('H', 'H1', 'X')) as schedule_products
            """;

    private static final String BATCH_VIEW_COLUMNS = PRODUCT_COLUMNS + """
            , b.id as batch_id, b.batch_no, b.expiry_date, sb.state, sb.qty, r.rk as fefo_rank, b.mrp, b.purchase_rate,
            b.vendor_id, v.name as vendor_name, v.return_days_before_expiry,
            coalesce(b.mrp <> (select b3.mrp from pharmacy.batch b3
                                where b3.tenant_id = b.tenant_id and b3.product_id = b.product_id
                                  and b3.first_received_at < b.first_received_at
                                order by b3.first_received_at desc, b3.id limit 1), false) as new_mrp
            """;
    private static final String BATCH_VIEW_FROM = """
              from pharmacy.stock_balance sb
              join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
            """ + PRODUCT_JOIN + """
              left join pharmacy.vendor v on v.tenant_id = b.tenant_id and v.id = b.vendor_id
              left join (select sb2.batch_id, row_number() over (partition by sb2.product_id
                                  order by b2.expiry_date, b2.first_received_at, b2.id) as rk
                           from pharmacy.stock_balance sb2
                           join pharmacy.batch b2 on b2.tenant_id = sb2.tenant_id and b2.id = sb2.batch_id
                          where sb2.tenant_id = :tenantId and sb2.store_id = :storeId and sb2.state = 'AVAILABLE'
                            and sb2.qty > 0 and b2.expiry_date >= cast(:today as date)) r
                     on r.batch_id = sb.batch_id and sb.state = 'AVAILABLE'
            """;
    private static final String BATCH_WHERE = """
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.qty > 0
               and (:view <> 'NEAR_EXPIRY' or (sb.state = 'AVAILABLE' and b.expiry_date >= cast(:today as date)
                                               and b.expiry_date <= cast(:today as date) + cast(:within as integer)))
               and (:view <> 'EXPIRED' or (sb.state = 'QUARANTINE' and b.expiry_date < cast(:today as date)))
               and (:view <> 'SCHEDULE' or coalesce(p.schedule, g.schedule) in ('H', 'H1', 'X'))
               and (:like = '' or lower(coalesce(g.name, p.name)) like :like or lower(coalesce(g.salt, p.salt)) like :like
                    or lower(p.alias) like :like or lower(coalesce(g.code, p.code)) like :like or lower(p.barcode) = :exact)
            """;
    private static final String BATCH_ORDER = """
             order by lower(coalesce(g.name, p.name)), p.id, b.expiry_date, b.first_received_at, b.id, sb.state
            """;
    private static final String BATCH_PAGE = "select " + BATCH_VIEW_COLUMNS + BATCH_VIEW_FROM + BATCH_WHERE + BATCH_ORDER
            + " limit :limit offset :offset";
    private static final String BATCH_COUNT = "select count(*) " + BATCH_VIEW_FROM + BATCH_WHERE;
    private static final String BATCHES_OF_PRODUCT = "select " + BATCH_VIEW_COLUMNS + BATCH_VIEW_FROM + """
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.product_id = :productId and sb.qty > 0
            """ + BATCH_ORDER;

    private static final String LOW_STOCK_BODY = """
              from pharmacy.product_store_setting s
              join pharmacy.product p on p.tenant_id = s.tenant_id and p.id = s.product_id and p.status = 'ACTIVE'
              left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
              left join pharmacy.stock_balance sb on sb.tenant_id = s.tenant_id and sb.store_id = s.store_id
                   and sb.product_id = s.product_id and sb.state = 'AVAILABLE'
              left join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                   and b.expiry_date >= cast(:today as date)
             where s.tenant_id = :tenantId and s.store_id = :storeId and s.reorder_level is not null
             group by p.id, g.id, s.id
            having coalesce(sum(case when b.id is not null then sb.qty end), 0) <= s.reorder_level
            """;
    private static final String LOW_STOCK_PAGE = "select " + PRODUCT_COLUMNS
            + ", coalesce(sum(case when b.id is not null then sb.qty end), 0) as available, s.reorder_level "
            + LOW_STOCK_BODY + " order by lower(coalesce(g.name, p.name)), p.id limit :limit offset :offset";
    private static final String LOW_STOCK_COUNT = "select count(*) from (select p.id " + LOW_STOCK_BODY + ") x";

    private static final String AVAILABLE_OF_PRODUCT = "select " + BAL_COLUMNS + BAL_FROM + """
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.product_id = :productId
               and sb.state = 'AVAILABLE' and sb.qty > 0
             order by b.expiry_date, b.first_received_at, b.id
            """;
    private static final String STATES_OF = """
            select sb.product_id, sb.store_id,
                   coalesce(sum(case when sb.state = 'AVAILABLE' then sb.qty end), 0) as available,
                   coalesce(sum(case when sb.state = 'IN_TRANSIT' then sb.qty end), 0) as in_transit,
                   coalesce(sum(case when sb.state = 'PENDING_RETURN' then sb.qty end), 0) as pending_return,
                   coalesce(sum(case when sb.state = 'QUARANTINE' then sb.qty end), 0) as quarantine,
                   (select st.reorder_level from pharmacy.product_store_setting st
                     where st.tenant_id = sb.tenant_id and st.product_id = sb.product_id and st.store_id = sb.store_id) as reorder_level
              from pharmacy.stock_balance sb
             where sb.tenant_id = :tenantId and sb.product_id in (:productIds) and sb.store_id in (:storeIds)
             group by sb.tenant_id, sb.product_id, sb.store_id
            """;
    private static final String STATES = """
            select s.id as store_id,
                   coalesce(sum(case when sb.state = 'AVAILABLE' then sb.qty end), 0) as available,
                   coalesce(sum(case when sb.state = 'IN_TRANSIT' then sb.qty end), 0) as in_transit,
                   coalesce(sum(case when sb.state = 'PENDING_RETURN' then sb.qty end), 0) as pending_return,
                   coalesce(sum(case when sb.state = 'QUARANTINE' then sb.qty end), 0) as quarantine,
                   (select st.reorder_level from pharmacy.product_store_setting st
                     where st.tenant_id = s.tenant_id and st.product_id = :productId and st.store_id = s.id) as reorder_level
              from pharmacy.store s
              left join pharmacy.stock_balance sb on sb.tenant_id = s.tenant_id and sb.store_id = s.id
                   and sb.product_id = :productId
             where s.tenant_id = :tenantId and s.id in (:storeIds)
             group by s.id
            """;

    private final JdbcClient jdbc;

    public JdbcStockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void raiseStatementTimeout(int seconds) {
        jdbc.sql("select set_config('statement_timeout', :value, true)").param("value", seconds + "s")
                .query(String.class).single();
    }

    @Override
    public List<Bal> lockBalances(UUID tenantId, UUID storeId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(LOCK_BALANCES).param("tenantId", tenantId).param("storeId", storeId)
                .param("products", productIds).query(JdbcStockRepository::mapBal).list();
    }

    @Override
    public void ensureBalance(UUID tenantId, UUID unitId, UUID storeId, UUID productId, UUID batchId, StockState state,
                              UUID actorMembershipId) {
        jdbc.sql(ENSURE_BALANCE).param("tenantId", tenantId).param("unitId", unitId).param("storeId", storeId)
                .param("productId", productId).param("batchId", batchId).param("state", state.name())
                .param("actor", actorMembershipId).update();
    }

    @Override
    public Bal ensureBalanceLocked(UUID tenantId, UUID unitId, UUID storeId, UUID productId, UUID batchId,
                                   StockState state, UUID actorMembershipId) {
        jdbc.sql(ENSURE_BALANCE).param("tenantId", tenantId).param("unitId", unitId).param("storeId", storeId)
                .param("productId", productId).param("batchId", batchId).param("state", state.name())
                .param("actor", actorMembershipId).update();
        return jdbc.sql(LOCK_ONE_BALANCE).param("tenantId", tenantId).param("storeId", storeId).param("batchId", batchId)
                .param("state", state.name()).query(JdbcStockRepository::mapBal).single();
    }

    @Override
    public List<Bal> lockExpiredAvailable(UUID tenantId, UUID storeId, LocalDate today) {
        return jdbc.sql(LOCK_EXPIRED).param("tenantId", tenantId).param("storeId", storeId).param("today", today)
                .query(JdbcStockRepository::mapBal).list();
    }

    @Override
    public void setBalance(UUID tenantId, UUID balanceId, int qty, UUID actorMembershipId) {
        jdbc.sql(SET_BALANCE).param("tenantId", tenantId).param("id", balanceId).param("qty", qty)
                .param("actor", actorMembershipId).update();
    }

    @Override
    public void insertLedger(UUID tenantId, UUID unitId, LedgerIn l) {
        jdbc.sql(INSERT_LEDGER).param("tenantId", tenantId).param("unitId", unitId).param("movementId", l.movementId())
                .param("txnType", l.txnType()).param("docType", l.docType()).param("docId", l.docId())
                .param("docNo", l.docNo()).param("docLineId", l.docLineId()).param("storeId", l.storeId())
                .param("state", l.state().name()).param("productId", l.productId()).param("batchId", l.batchId())
                .param("qtyIn", l.qtyIn()).param("qtyOut", l.qtyOut()).param("balanceAfter", l.balanceAfter())
                .param("actorKind", l.actorMembershipId() == null ? "SYSTEM" : "USER").param("actor", l.actorMembershipId())
                .param("signer", l.secondSignerMembershipId()).param("approver", l.approvedByMembershipId())
                .param("reason", l.reasonKey()).param("reverses", l.reversesLedgerId()).param("admission", l.admissionId()).update();
    }

    @Override
    public Optional<LedgerRow> findLedger(UUID tenantId, UUID ledgerId) {
        return jdbc.sql(FIND_LEDGER).param("tenantId", tenantId).param("id", ledgerId)
                .query((rs, n) -> new LedgerRow(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("batch_id", UUID.class), rs.getObject("product_id", UUID.class),
                        StockState.valueOf(rs.getString("state")))).optional();
    }

    @Override
    public void ensureBatch(UUID tenantId, UUID unitId, UUID productId, String batchNo, LocalDate expiryDate, BigDecimal mrp,
                            BigDecimal purchaseRate, String source, UUID actorMembershipId) {
        jdbc.sql(ENSURE_BATCH).param("tenantId", tenantId).param("unitId", unitId).param("productId", productId)
                .param("batchNo", batchNo).param("expiry", expiryDate).param("mrp", mrp).param("rate", purchaseRate)
                .param("source", source).param("actor", actorMembershipId).update();
    }

    @Override
    public Optional<BatchRow> findBatchForUpdate(UUID tenantId, UUID productId, String batchNo, LocalDate expiryDate) {
        return jdbc.sql(BY_IDENTITY + " for update of b").param("tenantId", tenantId).param("productId", productId)
                .param("batchNo", batchNo).param("expiry", expiryDate).query(JdbcStockRepository::mapBatch).optional();
    }

    @Override
    public Optional<BatchRow> findBatchByIdentity(UUID tenantId, UUID productId, String batchNo, LocalDate expiryDate) {
        return jdbc.sql(BY_IDENTITY).param("tenantId", tenantId).param("productId", productId).param("batchNo", batchNo)
                .param("expiry", expiryDate).query(JdbcStockRepository::mapBatch).optional();
    }

    @Override
    public Optional<BatchRow> findBatch(UUID tenantId, UUID batchId) {
        return jdbc.sql(BATCH_BY_ID).param("tenantId", tenantId).param("id", batchId).query(JdbcStockRepository::mapBatch)
                .optional();
    }

    @Override
    public void setBatchPurchaseRate(UUID tenantId, UUID batchId, BigDecimal purchaseRate, UUID actorMembershipId) {
        jdbc.sql(SET_RATE).param("tenantId", tenantId).param("id", batchId).param("rate", purchaseRate)
                .param("actor", actorMembershipId).update();
    }

    @Override
    public void setBatchSupply(UUID tenantId, UUID batchId, BigDecimal purchaseRate, UUID vendorId, UUID actorMembershipId) {
        jdbc.sql(SET_SUPPLY).param("tenantId", tenantId).param("id", batchId).param("rate", purchaseRate)
                .param("vendorId", vendorId).param("actor", actorMembershipId).update();
    }

    @Override
    public Optional<BigDecimal> latestBatchMrp(UUID tenantId, UUID productId) {
        return jdbc.sql(LATEST_MRP).param("tenantId", tenantId).param("productId", productId).query(BigDecimal.class)
                .optional();
    }

    @Override
    public Overview overview(UUID tenantId, UUID storeId, LocalDate today) {
        return jdbc.sql(OVERVIEW).param("tenantId", tenantId).param("storeId", storeId).param("today", today)
                .query((rs, n) -> new Overview(rs.getInt("sku_count"), rs.getInt("batch_count"), rs.getLong("stock_value"),
                        rs.getInt("near30"), rs.getInt("near60"), rs.getInt("near90"), rs.getInt("expired_quarantined"),
                        rs.getInt("low_stock"), rs.getInt("schedule_products"))).single();
    }

    @Override
    public BatchSlice batches(UUID tenantId, UUID storeId, LocalDate today, String view, int withinDays, String like,
                              String exact, int limit, int offset) {
        List<BatchView> items = jdbc.sql(BATCH_PAGE).param("tenantId", tenantId).param("storeId", storeId)
                .param("today", today).param("view", view).param("within", withinDays).param("like", like)
                .param("exact", exact).param("limit", limit).param("offset", offset)
                .query(JdbcStockRepository::mapView).list();
        long total = jdbc.sql(BATCH_COUNT).param("tenantId", tenantId).param("storeId", storeId).param("today", today)
                .param("view", view).param("within", withinDays).param("like", like).param("exact", exact)
                .query(Long.class).single();
        return new BatchSlice(items, total);
    }

    @Override
    public List<BatchView> batchesOfProduct(UUID tenantId, UUID storeId, UUID productId, LocalDate today) {
        return jdbc.sql(BATCHES_OF_PRODUCT).param("tenantId", tenantId).param("storeId", storeId)
                .param("productId", productId).param("today", today).query(JdbcStockRepository::mapView).list();
    }

    @Override
    public LowStockSlice lowStock(UUID tenantId, UUID storeId, LocalDate today, int limit, int offset) {
        List<LowStockRow> items = jdbc.sql(LOW_STOCK_PAGE).param("tenantId", tenantId).param("storeId", storeId)
                .param("today", today).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new LowStockRow(rs.getObject("product_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("strength"), rs.getString("form"), rs.getString("schedule"),
                        rs.getString("purchase_unit"), rs.getString("issue_unit"), rs.getInt("conversion"),
                        rs.getInt("available"), rs.getInt("reorder_level"), rs.getBigDecimal("gst_percent"))).list();
        long total = jdbc.sql(LOW_STOCK_COUNT).param("tenantId", tenantId).param("storeId", storeId)
                .param("today", today).query(Long.class).single();
        return new LowStockSlice(items, total);
    }

    @Override
    public Optional<Integer> availableQty(UUID tenantId, UUID storeId, UUID batchId) {
        return jdbc.sql("select qty from pharmacy.stock_balance where tenant_id = :tenantId and store_id = :storeId"
                + " and batch_id = :batchId and state = 'AVAILABLE'").param("tenantId", tenantId).param("storeId", storeId)
                .param("batchId", batchId).query(Integer.class).optional();
    }

    @Override
    public List<Bal> availableOfProduct(UUID tenantId, UUID storeId, UUID productId) {
        return jdbc.sql(AVAILABLE_OF_PRODUCT).param("tenantId", tenantId).param("storeId", storeId)
                .param("productId", productId).query(JdbcStockRepository::mapBal).list();
    }

    @Override
    public List<StateRow> statesOfProduct(UUID tenantId, UUID productId, Collection<UUID> storeIds) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(STATES).param("tenantId", tenantId).param("productId", productId).param("storeIds", storeIds)
                .query((rs, n) -> {
                    int level = rs.getInt("reorder_level");
                    Integer reorder = rs.wasNull() ? null : level;
                    return new StateRow(rs.getObject("store_id", UUID.class), rs.getInt("available"),
                            rs.getInt("in_transit"), rs.getInt("pending_return"), rs.getInt("quarantine"), reorder);
                }).list();
    }

    @Override
    public java.util.Map<UUID, List<StateRow>> statesOfProducts(UUID tenantId, Collection<UUID> productIds,
                                                                Collection<UUID> storeIds) {
        java.util.Map<UUID, List<StateRow>> out = new java.util.HashMap<>();
        if (productIds.isEmpty() || storeIds.isEmpty()) {
            return out;
        }
        jdbc.sql(STATES_OF).param("tenantId", tenantId).param("productIds", productIds).param("storeIds", storeIds)
                .query((rs, n) -> {
                    int level = rs.getInt("reorder_level");
                    Integer reorder = rs.wasNull() ? null : level;
                    out.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(new StateRow(rs.getObject("store_id", UUID.class), rs.getInt("available"),
                                    rs.getInt("in_transit"), rs.getInt("pending_return"), rs.getInt("quarantine"), reorder));
                    return null;
                }).list();
        return out;
    }

    private static Bal mapBal(ResultSet rs, int rowNum) throws SQLException {
        return new Bal(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class),
                StockState.valueOf(rs.getString("state")), rs.getInt("qty"), rs.getString("batch_no"),
                rs.getObject("expiry_date", LocalDate.class), instant(rs.getTimestamp("first_received_at")),
                rs.getBigDecimal("mrp"), rs.getBigDecimal("purchase_rate"));
    }

    private static BatchRow mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new BatchRow(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("batch_no"), rs.getObject("expiry_date", LocalDate.class), rs.getBigDecimal("mrp"),
                rs.getBigDecimal("purchase_rate"), rs.getObject("vendor_id", UUID.class),
                instant(rs.getTimestamp("first_received_at")));
    }

    private static BatchView mapView(ResultSet rs, int rowNum) throws SQLException {
        int rank = rs.getInt("fefo_rank");
        Integer fefo = rs.wasNull() ? null : rank;
        int days = rs.getInt("return_days_before_expiry");
        Integer returnDays = rs.wasNull() ? null : days;
        return new BatchView(rs.getObject("product_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("strength"), rs.getString("form"), rs.getString("schedule"), rs.getString("purchase_unit"),
                rs.getString("issue_unit"), rs.getInt("conversion"), rs.getObject("batch_id", UUID.class),
                rs.getString("batch_no"), rs.getObject("expiry_date", LocalDate.class),
                StockState.valueOf(rs.getString("state")), rs.getInt("qty"), fefo, rs.getBigDecimal("mrp"),
                rs.getBigDecimal("purchase_rate"), rs.getObject("vendor_id", UUID.class), rs.getString("vendor_name"),
                returnDays, rs.getBoolean("new_mrp"), rs.getBigDecimal("gst_percent"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
