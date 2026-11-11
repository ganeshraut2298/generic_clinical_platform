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
 * Reserves {@code /api/v1/admin/dev} and {@code /api/v1/admin/bootstrap} and everything under them (ADR-008 D3,
 * ADR-018 D1). Runs before Spring Security and before every filter that can answer on {@code /api/v1/admin/**}. It
 * asks every {@link DevEndpointGate} bean and continues only when one of them permits (each gate refuses the paths it
 * does not own). Without a gate bean (every production build, and any build where a runtime condition is false), when
 * all gates refuse, or when any gate fails, the request answers {@link AdminDevNotFound} and goes no further, so an
 * unauthenticated caller never sees the 401, 403 or 503 of the admin chains for these paths. The match uses the decoded
 * lookup path and ignores case, so an encoded or upper-case path does not dodge it. Contains no endpoint logic.
 */
public class AdminDevPathFilter extends OncePerRequestFilter {

    /** The reserved prefix of the dev-only sample admin, in lower case. */
    public static final String RESERVED = AdminRequestPaths.BASE + "/dev";

    /** The reserved prefix of the dev-only bootstrap registration (ADR-018), in lower case. */
    public static final String RESERVED_BOOTSTRAP = AdminRequestPaths.BASE + "/bootstrap";

    private static final Logger log = LoggerFactory.getLogger(AdminDevPathFilter.class);

    private final ObjectProvider<DevEndpointGate> gates;

    public AdminDevPathFilter(ObjectProvider<DevEndpointGate> gates) {
        this.gates = gates;
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
        return under(path, RESERVED) || under(path, RESERVED_BOOTSTRAP);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private boolean permitted(HttpServletRequest request) {
        try {
            return gates.orderedStream().anyMatch(gate -> gate.permits(request));
        } catch (RuntimeException ex) {
            // Fail closed. Only the exception type is logged: a message could carry request data.
            log.warn("The dev endpoint gate failed; answering 404 ({})", ex.getClass().getSimpleName());
            return false;
        }
    }
}
