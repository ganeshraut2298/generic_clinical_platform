package com.sahaj.platform.web.admin;

import com.sahaj.platform.infrastructure.admin.AdminRateLimits;
import com.sahaj.platform.infrastructure.ratelimit.ClientKey;
import com.sahaj.platform.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-address limits of ADR-003 D2.5: 20 requests per 15 minutes across {@code /auth/login},
 * {@code /auth/totp/enrol} and {@code /auth/totp/verify}, and 30 on {@code /auth/refresh}. Every
 * attempt counts, valid or not, and the check runs before the body is read. The address is the
 * servlet remote address only ({@link ClientKey}); a forwarded header changes nothing.
 */
class AdminRateLimitFilter extends OncePerRequestFilter {

    private final AdminRateLimits limits;
    private final ProblemJsonWriter problemWriter;

    AdminRateLimitFilter(AdminRateLimits limits, ProblemJsonWriter problemWriter) {
        this.limits = limits;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SlidingWindowRateLimiter limiter = limiterFor(request);
        if (limiter != null) {
            String prefix = limiter == limits.refresh() ? "admin-refresh:" : "admin-signin:";
            SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(prefix + ClientKey.of(request.getRemoteAddr()));
            if (!decision.allowed()) {
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                problemWriter.write(response, Problems.tooManyRequests(decision.retryAfterSeconds()));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private SlidingWindowRateLimiter limiterFor(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        String path = AdminRequestPaths.lookupPath(request);
        if (AdminRequestPaths.REFRESH.equals(path)) {
            return limits.refresh();
        }
        if (AdminRequestPaths.LOGIN.equals(path) || AdminRequestPaths.ENROL.equals(path)
                || AdminRequestPaths.VERIFY.equals(path)) {
            return limits.signIn();
        }
        return null;
    }
}
