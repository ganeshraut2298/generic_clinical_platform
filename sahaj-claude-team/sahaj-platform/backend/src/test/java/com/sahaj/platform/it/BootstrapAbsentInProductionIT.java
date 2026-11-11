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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * SAH-42 / ADR-018 T13 and story 1, the run-time half: a build made WITHOUT {@code -Pdev-tools} (the production build) answers
 * the same empty 404 for every method and path under {@code /api/v1/admin/bootstrap}, however the developer sets the switch.
 * The application is started the way a careless developer would start it: profiles dev,test, loopback address, the admin realm
 * on, {@code SAHAJ_ADMIN_BOOTSTRAP_ENABLED=true} and a valid throwaway PIN hash in the (simulated) environment, and a perfectly
 * formed local JSON request. Nothing but the missing classes (and the reserved-path filter in main code) can explain the 404,
 * which is the point of the build-time control.
 *
 * <p>The answers must be byte-identical to the 404 of the dev sample path (status, headers except Date and the request id,
 * body), never 401, 403, 405, 400 or 503, and nothing may be logged about the bootstrap (a production jar has no such class, so
 * no start-up line exists). Runs only in a build without the dev-tools profile; the dev-tools build is covered by
 * {@code BootstrapSwitchesIT}.
 */
class BootstrapAbsentInProductionIT {

    private static final String UNLOCK = "/api/v1/admin/bootstrap/unlock";
    private static final String REGISTER = "/api/v1/admin/bootstrap/register";
    private static final String[] PATHS = {UNLOCK, REGISTER, "/api/v1/admin/bootstrap", "/api/v1/admin/bootstrap/",
            "/api/v1/admin/bootstrap/x", "/api/v1/admin/BOOTSTRAP/Unlock", UNLOCK + "/"};

    private static void assumeProductionBuild() {
        assumeFalse("true".equals(System.getProperty("sahaj.devtools")),
                "Stands down in a -Pdev-tools build (sahaj.devtools=true): there the registration legitimately exists.");
    }

    /** The switch on and a syntactically valid hash, as a developer who followed the instructions would have. */
    private static AppRun.Builder carelessDeveloper() {
        return AppRun.builder().noFlag()
                .environmentVariable("SAHAJ_ADMIN_BOOTSTRAP_ENABLED", "true")
                .environmentVariable("SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH", new BCryptPasswordEncoder(12).encode("qa-throwaway-pin"));
    }

    private static void sweep(AppRun app, Map<String, String> fingerprints, String label) throws IOException {
        String reference = app.request("GET", "/api/v1/admin/dev/sample-admin").send().assertDevNotFound().fingerprint();
        fingerprints.put(label + " reference", reference);
        for (String path : PATHS) {
            for (String method : new String[] {"POST", "GET", "PUT", "DELETE", "PATCH", "OPTIONS"}) {
                RawHttp.Request request = app.request(method, path);
                if (!method.equals("GET") && !method.equals("DELETE") && !method.equals("OPTIONS")) {
                    request.json("{\"pin\":\"qa-throwaway-pin\"}");
                }
                if (method.equals("OPTIONS")) {
                    request.header("Origin", "http://localhost:4200").header("Access-Control-Request-Method", "POST");
                }
                RawHttp.Response response = request.send();
                response.assertDevNotFound();
                fingerprints.put(label + " " + method + " " + path, response.fingerprint());
            }
        }
        // A request with a ticket cookie and a bearer token: still the same bytes.
        RawHttp.Response withCredentials = app.post(REGISTER).json("{\"ticket\":\"" + "A".repeat(43) + "\"}")
                .header("Cookie", "sahaj_admin_bootstrap=" + "B".repeat(43) + "; sahaj_admin_rt=junk")
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x").send();
        withCredentials.assertDevNotFound();
        fingerprints.put(label + " with credentials", withCredentials.fingerprint());
    }

    private static void assertNoBootstrapClassExists() {
        for (String name : new String[] {"com.sahaj.platform.devtools.bootstrap.BootstrapController",
                "com.sahaj.platform.devtools.bootstrap.BootstrapGate"}) {
            assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void realmOnWithTheSwitchAndAValidHashAnswersTheSameEmpty404ForEveryMethodAndPathAndNothingIsLogged() throws IOException {
        assumeProductionBuild();
        assertNoBootstrapClassExists();
        try (AppRun app = carelessDeveloper().start()) {
            Map<String, String> fingerprints = new LinkedHashMap<>();
            sweep(app, fingerprints, "realm on");
            assertThat(fingerprints.values().stream().distinct().toList()).as("one single 404 answer").hasSize(1);
            assertThat(app.context().getBeanNamesForType(org.springframework.security.web.SecurityFilterChain.class))
                    .as("no bootstrap chain bean").noneMatch(name -> name.toLowerCase().contains("bootstrap"));
            assertThat(app.logText()).as("a production jar has no start-up line about the bootstrap")
                    .doesNotContain("Admin bootstrap registration");
            // Control: the realm is really up, so the 404 is not a side effect of a dead realm (an unauthenticated /me is 401).
            assertThat(app.request("GET", "/api/v1/admin/me").send().status()).isEqualTo(401);
        }
    }

    @Test
    void realmOffAndRealmUnavailableAnswerTheSameEmpty404() throws IOException {
        assumeProductionBuild();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        try (AppRun off = carelessDeveloper().realmOff().start()) {
            sweep(off, fingerprints, "realm off");
        }
        try (AppRun unavailable = carelessDeveloper().realmUnavailable().start()) {
            sweep(unavailable, fingerprints, "realm unavailable");
            // Control: any other admin path is the 503 admin-unavailable answer, so the bootstrap path is the odd one out on purpose.
            assertThat(unavailable.request("GET", "/api/v1/admin/me").send().status()).isEqualTo(503);
        }
        assertThat(fingerprints.values().stream().distinct().toList()).as("same bytes in every configuration").hasSize(1);
    }

    @Test
    void theAnswerIsTheSameWhateverTheSwitchSaysBecauseTheCodeIsNotThere() throws IOException {
        assumeProductionBuild();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String switchValue : new String[] {"true", "false", ""}) {
            try (AppRun app = carelessDeveloper().environmentVariable("SAHAJ_ADMIN_BOOTSTRAP_ENABLED", switchValue).start()) {
                fingerprints.put("switch=" + switchValue, app.post(UNLOCK).json("{\"pin\":\"x\"}").send().assertDevNotFound().fingerprint());
            }
        }
        assertThat(fingerprints.values().stream().distinct().toList()).as(fingerprints.keySet().toString()).hasSize(1);
    }
}
