package com.sahaj.platform.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.application.AccessRefusedException;
import com.sahaj.platform.application.ConsentRequiredException;
import com.sahaj.platform.application.OtpInvalidException;
import com.sahaj.platform.application.OtpLockedException;
import com.sahaj.platform.application.OtpResendTooSoonException;
import com.sahaj.platform.application.PlanLimitException;
import com.sahaj.platform.application.RefreshInvalidException;
import com.sahaj.platform.application.SelectionInvalidException;
import com.sahaj.platform.application.SetupIncompleteException;
import com.sahaj.platform.application.SetupStepInvalidException;
import com.sahaj.platform.application.SlugTakenException;
import com.sahaj.platform.application.TeamConflictException;
import com.sahaj.platform.application.TeamInputInvalidException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every API error into an RFC 9457 {@code application/problem+json} body in the shape of
 * {@code docs/api/platform.yaml}. No stack trace, no exception message and no submitted value is
 * ever returned, and none is logged: an exception message can carry the offending value (a
 * database constraint error includes the failing row, a JSON error includes the text), so the log
 * line names only the exception classes.
 */
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private static final String INVALID_OPTION = "Choose a valid option";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Problems.FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.add(new Problems.FieldError(error.getField(),
                        error.getDefaultMessage() == null ? "Check this field" : error.getDefaultMessage())));
        return respond(Problems.validation(errors), headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Problems.FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    errors.add(new Problems.FieldError(field == null ? "parameter" : field,
                            error.getDefaultMessage() == null ? "Check this field" : error.getDefaultMessage())));
        });
        return respond(Problems.validation(errors), headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Problems.FieldError> errors = new ArrayList<>();
        if (ex.getCause() instanceof JsonMappingException mapping) {
            String field = mapping.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .reduce((first, second) -> first + "." + second)
                    .orElse(null);
            if (field != null) {
                errors.add(new Problems.FieldError(field, INVALID_OPTION));
            }
        }
        ProblemDetail problem = errors.isEmpty()
                ? Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Request could not be read",
                        "The request body is missing or is not valid JSON.")
                : Problems.validation(errors);
        return respond(problem, headers);
    }

    /**
     * Every standard MVC problem (type mismatch, missing parameter, 404, 405, 415 and so on) passes
     * through here. Spring fills {@code detail} with text built from the request ("Failed to convert
     * 'id' with value: '...'", "Content-Type '...' is not supported", "No static resource ..."), so
     * it is replaced: the body carries a Sahaj problem type, a standard title and a fixed sentence,
     * and nothing the caller submitted. The request path Spring puts in {@code instance} is removed
     * by {@link ProblemInstanceScrubber}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && isDefaultType(problem.getType())) {
            ProblemDetail generic = Problems.generic(statusCode);
            problem.setType(generic.getType());
            problem.setTitle(generic.getTitle());
            problem.setDetail(fixedDetail(statusCode));
        }
        return response;
    }

    /**
     * True only for the {@code about:blank} type Spring fills in by default. A problem that already
     * carries its own type (a domain error such as a plan limit, built with {@link Problems}) is left
     * alone, so its type and detail survive {@link ResponseEntityExceptionHandler}.
     */
    private static boolean isDefaultType(URI type) {
        return type == null || "about:blank".equals(type.toString());
    }

    private static String fixedDetail(HttpStatusCode status) {
        switch (status.value()) {
            case 400:
                return "The request could not be understood. Check it and try again.";
            case 404:
                return "We could not find that.";
            case 405:
                return "This action is not allowed here.";
            case 406:
            case 415:
                return "This type of content is not supported.";
            default:
                return status.is5xxServerError()
                        ? "Something went wrong on our side. Please try again."
                        : "The request could not be processed.";
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex) {
        return respond(Problems.notFound(), new HttpHeaders());
    }

    /** SAH-7: a clash with saved data (a service name or holiday date already used). */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex) {
        return respond(Problems.of(HttpStatus.CONFLICT, ex.problemType(), ex.title(), ex.getMessage()),
                new HttpHeaders());
    }

    /** SAH-7: a rule broken by a well formed request. The message is fixed text written by us, never the submitted value. */
    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<Object> handleInvalidInput(InvalidInputException ex) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                ex.getMessage());
        problem.setProperty("fieldErrors", List.of(new Problems.FieldError(ex.field(), ex.getMessage())));
        return respond(problem, new HttpHeaders());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        List<Problems.FieldError> errors = new ArrayList<>();
        ex.getConstraintViolations().forEach(violation ->
                errors.add(new Problems.FieldError(lastNode(violation.getPropertyPath()), violation.getMessage())));
        return respond(Problems.validation(errors), new HttpHeaders());
    }

    /** The last named node of a property path, e.g. {@code count} from {@code needsCount.count}. */
    private static String lastNode(Path path) {
        String field = "parameter";
        for (Path.Node node : path) {
            if (node.getName() != null) {
                field = node.getName();
            }
        }
        return field;
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
        return respond(Problems.unauthorized(), new HttpHeaders());
    }

    @ExceptionHandler(OtpLockedException.class)
    ResponseEntity<Object> handleOtpLocked(OtpLockedException ex) {
        return respond(Problems.locked("Too many wrong codes. Try again in 15 minutes.", ex.retryAfterSeconds()),
                new HttpHeaders());
    }

    @ExceptionHandler(OtpResendTooSoonException.class)
    ResponseEntity<Object> handleOtpResendTooSoon(OtpResendTooSoonException ex) {
        return respond(Problems.otpResendTooSoon(ex.retryAfterSeconds()), new HttpHeaders());
    }

    @ExceptionHandler(OtpInvalidException.class)
    ResponseEntity<Object> handleOtpInvalid(OtpInvalidException ex) {
        return respond(Problems.of(HttpStatus.UNAUTHORIZED, "otp-invalid", "That code is not right", ex.getMessage()),
                new HttpHeaders());
    }

    @ExceptionHandler(AccessRefusedException.class)
    ResponseEntity<Object> handleAccessRefused(AccessRefusedException ex) {
        return respond(Problems.of(HttpStatus.FORBIDDEN, "access-refused", "Not allowed", ex.getMessage()),
                new HttpHeaders());
    }

    @ExceptionHandler(SelectionInvalidException.class)
    ResponseEntity<Object> handleSelectionInvalid(SelectionInvalidException ex) {
        return respond(Problems.of(HttpStatus.UNAUTHORIZED, "selection-invalid", "Choose your clinic again",
                ex.getMessage()), new HttpHeaders());
    }

    @ExceptionHandler(RefreshInvalidException.class)
    ResponseEntity<Object> handleRefreshInvalid(RefreshInvalidException ex) {
        return respond(Problems.unauthorized(), new HttpHeaders());
    }

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Object> handleConsentRequired(ConsentRequiredException ex) {
        return respond(Problems.validation(
                List.of(new Problems.FieldError("consent", "Accept the Terms and Privacy to continue."))),
                new HttpHeaders());
    }

    @ExceptionHandler(PlanLimitException.class)
    ResponseEntity<Object> handlePlanLimit(PlanLimitException ex) {
        return respond(Problems.of(HttpStatus.CONFLICT, "plan-limit", "Your plan limit was reached", ex.getMessage()),
                new HttpHeaders());
    }

    @ExceptionHandler(SlugTakenException.class)
    ResponseEntity<Object> handleSlugTaken(SlugTakenException ex) {
        return respond(Problems.of(HttpStatus.CONFLICT, "slug-taken", "That web address was just taken",
                ex.getMessage()), new HttpHeaders());
    }

    @ExceptionHandler(SetupIncompleteException.class)
    ResponseEntity<Object> handleSetupIncomplete(SetupIncompleteException ex) {
        return respond(Problems.of(HttpStatus.BAD_REQUEST, "setup-incomplete", "Finish every step first",
                ex.getMessage()), new HttpHeaders());
    }

    @ExceptionHandler(SetupStepInvalidException.class)
    ResponseEntity<Object> handleSetupStepInvalid(SetupStepInvalidException ex) {
        return respond(Problems.of(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                ex.getMessage()), new HttpHeaders());
    }

    @ExceptionHandler(TeamConflictException.class)
    ResponseEntity<Object> handleTeamConflict(TeamConflictException ex) {
        ProblemDetail problem = switch (ex.kind()) {
            case LAST_OWNER -> Problems.of(HttpStatus.CONFLICT, "last-owner", "Your clinic needs an owner",
                    "Your clinic must keep at least one active owner. Make someone else an owner first.");
            case DUPLICATE_MOBILE -> Problems.of(HttpStatus.CONFLICT, "duplicate-mobile",
                    "That person is already on your team", "This mobile number is already on your team.");
            case CONCURRENT_CHANGE -> Problems.of(HttpStatus.CONFLICT, "concurrent-change",
                    "The team just changed", "Someone changed the team at the same time. Try again.");
        };
        return respond(problem, new HttpHeaders());
    }

    @ExceptionHandler(TeamInputInvalidException.class)
    ResponseEntity<Object> handleTeamInputInvalid(TeamInputInvalidException ex) {
        return respond(Problems.of(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                ex.getMessage()), new HttpHeaders());
    }

    /** A signed-out visitor is told to sign in (401); a signed-in user without permission gets 403. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        boolean anonymous = SecurityContextHolder.getContext().getAuthentication() == null
                || SecurityContextHolder.getContext().getAuthentication() instanceof AnonymousAuthenticationToken;
        return respond(anonymous ? Problems.unauthorized() : Problems.forbidden(), new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.error("Unhandled error type={} rootCause={} sqlState={} dbError={} at={}", ex.getClass().getName(),
                root.getClass().getName(), sqlState(root), primaryDbMessage(root), firstSahajFrame(ex));
        return respond(Problems.internal(), new HttpHeaders());
    }

    private static String sqlState(Throwable root) {
        return root instanceof java.sql.SQLException sql ? sql.getSQLState() : "-";
    }

    /**
     * Only the first line of a database error: the primary message ("relation x does not exist"). The
     * detail lines of a constraint error can quote the row's values, so they are never logged.
     */
    private static String primaryDbMessage(Throwable root) {
        if (!(root instanceof java.sql.SQLException) || root.getMessage() == null) {
            return "-";
        }
        String message = root.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String firstSahajFrame(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith("com.sahaj.")) {
                    return frame.getClassName() + ":" + frame.getLineNumber();
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return "-";
    }

    private static ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders headers) {
        return ResponseEntity.status(problem.getStatus())
                .headers(headers)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
