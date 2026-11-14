package com.sahaj.opd.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.platform.support.TestTenantSecurity;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Security review M4 (OWASP ASVS V7.2): a 403 the EMR raises itself (not-own-patient, outside-scope, ...) leaves one fixed log
 * line like the platform's refused-access line: the problem type, the clinic and user ids and the controller method. Never the
 * message, the path or an id from it, and nothing about a patient.
 */
class EmrProblemHandlerRefusalLogTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final EmrProblemHandler handler = new EmrProblemHandler();
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private Logger logger;

    /** A stand-in for the controller method that handled the request. */
    static class SampleController {
        public void sign() {
            // never called
        }
    }

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(EmrProblemHandler.class);
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(lines);
        lines.stop();
    }

    private static ServletWebRequest request() throws Exception {
        MockHttpServletRequest http = new MockHttpServletRequest("POST",
                "/api/v1/opd/emr/records/11111111-1111-1111-1111-111111111111/sign");
        ServletWebRequest request = new ServletWebRequest(http);
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(new SampleController(), SampleController.class.getMethod("sign")),
                RequestAttributes.SCOPE_REQUEST);
        return request;
    }

    @Test
    void aRefusedAccessIsLoggedWithTheTypeTheIdsAndTheHandlerOnly() throws Exception {
        ServletWebRequest request = request();

        ResponseEntity<ProblemDetail> answer = TestTenantSecurity.runAs(
                TestTenantSecurity.authentication(TENANT, USER, "DOCTOR"),
                () -> handler.handleEmrProblem(EmrProblemException.notOwnPatient(), request));

        assertThat(answer.getStatusCode().value()).isEqualTo(403);
        assertThat(lines.list).hasSize(1);
        ILoggingEvent line = lines.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage()).isEqualTo("Access refused type=not-own-patient tenantId=" + TENANT
                + " userId=" + USER + " handler=SampleController.sign");
        assertThat(line.getFormattedMessage()).doesNotContain("11111111").doesNotContain("own patients")
                .doesNotContain("/api/");
    }

    @Test
    void aRefusalWithNoSignedInClinicUserLogsDashesAndAnyOtherStatusLogsNothing() throws Exception {
        ServletWebRequest request = request();

        handler.handleEmrProblem(EmrProblemException.outsideScope(), request);
        assertThat(lines.list).hasSize(1);
        assertThat(lines.list.get(0).getFormattedMessage())
                .isEqualTo("Access refused type=outside-scope tenantId=- userId=- handler=SampleController.sign");

        lines.list.clear();
        handler.handleEmrProblem(EmrProblemException.emrRecordSigned(), request);
        handler.handleEmrProblem(EmrProblemException.recordNotFound(), request);
        assertThat(lines.list).isEmpty();
    }
}
