package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * DEC-016 / ADR-008 test T18, part 1: the main-code guard callback
 * {@code db/guard/afterMigrate__platform_dev_tools_absent_guard.sql} (R3, review L2).
 *
 * <p>The guard reads the database name in one place. A test cannot rename {@code sahaj_test}, so it loads the SQL text
 * from the class path (the exact file that ships) and builds a variant in memory with
 * {@code current_database()} replaced by a string literal (test code only; no shipped file is changed). Every case runs
 * as {@code sahaj_migrator} inside one transaction with a savepoint per case and ends in a rollback, so
 * {@code sahaj_test} is left as it was.
 *
 * <p>Cases that only need the function or no dev object at all run today. The cases about the INSERT grant and the
 * sample account need the admin tables and the role {@code sahaj_admin_definer} (human prerequisites) and say so when
 * they skip. The "objects missing" path (no role, no table, no function) is exercised by the all-absent case while those
 * prerequisites are missing, as they are today.
 */
class DevToolsGuardCallbackIT {

    private static final String FUNCTION = "platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)";
    private static final String SAMPLE_EMAIL = "sample-admin@example.test";

    private String guard;
    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql")) {
            assertThat(in).as("guard callback on the class path").isNotNull();
            guard = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(guard).contains("current_database()");
        connection = SampleAdminDb.migrator();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        connection.rollback();
        connection.close();
    }

    private String variant(String databaseName) {
        return guard.replace("current_database()", "'" + databaseName + "'");
    }

    /** Runs the SQL; returns the server error or null. The case is undone with the savepoint either way. */
    private PSQLException run(String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return null;
        } catch (PSQLException ex) {
            return ex;
        } finally {
            connection.rollback(savepoint);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Puts a stand-in function with the exact signature in place (the real one, if present, is dropped inside the transaction). */
    private void givenFunction() throws SQLException {
        exec("drop function if exists " + FUNCTION);
        exec("create function platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text) "
                + "returns boolean language sql as 'select false'");
    }

    /** No function, no INSERT privilege of the definer, no active sample account: every dev-tools object absent. */
    private void givenNothing() throws SQLException {
        exec("drop function if exists " + FUNCTION);
        dropBootstrapObjects();
        if (SampleAdminDb.roleExists() && SampleAdminDb.tablesPresent()) {
            exec("revoke insert on platform.platform_admin from sahaj_admin_definer");
            exec("update platform.platform_admin set active = false where email = '" + SAMPLE_EMAIL + "'");
        }
    }

    private static void assertPasses(PSQLException error, String label) {
        assertThat(error == null ? "no error" : error.getServerErrorMessage().getMessage()).as(label).isEqualTo("no error");
    }

    private static void assertRaised(PSQLException error, String label) {
        assertThat(error != null).as(label).isTrue();
    }

    private static void assertNoSecrets(PSQLException error) {
        ServerErrorMessage server = error.getServerErrorMessage();
        String all = server.getMessage() + " " + server.getDetail() + " " + server.getHint();
        assertThat(all).doesNotContain("@example.test").doesNotContain("$2a$").doesNotContain("$2b$")
                .doesNotContain("Failing row").doesNotContain("password");
    }

    @Test
    void theShippedTextRunsCleanInSahajTestWhateverTheDevToolsStateIs() throws Exception {
        assertPasses(run(guard), "the unchanged guard in sahaj_test");
    }

    @Test
    void noDevToolsObjectAtAllPassesWithAnyDatabaseName() throws Exception {
        givenNothing();
        assertPasses(run(variant("sahaj_staging")), "sahaj_staging with no dev-tools object");
        assertPasses(run(variant("sahaj_prod")), "sahaj_prod with no dev-tools object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_staging", "sahaj_prod", "sahaj_dev_staging", "sahaj_test_prod", "sahaj_dev_1",
            "sahaj_test_1", "sahaj_dev ", "Sahaj_Dev", "sahaj"})
    void onlyTheFunctionExistsInAnyOtherDatabaseRaisesAndNamesTheFunction(String name) throws Exception {
        givenNothing();
        givenFunction();
        PSQLException error = run(variant(name));
        assertRaised(error, "guard with database name '" + name + "' and the function present");
        assertThat(error.getSQLState()).as("SQLSTATE; server message: " + error.getServerErrorMessage().getMessage()).isEqualTo("P0001");
        ServerErrorMessage server = error.getServerErrorMessage();
        assertThat(server.getMessage()).contains("Dev-tools admin objects (sample admin or bootstrap registration) found in database \"" + name + "\"");
        assertThat(server.getDetail()).contains("the function platform.admin_dev_create_sample_admin exists")
                .doesNotContain("INSERT").doesNotContain("account");
        assertThat(server.getHint()).contains("dev-tools-sample-admin-cleanup.sql").contains("both dev-tools features");
        assertNoSecrets(error);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_dev", "sahaj_test"})
    void theTwoExactDevAndTestNamesNeverRaiseWhateverIsPresent(String name) throws Exception {
        givenNothing();
        givenFunction();
        assertPasses(run(variant(name)), "guard in '" + name + "' with the function present");
    }

    @Test
    void onlyTheTableLevelInsertGrantRaisesAndNamesThePrivilege() throws Exception {
        SampleAdminDb.assumePrerequisites();
        givenNothing();
        exec("grant insert on platform.platform_admin to sahaj_admin_definer");
        PSQLException error = run(variant("sahaj_staging"));
        assertRaised(error, "guard raised");
        assertThat(error.getServerErrorMessage().getDetail())
                .contains("sahaj_admin_definer holds INSERT on platform.platform_admin")
                .doesNotContain("function").doesNotContain("account");
        assertNoSecrets(error);
    }

    @Test
    void onlyAColumnLevelInsertGrantRaises() throws Exception {
        SampleAdminDb.assumePrerequisites();
        givenNothing();
        exec("grant insert (id) on platform.platform_admin to sahaj_admin_definer");
        PSQLException error = run(variant("sahaj_staging"));
        assertRaised(error, "a single column grant is caught");
        assertThat(error.getServerErrorMessage().getDetail()).contains("holds INSERT");
        assertNoSecrets(error);
    }

    private void givenSampleAccount(boolean active) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "update platform.platform_admin set active = ? where email = ?")) {
            update.setBoolean(1, active);
            update.setString(2, SAMPLE_EMAIL);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        insert into platform.platform_admin (id, email, full_name, password_hash, active)
                        values (gen_random_uuid(), ?, 'Sample admin', ?, ?)
                        """)) {
                    insert.setString(1, SAMPLE_EMAIL);
                    insert.setString(2, "$2a$12$" + "a".repeat(53));
                    insert.setBoolean(3, active);
                    insert.executeUpdate();
                }
            }
        }
    }

    @Test
    void onlyAnActiveSampleAccountRaisesAndNamesTheAccountNotTheEmail() throws Exception {
        SampleAdminDb.assumePrerequisites();
        givenNothing();
        givenSampleAccount(true);
        PSQLException error = run(variant("sahaj_staging"));
        assertRaised(error, "guard raised");
        assertThat(error.getServerErrorMessage().getDetail()).contains("an active dev sample admin account exists")
                .doesNotContain("function").doesNotContain("INSERT");
        assertNoSecrets(error);
    }

    @Test
    void anInactiveSampleAccountDoesNotRaise() throws Exception {
        SampleAdminDb.assumePrerequisites();
        givenNothing();
        givenSampleAccount(false);
        assertPasses(run(variant("sahaj_staging")), "switched-off account passes (after the cleanup runbook)");
    }

    @Test
    void allThreeFindingsAreListedTogether() throws Exception {
        SampleAdminDb.assumePrerequisites();
        givenNothing();
        givenFunction();
        exec("grant insert (id) on platform.platform_admin to sahaj_admin_definer");
        givenSampleAccount(true);
        PSQLException error = run(variant("sahaj_staging"));
        assertRaised(error, "guard raised");
        assertThat(error.getServerErrorMessage().getDetail())
                .contains("the function").contains("holds INSERT").contains("active dev sample admin account");
    }

    // ---- SAH-42 / ADR-018: checks 4 and 5 (the bootstrap state table and any bootstrap function) --------------------------

    /** Removes the SAH-42 objects inside the (rolled back) transaction, whatever their signature, so "nothing" really is nothing. */
    private void dropBootstrapObjects() throws SQLException {
        exec("drop table if exists platform.platform_admin_bootstrap_state");
        exec("do $d$ declare r record; begin "
                + "for r in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'platform' and p.proname like 'admin\\_bootstrap\\_%' loop "
                + "execute 'drop function ' || r.sig; end loop; end $d$");
    }

    private void givenBootstrapTable() throws SQLException {
        exec("create table platform.platform_admin_bootstrap_state (id smallint primary key)");
    }

    private void givenBootstrapFunction(String name) throws SQLException {
        exec("create function platform." + name + "(integer) returns integer language sql as 'select 1'");
    }

    @Test
    void theShippedGuardTextHasBothSah42ChecksAndStillOnlyReads() throws Exception {
        assertThat(guard).contains("to_regclass('platform.platform_admin_bootstrap_state')")
                .contains("p.proname like 'admin\\_bootstrap\\_%'")
                .contains("the table platform.platform_admin_bootstrap_state exists")
                .contains("a platform.admin_bootstrap_ function exists");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_staging", "sahaj_prod", "sahaj_dev_staging", "sahaj_test_prod", "sahaj_dev_1", "sahaj_test_1",
            "sahaj_dev ", "Sahaj_Dev"})
    void onlyTheBootstrapStateTableRaisesAndNamesItInWordsAndPointsAtTheRunbook(String name) throws Exception {
        givenNothing();
        givenBootstrapTable();
        PSQLException error = run(variant(name));
        assertRaised(error, "guard with database name '" + name + "' and only the bootstrap table");
        ServerErrorMessage server = error.getServerErrorMessage();
        assertThat(server.getDetail()).isEqualTo("the table platform.platform_admin_bootstrap_state exists");
        assertThat(server.getHint()).contains("dev-tools-sample-admin-cleanup.sql").contains("both dev-tools features");
        assertThat(server.getMessage()).contains("Dev-tools admin objects (sample admin or bootstrap registration) found in database \"" + name + "\"");
        assertNoSecrets(error);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin_bootstrap_begin", "admin_bootstrap_unlock_failed", "admin_bootstrap_unlock_succeeded",
            "admin_bootstrap_ticket_status", "admin_bootstrap_register", "admin_bootstrap_anything_else"})
    void onlyOneBootstrapFunctionOfAnySignatureRaisesAndNamesTheKindOfFinding(String function) throws Exception {
        givenNothing();
        givenBootstrapFunction(function);
        PSQLException error = run(variant("sahaj_staging"));
        assertRaised(error, "guard with only " + function);
        assertThat(error.getServerErrorMessage().getDetail()).isEqualTo("a platform.admin_bootstrap_ function exists");
        assertNoSecrets(error);
    }

    @Test
    void theBootstrapTableAndAFunctionTogetherAreBothListed() throws Exception {
        givenNothing();
        givenBootstrapTable();
        givenBootstrapFunction("admin_bootstrap_begin");
        PSQLException error = run(variant("sahaj_staging"));
        assertRaised(error, "guard raised");
        assertThat(error.getServerErrorMessage().getDetail())
                .contains("the table platform.platform_admin_bootstrap_state exists").contains("a platform.admin_bootstrap_ function exists");
    }

    @Test
    void aFunctionWhoseNameOnlyLooksSimilarIsNotAFindingTheMatchIsTheExactPrefixInSchemaPlatform() throws Exception {
        givenNothing();
        exec("create function platform.admin_bootstrapx(integer) returns integer language sql as 'select 1'");
        exec("create function platform.my_admin_bootstrap_begin(integer) returns integer language sql as 'select 1'");
        exec("create schema qa_probe_schema");
        exec("create function qa_probe_schema.admin_bootstrap_begin(integer) returns integer language sql as 'select 1'");
        assertPasses(run(variant("sahaj_staging")), "similar names and another schema are not findings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_dev", "sahaj_test"})
    void theTwoExactDevAndTestNamesNeverRaiseWhateverBootstrapObjectsExist(String name) throws Exception {
        givenNothing();
        givenBootstrapTable();
        givenBootstrapFunction("admin_bootstrap_register");
        assertPasses(run(variant(name)), "guard in '" + name + "' with the bootstrap objects present");
    }

    @Test
    void noBootstrapObjectAndNoOtherFindingPassesInAnyOtherDatabase() throws Exception {
        givenNothing();
        for (String name : new String[] {"sahaj_staging", "sahaj_prod", "sahaj_dev_staging", "sahaj_test_prod"}) {
            assertPasses(run(variant(name)), name + " with neither the table nor any bootstrap function");
        }
    }

    @Test
    void theGuardIsReadOnly() throws Exception {
        // It never drops, revokes or updates (a migrate must not change privileges in a non-dev database silently).
        assertThat(guard.toLowerCase()).doesNotContain("drop ").doesNotContain("revoke ").doesNotContain("grant ")
                .doesNotContain(" update ").doesNotContain("delete ");
    }
}
