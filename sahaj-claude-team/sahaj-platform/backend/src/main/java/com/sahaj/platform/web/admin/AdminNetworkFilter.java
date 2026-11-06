package com.sahaj.platform.web.admin;

import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every {@code /api/v1/admin/**} request from an address outside the allow-list answers
 * {@code 403 admin-network-denied} (ADR-003 D2.8). Runs before the rate limits and the token check,
 * so a denied address learns nothing and costs no database call. Preflight requests are not exempt
 * here: the browser's CORS filter answers them earlier in the chain.
 */
public class AdminNetworkFilter extends OncePerRequestFilter {

    private final AdminNetworkPolicy policy;
    private final ProblemJsonWriter problemWriter;

    public AdminNetworkFilter(AdminNetworkPolicy policy, ProblemJsonWriter problemWriter) {
        this.policy = policy;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!policy.permits(request.getRemoteAddr())) {
            problemWriter.write(response, AdminProblems.networkDenied());
            return;
        }
        chain.doFilter(request, response);
    }
}
