package com.sahaj.ipd.web;

import com.sahaj.ipd.application.AuditDevice;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SAH-76 (ADR-027 D11): builds the audit device label once per {@code /api/v1/ipd/} request and keeps it as a request
 * attribute for {@link AuditDevice#label()}. Never refuses a request, whatever the header holds.
 */
@Component
public class AuditDeviceFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/ipd/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(AuditDevice.ATTRIBUTE,
                AuditDevice.label(request.getHeader(AuditDevice.HEADER), request.getHeader("User-Agent")));
        chain.doFilter(request, response);
    }
}
