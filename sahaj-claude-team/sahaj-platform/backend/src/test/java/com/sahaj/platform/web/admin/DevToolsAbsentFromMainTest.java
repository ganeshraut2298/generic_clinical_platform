package com.sahaj.platform.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-008 must-not-ship rule, unit part: nothing under {@code src/main} may be, contain or refer to the
 * dev-tools source set, and the controller class exists on the class path only in a build made with the
 * Maven profile {@code dev-tools} (which sets {@code sahaj.devtools=true} for the tests).
 */
class DevToolsAbsentFromMainTest {

    private static final String FORBIDDEN = "devtools";
    private static final String CONTROLLER = "com.sahaj.platform.devtools.admin.DevSampleAdminController";
    private static final String DEV_TOOLS_SQL = "db/dev-tools/afterMigrate__dev_tools_sample_admin.sql";
    private static final String GUARD_SQL = "db/guard/afterMigrate__platform_dev_tools_absent_guard.sql";

    private static boolean devToolsBuild() {
        return "true".equals(System.getProperty("sahaj.devtools"));
    }

    private static URL resource(String name) {
        return Thread.currentThread().getContextClassLoader().getResource(name);
    }

    private static Path mainRoot() {
        return Paths.get(System.getProperty("basedir", ".")).resolve("src").resolve("main");
    }

    @Test
    void srcMainHasNoDevToolsPackageDirectoryAndNoReferenceToIt() throws IOException {
        Path root = mainRoot();
        assertThat(root).as("src/main found from " + Paths.get("").toAbsolutePath()).isDirectory();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = root.relativize(path).toString().toLowerCase(Locale.ROOT);
                if (name.contains(FORBIDDEN)) {
                    offenders.add("path: " + root.relativize(path));
                } else if (Files.isRegularFile(path) && isText(name)
                        && new String(Files.readAllBytes(path), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)
                        .contains(FORBIDDEN)) {
                    offenders.add("content: " + root.relativize(path));
                }
            }
        }
        assertThat(offenders).as("src/main must not mention the dev-tools source set").isEmpty();
    }

    private static boolean isText(String name) {
        return name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".sql")
                || name.endsWith(".properties") || name.endsWith(".xml") || name.endsWith(".md")
                || name.endsWith(".json") || name.endsWith(".factories") || name.endsWith(".imports");
    }

    /**
     * ADR-008 R9, the class path half of the must-not-ship rule: without the dev-tools profile the package
     * directory, the Flyway callback folder and the sample admin SQL are not on the class path at all. The file lookup
     * is exact where directory lookups vary by class loader, so it is asserted as well. In a dev-tools build the SQL
     * file is found.
     */
    @Test
    void theDevToolsPackageFolderAndTheSampleAdminSqlAreOnTheClassPathOnlyInADevToolsBuild() {
        if (devToolsBuild()) {
            assertThat(resource(DEV_TOOLS_SQL)).as(DEV_TOOLS_SQL).isNotNull();
            assertThat(resource("com/sahaj/platform/devtools/admin/DevSampleAdminController.class")).isNotNull();
        } else {
            assertThat(resource("com/sahaj/platform/devtools/")).as("package folder").isNull();
            assertThat(resource("db/dev-tools/")).as("callback folder").isNull();
            assertThat(resource(DEV_TOOLS_SQL)).as(DEV_TOOLS_SQL).isNull();
            assertThat(resource("com/sahaj/platform/devtools/admin/DevSampleAdminController.class")).isNull();
        }
    }

    /**
     * ADR-008 R3: the guard callback is main code, so it is in every build, with or without the profile. It is the
     * database-side check that no dev-tools objects survive in a database that is not {@code sahaj_dev} or
     * {@code sahaj_test}.
     */
    @Test
    void theGuardCallbackIsInEveryBuildAndTheFlywayLocationsNameIt() throws IOException {
        assertThat(resource(GUARD_SQL)).as(GUARD_SQL).isNotNull();
        String guard = new String(Files.readAllBytes(mainRoot().resolve("resources").resolve(GUARD_SQL)),
                StandardCharsets.UTF_8);
        assertThat(guard).contains("^sahaj_(dev|test)$").contains("admin_dev_create_sample_admin");
        String yml = new String(Files.readAllBytes(mainRoot().resolve("resources").resolve("application.yml")),
                StandardCharsets.UTF_8);
        assertThat(yml).contains("locations: classpath:db/migration,classpath:db/guard");
    }

    @Test
    void theControllerIsOnTheClassPathOnlyInADevToolsBuild() throws Exception {
        if ("true".equals(System.getProperty("sahaj.devtools"))) {
            assertThat(Class.forName(CONTROLLER)).isNotNull();
        } else {
            assertThatThrownBy(() -> Class.forName(CONTROLLER)).isInstanceOf(ClassNotFoundException.class);
        }
    }
}
