package com.sahaj.platform.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter =
            new RequestBodySizeLimitFilter(new ProblemJsonWriter(new ObjectMapper()));

    @Test
    void ignoresPathsOutsideThePublicPrefix() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/platform/me");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void guardsThePublicPrefix() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void rejectsADeclaredContentLengthOverTheLimitWithoutRunningTheChain() throws Exception {
        byte[] body = "a".repeat((int) RequestBodySizeLimitFilter.MAX_BODY_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        // setContent also fixes the declared Content-Length, which the filter checks first.
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new AssertionError("the chain must not run for an oversized body");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void letsAnOrdinarySmallBodyThrough() throws Exception {
        byte[] body = "{\"name\":\"Asha\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/public/sales-enquiries");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[][] seenByChain = new byte[1][];
        FilterChain chain = (req, res) -> seenByChain[0] = StreamUtils.copyToByteArray(req.getInputStream());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenByChain[0]).isEqualTo(body);
    }
}
