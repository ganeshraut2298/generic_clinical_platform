package com.sahaj.ipd.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.AuditDevice;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SAH-76 (ADR-027 D11, OQ-76-22) the device label filter: it labels IPD requests only, from the two headers, and never
 * refuses a request whatever the header holds.
 */
class AuditDeviceFilterTest {

    private static final String CHROME =
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final AuditDeviceFilter filter = new AuditDeviceFilter();

    private MockHttpServletRequest request(String uri, String device) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.addHeader("User-Agent", CHROME);
        if (device != null) {
            request.addHeader(AuditDevice.HEADER, device);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> label(MockHttpServletRequest request) {
        return (Map<String, Object>) request.getAttribute(AuditDevice.ATTRIBUTE);
    }

    @Test
    void anIpdRequestGetsItsLabelAndContinues() throws Exception {
        MockHttpServletRequest request = request("/api/v1/ipd/medicine-orders/1/doses", "abc12345-DEF");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(label(request)).containsEntry("id", "abc12345-DEF").containsEntry("agent", "Chrome");
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aMalformedOrMissingHeaderIsRecordedAsNoIdAndNeverRefused() throws Exception {
        for (String header : new String[] {null, "short", "has space in it!", "x".repeat(40)}) {
            MockHttpServletRequest request = request("/api/v1/ipd/safety", header);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(label(request).get("id")).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void otherModulesAreNotLabelled() throws Exception {
        MockHttpServletRequest request = request("/api/v1/opd/visits", "abc12345-DEF");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(AuditDevice.ATTRIBUTE)).isNull();
    }

    @Test
    void theLabelOfARequestInScopeIsTheOneTheFilterBuilt() throws Exception {
        MockHttpServletRequest request = request("/api/v1/ipd/safety", "abc12345-DEF");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(new AuditDevice().label()).containsEntry("id", "abc12345-DEF").containsEntry("agent", "Chrome");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }
}
