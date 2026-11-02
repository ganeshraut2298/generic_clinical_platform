package com.sahaj.platform.web.security;

import com.sahaj.platform.infrastructure.ratelimit.ClientKey;
import com.sahaj.platform.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.web.Problems;
import com.sahaj.platform.web.SalesEnquiryController;
import com.sahaj.platform.web.SalesEnquiryRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Limits {@code POST /api/v1/platform/public/sales-enquiries} per client address. Every attempt
 * counts, valid or not, and the check runs before the body is read, so invalid requests cannot be
 * used to probe the endpoint without limit.
 *
 * <p>Unlike a filter matched on the raw request path, this runs after Spring MVC has already
 * resolved the handler that will serve the request, and it only acts when that handler is
 * {@link SalesEnquiryController#create}. A percent-encoded or otherwise disguised path
 * (for example {@code sales%2Denquiries} or {@code %73ales-enquiries}) still resolves to the same
 * handler, so it cannot skip the limit the way a raw-path string comparison could.
 *
 * <p>The client address is {@code request.getRemoteAddr()} only (see {@link ClientKey}); a
 * forged {@code X-Forwarded-For} header changes nothing.
 */
public class EnquiryRateLimitInterceptor implements HandlerInterceptor {

    private final SlidingWindowRateLimiter limiter;
    private final ProblemJsonWriter problemWriter;
    private final Method guardedMethod;

    public EnquiryRateLimitInterceptor(SlidingWindowRateLimiter limiter, ProblemJsonWriter problemWriter) {
        this.limiter = limiter;
        this.problemWriter = problemWriter;
        try {
            this.guardedMethod = SalesEnquiryController.class.getMethod("create", SalesEnquiryRequest.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("SalesEnquiryController.create is missing", ex);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod) || !guardedMethod.equals(handlerMethod.getMethod())) {
            return true;
        }
        SlidingWindowRateLimiter.Decision decision =
                limiter.tryAcquire("sales-enquiry:" + ClientKey.of(request.getRemoteAddr()));
        if (decision.allowed()) {
            return true;
        }
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        problemWriter.write(response, Problems.tooManyRequests(decision.retryAfterSeconds()));
        return false;
    }
}
