package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.opd.infrastructure.partition.EmrPartitionUpkeepMonitor;
import com.sahaj.platform.support.LogCapture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * DEC-049 item 3 (docs/database/SAH-53.md "Partition upkeep", test cases S-72 to S-78 and the monitor case S-79): the
 * monthly partitions of {@code opd.emr_record} are made on every migrate, by the Flyway callback
 * {@code afterMigrate__opd_emr_record_partition_upkeep.sql}, through {@code opd.emr_record_upkeep_partitions}, and the
 * application warns when fewer than 3 future months exist ({@code EmrPartitionUpkeepMonitor}).
 *
 * <p>Nothing here changes {@code sahaj_test} for the other tests. Every case that makes, hides or fills a partition runs as
 * {@code sahaj_migrator} inside one transaction that {@link DbAccess#asMigrator} rolls back (PostgreSQL DDL is
 * transactional), so a partition made by a case does not stay and no partition is ever dropped. The row of S-75 is written in
 * that same transaction (a temporary policy for the migrator, rolled back too) because the table is under forced row level
 * security and no committed row of a throw-away month is wanted. Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrPartitionUpkeepIT extends BaseIntegrationTest {

    private static final String CALLBACK = "/db/guard/afterMigrate__opd_emr_record_partition_upkeep.sql";
    private static final String MIGRATION = "/db/migration/V2026_12_13_1000__opd_emr_record_partition_upkeep.sql";
    private static final String CURRENT_MONTH = "date_trunc('month', now() at time zone 'UTC')::date";
    private static final String UPKEEP = "opd.emr_record_upkeep_partitions(integer)";
    private static final String COUNT = "opd.emr_record_future_partition_count()";
    private static final String[] TABLE_PRIVILEGES = {"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"};
    private static final String SKIPPED = "skipped_default_partition_has_rows";

    @Autowired
    private Flyway flyway;

    @Autowired
    private EmrPartitionUpkeepMonitor monitor;

    // ---------------- helpers ----------------

    /** Runs the upkeep and returns {@code "yyyy-MM-dd|outcome"} per row; every database notice goes to {@code notices}. */
    private static List<String> upkeep(Connection c, int monthsAhead, List<String> notices) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("select partition_month::text, outcome from " + "opd.emr_record_upkeep_partitions(?)")) {
            st.setInt(1, monthsAhead);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + "|" + rs.getString(2));
                }
            }
            for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
                notices.add(w.getMessage());
            }
        }
        for (SQLWarning w = c.getWarnings(); w != null; w = w.getNextWarning()) {
            notices.add(w.getMessage());
        }
        return rows;
    }

    private static List<String> upkeep(Connection c, int monthsAhead) throws SQLException {
        return upkeep(c, monthsAhead, new ArrayList<>());
    }

    /** First days (yyyy-MM-dd) of the months from the current UTC month to {@code monthsAhead} later that have no partition yet. */
    private static List<String> missingMonths(Connection c, int monthsAhead) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("select to_char(m, 'YYYY-MM-DD') from (select (" + CURRENT_MONTH
                + " + make_interval(months => i))::date as m from generate_series(0, ?) i) x "
                + "where to_regclass('opd.emr_record_' || to_char(m, 'YYYY_MM')) is null order by m")) {
            st.setInt(1, monthsAhead);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static String partitionOf(String firstDay) {
        return "emr_record_" + firstDay.substring(0, 7).replace('-', '_');
    }

    private static long futureCount(Connection c) throws SQLException {
        return DbAccess.count(c, "select " + COUNT);
    }

    /** Enabled and forced RLS, exactly the one tenant policy of the helper, and no privilege for sahaj_app or PUBLIC. */
    private static void assertSafePartition(Connection c, String part) throws SQLException {
        String table = "opd." + part;
        assertThat(DbAccess.text(c, "select to_regclass(?)::text", table)).as(part + " exists").isNotNull();
        assertThat(DbAccess.text(c, "select relrowsecurity::text || ':' || relforcerowsecurity::text from pg_class where oid = ?::regclass", table))
                .as(part + " row level security enabled and forced").isEqualTo("true:true");
        assertThat(DbAccess.text(c, "select coalesce(string_agg(polname, ',' order by polname), '') from pg_policy where polrelid = ?::regclass", table))
                .as(part + " policy").isEqualTo("p_" + part + "_tenant");
        for (String privilege : TABLE_PRIVILEGES) {
            assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', ?, ?)::text", table, privilege))
                    .as(part + " sahaj_app " + privilege).isEqualTo("false");
        }
        assertThat(DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass and a.grantee = 0", table))
                .as(part + " privileges for PUBLIC").isZero();
        assertThat(DbAccess.count(c, "select count(*) from pg_class cl, aclexplode(cl.relacl) a where cl.oid = ?::regclass "
                + "and a.grantee = 'sahaj_app'::regrole", table)).as(part + " privileges for sahaj_app").isZero();
    }

    // ---------------- S-72 the coming months exist after the context started ----------------

    @Test
    void afterTheContextStartedTheCurrentMonthAndTheNextTwelveExistWithForcedRowLevelSecurityAndNoPrivilegeForTheApp() throws Exception {
        db().asApp(c -> {
            for (int i = 0; i <= 12; i++) {
                String part = "emr_record_" + DbAccess.text(c, "select to_char(" + CURRENT_MONTH + " + make_interval(months => ?), 'YYYY_MM')", i);
                assertSafePartition(c, part);
            }
            assertThat(futureCount(c)).as("12 or more months after the current one").isGreaterThanOrEqualTo(12);
            return null;
        });
    }

    // ---------------- S-73 idempotent ----------------

    @Test
    void theUpkeepOfTwelveMonthsReturnsNoRowsTwiceAndTheEnsureHelperIsUntouched() throws Exception {
        db().asMigrator(c -> {
            assertThat(upkeep(c, 12)).as("first call after the callback ran").isEmpty();
            assertThat(upkeep(c, 12)).as("second call").isEmpty();
            assertThat(upkeep(c, 0)).as("only the current month").isEmpty();
            // S-67 still holds: the helper is idempotent and makes one new month once
            assertThat(DbAccess.count(c, "select opd.emr_record_ensure_partitions(date '2026-11-01', 24)")).isZero();
            String month = EmrClinicTablesIT.beyondTheUpkeepMonth();
            assertThat(DbAccess.count(c, "select opd.emr_record_ensure_partitions(date '" + month + "-01', 1)")).isEqualTo(1);
            assertThat(DbAccess.count(c, "select opd.emr_record_ensure_partitions(date '" + month + "-01', 1)")).isZero();
            return null;
        });
    }

    // ---------------- S-74 creates, and the boundaries of the argument ----------------

    @Test
    void theUpkeepOfTwentyFourMonthsCreatesExactlyTheMissingMonthsSafelyAndIsThenIdempotent() throws Exception {
        db().asMigrator(c -> {
            List<String> missing = missingMonths(c, 24);
            assertThat(missing).as("the months 13 to 24 ahead are never made by the callback of 12").isNotEmpty();
            long before = futureCount(c);
            List<String> rows = upkeep(c, 24);
            assertThat(rows).containsExactlyElementsOf(missing.stream().map(m -> m + "|created").toList());
            for (String m : missing) {
                assertSafePartition(c, partitionOf(m));
            }
            assertThat(futureCount(c)).as("the count function sees the new months in the same transaction")
                    .isEqualTo(before + missing.size());
            assertThat(upkeep(c, 24)).as("second call").isEmpty();
            return null;
        });
    }

    @Test
    void sixtyMonthsAreAccepted() throws Exception {
        db().asMigrator(c -> {
            List<String> missing = missingMonths(c, 60);
            assertThat(upkeep(c, 60)).containsExactlyElementsOf(missing.stream().map(m -> m + "|created").toList());
            return null;
        });
    }

    @Test
    void sixtyOneMinusOneAndNullAreRefusedWithInvalidParameterValue() {
        for (String argument : new String[] {"61", "-1", "null", "1000"}) {
            assertThatThrownBy(() -> db().asMigrator(c -> {
                DbAccess.execute(c, "select * from opd.emr_record_upkeep_partitions(" + argument + ")");
                return null;
            })).as("argument " + argument).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo("22023");
                assertThat(e.getMessage()).contains("between 0 and 60");
            });
        }
    }

    // ---------------- S-75 skip, no failure ----------------

    @Test
    void aMonthWhoseRowsAreInTheDefaultPartitionIsSkippedWithAWarningAndTheOtherMonthsAreStillMade() throws Exception {
        UUID tenant = createTenant(UUID.randomUUID(), UUID.randomUUID(), uniqueName("Upkeep skip clinic"));
        String skipDay = YearMonth.now(ZoneOffset.UTC).plusMonths(50).atDay(1).toString();
        String skipPartition = partitionOf(skipDay);
        db().asMigrator(c -> {
            // A row of the month 50 ahead, written in this transaction only (forced RLS: temporary policies for the migrator,
            // rolled back with everything else). An Emergency record needs no patient and no visit (as in EmrClinicTablesIT).
            DbAccess.execute(c, "select set_config('app.tenant_id', ?, true)", tenant.toString());
            DbAccess.execute(c, "create policy qa_s75_insert on opd.emr_record for insert to sahaj_migrator with check (true)");
            DbAccess.execute(c, "create policy qa_s75_select on opd.emr_record_default for select to sahaj_migrator using (true)");
            DbAccess.execute(c, "insert into opd.emr_record (tenant_id, encounter_type, er_case_id, opened_by_membership_id, department_key, "
                    + "department_name, starter_key, layout_kind, starter_layout_version, choice_source, created_at, created_by) values "
                    + "(?, 'ER_ASSESSMENT', gen_random_uuid(), gen_random_uuid(), 'gm', 'General Medicine', 'gm', 'STARTER_LAYOUT', 1, "
                    + "'CLINIC_DEFAULT', ?::timestamptz, gen_random_uuid())", tenant, skipDay + " 10:00:00+00");
            assertThat(DbAccess.count(c, "select count(*) from only opd.emr_record_default where created_at >= ?::timestamptz", skipDay + " 00:00:00+00"))
                    .as("the row is in the default partition").isOne();

            List<String> missing = missingMonths(c, 60);
            assertThat(missing).contains(skipDay);
            List<String> notices = new ArrayList<>();
            List<String> rows = upkeep(c, 60, notices);
            assertThat(rows).as("one skip row, every other missing month created, no exception")
                    .containsExactlyElementsOf(missing.stream().map(m -> m + "|" + (m.equals(skipDay) ? SKIPPED : "created")).toList());
            assertThat(DbAccess.text(c, "select to_regclass(?)::text", "opd." + skipPartition))
                    .as("no partition for the skipped month (the failed create was undone)").isNull();
            String noticeText = String.join("\n", notices);
            assertThat(noticeText).as("the warning names the partition").contains(skipPartition).contains("default partition");
            assertThat(noticeText).as("the warning holds no row data").doesNotContain(tenant.toString()).doesNotContain("General Medicine");
            for (String m : missing) {
                if (!m.equals(skipDay)) {
                    assertSafePartition(c, partitionOf(m));
                }
            }
            assertThat(upkeep(c, 60)).as("a second call gives the same skip row and nothing else")
                    .containsExactly(skipDay + "|" + SKIPPED);
            return null; // the row, the policies and every partition made here are rolled back by asMigrator
        });
        // nothing stayed: the skipped month is still absent and no partition of this case exists
        db().asApp(c -> {
            assertThat(DbAccess.text(c, "select to_regclass(?)::text", "opd." + skipPartition)).isNull();
            return null;
        });
    }

    // ---------------- S-76 privileges ----------------

    @Test
    void theUpkeepFunctionCannotBeRunByTheAppOrPublicAndTheCountFunctionIsForTheAppOnly() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.text(c, "select has_function_privilege('sahaj_app', ?, 'EXECUTE')::text", UPKEEP)).isEqualTo("false");
            assertThat(DbAccess.text(c, "select has_function_privilege('sahaj_app', ?, 'EXECUTE')::text", COUNT)).isEqualTo("true");
            for (String function : new String[] {UPKEEP, COUNT}) {
                assertThat(DbAccess.count(c, "select count(*) from pg_proc p, aclexplode(p.proacl) a where p.oid = ?::regprocedure and a.grantee = 0",
                        function)).as(function + " granted to PUBLIC").isZero();
                assertThat(DbAccess.text(c, "select (proacl is not null)::text from pg_proc where oid = ?::regprocedure", function))
                        .as(function + " has an explicit ACL (the default would give PUBLIC execute)").isEqualTo("true");
            }
            return null;
        });
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "select * from opd.emr_record_upkeep_partitions(12)");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    // ---------------- S-77 the count function ----------------

    @Test
    void theCountFunctionEqualsTheMonthlyPartitionsAfterTheCurrentMonthAndIsSecurityDefinerWithAPinnedSearchPath() throws Exception {
        String catalogCount = "select count(*) from pg_inherits i join pg_class c on c.oid = i.inhrelid where i.inhparent = 'opd.emr_record'::regclass "
                + "and c.relname ~ '^emr_record_[0-9]{4}_[0-9]{2}$' and substr(c.relname, 12) > to_char(" + CURRENT_MONTH + ", 'YYYY_MM')";
        db().asApp(c -> {
            long expected = DbAccess.count(c, catalogCount);
            assertThat(expected).isGreaterThanOrEqualTo(12);
            assertThat(futureCount(c)).as("without a bound clinic").isEqualTo(expected);
            return null;
        });
        db().inTenant(UUID.randomUUID(), c -> {
            assertThat(futureCount(c)).as("with a bound clinic").isEqualTo(DbAccess.count(c, catalogCount));
            return null;
        });
        db().asApp(c -> {
            assertThat(DbAccess.text(c, "select prosecdef::text from pg_proc where oid = ?::regprocedure", COUNT)).isEqualTo("true");
            assertThat(DbAccess.text(c, "select array_to_string(proconfig, ';') from pg_proc where oid = ?::regprocedure", COUNT))
                    .isEqualTo("search_path=pg_catalog, pg_temp");
            assertThat(DbAccess.text(c, "select prorettype::regtype::text from pg_proc where oid = ?::regprocedure", COUNT)).isEqualTo("integer");
            // execute: the application role and the owner only
            assertThat(DbAccess.text(c, "select coalesce(string_agg(r.rolname, ',' order by r.rolname), '') from pg_proc p "
                    + "cross join lateral aclexplode(p.proacl) a join pg_roles r on r.oid = a.grantee "
                    + "where p.oid = ?::regprocedure and a.grantee <> p.proowner and a.privilege_type = 'EXECUTE'", COUNT))
                    .as("roles other than the owner that can execute").isEqualTo("sahaj_app");
            assertThat(DbAccess.count(c, "select count(*) from pg_proc p cross join lateral aclexplode(p.proacl) a "
                    + "where p.oid = ?::regprocedure and a.privilege_type <> 'EXECUTE'", COUNT)).isZero();
            return null;
        });
    }

    // ---------------- S-78 the callback is in the code, in the jar's source and picked up by Flyway ----------------

    @Test
    void theCallbackFileIsInMainResourcesRunsTheUpkeepOfTwelveMonthsAndChangesNothingElse() throws Exception {
        String callback = read(CALLBACK);
        assertThat(callback).contains("opd.emr_record_upkeep_partitions(12)").contains("to_regprocedure('opd.emr_record_upkeep_partitions(integer)')");
        assertThat(callback.toLowerCase()).as("no destructive or privilege statement")
                .doesNotContain("drop ").doesNotContain("delete ").doesNotContain("grant ").doesNotContain("revoke ").doesNotContain("truncate");
        assertThat(read(MIGRATION)).contains("create or replace function opd.emr_record_upkeep_partitions")
                .contains("create or replace function opd.emr_record_future_partition_count");
        assertThat(CALLBACK).as("a callback name, not a V__ or R__ migration").startsWith("/db/guard/afterMigrate__");
        // the Flyway locations of application.yml still list db/guard next to db/migration (no configuration change was needed)
        String locations = env.getProperty("spring.flyway.locations");
        assertThat(locations).contains("classpath:db/migration").contains("classpath:db/guard");
    }

    @Test
    void aSecondMigrateWithNothingPendingRunsTheCallbackAgainAndLeavesTheTwelveMonthsInPlace() throws Exception {
        try (LogCapture log = new LogCapture()) {
            MigrateResult result = flyway.migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).as("nothing pending").isZero();
            assertThat(log.allText().toLowerCase()).as("Flyway ran the SQL callback of this upkeep after the migrate")
                    .contains("opd emr record partition upkeep");
        }
        db().asApp(c -> {
            assertThat(futureCount(c)).isGreaterThanOrEqualTo(12);
            return null;
        });
        db().asMigrator(c -> {
            assertThat(missingMonths(c, 12)).as("months 0 to 12 all exist after the second migrate").isEmpty();
            return null;
        });
    }

    private String read(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the class path").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------- S-79 the monitor on a real database ----------------

    private static final class Warnings implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(EmrPartitionUpkeepMonitor.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Warnings() {
            appender.start();
            logger.addAppender(appender);
        }

        List<ILoggingEvent> events() {
            return List.copyOf(appender.list);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void theRealMonitorBeanOnTheRealDatabaseReadsTheCountAndLogsNothingWhenTwelveMonthsExist() throws Exception {
        try (Warnings warnings = new Warnings()) {
            monitor.checkAtStartup();
            monitor.checkDaily();
            assertThat(warnings.events()).as("no warning with 12 or more future months").isEmpty();
        }
    }

    /** Hides the future months (a rename inside the open transaction) until only {@code keep} remain counted. */
    private static void keepOnlyFutureMonths(Connection c, int keep) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid "
                + "where i.inhparent = 'opd.emr_record'::regclass and c.relname ~ '^emr_record_[0-9]{4}_(0[1-9]|1[0-2])$' "
                + "and substr(c.relname, 12) > to_char(" + CURRENT_MONTH + ", 'YYYY_MM') order by c.relname");
                ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        for (int i = keep; i < names.size(); i++) {
            // the name comes from the catalog and matches the pattern above
            DbAccess.execute(c, "alter table opd." + names.get(i) + " rename to qa_hidden_" + names.get(i));
        }
    }

    @Test
    void theMonitorWarnsOnARealDatabaseWithUnderThreeFutureMonthsAndNotWithThreeOrMore() throws Exception {
        // The months are hidden by a rename in one rolled back transaction of sahaj_migrator, and the monitor reads through that
        // same connection, so no other test, and no other connection, ever sees fewer months. Nothing is dropped.
        try (Connection c = db().migrator(); Warnings warnings = new Warnings()) {
            c.setAutoCommit(false);
            try {
                EmrPartitionUpkeepMonitor onTheTransaction = new EmrPartitionUpkeepMonitor(JdbcClient.create(new SingleConnectionDataSource(c, true)));
                keepOnlyFutureMonths(c, 3);
                assertThat(futureCount(c)).isEqualTo(3);
                onTheTransaction.checkAtStartup();
                assertThat(warnings.events()).as("exactly 3 future months is healthy").isEmpty();

                keepOnlyFutureMonths(c, 2);
                assertThat(futureCount(c)).isEqualTo(2);
                onTheTransaction.checkAtStartup();
                assertThat(warnings.events()).hasSize(1);
                ILoggingEvent event = warnings.events().get(0);
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("fewer than 3").contains("count: 2").contains("Deploy or restart");
                assertThat(event.getFormattedMessage()).as("count only, no partition name, no row data").doesNotContain("emr_record_20");

                keepOnlyFutureMonths(c, 0);
                assertThat(futureCount(c)).isZero();
                onTheTransaction.checkDaily();
                assertThat(warnings.events()).hasSize(2);
                assertThat(warnings.events().get(1).getFormattedMessage()).contains("count: 0");
            } finally {
                c.rollback();
            }
        }
        // the hiding was undone: the real monitor is quiet again
        try (Warnings warnings = new Warnings()) {
            monitor.checkAtStartup();
            assertThat(warnings.events()).isEmpty();
        }
    }

    @Test
    void aFailingCallOnARealDatabaseLogsOneWarnWithTheClassOnlyAndNeverStopsTheCaller() throws Exception {
        // (a) the database answers with an error: the count function is renamed away inside a rolled back transaction
        try (Connection c = db().migrator(); Warnings warnings = new Warnings()) {
            c.setAutoCommit(false);
            try {
                DbAccess.execute(c, "alter function opd.emr_record_future_partition_count() rename to qa_hidden_count");
                EmrPartitionUpkeepMonitor failing = new EmrPartitionUpkeepMonitor(JdbcClient.create(new SingleConnectionDataSource(c, true)));
                assertThatCode(failing::checkAtStartup).doesNotThrowAnyException();
                assertThat(warnings.events()).hasSize(1);
                ILoggingEvent event = warnings.events().get(0);
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Could not check the future monthly partitions").contains("cause:");
                assertThat(event.getFormattedMessage()).as("the database message is not copied into the log")
                        .doesNotContain("does not exist").doesNotContain("qa_hidden_count").doesNotContain("opd.emr_record_future_partition_count");
                assertThat(event.getThrowableProxy()).as("no stack trace").isNull();
            } finally {
                c.rollback();
            }
        }
        // (b) the database cannot be reached with these credentials
        DriverManagerDataSource wrongPassword = new DriverManagerDataSource(env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"), "not-the-password-qa-probe");
        try (Warnings warnings = new Warnings()) {
            EmrPartitionUpkeepMonitor unreachable = new EmrPartitionUpkeepMonitor(JdbcClient.create(wrongPassword));
            assertThatCode(unreachable::checkAtStartup).doesNotThrowAnyException();
            assertThatCode(unreachable::checkDaily).doesNotThrowAnyException();
            assertThat(warnings.events()).hasSize(2);
            for (ILoggingEvent event : warnings.events()) {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Could not check").doesNotContain("not-the-password-qa-probe")
                        .doesNotContain(env.getRequiredProperty("spring.datasource.url"));
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }
}
