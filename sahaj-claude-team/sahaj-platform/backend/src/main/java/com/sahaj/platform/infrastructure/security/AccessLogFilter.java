package com.sahaj.platform.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Release audit G-4 (security review S36-1): one INFO line per {@code /api/**} request, written after it completes, so
 * there is a record of who called what and how it ended. The logger is {@value #LOGGER_NAME} so operations can route and
 * retain it on its own.
 *
 * <p>The line holds the HTTP method, the path TEMPLATE Spring MVC matched (for example
 * {@code /api/v1/ipd/admissions/{id}}, never the real path, so no id sits in it), the status, the duration in
 * milliseconds, and the clinic id, user id and request id taken from the logging context (opaque UUIDs, set by
 * {@link RequestLogContextFilter}, which must run before this filter). It never holds a query string, a body, a header,
 * a name or a mobile number. A request no handler matched (a 404, one refused before it reached a controller, or one that fell
 * through to the static resource catch-all pattern {@code /**}) is logged with the path {@value #UNMATCHED}, because
 * the raw path is caller-controlled text. The admin chain also runs this filter; the admin user id is put into the
 * logging context by the admin token filter, so it is on the line (the clinic id is {@code -}).
 */
public class AccessLogFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "sahaj.access";
    static final String UNMATCHED = "unmatched";
    /** The catch-all pattern of Spring's static resource handler; a request that falls to it matched no API handler. */
    static final String STATIC_RESOURCE_PATTERN = "/**";

    private static final Logger ACCESS = LoggerFactory.getLogger(LOGGER_NAME);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getLookupPathForRequest(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            if (ACCESS.isInfoEnabled()) {
                long millis = (System.nanoTime() - started) / 1_000_000L;
                ACCESS.info("request method={} path={} status={} durationMs={} tenantId={} userId={} requestId={}",
                        request.getMethod(), pathTemplate(request), status, millis,
                        mdc(RequestLogContextFilter.TENANT_ID), mdc(RequestLogContextFilter.USER_ID),
                        mdc(RequestLogContextFilter.REQUEST_ID));
            }
        }
    }

    private static String pathTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String text && !text.isBlank() && !STATIC_RESOURCE_PATTERN.equals(text)
                ? text : UNMATCHED;
    }

    private static String mdc(String key) {
        String value = MDC.get(key);
        return value == null ? "-" : value;
    }
}
