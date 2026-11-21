package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.SaleSearchRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC for the counter's search: one grouped read over {@code stock_balance} and {@code batch} for a store. */
@Repository("pharmacyJdbcSaleSearchRepository")
public class JdbcSaleSearchRepository implements SaleSearchRepository {

    private static final String SHELF = """
            select sb.product_id, sum(sb.qty) as qty,
                   (array_agg(b.mrp order by b.expiry_date, b.first_received_at, b.id))[1] as first_mrp
              from pharmacy.stock_balance sb
              join pharmacy.batch b on b.tenant_id = sb.tenant_id and b.id = sb.batch_id
             where sb.tenant_id = :tenantId and sb.store_id = :storeId and sb.product_id in (:ids)
               and sb.state = 'AVAILABLE' and sb.qty > 0 and b.expiry_date >= :today
             group by sb.product_id
            """;

    private final JdbcClient jdbc;

    public JdbcSaleSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Shelf> shelf(UUID tenantId, UUID storeId, Collection<UUID> productIds, LocalDate today) {
        Map<UUID, Shelf> out = new HashMap<>();
        if (productIds.isEmpty()) {
            return out;
        }
        jdbc.sql(SHELF).param("tenantId", tenantId).param("storeId", storeId).param("ids", productIds)
                .param("today", Date.valueOf(today))
                .query((rs, n) -> Map.entry(rs.getObject("product_id", UUID.class),
                        new Shelf(rs.getInt("qty"), rs.getBigDecimal("first_mrp"))))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
