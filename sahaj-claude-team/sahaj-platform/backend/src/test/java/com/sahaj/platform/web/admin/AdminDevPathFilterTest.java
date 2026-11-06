package com.sahaj.platform.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * ADR-008 D3: the reserved path /api/v1/admin/dev answers the same empty 404 in a build without a gate bean,
 * when the gate refuses, and when the gate fails; and passes through only when a gate allows it.
 */
class AdminDevPathFilterTest {

    private static final String[] RESERVED_PATHS = {"/api/v1/admin/dev/sample-admin", "/api/v1/admin/dev",
            "/api/v1/admin/dev/x", "/api/v1/admin/dev/sample-admin/", "/API/V1/ADMIN/DEV/SAMPLE-ADMIN",
            "/api/v1/admin/Dev/x"};
    private static final String[] METHODS = {"POST", "GET", "OPTIONS", "PUT", "DELETE", "HEAD", "PATCH"};

    private static ObjectProvider<DevEndpointGate> providerOf(DevEndpointGate gate) {
        @SuppressWarnings("unchecked")
        ObjectProvider<DevEndpointGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        return provider;
    }

    /** What the caller can see: status, headers (without Date) and body bytes. */
    private record Seen(int status, Map<String, List<String>> headers, byte[] body, String contentType) {
    }

    private static final class RecordingChain extends MockFilterChain {
        private boolean reached;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            reached = true;
        }
    }

    private static Seen run(DevEndpointGate gate, String method, String path, RecordingChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Origin", "http://localhost:4200");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Authorization", "Bearer anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AdminDevPathFilter(providerOf(gate)).doFilter(request, response, chain);
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase("Date")) {
                headers.put(name.toLowerCase(), new ArrayList<>(response.getHeaders(name)));
            }
        }
        return new Seen(response.getStatus(), headers, response.getContentAsByteArray(), response.getContentType());
    }

    @Test
    void withoutAGateBeanEveryReservedPathAndMethodAnswersTheSameEmpty404() throws Exception {
        for (String path : RESERVED_PATHS) {
            for (String method : METHODS) {
                RecordingChain chain = new RecordingChain();
                Seen seen = run(null, method, path, chain);
                String label = method + " " + path;
                assertThat(seen.status()).as(label).isEqualTo(404);
                assertThat(seen.body()).as(label).isEmpty();
                assertThat(seen.contentType()).as(label).isNull();
                assertThat(seen.headers()).as(label).containsOnlyKeys("cache-control");
                assertThat(seen.headers().get("cache-control")).as(label).containsExactly("no-store");
                assertThat(chain.reached).as(label).isFalse();
            }
        }
    }

    @Test
    void aRefusingGateAnswersTheSameBytesAsNoGate() throws Exception {
        DevEndpointGate refuses = request -> false;
        Seen none = run(null, "POST", "/api/v1/admin/dev/sample-admin", new RecordingChain());
        Seen refused = run(refuses, "POST", "/api/v1/admin/dev/sample-admin", new RecordingChain());
        assertThat(refused.status()).isEqualTo(none.status());
        assertThat(refused.headers()).isEqualTo(none.headers());
        assertThat(refused.body()).isEqualTo(none.body());
    }

    @Test
    void aGateThatThrowsFailsClosed() throws Exception {
        DevEndpointGate broken = request -> {
            throw new IllegalStateException("secret detail");
        };
        RecordingChain chain = new RecordingChain();
        Seen seen = run(broken, "POST", "/api/v1/admin/dev/sample-admin", chain);
        assertThat(seen.status()).isEqualTo(404);
        assertThat(seen.body()).isEmpty();
        assertThat(chain.reached).isFalse();
    }

    @Test
    void anAllowingGateLetsTheRequestThrough() throws Exception {
        RecordingChain chain = new RecordingChain();
        Seen seen = run(request -> true, "POST", "/api/v1/admin/dev/sample-admin", chain);
        assertThat(chain.reached).isTrue();
        assertThat(seen.status()).isEqualTo(200);
    }

    @Test
    void theGateIsAskedForTheRequestItself() throws Exception {
        List<HttpServletRequest> asked = new ArrayList<>();
        run(request -> {
            asked.add(request);
            return true;
        }, "POST", "/api/v1/admin/dev/sample-admin", new RecordingChain());
        assertThat(asked).hasSize(1);
    }

    @Test
    void otherAdminAndClinicPathsAreNotTouched() throws Exception {
        String[] others = {"/api/v1/admin/auth/login", "/api/v1/admin/me", "/api/v1/admin/developers",
                "/api/v1/admin/devices", "/api/v1/platform/health", "/api/v1/admin"};
        for (String path : others) {
            RecordingChain chain = new RecordingChain();
            Seen seen = run(null, "POST", path, chain);
            assertThat(chain.reached).as(path).isTrue();
            assertThat(seen.status()).as(path).isEqualTo(200);
        }
    }

    @Test
    void aPercentEncodedPathDoesNotDodgeTheFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/%64ev/sample-admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        new AdminDevPathFilter(providerOf(null)).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.reached).isFalse();
    }

    @Test
    void the404ReplacesAnythingAlreadyOnTheResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Request-Id", "abc");
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");

        AdminDevNotFound.write(response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeaderNames()).containsExactly("Cache-Control");
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void theFilterIsRegisteredAlwaysAndBeforeSpringSecurity() {
        FilterRegistrationBean<AdminDevPathFilter> registration =
                new AdminDevPathConfig().adminDevPathFilterRegistration(providerOf(null));

        assertThat(registration.getOrder()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getFilter()).isInstanceOf(AdminDevPathFilter.class);
    }
}
