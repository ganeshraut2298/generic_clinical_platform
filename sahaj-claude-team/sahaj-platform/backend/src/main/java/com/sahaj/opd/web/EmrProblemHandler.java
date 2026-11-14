package com.sahaj.opd.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.platform.api.ClinicUser;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Problems that only the EMR endpoints raise (docs/api/opd.yaml 0.3.0): the contract's own slugs (see
 * {@link EmrProblemException}), and a body that cannot be read. It applies to the EMR controllers only and runs before the platform handler, which handles everything else
 * (401, 403, 409, 413, validation). The text is fixed plain English: no submitted value, no field name that was sent,
 * and nothing is logged here except a refused access (403, security M4).
 */
@RestControllerAdvice(assignableTypes = EmrApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmrProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(EmrProblemHandler.class);
    private static final String TYPE_BASE = "https://sahaj.in/problems/";
    private static final String RELOAD_PAGE = "Reload the page and try again.";
    private static final String INVALID_OPTION = "Choose a valid option";
    private static final List<String> RELOAD_FIELDS = List.of("revision", "version");

    @ExceptionHandler(EmrProblemException.class)
    ResponseEntity<ProblemDetail> handleEmrProblem(EmrProblemException ex, WebRequest request) {
        if (ex.status() == HttpStatus.FORBIDDEN.value()) {
            logRefused(ex.slug(), request);
        }
        ProblemDetail problem = problem(HttpStatus.valueOf(ex.status()), ex.slug(), ex.title(), ex.getMessage());
        if (!ex.fieldErrors().isEmpty()) {
            problem.setProperty("fieldErrors", ex.fieldErrors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message())).toList());
        }
        ex.extensions().forEach(problem::setProperty);
        return respond(problem);
    }

    /**
     * Security review M4 (OWASP ASVS V7.2): a 403 the EMR raises itself (not-own-patient, outside-scope, ...) is logged like the
     * platform handler logs a refused access: the problem type, the clinic and user ids and the controller method name. Never
     * the message, the path or any id from it, and nothing about a patient.
     */
    void logRefused(String type, WebRequest request) {
        String tenantId = "-";
        String userId = "-";
        try {
            ClinicUser user = ClinicUser.current();
            tenantId = String.valueOf(user.tenantId());
            userId = String.valueOf(user.userId());
        } catch (AuthenticationException notAClinicUser) {
            // an admin or system caller: the ids stay "-"
        }
        log.warn("Access refused type={} tenantId={} userId={} handler={}", type, tenantId, userId, handlerName(request));
    }

    private static String handlerName(WebRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (handler instanceof HandlerMethod method) {
            return method.getBeanType().getSimpleName() + "." + method.getMethod().getName();
        }
        return "-";
    }

    /**
     * A closed body with an unknown field answers 400 validation (the platform handler would call it
     * malformed-request); every other unreadable body is answered as the platform handler does.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        if (causedByUnknownField(ex)) {
            return respond(validation("body", "Send only the fields listed for this request."));
        }
        if (ex.getCause() instanceof JsonMappingException mapping) {
            String field = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull).reduce((first, second) -> first + "." + second).orElse(null);
            if (field != null) {
                String last = field.substring(field.lastIndexOf('.') + 1);
                return respond(validation(field, RELOAD_FIELDS.contains(last) ? RELOAD_PAGE : INVALID_OPTION));
            }
        }
        return respond(problem(HttpStatus.BAD_REQUEST, "malformed-request", "Request could not be read",
                "The request body is missing or is not valid JSON."));
    }

    private static boolean causedByUnknownField(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof EmrStrictJsonModule.UnknownFieldException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static ProblemDetail validation(String field, String message) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                "Some fields are missing or not valid.");
        problem.setProperty("fieldErrors", List.of(Map.of("field", field, "message", message)));
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
