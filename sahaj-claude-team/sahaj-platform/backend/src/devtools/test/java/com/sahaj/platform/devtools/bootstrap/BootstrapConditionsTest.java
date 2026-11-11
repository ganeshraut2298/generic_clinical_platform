package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.AdminTokenHasher;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.devtools.admin.DevSampleAdminFlywayCustomizer;
import com.sahaj.platform.devtools.admin.DevSampleAdminGate;
import com.sahaj.platform.infrastructure.admin.AdminKeyMaterial;
import com.sahaj.platform.infrastructure.admin.AdminProperties;
import com.sahaj.platform.web.admin.DevEndpointGate;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ADR-018 D2, conditions B2 to B7: the bootstrap beans exist only when every one holds, and each is turned off alone with
 * every other one on. The start-up line (BR2) is one WARN with a fixed sentence, never a value of the environment.
 */
class BootstrapConditionsTest {

    /** Shaped like a cost-12 BCrypt hash; made up for this test, it hashes nothing. */
    private static final String HASH = "$2a$12$" + "q".repeat(53);

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

        @Bean
        AdminKeyMaterial keys() {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 7);
            return AdminKeyMaterial.validate(new AdminProperties(true, "a-secret-with-enough-length-1234567",
                    Base64.getEncoder().encodeToString(key), "", new AdminProperties.Totp(false), null), "other");
        }

        @Bean
        AdminTokenHasher tokenHasher() {
            return new AdminTokenHasher("a-secret-with-enough-length-1234567");
        }

        @Bean
        AdminAuthRepository authRepository() {
            return mock(AdminAuthRepository.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private static final String[] ALL_ON = {"spring.profiles.active=dev", "sahaj.admin.enabled=true",
            "sahaj.admin.jwt-secret=a-secret-with-enough-length-1234567",
            "sahaj.admin.totp-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMzJieXRlcw==", "server.address=127.0.0.1",
            "sahaj.admin.bootstrap.enabled=true", "sahaj.admin.bootstrap.pin-hash=" + HASH};

    private static ApplicationContextRunner bare() {
        return new ApplicationContextRunner().withUserConfiguration(Dependencies.class, BootstrapConfiguration.class,
                BootstrapStartupNoticeConfiguration.class);
    }

    /** Every condition on, then the overrides (a later value wins). */
    private static ApplicationContextRunner runner(String... overrides) {
        List<String> values = new ArrayList<>(List.of(ALL_ON));
        values.addAll(List.of(overrides));
        return bare().withPropertyValues(values.toArray(new String[0]));
    }

    private static ApplicationContextRunner without(String property) {
        List<String> values = new ArrayList<>();
        for (String value : ALL_ON) {
            if (!value.startsWith(property + "=")) {
                values.add(value);
            }
        }
        return bare().withPropertyValues(values.toArray(new String[0]));
    }

    private void assertOn(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DevEndpointGate.class);
            assertThat(context).hasSingleBean(BootstrapGate.class);
            assertThat(context).hasSingleBean(BootstrapPinVerifier.class);
            assertThat(context).hasSingleBean(BootstrapTicketHasher.class);
            assertThat(context).hasSingleBean(BootstrapRepository.class);
            assertThat(context).hasSingleBean(BootstrapService.class);
            assertThat(context).hasSingleBean(BootstrapFlywayCustomizer.class);
            assertThat(context).hasSingleBean(BootstrapStartupNotice.class);
        });
    }

    private void assertOff(String label, ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).as(label).doesNotHaveBean(DevEndpointGate.class);
            assertThat(context).as(label).doesNotHaveBean(BootstrapGate.class);
            assertThat(context).as(label).doesNotHaveBean(BootstrapPinVerifier.class);
            assertThat(context).as(label).doesNotHaveBean(BootstrapTicketHasher.class);
            assertThat(context).as(label).doesNotHaveBean(BootstrapRepository.class);
            assertThat(context).as(label).doesNotHaveBean(BootstrapService.class);
            assertThat(context).as(label).doesNotHaveBean(BootstrapFlywayCustomizer.class);
        });
    }

    @Test
    void everythingExistsWhenAllConditionsHold() {
        assertOn(runner());
    }

    @Test
    void profilesDevAndTestTogetherStillCount() {
        assertOn(runner("spring.profiles.active=dev,test"));
    }

    @Test
    void b2AnyOtherProfileOrNoDevSwitchesItOff() {
        assertOff("prod", runner("spring.profiles.active=prod"));
        assertOff("dev,prod", runner("spring.profiles.active=dev,prod"));
        assertOff("test only", runner("spring.profiles.active=test"));
        assertOff("dev,staging", runner("spring.profiles.active=dev,staging"));
        assertOff("none", without("spring.profiles.active"));
    }

    @Test
    void b3TheSwitchMustBeTrueInAnyLetterCaseAndNothingElse() {
        assertOn(runner("sahaj.admin.bootstrap.enabled=TRUE"));
        assertOn(runner("sahaj.admin.bootstrap.enabled=True"));
        for (String value : new String[] {"false", "", "yes", "1", "on", "tru", "truee"}) {
            assertOff("[" + value + "]", runner("sahaj.admin.bootstrap.enabled=" + value));
        }
        assertOff("absent", without("sahaj.admin.bootstrap.enabled"));
    }

    @Test
    void b3AnEnvironmentVariableInTheNameOfTheEnvFileTurnsItOn() {
        ApplicationContextRunner runner = without("sahaj.admin.bootstrap.enabled").withInitializer(context ->
                context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                        "systemEnvironment", Map.of("SAHAJ_ADMIN_BOOTSTRAP_ENABLED", "true"))));
        assertOn(runner);
    }

    @Test
    void b4TheHashMustBeABcryptHashOfCostTwelveToSixteen() {
        for (int cost = 12; cost <= 16; cost++) {
            assertOn(runner("sahaj.admin.bootstrap.pin-hash=$2a$" + cost + "$" + "q".repeat(53)));
        }
        assertOn(runner("sahaj.admin.bootstrap.pin-hash=$2y$12$" + "./AZaz09".repeat(6) + "./AZa"));
        assertOn(runner("sahaj.admin.bootstrap.pin-hash=$2b$14$" + "q".repeat(53)));
        for (String bad : new String[] {"", " ", "plain", "$2a$11$" + "q".repeat(53), "$2a$10$" + "q".repeat(53),
                "$2a$17$" + "q".repeat(53), "$2a$09$" + "q".repeat(53), "$2x$12$" + "q".repeat(53),
                "$1$12$" + "q".repeat(53), "$2a$12$" + "q".repeat(52), "$2a$12$" + "q".repeat(54),
                "$2a$12$" + "q".repeat(52) + "!", "'$2a$12$" + "q".repeat(53) + "'", "$argon2id$v=19$m=65536,t=3,p=1$abc$def", "$2a$12" + "q".repeat(53),
                "2a$12$" + "q".repeat(53)}) {
            assertOff("[" + bad + "]", runner("sahaj.admin.bootstrap.pin-hash=" + bad));
        }
        assertOff("absent", without("sahaj.admin.bootstrap.pin-hash"));
    }

    @Test
    void b3AndB4DoNotTrimTheirValues() {
        MockEnvironment padded = new MockEnvironment().withProperty(BootstrapConditions.ENABLED, " true")
                .withProperty(BootstrapConditions.PIN_HASH, HASH + " ");
        MockEnvironment leading = new MockEnvironment().withProperty(BootstrapConditions.ENABLED, "true ")
                .withProperty(BootstrapConditions.PIN_HASH, " " + HASH);
        MockEnvironment newline = new MockEnvironment().withProperty(BootstrapConditions.PIN_HASH, HASH + "\n");

        assertThat(BootstrapConditions.switchOn(padded)).isFalse();
        assertThat(BootstrapConditions.switchOn(leading)).isFalse();
        assertThat(BootstrapConditions.pinHashValid(padded)).isFalse();
        assertThat(BootstrapConditions.pinHashValid(leading)).isFalse();
        assertThat(BootstrapConditions.pinHashValid(newline)).isFalse();
    }

    @Test
    void b5TheAdminRealmMustBeOnWithBothSecrets() {
        assertOff("disabled", runner("sahaj.admin.enabled=false"));
        assertOff("no jwt secret", runner("sahaj.admin.jwt-secret="));
        assertOff("no totp key", runner("sahaj.admin.totp-key="));
        assertOff("absent", without("sahaj.admin.enabled"));
    }

    @Test
    void b6AnyProxyConfigurationOrCloudPlatformSwitchesItOff() {
        assertOff("framework", runner("server.forward-headers-strategy=framework"));
        assertOff("native", runner("server.forward-headers-strategy=native"));
        assertOn(runner("server.forward-headers-strategy=none"));
        assertOff("remote ip header", runner("server.tomcat.remoteip.remote-ip-header=X-Forwarded-For"));
        assertOff("protocol header", runner("server.tomcat.remoteip.protocol-header=X-Forwarded-Proto"));
        assertOff("cloud none", runner("spring.main.cloud-platform=none"));
        assertOff("cloud kubernetes", runner("spring.main.cloud-platform=kubernetes"));
    }

    @Test
    void b7TheServerMustBindToALoopbackAddress() {
        assertOn(runner("server.address=::1"));
        assertOn(runner("server.address=127.0.0.5"));
        assertOff("all interfaces", runner("server.address=0.0.0.0"));
        assertOff("lan", runner("server.address=192.168.1.5"));
        assertOff("name", runner("server.address=localhost"));
        assertOff("blank", runner("server.address="));
        assertOff("absent", without("server.address"));
    }

    @Test
    void theCommandLineIsNotNeededAndNotSpecial() {
        // Unlike the sample admin (ADR-008 R2) the bootstrap switch is read from the environment (H-BOOT-2).
        assertOn(runner().withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource("--unrelated=1"))));
    }

    // ---- the two dev-only features side by side ----

    private static ApplicationContextRunner both() throws ClassNotFoundException {
        // The sample admin's configuration class is package-private; the context takes it by name.
        Class<?> sampleConfiguration = Class.forName("com.sahaj.platform.devtools.admin.DevSampleAdminConfiguration");
        return new ApplicationContextRunner()
                .withUserConfiguration(Dependencies.class, BootstrapConfiguration.class,
                        BootstrapStartupNoticeConfiguration.class, sampleConfiguration,
                        // both controllers must still get their own gate when two gates exist
                        Class.forName("com.sahaj.platform.devtools.admin.DevSampleAdminController"),
                        BootstrapController.class)
                .withPropertyValues(ALL_ON)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SimpleCommandLinePropertySource("--sahaj.admin.dev-sample-enabled=true")));
    }

    @Test
    void withBothFeaturesOnThereAreTwoGatesAndEachRefusesThePathOfTheOther() throws Exception {
        both().run(context -> {
            assertThat(context).hasSingleBean(BootstrapGate.class).hasSingleBean(DevSampleAdminGate.class);
            assertThat(context.getBeansOfType(DevEndpointGate.class)).hasSize(2);
            assertThat(context.getBeanProvider(DevEndpointGate.class).orderedStream().count()).isEqualTo(2);

            org.springframework.mock.web.MockHttpServletRequest bootstrap =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", BootstrapGate.UNLOCK_PATH);
            bootstrap.addHeader("Host", "localhost");
            bootstrap.addHeader("Content-Type", "application/json");
            bootstrap.addHeader("X-Sahaj-Dev-Nonce", "ab".repeat(16));
            org.springframework.mock.web.MockHttpServletRequest sample =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", DevSampleAdminGate.PATH);
            sample.addHeader("Host", "localhost");
            sample.addHeader("Content-Type", "application/json");

            assertThat(context.getBean(DevSampleAdminGate.class).permits(bootstrap)).isFalse();
            assertThat(context.getBean(BootstrapGate.class).permits(sample)).isFalse();
            assertThat(context.getBean(BootstrapGate.class).permits(bootstrap)).isTrue();
        });
    }

    @Test
    void theFlywayLocationOfEachFeatureIsItsOwn() {
        FluentConfiguration configuration = Flyway.configure().locations("classpath:db/migration", "classpath:db/guard");
        BootstrapFlywayCustomizer customizer = new BootstrapFlywayCustomizer();

        customizer.customize(configuration);
        customizer.customize(configuration);

        assertThat(Arrays.stream(configuration.getLocations()).map(Location::getDescriptor)).containsExactlyInAnyOrder(
                "classpath:db/migration", "classpath:db/guard", "classpath:db/bootstrap-dev-tools");
        assertThat(BootstrapFlywayCustomizer.LOCATION).isNotEqualTo(DevSampleAdminFlywayCustomizer.LOCATION);
    }

    /**
     * R3-U15 (code review finding 7, N2): no Flyway location of the app is a string prefix of another. The list is built
     * from the locations in application.yml and the constants of the two customizers, so a rename is caught here.
     */
    @Test
    void noFlywayLocationIsAPrefixOfAnotherAndTheBootstrapOneIsExactlyTheNewName() {
        assertThat(BootstrapFlywayCustomizer.LOCATION).isEqualTo("classpath:db/bootstrap-dev-tools");

        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String configured = yaml.getObject().getProperty("spring.flyway.locations");
        assertThat(configured).as("spring.flyway.locations in application.yml").isNotBlank();

        List<String> locations = new ArrayList<>();
        for (String location : configured.split(",")) {
            locations.add(location.trim());
        }
        locations.add(DevSampleAdminFlywayCustomizer.LOCATION);
        locations.add(BootstrapFlywayCustomizer.LOCATION);
        assertThat(locations).hasSize(4).doesNotHaveDuplicates();

        for (String first : locations) {
            for (String second : locations) {
                if (!first.equals(second)) {
                    assertThat(second).as(first + " must not be a prefix of " + second).doesNotStartWith(first);
                }
            }
        }
    }

    // ---- the start-up line (BR2) ----

    private static List<ILoggingEvent> logsOf(Runnable start) {
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
        return logs.list;
    }

    private static List<String> warningsOf(ApplicationContextRunner runner) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logsOf(() -> runner.run(context -> { }))) {
            if (event.getLevel() == Level.WARN && event.getLoggerName().contains("BootstrapStartupNotice")) {
                lines.add(event.getFormattedMessage());
            }
        }
        return lines;
    }

    @Test
    void whenEverythingHoldsTheLineSaysOnWithTheFixedSentence() {
        assertThat(warningsOf(runner())).containsExactly("Admin bootstrap registration is on (dev-tools build). "
                + "/admin/register works from this machine until an admin exists.");
    }

    @Test
    void eachReasonHasItsFixedSentenceAndOnlyTheFirstFailingOneIsGiven() {
        assertThat(warningsOf(runner("sahaj.admin.bootstrap.pin-hash=")))
                .containsExactly("Admin bootstrap registration is off: the PIN hash is missing or not a valid BCrypt "
                        + "hash (cost 12 to 16).");
        assertThat(warningsOf(runner("spring.profiles.active=prod")))
                .containsExactly("Admin bootstrap registration is off: the active profiles are not dev.");
        assertThat(warningsOf(runner("sahaj.admin.enabled=false")))
                .containsExactly("Admin bootstrap registration is off: the admin realm is off.");
        assertThat(warningsOf(runner("server.forward-headers-strategy=framework")))
                .containsExactly("Admin bootstrap registration is off: proxy settings or a cloud platform are present.");
        assertThat(warningsOf(runner("server.address=0.0.0.0")))
                .containsExactly("Admin bootstrap registration is off: server.address is not a loopback address.");
        // several failing at once: the hash comes first, then profiles, realm, proxy, address
        assertThat(warningsOf(runner("sahaj.admin.bootstrap.pin-hash=x", "spring.profiles.active=prod",
                "server.address=0.0.0.0"))).hasSize(1).first().asString().contains("PIN hash");
        assertThat(warningsOf(runner("spring.profiles.active=prod", "sahaj.admin.enabled=false",
                "server.address=0.0.0.0"))).hasSize(1).first().asString().contains("profiles");
    }

    @Test
    void theLineIsWrittenWhenTheSwitchIsTrueEvenIfTheBeansCannotExist() {
        List<String> warnings = warningsOf(runner("sahaj.admin.enabled=false"));

        assertThat(warnings).hasSize(1);
    }

    @Test
    void nothingIsLoggedAndNoNoticeExistsWhenTheSwitchIsNotTrue() {
        for (String value : new String[] {"false", "", "yes"}) {
            assertThat(warningsOf(runner("sahaj.admin.bootstrap.enabled=" + value))).as(value).isEmpty();
        }
        assertThat(warningsOf(without("sahaj.admin.bootstrap.enabled"))).isEmpty();
        without("sahaj.admin.bootstrap.enabled").run(context ->
                assertThat(context).doesNotHaveBean(BootstrapStartupNotice.class));
    }

    @Test
    void noLogLineOfTheStartCarriesTheHashItsLengthItsPrefixOrAnyKey() {
        List<ILoggingEvent> events = logsOf(() -> runner().run(context -> { }));
        events.addAll(logsOf(() -> runner("server.address=0.0.0.0").run(context -> { })));
        events.addAll(logsOf(() -> runner("sahaj.admin.bootstrap.pin-hash=" + "$2a$12$" + "q".repeat(52))
                .run(context -> { })));

        for (ILoggingEvent event : events) {
            String text = event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy());
            assertThat(text).doesNotContain("qqqq").doesNotContain("$2a$").doesNotContain("a-secret-with-enough")
                    .doesNotContain("dGVzdC1rZXkt");
        }
    }

    @Test
    void theHelpersAgreeWithTheRunner() {
        MockEnvironment on = new MockEnvironment().withProperty(BootstrapConditions.ENABLED, "true")
                .withProperty(BootstrapConditions.PIN_HASH, HASH).withProperty("sahaj.admin.enabled", "true")
                .withProperty("sahaj.admin.jwt-secret", "a").withProperty("sahaj.admin.totp-key", "b")
                .withProperty("server.address", "127.0.0.1");
        on.setActiveProfiles("dev");

        assertThat(BootstrapConditions.switchOn(on)).isTrue();
        assertThat(BootstrapConditions.pinHashValid(on)).isTrue();
        assertThat(BootstrapConditions.adminRealmOn(on)).isTrue();
        assertThat(BootstrapConditions.firstFailingReason(on)).isEmpty();
        assertThat(BootstrapConditions.switchOn(new MockEnvironment())).isFalse();
        assertThat(BootstrapConditions.pinHashValid(new MockEnvironment())).isFalse();
    }
}
