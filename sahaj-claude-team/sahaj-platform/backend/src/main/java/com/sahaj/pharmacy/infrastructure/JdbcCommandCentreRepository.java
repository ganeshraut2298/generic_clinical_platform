package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.CommandCentreRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the Command Center cards (select only). The bill and return reads use the index on store and time (migrations 1840, 2000),
 * the expiry bands the stock balance and batch tables. Value is quantity times the batch purchase rate in paise.
 */
@Repository("pharmacyJdbcCommandCentreRepository")
public class JdbcCommandCentreRepository implements CommandCentreRepository {

    private final JdbcClient jdbc;

    public JdbcCommandCentreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Sales sales(UUID tenantId, Collection<UUID> storeIds, Instant from, Instant to) {
        if (storeIds.isEmpty()) {
            return new Sales(0, 0, 0);
        }
        long[] bills = jdbc.sql("""
                select coalesce(sum(net_paise), 0)::bigint, count(*)::bigint from pharmacy.bill
                 where tenant_id = :t and store_id in (:stores) and status = 'SAVED' and saved_at >= :from and saved_at < :to
                """).param("t", tenantId).param("stores", storeIds).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        long refunded = jdbc.sql("""
                select coalesce(sum(rl.refund_paise), 0)::bigint
                  from pharmacy.sales_return r
                  join pharmacy.sales_return_line rl on rl.tenant_id = r.tenant_id and rl.sales_return_id = r.id
                 where r.tenant_id = :t and r.store_id in (:stores) and r.status = 'COMPLETED' and rl.destination <> 'REFUSED'
                   and r.completed_at >= :from and r.completed_at < :to
                """).param("t", tenantId).param("stores", storeIds).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).query(Long.class).single();
        return new Sales(bills[0], (int) bills[1], refunded);
    }

    @Override
    public List<ExpiryBand> expiryBands(UUID tenantId, Collection<UUID> storeIds, LocalDate today) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select sb.store_id, sb.product_id,
                       case when b.expiry_date < :today then 'EXPIRED'
                            when b.expiry_date <= :today + 30 then 'D0_30'
                            when b.expiry_date <= :today + 60 then 'D31_60'
                            else 'D61_90' end as band,
                       count(distinct b.id)::int as batches,
                       coalesce(round(sum(sb.qty * b.purchase_rate) * 100), 0)::bigint as value
                  from pharmacy.stock_balance sb
                  join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
                 where sb.tenant_id = :t and sb.store_id in (:stores) and sb.state = 'AVAILABLE' and sb.qty > 0
                   and b.expiry_date <= :today + 90
                 group by sb.store_id, sb.product_id, band
                """).param("t", tenantId).param("stores", storeIds).param("today", java.sql.Date.valueOf(today))
                .query((rs, n) -> new ExpiryBand(rs.getObject("store_id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("band"), rs.getInt("batches"), rs.getLong("value"))).list();
    }

    @Override
    public Udhar udhar(UUID tenantId, Instant since) {
        return jdbc.sql("""
                select coalesce(sum(c.due_paise), 0)::bigint as due,
                       coalesce(sum(greatest(0, c.due_paise - coalesce((select sum(e.amount_paise)
                              from pharmacy.customer_account_entry e
                             where e.tenant_id = c.tenant_id and e.customer_id = c.id and e.kind = 'CREDIT_BILL'
                               and e.occurred_at >= :since), 0))), 0)::bigint as over60,
                       coalesce(bool_or(c.credit_limit_paise > 0), false) as any_limit
                  from pharmacy.customer c where c.tenant_id = :t and c.status = 'ACTIVE'
                """).param("t", tenantId).param("since", Timestamp.from(since))
                .query((rs, n) -> new Udhar(rs.getLong("due"), rs.getLong("over60"), rs.getBoolean("any_limit"))).single();
    }

    @Override
    public int indentsWaiting(UUID tenantId, Collection<UUID> storeIds) {
        if (storeIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                select count(*)::int from pharmacy.indent i
                 where i.tenant_id = :t
                   and ((i.to_store_id in (:stores) and i.status in ('APPROVED', 'PARTLY_DISPATCHED'))
                     or (i.from_store_id in (:stores) and i.status = 'DISPATCHED'))
                """).param("t", tenantId).param("stores", storeIds).query(Integer.class).single();
    }
}
