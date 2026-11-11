package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.PsqlRunner;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

/**
 * SAH-42 design case S29: the extended cleanup runbook {@code dev-tools-sample-admin-cleanup.sql} (step 1b and the self-check)
 * removes the bootstrap objects too, against the REAL objects in {@code sahaj_test}: the five functions and the state table are
 * gone after the first run, the definer holds no INSERT privilege on {@code platform_admin}, the second run changes nothing and
 * passes, and the production guard then passes in a database that is not a dev or test database (the point of the runbook).
 *
 * <p>PRECONDITIONS: the human prerequisites, the bootstrap objects (made by one dev-tools start with the switch and Flyway on; this
 * class never starts one) and a {@code psql}. In {@code finally} both dev-tools callbacks are applied again from the class path,
 * the same text Flyway runs, so the objects that existed before the test exist after it and the lock state is a clean slate.
 */
class BootstrapCleanupRunbookIT {

    private static String resource(String name) throws Exception {
        try (InputStream in = BootstrapCleanupRunbookIT.class.getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void apply(String sql) throws SQLException {
        try (Connection c = SampleAdminDb.migrator()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            c.commit();
        }
    }

    @Test
    void cleanupRemovesTheBootstrapObjectsAndTheInsertGrantTwiceSafelyAndTheGuardThenPassesAnywhere() throws Exception {
        BootstrapDb.assumeObjects();
        BootstrapDb.failIfAnAdminExists(); // an active sample account would be a legitimate finding of the guard (check 3)
        Path psql = PsqlRunner.findPsql();
        assumeTrue(psql != null, PsqlRunner.NOT_FOUND);
        boolean sampleBefore = SampleAdminDb.devFunctionPresent();
        try {
            PsqlRunner.Outcome first = PsqlRunner.runCleanup(psql, true);
            assertThat(first.exitCode()).as(first.output()).isZero();
            assertThat(first.output()).contains("Cleanup check passed for database \"sahaj_test\"");
            assertThat(BootstrapDb.tablePresent()).as("state table after the first run").isFalse();
            for (String function : BootstrapDb.FUNCTIONS) {
                try (Connection c = SampleAdminDb.migrator()) {
                    assertThat(SampleAdminDb.scalarBoolean(c, "select to_regprocedure(?) is not null", function))
                            .as(function + " after the first run").isFalse();
                    assertThat(SampleAdminDb.scalarBoolean(c,
                            "select has_any_column_privilege('sahaj_admin_definer','platform.platform_admin','INSERT')"))
                            .as("any INSERT privilege of the definer").isFalse();
                }
            }

            // With every dev-tools object gone the production guard passes in a database with any name.
            String guard = resource("/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql");
            try (Connection c = SampleAdminDb.migrator()) {
                c.setAutoCommit(false);
                for (String name : new String[] {"sahaj_staging", "sahaj_prod", "sahaj_dev_staging"}) {
                    try (Statement s = c.createStatement()) {
                        s.execute(guard.replace("current_database()", "'" + name + "'"));
                    } catch (PSQLException ex) {
                        throw new AssertionError("the guard raised in '" + name + "' after the cleanup: "
                                + ex.getServerErrorMessage().getMessage() + " / " + ex.getServerErrorMessage().getDetail(), ex);
                    }
                }
                c.rollback();
            }

            PsqlRunner.Outcome second = PsqlRunner.runCleanup(psql, true);
            assertThat(second.exitCode()).as(second.output()).isZero();
            assertThat(second.output()).contains("Cleanup check passed");
            assertThat(BootstrapDb.tablePresent()).as("still gone after the second run").isFalse();
        } finally {
            apply(resource("/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql"));
            if (sampleBefore) {
                apply(resource("/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql"));
            }
            BootstrapDb.resetState();
        }
        assertThat(BootstrapDb.objectsPresent()).as("the callback made the objects again").isTrue();
        assertThat(BootstrapDb.state().failures()).isZero();
    }
}
