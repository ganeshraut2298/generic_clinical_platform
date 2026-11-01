package com.sahaj.platform.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts {@code requestId} and {@code tenantId} into the logging context for the request and
 * returns the request id in {@code X-Request-Id}. The id is always generated here (a client
 * supplied value is not trusted); the tenant comes from the authenticated principal only.
 */
public class RequestLogContextFilter extends OncePerRequestFilter {

    static final String REQUEST_ID = "requestId";
    static final String TENANT_ID = "tenantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        CurrentTenant.principal().ifPresent(p -> MDC.put(TENANT_ID, p.tenantId().toString()));
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
            MDC.remove(TENANT_ID);
        }
    }
}
