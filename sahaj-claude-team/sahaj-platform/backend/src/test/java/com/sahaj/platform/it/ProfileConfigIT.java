package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * SAH-1 criterion 9, configuration part: profile dev points at sahaj_dev and profile test at
 * sahaj_test, the pool user is sahaj_app, Flyway's user is sahaj_migrator, and no password is
 * written in a file. This reads the YAML files themselves with no environment variables set, so it
 * needs neither Spring nor a database and always runs.
 */
class ProfileConfigIT {

    private static final PropertyPlaceholderHelper PLACEHOLDERS = new PropertyPlaceholderHelper("${", "}", ":", true);

    /** Loads a YAML file and resolves ${NAME:default} to the default, as if no variable were set. */
    private static Map<String, String> load(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        Map<String, String> flat = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                flat.put(name, String.valueOf(enumerable.getProperty(name)));
            }
        }
        return flat;
    }

    private static String resolveWithNoEnvironment(String value) {
        return PLACEHOLDERS.replacePlaceholders(value, name -> null);
    }

    @Test
    void profileDevUsesSahajDevAsSahajAppAndFlywayAsSahajMigrator() throws IOException {
        Map<String, String> dev = load("application-dev.yml");
        assertThat(resolveWithNoEnvironment(dev.get("spring.datasource.url"))).endsWith("/sahaj_dev");
        assertThat(resolveWithNoEnvironment(dev.get("spring.flyway.url"))).endsWith("/sahaj_dev");
        assertThat(dev.get("spring.datasource.username")).isEqualTo("sahaj_app");
        assertThat(dev.get("spring.flyway.user")).isEqualTo("sahaj_migrator");
    }

    @Test
    void profileTestUsesSahajTestAsSahajAppAndFlywayAsSahajMigrator() throws IOException {
        Map<String, String> test = load("application-test.yml");
        assertThat(resolveWithNoEnvironment(test.get("spring.datasource.url"))).endsWith("/sahaj_test");
        assertThat(resolveWithNoEnvironment(test.get("spring.flyway.url"))).endsWith("/sahaj_test");
        assertThat(test.get("spring.datasource.username")).isEqualTo("sahaj_app");
        assertThat(test.get("spring.flyway.user")).isEqualTo("sahaj_migrator");
    }

    @Test
    void theTestUrlComesFromSahajTestDbUrlAndTheDevUrlFromSahajDbUrl() throws IOException {
        assertThat(load("application-test.yml").get("spring.datasource.url")).contains("${SAHAJ_TEST_DB_URL:");
        assertThat(load("application-dev.yml").get("spring.datasource.url")).contains("${SAHAJ_DB_URL:");
    }

    @Test
    void theTestProfileIsTheOnlyOneThatMayCleanTheDatabase() throws IOException {
        assertThat(load("application-test.yml").get("spring.flyway.clean-disabled")).isEqualTo("false");
        assertThat(load("application.yml").get("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(load("application-dev.yml")).doesNotContainKey("spring.flyway.clean-disabled");
    }

    @Test
    void everyPasswordComesFromAnEnvironmentVariableAndNoneIsWrittenInAFile() throws IOException {
        for (String file : List.of("application.yml", "application-dev.yml", "application-test.yml")) {
            load(file).forEach((key, value) -> {
                String lower = key.toLowerCase();
                if (lower.endsWith("password") || lower.endsWith("secret") || lower.contains("jwt.secret")) {
                    assertThat(value).as(file + " " + key).matches("\\$\\{[A-Z0-9_]+}");
                }
            });
        }
    }

    @Test
    void neitherProfileConnectsAsPostgresOrAnotherSuperuser() throws IOException {
        for (String file : List.of("application-dev.yml", "application-test.yml")) {
            Map<String, String> yml = load(file);
            assertThat(yml.values()).as(file).noneMatch(v -> v.equals("postgres"));
        }
    }
}
