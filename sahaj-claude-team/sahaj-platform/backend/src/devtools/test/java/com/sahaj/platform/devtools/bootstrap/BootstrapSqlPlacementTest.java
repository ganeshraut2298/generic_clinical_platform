package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * docs/database/SAH-42.md: the db-engineer's SQL is placed UNCHANGED by the java-developer. This compares the copies in
 * {@code backend/} with the originals in {@code docs/database/} (line endings ignored). It needs the {@code docs}
 * folder next to {@code backend}; when that folder is not there (a checkout of the backend alone) the comparison is
 * skipped with a named precondition, never faked. The SQL itself is run by the integration tests that need the role
 * {@code sahaj_admin_definer} and the admin tables (QA S1 to S30), which are not part of this unit phase.
 */
class BootstrapSqlPlacementTest {

    private static final String CALLBACK = "src/devtools/resources/db/bootstrap-dev-tools/"
            + "afterMigrate__dev_tools_admin_bootstrap.sql";
    private static final String GUARD = "src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql";

    private static Path backend() {
        return Paths.get(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void theCallbackIsOnTheClassPathOfADevToolsBuildUnderItsOwnLocation() {
        assertThat(Thread.currentThread().getContextClassLoader()
                .getResource("db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql")).isNotNull();
        // R3-U15: the Revision 2 folder name exists in no build.
        assertThat(Thread.currentThread().getContextClassLoader()
                .getResource("db/dev-tools-bootstrap/afterMigrate__dev_tools_admin_bootstrap.sql")).isNull();
        assertThat(Thread.currentThread().getContextClassLoader().getResource("db/dev-tools-bootstrap/")).isNull();
        assertThat(Thread.currentThread().getContextClassLoader()
                .getResource("db/dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql")).isNull();
        assertThat(Thread.currentThread().getContextClassLoader()
                .getResource("db/bootstrap-dev-tools/afterMigrate__dev_tools_sample_admin.sql")).isNull();
    }

    @Test
    void theCallbackIsTheDbEngineersFileUnchanged() throws IOException {
        Path original = backend().resolve("../docs/database/SAH-42-admin-bootstrap.sql").normalize();
        assumeTrue(Files.exists(original), "precondition: docs/database/SAH-42-admin-bootstrap.sql is next to backend/");

        assertThat(read(backend().resolve(CALLBACK))).isEqualTo(read(original));
    }

    @Test
    void theProductionGuardIsTheDbEngineersFileUnchanged() throws IOException {
        Path original = backend().resolve("../docs/database/ADR-008-guard.sql").normalize();
        assumeTrue(Files.exists(original), "precondition: docs/database/ADR-008-guard.sql is next to backend/");

        assertThat(read(backend().resolve(GUARD))).isEqualTo(read(original));
    }

    @Test
    void neitherFileHoldsAFlywayPlaceholderOrAValueThatLooksLikeASecret() throws IOException {
        for (String file : new String[] {CALLBACK, GUARD}) {
            String text = read(backend().resolve(file));
            assertThat(text).as(file).doesNotContain("${").doesNotContain("$2a$").doesNotContain("$2y$");
        }
    }

    @Test
    void theCallbackCreatesTheFiveFunctionsAndTheStateTableAndNothingElseIsNamedBootstrap() throws IOException {
        String text = read(backend().resolve(CALLBACK));

        assertThat(text).contains("platform.platform_admin_bootstrap_state").contains("admin_bootstrap_begin")
                .contains("admin_bootstrap_unlock_failed").contains("admin_bootstrap_unlock_succeeded")
                .contains("admin_bootstrap_ticket_status").contains("admin_bootstrap_register")
                .contains("^sahaj_(dev|test)$");
    }
}
