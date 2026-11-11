package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.web.Problems;
import com.sahaj.platform.web.admin.AdminDevPathFilter;
import com.sahaj.platform.web.admin.DevEndpointGate;
import com.sahaj.platform.web.security.SecurityConfig;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Both endpoints through the real reserved-path filter, the real gate, the real bootstrap security chain and the real
 * controller (ADR-018 D2, D5, D8). The service is a mock: the rules are in {@link BootstrapServiceTest}, the database
 * functions in the integration tests that need the admin tables (QA S1 to S30).
 */
@WebMvcTest(controllers = BootstrapController.class, properties = {
        "sahaj.admin.enabled=true",
        "sahaj.admin.jwt-secret=an-admin-secret-with-more-than-32-chars",
        "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==", "server.address=127.0.0.1",
        "sahaj.admin.bootstrap.enabled=true",
        "sahaj.admin.bootstrap.pin-hash=$2a$12$qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
        "sahaj.auth.refresh-cookie-secure=false"})
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({SecurityConfig.class, BootstrapSecurityConfig.class, BootstrapEndpointTest.Wiring.class})
class BootstrapEndpointTest {

    private static final String UNLOCK = "/api/v1/admin/bootstrap/unlock";
    private static final String REGISTER = "/api/v1/admin/bootstrap/register";
    private static final String TICKET = "Xq3vTn8bKp0aLmZr5YwC2dEfGhJ7sUiOq9RtVbNcA1k";
    private static final String BINDING = "Zy9kPq2WmN5vTx0bYc7dLf3gHj9sAe1uKi4oQw6rXyZ";
    private static final String PIN = "throwaway-pin-1";
    private static final String PASSWORD = "throwaway-password-9";
    private static final String EMAIL = "first.admin@example.test";
    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        BootstrapGate bootstrapGate() {
            return new BootstrapGate();
        }

        @Bean
        FilterRegistrationBean<AdminDevPathFilter> devPathFilter(ObjectProvider<DevEndpointGate> gates) {
            FilterRegistrationBean<AdminDevPathFilter> registration =
                    new FilterRegistrationBean<>(new AdminDevPathFilter(gates));
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
    private BootstrapService service;

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

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        logs.stop();
        root.setLevel(rootLevel);
    }

    // ---- request builders ----

    private static MockHttpServletRequestBuilder curl(String path, String body) {
        return post(path).with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        }).header("Host", "127.0.0.1:8080").contentType("application/json").content(body);
    }

    private static MockHttpServletRequestBuilder unlock(String pin) {
        return curl(UNLOCK, "{\"pin\":\"" + pin + "\"}");
    }

    private static MockHttpServletRequestBuilder register() {
        return curl(REGISTER, "{\"ticket\":\"" + TICKET + "\",\"fullName\":\"First admin\",\"email\":\"" + EMAIL
                + "\",\"password\":\"" + PASSWORD + "\"}").cookie(new Cookie("sahaj_admin_bootstrap", BINDING));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static List<String> headersOf(MockHttpServletResponse response) {
        return response.getHeaderNames().stream().filter(name -> !name.equalsIgnoreCase("Date")).sorted()
                .map(name -> name + "=" + response.getHeaders(name)).collect(Collectors.toList());
    }

    private String loggedText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static BootstrapService.UnlockOutcome unlockOutcome(BootstrapService.UnlockKind kind, int triesLeft,
                                                                long retry, String ticket, String binding) {
        return new BootstrapService.UnlockOutcome(kind, triesLeft, retry, ticket, binding);
    }

    private static BootstrapService.RegisterOutcome registerOutcome(BootstrapService.RegisterKind kind, long retry,
                                                                    List<Problems.FieldError> errors) {
        return new BootstrapService.RegisterOutcome(kind, retry, errors,
                kind == BootstrapService.RegisterKind.CREATED ? ADMIN_ID : null);
    }

    // ================================================================== unlock

    @Test
    void aCorrectPinReturnsTheTicketInTheBodyAndTheBindingInAnHttpOnlyStrictCookie() throws Exception {
        when(service.unlock(any(), eq("127.0.0.1"), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.UNLOCKED, 0, 0, TICKET, BINDING));

        MockHttpServletResponse response = mockMvc.perform(unlock(PIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.ticket").value(TICKET))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse();

        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).startsWith("sahaj_admin_bootstrap=" + BINDING).contains("HttpOnly")
                .contains("SameSite=Strict").contains("Path=/api/v1/admin/bootstrap").contains("Max-Age=300")
                .doesNotContain("Secure");
        assertThat(response.getContentAsString()).doesNotContain(BINDING);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);
    }

    @Test
    void theBodyIsHandedToTheServiceAsSentAndNeverLogged() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.UNLOCKED, 0, 0, TICKET, BINDING));

        mockMvc.perform(unlock(PIN)).andExpect(status().isOk());

        org.mockito.ArgumentCaptor<BootstrapBodies.UnlockBody> body =
                org.mockito.ArgumentCaptor.forClass(BootstrapBodies.UnlockBody.class);
        verify(service).unlock(body.capture(), eq("127.0.0.1"), any());
        assertThat(body.getValue().wellFormed()).isTrue();
        assertThat(body.getValue().pin()).isEqualTo(PIN);
        assertThat(loggedText()).doesNotContain(PIN).doesNotContain(TICKET).doesNotContain(BINDING);
    }

    @Test
    void aWrongPinIs401WithTriesLeftAndTheSameTitleWhateverWasTyped() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.WRONG_PIN, 3, 0, null, null));

        mockMvc.perform(unlock("anything-else"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bootstrap-pin-invalid"))
                .andExpect(jsonPath("$.title").value("That PIN is not right."))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.triesLeft").value(3))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(loggedText()).contains("Bootstrap registration: wrong PIN (2 of 5)").doesNotContain("anything-else");
    }

    @Test
    void lockedIs423WithRetryAfterInTheHeaderAndTheBody() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.LOCKED, 0, 3600, null, null));

        mockMvc.perform(unlock(PIN))
                .andExpect(status().isLocked())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bootstrap-locked"))
                .andExpect(jsonPath("$.title").value("Registration is locked."))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3600))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ---- R3-U14: every problem document names the operation's own fixed path, never the request URI ----

    @Test
    void everyUnlockProblemDocumentHasTheFixedInstanceEvenWhenTheRequestUriIsWrittenDifferently() throws Exception {
        String[] uris = {UNLOCK, UNLOCK + ";x=y", "/api/v1/admin/bootstrap/%75nlock", "/api/v1/admin/bootstrap/unlock;a=b;c"};
        for (BootstrapService.UnlockKind kind : new BootstrapService.UnlockKind[] {
                BootstrapService.UnlockKind.BAD_REQUEST, BootstrapService.UnlockKind.INVALID_BODY,
                BootstrapService.UnlockKind.WRONG_PIN, BootstrapService.UnlockKind.LOCKED,
                BootstrapService.UnlockKind.LOCKED_NOW}) {
            when(service.unlock(any(), any(), any())).thenReturn(
                    new BootstrapService.UnlockOutcome(kind, 3, 3600, null, null, Instant.parse("2026-11-10T11:00:00Z")));
            for (String uri : uris) {
                MockHttpServletResponse response = perform(curl(uri, "{\"pin\":\"" + PIN + "\"}"));
                assertFixedInstanceOrFirewallRefusal(response, kind + " " + uri, UNLOCK);
            }
        }
    }

    /**
     * A document that reaches the answer carries the fixed path and never a byte of the request URI. A path with
     * {@code ;} parameters is refused earlier by the request firewall (empty 400), and a differently percent-encoded one
     * does not match the mapping (empty 404); neither echoes anything.
     */
    private static void assertFixedInstanceOrFirewallRefusal(MockHttpServletResponse response, String what,
                                                             String fixedPath) throws Exception {
        String body = response.getContentAsString();
        assertThat(body).as(what).doesNotContain("x=y").doesNotContain("a=b");
        if (body.isEmpty()) {
            assertThat(response.getStatus()).as(what + " refused before the controller").isIn(400, 404);
        } else {
            assertThat(body).as(what + " status " + response.getStatus())
                    .contains("\"instance\":\"" + fixedPath + "\"");
        }
    }

    @Test
    void theTooManyRequestsAnswerOfEitherOperationHasTheFixedInstance() throws Exception {
        when(service.unlock(any(), any(), any())).thenThrow(new BootstrapExceptions.Busy());
        when(service.register(any(), any(), any(), any())).thenThrow(new BootstrapExceptions.Busy());

        mockMvc.perform(unlock(PIN)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.instance").value(UNLOCK));
        mockMvc.perform(register()).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.instance").value(REGISTER));
    }

    @Test
    void everyRegisterProblemDocumentHasTheFixedInstanceEvenWhenTheRequestUriIsWrittenDifferently() throws Exception {
        String[] uris = {REGISTER, REGISTER + ";x=y", "/api/v1/admin/bootstrap/%72egister"};
        for (BootstrapService.RegisterKind kind : new BootstrapService.RegisterKind[] {
                BootstrapService.RegisterKind.BAD_REQUEST, BootstrapService.RegisterKind.INVALID_FORM,
                BootstrapService.RegisterKind.TICKET_INVALID, BootstrapService.RegisterKind.LOCKED}) {
            when(service.register(any(), any(), any(), any())).thenReturn(registerOutcome(kind, 600,
                    List.of(new Problems.FieldError("fullName", "Enter the full name."))));
            for (String uri : uris) {
                MockHttpServletResponse response = perform(curl(uri, "{\"ticket\":\"" + TICKET + "\"}")
                        .cookie(new Cookie("sahaj_admin_bootstrap", BINDING)));
                assertFixedInstanceOrFirewallRefusal(response, kind + " " + uri, REGISTER);
            }
        }
    }

    // ---- R3-U2: a failed judge is a bodyless 500, never a 401, and the lock line is never logged ----

    @Test
    void aFailedJudgeIsABodylessFiveHundredWithNoTriesLeftAndNoLockLine() throws Exception {
        for (RuntimeException failure : new RuntimeException[] {new BootstrapExceptions.Failed("SB004"),
                new BootstrapExceptions.Failed("P0001"), new BootstrapExceptions.Failed("55P03"),
                new org.springframework.transaction.TransactionSystemException("commit failed")}) {
            org.mockito.Mockito.doThrow(failure).when(service).unlock(any(), any(), any());

            MockHttpServletResponse response = perform(unlock(PIN));

            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).isEmpty();
            assertThat(response.getContentType()).isNull();
        }
        assertThat(loggedText()).doesNotContain("locked until").doesNotContain("wrong PIN").doesNotContain(PIN);
    }

    @Test
    void anUnlockThatIsCalledInsideATransactionIsABodylessFiveHundred() throws Exception {
        when(service.unlock(any(), any(), any())).thenThrow(
                new org.springframework.transaction.IllegalTransactionStateException("existing transaction found"));

        MockHttpServletResponse response = perform(unlock(PIN));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void aBlankMissingOrNullPinIsA400ValidationOnPinAndSetsNoCookie() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.INVALID_BODY, 0, 0, null, null));

        for (String body : new String[] {"{\"pin\":\"  \"}", "{}", "{\"pin\":null}"}) {
            mockMvc.perform(curl(UNLOCK, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("pin"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter the PIN."))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @Test
    void anUnreadableUnlockBodyIsA400BadRequestWithTheFixedTextNoFieldErrorsAndNoEcho() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.BAD_REQUEST, 0, 0, null, null));

        for (String body : new String[] {"{\"pin\":\"1\",\"extra\":1}", "{\"pin\":1}", "x"}) {
            MockHttpServletResponse response = mockMvc.perform(curl(UNLOCK, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.detail")
                            .value("The request could not be understood. Check it and try again."))
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andReturn().getResponse();

            assertThat(response.getContentAsString()).doesNotContain("extra").doesNotContain("pin\"");
        }
    }

    @Test
    void anUnreadableRegisterBodyAfterAValidTicketIsA400BadRequestAndTheCookieIsKept() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.BAD_REQUEST, 0, List.of()));

        MockHttpServletResponse response = mockMvc.perform(register())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bad-request"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail")
                        .value("The request could not be understood. Check it and try again."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                .andReturn().getResponse();

        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    void theFifthWrongPinIsLoggedOnceWithTheEndTimeAndALockAlreadyInForceIsNotLogged() throws Exception {
        java.time.Instant until = java.time.Instant.parse("2026-11-10T06:05:00Z");
        when(service.unlock(any(), any(), any())).thenReturn(
                new BootstrapService.UnlockOutcome(BootstrapService.UnlockKind.LOCKED_NOW, 0, 3600, null, null, until),
                unlockOutcome(BootstrapService.UnlockKind.LOCKED, 0, 3000, null, null));

        mockMvc.perform(unlock(PIN)).andExpect(status().isLocked())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3600));
        mockMvc.perform(unlock(PIN)).andExpect(status().isLocked())
                .andExpect(header().string("Retry-After", "3000"));

        assertThat(logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getLoggerName().endsWith("BootstrapController"))
                .map(ILoggingEvent::getFormattedMessage).toList())
                .containsExactly("Bootstrap registration locked until 2026-11-10T06:05:00Z");
        assertThat(loggedText()).doesNotContain(PIN);
    }

    @Test
    void anAdminThatExistsIsTheEmptyFourOhFourWithOnlyCacheControl() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.CLOSED, 0, 0, null, null));

        MockHttpServletResponse response = perform(unlock(PIN));
        MockHttpServletResponse filter = perform(unlock(PIN).with(request -> {
            request.setRemoteAddr("10.0.0.5");
            return request;
        }));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
        assertThat(headersOf(response)).containsExactly("Cache-Control=[no-store]");
        assertThat(headersOf(filter)).isEqualTo(headersOf(response));
    }

    @Test
    void closedFromTheDatabaseFunctionIsTheSameEmpty404() throws Exception {
        when(service.unlock(any(), any(), any())).thenThrow(new BootstrapExceptions.Closed());

        MockHttpServletResponse response = perform(unlock(PIN));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(headersOf(response)).containsExactly("Cache-Control=[no-store]");
    }

    @Test
    void aWaitOverTenSecondsIs429WithRetryAfterFiveAndNothingCounted() throws Exception {
        when(service.unlock(any(), any(), any())).thenThrow(new BootstrapExceptions.Busy());

        mockMvc.perform(unlock(PIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/rate-limit"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(5));
    }

    @Test
    void anyOtherFailureIsABodylessFiveHundredThatLogsTheTypeOnly() throws Exception {
        when(service.unlock(any(), any(), any())).thenThrow(new IllegalStateException(
                "Detail: Key (email)=(" + EMAIL + ") " + PIN + " " + PASSWORD));

        MockHttpServletResponse response = perform(unlock(PIN));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(loggedText()).contains("BootstrapExceptions.Failed").doesNotContain(EMAIL).doesNotContain(PIN)
                .doesNotContain(PASSWORD);
    }

    @Test
    void aFailureWithTheFailingRowInItsMessageReachesSpringsResolversAsAMessageFreeCauseLessException()
            throws Exception {
        String row = "Failing row contains (" + EMAIL + ", $2a$12$abcdefghijklmnopqrstuv)";
        when(service.unlock(any(), any(), any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                row, new java.sql.SQLException(row, "23514")));

        MvcResult result = mockMvc.perform(unlock(PIN)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        Exception resolved = result.getResolvedException();
        assertThat(resolved).isInstanceOf(BootstrapExceptions.Failed.class);
        assertThat(resolved.getMessage()).isNull();
        assertThat(resolved.getCause()).isNull();
        assertThat(resolved.toString()).isEqualTo("BootstrapExceptions.Failed[sqlState=23514]");
        assertThat(loggedText()).contains("sqlState=23514").doesNotContain(EMAIL).doesNotContain("Failing row");
    }

    // ================================================================== register

    @Test
    void aCreatedAdminIs201WithNoBodyNoContentTypeAndTheCookieCleared() throws Exception {
        when(service.register(any(), eq(BINDING), eq("127.0.0.1"), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.CREATED, 0, List.of()));

        MockHttpServletResponse response = mockMvc.perform(register())
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("Content-Type"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().bytes(new byte[0]))
                .andReturn().getResponse();

        assertThat(response.getContentLength()).isLessThanOrEqualTo(0);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeaders("Set-Cookie")).hasSize(1);

        assertThat(response.getHeader("Set-Cookie")).startsWith("sahaj_admin_bootstrap=;").contains("Max-Age=0")
                .contains("Path=/api/v1/admin/bootstrap");
        assertThat(loggedText()).contains("Bootstrap registration: admin created adminId=" + ADMIN_ID)
                .doesNotContain(EMAIL).doesNotContain(PASSWORD).doesNotContain(TICKET)
                .doesNotContain(BINDING).doesNotContain("First admin");
    }

    @Test
    void theServiceGetsTheFormAndTheCookieValueAndNothingElseFromTheRequest() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.CREATED, 0, List.of()));

        mockMvc.perform(register().header("Authorization", "Bearer not-a-token")
                .cookie(new Cookie("sahaj_admin_rt", "x")))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<BootstrapBodies.RegisterBody> body =
                org.mockito.ArgumentCaptor.forClass(BootstrapBodies.RegisterBody.class);
        verify(service).register(body.capture(), eq(BINDING), eq("127.0.0.1"), any());
        assertThat(body.getValue().wellFormed()).isTrue();
        assertThat(body.getValue().ticket()).isEqualTo(TICKET);
        assertThat(body.getValue().fullName()).isEqualTo("First admin");
        assertThat(body.getValue().email()).isEqualTo(EMAIL);
        assertThat(body.getValue().password()).isEqualTo(PASSWORD);
    }

    @Test
    void aMissingCookieReachesTheServiceAsNull() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.TICKET_INVALID, 0, List.of()));

        mockMvc.perform(curl(REGISTER, "{\"ticket\":\"" + TICKET + "\",\"fullName\":\"A\",\"email\":\"a@b.co\","
                + "\"password\":\"p\"}")).andExpect(status().isUnauthorized());

        verify(service).register(any(), eq((String) null), any(), any());
    }

    @Test
    void anInvalidTicketIs401TheCookieIsClearedAndTheTitleIsTheContractOne() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.TICKET_INVALID, 0, List.of()));

        MockHttpServletResponse response = mockMvc.perform(register())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bootstrap-ticket-invalid"))
                .andExpect(jsonPath("$.title").value("Your unlock took too long. Enter the PIN again."))
                .andReturn().getResponse();

        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0").startsWith("sahaj_admin_bootstrap=;");
    }

    @Test
    void lockedDuringRegisterIs423WithRetryAfter() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.LOCKED, 1234, List.of()));

        mockMvc.perform(register())
                .andExpect(status().isLocked())
                .andExpect(header().string("Retry-After", "1234"))
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bootstrap-locked"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(1234));
    }

    @Test
    void aRejectedFormIs400WithFieldErrorsAndNeverEchoesTheValuesOrClearsTheCookie() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(registerOutcome(
                BootstrapService.RegisterKind.INVALID_FORM, 0, List.of(
                        new Problems.FieldError("email", "Enter a valid email."),
                        new Problems.FieldError("password", "The password must be 12 to 64 characters."))));

        MockHttpServletResponse response = mockMvc.perform(register())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/validation"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("password"))
                .andReturn().getResponse();

        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getContentAsString()).doesNotContain(EMAIL).doesNotContain(PASSWORD)
                .doesNotContain("First admin");
    }

    @Test
    void anAdminThatExistsIsTheEmpty404ForRegisterToo() throws Exception {
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.CLOSED, 0, List.of()));

        MockHttpServletResponse response = perform(register());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(headersOf(response)).containsExactly("Cache-Control=[no-store]");
    }

    @Test
    void aWaitOverTenSecondsAtRegisterIs429TooAndAnyFailureIsABodyless500() throws Exception {
        org.mockito.Mockito.doThrow(new BootstrapExceptions.Busy()).when(service)
                .register(any(), any(), any(), any());
        mockMvc.perform(register()).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"));

        org.mockito.Mockito.doThrow(new IllegalStateException(PASSWORD + EMAIL)).when(service)
                .register(any(), any(), any(), any());
        MockHttpServletResponse response = perform(register());
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(loggedText()).doesNotContain(PASSWORD).doesNotContain(EMAIL);
    }

    // ================================================================== reach (K1 to K6) at the filter

    @Test
    void everyRefusedCallerAnswersTheSameEmpty404AndNeverReachesTheService() throws Exception {
        MockHttpServletRequestBuilder good = unlock(PIN);
        List<MockHttpServletRequestBuilder> refused = List.of(
                from(unlock(PIN), "10.0.0.5"), from(unlock(PIN), "192.168.1.20"), from(unlock(PIN), "2001:db8::1"),
                from(unlock(PIN).header("X-Forwarded-For", "127.0.0.1"), "10.0.0.5"),
                unlock(PIN).header("X-Forwarded-For", "203.0.113.9"),
                unlock(PIN).header("X-Forwarded-For", "127.0.0.1, 203.0.113.9"),
                unlock(PIN).header("Forwarded", "for=127.0.0.1"),
                unlock(PIN).header("X-Real-IP", "127.0.0.1"),
                unlock(PIN).header("Via", "1.1 proxy"),
                unlock(PIN).header("X-Forwarded-Prefix", "/x"),
                unlock(PIN).header("Origin", "http://evil.example"),
                unlock(PIN).header("Origin", "null"),
                unlock(PIN).header("Sec-Fetch-Site", "cross-site"),
                unlock(PIN).header("Sec-Fetch-Site", "same-site"),
                unlock(PIN).header("Host", "evil.example"),
                curl(UNLOCK, "{\"pin\":\"1\"}").contentType("application/x-www-form-urlencoded"),
                curl(UNLOCK, "{\"pin\":\"1\"}").contentType("text/plain"),
                get(UNLOCK).header("Host", "127.0.0.1"),
                options(UNLOCK).header("Host", "127.0.0.1").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST"),
                curl("/api/v1/admin/bootstrap/unlock/", "{}"),
                curl("/api/v1/admin/bootstrap", "{}"),
                curl("/api/v1/admin/bootstrap/other", "{}"),
                curl("/api/v1/admin/bootstrap/UNLOCK", "{}"),
                curl("/API/V1/ADMIN/BOOTSTRAP/UNLOCK", "{}"),
                curl("/api/v1/admin/bootstrap/register/x", "{}"));
        MockHttpServletResponse reference = perform(from(unlock(PIN), "10.0.0.5"));
        for (MockHttpServletRequestBuilder request : refused) {
            MvcResult result = mockMvc.perform(request).andReturn();
            MockHttpServletResponse response = result.getResponse();
            String label = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " from "
                    + result.getRequest().getRemoteAddr();
            assertThat(response.getStatus()).as(label).isEqualTo(404);
            assertThat(response.getContentAsByteArray()).as(label).isEmpty();
            assertThat(response.getContentType()).as(label).isNull();
            assertThat(headersOf(response)).as(label).isEqualTo(headersOf(reference)).containsExactly("Cache-Control=[no-store]");
        }
        verifyNoInteractions(service);
        assertThat(good).isNotNull();
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String address) {
        return builder.with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }

    @Test
    void theBrowserThroughTheAngularDevProxyIsLetThrough() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.WRONG_PIN, 4, 0, null, null));

        mockMvc.perform(post(UNLOCK).with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }).header("Host", "localhost:4200").contentType("application/json")
                        .content("{\"pin\":\"" + PIN + "\"}").header("Origin", "http://localhost:4200")
                        .header("Sec-Fetch-Site", "same-origin").header("X-Forwarded-For", "127.0.0.1")
                        .header("X-Forwarded-Host", "localhost:4200").header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Port", "4200"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void aBearerTokenOrAnyOtherCookieIsIgnored() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.WRONG_PIN, 4, 0, null, null));

        mockMvc.perform(unlock(PIN).header("Authorization", "Bearer not-a-token")
                        .cookie(new Cookie("sahaj_admin_rt", "x"), new Cookie("sahaj_rt", "y")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://sahaj.in/problems/bootstrap-pin-invalid"));
    }

    @Test
    void theControllerAsksTheGateAgainBeforeCallingTheService() {
        BootstrapController controller = new BootstrapController(new BootstrapGate(), service, false);
        MockHttpServletResponse unlock = new MockHttpServletResponse();
        MockHttpServletResponse register = new MockHttpServletResponse();
        org.springframework.mock.web.MockHttpServletRequest bare = new org.springframework.mock.web.MockHttpServletRequest();

        assertThat(controller.unlock(bare, unlock)).isNull();
        assertThat(controller.register(bare, register)).isNull();

        assertThat(unlock.getStatus()).isEqualTo(404);
        assertThat(register.getStatus()).isEqualTo(404);
        verifyNoInteractions(service);
    }

    // ================================================================== size, headers, logging

    @Test
    void aBodyOverFourKilobytesIs413AndNeverReachesTheService() throws Exception {
        MockHttpServletResponse response = perform(curl(UNLOCK, "{\"pin\":\"" + "9".repeat(5000) + "\"}"));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getContentAsString()).contains("payload-too-large").doesNotContain("99999");
        verify(service, never()).unlock(any(), any(), any());
    }

    @Test
    void aBodyJustUnderTheLimitIsReadNormally() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.WRONG_PIN, 4, 0, null, null));

        mockMvc.perform(curl(UNLOCK, "{\"pin\":\"" + "9".repeat(3900) + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void noRequestOrResponseBodyReachesTheLogAtDebug() throws Exception {
        when(service.unlock(any(), any(), any())).thenReturn(
                unlockOutcome(BootstrapService.UnlockKind.UNLOCKED, 0, 0, TICKET, BINDING));
        when(service.register(any(), any(), any(), any())).thenReturn(
                registerOutcome(BootstrapService.RegisterKind.CREATED, 0, List.of()));

        perform(unlock(PIN));
        perform(register());
        perform(curl(UNLOCK, "{\"pin\":\"" + PIN + "\",\"surprise\":true}"));

        assertThat(loggedText()).doesNotContain(PIN).doesNotContain(TICKET).doesNotContain(BINDING)
                .doesNotContain(PASSWORD).doesNotContain(EMAIL).doesNotContain("surprise");
    }

    @Test
    void theResponsesPrintNoValueInToString() {
        assertThat(unlockOutcome(BootstrapService.UnlockKind.UNLOCKED, 0, 0, TICKET, BINDING).toString())
                .doesNotContain(TICKET).doesNotContain(BINDING);
        assertThat(registerOutcome(BootstrapService.RegisterKind.CREATED, 0, List.of()).toString())
                .doesNotContain(ADMIN_ID.toString());
    }
}
