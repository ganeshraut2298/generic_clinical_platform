package com.sahaj.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * SAH-78 (design note SAH-78-pharmacy-masters 5.1, R2-16): the migration that seeds the SAMPLE catalogue is guarded by the
 * Flyway placeholder {@code ${sahajSampleData}}. Flyway fails on an unresolved placeholder, so it must be defined in every
 * environment: false in {@code application.yml} (production included), true only in the dev and test profiles. Every
 * placeholder a migration uses must be defined the same way.
 */
class FlywayPlaceholderConfigTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> placeholders(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
            Map<String, Object> placeholders = (Map<String, Object>) flyway.get("placeholders");
            return placeholders == null ? Map.of() : placeholders;
        }
    }

    private static Object sampleData(String resource) throws IOException {
        return placeholders(resource).get("sahajSampleData");
    }

    @Test
    void theDefaultForEveryEnvironmentIsFalse() throws IOException {
        assertThat(String.valueOf(sampleData("application.yml"))).isEqualTo("false");
    }

    @Test
    void onlyTheDevAndTestProfilesTurnTheSampleDataOn() throws IOException {
        assertThat(String.valueOf(sampleData("application-dev.yml"))).isEqualTo("true");
        assertThat(String.valueOf(sampleData("application-test.yml"))).isEqualTo("true");
    }

    @Test
    void everyPlaceholderAMigrationUsesIsDefinedInTheBaseConfiguration() throws IOException {
        Path migrations = Path.of("src", "main", "resources", "db", "migration");
        if (!Files.isDirectory(migrations)) {
            migrations = Path.of("backend").resolve(migrations);
        }
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".sql"))::iterator) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = PLACEHOLDER.matcher(sql);
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }
        assertThat(used).as("placeholders used by db/migration").contains("sahajSampleData");
        assertThat(used).as("each must be a key under spring.flyway.placeholders in application.yml")
                .isSubsetOf(placeholders("application.yml").keySet());
    }
}
