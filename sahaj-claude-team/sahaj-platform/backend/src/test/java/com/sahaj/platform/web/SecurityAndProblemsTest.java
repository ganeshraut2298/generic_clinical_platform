package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.web.security.SecurityConfig;
import com.sahaj.platform.support.FailureProbeController;
import com.sahaj.platform.support.LogCapture;
import com.sahaj.platform.support.TestTenantSecurity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.sahaj.platform.application.SalesEnquiryService;

/** Health endpoint, deny-by-default security and RFC 9457 errors (SAH-1 criteria 6, 7 and 10). */
@WebMvcTest(controllers = {HealthController.class, SalesEnquiryController.class})
@Import({SecurityConfig.class, FailureProbeController.class})
class SecurityAndProblemsTest {

    private static final String BASE = "/api/v1/platform";
    private static final String FAILURES = BASE + "/test-failures";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SalesEnquiryService service;

    @Test
    void healthIsOpenAndReportsUp() throws Exception {
        mockMvc.perform(get(BASE + "/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void healthIgnoresATenantIdSentByTheClient() throws Exception {
        mockMvc.perform(get(BASE + "/health?tenantId=" + UUID.randomUUID()).header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void everyPathOutsideHealthAndPublicNeedsALogin() throws Exception {
        for (String path : new String[] {BASE + "/me", BASE + "/team", BASE + "/setup", BASE + "/auth/logout",
                FAILURES + "/open-to-any-signed-in-user", "/api/v1/opd/anything", "/actuator/health", "/"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/unauthorized"));
        }
    }

    @Test
    void healthIsOpenForGetOnly() throws Exception {
        mockMvc.perform(post(BASE + "/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantIdInTheRequestDoesNotLogAnyoneIn() throws Exception {
        mockMvc.perform(get(FAILURES + "/open-to-any-signed-in-user")
                        .header("X-Tenant-Id", TENANT.toString())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInUserReachesAProtectedPath() throws Exception {
        mockMvc.perform(get(FAILURES + "/open-to-any-signed-in-user").with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isOk());
    }

    @Test
    void aSignedInUserWithoutThePermissionGets403AsAProblem() throws Exception {
        mockMvc.perform(get(FAILURES + "/admin-only").with(TestTenantSecurity.asUser(TENANT, USER, "FRONT_DESK")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/forbidden"));
    }

    @Test
    void aSignedInUserWithThePermissionIsLetThrough() throws Exception {
        mockMvc.perform(get(FAILURES + "/admin-only").with(TestTenantSecurity.asUser(TENANT, USER, "OWNER")))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownPathAfterLoginIs404AsAProblem() throws Exception {
        mockMvc.perform(get(BASE + "/does-not-exist").with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-found"));
    }

    @Test
    void aNotFoundExceptionBecomesA404ProblemThatDoesNotEchoTheMessage() throws Exception {
        mockMvc.perform(get(FAILURES + "/missing").with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-found"))
                .andExpect(content().string(not(containsString(FailureProbeController.SECRET_IN_MESSAGE))));
    }

    @Test
    void anUnexpectedErrorBecomes500WithoutTheCauseInTheResponseOrTheLog() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            mockMvc.perform(get(FAILURES + "/boom").with(TestTenantSecurity.asUser(TENANT, USER)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/internal-error"))
                    .andExpect(content().string(not(containsString(FailureProbeController.SECRET_IN_MESSAGE))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))))
                    .andExpect(content().string(not(containsString("at com.sahaj"))));

            assertThat(logs.allText()).doesNotContain(FailureProbeController.SECRET_IN_MESSAGE);
            assertThat(logs.allText()).contains("IllegalStateException");
        }
    }

    @Test
    void aCustomTypedProblemKeepsItsOwnTypeTitleAndDetail() throws Exception {
        mockMvc.perform(get(FAILURES + "/plan-limit").with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/plan-limit"))
                .andExpect(jsonPath("$.title").value("Plan limit reached"))
                .andExpect(jsonPath("$.detail").value("Upgrade your plan to add more users."));
    }

    @Test
    void theLogContextCarriesTheRequestIdHeader() throws Exception {
        mockMvc.perform(get(BASE + "/health"))
                .andExpect(header().exists("X-Request-Id"));
        mockMvc.perform(get(BASE + "/me"))
                .andExpect(header().exists("X-Request-Id"));
    }
}
