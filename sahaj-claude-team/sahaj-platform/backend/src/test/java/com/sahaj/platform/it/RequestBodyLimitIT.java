package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Release audit G-1 (DEC-025, SAH-35/36/38): the request-body size limit on the real embedded server, with a real HTTP client,
 * because MockMvc cannot send a chunked body. 65,536 bytes is the largest body a signed-in {@code /api/v1/**} endpoint reads
 * (property {@code sahaj.request-body.max-bytes}), 4,096 bytes on the public endpoints and for any request with no session
 * (fix pass: security #3); one byte more answers 413
 * {@code application/problem+json} of type {@code payload-too-large}, whether the length is declared (Content-Length) or not
 * (chunked), and the answer carries {@code X-Request-Id}. The IPD Give call and the colleague-tries "Got it" call are the
 * two signed-in operations of the audit. Test plan: docs/test-plans/SAH-35-36-38-audit.md.
 *
 * <p>This class has a context of its own (a real port is needed). The pool is cut to 3 connections and the public enquiry limit
 * raised so that neither the pool arithmetic of the suite (max_connections 100) nor the 5 per hour address limit gets in the way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "it.context=request-body-limit",
        "spring.datasource.hikari.maximum-pool-size=3",
        "sahaj.public-enquiry-limit.max-requests=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestBodyLimitIT extends AuthFlowTestSupport {

    private static final int SIGNED_IN_LIMIT = 65_536;
    private static final int PUBLIC_LIMIT = 4_096;
    private static final String TOO_LARGE_TYPE = PROBLEM_BASE + "payload-too-large";
    private static final String DOSES = "/api/v1/ipd/medicine-orders/{id}/doses";
    private static final String SEEN = "/api/v1/platform/me/pin/colleague-tries/seen";
    private static final String PUBLIC_ENQUIRY = "/api/v1/platform/public/sales-enquiries";
    private static final String PAD_MARKER = "padpadpad";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // ---------------- helpers ----------------

    /** Valid JSON of exactly {@code size} bytes: {"pad":"padpadpad..."}; the pad is not a field of any contract. */
    private static byte[] jsonOfSize(int size) {
        String head = "{\"pad\":\"";
        String tail = "\"}";
        int padLength = size - head.length() - tail.length();
        StringBuilder pad = new StringBuilder(padLength);
        while (pad.length() < padLength) {
            pad.append(PAD_MARKER);
        }
        pad.setLength(padLength);
        byte[] bytes = (head + pad + tail).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isEqualTo(size);
        return bytes;
    }

    private HttpResponse<String> send(String method, String path, byte[] body, boolean chunked, String accessToken)
            throws Exception {
        BodyPublisher publisher = chunked
                ? BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : BodyPublishers.ofByteArray(body);
        // the proof that the wire is what the test says it is: a chunked body has no declared length
        assertThat(publisher.contentLength()).isEqualTo(chunked ? -1L : (long) body.length);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, publisher);
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A real signed-in owner of a new clinic (real OTP sign-up, real JWT). */
    private String accessToken() throws Exception {
        Session session = signUp(uniqueMobile());
        assertThat(session.accessToken()).isNotBlank();
        return session.accessToken();
    }

    private void assertTooLarge(HttpResponse<String> response, String limitText, String label) throws Exception {
        assertThat(response.statusCode()).as(label).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).as(label).startsWith("application/problem+json");
        JsonNode p = JSON.readTree(response.body());
        assertThat(p.get("status").asInt()).as(label).isEqualTo(413);
        assertThat(p.get("type").asText()).as(label).isEqualTo(TOO_LARGE_TYPE);
        assertThat(p.get("title").asText()).as(label).isEqualTo("Request too large");
        assertThat(p.get("detail").asText()).as(label).isEqualTo("The request body must be " + limitText + " or smaller.");
        assertThat(response.body()).as("the body is never echoed").doesNotContain(PAD_MARKER);
        String requestId = response.headers().firstValue("X-Request-Id").orElse(null);
        assertThat(requestId).as(label + ": X-Request-Id on the 413").isNotNull();
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    private void assertReadByTheEndpoint(HttpResponse<String> response, String label) {
        assertThat(response.statusCode()).as(label + ": a body at the limit is read, not refused as too large")
                .isNotEqualTo(413);
        assertThat(response.statusCode()).as(label + " " + response.body()).isBetween(400, 499);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
    }

    private static String randomOrderPath() {
        return DOSES.replace("{id}", UUID.randomUUID().toString());
    }

    // ---------------- AC: the Give call (POST /medicine-orders/{id}/doses) ----------------

    @Test
    void theGiveCallRefusesABodyOf65537BytesWith413WhetherTheLengthIsDeclaredOrChunked() throws Exception {
        String token = accessToken();
        for (boolean chunked : new boolean[] {false, true}) {
            String how = chunked ? "chunked" : "Content-Length";
            assertTooLarge(send("POST", randomOrderPath(), jsonOfSize(SIGNED_IN_LIMIT + 1), chunked, token), "64 KB",
                    "Give, 65,537 bytes, " + how);
        }
    }

    @Test
    void theGiveCallReadsABodyOfExactly65536AndOf65535BytesWhetherTheLengthIsDeclaredOrChunked() throws Exception {
        String token = accessToken();
        for (boolean chunked : new boolean[] {false, true}) {
            for (int size : new int[] {SIGNED_IN_LIMIT, SIGNED_IN_LIMIT - 1}) {
                // a new clinic has no IPD module, so the endpoint's own guard answers module-off (403) before it reads the
                // fields; either way the answer is the endpoint's own 4xx, which proves the body was not refused as too large
                HttpResponse<String> r = send("POST", randomOrderPath(), jsonOfSize(size), chunked, token);
                assertReadByTheEndpoint(r, "Give, " + size + " bytes, " + (chunked ? "chunked" : "Content-Length"));
                assertThat(JSON.readTree(r.body()).get("type").asText())
                        .isIn(PROBLEM_BASE + "validation", PROBLEM_BASE + "module-off");
            }
        }
    }

    @Test
    void aVeryLargeGiveBodyIsRefusedToo() throws Exception {
        String token = accessToken();
        assertTooLarge(send("POST", randomOrderPath(), jsonOfSize(512 * 1024), false, token), "64 KB",
                "Give, 512 KB, Content-Length");
        assertTooLarge(send("POST", randomOrderPath(), jsonOfSize(512 * 1024), true, token), "64 KB",
                "Give, 512 KB, chunked");
    }

    // ---------------- AC: the colleague-tries "Got it" call ----------------

    @Test
    void theColleagueTriesSeenCallRefusesABodyOf65537BytesWith413AndReadsExactly65536() throws Exception {
        String token = accessToken();
        for (boolean chunked : new boolean[] {false, true}) {
            String how = chunked ? "chunked" : "Content-Length";
            assertTooLarge(send("POST", SEEN, jsonOfSize(SIGNED_IN_LIMIT + 1), chunked, token), "64 KB",
                    "seen, 65,537 bytes, " + how);
            HttpResponse<String> atLimit = send("POST", SEEN, jsonOfSize(SIGNED_IN_LIMIT), chunked, token);
            assertReadByTheEndpoint(atLimit, "seen, 65,536 bytes, " + how);
            // seenThrough is missing in the padded body: the endpoint's own 400 with the contract text
            JsonNode p = JSON.readTree(atLimit.body());
            assertThat(p.get("type").asText()).isEqualTo(PROBLEM_BASE + "validation");
            assertThat(p.get("fieldErrors").get(0).get("field").asText()).isEqualTo("seenThrough");
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).isEqualTo("Reload the page and try again.");
        }
    }

    // ---------------- the public endpoint keeps its own 4 KB ----------------

    @Test
    void thePublicEnquiryEndpointStillRefusesAboveFourKbAndReadsExactlyFourKb() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            String how = chunked ? "chunked" : "Content-Length";
            assertTooLarge(send("POST", PUBLIC_ENQUIRY, jsonOfSize(PUBLIC_LIMIT + 1), chunked, null), "4 KB",
                    "public, 4,097 bytes, " + how);
            HttpResponse<String> atLimit = send("POST", PUBLIC_ENQUIRY, jsonOfSize(PUBLIC_LIMIT), chunked, null);
            assertReadByTheEndpoint(atLimit, "public, 4,096 bytes, " + how);
            assertThat(JSON.readTree(atLimit.body()).get("type").asText()).isEqualTo(PROBLEM_BASE + "validation");
        }
    }

    @Test
    void theTwoLimitsAreIndependentAbout5000BytesIsRefusedOnThePublicEndpointAndReadOnASignedInOne() throws Exception {
        String token = accessToken();
        assertTooLarge(send("POST", PUBLIC_ENQUIRY, jsonOfSize(5_000), false, null), "4 KB", "public, 5,000 bytes");
        assertReadByTheEndpoint(send("POST", randomOrderPath(), jsonOfSize(5_000), false, token), "Give, 5,000 bytes");
        assertReadByTheEndpoint(send("POST", SEEN, jsonOfSize(5_000), true, token), "seen, 5,000 bytes chunked");
    }

    // ---------------- other methods, no session, the server stays usable ----------------

    @Test
    void aPutBodyIsLimitedToo() throws Exception {
        String token = accessToken();
        assertTooLarge(send("PUT", "/api/v1/platform/setup/steps/1", jsonOfSize(SIGNED_IN_LIMIT + 1), false, token), "64 KB",
                "PUT setup step, 65,537 bytes");
        assertTooLarge(send("PUT", "/api/v1/platform/setup/steps/1", jsonOfSize(SIGNED_IN_LIMIT + 1), true, token), "64 KB",
                "PUT setup step, 65,537 bytes, chunked");
    }

    @Test
    void anOversizedBodyWithoutASessionIsRefusedWith413AtTheFourKbCapAndASmallOneStillGets401() throws Exception {
        // the guard sits in front of the login check on purpose: an anonymous caller cannot make the server read a big body.
        // Security review #3 / code review #4: with no principal the cap is the public 4 KB, not the signed-in 64 KB, so a
        // body of 65,537 bytes is refused with the 4 KB text (before this fix pass it said 64 KB).
        assertTooLarge(send("POST", randomOrderPath(), jsonOfSize(SIGNED_IN_LIMIT + 1), false, null), "4 KB",
                "no session, 65,537 bytes");
        HttpResponse<String> small = send("POST", randomOrderPath(), jsonOfSize(100), false, null);
        assertThat(small.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(small.body()).get("type").asText()).isEqualTo(PROBLEM_BASE + "unauthorized");
    }

    @Test
    void withoutASessionANonPublicPathRefusesAbout5000BytesWith413WhetherChunkedOrDeclared() throws Exception {
        // security review #3: a chunked body with no session used to be read up to 64 KB before the 401
        for (String path : new String[] {randomOrderPath(), SEEN}) {
            for (boolean chunked : new boolean[] {true, false}) {
                String how = chunked ? "chunked" : "Content-Length";
                assertTooLarge(send("POST", path, jsonOfSize(5_000), chunked, null), "4 KB",
                        "no session, 5,000 bytes, " + how + ", " + path.replaceAll("[0-9a-f-]{36}", "{id}"));
            }
        }
        // a chunked body far above both caps is refused too, with the 4 KB text
        assertTooLarge(send("POST", randomOrderPath(), jsonOfSize(512 * 1024), true, null), "4 KB",
                "no session, 512 KB, chunked");
    }

    @Test
    void withoutASessionABodyOfExactly4096And4095BytesIsReadAndAnswered401WhetherChunkedOrDeclared() throws Exception {
        for (boolean chunked : new boolean[] {true, false}) {
            String how = chunked ? "chunked" : "Content-Length";
            for (int size : new int[] {PUBLIC_LIMIT, PUBLIC_LIMIT - 1}) {
                HttpResponse<String> r = send("POST", randomOrderPath(), jsonOfSize(size), chunked, null);
                String label = "no session, " + size + " bytes, " + how;
                assertThat(r.statusCode()).as(label + ": a body at the cap is not too large").isEqualTo(401);
                assertThat(JSON.readTree(r.body()).get("type").asText()).as(label).isEqualTo(PROBLEM_BASE + "unauthorized");
            }
            // the first byte above the cap is refused
            assertTooLarge(send("POST", randomOrderPath(), jsonOfSize(PUBLIC_LIMIT + 1), chunked, null), "4 KB",
                    "no session, 4,097 bytes, " + how);
        }
    }

    @Test
    void aSignedInCallerKeepsThe64KbCapOnTheSamePathWhereAnAnonymousOneGets4Kb() throws Exception {
        String token = accessToken();
        String path = randomOrderPath();
        assertTooLarge(send("POST", path, jsonOfSize(5_000), true, null), "4 KB", "anonymous, 5,000 bytes");
        assertReadByTheEndpoint(send("POST", path, jsonOfSize(5_000), true, token), "signed in, 5,000 bytes chunked");
        assertReadByTheEndpoint(send("POST", path, jsonOfSize(SIGNED_IN_LIMIT), true, token), "signed in, 65,536 bytes chunked");
        assertTooLarge(send("POST", path, jsonOfSize(SIGNED_IN_LIMIT + 1), true, token), "64 KB", "signed in, 65,537 bytes");
    }

    @Test
    void afterA413TheSameSignedInSessionWorksAgain() throws Exception {
        String token = accessToken();
        assertTooLarge(send("POST", SEEN, jsonOfSize(SIGNED_IN_LIMIT + 1), true, token), "64 KB", "first, too large");
        HttpResponse<String> me = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/platform/me"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/platform/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(Arrays.asList(me.body(), health.body())).noneMatch(b -> b.contains(PAD_MARKER));
    }
}
