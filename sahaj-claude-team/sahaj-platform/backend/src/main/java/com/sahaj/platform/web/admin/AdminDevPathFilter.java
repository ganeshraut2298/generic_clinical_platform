package com.sahaj.platform.web.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reserves {@code /api/v1/admin/dev} and everything under it (ADR-008 D3). Runs before Spring
 * Security. Without a {@link DevEndpointGate} bean (every production build, and any build where a
 * runtime condition is false) or when the gate refuses, the request answers {@link AdminDevNotFound}
 * and goes no further, so an unauthenticated caller never sees the 401, 403 or 503 of the admin
 * chains for these paths. The match uses the decoded lookup path and ignores case, so an encoded or
 * upper-case path does not dodge it. Contains no endpoint logic.
 */
public class AdminDevPathFilter extends OncePerRequestFilter {

    /** The reserved prefix, in lower case. */
    public static final String RESERVED = AdminRequestPaths.BASE + "/dev";

    private static final Logger log = LoggerFactory.getLogger(AdminDevPathFilter.class);

    private final ObjectProvider<DevEndpointGate> gate;

    public AdminDevPathFilter(ObjectProvider<DevEndpointGate> gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isReserved(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (!permitted(request)) {
            AdminDevNotFound.write(response);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isReserved(HttpServletRequest request) {
        String path = AdminRequestPaths.lookupPath(request).toLowerCase(Locale.ROOT);
        return path.equals(RESERVED) || path.startsWith(RESERVED + "/");
    }

    private boolean permitted(HttpServletRequest request) {
        try {
            DevEndpointGate present = gate.getIfAvailable();
            return present != null && present.permits(request);
        } catch (RuntimeException ex) {
            // Fail closed. Only the exception type is logged: a message could carry request data.
            log.warn("The dev endpoint gate failed; answering 404 ({})", ex.getClass().getSimpleName());
            return false;
        }
    }
}
