package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminAuthService;
import com.sahaj.platform.application.admin.AdminIdentity;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.support.LogCapture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Release audit security review #1 (DEC-025, SAH-35/36/38) and code review #1/#5: the admin security chain
 * ({@code /api/v1/admin/**}, ADR-003 D8) now runs the request log context filter, the access log filter and the request body
 * size limit, like the clinic chain.
 *
 * <ul>
 * <li>Every admin request is capped at 4 KB (the admin chain runs the limit before its token filter, so nobody is signed in
 * yet when it looks): 4,097 bytes answers 413 {@code payload-too-large}, 4,096 bytes is read.</li>
 * <li>Every admin request writes one {@code sahaj.access} line with the matched template path, never the raw path, and a
 * request refused before a handler (413, network refusal, 401) is {@code unmatched}.</li>
 * <li>An admin-authenticated call carries the admin id as {@code userId}; the clinic id is {@code -}; the id does not leak into
 * the next request.</li>
 * </ul>
 *
 * <p>The realm is switched on in this class's own context ({@code sahaj.admin.enabled=true} with the dummy test secrets of the
 * DEC-016 tests). The admin tables are a human prerequisite that is not applied to sahaj_test yet (db/pending/admin), so the
 * repository and the sign-in service are mocks: this class proves the filters of the chain, not the sign-in logic (that has its
 * own tests). Test plan: docs/test-plans/SAH-35-36-38-audit.md.
 */
@SpringBootTest(properties = {
        "it.context=admin-chain-log-and-limit",
        "sahaj.admin.enabled=true",
        "sahaj.admin.jwt-secret=" + AppRun.ADMIN_JWT_SECRET,
        "sahaj.admin.totp-key=" + AppRun.ADMIN_TOTP_KEY_BASE64,
        "sahaj.admin.allowed-networks=10.0.0.0/8"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminChainLogAndLimitIT {

    private static final String BASE = "/api/v1/admin";
    private static final String LOGIN = BASE + "/auth/login";
    private static final String ME = BASE + "/me";
    private static final String ACCESS_LOGGER = "sahaj.access";
    private static final String TOO_LARGE_TYPE = "https://sahaj.in/problems/payload-too-large";
    private static final String PAD_MARKER = "padpadpad";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LINE = Pattern.compile("^request method=(\\S+) path=(\\S+) status=(\\d+) durationMs=(\\d+) "
            + "tenantId=(\\S+) userId=(\\S+) requestId=(\\S+)$");
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    private record Line(String method, String path, int status, String tenantId, String userId, String requestId, String raw) {
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AdminTokenService adminTokens;

    @MockBean
    private AdminAuthService service;
    @MockBean
    private AdminAuthRepository repository;

    /** Every request comes from its own allowed address so the per-address admin limits never leak between tests. */
    private static MockHttpServletRequestBuilder fromNewAddress(MockHttpServletRequestBuilder builder) {
        int n = NEXT_ADDRESS.getAndIncrement();
        return builder.with(request -> {
            request.setRemoteAddr("10.66." + (n / 250) + "." + (n % 250 + 1));
            return request;
        });
    }

    private static byte[] jsonOfSize(int size) {
        String head = "{\"pad\":\"";
        String tail = "\"}";
        StringBuilder pad = new StringBuilder();
        while (pad.length() < size - head.length() - tail.length()) {
            pad.append(PAD_MARKER);
        }
        pad.setLength(size - head.length() - tail.length());
        byte[] bytes = (head + pad + tail).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isEqualTo(size);
        return bytes;
    }

    private static Line theOnlyLine(LogCapture logs) {
        List<ILoggingEvent> events = logs.events().stream().filter(e -> ACCESS_LOGGER.equals(e.getLoggerName())).toList();
        assertThat(events).as("exactly one sahaj.access line per admin request").hasSize(1);
        String text = events.get(0).getFormattedMessage();
        Matcher m = LINE.matcher(text);
        assertThat(m.matches()).as("shape of the line: " + text).isTrue();
        return new Line(m.group(1), m.group(2), Integer.parseInt(m.group(3)), m.group(5), m.group(6), m.group(7), text);
    }

    private String bearer(UUID adminId, UUID sessionId) {
        return "Bearer " + adminTokens.issue(adminId, sessionId, List.of("pwd", "otp")).token();
    }

    private void assertTooLarge(MvcResult result, String label) throws Exception {
        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(413);
        assertThat(result.getResponse().getContentType()).as(label).startsWith("application/problem+json");
        JsonNode p = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(p.get("type").asText()).isEqualTo(TOO_LARGE_TYPE);
        assertThat(p.get("detail").asText()).isEqualTo("The request body must be 4 KB or smaller.");
        assertThat(result.getResponse().getContentAsString()).as("the body is never echoed").doesNotContain(PAD_MARKER);
        assertThat(result.getResponse().getHeader("X-Request-Id")).as(label + ": request id").isNotBlank();
    }

    // ---------------- the 4 KB cap ----------------

    @Test
    void aSignInBodyOf4097BytesIsRefusedWith413AndABodyOf4096And4095BytesIsReadByTheEndpoint() throws Exception {
        assertTooLarge(mvc.perform(fromNewAddress(post(LOGIN)).contentType(MediaType.APPLICATION_JSON).content(jsonOfSize(4_097)))
                .andReturn(), "login, 4,097 bytes");
        assertTooLarge(mvc.perform(fromNewAddress(post(LOGIN)).contentType(MediaType.APPLICATION_JSON).content(jsonOfSize(64 * 1024 + 1)))
                .andReturn(), "login, 65,537 bytes");
        for (int size : new int[] {4_096, 4_095}) {
            MvcResult r = mvc.perform(fromNewAddress(post(LOGIN)).contentType(MediaType.APPLICATION_JSON).content(jsonOfSize(size)))
                    .andReturn();
            // the padded body has an unknown field: the endpoint's own strict-JSON 400, which proves it was read
            assertThat(r.getResponse().getStatus()).as("login, " + size + " bytes").isEqualTo(400);
            assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("type").asText())
                    .isEqualTo("https://sahaj.in/problems/malformed-request");
        }
    }

    @Test
    void everyOtherPublicSignInEndpointHasTheSameCap() throws Exception {
        for (String path : new String[] {"/auth/totp/enrol", "/auth/totp/verify", "/auth/refresh"}) {
            assertTooLarge(mvc.perform(fromNewAddress(post(BASE + path)).contentType(MediaType.APPLICATION_JSON)
                    .content(jsonOfSize(5_000))).andReturn(), path + ", 5,000 bytes");
        }
    }

    @Test
    void anAdminWithALiveSessionHasTheSame4KbCapOnASignedInAdminEndpoint() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(repository.sessionTouch(sessionId, adminId)).thenReturn(true);
        // the limit runs ahead of the admin token filter, so a signed-in admin is capped at 4 KB too (documented behaviour:
        // no admin request body in docs/api/admin.yaml comes near it)
        assertTooLarge(mvc.perform(fromNewAddress(post(BASE + "/auth/sign-out")).header("Authorization", bearer(adminId, sessionId))
                .contentType(MediaType.APPLICATION_JSON).content(jsonOfSize(5_000))).andReturn(), "sign-out, 5,000 bytes");
    }

    // ---------------- the access line ----------------

    @Test
    void anAdminRequestWritesOneAccessLineWithTheTemplatePathAndNoTenantOrUserWhenNobodyIsSignedIn() throws Exception {
        MvcResult result;
        Line line;
        try (LogCapture logs = new LogCapture()) {
            result = mvc.perform(fromNewAddress(post(LOGIN)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"not-an-email-secret-xyz\",\"password\":\"hunter2-secret\"}")).andReturn();
            line = theOnlyLine(logs);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(line.method()).isEqualTo("POST");
        assertThat(line.path()).isEqualTo(LOGIN);
        assertThat(line.status()).isEqualTo(400);
        assertThat(line.tenantId()).isEqualTo("-");
        assertThat(line.userId()).isEqualTo("-");
        assertThat(line.requestId()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        assertThat(UUID.fromString(line.requestId())).isNotNull();
        assertThat(line.raw()).doesNotContain("not-an-email").doesNotContain("hunter2");
    }

    @Test
    void anAdminCallWithoutATokenIsLoggedWith401AndTheTemplatePathNeverTheCallersText() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(fromNewAddress(get(ME))).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(line.status()).isEqualTo(401);
            assertThat(line.userId()).isEqualTo("-");
            assertThat(line.path()).isIn(ME, "unmatched");
        }
        try (LogCapture logs = new LogCapture()) {
            mvc.perform(fromNewAddress(get(BASE + "/no-such-thing/Asha-Rane-9000000001"))).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(line.path()).isEqualTo("unmatched");
            assertThat(line.raw()).doesNotContain("Asha").doesNotContain("9000000001").doesNotContain("no-such-thing");
        }
    }

    @Test
    void anAdminAuthenticatedCallCarriesTheAdminIdAsUserIdAndTheTemplatePath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(repository.sessionTouch(sessionId, adminId)).thenReturn(true);
        when(service.me()).thenReturn(new AdminIdentity(adminId, "Dev admin"));
        MvcResult result;
        Line line;
        try (LogCapture logs = new LogCapture()) {
            result = mvc.perform(fromNewAddress(get(ME)).header("Authorization", bearer(adminId, sessionId))).andReturn();
            line = theOnlyLine(logs);
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(line.method()).isEqualTo("GET");
        assertThat(line.path()).isEqualTo(ME);
        assertThat(line.status()).isEqualTo(200);
        assertThat(line.userId()).as("the admin id from the token").isEqualTo(adminId.toString());
        assertThat(line.tenantId()).as("an admin has no clinic").isEqualTo("-");
        assertThat(line.requestId()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        assertThat(line.raw()).doesNotContain("Dev admin").doesNotContain("Bearer").doesNotContain(sessionId.toString());

        // the id stays with its own request: the next anonymous admin request on the same thread logs "-"
        try (LogCapture logs = new LogCapture()) {
            mvc.perform(fromNewAddress(get(ME))).andReturn();
            assertThat(theOnlyLine(logs).userId()).isEqualTo("-");
        }
    }

    @Test
    void anAdminTokenWhoseSessionHasEndedIsLoggedAs401WithoutTheAdminId() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(repository.sessionTouch(sessionId, adminId)).thenReturn(false);
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(fromNewAddress(get(ME)).header("Authorization", bearer(adminId, sessionId))).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(line.status()).isEqualTo(401);
            assertThat(line.userId()).as("a dead session is not a signed-in admin").isEqualTo("-");
            assertThat(line.raw()).doesNotContain(adminId.toString());
        }
    }

    @Test
    void aRequestFromOutsideTheAllowedNetworksIsRefusedAndStillLogged() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}").with(request -> {
                request.setRemoteAddr("203.0.113.9");
                return request;
            })).andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            assertThat(line.status()).isEqualTo(403);
            assertThat(line.userId()).isEqualTo("-");
            assertThat(line.requestId()).isEqualTo(r.getResponse().getHeader("X-Request-Id"));
        }
    }

    @Test
    void a413OnTheAdminChainIsLoggedAsUnmatchedWithNoBodyText() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            MvcResult r = mvc.perform(fromNewAddress(post(LOGIN)).contentType(MediaType.APPLICATION_JSON).content(jsonOfSize(5_000)))
                    .andReturn();
            Line line = theOnlyLine(logs);
            assertThat(r.getResponse().getStatus()).isEqualTo(413);
            assertThat(line.status()).isEqualTo(413);
            assertThat(line.path()).as("refused before any handler").isEqualTo("unmatched");
            assertThat(line.requestId()).isEqualTo(r.getResponse().getHeader("X-Request-Id"));
            assertThat(line.raw()).doesNotContain(PAD_MARKER);
        }
    }
}
