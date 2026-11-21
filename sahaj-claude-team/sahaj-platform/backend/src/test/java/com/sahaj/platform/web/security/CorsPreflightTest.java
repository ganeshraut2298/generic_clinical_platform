package com.sahaj.platform.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.web.HealthController;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BUG-emr-finish-no-call: the browser preflight from the Angular dev page must allow every request header
 * the app sends, including {@code Idempotency-Key} on EMR sign, and must still refuse other origins and
 * headers the app never sends. Uses the real {@link SecurityConfig} CORS source and filter chain.
 */
@WebMvcTest(controllers = HealthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "sahaj.cors.allowed-origins=http://localhost:4200,http://127.0.0.1:4200")
class CorsPreflightTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200";
    private static final String SIGN_PATH =
            "/api/v1/opd/emr/records/7f4c2a8e-1b3d-4e5f-9a6b-0c1d2e3f4a5b/sign";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightForSignFromTheAppOriginAllowsTheIdempotencyKey() throws Exception {
        MvcResult result = mockMvc.perform(options(SIGN_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type, idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andReturn();

        assertThat(allowedHeaders(result)).contains("authorization", "content-type", "idempotency-key");
        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("POST");
    }

    @Test
    void preflightFromTheSecondDevOriginIsAlsoAllowed() throws Exception {
        mockMvc.perform(options(SIGN_PATH)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:4200"));
    }

    @Test
    void preflightFromAnOriginNotConfiguredIsRefused() throws Exception {
        mockMvc.perform(options(SIGN_PATH)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type, idempotency-key"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @Test
    void headerNamesMatchWhateverTheirCase() throws Exception {
        MvcResult result = mockMvc.perform(options(SIGN_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "AUTHORIZATION, Content-Type, IDEMPOTENCY-KEY"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andReturn();

        assertThat(allowedHeaders(result)).contains("authorization", "content-type", "idempotency-key");
    }

    @Test
    void ipdDeviceHeaderStaysAllowed() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/ipd/admissions")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, x-sahaj-device, idempotency-key"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(allowedHeaders(result)).contains("x-sahaj-device", "idempotency-key");
    }

    @Test
    void aHeaderTheAppNeverSendsIsRefused() throws Exception {
        mockMvc.perform(options(SIGN_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-tenant-id"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void aHeaderTheAppNeverSendsIsLeftOutWhenAskedWithAllowedOnes() throws Exception {
        // Spring answers with only the allowed subset; the browser then blocks the call because x-tenant-id is missing.
        MvcResult result = mockMvc.perform(options(SIGN_PATH)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key, x-tenant-id"))
                .andReturn();

        assertThat(allowedHeaders(result)).contains("idempotency-key").doesNotContain("x-tenant-id", "*");
    }

    @Test
    void actualResponsesExposeRetryAfterToTheAppOrigin() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/platform/health").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("Retry-After");
    }

    private static List<String> allowedHeaders(MvcResult result) {
        String value = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
        assertThat(value).isNotNull();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }
}
