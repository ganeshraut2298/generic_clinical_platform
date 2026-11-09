package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.infrastructure.admin.AdminAuthentication;
import com.sahaj.platform.infrastructure.admin.AdminPrincipal;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.infrastructure.security.RequestLogContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer} admin access token (ADR-003 D3, D8). A token is accepted
 * only when it verifies with the admin key and has audience {@code sahaj-admin}, role
 * {@code PLATFORM_ADMIN}, a session id and no tenant, and when the database confirms the session on
 * every request ({@code admin_session_touch}: active admin, not revoked, within the 30-minute idle
 * and 12-hour limits). A dead session leaves the request anonymous and marks it, so the entry point
 * answers {@code 401 admin-session-ended}. A clinic token, a pre-auth token or any other string
 * simply leaves the request anonymous. The four public sign-in endpoints ignore any bearer header.
 */
class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    static final String SESSION_ENDED_ATTRIBUTE = AdminJwtAuthenticationFilter.class.getName() + ".SESSION_ENDED";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Logger log = LoggerFactory.getLogger(AdminJwtAuthenticationFilter.class);

    private final AdminTokenService tokens;
    private final AdminAuthRepository repository;
    private final ProblemJsonWriter problemWriter;

    AdminJwtAuthenticationFilter(AdminTokenService tokens, AdminAuthRepository repository,
                                 ProblemJsonWriter problemWriter) {
        this.tokens = tokens;
        this.repository = repository;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return AdminRequestPaths.isPublicPost(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            AdminTokenService.Claims claims = tokens.parse(header.substring(BEARER_PREFIX.length())).orElse(null);
            if (claims != null) {
                boolean live;
                try {
                    live = repository.sessionTouch(claims.sessionId(), claims.adminId());
                } catch (RuntimeException ex) {
                    // Fail closed. Class name only: a database message could carry a value.
                    log.error("Admin session check failed type={}", ex.getClass().getName());
                    problemWriter.write(response, AdminProblems.unavailable());
                    return;
                }
                if (live) {
                    RequestLogContextFilter.recordUser(claims.adminId());
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                            new AdminAuthentication(new AdminPrincipal(claims.adminId(), claims.sessionId())));
                } else {
                    request.setAttribute(SESSION_ENDED_ATTRIBUTE, Boolean.TRUE);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
