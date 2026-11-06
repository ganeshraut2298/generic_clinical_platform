package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sahaj.platform.it.devsample.PsqlRunner;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DEC-016 / ADR-008 test T19 (part 1): the human runbook {@code docs/database/runbooks/dev-tools-sample-admin-cleanup.sql}
 * run twice by a real {@code psql} against {@code sahaj_test} as {@code sahaj_migrator}, exactly as the runbook says.
 *
 * <p>Preconditions, each named when it skips: {@code psql} is found (on the PATH or under
 * {@code C:\Program Files\PostgreSQL\*\bin}), otherwise T19 is the manual check M-3 of docs/test-plans/DEC-016.md; and
 * the real dev-tools function is not in {@code sahaj_test} (the cleanup would remove it; that state is covered by
 * {@code DevToolsCleanupAfterCallbackIT} in the dev-tools test source set, which puts it back).
 *
 * <p>While the human prerequisites are missing, the function can still be tested: a stand-in function with the exact
 * signature is created (committed, as the script commits) and removed again in {@code finally}. The part about the
 * INSERT grant needs the role and the tables and says so when it skips. The case "outside sahaj_dev and sahaj_test, with a
 * sample admin, a session and a pre-auth row" needs a throw-away database and is the manual check M-3.
 */
class DevToolsCleanupScriptIT {

    private static final String FUNCTION = "platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)";

    private Path psql;
    private boolean standInCreated;

    private Outcome run(boolean onErrorStop) throws Exception {
        return new Outcome(PsqlRunner.runCleanup(psql, onErrorStop));
    }

    private record Outcome(int exitCode, String output) {
        Outcome(PsqlRunner.Outcome other) {
            this(other.exitCode(), other.output());
        }
    }

    @BeforeEach
    void locate() {
        psql = PsqlRunner.findPsql();
        assumeTrue(psql != null, PsqlRunner.NOT_FOUND);
        assumeTrue(!SampleAdminDb.devFunctionPresent(), "PRECONDITION NOT MET: the real dev-tools function exists in "
                + "sahaj_test; the cleanup would remove it. DevToolsCleanupAfterCallbackIT (dev-tools build) covers that state "
                + "and restores it.");
    }

    /** Only ever drops the stand-in this test created itself, never the real function. */
    @AfterEach
    void removeStandIn() throws Exception {
        if (standInCreated) {
            standInCreated = false;
            try (Connection c = SampleAdminDb.migrator(); Statement s = c.createStatement()) {
                s.execute("drop function if exists " + FUNCTION);
            }
        }
    }

    private void createStandIn() throws Exception {
        standInCreated = true;
        try (Connection c = SampleAdminDb.migrator(); Statement s = c.createStatement()) {
            s.execute("drop function if exists " + FUNCTION);
            s.execute("create function platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text) "
                    + "returns boolean language sql as 'select false'");
        }
    }

    private boolean functionPresent() {
        return SampleAdminDb.devFunctionPresent();
    }

    @Test
    void theFirstRunRemovesTheFunctionAndPassesItsOwnCheck() throws Exception {
        createStandIn();
        assertThat(functionPresent()).isTrue();

        Outcome first = run(true);

        assertThat(first.exitCode()).as(first.output()).isZero();
        assertThat(first.output()).contains("Cleanup check passed for database \"sahaj_test\"");
        assertThat(functionPresent()).as("function after the cleanup").isFalse();
        assertThat(first.output()).as("the script prints no email, hash or secret")
                .doesNotContain("@example.test").doesNotContain("$2a$");
    }

    @Test
    void theSecondRunChangesNothingAndPasses() throws Exception {
        createStandIn();
        assertThat(run(true).exitCode()).isZero();
        long auditBefore = SampleAdminDb.tablesPresent() ? auditRows() : 0;

        Outcome second = run(true);

        assertThat(second.exitCode()).as(second.output()).isZero();
        assertThat(second.output()).contains("Cleanup check passed");
        assertThat(functionPresent()).isFalse();
        assertThat(SampleAdminDb.tablesPresent() ? auditRows() : 0).as("audit rows after the second run").isEqualTo(auditBefore);
    }

    @Test
    void withoutOnErrorStopItStopsAtOnceWithTheMessageAndChangesNothing() throws Exception {
        createStandIn();

        Outcome outcome = run(false);

        assertThat(outcome.output()).contains("Stopped: run this file with psql -v ON_ERROR_STOP=1");
        assertThat(functionPresent()).as("function untouched when the script stopped").isTrue();
    }

    @Test
    void theInsertGrantOfTheDefinerIsRevokedAndTheSampleAccountIsLeftAloneInSahajTest() throws Exception {
        SampleAdminDb.assumePrerequisites();
        long activeBefore;
        try (Connection c = SampleAdminDb.migrator(); Statement s = c.createStatement()) {
            s.execute("grant insert (id) on platform.platform_admin to sahaj_admin_definer");
            activeBefore = SampleAdminDb.scalarLong(c,
                    "select count(*) from platform.platform_admin where email = 'sample-admin@example.test' and active");
        }

        Outcome outcome = run(true);

        assertThat(outcome.exitCode()).as(outcome.output()).isZero();
        try (Connection c = SampleAdminDb.migrator()) {
            assertThat(SampleAdminDb.scalarBoolean(c,
                    "select has_any_column_privilege('sahaj_admin_definer','platform.platform_admin','INSERT')"))
                    .as("any INSERT privilege of the definer after the cleanup").isFalse();
            assertThat(SampleAdminDb.scalarBoolean(c,
                    "select has_table_privilege('sahaj_admin_definer','platform.platform_admin','INSERT')")).isFalse();
            assertThat(SampleAdminDb.scalarLong(c,
                    "select count(*) from platform.platform_admin where email = 'sample-admin@example.test' and active"))
                    .as("sample account in sahaj_test is dev data and is left alone").isEqualTo(activeBefore);
        }
        assertThat(outcome.output()).contains("left as it is");
    }

    private long auditRows() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarLong(c, "select count(*) from platform.platform_admin_audit");
        }
    }
}
