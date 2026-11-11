package com.sahaj.platform.devtools.it;

import static com.sahaj.platform.devtools.it.BootstrapSupport.REGISTER;
import static com.sahaj.platform.devtools.it.BootstrapSupport.UNLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * SAH-42 request checks K1 to K6 of ADR-018 D2 (K7, "no admin exists", is in {@code BootstrapUnlockIT}), the 413 cap and the
 * message-free 500, on the running application with the registration ON (Flyway off), over a real socket from 127.0.0.1.
 * One application is shared by the class.
 *
 * <p>Every refusal must be the same empty 404, byte for byte the same as every other dev-only path that is off. A request that
 * passes the gate is recognised by "not 404": it reaches the database call, so the answer is {@code 400} (blank PIN, the
 * functions exist) or a bodyless {@code 500} (the functions are not there yet); both are accepted for the positive controls, and
 * those controls are skipped when an admin already exists (then every call is the empty 404, which proves nothing about the gate).
 *
 * <p>Not covered here: a non-loopback socket address (K2), because a test cannot connect from another address; it is covered
 * without a server by {@code BootstrapGateTest}. The remote address of these calls is 127.0.0.1.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapRequestChecksIT {

    private static final List<String> REFUSED_FORWARDING_HEADERS = List.of("Forwarded", "X-Forwarded-Prefix",
            "X-Forwarded-Server", "X-Original-Forwarded-For", "X-Real-IP", "X-Client-IP", "Client-IP", "True-Client-IP",
            "CF-Connecting-IP", "Fastly-Client-IP", "X-Cluster-Client-IP", "Via");

    private AppRun app;
    private String reference;

    @BeforeAll
    void start() throws IOException {
        app = BootstrapSupport.builder()
                .debug("org.springframework.web", "org.springframework.web.servlet", "org.springframework.security")
                .start();
        reference = BootstrapSupport.referenceNotFound(app);
    }

    @AfterAll
    void stop() {
        if (app != null) {
            app.close();
        }
    }

    /** A call that passes every check: right path, JSON, nothing odd. A blank PIN is never counted. */
    private RawHttp.Request good() {
        return app.post(UNLOCK).json("{\"pin\":\"\"}");
    }

    private void expectRefused(String label, RawHttp.Request request) throws IOException {
        RawHttp.Response response = request.send();
        response.assertDevNotFound();
        assertThat(response.fingerprint()).as(label + ": same bytes as every other off dev path").isEqualTo(reference);
    }

    private void expectPasses(String label, RawHttp.Request request) throws IOException {
        RawHttp.Response response = request.send();
        assertThat(response.status()).as(label + ": reaches the service (400 with the functions, 500 without)").isIn(400, 500);
    }

    private static void requireNoAdmin() {
        BootstrapDb.failIfAnAdminExists(); // finding 3: a stray admin is a failure with a message, never a skip
    }

    // ---- K1 ------------------------------------------------------------------------------------------------

    @Test
    void k1OnlyPostToTheTwoExactPathsIsAnsweredEveryOtherMethodAndPathIsThe404NeverA405() throws IOException {
        for (String path : new String[] {UNLOCK, REGISTER}) {
            for (String method : new String[] {"GET", "PUT", "DELETE", "PATCH", "OPTIONS"}) {
                expectRefused(method + " " + path, app.request(method, path).header("Content-Type", "application/json"));
            }
        }
        // OPTIONS with the headers of a CORS preflight: no CORS header and no 200, so no browser can ever call it cross-site.
        RawHttp.Response preflight = app.request("OPTIONS", UNLOCK).header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type").send();
        preflight.assertDevNotFound();
        assertThat(preflight.fingerprint()).isEqualTo(reference);
        for (String path : new String[] {UNLOCK + "/", REGISTER + "/", UNLOCK.toUpperCase(Locale.ROOT), "/API/V1/ADMIN/BOOTSTRAP/UNLOCK",
                "/api/v1/admin/bootstrap/Unlock", "/api/v1/admin/bootstrap/unlock/x", "/api/v1/admin/bootstrap",
                "/api/v1/admin/bootstrap/", "/api/v1/admin/bootstrap/status",
                "/api/v1/admin/bootstrap/unlock.json"}) {
            expectRefused("POST " + path, app.post(path).json("{\"pin\":\"\"}"));
        }
    }

    @Test
    void k1BothExactPathsPassWithAPostAndTheDatabaseRulesDecideFromThere() throws IOException {
        requireNoAdmin();
        expectPasses("unlock", good());
        expectPasses("register", app.post(REGISTER).json("{}"));
    }

    // ---- K3 ------------------------------------------------------------------------------------------------

    @Test
    void k3EachRefusedForwardingHeaderAloneRefusesInAnyCaseWhateverItsValue() throws IOException {
        assertThat(REFUSED_FORWARDING_HEADERS).hasSize(12);
        for (String header : REFUSED_FORWARDING_HEADERS) {
            for (String name : new String[] {header, header.toUpperCase(Locale.ROOT), header.toLowerCase(Locale.ROOT)}) {
                expectRefused("header " + name + ": 127.0.0.1", good().header(name, "127.0.0.1"));
            }
        }
        expectRefused("Forwarded for=127.0.0.1", good().header("Forwarded", "for=127.0.0.1"));
        expectRefused("Via empty", good().header("Via", ""));
    }

    @Test
    void k3XForwardedForMustHoldOnlyBareLoopbackAddressesOnce() throws IOException {
        Map<String, RawHttp.Request> refused = new LinkedHashMap<>();
        refused.put("a LAN address", good().header("X-Forwarded-For", "192.168.1.20"));
        refused.put("a public address", good().header("X-Forwarded-For", "203.0.113.9"));
        refused.put("loopback then a LAN address", good().header("X-Forwarded-For", "127.0.0.1, 10.0.0.5"));
        refused.put("a LAN address then loopback", good().header("X-Forwarded-For", "10.0.0.5, 127.0.0.1"));
        refused.put("loopback with a port", good().header("X-Forwarded-For", "127.0.0.1:8080"));
        refused.put("a name", good().header("X-Forwarded-For", "localhost"));
        refused.put("empty value", good().header("X-Forwarded-For", ""));
        refused.put("trailing comma", good().header("X-Forwarded-For", "127.0.0.1,"));
        refused.put("IPv6 documentation address", good().header("X-Forwarded-For", "2001:db8::1"));
        refused.put("the header twice", good().header("X-Forwarded-For", "127.0.0.1").header("X-Forwarded-For", "127.0.0.1"));
        refused.put("a zone id", good().header("X-Forwarded-For", "fe80::1%eth0"));
        for (Map.Entry<String, RawHttp.Request> entry : refused.entrySet()) {
            expectRefused("X-Forwarded-For " + entry.getKey(), entry.getValue());
        }
    }

    @Test
    void k3TheOtherAllowedForwardingHeadersAreCheckedToo() throws IOException {
        expectRefused("X-Forwarded-Host evil", good().header("X-Forwarded-Host", "evil.example"));
        expectRefused("X-Forwarded-Host twice", good().header("X-Forwarded-Host", "localhost").header("X-Forwarded-Host", "localhost"));
        expectRefused("X-Forwarded-Proto ftp", good().header("X-Forwarded-Proto", "ftp"));
        expectRefused("X-Forwarded-Proto empty", good().header("X-Forwarded-Proto", ""));
        expectRefused("X-Forwarded-Port letters", good().header("X-Forwarded-Port", "abc"));
        expectRefused("X-Forwarded-Port six digits", good().header("X-Forwarded-Port", "123456"));
    }

    @Test
    void k3ForwardingHeadersThatOnlyNarrowAreAllowedSoThePageWorksThroughThePortFourTwoZeroZeroProxy() throws IOException {
        requireNoAdmin();
        expectPasses("X-Forwarded-For 127.0.0.1", good().header("X-Forwarded-For", "127.0.0.1"));
        expectPasses("X-Forwarded-For ::1", good().header("X-Forwarded-For", "::1"));
        expectPasses("X-Forwarded-For ::ffff:127.0.0.1", good().header("X-Forwarded-For", "::ffff:127.0.0.1"));
        expectPasses("X-Forwarded-For chain of loopbacks", good().header("X-Forwarded-For", "127.0.0.1, ::1"));
        expectPasses("X-Forwarded-Proto http", good().header("X-Forwarded-Proto", "http"));
        expectPasses("X-Forwarded-Port 4200", good().header("X-Forwarded-Port", "4200"));
        expectPasses("X-Forwarded-Host localhost:4200", good().header("X-Forwarded-Host", "localhost:4200"));
        // The whole set of headers the Angular dev proxy and a browser at localhost:4200 send (OQ-42-1).
        expectPasses("the page through the dev proxy", app.post(UNLOCK).json("{\"pin\":\"\"}")
                .header("Host", "localhost:4200").header("Origin", "http://localhost:4200")
                .header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
                .header("X-Forwarded-For", "::ffff:127.0.0.1").header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "4200").header("X-Forwarded-Host", "localhost:4200"));
    }

    // ---- K4 ------------------------------------------------------------------------------------------------

    @Test
    void k4OnlyALocalHostIsAcceptedWhichStopsDnsRebinding() throws IOException {
        for (String host : new String[] {"evil.example", "evil.example:8080", "192.168.1.20", "192.168.1.20:8080", "localhost.evil.example",
                "127.0.0.1.evil.example", "0.0.0.0", "10.0.0.5:4200", "[::2]"}) {
            expectRefused("Host " + host, good().header("Host", host));
        }
    }

    @Test
    void k4TheThreeLocalSpellingsPassInAnyLetterCaseWithOrWithoutAPort() throws IOException {
        requireNoAdmin();
        for (String host : new String[] {"localhost", "LOCALHOST:4200", "127.0.0.1", "127.0.0.1:" + app.port(), "[::1]", "[::1]:4200"}) {
            expectPasses("Host " + host, good().header("Host", host));
        }
    }

    // ---- K5 ------------------------------------------------------------------------------------------------

    @Test
    void k5AForeignOrMalformedOriginOrAFetchSiteOtherThanSameOriginRefuses() throws IOException {
        for (String origin : new String[] {"http://evil.example", "https://evil.example:4200", "null", "http://localhost.evil.example",
                "http://localhost:4200/", "http://localhost:4200/x", "http://localhost:4200@evil.example", "ftp://localhost",
                "localhost:4200", "http://", "http://192.168.1.20:4200", "http://localhost:abc", "file://"}) {
            expectRefused("Origin " + origin, good().header("Origin", origin));
        }
        expectRefused("two Origin headers", good().header("Origin", "http://localhost:4200").header("Origin", "http://localhost:4200"));
        for (String site : new String[] {"cross-site", "same-site", "none", "Same-Origin", ""}) {
            expectRefused("Sec-Fetch-Site " + site, good().header("Sec-Fetch-Site", site));
        }
        expectRefused("Sec-Fetch-Site twice", good().header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Site", "same-origin"));
        expectRefused("good Origin but cross-site fetch", good().header("Origin", "http://localhost:4200")
                .header("Sec-Fetch-Site", "cross-site"));
    }

    @Test
    void k5NoOriginAndNoFetchSiteLikeCurlAndTheLocalOriginsPass() throws IOException {
        requireNoAdmin();
        expectPasses("curl: neither header", good());
        for (String origin : new String[] {"http://localhost", "https://localhost", "http://localhost:4200", "http://127.0.0.1:4200",
                "http://[::1]:4200", "HTTP://LOCALHOST:4200"}) {
            expectPasses("Origin " + origin, good().header("Origin", origin));
        }
        expectPasses("Sec-Fetch-Site same-origin", good().header("Sec-Fetch-Site", "same-origin"));
    }

    // ---- K6 ------------------------------------------------------------------------------------------------

    @Test
    void k6OnlyAJsonContentTypeIsAcceptedSoAFormPostFromAnotherPageNeverReachesTheHandler() throws IOException {
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x",
                "application/xml", "application/jsonx", "application/json-patch+json", "text/json", "*/*", "json", ""}) {
            expectRefused("Content-Type " + type, rawWithType(UNLOCK, type));
        }
        expectRefused("no Content-Type at all", app.post(UNLOCK));
        expectRefused("Content-Type twice", rawWithType(UNLOCK, "application/json").header("Content-Type", "application/json"));
    }

    /** The same call but with exactly the given Content-Type (the helper {@code json()} would add a second one). */
    private RawHttp.Request rawWithType(String path, String contentType) {
        RawHttp.Request request = app.post(path);
        if (!contentType.isEmpty()) {
            request.header("Content-Type", contentType);
        }
        return request;
    }

    @Test
    void k6JsonWithParametersAndInAnyCaseIsAccepted() throws IOException {
        requireNoAdmin();
        for (String type : new String[] {"application/json", "application/json; charset=utf-8", "APPLICATION/JSON", "Application/Json;charset=UTF-8"}) {
            expectPasses("Content-Type " + type, rawWithType(UNLOCK, type));
        }
    }

    // ---- credentials, nothing written, size -------------------------------------------------------------------

    @Test
    void aBearerTokenAndOtherCookiesSentWithAPassingCallAreIgnored() throws IOException {
        requireNoAdmin();
        RawHttp.Response plain = good().send();
        RawHttp.Response withCredentials = good().header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.junk")
                .header("Cookie", "sahaj_admin_rt=junk; sahaj_rt=junk; JSESSIONID=junk").send();
        assertThat(withCredentials.status()).as("same answer with and without credentials").isEqualTo(plain.status());
        assertThat(withCredentials.bodyText()).isEqualTo(plain.bodyText());
        assertThat(withCredentials.hasHeader("Set-Cookie")).as("no session or other cookie is set").isFalse();
    }

    @Test
    void refusalsWriteNothingToTheDatabase() throws IOException {
        BootstrapDb.State before = BootstrapDb.objectsPresent() ? BootstrapDb.state() : null;
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        for (int i = 0; i < 20; i++) {
            expectRefused("refusal " + i, good().header("Host", "evil.example"));
            expectRefused("refusal " + i, app.request("GET", UNLOCK));
        }
        if (BootstrapDb.objectsPresent()) {
            assertThat(BootstrapDb.state()).as("the state row is unchanged (count, lock, ticket)").isEqualTo(before);
        }
        assertThat(SampleAdminDb.adminCount()).isEqualTo(snapshot.adminsBefore());
        if (SampleAdminDb.tablesPresent()) {
            assertThat(SampleAdminDb.auditRowsSince(snapshot)).as("a refusal is never audited (BR12)").isEmpty();
        }
    }

    @Test
    void aBodyOverFourKilobytesIsA413OnBothCallsBeforeAnyDatabaseCall() throws IOException {
        String big = "{\"pin\":\"" + "x".repeat(5000) + "\"}";
        for (String path : new String[] {UNLOCK, REGISTER}) {
            RawHttp.Response response = app.post(path).json(big).send();
            assertThat(response.status()).as(path + " with a 5000 byte body").isEqualTo(413);
            assertThat(response.header("Cache-Control")).as("no-store even on the 413").isEqualTo("no-store");
            assertThat(response.bodyText()).as("the 413 does not echo the body").doesNotContain("xxxxx");
            assertThat(response.hasHeader("Set-Cookie")).isFalse();
        }
    }

    @Test
    void aDatabaseFailureIsABodylessFiveHundredWithTheSqlStateClassOnlyInTheLog() throws IOException {
        // Only meaningful while the dev-tools callback has NOT created the functions: the call then fails inside the database.
        assumeFalse(BootstrapDb.objectsPresent(), "The bootstrap functions exist, so the call does not fail; the failure paths "
                + "are covered by BootstrapServiceTest and JdbcBootstrapRepositoryTest.");
        requireNoAdmin();
        for (String path : new String[] {UNLOCK, REGISTER}) {
            RawHttp.Response response = app.post(path).json("{\"pin\":\"qa-secret-pin-0001\",\"x\":\"y\"}").send();
            assertThat(response.status()).as(path).isEqualTo(500);
            assertThat(response.body()).as(path + ": no body, no database text").isEmpty();
            assertThat(response.hasHeader("Content-Type")).as("no content type on a bodyless 500").isFalse();
            assertThat(response.header("Cache-Control")).isEqualTo("no-store");
            assertThat(response.hasHeader("Set-Cookie")).isFalse();
        }
        String logs = app.logText();
        assertThat(logs).as("one fixed line per failure, class and SQLSTATE only").contains("Bootstrap registration failed");
        assertThat(logs).doesNotContain("qa-secret-pin-0001").doesNotContain("does not exist").doesNotContain("admin_bootstrap_");
    }
}
