package com.sahaj.platform.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.application.SalesEnquiryService;
import com.sahaj.platform.web.security.SecurityConfig;
import com.sahaj.platform.support.FailureProbeController;
import com.sahaj.platform.support.TestTenantSecurity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Standard Spring MVC errors (bad path variable, bad or missing parameter, wrong content type,
 * wrong method, unknown path) must come back as Sahaj RFC 9457 problems that never carry a value the
 * caller submitted, not even in {@code detail}.
 */
@WebMvcTest(controllers = {HealthController.class, SalesEnquiryController.class})
@Import({SecurityConfig.class, FailureProbeController.class})
class ProblemDetailLeakTest {

    private static final String FAILURES = "/api/v1/platform/test-failures";
    private static final String SUBMITTED = "submitted-9876543210";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SalesEnquiryService service;

    @Test
    void aMalformedPathVariableGets400WithoutTheValueOrTheParameterName() throws Exception {
        ResultActions result = mockMvc.perform(get(FAILURES + "/by-id/" + SUBMITTED)
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"))
                .andExpect(jsonPath("$.title").value("Bad Request"));
        assertNoLeak(result);
    }

    @Test
    void aMalformedQueryParameterGets400WithoutTheValue() throws Exception {
        ResultActions result = mockMvc.perform(get(FAILURES + "/needs-count")
                        .param("count", SUBMITTED)
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"));
        assertNoLeak(result);
    }

    @Test
    void aMissingRequiredParameterGets400WithoutTheParameterName() throws Exception {
        ResultActions result = mockMvc.perform(get(FAILURES + "/needs-count")
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"));
        assertNoLeak(result);
        result.andExpect(content().string(not(containsString("count"))));
    }

    @Test
    void anUnsupportedMediaTypeGets415WithoutTheSubmittedContentType() throws Exception {
        ResultActions result = mockMvc.perform(post(FAILURES + "/json-only")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("text/" + SUBMITTED)
                        .content("{}")
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/unsupported-media-type"));
        assertNoLeak(result);
    }

    @Test
    void anUnreadableJsonBodyGets400WithoutTheText() throws Exception {
        ResultActions result = mockMvc.perform(post(FAILURES + "/json-only")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\": " + SUBMITTED + "}")
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertNoLeak(result);
    }

    @Test
    void aWrongMethodGets405WithoutTheMethodOrSupportedList() throws Exception {
        ResultActions result = mockMvc.perform(post(FAILURES + "/by-id/" + UUID.randomUUID())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/method-not-allowed"));
        assertNoLeak(result);
    }

    @Test
    void anUnknownPathGets404WithoutThePath() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/v1/platform/" + SUBMITTED)
                        .with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/not-found"));
        assertNoLeak(result);
    }

    @Test
    void aProblemFromTheOwnHandlersAlsoCarriesNoInstancePath() throws Exception {
        mockMvc.perform(get(FAILURES + "/missing").with(TestTenantSecurity.asUser(TENANT, USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    private static void assertNoLeak(ResultActions result) throws Exception {
        result.andExpect(content().string(not(containsString(SUBMITTED))))
                .andExpect(content().string(not(containsString("Failed to convert"))))
                .andExpect(content().string(not(containsString("MethodArgumentTypeMismatch"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("java.lang"))));
    }
}
