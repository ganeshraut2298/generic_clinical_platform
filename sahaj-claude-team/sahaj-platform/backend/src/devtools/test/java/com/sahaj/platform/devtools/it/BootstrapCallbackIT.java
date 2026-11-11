package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;

/**
 * SAH-42 database design cases S1, S2 and S3, and "both callback orders" (docs/database/SAH-42.md, the Interaction with
 * ADR-008's callback): the dev-tools callback {@code afterMigrate__dev_tools_admin_bootstrap.sql}, loaded from the class path
 * (the exact file that ships in the dev-tools jar) and run as {@code sahaj_migrator} inside ONE transaction that is always rolled
 * back, so {@code sahaj_test} is left as it was. A variant with {@code current_database()} replaced by a string literal is built
 * in memory for the name checks (test code only; no shipped file is changed).
 *
 * <p>Runs today: the name checks (they fail before anything else) and the "first missing prerequisite" check, which expects the
 * callback's own readable message while the human prerequisites are missing. The remaining cases need the role
 * {@code sahaj_admin_definer}, the admin tables and {@code platform.admin_preauth_create} and skip with a message that names
 * what is missing. The SQL has never been executed against a full database.
 */
class BootstrapCallbackIT {

    private static final String BOOTSTRAP = "/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql";
    private static final String OLD_BOOTSTRAP = "/db/dev-tools-bootstrap/afterMigrate__dev_tools_admin_bootstrap.sql";
    private static final String SAMPLE = "/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql";
    private static final String REFUSED = "Dev-tools admin bootstrap refused";
    private static final String NAME_MESSAGE = "is not a Sahaj dev or test database";

    private Connection connection;

    private static String load(String resource) throws Exception {
        try (InputStream in = BootstrapCallbackIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the class path").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void open() throws Exception {
        connection = SampleAdminDb.migrator();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        connection.rollback();
        connection.close();
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Runs the SQL; returns the server error, or null. Nothing of it is undone unless the caller rolls back to a savepoint. */
    private PSQLException run(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return null;
        } catch (PSQLException ex) {
            return ex;
        }
    }

    private PSQLException runIsolated(String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            return run(sql);
        } finally {
            connection.rollback(savepoint);
        }
    }

    /** (assertThat on a PSQLException is ambiguous: it is both a Throwable and an Iterable.) */
    private static void assertNoError(PSQLException error, String label) {
        assertThat(error == null ? "no error" : error.getServerErrorMessage().getMessage()).as(label).isEqualTo("no error");
    }

    private static String asDatabase(String sql, String name) {
        return sql.replace("current_database()", "'" + name + "'");
    }

    private String scalar(String sql) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ---- S3 (runs today) --------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_dev_staging", "sahaj_test_prod", "sahaj_dev_1", "sahaj_test_1", "sahaj_prod", "sahaj_staging",
            "Sahaj_Dev", "sahaj_dev ", "postgres"})
    void s3AnyOtherDatabaseNameIsRefusedFromTheVeryFirstStatementBeforeAnythingIsGranted(String name) throws Exception {
        PSQLException error = runIsolated(asDatabase(load(BOOTSTRAP), name));
        assertThat(error != null).as("callback with database name '" + name + "' must raise").isTrue();
        String message = error.getServerErrorMessage().getMessage();
        assertThat(message).contains(REFUSED).contains(NAME_MESSAGE).contains("\"" + name + "\"");
        assertThat(message).doesNotContain("@example.test").doesNotContain("$2a$");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_dev", "sahaj_test"})
    void s3TheTwoExactNamesPassTheNameCheck(String name) throws Exception {
        PSQLException error = runIsolated(asDatabase(load(BOOTSTRAP), name));
        if (error != null) {
            assertThat(error.getServerErrorMessage().getMessage())
                    .as("the name check must not be what stops '" + name + "'").doesNotContain(NAME_MESSAGE);
        }
    }

    @Test
    void s3WhileAHumanPrerequisiteIsMissingTheCallbackStopsWithItsOwnReadableMessageAndChangesNothing() throws Exception {
        boolean roleMissing = !SampleAdminDb.roleExists();
        boolean tablesMissing = !SampleAdminDb.tablesPresent();
        boolean preauthMissing = !tablesMissing && !BootstrapDb.preauthFunctionPresent();
        assumeTrue(roleMissing || tablesMissing || preauthMissing,
                "All human prerequisites are present; the missing-prerequisite messages are covered by the savepoint cases below.");
        boolean objectsBefore = BootstrapDb.objectsPresent();
        // Says in the test report which human prerequisite is the first one missing (no secret, no value).
        System.out.println("SAH-42 QA precondition state: role sahaj_admin_definer " + (roleMissing ? "MISSING" : "present")
                + ", admin tables " + (tablesMissing ? "MISSING" : "present") + ", admin_preauth_create "
                + (preauthMissing ? "MISSING" : "present or not checked"));

        PSQLException error = runIsolated(load(BOOTSTRAP));

        assertThat(error != null).as("the callback must refuse").isTrue();
        String message = error.getServerErrorMessage().getMessage();
        if (roleMissing) {
            assertThat(message).contains("Role sahaj_admin_definer does not exist").contains("scripts/setup/create-database.mjs");
        } else if (tablesMissing) {
            assertThat(message).contains("Admin tables are missing").contains("activate the parked admin migrations");
        } else {
            assertThat(message).contains("Admin sign-in functions are missing").contains("V2026_11_15_1010");
        }
        assertThat(message).doesNotContain("$2a$").doesNotContain("@example.test");
        connection.rollback();
        assertThat(BootstrapDb.objectsPresent()).as("a refused callback creates nothing").isEqualTo(objectsBefore);
    }

    // ---- S3 with the prerequisites in place ----------------------------------------------------------------------

    @Test
    void s3WithoutTheSignInFunctionTheCallbackRefusesBeforeAnyGrantSoItCanNeverRunBetweenTheTwoParkedMigrations() throws Exception {
        BootstrapDb.assumePrerequisites();
        exec("drop function platform.admin_preauth_create(uuid,text,text,text,inet)");
        PSQLException error = runIsolated(load(BOOTSTRAP));
        assertThat(error != null).isTrue();
        assertThat(error.getServerErrorMessage().getMessage()).contains("Admin sign-in functions are missing");
    }

    @Test
    void s3WithoutTheAdminTablesTheCallbackRefusesWithItsOwnMessage() throws Exception {
        SampleAdminDb.assumePrerequisites();
        exec("alter table platform.platform_admin_audit rename to platform_admin_audit_qa_probe");
        PSQLException error = runIsolated(load(BOOTSTRAP));
        assertThat(error != null).isTrue();
        assertThat(error.getServerErrorMessage().getMessage()).contains("Admin tables are missing");
    }

    // ---- S1, S2 ---------------------------------------------------------------------------------------------------

    private static final String FINGERPRINT = """
            select coalesce((select string_agg(p.proname || ':' || pg_get_userbyid(p.proowner) || ':' || coalesce(p.proacl::text, '')
                                                || ':' || coalesce(p.proconfig::text, ''), '|' order by p.proname)
                               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                              where n.nspname = 'platform' and p.proname like 'admin\\_bootstrap\\_%'), '')
                   || '#' || coalesce((select relacl::text from pg_class where oid = to_regclass('platform.platform_admin_bootstrap_state')), '')
                   || '#' || coalesce((select string_agg(attname || ':' || attacl::text, '|' order by attname)
                                         from pg_attribute where attrelid = 'platform.platform_admin'::regclass and attacl is not null), '')
                   || '#' || coalesce((select string_agg(nspacl::text, '|') from pg_namespace where nspname = 'platform'), '')
            """;

    @Test
    void s1RunningTheCallbackTwiceInARowChangesNothingTheSecondTimeAndS2LeavesTheStateRowAsItIs() throws Exception {
        BootstrapDb.assumePrerequisites();
        String callback = load(BOOTSTRAP);
        assertNoError(run(callback), "first run");
        String first = scalar(FINGERPRINT);
        exec("insert into " + BootstrapDb.TABLE + " (id, failures) values (1, 3) on conflict (id) do update set failures = 3");

        assertNoError(run(callback), "second run");

        assertThat(scalar(FINGERPRINT)).as("function ACLs and owners, table ACL, column grants and the schema ACL are the same").isEqualTo(first);
        assertThat(scalar("select failures from " + BootstrapDb.TABLE + " where id = 1")).as("S2: the count survived the re-run").isEqualTo("3");
        assertThat(scalar("select count(*) from " + BootstrapDb.TABLE)).as("still one row").isEqualTo("1");
        assertThat(scalar("select has_schema_privilege('sahaj_admin_definer', 'platform', 'CREATE')"))
                .as("the temporary CREATE on schema platform is taken back on every run").isEqualTo("f");
    }

    @Test
    void theCallbackLivesInTheRevision3FolderAndNotUnderTheOldName() {
        // Runs without a database. ADR-018 Revision 3 / code review finding 7: no dev-tools location is a prefix of another.
        assertThat(BootstrapCallbackIT.class.getResource(BOOTSTRAP)).as(BOOTSTRAP).isNotNull();
        assertThat(BootstrapCallbackIT.class.getResource(OLD_BOOTSTRAP)).as("old folder name must not exist").isNull();
        assertThat(BootstrapCallbackIT.class.getResource("/db/dev-tools-bootstrap/")).as("old folder").isNull();
        assertThat(BootstrapCallbackIT.class.getResource("/db/bootstrap-dev-tools/afterMigrate__dev_tools_sample_admin.sql"))
                .as("each callback only in its own folder").isNull();
    }

    // ---- R3-I11: the callback re-run over a database that holds the Revision 2 shapes ------------------------------------

    /** The objects as Revision 2 left them: old function shapes, seven columns, eight constraints, no lease. Inside the open transaction. */
    private void becomeRevision2() throws SQLException {
        exec("drop function if exists platform.admin_bootstrap_begin(uuid)");
        exec("drop function if exists platform.admin_bootstrap_unlock_failed(uuid, inet, text)");
        exec("drop function if exists platform.admin_bootstrap_unlock_succeeded(uuid, text, text)");
        exec("drop function if exists platform.admin_bootstrap_ticket_status(text, text)");
        exec("drop function if exists platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text)");
        // Dropping a column drops the check that mentions it.
        exec("alter table " + BootstrapDb.TABLE + " drop column if exists try_id, drop column if exists try_expires_at");
        exec("grant create on schema platform to sahaj_admin_definer");
        exec("create function platform.admin_bootstrap_begin() returns table (\"open\" boolean, failures smallint, locked_until timestamptz) "
                + "language sql as $$ select true, 0::smallint, null::timestamptz $$");
        exec("create function platform.admin_bootstrap_unlock_failed(inet, text) returns table (failures smallint, locked_until timestamptz) "
                + "language sql as $$ select 0::smallint, null::timestamptz $$");
        exec("create function platform.admin_bootstrap_unlock_succeeded(text, text) returns timestamptz "
                + "language sql as $$ select null::timestamptz $$");
        exec("create function platform.admin_bootstrap_ticket_status(text, text) returns text language sql as $$ select 'INVALID'::text $$");
        exec("create function platform.admin_bootstrap_register(text, text, uuid, text, text, text, inet, text) returns text "
                + "language sql as $$ select 'INVALID'::text $$");
        for (String old : List.of("admin_bootstrap_begin()", "admin_bootstrap_unlock_failed(inet, text)",
                "admin_bootstrap_unlock_succeeded(text, text)", "admin_bootstrap_ticket_status(text, text)",
                "admin_bootstrap_register(text, text, uuid, text, text, text, inet, text)")) {
            exec("alter function platform." + old + " owner to sahaj_admin_definer");
        }
        exec("revoke create on schema platform from sahaj_admin_definer");
    }

    @Test
    void r3I11TheCallbackRerunOverARevision2DatabaseReplacesTheShapesAddsTheLeaseAndKeepsTheCountAndTheLock() throws Exception {
        BootstrapDb.assumePrerequisites();
        String callback = load(BOOTSTRAP);
        assertNoError(run(callback), "the callback in its Revision 3 form");
        becomeRevision2();
        // The Revision 2 row: five wrong tries and a lock (a lock cancels the ticket, so there is none).
        exec("insert into " + BootstrapDb.TABLE + " (id, failures, locked_until) values (1, 5, clock_timestamp() + interval '40 minutes') "
                + "on conflict (id) do update set failures = 5, locked_until = clock_timestamp() + interval '40 minutes', "
                + "ticket_hash = null, binding_hash = null, ticket_expires_at = null");
        String lockBefore = scalar("select locked_until::text from " + BootstrapDb.TABLE + " where id = 1");
        assertThat(scalar("select count(*) from pg_attribute where attrelid = '" + BootstrapDb.TABLE + "'::regclass and attnum > 0 and not attisdropped"))
                .as("set-up: the Revision 2 table has 7 columns").isEqualTo("7");

        assertNoError(run(callback), "the callback over the Revision 2 shape");

        assertThat(scalar("select to_regprocedure('platform.admin_bootstrap_begin()') is null")).as("old begin() gone").isEqualTo("t");
        assertThat(scalar("select to_regprocedure('platform.admin_bootstrap_unlock_failed(inet,text)') is null")).as("old unlock_failed gone").isEqualTo("t");
        assertThat(scalar("select to_regprocedure('platform.admin_bootstrap_unlock_succeeded(text,text)') is null")).as("old unlock_succeeded gone").isEqualTo("t");
        for (String signature : BootstrapDb.FUNCTIONS) {
            assertThat(scalar("select to_regprocedure('" + signature + "') is not null")).as(signature).isEqualTo("t");
        }
        assertThat(scalar("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'platform' and p.proname like 'admin\\_bootstrap\\_%'")).as("exactly five functions, no old one left").isEqualTo("5");
        assertThat(scalar("select p.prorettype <> 'text'::regtype from pg_proc p where p.oid = to_regprocedure('" + BootstrapDb.TICKET_STATUS + "')"))
                .as("ticket_status now returns a row set, not text").isEqualTo("t");
        assertThat(scalar("select p.prorettype <> 'text'::regtype from pg_proc p where p.oid = to_regprocedure('" + BootstrapDb.REGISTER + "')"))
                .as("register now returns a row set, not text").isEqualTo("t");
        assertThat(scalar("select string_agg(attname, ',' order by attnum) from pg_attribute where attrelid = '" + BootstrapDb.TABLE
                + "'::regclass and attnum > 0 and not attisdropped"))
                .isEqualTo("id,failures,locked_until,ticket_hash,binding_hash,ticket_expires_at,updated_at,try_id,try_expires_at");
        assertThat(scalar("select count(*) from pg_constraint where conrelid = '" + BootstrapDb.TABLE + "'::regclass")).as("nine named constraints").isEqualTo("9");
        assertThat(scalar("select failures from " + BootstrapDb.TABLE + " where id = 1")).as("the count is kept").isEqualTo("5");
        assertThat(scalar("select locked_until::text from " + BootstrapDb.TABLE + " where id = 1")).as("the lock is kept").isEqualTo(lockBefore);
        assertThat(scalar("select (try_id is null and try_expires_at is null) from " + BootstrapDb.TABLE + " where id = 1")).as("the new lease columns start null").isEqualTo("t");
        assertThat(scalar("select has_schema_privilege('sahaj_admin_definer', 'platform', 'CREATE')")).isEqualTo("f");

        String fingerprint = scalar(FINGERPRINT);
        assertNoError(run(callback), "a second re-run");
        assertThat(scalar(FINGERPRINT)).as("a second re-run changes nothing").isEqualTo(fingerprint);
        assertThat(scalar("select failures || '/' || locked_until::text from " + BootstrapDb.TABLE + " where id = 1")).isEqualTo("5/" + lockBefore);
    }

    @Test
    void theSelfCheckFailsTheRunWhenAnExtraGrantWasMadeOnTheAdminsTable() throws Exception {
        BootstrapDb.assumePrerequisites();
        String callback = load(BOOTSTRAP);
        assertNoError(run(callback), "run");
        exec("grant insert (active) on platform.platform_admin to sahaj_admin_definer");

        PSQLException error = runIsolated(callback);

        assertThat(error != null).as("an INSERT grant on the column active is beyond the allowed seven and must fail the run").isTrue();
    }

    @Test
    void aPrivilegeGivenToSahajAppOnTheStateTableIsTakenBackByTheNextRun() throws Exception {
        BootstrapDb.assumePrerequisites();
        String callback = load(BOOTSTRAP);
        assertNoError(run(callback), "run");
        exec("grant select on " + BootstrapDb.TABLE + " to sahaj_app");
        // The callback revokes on every run, so the run passes and takes the privilege away again.
        assertNoError(run(callback), "the run repairs it");
        assertThat(scalar("select has_table_privilege('sahaj_app', '" + BootstrapDb.TABLE + "', 'SELECT')")).isEqualTo("f");
    }

    // ---- both callback orders -----------------------------------------------------------------------------------

    private List<String> insertColumnsOfTheDefiner() throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("""
                select a.attname from pg_attribute a
                 where a.attrelid = 'platform.platform_admin'::regclass and a.attnum > 0 and not a.attisdropped
                   and has_column_privilege('sahaj_admin_definer', a.attrelid, a.attnum, 'INSERT')
                 order by a.attname""")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private void assertGrantsAreAtLeastTheFourAndAtMostTheSeven(String order) throws SQLException {
        TreeSet<String> actual = new TreeSet<>(insertColumnsOfTheDefiner());
        List<String> required = List.of("email", "full_name", "id", "password_hash");
        List<String> allowed = List.of("email", "full_name", "id", "password_hash", "totp_enrolled_at", "totp_key_version", "totp_secret_enc");
        assertThat(actual).as(order + ": the four columns the bootstrap needs").containsAll(required);
        assertThat(allowed).as(order + ": nothing beyond the seven of the sample-admin callback; actual " + actual).containsAll(actual);
        assertThat(scalar("select to_regprocedure('platform.admin_bootstrap_begin(uuid)') is not null")).as(order + ": bootstrap function").isEqualTo("t");
        assertThat(scalar("select to_regprocedure('platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)') is not null"))
                .as(order + ": sample-admin function").isEqualTo("t");
    }

    @Test
    void bothCallbacksRunInEitherOrderAndLeaveAValidGrantSetAndBothFunctionSets() throws Exception {
        BootstrapDb.assumePrerequisites();
        String bootstrap = load(BOOTSTRAP);
        String sample = load(SAMPLE);

        Savepoint start = connection.setSavepoint();
        assertNoError(run(sample), "sample admin callback first");
        assertNoError(run(bootstrap), "then the bootstrap callback");
        assertGrantsAreAtLeastTheFourAndAtMostTheSeven("sample admin, then bootstrap");
        assertNoError(run(sample), "and the sample admin callback again");
        assertGrantsAreAtLeastTheFourAndAtMostTheSeven("sample admin, bootstrap, sample admin");
        connection.rollback(start);

        start = connection.setSavepoint();
        assertNoError(run(bootstrap), "bootstrap callback first");
        assertNoError(run(sample), "then the sample admin callback (it revokes INSERT first)");
        assertGrantsAreAtLeastTheFourAndAtMostTheSeven("bootstrap, then sample admin");
        assertNoError(run(bootstrap), "and the bootstrap callback again");
        assertGrantsAreAtLeastTheFourAndAtMostTheSeven("bootstrap, sample admin, bootstrap");
        connection.rollback(start);
    }

    @Test
    void theBootstrapCallbackAloneLeavesExactlyTheFourInsertColumns() throws Exception {
        BootstrapDb.assumePrerequisites();
        exec("revoke insert (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at) on platform.platform_admin from sahaj_admin_definer");
        assertNoError(run(load(BOOTSTRAP)), "run");
        assertThat(insertColumnsOfTheDefiner()).containsExactly("email", "full_name", "id", "password_hash");
    }
}
