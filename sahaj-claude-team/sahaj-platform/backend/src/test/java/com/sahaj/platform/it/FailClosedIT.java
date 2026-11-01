package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.asUserWithoutTenant;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static com.sahaj.platform.support.TestTenantSecurity.signedInWithoutTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-1 criterion 8: with no tenant bound, a tenant table returns no rows and accepts no inserts
 * (fail closed). Runs as sahaj_app on sahaj_test, with rows that do exist for a real tenant.
 */
@OnTestDatabase
class FailClosedIT extends BaseIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID userA;
    private String nameA;

    @BeforeEach
    void aTenantWithData() throws Exception {
        tenantA = UUID.randomUUID();
        userA = UUID.randomUUID();
        nameA = uniqueName("Fail closed A");
        createTenant(tenantA, userA, nameA);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void aSignedInUserWithNoTenantListsNoTenantsEvenThoughRowsExist() throws Exception {
        mvc.perform(get(PROBE + "/tenants").with(asUserWithoutTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aSignedInUserWithNoTenantGets404ForAnExistingRowById() throws Exception {
        mvc.perform(get(PROBE + "/tenants/" + tenantA).with(asUserWithoutTenant()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void aSignedInUserWithNoTenantSeesNoAuditEvents() throws Exception {
        mvc.perform(get(PROBE + "/audit-events").with(asUserWithoutTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aSignedInUserWithNoTenantCannotInsertATenantRow() throws Exception {
        UUID wanted = UUID.randomUUID();
        MvcResult result = mvc.perform(post(PROBE + "/tenants")
                        .with(asUserWithoutTenant())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + wanted + "\",\"name\":\"Should not exist\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("insert refused").isGreaterThanOrEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(PROBLEM_JSON);

        // Nothing was created: the would-be owner of that id finds no row.
        mvc.perform(get(PROBE + "/tenants/" + wanted).with(asUser(wanted, UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void directQueriesWithNoTenantBoundReturnZeroRowsOnEveryTenantTable() {
        runAs(signedInWithoutTenant(), () -> tx().executeWithoutResult(s -> {
            assertThat(jdbc.sql("select count(*) from platform.tenant").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from platform.membership").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from platform.audit_event").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select platform.current_tenant() is null").query(Boolean.class).single()).isTrue();
        }));
    }

    @Test
    void directInsertsWithNoTenantBoundAreRefusedByRowLevelSecurity() {
        UUID wanted = UUID.randomUUID();
        assertThatThrownBy(() -> runAs(signedInWithoutTenant(), () -> tx().executeWithoutResult(s ->
                jdbc.sql("insert into platform.tenant (id, name) values (:id, 'No tenant bound')").param("id", wanted).update())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("row-level security");

        // The same with no security context at all.
        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                jdbc.sql("insert into platform.tenant (id, name) values (:id, 'No user at all')").param("id", UUID.randomUUID()).update()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    void aMembershipInsertWithNoTenantBoundIsRefused() {
        assertThatThrownBy(() -> runAs(signedInWithoutTenant(), () -> tx().executeWithoutResult(s ->
                jdbc.sql("insert into platform.membership (tenant_id, user_id, role) values (:t, :u, 'OWNER')")
                        .param("t", tenantA).param("u", UUID.randomUUID()).update())))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void anUpdateWithNoTenantBoundChangesNothing() throws Exception {
        int updated = runAs(signedInWithoutTenant(), () -> tx().execute(s ->
                jdbc.sql("update platform.tenant set name = 'Changed by nobody' where id = :id").param("id", tenantA).update()));
        assertThat(updated).isZero();
        mvc.perform(get(PROBE + "/tenants/" + tenantA).with(asUser(tenantA, userA)))
                .andExpect(jsonPath("$.name").value(nameA));
    }

    @Test
    void aDeleteWithNoTenantBoundIsRefused() {
        // sahaj_app has no DELETE privilege on tenant at all (a tenant is closed through status).
        assertThatThrownBy(() -> runAs(signedInWithoutTenant(), () -> tx().executeWithoutResult(s ->
                jdbc.sql("delete from platform.tenant where id = :id").param("id", tenantA).update())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("permission denied");
    }
}
