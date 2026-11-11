package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * ADR-018 D2 request checks K1 to K6 (K7 is the database's: see BootstrapServiceTest). Each check is turned off alone
 * with every other one on, and the gate refuses anything it is in doubt about.
 */
class BootstrapGateTest {

    private final BootstrapGate gate = new BootstrapGate();

    /** What curl sends from the developer's machine: loopback, a local Host, JSON, no browser headers. */
    private static MockHttpServletRequest curl(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setContentType("application/json");
        return request;
    }

    private static MockHttpServletRequest curl() {
        return curl(BootstrapGate.UNLOCK_PATH);
    }

    /** What the browser sends through the Angular dev proxy on port 4200. */
    private static MockHttpServletRequest throughProxy(String path) {
        MockHttpServletRequest request = curl(path);
        request.removeHeader("Host");
        request.addHeader("Host", "localhost:4200");
        request.addHeader("Origin", "http://localhost:4200");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        request.addHeader("Sec-Fetch-Mode", "cors");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Forwarded-Host", "localhost:4200");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Port", "4200");
        return request;
    }

    @Test
    void bothEndpointsPassFromCurlAndFromTheDevProxy() {
        assertThat(gate.permits(curl(BootstrapGate.UNLOCK_PATH))).isTrue();
        assertThat(gate.permits(curl(BootstrapGate.REGISTER_PATH))).isTrue();
        assertThat(gate.permits(throughProxy(BootstrapGate.UNLOCK_PATH))).isTrue();
        assertThat(gate.permits(throughProxy(BootstrapGate.REGISTER_PATH))).isTrue();
    }

    // ---- K1 ----

    @Test
    void k1OnlyPostOnTheTwoExactPathsPasses() {
        for (String method : new String[] {"GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "post"}) {
            MockHttpServletRequest request = curl();
            request.setMethod(method);
            assertThat(gate.permits(request)).as(method).isFalse();
        }
        for (String path : new String[] {"/api/v1/admin/bootstrap", "/api/v1/admin/bootstrap/",
                "/api/v1/admin/bootstrap/unlock/", "/api/v1/admin/bootstrap/Unlock", "/API/V1/ADMIN/BOOTSTRAP/UNLOCK",
                "/api/v1/admin/bootstrap/other", "/api/v1/admin/bootstrap/unlock/x", "/api/v1/admin/dev/sample-admin",
                "/api/v1/admin/auth/login", "/api/v1/admin/bootstrap/unlock;x=1/../register"}) {
            assertThat(gate.permits(curl(path))).as(path).isFalse();
        }
    }

    // ---- K2 ----

    @Test
    void k2OnlyALoopbackSocketAddressPasses() {
        for (String address : new String[] {"127.0.0.1", "127.0.0.5", "127.255.255.254", "::1", "0:0:0:0:0:0:0:1",
                "::ffff:127.0.0.1"}) {
            MockHttpServletRequest request = curl();
            request.setRemoteAddr(address);
            assertThat(gate.permits(request)).as(address).isTrue();
        }
        for (String address : new String[] {"10.0.0.5", "192.168.1.20", "172.16.0.1", "128.0.0.1", "0.0.0.0",
                "2001:db8::1", "::ffff:10.0.0.5", "localhost", "", "not an address", "127.0.0.1.evil.example",
                "127.0.0.1:8080"}) {
            MockHttpServletRequest request = curl();
            request.setRemoteAddr(address);
            assertThat(gate.permits(request)).as("[" + address + "]").isFalse();
        }
    }

    @Test
    void k2ALoopbackLookingForwardedHeaderNeverMakesARemoteSocketLocal() {
        MockHttpServletRequest request = curl();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(gate.permits(request)).isFalse();
    }

    // ---- K3 ----

    @Test
    void k3EveryRefusedForwardingHeaderRefusesEvenWithALoopbackValue() {
        for (String name : BootstrapGate.REFUSED_FORWARDING_HEADERS) {
            MockHttpServletRequest request = curl();
            request.addHeader(name, "127.0.0.1");
            assertThat(gate.permits(request)).as(name).isFalse();
        }
        assertThat(BootstrapGate.REFUSED_FORWARDING_HEADERS).containsExactlyInAnyOrder("Forwarded",
                "X-Forwarded-Prefix", "X-Forwarded-Server", "X-Original-Forwarded-For", "X-Real-IP", "X-Client-IP",
                "Client-IP", "True-Client-IP", "CF-Connecting-IP", "Fastly-Client-IP", "X-Cluster-Client-IP", "Via");
    }

    @Test
    void k3XForwardedForPassesOnlyWhenEveryElementIsALoopbackLiteral() {
        for (String value : new String[] {"127.0.0.1", "::1", "127.0.0.1, ::1", "127.0.0.1,127.0.0.2", "::ffff:127.0.0.1",
                " 127.0.0.1 "}) {
            MockHttpServletRequest request = curl();
            request.addHeader("X-Forwarded-For", value);
            assertThat(gate.permits(request)).as("[" + value + "]").isTrue();
        }
        for (String value : new String[] {"203.0.113.9", "127.0.0.1, 203.0.113.9", "203.0.113.9, 127.0.0.1", "",
                " ", ",", "127.0.0.1,", ",127.0.0.1", "127.0.0.1,,::1", "localhost", "127.0.0.1:8080", "[::1]",
                "[::1]:8080", "127.0.0.1%eth0", "unknown", "for=127.0.0.1", "127.0.0.1 127.0.0.1", "0.0.0.0", "::",
                "2001:db8::1", "999.0.0.1", "127.1", "0x7f.0.0.1", "2130706433"}) {
            MockHttpServletRequest request = curl();
            request.addHeader("X-Forwarded-For", value);
            assertThat(gate.permits(request)).as("[" + value + "]").isFalse();
        }
    }

    @Test
    void k3AllowedForwardingHeadersRefuseWhenRepeated() {
        for (String name : new String[] {"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
                "X-Forwarded-Port"}) {
            MockHttpServletRequest request = curl();
            String value = name.equals("X-Forwarded-For") ? "127.0.0.1" : name.equals("X-Forwarded-Host")
                    ? "localhost" : name.equals("X-Forwarded-Proto") ? "http" : "4200";
            request.addHeader(name, value);
            assertThat(gate.permits(request)).as(name + " once").isTrue();
            request.addHeader(name, value);
            assertThat(gate.permits(request)).as(name + " twice").isFalse();
        }
    }

    @Test
    void k3ForwardedHostProtoAndPortAreChecked() {
        assertThat(withHeader("X-Forwarded-Host", "evil.example")).isFalse();
        assertThat(withHeader("X-Forwarded-Host", "localhost:4200")).isTrue();
        assertThat(withHeader("X-Forwarded-Host", "[::1]:4200")).isTrue();
        assertThat(withHeader("X-Forwarded-Host", "localhost:99999x")).isFalse();
        assertThat(withHeader("X-Forwarded-Proto", "http")).isTrue();
        assertThat(withHeader("X-Forwarded-Proto", "HTTPS")).isTrue();
        assertThat(withHeader("X-Forwarded-Proto", "ftp")).isFalse();
        assertThat(withHeader("X-Forwarded-Proto", "http,https")).isFalse();
        assertThat(withHeader("X-Forwarded-Proto", "")).isFalse();
        assertThat(withHeader("X-Forwarded-Port", "4200")).isTrue();
        assertThat(withHeader("X-Forwarded-Port", "123456")).isFalse();
        assertThat(withHeader("X-Forwarded-Port", "80a")).isFalse();
        assertThat(withHeader("X-Forwarded-Port", "")).isFalse();
        assertThat(withHeader("X-Forwarded-Port", "-1")).isFalse();
    }

    private boolean withHeader(String name, String value) {
        MockHttpServletRequest request = curl();
        request.addHeader(name, value);
        return gate.permits(request);
    }

    // ---- K4 ----

    @Test
    void k4OnlyALocalHostPasses() {
        for (String host : new String[] {"localhost", "LOCALHOST:4200", "127.0.0.1", "127.0.0.1:8080", "[::1]",
                "[::1]:8080", "localhost:1"}) {
            assertThat(hostPasses(host)).as(host).isTrue();
        }
        for (String host : new String[] {"evil.example", "evil.example:8080", "localhost.evil.example", "127.0.0.1.nip.io",
                "127.0.0.2", "0.0.0.0", "[::2]", "::1", "localhost:", "localhost:abc", "localhost:123456",
                "", "localhost:80:80", "user@localhost", "localhost/", "192.168.1.5:8080"}) {
            assertThat(hostPasses(host)).as("[" + host + "]").isFalse();
        }
    }

    private boolean hostPasses(String host) {
        MockHttpServletRequest request = curl();
        request.removeHeader("Host");
        request.addHeader("Host", host);
        return gate.permits(request);
    }

    @Test
    void k4AMissingOrRepeatedHostRefuses() {
        MockHttpServletRequest none = curl();
        none.removeHeader("Host");
        assertThat(gate.permits(none)).isFalse();
        MockHttpServletRequest twice = curl();
        twice.addHeader("Host", "localhost");
        assertThat(gate.permits(twice)).isFalse();
    }

    // ---- K5 ----

    @Test
    void k5OriginAbsentOrExactlyALocalOriginPasses() {
        for (String origin : new String[] {"http://localhost:4200", "http://localhost", "https://localhost:4200",
                "http://127.0.0.1:4200", "http://[::1]:4200", "http://LOCALHOST:4200"}) {
            assertThat(withHeader("Origin", origin)).as(origin).isTrue();
        }
        for (String origin : new String[] {"null", "http://evil.example", "http://evil.example:4200",
                "http://localhost.evil.example", "http://localhost:4200/", "http://localhost:4200/x",
                "http://localhost:4200?x", "http://localhost:4200#x", "http://user@localhost:4200", "ftp://localhost",
                "localhost:4200", "//localhost", "http://", "https://", "", " http://localhost:4200",
                "http://localhost:4200 ", "http://localhost:abc", "file://", "http://192.168.1.5:4200"}) {
            assertThat(withHeader("Origin", origin)).as("[" + origin + "]").isFalse();
        }
    }

    @Test
    void k5ARepeatedOriginRefuses() {
        MockHttpServletRequest request = curl();
        request.addHeader("Origin", "http://localhost:4200");
        request.addHeader("Origin", "http://localhost:4200");
        assertThat(gate.permits(request)).isFalse();
    }

    @Test
    void k5SecFetchSiteAbsentOrSameOriginPasses() {
        assertThat(withHeader("Sec-Fetch-Site", "same-origin")).isTrue();
        for (String value : new String[] {"cross-site", "same-site", "none", "", "SAME-ORIGIN", "same-origin, none"}) {
            assertThat(withHeader("Sec-Fetch-Site", value)).as("[" + value + "]").isFalse();
        }
        MockHttpServletRequest twice = curl();
        twice.addHeader("Sec-Fetch-Site", "same-origin");
        twice.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(gate.permits(twice)).isFalse();
    }

    @Test
    void k5OtherSecFetchHeadersAreNotACheck() {
        assertThat(withHeader("Sec-Fetch-Mode", "cors")).isTrue();
        assertThat(withHeader("Sec-Fetch-Dest", "empty")).isTrue();
    }

    // ---- K6 ----

    @Test
    void k6OnlyApplicationJsonPasses() {
        for (String type : new String[] {"application/json", "application/json;charset=utf-8",
                "application/json; charset=UTF-8", "APPLICATION/JSON"}) {
            assertThat(withContentType(type)).as(type).isTrue();
        }
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data",
                "application/xml", "application/problem+json", "application/*", "*/*", "application/json2",
                "text/json", "garbage", "application/", "/json", "application/json, text/plain"}) {
            assertThat(withContentType(type)).as(type).isFalse();
        }
    }

    private boolean withContentType(String type) {
        MockHttpServletRequest request = curl();
        request.removeHeader("Content-Type");
        request.addHeader("Content-Type", type);
        return gate.permits(request);
    }

    @Test
    void k6AMissingOrRepeatedContentTypeRefuses() {
        MockHttpServletRequest none = curl();
        none.removeHeader("Content-Type");
        assertThat(gate.permits(none)).isFalse();
        MockHttpServletRequest twice = new MockHttpServletRequest("POST", BootstrapGate.UNLOCK_PATH) {
            @Override
            public java.util.Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Content-Type")
                        ? java.util.Collections.enumeration(List.of("application/json", "application/json"))
                        : super.getHeaders(name);
            }
        };
        twice.setRemoteAddr("127.0.0.1");
        twice.addHeader("Host", "127.0.0.1:8080");
        assertThat(gate.permits(twice)).isFalse();
    }

    // ---- fail closed ----

    @Test
    void aRuntimeErrorWhileCheckingRefuses() {
        MockHttpServletRequest broken = new MockHttpServletRequest("POST", BootstrapGate.UNLOCK_PATH) {
            @Override
            public String getRemoteAddr() {
                throw new IllegalStateException("boom");
            }
        };
        assertThat(gate.permits(broken)).isFalse();
    }

    @Test
    void aBearerTokenOrACookieIsNotACheck() {
        MockHttpServletRequest request = curl();
        request.addHeader("Authorization", "Bearer anything");
        request.setCookies(new jakarta.servlet.http.Cookie("sahaj_admin_rt", "x"));
        assertThat(gate.permits(request)).isTrue();
    }

    @Test
    void theGatePrintsNothing() {
        List<String> printed = new ArrayList<>();
        printed.add(gate.toString());
        assertThat(printed).containsExactly("BootstrapGate[]");
    }
}
