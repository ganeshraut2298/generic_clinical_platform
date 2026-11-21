package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.RegisterReadRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over the H1 and Schedule X register rows, the AVAILABLE stock ledger and the closed stock audits (select only). The
 * prescriber filter is a lower-case contains match with the LIKE characters escaped. No patient field is logged.
 */
@Repository("pharmacyJdbcRegisterReadRepository")
public class JdbcRegisterReadRepository implements RegisterReadRepository {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String H1_COLUMNS = """
            id, kind, reverses_row_id, reversal_reason, reversal_ref, occurred_at, bill_no, patient_id, patient_name, patient_age,
            patient_address, prescriber_name, prescriber_reg_no, prescription_date, rx_source, rx_record_id, product_id,
            prescribed_product_id, batch_id, qty, pharmacist_membership_id, override_reason_key
            """;
    private static final String H1_WHERE = """
             where tenant_id = :t and store_id = :s and occurred_at >= :from and occurred_at < :to
               and (cast(:product as uuid) is null or product_id = cast(:product as uuid))
               and (cast(:prescriber as text) is null or lower(prescriber_name) like cast(:prescriber as text) escape '\\')
            """;
    private static final String X_COLUMNS = """
            id, kind, occurred_at, source_type, source_no, reversal_reason, patient_id, patient_name, patient_age, prescriber_name,
            prescriber_reg_no, prescription_date, product_id, batch_id, qty, first_signer_membership_id, second_signer_membership_id
            """;
    private static final String X_WHERE = """
             where tenant_id = :t and store_id = :s and occurred_at >= :from and occurred_at < :to
               and (cast(:product as uuid) is null or product_id = cast(:product as uuid))
            """;

    private final JdbcClient jdbc;

    public JdbcRegisterReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String like(String prescriberLike) {
        return prescriberLike == null || prescriberLike.isBlank() ? null
                : "%" + prescriberLike.trim().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
    }

    private JdbcClient.StatementSpec h1(String sql, UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId,
                                        String prescriber) {
        return jdbc.sql(sql).param("t", tenantId).param("s", storeId).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).param("product", productId).param("prescriber", like(prescriber));
    }

    @Override
    public List<H1Row> h1Page(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, String prescriberLike,
                              int limit, int offset) {
        return h1("select " + H1_COLUMNS + " from pharmacy.h1_register_row" + H1_WHERE
                + " order by occurred_at desc, id limit :limit offset :offset", tenantId, storeId, from, to, productId,
                prescriberLike).param("limit", limit).param("offset", offset).query(JdbcRegisterReadRepository::h1Row).list();
    }

    @Override
    public long h1Count(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, String prescriberLike) {
        return h1("select count(*) from pharmacy.h1_register_row" + H1_WHERE, tenantId, storeId, from, to, productId,
                prescriberLike).query(Long.class).single();
    }

    @Override
    public List<H1Row> h1All(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, String prescriberLike,
                             int limit) {
        return h1("select " + H1_COLUMNS + " from pharmacy.h1_register_row" + H1_WHERE
                + " order by occurred_at, id limit :limit", tenantId, storeId, from, to, productId, prescriberLike)
                .param("limit", limit).query(JdbcRegisterReadRepository::h1Row).list();
    }

    @Override
    public int h1Entries(UUID tenantId, UUID storeId, Instant from, Instant to, boolean overridesOnly) {
        return jdbc.sql("select count(*) from pharmacy.h1_register_row where tenant_id = :t and store_id = :s"
                + " and occurred_at >= :from and occurred_at < :to and kind = 'ENTRY'"
                + (overridesOnly ? " and override_reason_key is not null" : ""))
                .param("t", tenantId).param("s", storeId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query(Integer.class).single();
    }

    @Override
    public List<XRow> xPage(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId, int limit, int offset) {
        return jdbc.sql("select " + X_COLUMNS + " from pharmacy.schedule_x_register_row" + X_WHERE
                + " order by occurred_at desc, id limit :limit offset :offset").param("t", tenantId).param("s", storeId)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("product", productId)
                .param("limit", limit).param("offset", offset).query(JdbcRegisterReadRepository::xRow).list();
    }

    @Override
    public long xCount(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId) {
        return jdbc.sql("select count(*) from pharmacy.schedule_x_register_row" + X_WHERE).param("t", tenantId)
                .param("s", storeId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("product", productId).query(Long.class).single();
    }

    @Override
    public List<XBalance> xBalances(UUID tenantId, UUID storeId, Instant from, Instant to, UUID productId) {
        return jdbc.sql("""
                select m.product_id, coalesce(g.ndps, p.ndps, false) as ndps,
                       coalesce(sum(case when m.occurred_at < :from then m.qty_in - m.qty_out end), 0)::bigint as opening,
                       coalesce(sum(case when m.occurred_at >= :from then m.qty_in end), 0)::bigint as received,
                       coalesce(sum(case when m.occurred_at >= :from then m.qty_out end), 0)::bigint as issued,
                       coalesce(sum(m.qty_in - m.qty_out), 0)::bigint as closing
                  from pharmacy.stock_ledger m
                  join pharmacy.product p on p.tenant_id = m.tenant_id and p.id = m.product_id
                  left join pharmacy.catalogue_product g on g.id = p.catalogue_product_id
                 where m.tenant_id = :t and m.store_id = :s and m.state = 'AVAILABLE' and m.occurred_at < :to
                   and (cast(:product as uuid) is null or m.product_id = cast(:product as uuid))
                   and (coalesce(p.schedule, g.schedule) = 'X' or coalesce(g.ndps, p.ndps, false))
                 group by m.product_id, coalesce(g.ndps, p.ndps, false)
                having sum(case when m.occurred_at >= :from then m.qty_in + m.qty_out else 0 end) > 0
                    or coalesce(sum(m.qty_in - m.qty_out), 0) <> 0
                 order by m.product_id
                """).param("t", tenantId).param("s", storeId).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).param("product", productId)
                .query((rs, n) -> new XBalance(rs.getObject("product_id", UUID.class), rs.getBoolean("ndps"),
                        rs.getLong("opening"), rs.getLong("received"), rs.getLong("issued"), rs.getLong("closing"))).list();
    }

    @Override
    public Map<UUID, XCount> latestCounts(UUID tenantId, UUID storeId, Collection<UUID> productIds) {
        Map<UUID, XCount> out = new HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select distinct on (l.product_id) l.product_id, a.closed_at,
                       (select coalesce(sum(l2.counted_qty), 0) from pharmacy.stock_audit_line l2
                         where l2.tenant_id = l.tenant_id and l2.stock_audit_id = a.id and l2.product_id = l.product_id
                           and not l2.not_counted and l2.counted_qty is not null)::bigint as counted
                  from pharmacy.stock_audit_line l
                  join pharmacy.stock_audit a on a.tenant_id = l.tenant_id and a.id = l.stock_audit_id
                 where l.tenant_id = :t and a.store_id = :s and a.status = 'CLOSED' and l.product_id in (:ids)
                   and not l.not_counted and l.counted_qty is not null
                 order by l.product_id, a.closed_at desc
                """).param("t", tenantId).param("s", storeId).param("ids", productIds).query((rs, n) -> {
                    out.put(rs.getObject("product_id", UUID.class), new XCount(rs.getLong("counted"),
                            rs.getTimestamp("closed_at").toInstant().atZone(IST).toLocalDate()));
                    return null;
                }).list();
        return out;
    }

    private static H1Row h1Row(ResultSet rs, int n) throws SQLException {
        java.sql.Date date = rs.getDate("prescription_date");
        return new H1Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("reverses_row_id", UUID.class),
                rs.getString("reversal_reason"), rs.getString("reversal_ref"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("bill_no"), rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                rs.getInt("patient_age"), rs.getString("patient_address"), rs.getString("prescriber_name"),
                rs.getString("prescriber_reg_no"), date == null ? null : date.toLocalDate(), rs.getString("rx_source"),
                rs.getObject("rx_record_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("prescribed_product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("qty"),
                rs.getObject("pharmacist_membership_id", UUID.class), rs.getString("override_reason_key"));
    }

    private static XRow xRow(ResultSet rs, int n) throws SQLException {
        java.sql.Date date = rs.getDate("prescription_date");
        return new XRow(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("source_type"), rs.getString("source_no"), rs.getString("reversal_reason"),
                rs.getObject("patient_id", UUID.class), rs.getString("patient_name"), rs.getInt("patient_age"),
                rs.getString("prescriber_name"), rs.getString("prescriber_reg_no"), date == null ? null : date.toLocalDate(),
                rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("qty"),
                rs.getObject("first_signer_membership_id", UUID.class), rs.getObject("second_signer_membership_id", UUID.class));
    }
}
