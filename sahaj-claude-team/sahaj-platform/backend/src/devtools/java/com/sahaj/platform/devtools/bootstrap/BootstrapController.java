package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.web.KeepProblemInstance;
import com.sahaj.platform.web.Problems;
import com.sahaj.platform.web.admin.AdminDevNotFound;
import com.sahaj.platform.web.admin.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/bootstrap/unlock} and {@code .../register} (docs/api/admin.yaml {@code bootstrapUnlock},
 * {@code bootstrapRegister}; ADR-018). No authentication ({@link PublicEndpoint}); the reserved-path filter in main code
 * and this controller both ask the gate (defence in depth).
 *
 * <p>Every "no" about reach, switch or closed registration is the same empty 404. Request bodies are read here with a
 * strict mapper ({@link BootstrapBodies}) and never logged; no request or response body is logged on this path, the
 * only log lines are the three allowed by ADR-018 D8 (wrong PIN count, lock, admin created) and they hold no value. Any
 * other failure is wrapped in a message-free, cause-less {@link BootstrapExceptions.Failed} before Spring's exception
 * resolvers see it, and answers a bodyless 500 with the SQLSTATE class only in the log (ADR-008 R4).
 *
 * <p>The mappings are on the methods with the full path, not on the class, so the ADR-003 D8 convention scan (which
 * looks at class-level admin mappings in the admin web package) is unaffected.
 */
@RestController
@ConditionalOnAdminBootstrap
@KeepProblemInstance
public class BootstrapController {

    private static final Logger log = LoggerFactory.getLogger(BootstrapController.class);

    private final BootstrapGate gate;
    private final BootstrapService service;
    private final boolean secureCookie;

    BootstrapController(BootstrapGate gate, BootstrapService service,
                        @Value("${sahaj.auth.refresh-cookie-secure:true}") boolean secureCookie) {
        this.gate = gate;
        this.service = service;
        this.secureCookie = secureCookie;
    }

    @PostMapping(BootstrapGate.UNLOCK_PATH)
    @PublicEndpoint
    public ResponseEntity<Object> unlock(HttpServletRequest request, HttpServletResponse response) {
        if (!gate.permits(request)) {
            AdminDevNotFound.write(response);
            return null;
        }
        BootstrapService.UnlockOutcome outcome;
        try {
            outcome = service.unlock(BootstrapBodies.readUnlock(bodyOf(request)), request.getRemoteAddr(),
                    MDC.get("requestId"));
        } catch (BootstrapExceptions.Closed closed) {
            AdminDevNotFound.write(response);
            return null;
        } catch (BootstrapExceptions.Busy busy) {
            return busyAnswer(BootstrapGate.UNLOCK_PATH);
        } catch (BootstrapExceptions.Failed failed) {
            throw failed;
        } catch (RuntimeException unexpected) {
            throw new BootstrapExceptions.Failed(JdbcBootstrapRepository.sqlStateOf(unexpected));
        }
        switch (outcome.kind()) {
            case CLOSED:
                AdminDevNotFound.write(response);
                return null;
            case LOCKED:
                // A lock already in force: no log line (ADR-018 D8, Revision 2), so a local loop cannot fill the log.
                return lockedAnswer(BootstrapGate.UNLOCK_PATH, outcome.retryAfterSeconds());
            case LOCKED_NOW:
                // The 5th wrong PIN, after the commit: the one lock line, with the end time and no other value.
                log.warn("Bootstrap registration locked until {}", outcome.lockedUntil());
                return lockedAnswer(BootstrapGate.UNLOCK_PATH, outcome.retryAfterSeconds());
            case BAD_REQUEST:
                return problem(BootstrapGate.UNLOCK_PATH, badRequest(), null);
            case INVALID_BODY:
                return problem(BootstrapGate.UNLOCK_PATH,
                        Problems.validation(List.of(new Problems.FieldError("pin", "Enter the PIN."))), null);
            case WRONG_PIN:
                log.info("Bootstrap registration: wrong PIN ({} of {})",
                        BootstrapService.WRONG_PINS_BEFORE_LOCK - outcome.triesLeft(),
                        BootstrapService.WRONG_PINS_BEFORE_LOCK);
                ProblemDetail wrong = Problems.of(HttpStatus.UNAUTHORIZED, "bootstrap-pin-invalid",
                        "That PIN is not right.", null);
                wrong.setProperty("triesLeft", outcome.triesLeft());
                return problem(BootstrapGate.UNLOCK_PATH, wrong, null);
            case UNLOCKED:
                BootstrapCookie.write(response, outcome.binding(), secureCookie);
                return ok(HttpStatus.OK, new BootstrapUnlocked(outcome.ticket(), BootstrapService.ticketSeconds()));
            default:
                throw new BootstrapExceptions.Failed(null);
        }
    }

    @PostMapping(BootstrapGate.REGISTER_PATH)
    @PublicEndpoint
    public ResponseEntity<Object> register(HttpServletRequest request, HttpServletResponse response) {
        if (!gate.permits(request)) {
            AdminDevNotFound.write(response);
            return null;
        }
        BootstrapService.RegisterOutcome outcome;
        try {
            outcome = service.register(BootstrapBodies.readRegister(bodyOf(request)), BootstrapCookie.read(request),
                    request.getRemoteAddr(), MDC.get("requestId"));
        } catch (BootstrapExceptions.Closed closed) {
            AdminDevNotFound.write(response);
            return null;
        } catch (BootstrapExceptions.Busy busy) {
            return busyAnswer(BootstrapGate.REGISTER_PATH);
        } catch (BootstrapExceptions.Failed failed) {
            throw failed;
        } catch (RuntimeException unexpected) {
            throw new BootstrapExceptions.Failed(JdbcBootstrapRepository.sqlStateOf(unexpected));
        }
        switch (outcome.kind()) {
            case CLOSED:
                AdminDevNotFound.write(response);
                return null;
            case LOCKED:
                return lockedAnswer(BootstrapGate.REGISTER_PATH, outcome.retryAfterSeconds());
            case TICKET_INVALID:
                BootstrapCookie.clear(response, secureCookie);
                return problem(BootstrapGate.REGISTER_PATH, Problems.of(HttpStatus.UNAUTHORIZED, "bootstrap-ticket-invalid",
                        "Your unlock took too long. Enter the PIN again.", null), null);
            case BAD_REQUEST:
                return problem(BootstrapGate.REGISTER_PATH, badRequest(), null);
            case INVALID_FORM:
                return problem(BootstrapGate.REGISTER_PATH, Problems.validation(outcome.fieldErrors()), null);
            case CREATED:
                log.info("Bootstrap registration: admin created adminId={}", outcome.adminId());
                BootstrapCookie.clear(response, secureCookie);
                // 201 with no body, no Content-Type and no Location; no sign-in token (ADR-018 Revision 5).
                return ResponseEntity.status(HttpStatus.CREATED)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .header("X-Content-Type-Options", "nosniff")
                        .build();
            default:
                throw new BootstrapExceptions.Failed(null);
        }
    }

    // ------------------------------------------------------------------ answers

    private static InputStream bodyOf(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (IOException ex) {
            return InputStream.nullInputStream();
        }
    }

    private static ResponseEntity<Object> ok(HttpStatus status, Object body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Every bootstrap problem sets {@code instance} to the operation's own fixed path (ADR-018 D2, Revision 3), so Spring
     * never fills it with the raw request URI (which can carry caller-chosen bytes).
     */
    private static ResponseEntity<Object> problem(String path, ProblemDetail problem, Long retryAfterSeconds) {
        problem.setInstance(URI.create(path));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        return builder.body(problem);
    }

    /**
     * An unreadable body (ADR-018 D2 "Request bodies"): the shared fixed text, no field errors, and nothing of the body.
     */
    private static ProblemDetail badRequest() {
        ProblemDetail problem = Problems.generic(HttpStatus.BAD_REQUEST);
        problem.setDetail("The request could not be understood. Check it and try again.");
        return problem;
    }

    private static ResponseEntity<Object> lockedAnswer(String path, long retryAfterSeconds) {
        ProblemDetail locked = Problems.of(HttpStatus.LOCKED, "bootstrap-locked", "Registration is locked.", null);
        locked.setProperty("retryAfterSeconds", retryAfterSeconds);
        return problem(path, locked, retryAfterSeconds);
    }

    /** The wait for the database turn was over 10 seconds: nothing was counted, created or spent. */
    private static ResponseEntity<Object> busyAnswer(String path) {
        ProblemDetail busy = Problems.tooManyRequests(5);
        return problem(path, busy, 5L);
    }

    @ExceptionHandler(RuntimeException.class)
    void failed(RuntimeException failure, HttpServletResponse response) {
        log.error("Bootstrap registration failed ({})", describe(failure));
        response.reset();
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    /** The class name, and the SQLSTATE when the failure is ours: never a message. */
    private static String describe(RuntimeException failure) {
        return failure instanceof BootstrapExceptions.Failed ? failure.toString() : failure.getClass().getSimpleName();
    }
}
