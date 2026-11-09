package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Release audit G-4: one INFO line per /api request on the "sahaj.access" logger, with the path template and opaque
 * ids, and never a path value, a query string, a header or a body.
 */
class AccessLogFilterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final AccessLogFilter filter = new AccessLogFilter();
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("sahaj.access");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void capture() {
        previousLevel = accessLogger.getLevel();
        accessLogger.setLevel(Level.INFO);
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void release() {
        accessLogger.detachAppender(appender);
        accessLogger.setLevel(previousLevel);
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    private String only() {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        return events.get(0).getFormattedMessage();
    }

    @Test
    void writesOneInfoLineWithTemplateStatusDurationAndIds() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        MDC.put("tenantId", TENANT.toString());
        MDC.put("userId", USER.toString());
        UUID admissionId = UUID.randomUUID();
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/ipd/admissions/" + admissionId + "/doses");
        request.setQueryString("name=Asha%20Verma&mobile=9000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/ipd/admissions/{id}/doses");
            ((MockHttpServletResponse) res).setStatus(201);
        };

        filter.doFilter(request, response, chain);

        String line = only();
        assertThat(line).contains("method=POST").contains("path=/api/v1/ipd/admissions/{id}/doses")
                .contains("status=201").contains("tenantId=" + TENANT).contains("userId=" + USER)
                .contains("requestId=" + requestId).containsPattern("durationMs=\\d+");
        assertThat(line).doesNotContain(admissionId.toString()).doesNotContain("Asha").doesNotContain("Verma")
                .doesNotContain("9000000001").doesNotContain("name=").doesNotContain("?");
    }

    @Test
    void aRequestNoHandlerMatchedIsLoggedAsUnmatchedNeverWithItsRawPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ipd/Asha-Verma-9000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(404));

        String line = only();
        assertThat(line).contains("path=unmatched").contains("status=404").contains("tenantId=-").contains("userId=-");
        assertThat(line).doesNotContain("Asha").doesNotContain("9000000001");
    }

    @Test
    void theStaticResourceCatchAllPatternIsLoggedAsUnmatched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ipd/Asha-Verma-9000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/**");
            ((MockHttpServletResponse) res).setStatus(404);
        };

        filter.doFilter(request, response, chain);

        assertThat(only()).contains("path=unmatched").doesNotContain("path=/**").contains("status=404");
    }

    @Test
    void aUserIdRecordedAfterTheContextFilterStartedIsOnTheLineAndClearedAfterwards() throws Exception {
        UUID adminId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain inner = (req, res) -> filter.doFilter(req, res, (r, s) -> RequestLogContextFilter.recordUser(adminId));

        new RequestLogContextFilter().doFilter(request, response, inner);

        assertThat(only()).contains("userId=" + adminId).contains("tenantId=-");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void anExceptionFromTheChainIsLoggedAs500AndStillPropagates() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ipd/admissions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(IllegalStateException.class);

        assertThat(only()).contains("status=500");
    }

    @Test
    void pathsOutsideTheApiAreNotLogged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(appender.list).isEmpty();
    }

    @Test
    void theLoggerIsNamedSahajAccess() {
        assertThat(AccessLogFilter.LOGGER_NAME).isEqualTo("sahaj.access");
    }

    @Test
    void nothingIsWrittenWhenInfoIsOff() throws Exception {
        accessLogger.setLevel(Level.WARN);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/ipd/admissions"), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThat(appender.list).isEmpty();
    }

    @Test
    void theContextFilterAndTheAccessLogTogetherCarryTheUserId() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TenantAuthentication(new TenantPrincipal(USER, TENANT), AuthorityUtils.NO_AUTHORITIES));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ipd/admissions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain inner = (req, res) -> filter.doFilter(req, res, (r, s) -> { });

        new RequestLogContextFilter().doFilter(request, response, inner);

        String line = only();
        assertThat(line).contains("tenantId=" + TENANT).contains("userId=" + USER);
        assertThat(line).contains("requestId=" + response.getHeader("X-Request-Id"));
        assertThat(MDC.get("userId")).as("cleared after the request").isNull();
    }
}
