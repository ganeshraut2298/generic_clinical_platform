package com.sahaj.platform.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes an RFC 9457 problem straight to the servlet response. Used where the MVC error handler
 * is not reached: the security filters and the rate-limit filter.
 */
public class ProblemJsonWriter {

    private final ObjectMapper objectMapper;

    public ProblemJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
        response.flushBuffer();
    }
}
