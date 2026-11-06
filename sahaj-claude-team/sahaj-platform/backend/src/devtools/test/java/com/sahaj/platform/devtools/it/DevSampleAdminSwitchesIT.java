package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * DEC-016 / ADR-008 test T1 and its R9 extensions, on the whole application started the way a developer starts it
 * (real command-line arguments, real socket, profile dev,test, Flyway off). Runs only in a {@code -Pdev-tools} build,
 * because only there do the dev-tools classes exist; in the normal build T2 ({@code DevSampleAdminAbsentInProductionIT})
 * covers the absence.
 *
 * <p>For every way of turning ONE condition off: no dev-tools bean (no gate, controller, service, repository, security
 * chain, Flyway customizer), no start-up WARN, the empty 404 of the contract, the same bytes in every case, and no admin or
 * audit row written. For the two spellings of {@code true} on the command line: the beans exist.
 *
 * <p>Not repeated here (covered without a server by {@code DevSampleAdminConditionsTest}): C5 with a non-loopback
 * address (binding the test server to a LAN address is not done), the operating-system environment variable
 * {@code SAHAJ_REMOTE_IP_HEADER} (an in-process test cannot set a real one; the property it maps to is tested
 * here) and C1 with {@code prod} or {@code staging} (those profiles would need their own configuration files). An
 * operating-system variable of the flag is simulated by a property source of the same type.
 */
class DevSampleAdminSwitchesIT {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String PACKAGE = "com.sahaj.platform.devtools.admin.";
    private static final List<String> DEV_TOOLS_CLASSES = List.of("DevSampleAdminGate", "DevSampleAdminNonce",
            "DevSampleAdminController", "DevSampleAdminService", "DevSampleAdminRepository",
            "DevSampleAdminFlywayCustomizer", "DevSampleAdminStartupNotice");

    private final Map<String, String> fingerprints = new LinkedHashMap<>();

    private static Class<?> devToolsClass(String simpleName) {
        try {
            return Class.forName(PACKAGE + simpleName);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("The dev-tools class " + simpleName + " must exist in a -Pdev-tools build", ex);
        }
    }

    private static void assertDevToolsBeans(AppRun app, boolean expected, String label) {
        for (String simpleName : DEV_TOOLS_CLASSES) {
            String[] names = app.context().getBeanNamesForType(devToolsClass(simpleName), true, false);
            assertThat(names.length > 0).as(label + ": bean of " + simpleName).isEqualTo(expected);
        }
        assertThat(app.context().containsBean("devSampleAdminSecurityFilterChain")).as(label + ": dev security chain")
                .isEqualTo(expected);
        assertThat(app.context().getBeansOfType(FlywayConfigurationCustomizer.class).values().stream()
                .anyMatch(c -> c.getClass().getName().startsWith(PACKAGE))).as(label + ": dev-tools Flyway location")
                .isEqualTo(expected);
        assertThat(app.context().getBeansOfType(SecurityFilterChain.class).keySet().stream()
                .anyMatch(n -> n.toLowerCase().contains("devsample"))).as(label + ": chain by name").isEqualTo(expected);
        assertThat(app.nonceFromLog() != null).as(label + ": start-up WARN with a nonce").isEqualTo(expected);
    }

    /** One case with ONE condition off: nothing exists, the answer is the standard 404, nothing is written. */
    private void assertOff(String label, AppRun.Builder builder) throws IOException {
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try (AppRun app = builder.start()) {
            assertDevToolsBeans(app, false, label);
            String fingerprint = app.devCall(NONCE).send().assertDevNotFound().fingerprint();
            fingerprints.put(label, fingerprint);
            // Same answer with a bearer token and a refresh cookie (T15 with the endpoint off).
            assertThat(app.devCall(NONCE).header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x")
                    .header("Cookie", "sahaj_admin_rt=junk").send().assertDevNotFound().fingerprint())
                    .as(label + ": tokens change nothing").isEqualTo(fingerprint);
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

    @Test
    void c2TheFlagFromAnythingButTheCommandLineLeavesTheEndpointAbsent() throws Exception {
        assertOff("no flag at all", AppRun.builder().noFlag());
        assertOff("flag as an operating-system environment variable",
                AppRun.builder().noFlag().environmentVariable("SAHAJ_ADMIN_DEV_SAMPLE_ENABLED", "true"));
        assertOff("flag as a JVM system property",
                AppRun.builder().noFlag().systemProperty("sahaj.admin.dev-sample-enabled", "true"));
        assertOff("flag inside SPRING_APPLICATION_JSON",
                AppRun.builder().noFlag().option("spring.application.json", "{\"sahaj.admin.dev-sample-enabled\":\"true\"}"));
        assertOff("flag in a default-properties map source",
                AppRun.builder().noFlag().defaultProperty("sahaj.admin.dev-sample-enabled", "true"));

        Path file = Files.createTempFile("dec016-flag", ".yml");
        try {
            Files.writeString(file, "sahaj:\n  admin:\n    dev-sample-enabled: true\n");
            assertOff("flag in a yml file given with spring.config.additional-location", AppRun.builder().noFlag()
                    .option("spring.config.additional-location", "optional:file:" + file.toAbsolutePath().toString().replace('\\', '/')));
        } finally {
            Files.deleteIfExists(file);
        }
        assertAllTheSame();
    }

    @Test
    void c2ACommandLineValueThatIsNotTrueLeavesTheEndpointAbsent() throws Exception {
        for (String raw : new String[] {"false", "yes", "1", "", "${X}", "on", "tru"}) {
            assertOff("command line --sahaj.admin.dev-sample-enabled=" + raw, AppRun.builder().flag(raw));
        }
        assertAllTheSame();
    }

    @Test
    void c2TrueAndTrueInCapitalsOnTheCommandLineTurnTheBeansOn() throws Exception {
        for (String raw : new String[] {"true", "TRUE", "True"}) {
            try (AppRun app = AppRun.builder().flag(raw).start()) {
                assertDevToolsBeans(app, true, "command line =" + raw);
                assertThat(app.nonceFromLog()).as("nonce in the one WARN line").matches("[0-9a-f]{32}");
                // The right nonce is still needed: without it the answer is the standard 404.
                app.devCall(null).send().assertDevNotFound();
            }
        }
    }

    @Test
    void c1AnyOtherProfileCombinationLeavesTheEndpointAbsent() throws Exception {
        assertOff("profile test only (no dev)", AppRun.builder().profiles("test"));
        assertAllTheSame();
    }

    @Test
    void c3TheAdminRealmOffOrUnavailableLeavesTheEndpointAbsent() throws Exception {
        assertOff("admin realm off", AppRun.builder().realmOff());
        assertOff("admin realm on but a secret missing (unavailable)", AppRun.builder().realmUnavailable());
        assertAllTheSame();
    }

    @Test
    void c4AnyProxyOrCloudConfigurationLeavesTheEndpointAbsent() throws Exception {
        assertOff("forward headers strategy native", AppRun.builder().option("server.forward-headers-strategy", "native"));
        assertOff("forward headers strategy framework", AppRun.builder().option("server.forward-headers-strategy", "framework"));
        assertOff("remote IP header configured (what SAHAJ_REMOTE_IP_HEADER sets)",
                AppRun.builder().option("server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For")
                        // The application refuses to start with a remote IP header and no trusted proxies (TrustedProxyStartupCheck).
                        .option("server.tomcat.remoteip.internal-proxies", "10\\.99\\.99\\.\\d{1,3}"));
        assertOff("protocol header configured",
                AppRun.builder().option("server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto"));
        assertOff("spring.main.cloud-platform=kubernetes", AppRun.builder().option("spring.main.cloud-platform", "kubernetes"));
        assertOff("spring.main.cloud-platform=none (any value refuses)", AppRun.builder().option("spring.main.cloud-platform", "none"));
        assertOff("spring.main.cloud-platform=heroku", AppRun.builder().option("spring.main.cloud-platform", "heroku"));
        assertOff("Kubernetes detected from its two environment variables", AppRun.builder()
                .environmentVariable("KUBERNETES_SERVICE_HOST", "10.0.0.1").environmentVariable("KUBERNETES_SERVICE_PORT", "443"));
        assertOff("Heroku detected from DYNO", AppRun.builder().environmentVariable("DYNO", "web.1"));
        assertAllTheSame();
    }

    @Test
    void c4AForwardHeadersStrategyOfNoneDoesNotSwitchTheEndpointOff() throws Exception {
        // "none" is the explicit no-proxy setting; it must not be mistaken for a proxy configuration.
        try (AppRun app = AppRun.builder().option("server.forward-headers-strategy", "none").start()) {
            assertDevToolsBeans(app, true, "forward-headers-strategy=none");
        }
    }
}
