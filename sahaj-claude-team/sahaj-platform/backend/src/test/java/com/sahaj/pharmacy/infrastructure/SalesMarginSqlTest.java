package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Gross margin definition of pharmacy-sales.yaml 0.3.9 (WP-N). The statement itself runs against PostgreSQL in the
 * integration tests; here its shape is pinned.
 */
class SalesMarginSqlTest {

    private static String sql() throws ReflectiveOperationException {
        Field field = JdbcReportRepository.class.getDeclaredField("SALES_MARGIN");
        field.setAccessible(true);
        return ((String) field.get(null)).toLowerCase(Locale.ROOT).replaceAll("\s+", " ");
    }

    @Test
    void theRefundSubqueryIsFilteredByProductPeriodAndStore() throws ReflectiveOperationException {
        String refunds = sql().substring(sql().indexOf("from pharmacy.sales_return r"));

        assertThat(refunds).contains("cast(:productid as uuid) is null or rl.product_id = cast(:productid as uuid)");
        assertThat(refunds).contains("r.completed_at >=").contains("r.completed_at <");
        assertThat(refunds).contains("r.store_id in (:storeids)");
    }

    @Test
    void theSaleValueBehindTheMarginIsTheTaxableValueNotTheNet() throws ReflectiveOperationException {
        String sql = sql();

        assertThat(sql).contains("coalesce(sum(l.taxable_paise), 0)::bigint as taxable");
        assertThat(sql).contains("coalesce(a.taxable, 0) - coalesce(rr.taxable_refunded, 0)");
        assertThat(sql).contains("sum(rl.taxable_paise)::bigint as taxable_refunded");
        assertThat(sql).contains("(coalesce(a.net, 0) - coalesce(rr.refunded, 0))::bigint");
    }

    @Test
    void theCostOfUnitsReturnedToAvailableOrQuarantineIsSubtractedFromTheCost() throws ReflectiveOperationException {
        String sql = sql();

        assertThat(sql).contains("rl.destination in ('available', 'quarantine')");
        assertThat(sql).contains("round(sum(rl.qty * rb.purchase_rate) * 100)");
        assertThat(sql).contains("coalesce(a.cost, 0) - coalesce(rr.returned_cost, 0)");
    }

    @Test
    void aDayAndStoreWithReturnsAndNoBillStillGetsARow() throws ReflectiveOperationException {
        String sql = sql();

        assertThat(sql).contains(") a full join (select r.store_id");
        assertThat(sql).contains("coalesce(a.bills, 0)::bigint").contains("coalesce(a.qty, 0)::bigint")
                .contains("coalesce(a.discount, 0)::bigint").contains("coalesce(a.tax, 0)::bigint");
        assertThat(sql).contains("st.id = coalesce(a.store_id, rr.store_id)");
    }

    @Test
    void aCancelledBillIsNotASale() throws ReflectiveOperationException {
        assertThat(sql()).contains("b.status = 'saved'");
    }
}
