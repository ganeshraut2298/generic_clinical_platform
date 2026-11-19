package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.sahaj.pharmacy.application.IntegrationOffException;
import com.sahaj.pharmacy.application.PharmacyProblemException;
import com.sahaj.pharmacy.application.StoreAccessDeniedException;
import com.sahaj.pharmacy.application.StoreDenialAuditor;
import java.net.URI;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problems that only the Pharmacy and Inventory endpoints raise (docs/api/pharmacy.yaml 0.1.0): the 409s that carry
 * {@code current} or {@code lockedFields}, and a body that cannot be read. It applies to the pharmacy controllers only
 * and runs before the platform handler, which handles everything else (401, 403, 404, 409, 413, validation, 503). The
 * text is fixed plain English: no submitted value and no field name that was sent.
 *
 * <p>It also writes the audit of a store denial (code review N-1, security L7): by the time it runs, the caller's
 * transaction has ended, so the audit takes the only connection the request holds, in one short transaction.
 */
@RestControllerAdvice(assignableTypes = PharmacyApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PharmacyProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(PharmacyProblemHandler.class);
    private static final String TYPE_BASE = "https://sahaj.in/problems/";
    private static final String RELOAD_PAGE = "Reload the page and try again.";
    private static final String INVALID_OPTION = "Choose a valid option";
    private static final String INVALID_DATE = "Enter a valid date";
    private static final long RETRY_AFTER_SECONDS = 5;
    private static final List<String> RELOAD_FIELDS = List.of("version", "baseVersionNo", "clientKey");

    private final ObjectProvider<StoreDenialAuditor> denialAuditor;

    /** The auditor is looked up when a denial happens, so a web slice test without it still starts. */
    public PharmacyProblemHandler(ObjectProvider<StoreDenialAuditor> denialAuditor) {
        this.denialAuditor = denialAuditor;
    }

    @ExceptionHandler(PharmacyProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(PharmacyProblemException ex) {
        ProblemDetail problem = problem(HttpStatus.valueOf(ex.status()), ex.slug(), ex.title(), ex.getMessage());
        ex.extensions().forEach(problem::setProperty);
        return respond(problem);
    }

    /**
     * A closed body with an unknown field answers 400 validation (the platform handler would call it malformed-request);
     * every other unreadable body is answered as the platform handler does.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return unreadable(ex);
    }

    /** The service asked for the body after its gate (see {@link PharmacyBodies}) and it could not be read. */
    @ExceptionHandler(UnreadableBodyException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(UnreadableBodyException ex) {
        return unreadable(ex);
    }

    /** 409 {@code integration-off} (ADR-028 D5): an Integrated-only operation on a Standalone unit. Nothing was written. */
    @ExceptionHandler(IntegrationOffException.class)
    ResponseEntity<ProblemDetail> handleIntegrationOff(IntegrationOffException ex) {
        return respond(problem(HttpStatus.CONFLICT, "integration-off", IntegrationOffException.TITLE,
                IntegrationOffException.DETAIL));
    }

    /**
     * 403 {@code store-permission-denied}, answered as the platform handler answers a {@code ForbiddenException}, after the
     * denial audit (ids only) has been written in its own short transaction. If the audit cannot be written the person is
     * still refused (nothing was changed) and the failure is logged with ids only.
     */
    @ExceptionHandler(StoreAccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleStoreDenied(StoreAccessDeniedException ex) {
        try {
            denialAuditor.getObject().record(ex);
        } catch (RuntimeException failed) {
            log.error("Store denial audit not written storeId={} membershipId={} capability={} cause={}", ex.storeId(),
                    ex.membershipId(), ex.capability(), failed.getClass().getSimpleName());
        }
        return respond(problem(HttpStatus.FORBIDDEN, ex.problemType(), ex.title(), ex.getMessage()));
    }

    /**
     * 503 {@code database-busy} (ADR-029 P5, DEC-054): a deadlock (SQLSTATE 40P01), a lock that was not free in time (55P03), a
     * serialization failure (40001) or a statement that ran past its timeout (57014). PostgreSQL has already ended the
     * statement and the request's transaction is rolled back, so nothing was saved; the client retries with the same
     * {@code clientKey}. The answer carries no exception text and the log line holds only the kind and the SQLSTATE.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> handleBusy(RuntimeException ex) {
        log.warn("Pharmacy request ended by the database kind={} sqlState={}", ex.getClass().getSimpleName(), sqlState(ex));
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "database-busy", "Sahaj is busy",
                "This is taking longer than usual and was stopped. Nothing was saved by this attempt. "
                        + "Wait a moment and try again.");
        problem.setProperty("retryAfterSeconds", RETRY_AFTER_SECONDS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS)).body(problem);
    }

    private static String sqlState(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof java.sql.SQLException sql) {
                return sql.getSQLState();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return "-";
    }

    private ResponseEntity<ProblemDetail> unreadable(Exception ex) {
        if (causedByUnknownField(ex)) {
            return respond(validation("body", "Send only the fields listed for this request."));
        }
        if (ex.getCause() instanceof JsonMappingException mapping) {
            String field = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull).reduce((first, second) -> first + "." + second).orElse(null);
            if (field != null) {
                String last = field.substring(field.lastIndexOf('.') + 1);
                return respond(validation(field, message(mapping, last)));
            }
        }
        return respond(problem(HttpStatus.BAD_REQUEST, "malformed-request", "Request could not be read",
                "The request body is missing or is not valid JSON."));
    }

    /**
     * The sentence for a value that cannot be read: a date field gets "Enter a valid date" (F-4), the reload fields get
     * "Reload the page and try again.", every other field (enum, id) gets "Choose a valid option". Never the value.
     */
    private static String message(JsonMappingException mapping, String lastField) {
        if (mapping instanceof MismatchedInputException mismatched && mismatched.getTargetType() != null
                && Temporal.class.isAssignableFrom(mismatched.getTargetType())) {
            return INVALID_DATE;
        }
        return RELOAD_FIELDS.contains(lastField) ? RELOAD_PAGE : INVALID_OPTION;
    }

    private static boolean causedByUnknownField(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof PharmacyStrictJsonModule.UnknownFieldException) {
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
