package com.sahaj.platform.devtools.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.web.admin.AdminDevPathFilter;
import com.sahaj.platform.web.admin.DevEndpointGate;
import com.sahaj.platform.web.security.SecurityConfig;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The endpoint through the real reserved-path filter, the real gate and the real dev security chain (ADR-008
 * D1, D3, D4, D6, D7). The service is a mock: the database function is covered by the repository test and by
 * the integration tests that need the admin tables (T6 to T11, T14).
 */
@WebMvcTest(controllers = DevSampleAdminController.class, properties = {
        "sahaj.admin.enabled=true",
        "sahaj.admin.jwt-secret=an-admin-secret-with-more-than-32-chars",
        "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==", "server.address=127.0.0.1"})
@ActiveProfiles("dev")
@ContextConfiguration(initializers = DevSampleAdminEndpointTest.CommandLineFlag.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Import({SecurityConfig.class, DevSampleAdminSecurityConfig.class, DevSampleAdminEndpointTest.Wiring.class})
class DevSampleAdminEndpointTest {

    private static final String PATH = "/api/v1/admin/dev/sample-admin";
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PASSWORD = "Abcdefgh23456789JKLMNPQR";
    private static final String SECRET_KEY = "JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP";
    private static final String URI = "otpauth://totp/Sahaj%20admin:sample-admin%40example.test?secret="
            + "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Sahaj%20admin&algorithm=SHA1&digits=6&period=30";

    /** The nonce of this test context: sixteen 0xab bytes, so thirty-two times "ab". */
    private static final String NONCE = "ab".repeat(16);
    private static final String NONCE_HEADER = "X-Sahaj-Dev-Nonce";

    /**
     * C2 is read from the command line only (ADR-008 R2): the flag goes in as a real program argument, which Spring
     * Boot turns into the property source {@code commandLineArgs}.
     */
    static class CommandLineFlag implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new SimpleCommandLinePropertySource("--sahaj.admin.dev-sample-enabled=true"));
        }
    }

    /** Main code's filter in front of Spring Security, as the application registers it, plus the real gate. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        DevSampleAdminNonce nonce() {
            return new DevSampleAdminNonce(new SecureRandom() {
                private static final long serialVersionUID = 1L;

                @Override
                public void nextBytes(byte[] bytes) {
                    Arrays.fill(bytes, (byte) 0xab);
                }
            });
        }

        @Bean
        DevSampleAdminGate gate(DevSampleAdminNonce nonce) {
            return new DevSampleAdminGate(nonce);
        }

        @Bean
        FilterRegistrationBean<AdminDevPathFilter> devPathFilter(ObjectProvider<DevEndpointGate> gate) {
            FilterRegistrationBean<AdminDevPathFilter> registration =
                    new FilterRegistrationBean<>(new AdminDevPathFilter(gate));
            registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
            return registration;
        }

        @Bean
        AdminNetworkPolicy adminNetworkPolicy() {
            return new AdminNetworkPolicy("", true);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private DevSampleAdminService service;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    private Level rootLevel;

    @BeforeEach
    void setUp() {
        rootLevel = root.getLevel();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
    }

    /** Without this every later test in the same JVM would log at DEBUG into a list that is never freed. */
    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        logs.stop();
        root.setLevel(rootLevel);
    }

    private static DevSampleAdminResponse created() {
        return new DevSampleAdminResponse(ADMIN_ID, "sample-admin@example.test", "Sample admin", PASSWORD, SECRET_KEY,
                URI, "sample-admin@example.test", "Sahaj admin", 6, 30, "SHA1", true, true, "/admin/sign-in");
    }

    /** What curl sends from the developer's machine: loopback, a local Host and the nonce of this start. */
    private static MockHttpServletRequestBuilder curl() {
        return post(PATH).with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        }).header("Host", "127.0.0.1:8080").header(NONCE_HEADER, NONCE);
    }

    private static MockHttpServletRequestBuilder withNonce(MockHttpServletRequestBuilder builder) {
        return builder.header(NONCE_HEADER, NONCE);
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String address) {
        return builder.with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    @Test
    void theHappyPathReturnsTheContractBodyOnceWithNoStoreHeaders() throws Exception {
        when(service.create(eq("127.0.0.1"), any())).thenReturn(created());

        mockMvc.perform(curl())
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.adminId").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.email").value("sample-admin@example.test"))
                .andExpect(jsonPath("$.fullName").value("Sample admin"))
                .andExpect(jsonPath("$.password").value(PASSWORD))
                .andExpect(jsonPath("$.secretKey").value(SECRET_KEY))
                .andExpect(jsonPath("$.otpauthUri").value(URI))
                .andExpect(jsonPath("$.accountName").value("sample-admin@example.test"))
                .andExpect(jsonPath("$.issuer").value("Sahaj admin"))
                .andExpect(jsonPath("$.digits").value(6))
                .andExpect(jsonPath("$.periodSeconds").value(30))
                .andExpect(jsonPath("$.algorithm").value("SHA1"))
                .andExpect(jsonPath("$.totpEnrolled").value(true))
                .andExpect(jsonPath("$.totpDevBypassOn").value(true))
                .andExpect(jsonPath("$.signInPath").value("/admin/sign-in"))
                .andExpect(jsonPath("$.length()").value(14));
    }

    @Test
    void ipv6LoopbackAndLocalhostHostWork() throws Exception {
        when(service.create(any(), any())).thenReturn(created());

        mockMvc.perform(from(withNonce(post(PATH).header("Host", "[::1]:8080")), "0:0:0:0:0:0:0:1"))
                .andExpect(status().isCreated());
    }

    @Test
    void aTokenOrCookieSentWithTheRequestIsIgnored() throws Exception {
        when(service.create(any(), any())).thenReturn(created());

        mockMvc.perform(curl().header("Authorization", "Bearer not-a-token")
                        .cookie(new jakarta.servlet.http.Cookie("sahaj_admin_rt", "x")))
                .andExpect(status().isCreated());
    }

    @Test
    void anAdminThatAlreadyExistsAnswersTheSameBytesAsTheFilter404() throws Exception {
        when(service.create(any(), any())).thenReturn(null);

        MockHttpServletResponse fromController = perform(curl());
        MockHttpServletResponse fromFilter = perform(from(curl(), "10.0.0.5"));

        assertThat(fromController.getStatus()).isEqualTo(404);
        assertThat(fromFilter.getStatus()).isEqualTo(404);
        assertThat(fromController.getContentAsByteArray()).isEmpty();
        assertThat(fromController.getContentType()).isNull();
        assertThat(headersOf(fromController)).isEqualTo(headersOf(fromFilter)).containsExactly("Cache-Control=[no-store]");
        assertThat(fromController.getContentAsByteArray()).isEqualTo(fromFilter.getContentAsByteArray());
    }

    @Test
    void aUniqueViolationAnswersTheSame404() throws Exception {
        when(service.create(any(), any())).thenThrow(new DevSampleAdminUnavailableException());

        MockHttpServletResponse response = perform(curl());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(headersOf(response)).containsExactly("Cache-Control=[no-store]");
    }

    @Test
    void anyOtherFailureIsABodylessFiveHundredThatLogsTheTypeOnly() throws Exception {
        when(service.create(any(), any())).thenThrow(new IllegalStateException(
                "Detail: Key (email)=(sample-admin@example.test) " + PASSWORD + " " + SECRET_KEY));

        MockHttpServletResponse response = perform(curl());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String log = loggedText();
        assertThat(log).contains("DevSampleAdminFailedException").doesNotContain("sample-admin@example.test")
                .doesNotContain(PASSWORD).doesNotContain(SECRET_KEY);
    }

    @Test
    void aFailureWithTheFailingRowInItsMessageAndCauseReachesSpringsResolversAsAMessageFreeCauseLessException()
            throws Exception {
        // The driver's message and cause chain hold the row (email, hash, ciphertext); at DEBUG Spring prints
        // "Resolved [exception]" for the exception it resolves: only the wrapper may arrive there (ADR-008 R4).
        String row = "Failing row contains (sample-admin@example.test, $2a$12$abcdefghijklmnopqrstuv, \\x00ff)";
        when(service.create(any(), any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(row,
                new java.sql.SQLException(row, "23514")));

        MvcResult result = mockMvc.perform(curl()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        Exception resolved = result.getResolvedException();
        assertThat(resolved).isInstanceOf(DevSampleAdminFailedException.class);
        assertThat(resolved.getMessage()).isNull();
        assertThat(resolved.getCause()).isNull();
        assertThat(resolved.getSuppressed()).isEmpty();
        assertThat(resolved.toString()).isEqualTo("DevSampleAdminFailedException[sqlState=23514]");
        String log = loggedText();
        assertThat(log).contains("sqlState=23514").doesNotContain("sample-admin@example.test")
                .doesNotContain("Failing row").doesNotContain("$2a$12$").doesNotContain("\\x00ff");
    }

    @Test
    void aFailureThatIsAlreadyOurWrapperIsAnsweredAsIs() throws Exception {
        when(service.create(any(), any())).thenThrow(new DevSampleAdminFailedException("08006"));

        MvcResult result = mockMvc.perform(curl()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(loggedText()).contains("sqlState=08006");
    }

    @Test
    void everyRefusedCallerAnswersTheSame404AndNeverReachesTheService() throws Exception {
        List<MockHttpServletRequestBuilder> refused = List.of(
                from(curl(), "10.0.0.5"), from(curl(), "192.168.1.20"), from(curl(), "2001:db8::1"),
                from(curl(), "::ffff:10.0.0.5"),
                from(curl().header("X-Forwarded-For", "127.0.0.1"), "10.0.0.5"),
                from(curl().header("Forwarded", "for=127.0.0.1"), "10.0.0.5"),
                from(curl().header("X-Real-IP", "127.0.0.1"), "10.0.0.5"),
                curl().header("Via", "1.1 proxy"),
                curl().header("X-Forwarded-Server", "proxy"),
                curl().header("X-Original-Forwarded-For", "203.0.113.9"),
                curl().header("X-Client-IP", "203.0.113.9"),
                curl().header("Client-IP", "203.0.113.9"),
                curl().header("True-Client-IP", "203.0.113.9"),
                curl().header("CF-Connecting-IP", "203.0.113.9"),
                curl().header("Fastly-Client-IP", "203.0.113.9"),
                curl().header("X-Cluster-Client-IP", "203.0.113.9"),
                curl().header("Origin", "http://localhost:4200"),
                curl().header("Sec-Fetch-Site", "same-origin"),
                curl().header("Sec-Fetch-Mode", "cors"),
                withNonce(post(PATH).header("Host", "evil.example:8080")),
                withNonce(get(PATH).header("Host", "127.0.0.1")),
                withNonce(options(PATH).header("Host", "127.0.0.1").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")),
                withNonce(post(PATH + "/").header("Host", "127.0.0.1")),
                withNonce(post(PATH.toUpperCase()).header("Host", "127.0.0.1")),
                withNonce(post("/api/v1/admin/dev").header("Host", "127.0.0.1")),
                withNonce(post("/api/v1/admin/dev/x").header("Host", "127.0.0.1")));
        MockHttpServletResponse reference = perform(from(curl(), "10.0.0.5"));
        for (MockHttpServletRequestBuilder request : refused) {
            MvcResult result = mockMvc.perform(request).andReturn();
            MockHttpServletResponse response = result.getResponse();
            String label = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " from "
                    + result.getRequest().getRemoteAddr();
            assertThat(response.getStatus()).as(label).isEqualTo(404);
            assertThat(response.getContentAsByteArray()).as(label).isEmpty();
            assertThat(headersOf(response)).as(label).isEqualTo(headersOf(reference));
        }
        verify(service, never()).create(any(), any());
    }

    // ---- G7, the nonce (ADR-008 R1): the same empty 404 from the same place, and never counted ----

    private static MockHttpServletRequestBuilder curlWithNonceValues(String... values) {
        MockHttpServletRequestBuilder builder = post(PATH).with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        }).header("Host", "127.0.0.1:8080");
        for (String value : values) {
            builder = builder.header(NONCE_HEADER, value);
        }
        return builder;
    }

    @Test
    void aMissingEmptyMalformedOrWrongNonceIsTheSame404AsEveryOtherRefusalAndNeverReachesTheService()
            throws Exception {
        MockHttpServletResponse reference = perform(from(curl(), "10.0.0.5"));
        List<MockHttpServletRequestBuilder> refused = List.of(
                curlWithNonceValues(),
                curlWithNonceValues(""),
                curlWithNonceValues(" "),
                curlWithNonceValues(NONCE.substring(1)),
                curlWithNonceValues(NONCE + "a"),
                curlWithNonceValues("00".repeat(16)),
                curlWithNonceValues(NONCE.toUpperCase()),
                curlWithNonceValues("Ab".repeat(16)),
                curlWithNonceValues(NONCE, NONCE),
                curlWithNonceValues(NONCE, "00".repeat(16)),
                curlWithNonceValues("00".repeat(16), NONCE),
                curlWithNonceValues("ab".repeat(15) + "a\u00e9"),
                curlWithNonceValues("\u0661\u0662".repeat(16)),
                curlWithNonceValues("0x" + NONCE.substring(2)),
                curlWithNonceValues("Bearer " + NONCE));
        for (MockHttpServletRequestBuilder request : refused) {
            MvcResult result = mockMvc.perform(request).andReturn();
            MockHttpServletResponse response = result.getResponse();
            String label = "nonce headers " + Collections.list(result.getRequest().getHeaders(NONCE_HEADER));
            assertThat(response.getStatus()).as(label).isEqualTo(404);
            assertThat(response.getContentAsByteArray()).as(label).isEmpty();
            assertThat(response.getContentType()).as(label).isNull();
            assertThat(headersOf(response)).as(label).isEqualTo(headersOf(reference));
        }
        verify(service, never()).create(any(), any());
    }

    @Test
    void aWhitespacePaddedNonceIsNotTrimmedByTheGate() throws Exception {
        assertThat(perform(curlWithNonceValues(NONCE + " ")).getStatus()).isEqualTo(404);
        assertThat(perform(curlWithNonceValues("\t" + NONCE)).getStatus()).isEqualTo(404);
    }

    @Test
    void fiftyWrongNonceCallsAreNotCountedAndTheCorrectOneThenAnswers201() throws Exception {
        when(service.create(any(), any())).thenReturn(created());
        for (int i = 0; i < 50; i++) {
            assertThat(perform(curlWithNonceValues(String.format("%032x", i))).getStatus()).isEqualTo(404);
        }

        mockMvc.perform(curl()).andExpect(status().isCreated());
    }

    @Test
    void theRightNonceAloneIsEnoughAfterTheOtherChecksPass() throws Exception {
        when(service.create(any(), any())).thenReturn(created());

        mockMvc.perform(curlWithNonceValues(NONCE)).andExpect(status().isCreated());
    }

    @Test
    void theNonceIsNeverInAResponseOrALogLine() throws Exception {
        when(service.create(any(), any())).thenReturn(created());

        MockHttpServletResponse ok = perform(curl());
        MockHttpServletResponse refused = perform(curlWithNonceValues("00".repeat(16)));

        assertThat(ok.getContentAsString()).doesNotContain(NONCE);
        assertThat(headersOf(ok).toString()).doesNotContain(NONCE);
        assertThat(headersOf(refused).toString()).doesNotContain(NONCE);
        assertThat(loggedText()).doesNotContain(NONCE).doesNotContain("00".repeat(16));
    }

    @Test
    void fiftyRefusedCallsNeverUseUpTheRateLimit() throws Exception {
        when(service.create(any(), any())).thenReturn(created());
        for (int i = 0; i < 50; i++) {
            assertThat(perform(from(curl(), "10.0.0.5")).getStatus()).isEqualTo(404);
        }

        for (int i = 0; i < 5; i++) {
            assertThat(perform(curl()).getStatus()).as("call " + (i + 1)).isEqualTo(201);
        }
    }

    @Test
    void theSixthCallThatPassedTheGateAnswers429WithRetryAfter() throws Exception {
        when(service.create(any(), any())).thenReturn(null);
        for (int i = 0; i < 5; i++) {
            assertThat(perform(curl()).getStatus()).as("call " + (i + 1)).isEqualTo(404);
        }

        mockMvc.perform(curl())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        // a caller that fails the gate still gets the plain 404, never the 429
        assertThat(perform(from(curl(), "10.0.0.5")).getStatus()).isEqualTo(404);
    }

    @Test
    void noRequestBodyIsReadAndTheServiceGetsTheRemoteAddressAndRequestId() throws Exception {
        when(service.create(any(), any())).thenReturn(created());

        mockMvc.perform(curl().contentType("application/json").content("{\"password\":\"mine\"}"))
                .andExpect(status().isCreated());

        verify(service).create(eq("127.0.0.1"), any());
    }

    @Test
    void theSuccessfulCallLogsNoSecret() throws Exception {
        when(service.create(any(), any())).thenReturn(created());

        mockMvc.perform(curl()).andExpect(status().isCreated());

        assertThat(loggedText()).doesNotContain(PASSWORD).doesNotContain(SECRET_KEY)
                .doesNotContain(SECRET_KEY.replace(" ", "")).doesNotContain("otpauth");
    }

    @Test
    void theControllerAsksTheGateAgainBeforeCallingTheService() throws Exception {
        // Reached only when the filter in front is bypassed; here the gate is called directly.
        DevSampleAdminController controller = new DevSampleAdminController(request -> false, service);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.create(new org.springframework.mock.web.MockHttpServletRequest(), response)).isNull();

        assertThat(response.getStatus()).isEqualTo(404);
        verify(service, never()).create(any(), isNull());
    }

    private static List<String> headersOf(MockHttpServletResponse response) {
        return response.getHeaderNames().stream().filter(name -> !name.equalsIgnoreCase("Date")).sorted()
                .map(name -> name + "=" + response.getHeaders(name)).collect(Collectors.toList());
    }

    private String loggedText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}
