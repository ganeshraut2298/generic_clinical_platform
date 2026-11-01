package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.asUserWithoutTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SAH-1 criterion 2 (a user of tenant B asking for tenant A's row by id gets 404 and no row leaks)
 * and criterion 10 (a tenant id in the body, a header or the query changes nothing). The app runs as
 * sahaj_app on sahaj_test, so PostgreSQL row level security is what is being tested.
 */
@OnTestDatabase
class TenantIsolationIT extends BaseIntegrationTest {

    private UUID tenantA;
    private UUID tenantB;
    private UUID userA;
    private UUID userB;
    private String nameA;
    private String nameB;

    @BeforeEach
    void twoTenants() throws Exception {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        nameA = uniqueName("Clinic A");
        nameB = uniqueName("Clinic B");
        createTenant(tenantA, userA, nameA);
        createTenant(tenantB, userB, nameB);
    }

    private MockHttpServletRequestBuilder getTenantAsB(UUID id) {
        return get(PROBE + "/tenants/" + id).with(asUser(tenantB, userB));
    }

    @Test
    void ownerOfATenantReadsItsOwnRow() throws Exception {
        mvc.perform(get(PROBE + "/tenants/" + tenantA).with(asUser(tenantA, userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantA.toString()))
                .andExpect(jsonPath("$.name").value(nameA));
    }

    @Test
    void aUserOfTenantBGets404ForTenantAById() throws Exception {
        mvc.perform(getTenantAsB(tenantA))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "not-found"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(content().string(not(containsString(nameA))));
    }

    @Test
    void theNotFoundAnswerForAnotherTenantIsIdenticalToTheAnswerForAnIdThatDoesNotExist() throws Exception {
        MvcResult existsInA = mvc.perform(getTenantAsB(tenantA)).andReturn();
        MvcResult doesNotExist = mvc.perform(getTenantAsB(UUID.randomUUID())).andReturn();
        assertThat(existsInA.getResponse().getStatus()).isEqualTo(doesNotExist.getResponse().getStatus()).isEqualTo(404);
        assertThat(existsInA.getResponse().getContentType()).isEqualTo(doesNotExist.getResponse().getContentType());
        // Same title and type: a caller cannot tell "belongs to someone else" from "does not exist".
        assertThat(existsInA.getResponse().getContentAsString())
                .isEqualTo(doesNotExist.getResponse().getContentAsString());
    }

    @Test
    void sendingTheTenantIdOfAInAHeaderDoesNotLetBReadAsRow() throws Exception {
        mvc.perform(getTenantAsB(tenantA).header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(content().string(not(containsString(nameA))));
    }

    @Test
    void sendingTheTenantIdOfAInTheQueryDoesNotLetBReadAsRow() throws Exception {
        mvc.perform(getTenantAsB(tenantA).queryParam("tenantId", tenantA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(content().string(not(containsString(nameA))));
    }

    @Test
    void sendingTheTenantIdOfAInHeaderAndQueryTogetherStillGives404() throws Exception {
        mvc.perform(getTenantAsB(tenantA)
                        .header("X-Tenant-Id", tenantA.toString())
                        .queryParam("tenantId", tenantA.toString())
                        .queryParam("tenant_id", tenantA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void theListOfBShowsOnlyBEvenWhenTheClientNamesTenantA() throws Exception {
        mvc.perform(get(PROBE + "/tenants").with(asUser(tenantB, userB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(tenantB.toString()))
                .andExpect(content().string(not(containsString(nameA))));

        mvc.perform(get(PROBE + "/tenants").with(asUser(tenantB, userB))
                        .header("X-Tenant-Id", tenantA.toString())
                        .queryParam("tenantId", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(tenantB.toString()));
    }

    @Test
    void aUserOfBCannotWriteTenantAsRowEvenByUsingItsId() throws Exception {
        MvcResult result = mvc.perform(post(PROBE + "/tenants")
                        .with(asUser(tenantB, userB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + tenantA + "\",\"name\":\"Taken over\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("the insert is refused").isGreaterThanOrEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(PROBLEM_JSON);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(nameA).doesNotContain("Taken over");

        // A's row is unchanged.
        mvc.perform(get(PROBE + "/tenants/" + tenantA).with(asUser(tenantA, userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(nameA));
    }

    @Test
    void aRequestWithoutLoginGets401AsProblemJson() throws Exception {
        mvc.perform(get(PROBE + "/tenants/" + tenantA).header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    // ---- criterion 10: the tenant comes only from the logged-in user ----

    @Test
    void tenantIdInBodyHeaderAndQueryIsIgnoredWhenCreatingARow() throws Exception {
        UUID tenantC = UUID.randomUUID();
        UUID userC = UUID.randomUUID();
        String nameC = uniqueName("Clinic C");

        mvc.perform(post(PROBE + "/tenants")
                        .with(asUser(tenantC, userC))
                        .header("X-Tenant-Id", tenantA.toString())
                        .queryParam("tenantId", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + tenantC + "\",\"name\":\"" + nameC + "\",\"tenantId\":\"" + tenantA + "\"}"))
                .andExpect(status().isOk());

        // The row belongs to C, the caller's own tenant ...
        mvc.perform(get(PROBE + "/tenants/" + tenantC).with(asUser(tenantC, userC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(nameC));
        // ... its audit event is in C's trail, not A's ...
        mvc.perform(get(PROBE + "/audit-events").with(asUser(tenantC, userC)))
                .andExpect(jsonPath("$[?(@.entityId == '" + tenantC + "')]").isNotEmpty());
        mvc.perform(get(PROBE + "/audit-events").with(asUser(tenantA, userA)))
                .andExpect(jsonPath("$[?(@.entityId == '" + tenantC + "')]").isEmpty());
        // ... and A still sees only itself.
        mvc.perform(get(PROBE + "/tenants").with(asUser(tenantA, userA)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(tenantA.toString()));
    }

    @Test
    void aSignedInUserWithNoTenantCannotChooseOneWithAHeaderOrQuery() throws Exception {
        mvc.perform(get(PROBE + "/tenants").with(asUserWithoutTenant())
                        .header("X-Tenant-Id", tenantA.toString())
                        .queryParam("tenantId", tenantA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(PROBE + "/tenants/" + tenantA).with(asUserWithoutTenant())
                        .header("X-Tenant-Id", tenantA.toString())
                        .queryParam("tenantId", tenantA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void aForgedTenantHeaderOnTheListDoesNotAddTenantsFromElsewhere() throws Exception {
        // Header names an unrelated tenant, the user is A: still exactly A.
        mvc.perform(get(PROBE + "/tenants").with(asUser(tenantA, userA))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(tenantA.toString()));
    }
}
