package com.sahaj.platform.devtools.admin;

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
 * 5 calls per 15 minutes in total (ADR-008 D6), one fixed key. It sits inside the dev security chain, so
 * only a request that already passed the gate in main code's reserved-path filter can reach it: a request
 * that fails the gate gets 404 and never counts. Over the limit answers {@code 429 rate-limit} with
 * {@code Retry-After}.
 */
final class DevSampleAdminRateLimitFilter extends OncePerRequestFilter {

    static final String KEY = "dev-sample-admin";

    private final SlidingWindowRateLimiter limiter;
    private final ProblemJsonWriter problemWriter;

    DevSampleAdminRateLimitFilter(SlidingWindowRateLimiter limiter, ProblemJsonWriter problemWriter) {
        this.limiter = limiter;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SlidingWindowRateLimiter.Decision decision = limiter.tryAcquire(KEY);
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            problemWriter.write(response, Problems.tooManyRequests(decision.retryAfterSeconds()));
            return;
        }
        chain.doFilter(request, response);
    }
}
