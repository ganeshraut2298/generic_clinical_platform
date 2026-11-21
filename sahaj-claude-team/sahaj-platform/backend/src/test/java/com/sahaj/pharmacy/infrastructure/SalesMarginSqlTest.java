package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pass-2 M6 (the statement itself runs against PostgreSQL in the integration tests): the SALES_MARGIN refund subquery is filtered by
 * product and period like the bills are, and the cost of the units returned to AVAILABLE stock is taken off the cost of goods.
 */
class SalesMarginSqlTest {

    private static String sql() throws ReflectiveOperationException {
        Field field = JdbcReportRepository.class.getDeclaredField("SALES_MARGIN");
        field.setAccessible(true);
        return ((String) field.get(null)).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Test
    void theRefundSubqueryIsFilteredByProductPeriodAndStore() throws ReflectiveOperationException {
        String refunds = sql().substring(sql().indexOf("from pharmacy.sales_return r"));

        assertThat(refunds).contains("cast(:productid as uuid) is null or rl.product_id = cast(:productid as uuid)");
        assertThat(refunds).contains("r.completed_at >=").contains("r.completed_at <");
        assertThat(refunds).contains("r.store_id in (:storeids)");
    }

    @Test
    void theCostOfUnitsReturnedToAvailableStockIsSubtractedFromTheCost() throws ReflectiveOperationException {
        String sql = sql();

        assertThat(sql).contains("case when rl.destination = 'available' then rl.qty * rb.purchase_rate else 0 end");
        assertThat(sql).contains("(a.cost - coalesce(rr.returned_cost, 0))::bigint");
    }
}
