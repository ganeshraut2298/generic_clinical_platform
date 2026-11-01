package com.sahaj.platform.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.application.SalesEnquiryService;
import com.sahaj.platform.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import com.sahaj.platform.web.SalesEnquiryController;
import com.sahaj.platform.web.SalesEnquiryRequest;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class EnquiryRateLimitInterceptorTest {

    private final SlidingWindowRateLimiter limiter =
            new SlidingWindowRateLimiter(1, Duration.ofHours(1), 100, Clock.systemUTC());
    private final EnquiryRateLimitInterceptor interceptor =
            new EnquiryRateLimitInterceptor(limiter, new ProblemJsonWriter(new ObjectMapper()));

    private static HandlerMethod guardedHandlerMethod() throws NoSuchMethodException {
        SalesEnquiryController controller = new SalesEnquiryController(Mockito.mock(SalesEnquiryService.class));
        return new HandlerMethod(controller, "create", SalesEnquiryRequest.class);
    }

    @Test
    void ignoresAHandlerThatIsNotTheGuardedController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/platform/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, "not-a-handler-method");

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsTheFirstRequestThenBlocksTheNextFromTheSameAddress() throws Exception {
        HandlerMethod handler = guardedHandlerMethod();
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        first.setRemoteAddr("203.0.113.9");

        assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), handler)).isTrue();

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        second.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(second, secondResponse, handler);

        assertThat(allowed).isFalse();
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isNotNull();
        assertThat(secondResponse.getContentType()).startsWith("application/problem+json");
    }
}
