package com.sahaj.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-002 D4, enforcement test 1: nothing in {@code src/main} except the tenant binding class may
 * set a session setting that row level security trusts, and no pool may run init SQL.
 */
class TenantBindingSourceRuleTest {

    private static final String ALLOWED = "TenantBindingTransactionManager.java";
    private static final List<String> FORBIDDEN = List.of(
            "set local app.", "set_config('app.", "set_config(\"app.", "set session", "connection-init-sql",
            "connectioninitsql", "app.actor_kind");

    @Test
    void onlyTheTenantBindingClassSetsAppSettings() throws IOException {
        Path main = Path.of("src", "main");
        assertThat(main).isDirectory();

        try (Stream<Path> files = Files.walk(main)) {
            List<String> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(file -> !file.toString().contains("db" + java.io.File.separator + "migration"))
                    .filter(file -> !file.getFileName().toString().equals(ALLOWED))
                    .filter(file -> {
                        try {
                            String text = Files.readString(file).toLowerCase(Locale.ROOT);
                            return FORBIDDEN.stream().anyMatch(text::contains);
                        } catch (IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .map(Path::toString)
                    .toList();

            assertThat(offenders).as("files that set app.* session settings or pool init SQL").isEmpty();
        }
    }

    @Test
    void theBindingClassStillExistsSoTheRuleChecksSomething() {
        assertThat(Path.of("src", "main", "java", "com", "sahaj", "platform", "infrastructure", "tenancy", ALLOWED))
                .isRegularFile();
    }
}
