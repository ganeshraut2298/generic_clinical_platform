package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/** SAH-31: the catalogue is read in select order and rule order from the two reference tables, with no filter. */
class JdbcDrugCatalogueRepositoryTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    private String sqlSent(Runnable call) {
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            call.run();
        } catch (RuntimeException e) {
            // the row mapping is not under test
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        return sql.getValue();
    }

    @Test
    void theDrugsAreReadInSelectOrder() {
        assertThat(sqlSent(() -> new JdbcDrugCatalogueRepository(jdbc).findDrugs()))
                .contains("from ipd.drug").contains("order by sort_order")
                .contains("key, name, route, classes, high_alert").doesNotContain("tenant_id");
    }

    @Test
    void theInteractionsAreReadInRuleOrder() {
        assertThat(sqlSent(() -> new JdbcDrugCatalogueRepository(jdbc).findInteractions()))
                .contains("from ipd.drug_interaction").contains("order by sort_order")
                .contains("class_a, class_b, severity, text").doesNotContain("tenant_id");
    }
}
