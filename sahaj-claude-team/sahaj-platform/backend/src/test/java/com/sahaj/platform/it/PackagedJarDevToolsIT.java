package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * DEC-016 / ADR-008 test T13 (must-not-ship), failsafe, runs in {@code mvn verify} after {@code package}: the jar
 * that was just built must contain nothing of the dev-only sample admin endpoint. It opens the real artefact
 * ({@code sahaj.packagedJar}, set by the pom) and never passes vacuously: a missing property or a missing file is a
 * failure, not a skip.
 *
 * <p>Known limit (ADR-008 R9, H-DEV-8): {@code -DskipTests} and {@code -DskipITs} skip this test. A human who builds a
 * jar for a shared machine should also run it (see docs/test-plans/DEC-016.md).
 *
 * <p>In a {@code -Pdev-tools} build the dev-tools classes are supposed to be there; the "absent" tests then say so and
 * stand down, and {@code PackagedJarDevToolsBuildIT} (dev-tools test source set) is the positive control instead.
 */
class PackagedJarDevToolsIT {

    private static final String PROPERTY = "sahaj.packagedJar";
    private static final String GUARD = "BOOT-INF/classes/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql";

    private static boolean devToolsBuild() {
        return "true".equals(System.getProperty("sahaj.devtools"));
    }

    private static Path jar() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            fail("System property " + PROPERTY + " is not set. The failsafe configuration in backend/pom.xml must set it "
                    + "to the packaged jar; this check must never pass without a jar.");
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) {
            fail("The packaged jar does not exist: " + path + ". The test runs after the package phase; "
                    + "it cannot run on a build that did not package.");
        }
        return path;
    }

    private static List<String> entryNames(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return Collections.list(jar.entries()).stream().map(e -> e.getName()).toList();
        }
    }

    @Test
    void thePackagedJarPropertyIsSetAndTheFileExistsAndIsNotEmpty() throws IOException {
        Path jar = jar();
        assertThat(Files.size(jar)).as("jar size").isGreaterThan(1_000_000L);
        assertThat(entryNames(jar)).as("a repackaged Spring Boot jar").contains("BOOT-INF/classes/com/sahaj/SahajApplication.class");
    }

    @Test
    void theProductionJarContainsNoDevToolsEntry() throws IOException {
        assumeFalse(devToolsBuild(), "Stands down in a -Pdev-tools build (sahaj.devtools=true): the dev-tools jar is "
                + "meant to hold these entries; PackagedJarDevToolsBuildIT checks that build instead.");
        Path jar = jar();
        List<String> forbidden = new ArrayList<>();
        for (String name : entryNames(jar)) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.startsWith("BOOT-INF/classes/com/sahaj/platform/devtools/")
                    || name.startsWith("BOOT-INF/classes/db/dev-tools/")
                    || name.startsWith("com/sahaj/platform/devtools/")
                    || name.startsWith("db/dev-tools/")
                    || name.contains("DevSample")
                    || name.endsWith("afterMigrate__dev_tools_sample_admin.sql")
                    || (name.startsWith("BOOT-INF/classes/") && lower.contains("/devtools/"))) {
                forbidden.add(name);
            }
        }
        assertThat(forbidden).as("dev-tools entries in " + jar.getFileName()).isEmpty();
    }

    /**
     * SAH-42 / ADR-018 "Must-not-ship" (T13 extended): the bootstrap registration has its own classes, its own Flyway location
     * and its own SQL callback; none of them may be in a normal jar. Each rule is listed on its own so a failure names the rule.
     */
    @Test
    void theProductionJarContainsNoBootstrapRegistrationClassSqlCallbackOrFlywayLocation() throws IOException {
        assumeFalse(devToolsBuild(), "Stands down in a -Pdev-tools build (sahaj.devtools=true): the dev-tools jar is meant to hold "
                + "these entries; PackagedJarDevToolsBuildIT checks that build instead.");
        Path jar = jar();
        List<String> names = entryNames(jar);
        assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/classes/com/sahaj/platform/devtools/bootstrap/")).toList())
                .as("class folder of the bootstrap registration").isEmpty();
        assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/classes/db/bootstrap-dev-tools/")
                || n.startsWith("db/bootstrap-dev-tools/")).toList()).as("Flyway location db/bootstrap-dev-tools/ (Revision 3 name)").isEmpty();
        // ADR-018 Revision 3 (code review finding 7): the old name must exist in no jar at all, a regression check.
        assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/classes/db/dev-tools-bootstrap/")
                || n.startsWith("db/dev-tools-bootstrap/")).toList()).as("old Flyway location db/dev-tools-bootstrap/").isEmpty();
        assertThat(names.stream().filter(n -> n.endsWith("afterMigrate__dev_tools_admin_bootstrap.sql")).toList())
                .as("the bootstrap SQL callback, wherever it sits").isEmpty();
        assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/classes/com/sahaj/platform/devtools/") && n.contains("Bootstrap")).toList())
                .as("any entry with Bootstrap in its name below the devtools package").isEmpty();
        assertThat(names.stream().filter(n -> n.contains("BootstrapPinHashTool")).toList()).as("the PIN hash tool").isEmpty();
        assertThat(names.stream().filter(n -> n.contains("dev-tools-bootstrap") || n.contains("bootstrap-dev-tools")
                || n.contains("dev_tools_admin_bootstrap")).toList())
                .as("any name that mentions either bootstrap location name or the callback").isEmpty();
    }

    @Test
    void theProductionJarStillHoldsTheMainCodeThatAnswersTheEmpty404ForTheReservedBootstrapPath() throws IOException {
        // The reserved-path filter is main code (ADR-018 D1): with the bootstrap classes absent it is what turns every
        // /api/v1/admin/bootstrap/** request into the empty 404. It must therefore ship in every build.
        assertThat(entryNames(jar())).contains("BOOT-INF/classes/com/sahaj/platform/web/admin/AdminDevPathFilter.class",
                "BOOT-INF/classes/com/sahaj/platform/web/admin/AdminDevNotFound.class", "BOOT-INF/classes/com/sahaj/platform/web/admin/DevEndpointGate.class");
    }

    @Test
    void theGuardCallbackInTheJarHoldsTheTwoSah42ChecksSoANonDevDatabaseWithTheBootstrapObjectsStopsTheStart() throws IOException {
        try (JarFile file = new JarFile(jar().toFile())) {
            var entry = file.getEntry(GUARD);
            assertThat(entry).as(GUARD).isNotNull();
            try (var in = file.getInputStream(entry)) {
                String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(sql).contains("platform_admin_bootstrap_state").contains("admin\\_bootstrap\\_%");
            }
        }
    }

    @Test
    void theBootstrapClassesCannotBeLoadedWithoutTheProfile() {
        assumeFalse(devToolsBuild(), "Stands down in a -Pdev-tools build (sahaj.devtools=true).");
        for (String name : List.of("com.sahaj.platform.devtools.bootstrap.BootstrapController",
                "com.sahaj.platform.devtools.bootstrap.BootstrapService", "com.sahaj.platform.devtools.bootstrap.BootstrapPinHashTool",
                "com.sahaj.platform.devtools.bootstrap.BootstrapGate")) {
            assertThatThrownBy(() -> Class.forName(name)).as(name + " on the test class path").isInstanceOf(ClassNotFoundException.class);
        }
        assertThat(PackagedJarDevToolsIT.class.getResource("/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql"))
                .as("the callback file on the class path").isNull();
        assertThat(PackagedJarDevToolsIT.class.getResource("/db/dev-tools-bootstrap/afterMigrate__dev_tools_admin_bootstrap.sql"))
                .as("the callback file under the old folder name on the class path").isNull();
    }

    @Test
    void theProductionJarHasNoDevToolsManifestAttributeAndNoDevToolsName() throws IOException {
        assumeFalse(devToolsBuild(), "Stands down in a -Pdev-tools build (sahaj.devtools=true).");
        Path jar = jar();
        try (JarFile file = new JarFile(jar.toFile())) {
            assertThat(file.getManifest()).as("manifest").isNotNull();
            assertThat(file.getManifest().getMainAttributes().getValue("Sahaj-Dev-Tools"))
                    .as("manifest attribute Sahaj-Dev-Tools").isNull();
        }
        assertThat(jar.getFileName().toString()).as("jar file name").doesNotContain("-devtools-");
    }

    @Test
    void theJarHoldsTheMainCodeGuardCallbackThatFailsAStartWhenDevToolsObjectsExistElsewhere() throws IOException {
        // The guard is main code (ADR-008 R3): it ships in every build, the dev-tools build included.
        assertThat(entryNames(jar())).as("guard callback in the jar").contains(GUARD);
    }

    @Test
    void theControllerClassCannotBeLoadedWithoutTheProfile() {
        assumeFalse(devToolsBuild(), "Stands down in a -Pdev-tools build (sahaj.devtools=true).");
        assertThatThrownBy(() -> Class.forName("com.sahaj.platform.devtools.admin.DevSampleAdminController"))
                .as("the dev-tools controller on the test class path")
                .isInstanceOf(ClassNotFoundException.class);
    }
}
