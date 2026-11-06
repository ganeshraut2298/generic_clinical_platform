package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.RawHttp;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DEC-016 / ADR-008 test T2. A build made WITHOUT {@code -Pdev-tools} (the production build) answers the same empty 404 for
 * every method and path under {@code /api/v1/admin/dev}, with the admin realm on, off or unavailable. The application is
 * started the way a careless developer would start it: profiles dev,test, loopback address, the flag as a real
 * command-line argument and a plausible nonce header. Nothing but the missing classes can explain the 404, which is the
 * point of the build-time control.
 *
 * <p>The answers must be byte-identical (status, headers except Date and the request id, body), and never 401, 403, 405 or
 * 503, which would tell a caller how the server is configured. Runs only in a build without the dev-tools profile.
 */
class DevSampleAdminAbsentInProductionIT {

    private static final String[] PATHS = {
            "/api/v1/admin/dev/sample-admin", "/api/v1/admin/dev", "/api/v1/admin/dev/x",
            "/api/v1/admin/DEV/Sample-Admin", "/api/v1/admin/dev/sample-admin/"};

    private static void assumeProductionBuild() {
        assumeFalse("true".equals(System.getProperty("sahaj.devtools")),
                "Stands down in a -Pdev-tools build (sahaj.devtools=true): there the endpoint legitimately exists.");
    }

    /** Collects the fingerprint of every answer; all must be equal. */
    private static void sweep(AppRun app, Map<String, String> fingerprints, String label) throws IOException {
        for (String path : PATHS) {
            RawHttp.Response post = app.devCall("0123456789abcdef0123456789abcdef").send();
            post.assertDevNotFound();
            fingerprints.put(label + " POST dev-call", post.fingerprint());

            RawHttp.Response postOther = app.post(path).header("X-Sahaj-Dev-Nonce", "0123456789abcdef0123456789abcdef").send();
            postOther.assertDevNotFound();
            fingerprints.put(label + " POST " + path, postOther.fingerprint());

            RawHttp.Response get = app.request("GET", path).send();
            get.assertDevNotFound();
            fingerprints.put(label + " GET " + path, get.fingerprint());

            RawHttp.Response put = app.request("PUT", path).send();
            put.assertDevNotFound();
            fingerprints.put(label + " PUT " + path, put.fingerprint());

            RawHttp.Response preflight = app.request("OPTIONS", path)
                    .header("Origin", "http://localhost:4200")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,x-sahaj-dev-nonce")
                    .send();
            preflight.assertDevNotFound();
            fingerprints.put(label + " OPTIONS " + path, preflight.fingerprint());
        }
    }

    private static void assertNoDevToolsClassExists() {
        assertThatThrownBy(() -> Class.forName("com.sahaj.platform.devtools.admin.DevSampleAdminController"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void realmOnAnswersTheSameEmpty404ForEveryMethodAndPathAndTheRealmItselfIsUp() throws IOException {
        assumeProductionBuild();
        assertNoDevToolsClassExists();
        try (AppRun app = AppRun.builder().start()) {
            Map<String, String> fingerprints = new LinkedHashMap<>();
            sweep(app, fingerprints, "realm on");
            assertThat(fingerprints.values().stream().distinct().toList()).as("one single 404 answer").hasSize(1);
            assertThat(app.context().getBeanNamesForType(org.springframework.security.web.SecurityFilterChain.class))
                    .as("no dev chain bean").noneMatch(name -> name.toLowerCase().contains("devsample"));
            // Control: the realm is really up, so the 404 is not a side effect of a dead realm (an unauthenticated /me is 401).
            assertThat(app.request("GET", "/api/v1/admin/me").send().status()).isEqualTo(401);
        }
    }

    @Test
    void realmOffAnswersTheSameEmpty404() throws IOException {
        assumeProductionBuild();
        try (AppRun app = AppRun.builder().realmOff().start()) {
            Map<String, String> fingerprints = new LinkedHashMap<>();
            sweep(app, fingerprints, "realm off");
            assertThat(fingerprints.values().stream().distinct().toList()).hasSize(1);
        }
    }

    @Test
    void realmUnavailableAnswersTheSameEmpty404NotThe503OfTheAdminUnavailableChain() throws IOException {
        assumeProductionBuild();
        try (AppRun app = AppRun.builder().realmUnavailable().start()) {
            Map<String, String> fingerprints = new LinkedHashMap<>();
            sweep(app, fingerprints, "realm unavailable");
            assertThat(fingerprints.values().stream().distinct().toList()).hasSize(1);
            // Control: any other admin path is the 503 admin-unavailable answer, so the dev path is the odd one out on purpose.
            assertThat(app.request("GET", "/api/v1/admin/me").send().status()).isEqualTo(503);
        }
    }

    @Test
    void theThreeConfigurationsAnswerByteIdentically() throws IOException {
        assumeProductionBuild();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        try (AppRun on = AppRun.builder().start()) {
            fingerprints.put("on", on.devCall("0123456789abcdef0123456789abcdef").send().assertDevNotFound().fingerprint());
        }
        try (AppRun off = AppRun.builder().realmOff().start()) {
            fingerprints.put("off", off.devCall("0123456789abcdef0123456789abcdef").send().assertDevNotFound().fingerprint());
        }
        try (AppRun unavailable = AppRun.builder().realmUnavailable().start()) {
            fingerprints.put("unavailable",
                    unavailable.devCall("0123456789abcdef0123456789abcdef").send().assertDevNotFound().fingerprint());
        }
        assertThat(fingerprints.values().stream().distinct().toList()).as("answers by configuration: " + fingerprints.keySet())
                .hasSize(1);
    }
}
