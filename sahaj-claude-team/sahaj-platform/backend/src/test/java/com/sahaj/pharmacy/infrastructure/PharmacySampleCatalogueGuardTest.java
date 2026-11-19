package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * SAH-78 security M1: the afterMigrate guard that stops start-up when the fake sample catalogue is found in a database that
 * is not sahaj_dev or sahaj_test. This checks the shape of the callback file without a database (it runs from the packaged
 * classpath, next to the dev-tools guard); the raising path itself runs against PostgreSQL in an integration test, by
 * replacing the database name at the TEST SUBSTITUTION POINT (like DevToolsGuardCallbackIT).
 */
class PharmacySampleCatalogueGuardTest {

    private static final String GUARD = "/db/guard/afterMigrate__pharmacy_sample_catalogue_absent_guard.sql";

    private static String sql() throws IOException {
        try (InputStream in = PharmacySampleCatalogueGuardTest.class.getResourceAsStream(GUARD)) {
            assertThat(in).as(GUARD + " is on the classpath of every build").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The statements without the comment lines. */
    private static String code() throws IOException {
        StringBuilder code = new StringBuilder();
        for (String line : sql().split("\n")) {
            String withoutComment = line.contains("--") ? line.substring(0, line.indexOf("--")) : line;
            code.append(withoutComment).append('\n');
        }
        return code.toString().toLowerCase(Locale.ROOT);
    }

    @Test
    void theGuardIsAFlywayAfterMigrateCallbackInTheGuardFolder() {
        assertThat(GUARD).startsWith("/db/guard/afterMigrate__");
        assertThat(PharmacySampleCatalogueGuardTest.class.getResource("/db/migration/afterMigrate__pharmacy_sample_catalogue_absent_guard.sql"))
                .as("a callback is not a migration").isNull();
    }

    @Test
    void itDoesNothingInSahajDevAndSahajTestAndNamesNoOtherDatabaseAsSafe() throws IOException {
        String code = code();
        assertThat(code).contains("current_database()").contains("'^sahaj_(dev|test)$'");
        // the database name is read in exactly one place, the test substitution point
        assertThat(code.split("current_database\\(\\)", -1)).hasSize(2);
        assertThat(sql()).contains("TEST SUBSTITUTION POINT");
    }

    @Test
    void itStopsStartUpWhenSampleRowsExistAndSkipsWhenTheTableIsNotThereYet() throws IOException {
        String code = code();
        assertThat(code).contains("to_regclass('pharmacy.catalogue_product') is null")
                .contains("from pharmacy.catalogue_product where sample")
                .contains("raise exception").contains("errcode = 'p0001'");
    }

    @Test
    void itOnlyReadsAndNeverChangesAnything() throws IOException {
        // no statement starts with a write or a privilege change (the hint text may mention the manual delete)
        assertThat(code()).doesNotContainPattern("(?m)^\\s*(delete|update|insert|truncate|drop|alter|grant|revoke|create)\\b");
    }

    @Test
    void itHasNoFlywayPlaceholderAndTheMessageCarriesNoRowValue() throws IOException {
        assertThat(sql()).doesNotContain("${");
        String code = code();
        // the raise names the database and the number of rows only
        assertThat(code).contains("v_db, v_count").doesNotContain("select code").doesNotContain("select name");
    }

    @Test
    void theSeedMigrationItGuardsIsNotEditedAndStillReadsThePlaceholder() throws IOException {
        try (InputStream in = PharmacySampleCatalogueGuardTest.class.getResourceAsStream(
                "/db/migration/V2026_12_17_1030__pharmacy_catalogue_sample_seed.sql")) {
            assertThat(in).isNotNull();
            String seed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(seed).contains("'${sahajSampleData}' = 'true'");
            assertThat(seed).doesNotContain("current_database()");
        }
    }
}
