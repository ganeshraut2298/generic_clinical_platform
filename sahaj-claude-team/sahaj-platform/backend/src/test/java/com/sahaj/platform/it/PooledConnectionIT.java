package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.asUserWithoutTenant;
import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static com.sahaj.platform.support.TestTenantSecurity.signedInWithoutTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.support.FailureProbeController;
import com.sahaj.platform.support.TenantProbeController;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-1 criterion 8, pooled connections: the request pool is cut to ONE connection, so every
 * request, whichever tenant it belongs to, must reuse the same physical PostgreSQL session. If a
 * tenant were bound to the session instead of the transaction, tenant A's identity would leak into
 * the next request. Runs as sahaj_app on sahaj_test.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TenantProbeController.class, FailureProbeController.class})
class PooledConnectionIT extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID userA;
    private UUID userB;

    @BeforeEach
    void twoTenants() throws Exception {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        createTenant(tenantA, userA, uniqueName("Pool A"));
        createTenant(tenantB, userB, uniqueName("Pool B"));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void thePoolHasExactlyOneConnection() throws SQLException {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
    }

    /** What one transaction sees on the pooled PostgreSQL session. */
    private record Seen(int pid, String setting, boolean tenantIsNull, int visibleTenants, UUID onlyTenant) {
    }

    @Test
    void theSamePhysicalConnectionCarriesNoTenantFromOneTransactionToTheNext() {
        Seen inA = runAs(authentication(tenantA, userA), () -> tx().execute(s -> snapshot()));
        Seen inNone = runAs(signedInWithoutTenant(), () -> tx().execute(s -> snapshot()));
        Seen inB = runAs(authentication(tenantB, userB), () -> tx().execute(s -> snapshot()));
        Seen noneAgain = tx().execute(s -> snapshot());

        assertThat(inA.setting()).isEqualTo(tenantA.toString());
        assertThat(inA.onlyTenant()).isEqualTo(tenantA);

        assertThat(inNone.pid()).as("same PostgreSQL session as the previous transaction").isEqualTo(inA.pid());
        assertThat(inNone.tenantIsNull()).as("tenant A was not carried over").isTrue();
        assertThat(inNone.visibleTenants()).isZero();
        assertThat(inNone.setting()).as("empty or unset, never tenant A").isNotEqualTo(tenantA.toString());

        assertThat(inB.pid()).isEqualTo(inA.pid());
        assertThat(inB.setting()).isEqualTo(tenantB.toString());
        assertThat(inB.onlyTenant()).isEqualTo(tenantB);
        assertThat(inB.visibleTenants()).isEqualTo(1);

        assertThat(noneAgain.pid()).isEqualTo(inA.pid());
        assertThat(noneAgain.tenantIsNull()).as("tenant B was not carried over").isTrue();
        assertThat(noneAgain.visibleTenants()).isZero();
    }

    /** Must be called inside a transaction, so all statements share one connection. */
    private Seen snapshot() {
        int pid = jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
        String setting = jdbc.sql("select coalesce(current_setting('app.tenant_id', true), '<unset>')")
                .query(String.class).single();
        boolean tenantIsNull = jdbc.sql("select platform.current_tenant() is null").query(Boolean.class).single();
        java.util.List<UUID> visible = jdbc.sql("select id from platform.tenant").query(UUID.class).list();
        return new Seen(pid, setting, tenantIsNull, visible.size(), visible.size() == 1 ? visible.get(0) : null);
    }

    @Test
    void alternatingRequestsOfAAndBNeverSeeEachOthersRows() throws Exception {
        for (int round = 0; round < 6; round++) {
            mvc.perform(get(PROBE + "/tenants").with(asUser(tenantA, userA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(tenantA.toString()));
            mvc.perform(get(PROBE + "/tenants").with(asUser(tenantB, userB)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(tenantB.toString()));
            mvc.perform(get(PROBE + "/tenants/" + tenantA).with(asUser(tenantB, userB)))
                    .andExpect(status().isNotFound());
            mvc.perform(get(PROBE + "/tenants/" + tenantB).with(asUser(tenantA, userA)))
                    .andExpect(status().isNotFound());
            // A signed-in user with no tenant, in the middle of the sequence, still reads nothing.
            mvc.perform(get(PROBE + "/tenants").with(asUserWithoutTenant()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }
}
