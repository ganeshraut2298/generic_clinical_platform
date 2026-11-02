package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.support.FailureProbeController;
import com.sahaj.platform.support.LogCapture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * SAH-1 criteria 6 and 7 on the full stack (real database behind the context): the public health
 * endpoint, deny by default, 403 for a role without permission, and RFC 9457 problem bodies for every
 * kind of error, none of which leaks a value.
 */
@OnTestDatabase
class ErrorsAndRolesIT extends BaseIntegrationTest {

    private static final String BASE = "/api/v1/platform";
    private static final String FAILURES = BASE + "/test-failures";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    // ---- criterion 6 ----

    @Test
    void healthIsPublicAndReportsUp() throws Exception {
        mvc.perform(get(BASE + "/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void healthStaysPublicEvenWithAnUnknownBearerToken() throws Exception {
        mvc.perform(get(BASE + "/health").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ---- deny by default and roles ----

    @Test
    void aProtectedPathWithoutLoginGets401AsProblemJson() throws Exception {
        mvc.perform(get(FAILURES + "/open-to-any-signed-in-user"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "unauthorized"));
    }

    @Test
    void anUnknownPathWithoutLoginAlsoGets401SoNothingIsDiscoverable() throws Exception {
        mvc.perform(get(BASE + "/no-such-thing"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void healthDoesNotAcceptWritesWithoutLogin() throws Exception {
        mvc.perform(post(BASE + "/health").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        mvc.perform(delete(BASE + "/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRoleWithoutPermissionGets403AsProblemJson() throws Exception {
        mvc.perform(get(FAILURES + "/admin-only").with(asUser(TENANT, USER, "FRONT_DESK")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "forbidden"));
        mvc.perform(get(FAILURES + "/admin-only").with(asUser(TENANT, USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRoleWithPermissionGets200() throws Exception {
        mvc.perform(get(FAILURES + "/admin-only").with(asUser(TENANT, USER, "OWNER")))
                .andExpect(status().isOk());
    }

    // ---- criterion 7: every error is problem+json and leaks nothing ----

    @Test
    void anUnknownPathForASignedInUserGets404AsProblemJson() throws Exception {
        mvc.perform(get(BASE + "/does-not-exist").with(asUser(TENANT, USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aWrongMethodGets405AsProblemJson() throws Exception {
        mvc.perform(post(BASE + "/health").with(asUser(TENANT, USER)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void anUnexpectedExceptionGets500AsProblemJsonWithNoStackTraceAndNoMessage() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            mvc.perform(get(FAILURES + "/boom").with(asUser(TENANT, USER)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(content().string(not(containsString(FailureProbeController.SECRET_IN_MESSAGE))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))))
                    .andExpect(content().string(not(containsString("at com.sahaj"))));
            assertThat(logs.allText()).doesNotContain(FailureProbeController.SECRET_IN_MESSAGE);
        }
    }

    @Test
    void aNotFoundExceptionNeverEchoesItsMessage() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            mvc.perform(get(FAILURES + "/missing").with(asUser(TENANT, USER)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(content().string(not(containsString(FailureProbeController.SECRET_IN_MESSAGE))));
            assertThat(logs.allText()).doesNotContain(FailureProbeController.SECRET_IN_MESSAGE);
        }
    }

    @Test
    void aMalformedIdInThePathGets400AsProblemJsonThatDoesNotEchoTheSubmittedValue() throws Exception {
        mvc.perform(get(PROBE + "/tenants/not-a-uuid-9876").with(asUser(TENANT, USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(content().string(not(containsString("not-a-uuid-9876"))))
                .andExpect(content().string(not(containsString("MethodArgumentTypeMismatch"))));
    }
}
