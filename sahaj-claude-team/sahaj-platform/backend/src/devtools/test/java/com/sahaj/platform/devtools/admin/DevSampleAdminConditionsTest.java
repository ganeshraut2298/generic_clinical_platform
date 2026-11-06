package com.sahaj.platform.devtools.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.infrastructure.admin.AdminProperties;
import com.sahaj.platform.web.admin.DevEndpointGate;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

/**
 * ADR-008 D2, conditions C1 to C5: the dev-tools configuration exists only when every one holds. When one is
 * false no gate, controller, service, repository, security chain or Flyway customizer exists (so main code answers
 * the 404). Each condition is turned off alone, with every other one on. C2 (revision 2, R2) is read from the
 * {@code commandLineArgs} property source only, and the tests pass the flag as real program arguments.
 */
class DevSampleAdminConditionsTest {

    private static final String FLAG = "sahaj.admin.dev-sample-enabled";
    private static final Pattern NONCE_IN_WARNING = Pattern.compile(
            "Send header X-Sahaj-Dev-Nonce: ([0-9a-f]{32})\\. The value changes at every start\\.$");

    /** What the admin realm and the web layer provide in the real application. */
    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }

        @Bean
        AdminPasswordHasher hasher() {
            return new AdminPasswordHasher(new SecureRandom());
        }

        @Bean
        TotpSecretCipher cipher() {
            return new TotpSecretCipher(new byte[32], new SecureRandom());
        }

        @Bean
        AdminProperties adminProperties() {
            return new AdminProperties(true, "x", "y", "", new AdminProperties.Totp(false), null);
        }
    }

    /** Every condition on except C2, which is the command-line flag added by {@link #runner}. */
    private static final String[] ALL_ON = {"spring.profiles.active=dev", "sahaj.admin.enabled=true",
            "sahaj.admin.jwt-secret=a-secret-with-enough-length-1234567",
            "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==", "server.address=127.0.0.1"};

    private static ApplicationContextRunner bare() {
        return new ApplicationContextRunner().withUserConfiguration(Dependencies.class,
                DevSampleAdminConfiguration.class);
    }

    /** The flag as a real program argument, which creates the property source {@code commandLineArgs}. */
    private static ApplicationContextRunner withArgs(ApplicationContextRunner runner, String... args) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource(args)));
    }

    private static ApplicationContextRunner runner(String... overrides) {
        List<String> values = new ArrayList<>(List.of(ALL_ON));
        values.addAll(List.of(overrides));
        return withArgs(bare().withPropertyValues(values.toArray(new String[0])), "--" + FLAG + "=true");
    }

    /** All other conditions on; the flag is whatever the given initializer puts in the environment (or nothing). */
    private static ApplicationContextRunner withoutArgs(Consumer<org.springframework.core.env.MutablePropertySources> add) {
        return bare().withPropertyValues(ALL_ON).withInitializer(
                context -> add.accept(context.getEnvironment().getPropertySources()));
    }

    private void assertOn(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DevEndpointGate.class);
            assertThat(context).hasSingleBean(DevSampleAdminNonce.class);
            assertThat(context).hasSingleBean(DevSampleAdminService.class);
            assertThat(context).hasSingleBean(DevSampleAdminRepository.class);
            assertThat(context).hasSingleBean(DevSampleAdminFlywayCustomizer.class);
            assertThat(context).hasSingleBean(DevSampleAdminStartupNotice.class);
        });
    }

    private void assertOff(String label, ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).as(label).doesNotHaveBean(DevEndpointGate.class);
            assertThat(context).as(label).doesNotHaveBean(DevSampleAdminNonce.class);
            assertThat(context).as(label).doesNotHaveBean(DevSampleAdminService.class);
            assertThat(context).as(label).doesNotHaveBean(DevSampleAdminRepository.class);
            assertThat(context).as(label).doesNotHaveBean(FlywayConfigurationCustomizer.class);
            assertThat(context).as(label).doesNotHaveBean(DevSampleAdminStartupNotice.class);
        });
    }

    @Test
    void everythingExistsWhenAllFiveConditionsHold() {
        assertOn(runner());
    }

    @Test
    void profilesDevAndTestTogetherStillCount() {
        assertOn(runner("spring.profiles.active=dev,test"));
    }

    @Test
    void c1AnyOtherProfileSwitchesItOff() {
        for (String profiles : new String[] {"test", "prod", "dev,prod", "dev,staging", "staging", "default"}) {
            assertOff("profiles " + profiles, runner("spring.profiles.active=" + profiles));
        }
        assertOff("no profile", withArgs(bare().withPropertyValues(ALL_ON[1], ALL_ON[2], ALL_ON[3], ALL_ON[4]),
                "--" + FLAG + "=true"));
    }

    // ---- C2: the command line only (ADR-008 R2) ----

    @Test
    void c2TheCommandLineValueMustBeTrueInAnyCase() {
        for (String value : new String[] {"true", "TRUE", "True", "tRuE"}) {
            assertOn(withArgs(bare().withPropertyValues(ALL_ON), "--" + FLAG + "=" + value));
        }
    }

    @Test
    void c2AnyOtherCommandLineValueSwitchesItOff() {
        for (String value : new String[] {"false", "yes", "1", "", "on", "enabled", "${X}", "${sahaj.admin.enabled}",
            "true ", " true", "truee", "t"}) {
            assertOff("--flag=" + value, withArgs(bare().withPropertyValues(ALL_ON), "--" + FLAG + "=" + value));
        }
        assertOff("--flag without a value", withArgs(bare().withPropertyValues(ALL_ON), "--" + FLAG));
    }

    @Test
    void c2ARepeatedCommandLineOptionIsNotTrue() {
        assertOff("true twice", withArgs(bare().withPropertyValues(ALL_ON), "--" + FLAG + "=true",
                "--" + FLAG + "=true"));
        assertOff("true then false", withArgs(bare().withPropertyValues(ALL_ON), "--" + FLAG + "=true",
                "--" + FLAG + "=false"));
    }

    @Test
    void c2RelaxedSpellingsOnTheCommandLineAreNotTheFlag() {
        for (String spelling : new String[] {"sahaj.admin.devSampleEnabled", "sahaj.admin.dev_sample_enabled",
            "SAHAJ_ADMIN_DEV_SAMPLE_ENABLED", "sahaj.admin.DEV-SAMPLE-ENABLED", "sahaj.admin.dev-sample-enabled.x"}) {
            assertOff(spelling, withArgs(bare().withPropertyValues(ALL_ON), "--" + spelling + "=true"));
        }
    }

    @Test
    void c2NoCommandLineSourceMeansOff() {
        assertOff("no source at all", withoutArgs(sources -> { }));
        assertOff("a command line without the flag", withArgs(bare().withPropertyValues(ALL_ON), "--other=true"));
        assertOff("an empty command line", withArgs(bare().withPropertyValues(ALL_ON)));
    }

    @Test
    void c2EveryOtherPropertySourceIsIgnoredEvenWithTheValueTrue() {
        // An ordinary property (a map source such as a test property or an application.yml)
        assertOff("property values", bare().withPropertyValues(ALL_ON).withPropertyValues(FLAG + "=true"));
        assertOff("yml", withoutArgs(sources -> sources.addFirst(new MapPropertySource(
                "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'",
                Map.of(FLAG, "true")))));
        assertOff("config import / additional location", withoutArgs(sources -> sources.addFirst(
                new MapPropertySource("Config resource 'file [application-local.yml]' via location "
                        + "'optional:file:./config/'", Map.of(FLAG, "true")))));
        assertOff("SPRING_APPLICATION_JSON", withoutArgs(sources -> sources.addFirst(
                new MapPropertySource("spring.application.json", Map.of(FLAG, "true")))));
    }

    @Test
    void c2AnEnvironmentVariableIsIgnoredInEveryFormItCanTake() {
        for (String name : new String[] {"SAHAJ_ADMIN_DEV_SAMPLE_ENABLED", "SAHAJ_ADMIN_DEV-SAMPLE-ENABLED",
            "sahaj.admin.dev-sample-enabled"}) {
            assertOff("env " + name, withoutArgs(sources -> sources.addFirst(
                    new SystemEnvironmentPropertySource("systemEnvironment", Map.of(name, "true")))));
        }
    }

    @Test
    void c2ASystemPropertyIsIgnoredEvenWhenItIsTheRealJvmProperty() {
        String previous = System.getProperty(FLAG);
        System.setProperty(FLAG, "true");
        try {
            assertOff("real -D", bare().withPropertyValues(ALL_ON).withSystemProperties(FLAG + "=true"));
            assertOff("real -D without the runner's own copy", bare().withPropertyValues(ALL_ON));
        } finally {
            if (previous == null) {
                System.clearProperty(FLAG);
            } else {
                System.setProperty(FLAG, previous);
            }
        }
    }

    @Test
    void c2TheFlagOnTheCommandLineWinsNothingOverAnotherSourceThatSaysFalse() {
        // the command line decides on its own: another source saying false does not switch it off
        assertOn(withArgs(bare().withPropertyValues(ALL_ON).withPropertyValues(FLAG + "=false"),
                "--" + FLAG + "=true"));
    }

    // ---- C2 through the real SpringApplication (the property source Spring Boot really builds) ----

    private static final String[] BOOT_ARGS = {"--spring.profiles.active=dev", "--sahaj.admin.enabled=true",
        "--sahaj.admin.jwt-secret=a-secret-with-enough-length-1234567",
        "--sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==", "--server.address=127.0.0.1",
        "--spring.main.banner-mode=off"};

    private static boolean bootHasGate(String[] extraArgs, Map<String, Object> defaults) {
        String[] args = new String[BOOT_ARGS.length + extraArgs.length];
        System.arraycopy(BOOT_ARGS, 0, args, 0, BOOT_ARGS.length);
        System.arraycopy(extraArgs, 0, args, BOOT_ARGS.length, extraArgs.length);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Dependencies.class,
                DevSampleAdminConfiguration.class).web(WebApplicationType.NONE).properties(defaults);
        try (ConfigurableApplicationContext context = builder.run(args)) {
            return context.getBeanNamesForType(DevEndpointGate.class).length == 1;
        }
    }

    @Test
    void springBootsOwnCommandLineArgumentsTurnItOnAndNothingElseDoes() {
        assertThat(bootHasGate(new String[] {"--" + FLAG + "=true"}, Map.of())).isTrue();
        assertThat(bootHasGate(new String[0], Map.of())).isFalse();
        // default properties (SpringApplication.setDefaultProperties) are not the command line
        assertThat(bootHasGate(new String[0], Map.of(FLAG, "true"))).isFalse();
    }

    @Test
    void springBootsSystemPropertyAndApplicationJsonDoNotTurnItOn() {
        String previousJson = System.getProperty("spring.application.json");
        String previousFlag = System.getProperty(FLAG);
        try {
            System.setProperty(FLAG, "true");
            assertThat(bootHasGate(new String[0], Map.of())).isFalse();
            System.clearProperty(FLAG);
            System.setProperty("spring.application.json", "{\"sahaj\":{\"admin\":{\"dev-sample-enabled\":true}}}");
            assertThat(bootHasGate(new String[0], Map.of())).isFalse();
        } finally {
            restore("spring.application.json", previousJson);
            restore(FLAG, previousFlag);
        }
    }

    private static void restore(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    // ---- C3 ----

    @Test
    void c3TheAdminRealmMustBeOnWithBothSecrets() {
        assertOff("realm off", runner("sahaj.admin.enabled=false"));
        assertOff("jwt secret missing", runner("sahaj.admin.jwt-secret="));
        assertOff("totp key missing", runner("sahaj.admin.totp-key="));
    }

    // ---- C4: proxy configuration and cloud platform (ADR-008 R6) ----

    @Test
    void c4AnyProxyConfigurationSwitchesItOff() {
        assertOff("remote ip header", runner("server.tomcat.remoteip.remote-ip-header=X-Forwarded-For"));
        assertOff("protocol header", runner("server.tomcat.remoteip.protocol-header=X-Forwarded-Proto"));
        assertOff("native", runner("server.forward-headers-strategy=native"));
        assertOff("framework", runner("server.forward-headers-strategy=framework"));
        assertOn(runner("server.forward-headers-strategy=none"));
        assertOn(runner("server.tomcat.remoteip.remote-ip-header="));
    }

    @Test
    void c4AnyValueOfTheCloudPlatformPropertyRefusesIncludingNone() {
        for (String value : new String[] {"kubernetes", "none", "NONE", "heroku", "cloud_foundry", "sap", "nomad",
            "azure_app_service", "something-else", "x"}) {
            assertOff("spring.main.cloud-platform=" + value, runner("spring.main.cloud-platform=" + value));
        }
        assertOn(runner("spring.main.cloud-platform="));
    }

    @Test
    void c4ADetectedCloudPlatformRefuses() {
        assertOff("kubernetes", withEnvironmentVariables(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1",
                "KUBERNETES_SERVICE_PORT", "443")));
        assertOff("heroku", withEnvironmentVariables(Map.of("DYNO", "web.1")));
        assertOff("cloud foundry", withEnvironmentVariables(Map.of("VCAP_APPLICATION", "{}", "VCAP_SERVICES", "{}")));
        // half a Kubernetes signal is not a platform
        assertOn(withEnvironmentVariables(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1")));
    }

    private static ApplicationContextRunner withEnvironmentVariables(Map<String, Object> variables) {
        return withArgs(bare().withPropertyValues(ALL_ON), "--" + FLAG + "=true").withInitializer(
                context -> context.getEnvironment().getPropertySources()
                        .addLast(new SystemEnvironmentPropertySource("systemEnvironment", variables)));
    }

    // ---- C5 ----

    @Test
    void c5TheServerMustBindToALoopbackAddress() {
        assertOff("0.0.0.0", runner("server.address=0.0.0.0"));
        assertOff("lan", runner("server.address=192.168.1.20"));
        assertOff("host name", runner("server.address=localhost"));
        assertOff("blank", runner("server.address="));
        assertOff("absent", withArgs(bare().withPropertyValues(ALL_ON[0], ALL_ON[1], ALL_ON[2], ALL_ON[3]),
                "--" + FLAG + "=true"));
        assertOn(runner("server.address=::1"));
        assertOn(runner("server.address=127.0.0.5"));
    }

    @Test
    void theFlywayCustomizerAddsTheCallbackLocationOnceAndKeepsTheOthers() {
        FluentConfiguration configuration = Flyway.configure()
                .locations("classpath:db/migration", "classpath:db/guard");
        DevSampleAdminFlywayCustomizer customizer = new DevSampleAdminFlywayCustomizer();

        customizer.customize(configuration);
        customizer.customize(configuration);

        assertThat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor))
                .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/guard", "classpath:db/dev-tools");
    }

    // ---- the start-up warning and the nonce (ADR-008 R1) ----

    private static List<String> warningsOf(Runnable start) {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            start.run();
        } finally {
            root.detachAppender(logs);
        }
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void theStartupWarningIsOneLineWithTheNonceAndTheFixedSentences() {
        List<String> warnings = warningsOf(() -> runner().run(context -> assertThat(context)
                .hasSingleBean(DevSampleAdminStartupNotice.class)));

        assertThat(warnings).hasSize(1);
        String line = warnings.get(0);
        assertThat(line).startsWith("Dev-only sample admin endpoint is on (dev-tools build). POST "
                + "/api/v1/admin/dev/sample-admin from this machine creates an admin once. Send header "
                + "X-Sahaj-Dev-Nonce: ");
        assertThat(line).endsWith(". The value changes at every start.");
        Matcher matcher = NONCE_IN_WARNING.matcher(line);
        assertThat(matcher.find()).isTrue();
        assertThat(line).doesNotContain("example.test").doesNotContain("password");
    }

    @Test
    void everyStartMakesANewNonceAndTheLineHoldsExactlyOne() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            List<String> warnings = warningsOf(() -> runner().run(context -> { }));
            Matcher matcher = NONCE_IN_WARNING.matcher(warnings.get(0));
            assertThat(matcher.find()).isTrue();
            seen.add(matcher.group(1));
            assertThat(warnings.get(0).split("[0-9a-f]{32}", -1)).hasSize(2);
        }
        assertThat(seen).hasSize(5);
    }

    @Test
    void theNonceIsInNoPropertySourceAndNoOtherLogLine() {
        List<ILoggingEvent> all = new ArrayList<>();
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        String[] nonce = new String[1];
        try {
            runner().run(context -> {
                nonce[0] = context.getBean(DevSampleAdminNonce.class).valueForStartupNotice();
                for (PropertySource<?> source : ((org.springframework.core.env.ConfigurableEnvironment)
                        context.getEnvironment()).getPropertySources()) {
                    if (source instanceof EnumerablePropertySource<?> enumerable) {
                        for (String name : enumerable.getPropertyNames()) {
                            Object value = source.getProperty(name);
                            assertThat(String.valueOf(value)).as(source.getName() + "/" + name)
                                    .doesNotContain(nonce[0]);
                        }
                    }
                }
                assertThat(context.getBean(DevSampleAdminNonce.class).toString()).doesNotContain(nonce[0]);
                assertThat(context.getBean(DevSampleAdminGate.class).toString()).doesNotContain(nonce[0]);
            });
            all.addAll(logs.list);
        } finally {
            root.detachAppender(logs);
        }
        List<String> carrying = all.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(nonce[0])).toList();
        assertThat(carrying).hasSize(1);
        assertThat(carrying.get(0)).contains("X-Sahaj-Dev-Nonce: " + nonce[0]);
    }

    @Test
    void theConditionHelpersAgreeWithTheRunner() {
        MockEnvironment dev = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        dev.setActiveProfiles("dev");
        assertThat(DevSampleAdminConditions.profilesAllowed(dev)).isTrue();
        assertThat(DevSampleAdminConditions.noProxyConfiguration(dev)).isTrue();
        assertThat(DevSampleAdminConditions.bindsToLoopback(dev)).isTrue();
        assertThat(DevSampleAdminConditions.flagOnCommandLine(dev)).isFalse();
        dev.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--" + FLAG + "=true"));
        assertThat(DevSampleAdminConditions.flagOnCommandLine(dev)).isTrue();
    }

    @Test
    void aNonConfigurableEnvironmentNeverHasTheFlag() {
        Environment plain = mock(Environment.class);

        assertThat(DevSampleAdminConditions.flagOnCommandLine(plain)).isFalse();
    }
}
