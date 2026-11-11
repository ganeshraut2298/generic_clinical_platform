package com.sahaj.platform.devtools.it;

import static com.sahaj.platform.devtools.it.BootstrapSupport.ENABLED_ENV;
import static com.sahaj.platform.devtools.it.BootstrapSupport.HASH_ENV;
import static com.sahaj.platform.devtools.it.BootstrapSupport.REGISTER;
import static com.sahaj.platform.devtools.it.BootstrapSupport.UNLOCK;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SAH-42 story 1 (BR1 to BR4) and the start-up line (BR2): the bootstrap registration exists only when every start condition
 * B2 to B7 of ADR-018 D2 holds. Whole application, real command-line arguments and (simulated) environment variables, a real
 * socket, profile {@code dev,test}, Flyway off. Needs no database prerequisite: nothing here reaches a bootstrap function.
 * Runs only in a {@code -Pdev-tools} build (in a normal build {@code BootstrapAbsentInProductionIT} covers the absence).
 *
 * <p>For every way of turning ONE condition off: no bootstrap bean (no gate, controller, service, repository, security chain,
 * Flyway location), the empty 404 of the contract, the same bytes in every case and the same as any other dev-only path that is
 * off, for both calls and for every method, and no admin row or audit row written. The start-up line is checked for what it
 * says and for what it must never say (the hash).
 *
 * <p>Not repeated here because they cannot be run safely in-process: B7 with a non-loopback address (binding the test server to
 * a LAN address is not done; covered without a server by {@code BootstrapConditionsTest}) and a real operating-system variable
 * (a simulated source of the same type is used).
 */
class BootstrapSwitchesIT {

    private static final String PACKAGE = "com.sahaj.platform.devtools.bootstrap.";
    private static final List<String> BEAN_TYPES = List.of("BootstrapGate", "BootstrapPinVerifier", "BootstrapTicketHasher",
            "BootstrapRepository", "BootstrapService", "BootstrapController", "BootstrapFlywayCustomizer");
    private static final String ON_LINE = "Admin bootstrap registration is on (dev-tools build). /admin/register works from "
            + "this machine until an admin exists.";
    private static final String OFF_PREFIX = "Admin bootstrap registration is off: ";

    private final Map<String, String> fingerprints = new LinkedHashMap<>();

    private static Class<?> bootstrapClass(String simpleName) {
        try {
            return Class.forName(PACKAGE + simpleName);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("The dev-tools class " + simpleName + " must exist in a -Pdev-tools build", ex);
        }
    }

    private static void assertBeans(AppRun app, boolean expected, String label) {
        for (String simpleName : BEAN_TYPES) {
            String[] names = app.context().getBeanNamesForType(bootstrapClass(simpleName), true, false);
            assertThat(names.length > 0).as(label + ": bean of " + simpleName).isEqualTo(expected);
        }
        assertThat(app.context().containsBean("bootstrapSecurityFilterChain")).as(label + ": bootstrap security chain")
                .isEqualTo(expected);
        assertThat(app.context().getBeansOfType(SecurityFilterChain.class).keySet().stream()
                .anyMatch(n -> n.toLowerCase(Locale.ROOT).contains("bootstrap"))).as(label + ": chain by name")
                .isEqualTo(expected);
        assertThat(app.context().getBeansOfType(FlywayConfigurationCustomizer.class).values().stream()
                .anyMatch(c -> c.getClass().getName().startsWith(PACKAGE))).as(label + ": Flyway location of the callback")
                .isEqualTo(expected);
    }

    private static List<String> startupLines(AppRun app) {
        return app.logLines().stream()
                .filter(line -> line.contains("Admin bootstrap registration is "))
                .collect(Collectors.toList());
    }

    private static void assertNoHashInLogs(AppRun app) {
        String logs = app.logText();
        String hash = BootstrapSupport.pinHash();
        assertThat(logs).as("the PIN hash is never logged").doesNotContain(hash).doesNotContain(hash.substring(7, 27))
                .doesNotContain(BootstrapSupport.PIN);
    }

    /** One case with ONE condition off: nothing exists, the answer is the standard 404, nothing is written. */
    private void assertOff(String label, AppRun.Builder builder, String expectedReason) throws IOException {
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try (AppRun app = builder.start()) {
            assertBeans(app, false, label);
            List<String> lines = startupLines(app);
            if (expectedReason == null) {
                assertThat(lines).as(label + ": switch not true, so no start-up line at all").isEmpty();
            } else {
                assertThat(lines).as(label + ": exactly one start-up line").hasSize(1);
                assertThat(lines.get(0)).as(label + ": the fixed reason").contains(OFF_PREFIX + expectedReason + ".");
            }
            assertNoHashInLogs(app);
            String reference = BootstrapSupport.referenceNotFound(app);
            fingerprints.put(label, reference);
            for (String path : new String[] {UNLOCK, REGISTER}) {
                // Every method, with and without a body, with a hostile body size and with credentials: same bytes.
                for (String method : new String[] {"POST", "GET", "PUT", "DELETE", "PATCH", "OPTIONS"}) {
                    RawHttp.Request request = app.request(method, path);
                    if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                        request.json("{\"pin\":\"x\"}");
                    }
                    assertThat(request.send().assertDevNotFound().fingerprint()).as(label + ": " + method + " " + path)
                            .isEqualTo(reference);
                }
                assertThat(app.post(path).json("{\"pin\":\"" + "x".repeat(10_000) + "\"}").send().assertDevNotFound()
                        .fingerprint()).as(label + ": a 10 KB body is still the empty 404 (the filter answers first)")
                        .isEqualTo(reference);
                assertThat(app.post(path).json("{\"pin\":\"x\"}")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x")
                        .header("Cookie", "sahaj_admin_rt=junk; sahaj_rt=junk; sahaj_admin_bootstrap=junk")
                        .send().assertDevNotFound().fingerprint()).as(label + ": tokens and cookies change nothing")
                        .isEqualTo(reference);
            }
            // Other paths below the reserved prefix are the same 404, never 405, 401 or 403.
            for (String path : new String[] {"/api/v1/admin/bootstrap", "/api/v1/admin/bootstrap/", "/api/v1/admin/bootstrap/x",
                    "/api/v1/admin/BOOTSTRAP/Unlock", "/api/v1/admin/bootstrap/unlock/", "/api/v1/admin/bootstrap/status"}) {
                assertThat(app.post(path).json("{}").send().assertDevNotFound().fingerprint()).as(label + ": " + path)
                        .isEqualTo(reference);
            }
        }
        assertThat(SampleAdminDb.adminCount()).as(label + ": admin rows").isEqualTo(snapshot.adminsBefore());
        if (SampleAdminDb.tablesPresent()) {
            assertThat(SampleAdminDb.auditRowsSince(snapshot)).as(label + ": audit rows").isEmpty();
        }
    }

    private void assertAllTheSame() {
        assertThat(fingerprints.values().stream().distinct().toList())
                .as("the 404 must be byte-identical in every case: " + fingerprints.keySet()).hasSize(1);
    }

    // ---- B3 -----------------------------------------------------------------------------------------------

    @Test
    void b3TheSwitchAbsentOrNotTrueLeavesTheRegistrationAbsentAndSilent() throws Exception {
        assertOff("switch empty (as if unset)", BootstrapSupport.builder().environmentVariable(ENABLED_ENV, ""), null);
        for (String raw : new String[] {"false", "yes", "1", "on", "tru", "truee", "enabled"}) {
            assertOff("switch=" + raw, BootstrapSupport.builder().environmentVariable(ENABLED_ENV, raw), null);
        }
        assertAllTheSame();
    }

    @Test
    void b3TrueInAnyLetterCaseTurnsItOnAndTheSwitchWorksFromTheEnvironmentAndFromTheCommandLine() throws Exception {
        for (String raw : new String[] {"true", "TRUE", "True"}) {
            try (AppRun app = BootstrapSupport.builder().environmentVariable(ENABLED_ENV, raw).start()) {
                assertBeans(app, true, "environment " + raw);
                assertThat(startupLines(app)).as("environment " + raw + ": one line").hasSize(1);
                assertThat(startupLines(app).get(0)).contains(ON_LINE);
                assertNoHashInLogs(app);
            }
        }
        // Unlike the sample admin (command line only), the bootstrap switch also works as a plain property (BR1, H-BOOT-2).
        try (AppRun app = BootstrapSupport.builder().environmentVariable(ENABLED_ENV, "")
                .option("sahaj.admin.bootstrap.enabled", "true").start()) {
            assertBeans(app, true, "command-line property");
        }
    }

    // ---- B4 -----------------------------------------------------------------------------------------------

    @Test
    void b4AMissingBlankOrBadHashLeavesTheRegistrationAbsentAndTheStartupLineSaysWhyWithoutAnySecret() throws Exception {
        String reason = "the PIN hash is missing or not a valid BCrypt hash (cost 12 to 16)";
        String body = "a".repeat(53);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("missing", "");
        bad.put("blank", "   ");
        bad.put("the PIN itself instead of a hash", BootstrapSupport.PIN);
        bad.put("cost 11 (below 12)", "$2a$11$" + body);
        bad.put("cost 17 (above 16)", "$2a$17$" + body);
        bad.put("unknown version $2c$", "$2c$12$" + body);
        bad.put("52 hash characters", "$2a$12$" + "a".repeat(52));
        bad.put("illegal character", "$2a$12$" + "a".repeat(52) + "!");
        for (Map.Entry<String, String> entry : bad.entrySet()) {
            assertOff("hash " + entry.getKey(), BootstrapSupport.builder().environmentVariable(HASH_ENV, entry.getValue()), reason);
        }
        assertAllTheSame();
    }

    @Test
    void b4TheHashTheToolMakesAtCost14IsAccepted() throws Exception {
        // Cost 14 is what BootstrapPinHashTool writes (about a second). The tool itself is exercised in PackagedJarDevToolsBuildIT.
        String cost14 = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(14).encode(BootstrapSupport.PIN);
        assertThat(cost14).startsWith("$2a$14$");
        try (AppRun app = BootstrapSupport.builder().environmentVariable(HASH_ENV, cost14).start()) {
            assertBeans(app, true, "cost 14 hash");
            assertThat(startupLines(app).get(0)).contains(ON_LINE);
        }
        // The $2y$ spelling that Apache's htpasswd writes is accepted too (ADR-018 D3).
        String htpasswdStyle = "$2y$" + cost14.substring(4);
        try (AppRun app = BootstrapSupport.builder().environmentVariable(HASH_ENV, htpasswdStyle).start()) {
            assertBeans(app, true, "$2y$ hash");
        }
    }

    @Test
    void b4TheHashThatTheRealToolPrintsFromAHiddenPromptIsAcceptedAndNothingElseIsPrintedBeforeIt() throws Exception {
        String printed = BootstrapSupport.hashFromTool("tool-made-pin-9x", 12);
        assertThat(printed).as("one line, only the hash").matches("\\$2a\\$12\\$[./A-Za-z0-9]{53}");
        try (AppRun app = BootstrapSupport.builder().environmentVariable(HASH_ENV, printed).start()) {
            assertBeans(app, true, "hash made by the tool");
            assertThat(startupLines(app).get(0)).contains(ON_LINE).doesNotContain(printed);
        }
    }

    // ---- B2 -----------------------------------------------------------------------------------------------

    @Test
    void b2AnyProfileCombinationThatIsNotDevOrDevTestLeavesTheRegistrationAbsent() throws Exception {
        String reason = "the active profiles are not dev";
        assertOff("profile test only", BootstrapSupport.builder().profiles("test"), reason);
        // Other names (staging, prod) would need their own configuration files to start; BootstrapConditionsTest covers them.
        assertAllTheSame();
    }

    @Test
    void b2ProfileDevAloneAndDevWithTestBothTurnItOn() throws Exception {
        for (String profiles : new String[] {"dev", "dev,test", "test,dev"}) {
            try (AppRun app = BootstrapSupport.builder().profiles(profiles).start()) {
                assertBeans(app, true, "profiles " + profiles);
            }
        }
    }

    // ---- B5 -----------------------------------------------------------------------------------------------

    @Test
    void b5TheAdminRealmOffOrUnavailableLeavesTheRegistrationAbsent() throws Exception {
        String reason = "the admin realm is off";
        assertOff("admin realm off", BootstrapSupport.builder().realmOff(), reason);
        assertOff("admin realm on but a secret missing", BootstrapSupport.builder().realmUnavailable(), reason);
        assertAllTheSame();
    }

    // ---- B6 -----------------------------------------------------------------------------------------------

    @Test
    void b6AnyProxyOrCloudConfigurationLeavesTheRegistrationAbsent() throws Exception {
        String reason = "proxy settings or a cloud platform are present";
        assertOff("forward headers strategy native", BootstrapSupport.builder().option("server.forward-headers-strategy", "native"), reason);
        assertOff("forward headers strategy framework",
                BootstrapSupport.builder().option("server.forward-headers-strategy", "framework"), reason);
        assertOff("remote IP header configured", BootstrapSupport.builder()
                .option("server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For")
                // The application refuses to start with a remote IP header and no trusted proxies (TrustedProxyStartupCheck).
                .option("server.tomcat.remoteip.internal-proxies", "10\\.99\\.99\\.\\d{1,3}"), reason);
        assertOff("protocol header configured",
                BootstrapSupport.builder().option("server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto"), reason);
        assertOff("spring.main.cloud-platform=kubernetes", BootstrapSupport.builder().option("spring.main.cloud-platform", "kubernetes"), reason);
        assertOff("spring.main.cloud-platform=none", BootstrapSupport.builder().option("spring.main.cloud-platform", "none"), reason);
        assertOff("Kubernetes detected from its variables", BootstrapSupport.builder()
                .environmentVariable("KUBERNETES_SERVICE_HOST", "10.0.0.1").environmentVariable("KUBERNETES_SERVICE_PORT", "443"), reason);
        assertOff("Heroku detected from DYNO", BootstrapSupport.builder().environmentVariable("DYNO", "web.1"), reason);
        assertAllTheSame();
    }

    @Test
    void b6AForwardHeadersStrategyOfNoneDoesNotSwitchItOff() throws Exception {
        try (AppRun app = BootstrapSupport.builder().option("server.forward-headers-strategy", "none").start()) {
            assertBeans(app, true, "forward-headers-strategy=none");
        }
    }

    // ---- B7 -----------------------------------------------------------------------------------------------

    @Test
    void b7AServerAddressThatIsNotALoopbackLiteralLeavesTheRegistrationAbsent() throws Exception {
        String reason = "server.address is not a loopback address";
        // "localhost" is a name, never resolved, so it is not a loopback literal (and it binds to the loopback interface only,
        // so this case is safe to run). 0.0.0.0 and LAN addresses are covered by BootstrapConditionsTest.
        assertOff("server.address=localhost (a name)", BootstrapSupport.builder().option("server.address", "localhost"), reason);
        assertAllTheSame();
    }

    @Test
    void b7BothLoopbackLiteralsTurnItOn() throws Exception {
        try (AppRun app = BootstrapSupport.builder().option("server.address", "127.0.0.1").start()) {
            assertBeans(app, true, "server.address=127.0.0.1");
        }
    }

    // ---- first failing reason wins --------------------------------------------------------------------------

    @Test
    void theStartupLineNamesOnlyTheFirstFailingReasonInTheFixedOrder() throws Exception {
        // B4 (hash) is reported before B2 (profiles), B2 before B5 (realm).
        assertOff("hash and profile both wrong", BootstrapSupport.builder().environmentVariable(HASH_ENV, "")
                .profiles("test"), "the PIN hash is missing or not a valid BCrypt hash (cost 12 to 16)");
        assertOff("profile and realm both wrong", BootstrapSupport.builder().profiles("test").realmOff(),
                "the active profiles are not dev");
        assertAllTheSame();
    }
}
