package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sahaj.platform.it.devsample.PsqlRunner;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * DEC-016 / ADR-008 test T19, part 2: the cleanup runbook against the REAL dev-tools objects. With the dev-tools function and
 * the seven-column grant in {@code sahaj_test}, the first run removes the function and every INSERT privilege of the definer,
 * the second run changes nothing and passes, and a later dev-tools callback (re-applied here from the class path, the same
 * text Flyway runs) recreates exactly the designed state.
 *
 * <p>PRECONDITIONS: the admin tables, the role and the dev-tools function exist (human prerequisites, then one start of a
 * dev-tools build with Flyway on), and {@code psql} is available. Whatever happens, the callback is applied again in
 * {@code finally} so that other tests find the function.
 */
class DevToolsCleanupAfterCallbackIT {

    private static String callback() throws Exception {
        try (InputStream in = DevToolsCleanupAfterCallbackIT.class
                .getResourceAsStream("/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void applyCallback() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(callback());
            }
            c.commit();
        }
    }

    @Test
    void cleanupRemovesTheRealObjectsTwiceSafelyAndTheCallbackRestoresThem() throws Exception {
        SampleAdminDb.assumeDevFunction();
        Path psql = PsqlRunner.findPsql();
        assumeTrue(psql != null, PsqlRunner.NOT_FOUND);
        try {
            PsqlRunner.Outcome first = PsqlRunner.runCleanup(psql, true);
            assertThat(first.exitCode()).as(first.output()).isZero();
            assertThat(first.output()).contains("Cleanup check passed for database \"sahaj_test\"");
            assertThat(SampleAdminDb.devFunctionPresent()).as("function after the first run").isFalse();
            try (Connection c = SampleAdminDb.migrator()) {
                assertThat(SampleAdminDb.scalarBoolean(c,
                        "select has_any_column_privilege('sahaj_admin_definer','platform.platform_admin','INSERT')"))
                        .as("any INSERT privilege of the definer").isFalse();
                assertThat(SampleAdminDb.scalarBoolean(c,
                        "select has_table_privilege('sahaj_admin_definer','platform.platform_admin','INSERT')")).isFalse();
            }

            PsqlRunner.Outcome second = PsqlRunner.runCleanup(psql, true);
            assertThat(second.exitCode()).as(second.output()).isZero();
            assertThat(second.output()).contains("Cleanup check passed");
        } finally {
            applyCallback();
        }

        assertThat(SampleAdminDb.devFunctionPresent()).as("function after the callback ran again").isTrue();
        try (Connection c = SampleAdminDb.migrator(); var s = c.prepareStatement("""
                select attname from pg_attribute
                 where attrelid = 'platform.platform_admin'::regclass and attnum > 0 and not attisdropped
                   and has_column_privilege('sahaj_admin_definer', attrelid, attnum, 'INSERT')
                """); var rs = s.executeQuery()) {
            TreeSet<String> columns = new TreeSet<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            assertThat(columns).containsExactlyInAnyOrder("id", "email", "full_name", "password_hash", "totp_secret_enc",
                    "totp_key_version", "totp_enrolled_at");
            assertThat(SampleAdminDb.scalarBoolean(c, "select has_function_privilege('sahaj_app', ?, 'EXECUTE')",
                    SampleAdminDb.FUNCTION_SIGNATURE)).isTrue();
        }
    }
}
