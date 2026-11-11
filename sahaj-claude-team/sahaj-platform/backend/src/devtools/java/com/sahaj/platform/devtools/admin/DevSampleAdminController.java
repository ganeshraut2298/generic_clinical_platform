package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.web.admin.AdminDevNotFound;
import com.sahaj.platform.web.admin.DevEndpointGate;
import com.sahaj.platform.web.admin.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/admin/dev/sample-admin} (docs/api/admin.yaml {@code devCreateSampleAdmin}, ADR-008).
 * No request body (never read), no authentication ({@link PublicEndpoint}). Calls the gate again before the
 * service (defence in depth; the reserved-path filter in main code is the first check). Every "no" is the same
 * empty 404; any other failure is a bodyless 500 and the log line carries the exception type only, because a
 * database message can carry the email. Every failure that is not already a {@link DevSampleAdminFailedException} or
 * the unavailable exception is wrapped in a message-free, cause-less {@link DevSampleAdminFailedException} here,
 * before Spring's exception resolvers see it, so their DEBUG "Resolved [...]" line prints the class name only
 * (ADR-008 R4).
 *
 * <p>The mapping is on the method with the full path, not on the class, so the ADR-003 D8 convention scan
 * (which looks at class-level admin mappings in the admin web package) is unaffected.
 */
@RestController
@ConditionalOnDevSampleAdmin
public class DevSampleAdminController {

    private static final Logger log = LoggerFactory.getLogger(DevSampleAdminController.class);

    private final DevEndpointGate gate;
    private final DevSampleAdminService service;

    /**
     * The parameter is named like the bean of {@code DevSampleAdminConfiguration}: with the bootstrap registration on there are
     * two {@code DevEndpointGate} beans, and Spring then picks the one whose bean name equals the parameter name.
     */
    DevSampleAdminController(DevEndpointGate devSampleAdminGate, DevSampleAdminService service) {
        this.gate = devSampleAdminGate;
        this.service = service;
    }

    @PostMapping(DevSampleAdminGate.PATH)
    @PublicEndpoint
    public ResponseEntity<DevSampleAdminResponse> create(HttpServletRequest request, HttpServletResponse response) {
        if (!gate.permits(request)) {
            AdminDevNotFound.write(response);
            return null;
        }
        DevSampleAdminResponse created;
        try {
            created = service.create(request.getRemoteAddr(), MDC.get("requestId"));
        } catch (DevSampleAdminUnavailableException | DevSampleAdminFailedException known) {
            throw known;
        } catch (RuntimeException unexpected) {
            // A commit-time failure of the transaction proxy, a cipher failure: nothing of it travels on.
            throw new DevSampleAdminFailedException(JdbcDevSampleAdminRepository.sqlStateOf(unexpected));
        }
        if (created == null) {
            AdminDevNotFound.write(response);
            return null;
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(created);
    }

    /** The class name, and the SQLSTATE when the failure is ours: never a message (it can carry the email). */
    private static String describe(RuntimeException failure) {
        return failure instanceof DevSampleAdminFailedException ? failure.toString()
                : failure.getClass().getSimpleName();
    }

    @ExceptionHandler(DevSampleAdminUnavailableException.class)
    void unavailable(HttpServletResponse response) {
        AdminDevNotFound.write(response);
    }

    @ExceptionHandler(RuntimeException.class)
    void failed(RuntimeException failure, HttpServletResponse response) {
        log.error("The sample admin could not be created ({})", describe(failure));
        response.reset();
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
