package com.sahaj.platform.devtools.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** ADR-008 D3 and R1, checks G1 to G5 and G7, one at a time and together. Anything doubtful is refused. */
class DevSampleAdminGateTest {

    /** Sixteen 0x5c bytes: thirty-two times "5c". */
    private static final String NONCE = "5c".repeat(16);
    private static final DevSampleAdminNonce NONCE_BEAN = new DevSampleAdminNonce(new SecureRandom() {
        private static final long serialVersionUID = 1L;

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) 0x5c);
        }
    });

    private final DevSampleAdminGate gate = new DevSampleAdminGate(NONCE_BEAN);

    /** A request that passes every check: what curl sends from the developer's machine. */
    private static MockHttpServletRequest curl() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", DevSampleAdminGate.PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:8080");
        request.addHeader("User-Agent", "curl/8.4.0");
        request.addHeader("Accept", "*/*");
        request.addHeader("X-Sahaj-Dev-Nonce", NONCE);
        return request;
    }

    @Test
    void aPlainCurlFromLoopbackPasses() {
        assertThat(gate.permits(curl())).isTrue();
    }

    @Test
    void loopbackInIpv4Ipv6AndMappedFormsPasses() {
        for (String address : new String[] {"127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"}) {
            MockHttpServletRequest request = curl();
            request.setRemoteAddr(address);
            assertThat(gate.permits(request)).as(address).isTrue();
        }
    }

    @Test
    void g1OnlyPostOnTheExactPath() {
        for (String method : new String[] {"GET", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH", "post"}) {
            MockHttpServletRequest request = curl();
            request.setMethod(method);
            assertThat(gate.permits(request)).as(method).isFalse();
        }
        for (String path : new String[] {"/api/v1/admin/dev/sample-admin/", "/API/V1/ADMIN/DEV/SAMPLE-ADMIN",
                "/api/v1/admin/dev", "/api/v1/admin/dev/sample-admin2", "/api/v1/admin/dev/x",
                "/api/v1/admin/auth/login", "/api/v1/admin/dev/sample-admin/x"}) {
            MockHttpServletRequest request = curl();
            request.setRequestURI(path);
            assertThat(gate.permits(request)).as(path).isFalse();
        }
    }

    @Test
    void g2OnlyLoopbackCallersPass() {
        for (String address : new String[] {"10.0.0.5", "192.168.1.20", "2001:db8::1", "::ffff:10.0.0.5",
                "0.0.0.0", "203.0.113.9", "localhost", "", "not an address"}) {
            MockHttpServletRequest request = curl();
            request.setRemoteAddr(address);
            assertThat(gate.permits(request)).as(address).isFalse();
        }
    }

    @Test
    void g2ANullRemoteAddressFailsClosed() {
        HttpServletRequest request = new MockHttpServletRequest("POST", DevSampleAdminGate.PATH) {
            @Override
            public String getRemoteAddr() {
                return null;
            }
        };
        assertThat(gate.permits(request)).isFalse();
    }

    @Test
    void g3EveryForwardingHeaderRefusesEvenFromLoopbackAndWhateverItsValue() {
        for (String header : DevSampleAdminGate.FORWARDING_HEADERS) {
            for (String value : new String[] {"127.0.0.1", "", "for=127.0.0.1", "anything"}) {
                MockHttpServletRequest request = curl();
                request.addHeader(header, value);
                assertThat(gate.permits(request)).as(header + ": " + value).isFalse();
            }
            MockHttpServletRequest lower = curl();
            lower.addHeader(header.toLowerCase(), "x");
            assertThat(gate.permits(lower)).as(header + " lower case").isFalse();
        }
    }

    @Test
    void g3TheListHasTheSixteenHeadersOfTheAdr() {
        assertThat(DevSampleAdminGate.FORWARDING_HEADERS).hasSize(16).containsExactlyInAnyOrder("Forwarded",
                "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix",
                "X-Forwarded-Server", "X-Original-Forwarded-For", "X-Real-IP", "X-Client-IP", "Client-IP",
                "True-Client-IP", "CF-Connecting-IP", "Fastly-Client-IP", "X-Cluster-Client-IP", "Via");
    }

    @Test
    void g3EachOfTheEightAddedHeadersAloneRefusesInUpperAndLowerCaseEvenWithTheRightNonce() {
        for (String header : new String[] {"X-Forwarded-Server", "X-Original-Forwarded-For", "X-Client-IP",
            "Client-IP", "True-Client-IP", "CF-Connecting-IP", "Fastly-Client-IP", "X-Cluster-Client-IP"}) {
            for (String name : new String[] {header, header.toUpperCase(), header.toLowerCase()}) {
                MockHttpServletRequest request = curl();
                request.addHeader(name, "203.0.113.9");
                assertThat(gate.permits(request)).as(name).isFalse();
            }
        }
    }

    @Test
    void g3AForwardedHeaderDoesNotMakeAForeignCallerLookLocal() {
        MockHttpServletRequest request = curl();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(gate.permits(request)).isFalse();
    }

    @Test
    void g4OnlyLocalHostNamesPassWithOrWithoutAPort() {
        for (String host : new String[] {"127.0.0.1", "127.0.0.1:8080", "localhost", "LOCALHOST:8080", "localhost:80",
                "[::1]", "[::1]:8080"}) {
            MockHttpServletRequest request = curl();
            request.removeHeader("Host");
            request.addHeader("Host", host);
            assertThat(gate.permits(request)).as(host).isTrue();
        }
        for (String host : new String[] {"evil.example", "evil.example:8080", "localhost.evil.example", "127.0.0.2",
                "10.0.0.5:8080", "localhost:", "localhost:80a", "localhost:123456", "[::1", "[::1]x", "[::2]",
                "::1", "0.0.0.0", "", " "}) {
            MockHttpServletRequest request = curl();
            request.removeHeader("Host");
            request.addHeader("Host", host);
            assertThat(gate.permits(request)).as("host [" + host + "]").isFalse();
        }
    }

    @Test
    void g4AMissingOrRepeatedHostRefuses() {
        MockHttpServletRequest missing = curl();
        missing.removeHeader("Host");
        assertThat(gate.permits(missing)).isFalse();

        MockHttpServletRequest twice = curl();
        twice.addHeader("Host", "localhost");
        assertThat(gate.permits(twice)).isFalse();
    }

    @Test
    void g5BrowserHeadersRefuse() {
        for (String[] header : new String[][] {{"Origin", "http://localhost:4200"}, {"Origin", "null"},
                {"Origin", ""}, {"Sec-Fetch-Site", "same-origin"}, {"Sec-Fetch-Mode", "cors"},
                {"Sec-Fetch-Dest", "empty"}, {"sec-fetch-user", "?1"}}) {
            MockHttpServletRequest request = curl();
            request.addHeader(header[0], header[1]);
            assertThat(gate.permits(request)).as(header[0] + ": " + header[1]).isFalse();
        }
    }

    // ---- G7, the nonce ----

    private static MockHttpServletRequest curlWithNonces(String... values) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", DevSampleAdminGate.PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:8080");
        for (String value : values) {
            request.addHeader("X-Sahaj-Dev-Nonce", value);
        }
        return request;
    }

    @Test
    void g7TheRightNonceAlonePasses() {
        assertThat(gate.permits(curlWithNonces(NONCE))).isTrue();
        MockHttpServletRequest lowerCaseHeaderName = new MockHttpServletRequest("POST", DevSampleAdminGate.PATH);
        lowerCaseHeaderName.setRemoteAddr("127.0.0.1");
        lowerCaseHeaderName.addHeader("Host", "localhost");
        lowerCaseHeaderName.addHeader("x-sahaj-dev-nonce", NONCE);
        assertThat(gate.permits(lowerCaseHeaderName)).isTrue();
    }

    @Test
    void g7NoHeaderEmptyShortLongWrongOrOtherCaseRefuses() {
        String[] bad = {"", " ", NONCE.substring(1), NONCE.substring(0, 31) + "d", NONCE + "5", "00".repeat(16),
            "5C".repeat(16), "5c".repeat(15) + "5C", " " + NONCE, NONCE + " ", "\t" + NONCE, NONCE + "\n",
            "x".repeat(32), "5c".repeat(15) + "5\u00e7", "\u0665c".repeat(16)};
        for (String value : bad) {
            String shown = value.codePoints().mapToObj(c -> String.format("%02x", c)).reduce("", String::concat);
            assertThat(gate.permits(curlWithNonces(value))).as("[" + shown + "]").isFalse();
        }
        assertThat(gate.permits(curlWithNonces())).as("no header").isFalse();
    }

    @Test
    void g7TwoHeadersRefuseEvenWhenOneIsRightAndInEitherOrder() {
        assertThat(gate.permits(curlWithNonces(NONCE, NONCE))).isFalse();
        assertThat(gate.permits(curlWithNonces(NONCE, "00".repeat(16)))).isFalse();
        assertThat(gate.permits(curlWithNonces("00".repeat(16), NONCE))).isFalse();
        assertThat(gate.permits(curlWithNonces(NONCE, ""))).isFalse();
    }

    @Test
    void g7ACommaSeparatedValueIsNotSplit() {
        assertThat(gate.permits(curlWithNonces(NONCE + "," + NONCE))).isFalse();
        assertThat(gate.permits(curlWithNonces("00".repeat(16) + "," + NONCE))).isFalse();
    }

    @Test
    void g7NeverMakesUpForAFailedEarlierCheck() {
        MockHttpServletRequest foreign = curl();
        foreign.setRemoteAddr("10.0.0.5");
        assertThat(gate.permits(foreign)).isFalse();
        MockHttpServletRequest wrongHost = curl();
        wrongHost.removeHeader("Host");
        wrongHost.addHeader("Host", "evil.example");
        assertThat(gate.permits(wrongHost)).isFalse();
        MockHttpServletRequest wrongPath = curl();
        wrongPath.setRequestURI("/api/v1/admin/dev/sample-admin/");
        assertThat(gate.permits(wrongPath)).isFalse();
    }

    @Test
    void anotherStartsNonceDoesNotPass() {
        DevSampleAdminGate other = new DevSampleAdminGate(DevSampleAdminNonce.generate());

        assertThat(other.permits(curl())).isFalse();
    }

    @Test
    void theNonceAndTheGatePrintNoValue() {
        assertThat(NONCE_BEAN.toString()).isEqualTo("DevSampleAdminNonce[redacted]").doesNotContain(NONCE);
        assertThat(gate.toString()).isEqualTo("DevSampleAdminGate[redacted]").doesNotContain(NONCE);
    }

    @Test
    void aNonceHas32LowerCaseHexCharactersFromSixteenRandomBytesAndIsNeverRepeated() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            String value = DevSampleAdminNonce.generate().valueForStartupNotice();
            assertThat(value).matches("[0-9a-f]{32}");
            seen.add(value);
        }
        assertThat(seen).hasSize(200);
        assertThat(DevSampleAdminNonce.BYTES).isEqualTo(16);
    }

    @Test
    void theNonceIsComparedWithAConstantTimeComparisonOnBytes() throws Exception {
        // the comparison must be MessageDigest.isEqual (constant time for equal length), not String.equals
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/devtools/java/com/sahaj/platform/"
                + "devtools/admin/DevSampleAdminNonce.java"));
        assertThat(source).contains("MessageDigest.isEqual(").doesNotContain(".equals(candidate").doesNotContain("equalsIgnoreCase");
    }

    @Test
    void anAuthorizationHeaderOrCookieDoesNotChangeTheAnswer() {
        MockHttpServletRequest request = curl();
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("Cookie", "sahaj_admin_rt=abc");
        assertThat(gate.permits(request)).isTrue();
    }
}
