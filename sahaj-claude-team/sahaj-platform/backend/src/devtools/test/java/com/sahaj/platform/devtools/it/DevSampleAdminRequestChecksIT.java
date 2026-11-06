package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * DEC-016 / ADR-008 tests T4 (16 forwarding headers), T16 (nonce matrix) and the browser/host/path refusals of T5, on
 * the running application with the endpoint ON (flag on the command line, Flyway off), over a real socket from 127.0.0.1.
 * One application is shared by the class; the last test uses up its rate limit, so it is ordered last.
 *
 * <p>The nonce is read from the one start-up WARN line, as a developer reads it from the console.
 *
 * <p>Every refusal must be the same empty 404 (byte-identical), and none may be counted by the rate limiter (D6 runs after
 * the gate); the last test proves it: after fifty refusals, five gate-passing calls still go through to the service
 * and only the sixth is answered 429. Those five calls reach the database function: with the dev-tools objects missing
 * each answers a bodyless 500, with them present the first creates the admin (201) and the others answer 404; the test
 * accepts any of these and removes what it created.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DevSampleAdminRequestChecksIT {

    /** The 16 headers of G3, in the contract's words. */
    private static final List<String> FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host",
            "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Forwarded-Server", "X-Original-Forwarded-For",
            "X-Real-IP", "X-Client-IP", "Client-IP", "True-Client-IP", "CF-Connecting-IP", "Fastly-Client-IP",
            "X-Cluster-Client-IP", "Via");

    private AppRun app;
    private String nonce;
    private String reference;

    @BeforeAll
    void start() throws IOException {
        app = AppRun.builder().start();
        nonce = app.nonceFromLog();
        assertThat(nonce).as("nonce printed once in the start-up WARN line").matches("[0-9a-f]{32}");
        // The reference 404: a request that fails the very first check.
        reference = app.request("GET", AppRun.PATH).send().assertDevNotFound().fingerprint();
    }

    @AfterAll
    void stop() {
        if (app != null) {
            app.close();
        }
    }

    private void expectRefusedLikeEveryOther(String label, RawHttp.Request request) throws IOException {
        RawHttp.Response response = request.send();
        response.assertDevNotFound();
        assertThat(response.fingerprint()).as(label + ": same bytes as the reference 404").isEqualTo(reference);
    }

    @Test
    @Order(1)
    void t4EachOfTheSixteenForwardingHeadersAloneRefusesEvenWithTheRightNonceInAnyCase() throws IOException {
        assertThat(FORWARDING_HEADERS).hasSize(16);
        for (String header : FORWARDING_HEADERS) {
            for (String name : new String[] {header, header.toUpperCase(Locale.ROOT), header.toLowerCase(Locale.ROOT)}) {
                expectRefusedLikeEveryOther("header " + name, app.devCall(nonce).header(name, "127.0.0.1"));
            }
        }
    }

    @Test
    @Order(2)
    void t4ForwardedHeaderValuesAreNeverReadAnEmptyOrLoopbackValueRefusesToo() throws IOException {
        expectRefusedLikeEveryOther("Forwarded for=127.0.0.1", app.devCall(nonce).header("Forwarded", "for=127.0.0.1"));
        expectRefusedLikeEveryOther("X-Forwarded-For 10.0.0.5", app.devCall(nonce).header("X-Forwarded-For", "10.0.0.5"));
        expectRefusedLikeEveryOther("X-Real-IP empty", app.devCall(nonce).header("X-Real-IP", ""));
    }

    @Test
    @Order(3)
    void t5BrowserHostMethodAndPathChecksRefuseEvenWithTheRightNonce() throws IOException {
        expectRefusedLikeEveryOther("Origin", app.devCall(nonce).header("Origin", "http://localhost:4200"));
        expectRefusedLikeEveryOther("Sec-Fetch-Site", app.devCall(nonce).header("Sec-Fetch-Site", "same-origin"));
        expectRefusedLikeEveryOther("Sec-Fetch-Mode", app.devCall(nonce).header("Sec-Fetch-Mode", "cors"));
        expectRefusedLikeEveryOther("Host evil.example", app.devCall(nonce).header("Host", "evil.example:8080"));
        expectRefusedLikeEveryOther("Host 192.168.1.20", app.devCall(nonce).header("Host", "192.168.1.20"));
        expectRefusedLikeEveryOther("GET", app.request("GET", AppRun.PATH).header("X-Sahaj-Dev-Nonce", nonce));
        expectRefusedLikeEveryOther("PUT", app.request("PUT", AppRun.PATH).header("X-Sahaj-Dev-Nonce", nonce));
        expectRefusedLikeEveryOther("trailing slash", app.post(AppRun.PATH + "/").header("X-Sahaj-Dev-Nonce", nonce));
        expectRefusedLikeEveryOther("upper-case path", app.post(AppRun.PATH.toUpperCase(Locale.ROOT)).header("X-Sahaj-Dev-Nonce", nonce));
        expectRefusedLikeEveryOther("sibling path", app.post("/api/v1/admin/dev/x").header("X-Sahaj-Dev-Nonce", nonce));
    }

    @Test
    @Order(4)
    void t16EveryWrongNonceIsTheSameEmptyFourOhFour() throws IOException {
        String wrong = nonce.substring(0, 31) + (nonce.charAt(31) == '0' ? '1' : '0');
        Map<String, RawHttp.Request> cases = new LinkedHashMap<>();
        cases.put("missing header", app.devCall(null));
        cases.put("empty value", app.devCall(""));
        cases.put("31 characters", app.devCall(nonce.substring(0, 31)));
        cases.put("33 characters", app.devCall(nonce + "0"));
        cases.put("wrong 32 hex characters", app.devCall(wrong));
        cases.put("right value with a space inside", app.devCall(nonce.substring(0, 5) + " " + nonce.substring(6)));
        cases.put("right value with a non-hex letter", app.devCall(nonce.substring(0, 31) + "g"));
        cases.put("right value twice (two headers)", app.devCall(nonce).header("X-Sahaj-Dev-Nonce", nonce));
        cases.put("right value then a wrong one", app.devCall(nonce).header("X-Sahaj-Dev-Nonce", wrong));
        cases.put("header name in another case, wrong value", app.post(AppRun.PATH).header("x-sahaj-dev-nonce", wrong));
        String upper = nonce.toUpperCase(Locale.ROOT);
        if (!upper.equals(nonce)) {
            cases.put("right value in capitals (no case folding)", app.devCall(upper));
        }
        cases.put("right value but a forwarding header", app.devCall(nonce).header("X-Forwarded-For", "127.0.0.1"));
        cases.put("right value but an Origin", app.devCall(nonce).header("Origin", "http://localhost:4200"));
        cases.put("right value but a foreign Host", app.devCall(nonce).header("Host", "evil.example"));
        cases.put("a bearer token and a refresh cookie do not help", app.devCall(wrong)
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x").header("Cookie", "sahaj_admin_rt=junk"));
        for (Map.Entry<String, RawHttp.Request> entry : cases.entrySet()) {
            expectRefusedLikeEveryOther(entry.getKey(), entry.getValue());
        }
    }

    @Test
    @Order(5)
    void t16RefusalsAreNeverCountedByTheRateLimitAndTheSixthPassingCallIs429() throws IOException {
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try {
            for (int i = 0; i < 50; i++) {
                expectRefusedLikeEveryOther("refusal " + i, i % 2 == 0 ? app.devCall(null) : app.devCall("f".repeat(32)));
            }
            List<Integer> passing = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                RawHttp.Response response = app.devCall(nonce).send();
                passing.add(response.status());
                assertThat(response.status()).as("passing call " + (i + 1) + " is counted but not limited")
                        .isIn(201, 404, 500);
            }
            RawHttp.Response sixth = app.devCall(nonce).send();
            assertThat(sixth.status()).as("sixth passing call (statuses of the first five: " + passing + ")").isEqualTo(429);
            assertThat(sixth.header("Retry-After")).as("Retry-After").matches("[0-9]+");
            assertThat(sixth.header("Content-Type")).contains("application/problem+json");
            assertThat(sixth.bodyText()).contains("rate-limit");
            // Refusals still answer 404, not 429, after the limit is used up (D6: the gate is first).
            expectRefusedLikeEveryOther("refusal after the limit", app.devCall(null));
        } finally {
            SampleAdminDb.cleanup(snapshot);
        }
    }
}
