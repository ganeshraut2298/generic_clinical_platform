package com.sahaj.platform.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default error page for errors raised outside the controllers (a request
 * the servlet container or the firewall rejects). It returns the same RFC 9457 body as everything
 * else and never echoes the cause.
 */
@RestController
public class ProblemErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatusCode status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (code instanceof Integer value) {
            HttpStatus resolved = HttpStatus.resolve(value);
            if (resolved != null && resolved.value() >= 400) {
                status = resolved;
            }
        }
        ProblemDetail problem = status.value() == 500 ? Problems.internal() : Problems.generic(status);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
