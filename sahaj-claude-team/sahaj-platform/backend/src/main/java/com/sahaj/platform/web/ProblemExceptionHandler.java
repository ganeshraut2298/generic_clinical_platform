package com.sahaj.platform.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.StaffPinException;
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
import java.util.Set;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;
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

    /**
     * Release audit G-12 and DEC-025: the JSON body fields whose contract text for an unreadable value is "Reload the
     * page and try again." (a client key, version, time or token the page itself sends, which a person cannot fix by
     * choosing another option). Every other unreadable field keeps {@link #INVALID_OPTION}. The query parameters
     * limit and cursor of the same text are read as strings and checked in the services, so they never reach here.
     */
    private static final String RELOAD_PAGE = "Reload the page and try again.";
    private static final Set<String> RELOAD_PAGE_FIELDS = Set.of("seenThrough", "clientKey", "identityUnknown",
            "version", "seniorCalls", "slotAt", "checkToken", "outcome");

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
                errors.add(new Problems.FieldError(field, unreadableMessage(field)));
            }
        }
        ProblemDetail problem = errors.isEmpty()
                ? Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Request could not be read",
                        "The request body is missing or is not valid JSON.")
                : Problems.validation(errors);
        return respond(problem, headers);
    }

    /** The text for an unreadable JSON value of {@code field} (a dotted path); the last name decides. */
    static String unreadableMessage(String field) {
        String last = field.substring(field.lastIndexOf('.') + 1);
        return RELOAD_PAGE_FIELDS.contains(last) ? RELOAD_PAGE : INVALID_OPTION;
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

    /** SAH-20: the clinic has not switched this add-on module on. */
    @ExceptionHandler(ModuleOffException.class)
    ResponseEntity<Object> handleModuleOff(ModuleOffException ex) {
        return respond(Problems.of(HttpStatus.FORBIDDEN, "module-off", "Module is off", ex.getMessage()),
                new HttpHeaders());
    }

    /** SAH-26: a business rule the signed-in person does not meet (for example owner approval for a dearer bed). */
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Object> handleForbidden(ForbiddenException ex, WebRequest request) {
        logRefused(ex.problemType(), request);
        return respond(Problems.of(HttpStatus.FORBIDDEN, ex.problemType(), ex.title(), ex.getMessage()),
                new HttpHeaders());
    }

    /** SAH-31 (ADR-010 D11): wrong-pin, pin-locked, pin-not-set, rate-limit, pin-unavailable. Fixed texts, no PIN. */
    @ExceptionHandler(StaffPinException.class)
    ResponseEntity<Object> handleStaffPin(StaffPinException ex) {
        ProblemDetail problem = Problems.of(HttpStatusCode.valueOf(ex.status()), ex.slug(), ex.title(),
                ex.getMessage());
        if (ex.retryAfterSeconds() > 0) {
            problem.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        }
        return respond(problem, new HttpHeaders());
    }

    /** SAH-7: a rule broken by a well formed request. The message is fixed text written by us, never the submitted value. */
    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<Object> handleInvalidInput(InvalidInputException ex) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                ex.getMessage());
        problem.setProperty("fieldErrors", List.of(new Problems.FieldError(ex.field(), ex.getMessage())));
        return respond(problem, new HttpHeaders());
    }

    /** SAH-28: several field errors together. The messages are fixed text written by us, never the submitted value. */
    @ExceptionHandler(InvalidFieldsException.class)
    ResponseEntity<Object> handleInvalidFields(InvalidFieldsException ex) {
        List<Problems.FieldError> errors = new ArrayList<>();
        ex.errors().forEach(error -> errors.add(new Problems.FieldError(error.field(), error.message())));
        ProblemDetail problem = Problems.validation(errors);
        if (ex.firstMessageAsDetail() && !errors.isEmpty()) {
            problem.setDetail(errors.get(0).message());
        }
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
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        boolean anonymous = SecurityContextHolder.getContext().getAuthentication() == null
                || SecurityContextHolder.getContext().getAuthentication() instanceof AnonymousAuthenticationToken;
        if (!anonymous) {
            logRefused("access-denied", request);
        }
        return respond(anonymous ? Problems.unauthorized() : Problems.forbidden(), new HttpHeaders());
    }

    /**
     * Security review L2 (OWASP ASVS V7.2): a refused access is logged with who was refused and where. Only the
     * problem type, the clinic and user ids and the controller method name are written: never the exception
     * message, the request path or any id from it, and nothing about a patient.
     */
    private static void logRefused(String type, WebRequest request) {
        String tenantId = "-";
        String userId = "-";
        try {
            ClinicUser user = ClinicUser.current();
            tenantId = String.valueOf(user.tenantId());
            userId = String.valueOf(user.userId());
        } catch (AuthenticationException notAClinicUser) {
            // an admin or system caller: the ids stay "-"
        }
        log.warn("Access refused type={} tenantId={} userId={} handler={}", type, tenantId, userId,
                handlerName(request));
    }

    private static String handlerName(WebRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (handler instanceof HandlerMethod method) {
            return method.getBeanType().getSimpleName() + "." + method.getMethod().getName();
        }
        return "-";
    }

    /** DEC-054: seconds a client is told to wait after a database timeout. */
    static final long BUSY_RETRY_AFTER_SECONDS = 5;

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (DatabaseBusy.isBusy(ex)) {
            // a bounded wait that ended (lock timeout, statement timeout, no free connection): 503, never the 500 below
            log.warn("Database busy kind={} type={} rootCause={} sqlState={} at={}", DatabaseBusy.kind(ex),
                    ex.getClass().getName(), root.getClass().getName(), sqlState(root), firstSahajFrame(ex));
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(BUSY_RETRY_AFTER_SECONDS));
            return respond(Problems.busy(BUSY_RETRY_AFTER_SECONDS), headers);
        }
        log.error("Unhandled error type={} rootCause={} sqlState={} dbError={} at={}", ex.getClass().getName(),
                root.getClass().getName(), sqlState(root), primaryDbMessage(root), firstSahajFrame(ex));
        return respond(Problems.internal(), new HttpHeaders());
    }

    private static String sqlState(Throwable root) {
        return root instanceof java.sql.SQLException sql ? sql.getSQLState() : "-";
    }

    private static final java.util.Set<String> IDENTIFIER_WORDS =
            java.util.Set.of("relation", "column", "constraint", "table", "schema", "index");
    private static final java.util.regex.Pattern SEVERITY_PREFIX = java.util.regex.Pattern.compile("^[A-Z]{4,7}:");

    /**
     * Only the first line of a database error, with every submitted value cut off (release audit G-13). Operators
     * still need the table and constraint names, so a double-quoted identifier is kept when the word before it is
     * relation, column, constraint, table, schema or index (for example
     * {@code duplicate key value violates unique constraint "ux_mobile"}). Anything else stops the line: a quoted text
     * after any other word (PostgreSQL writes {@code invalid input syntax for type uuid: "the-submitted-text"}), any
     * single-quoted text, and the first colon after the severity prefix, because the pgjdbc wordings write the value
     * after a colon without quotes ({@code Bad value for type int : 9000000001},
     * {@code Conversion to type X failed: <value>}, {@code Cannot cast to <type>: <value>}). The first line is also
     * cut at "Detail" or "Key (" (they quote the row's values). What is left names the kind of fault, never a value.
     */
    static String primaryDbMessage(Throwable root) {
        if (!(root instanceof java.sql.SQLException) || root.getMessage() == null) {
            return "-";
        }
        String message = root.getMessage();
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        for (String marker : new String[] {"Detail", "Key ("}) {
            int at = line.indexOf(marker);
            if (at >= 0) {
                line = line.substring(0, at);
            }
        }
        StringBuilder safe = new StringBuilder();
        int i = 0;
        java.util.regex.Matcher prefix = SEVERITY_PREFIX.matcher(line);
        if (prefix.find()) {
            safe.append(line, 0, prefix.end());
            i = prefix.end();
        }
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ':' || c == '\'') {
                break;
            }
            if (c == '"') {
                int close = line.indexOf('"', i + 1);
                if (close < 0 || !IDENTIFIER_WORDS.contains(lastWord(safe))) {
                    break;
                }
                safe.append(line, i, close + 1);
                i = close + 1;
                continue;
            }
            safe.append(c);
            i++;
        }
        String result = safe.toString().trim();
        if (result.endsWith(":")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result.isEmpty() ? "-" : result;
    }

    private static String lastWord(CharSequence text) {
        String trimmed = text.toString().stripTrailing();
        int space = trimmed.lastIndexOf(' ');
        return trimmed.substring(space + 1).toLowerCase(java.util.Locale.ROOT);
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
