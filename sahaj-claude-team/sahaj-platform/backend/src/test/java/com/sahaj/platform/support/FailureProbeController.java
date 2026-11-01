package com.sahaj.platform.support;

import com.sahaj.platform.api.NotFoundException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY controller that fails in controlled ways, to check the RFC 9457 error mapping and the
 * deny-by-default security. Pulled in with {@code @Import}; never component-scanned.
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/platform/test-failures")
public class FailureProbeController {

    /** The secret the exception message carries; tests assert it never reaches the response or the log. */
    public static final String SECRET_IN_MESSAGE = "9876543210";

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("failing row contains mobile " + SECRET_IN_MESSAGE);
    }

    @GetMapping("/missing")
    public String missing() {
        throw new NotFoundException("Record " + SECRET_IN_MESSAGE + " not found");
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('OWNER')")
    public String adminOnly() {
        return "ok";
    }

    /** A typed path variable: a non-UUID value raises a type mismatch. */
    @GetMapping("/by-id/{id}")
    public String byId(@PathVariable UUID id) {
        return id.toString();
    }

    /** A required typed query parameter: absent raises "missing", non-numeric raises a type mismatch. */
    @GetMapping("/needs-count")
    public String needsCount(@RequestParam int count) {
        return Integer.toString(count);
    }

    /** Accepts JSON only: any other content type raises "unsupported media type". */
    @PostMapping(value = "/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String jsonOnly(@RequestBody Map<String, Object> body) {
        return "ok";
    }

    @GetMapping("/open-to-any-signed-in-user")
    public String anySignedIn() {
        return "ok";
    }

    /** A domain error with its own RFC 9457 type: the handler must not rewrite it to a generic one. */
    @GetMapping("/plan-limit")
    public String planLimit() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("https://sahaj.in/problems/plan-limit"));
        problem.setTitle("Plan limit reached");
        problem.setDetail("Upgrade your plan to add more users.");
        throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }
}
