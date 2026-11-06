package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * DEC-016 / ADR-008 test T13, positive control (R9). Runs only in a {@code -Pdev-tools} build, after {@code package}. It
 * proves that the entry scan of {@code PackagedJarDevToolsIT} is not blind: the dev-tools jar DOES hold the controller
 * class, the dev-tools SQL callback and the manifest attribute, its file name says {@code -devtools-}, and the production
 * output folder {@code target/classes} still holds no {@code devtools} folder (the separate build directory
 * {@code target-devtools} keeps dev classes out of it).
 */
class PackagedJarDevToolsBuildIT {

    private static Path jar() {
        String value = System.getProperty("sahaj.packagedJar");
        if (value == null || value.isBlank()) {
            fail("System property sahaj.packagedJar is not set; the failsafe configuration in backend/pom.xml must set it.");
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) {
            fail("The dev-tools jar does not exist: " + path + " (this test runs after the package phase).");
        }
        return path;
    }

    @Test
    void theDevToolsJarHoldsTheControllerTheSqlCallbackAndTheManifestAttribute() throws IOException {
        Path jar = jar();
        assertThat(jar.getFileName().toString()).contains("-devtools-");
        try (JarFile file = new JarFile(jar.toFile())) {
            List<String> names = Collections.list(file.entries()).stream().map(e -> e.getName()).toList();
            assertThat(names).contains("BOOT-INF/classes/com/sahaj/platform/devtools/admin/DevSampleAdminController.class",
                    "BOOT-INF/classes/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql",
                    "BOOT-INF/classes/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql");
            assertThat(file.getManifest().getMainAttributes().getValue("Sahaj-Dev-Tools")).isEqualTo("true");
        }
    }

    @Test
    void thePlainTargetClassesFolderHasNoDevToolsFolder() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        Path production = Path.of(basedir, "target", "classes", "com", "sahaj", "platform", "devtools");
        assertThat(Files.exists(production)).as(production + " must not exist").isFalse();
        assertThat(Files.exists(Path.of(basedir, "target", "classes", "db", "dev-tools"))).isFalse();
    }
}
