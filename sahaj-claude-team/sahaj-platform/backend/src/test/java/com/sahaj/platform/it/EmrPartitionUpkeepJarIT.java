package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * DEC-049 item 3, test case S-78 (docs/database/SAH-53.md "Partition upkeep"), failsafe, runs in {@code mvn verify} after
 * {@code package}: the jar that was just built holds the afterMigrate callback that makes the monthly partitions of
 * {@code opd.emr_record} on every deploy, the migration with the two functions, and the application's check. Like
 * {@link PackagedJarDevToolsIT} it opens the real artefact ({@code sahaj.packagedJar}, set by the pom) and never passes
 * vacuously: a missing property or file is a failure. The callback is main code, so it is in every build, the dev-tools build
 * included (no stand-down).
 */
class EmrPartitionUpkeepJarIT {

    private static final String PROPERTY = "sahaj.packagedJar";
    private static final String CALLBACK_NAME = "db/guard/afterMigrate__opd_emr_record_partition_upkeep.sql";
    private static final String MIGRATION_NAME = "db/migration/V2026_12_13_1000__opd_emr_record_partition_upkeep.sql";
    private static final String MONITOR_NAME = "com/sahaj/opd/infrastructure/partition/EmrPartitionUpkeepMonitor.class";

    private static Path jar() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            fail("System property " + PROPERTY + " is not set. The failsafe configuration in backend/pom.xml must set it "
                    + "to the packaged jar; this check must never pass without a jar.");
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) {
            fail("The packaged jar does not exist: " + path + ". The test runs after the package phase; it cannot run on a build "
                    + "that did not package.");
        }
        return path;
    }

    private static List<String> entryNames(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return Collections.list(jar.entries()).stream().map(e -> e.getName()).toList();
        }
    }

    private static String entryText(Path jarPath, String name) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getEntry("BOOT-INF/classes/" + name);
            assertThat(entry).as("BOOT-INF/classes/" + name).isNotNull();
            try (InputStream in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void theJarHoldsTheUpkeepCallbackTheMigrationAndTheMonitor() throws IOException {
        List<String> names = entryNames(jar());
        assertThat(names).contains("BOOT-INF/classes/" + CALLBACK_NAME, "BOOT-INF/classes/" + MIGRATION_NAME,
                "BOOT-INF/classes/" + MONITOR_NAME);
    }

    @Test
    void theCallbackInTheJarRunsTheUpkeepOfTwelveMonthsAndIsTheSameFileAsTheSource() throws IOException {
        String inJar = entryText(jar(), CALLBACK_NAME);
        assertThat(inJar).contains("opd.emr_record_upkeep_partitions(12)").contains("to_regprocedure");
        try (InputStream source = getClass().getResourceAsStream("/" + CALLBACK_NAME)) {
            assertThat(source).as("the callback on the test class path").isNotNull();
            assertThat(new String(source.readAllBytes(), StandardCharsets.UTF_8)).as("jar and class path copy").isEqualTo(inJar);
        }
    }

    @Test
    void theCallbackIsNotInTheMigrationFolderWhereOnlyVAndRFilesAreAllowedAndTheGuardCallbackIsStillThere() throws IOException {
        List<String> names = entryNames(jar());
        assertThat(names.stream().filter(n -> n.startsWith("BOOT-INF/classes/db/migration/") && n.contains("afterMigrate")).toList())
                .as("a callback in db/migration").isEmpty();
        assertThat(names).contains("BOOT-INF/classes/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql");
    }
}
