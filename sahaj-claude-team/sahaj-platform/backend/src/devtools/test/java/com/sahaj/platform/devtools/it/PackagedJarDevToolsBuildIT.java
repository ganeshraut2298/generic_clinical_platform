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
        // SAH-42: the bootstrap's own Flyway location and callback are not in the production output folder either.
        assertThat(Files.exists(Path.of(basedir, "target", "classes", "db", "bootstrap-dev-tools"))).isFalse();
        assertThat(Files.exists(Path.of(basedir, "target", "classes", "db", "dev-tools-bootstrap"))).isFalse();
        assertThat(Files.exists(production.resolve("bootstrap"))).isFalse();
    }

    /** SAH-42 / ADR-018 T13 positive control: the entries that {@code PackagedJarDevToolsIT} forbids in a normal jar are here. */
    @Test
    void theDevToolsJarHoldsTheBootstrapRegistrationClassesTheSqlCallbackAndTheHashTool() throws IOException {
        try (JarFile file = new JarFile(jar().toFile())) {
            List<String> names = Collections.list(file.entries()).stream().map(e -> e.getName()).toList();
            assertThat(names).contains(
                    "BOOT-INF/classes/com/sahaj/platform/devtools/bootstrap/BootstrapController.class",
                    "BOOT-INF/classes/com/sahaj/platform/devtools/bootstrap/BootstrapGate.class",
                    "BOOT-INF/classes/com/sahaj/platform/devtools/bootstrap/BootstrapService.class",
                    "BOOT-INF/classes/com/sahaj/platform/devtools/bootstrap/BootstrapPinHashTool.class",
                    "BOOT-INF/classes/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql");
            // The two dev-tools features keep separate Flyway locations (ADR-018 D1): each callback only in its own folder.
            assertThat(names).doesNotContain("BOOT-INF/classes/db/dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql",
                    "BOOT-INF/classes/db/bootstrap-dev-tools/afterMigrate__dev_tools_sample_admin.sql");
            assertThat(names).contains("BOOT-INF/classes/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql");
            // ADR-018 Revision 3 (code review finding 7): the old folder name exists in no jar, so no dev-tools Flyway
            // location is a string prefix of another.
            assertThat(names.stream().filter(n -> n.contains("dev-tools-bootstrap")).toList())
                    .as("old folder name db/dev-tools-bootstrap in the dev-tools jar").isEmpty();
        }
    }

    /**
     * The command the human is told to run (docs/handoffs/SAH-42.md, ADR-018 D3), end to end as far as a test can: the Spring Boot
     * launcher finds the tool class in the packaged jar and the tool refuses to run without a console, so a hash can never be
     * made from echoed input. A test JVM has no console, so the typing itself is covered by {@code BootstrapPinHashToolTest} and by
     * {@code BootstrapSwitchesIT.b4TheHashThatTheRealToolPrints...} (the real tool code with a fake hidden prompt). The jar is
     * only read, never written.
     */
    @Test
    void theHashToolCommandStartsFromThePackagedJarAndRefusesWithoutAConsoleWithNothingOnStandardOutput() throws Exception {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(javaExe.toString(),
                "-Dloader.main=com.sahaj.platform.devtools.bootstrap.BootstrapPinHashTool",
                "-cp", jar().toString(), "org.springframework.boot.loader.launch.PropertiesLauncher");
        Process process = builder.start();
        process.getOutputStream().close();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        Thread outReader = new Thread(() -> copy(process.getInputStream(), out));
        Thread errReader = new Thread(() -> copy(process.getErrorStream(), err));
        outReader.start();
        errReader.start();
        boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        outReader.join(5000);
        errReader.join(5000);
        String stdout = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        String stderr = err.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(finished).as("the tool ended by itself. stderr: " + stderr).isTrue();
        assertThat(process.exitValue()).as("exit status 1 for a refusal. stderr: " + stderr).isEqualTo(1);
        assertThat(stderr).as("the fixed sentence").contains("Run this in a terminal window (no console found).");
        assertThat(stdout).as("nothing on standard output: no hash, no prompt, no PIN").isEmpty();
    }

    private static void copy(java.io.InputStream in, java.io.ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ex) {
            // the test fails on the missing text
        }
    }
}
