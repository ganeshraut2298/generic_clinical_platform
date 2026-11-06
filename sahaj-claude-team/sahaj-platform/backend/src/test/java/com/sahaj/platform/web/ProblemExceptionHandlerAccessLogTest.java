package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.infrastructure.security.TenantAuthentication;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Security review L2 (SAH-26): a refused access is logged with the problem type, the clinic and user ids and the
 * controller method, and never with the exception message, the path or any patient data. A trigger refusal from the
 * database (SQLSTATE 42501) is a plain server error.
 */
class ProblemExceptionHandlerAccessLogTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final String PATIENT_NAME = "Asha Verma";
    private static final String MOBILE = "9000000001";

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();

    /** Stands in for a controller. */
    static class SampleController {
        public void move() {
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TenantAuthentication(
                new TenantPrincipal(USER, TENANT), AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private static ServletWebRequest request(boolean withHandler) throws NoSuchMethodException {
        MockHttpServletRequest http = new MockHttpServletRequest("PUT", "/api/v1/ipd/admissions/" + UUID.randomUUID());
        if (withHandler) {
            http.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                    new HandlerMethod(new SampleController(), SampleController.class.getMethod("move")));
        }
        return new ServletWebRequest(http);
    }

    private static List<ILoggingEvent> warnings(LogCapture capture) {
        return capture.events().stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void aBusinessRefusalIsLoggedWithTypeTenantUserAndHandlerOnly() throws Exception {
        signIn("NURSE");
        try (LogCapture capture = new LogCapture()) {
            ResponseEntity<Object> response = handler.handleForbidden(new ForbiddenException("owner-approval-needed",
                    "Owner approval needed", "Moving " + PATIENT_NAME + " needs the owner's approval."), request(true));

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            List<ILoggingEvent> warnings = warnings(capture);
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage()).isEqualTo("Access refused type=owner-approval-needed"
                    + " tenantId=" + TENANT + " userId=" + USER + " handler=SampleController.move");
            assertThat(capture.allText()).doesNotContain(PATIENT_NAME).doesNotContain("/api/v1")
                    .doesNotContain("needs the owner");
        }
    }

    @Test
    void aSignedInUserWithoutPermissionIsLoggedAndGets403() throws Exception {
        signIn("BILLING");
        try (LogCapture capture = new LogCapture()) {
            ResponseEntity<Object> response = handler.handleAccessDenied(
                    new AccessDeniedException("You cannot move " + PATIENT_NAME + " " + MOBILE), request(true));

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(warnings(capture)).hasSize(1);
            assertThat(warnings(capture).get(0).getFormattedMessage()).isEqualTo("Access refused type=access-denied"
                    + " tenantId=" + TENANT + " userId=" + USER + " handler=SampleController.move");
            assertThat(capture.allText()).doesNotContain(PATIENT_NAME).doesNotContain(MOBILE)
                    .doesNotContain("/api/v1");
        }
    }

    @Test
    void aMissingHandlerIsADash() throws Exception {
        signIn("NURSE");
        try (LogCapture capture = new LogCapture()) {
            handler.handleAccessDenied(new AccessDeniedException("no"), request(false));

            assertThat(warnings(capture).get(0).getFormattedMessage()).endsWith("handler=-");
        }
    }

    @Test
    void aSignedOutVisitorGets401AndIsNotLoggedAsRefused() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        try (LogCapture capture = new LogCapture()) {
            ResponseEntity<Object> response = handler.handleAccessDenied(new AccessDeniedException("no"),
                    request(true));

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(warnings(capture)).isEmpty();
        }
    }

    @Test
    void aDatabaseGuardRefusalIsAGenericServerErrorWithNoDataInTheAnswer() throws Exception {
        try (LogCapture capture = new LogCapture()) {
            SQLException guard = new SQLException("bed change refused", "42501");
            ResponseEntity<Object> response = handler.handleUnexpected(
                    new BadSqlGrammarException("PreparedStatementCallback", "update ipd.admission", guard));

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            ProblemDetail problem = (ProblemDetail) response.getBody();
            assertThat(problem).isNotNull();
            assertThat(String.valueOf(problem.getDetail())).doesNotContain("bed change").doesNotContain("admission");
            assertThat(capture.allText()).contains("sqlState=42501").doesNotContain(PATIENT_NAME);
        }
    }
}
