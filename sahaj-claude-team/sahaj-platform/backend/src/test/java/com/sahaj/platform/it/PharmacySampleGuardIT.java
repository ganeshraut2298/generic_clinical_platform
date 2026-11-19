package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

/**
 * SAH-78 security review M1 / fix round 1: the Flyway callback
 * {@code db/guard/afterMigrate__pharmacy_sample_catalogue_absent_guard.sql} stops start-up when the sample catalogue (fake
 * products, {@code sample = true}) exists in any database other than {@code sahaj_dev} and {@code sahaj_test}. The database name is
 * read in one place (the line marked TEST SUBSTITUTION POINT), so a test replaces that call with a string literal and runs the
 * script as {@code sahaj_migrator} inside a transaction that is rolled back. The sample rows of {@code sahaj_test} (30, seeded
 * because the test profile sets the placeholder to true) are the data. Nothing is committed. Test plan: docs/test-plans/SAH-78.md
 * (T78-GUARD-*).
 */
@OnTestDatabase
class PharmacySampleGuardIT extends Sah78Support {

    private static final String FILE = "db/guard/afterMigrate__pharmacy_sample_catalogue_absent_guard.sql";
    private static final String POINT = "current_database()";

    private String script() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as("the callback is on the class path (db/guard)").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String asDatabase(String name) throws Exception {
        String sql = script();
        assertThat(sql.split(java.util.regex.Pattern.quote(POINT), -1)).as("the name is read in exactly one place").hasSize(2);
        return sql.replace(POINT, "'" + name + "'");
    }

    private void run(String sql, DbAccess.SqlWork<Object> before) throws SQLException {
        db().asMigrator(c -> {
            if (before != null) {
                before.apply(c);
            }
            try (var st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    @Test
    void theCallbackIsOnTheFlywayLocationListAndReadsTheDatabaseNameInOnePlace() throws Exception {
        assertThat(env.getProperty("spring.flyway.locations")).contains("classpath:db/guard").contains("classpath:db/migration");
        String sql = script();
        assertThat(sql).contains("TEST SUBSTITUTION POINT").contains("^sahaj_(dev|test)$");
        String code = sql.lines().filter(l -> !l.trim().startsWith("--")).collect(java.util.stream.Collectors.joining(" "));
        // text inside quotes (the message and the hint name the hand fix) is not a statement
        String statements = code.replaceAll("'([^']|'')*'", "''");
        assertThat(statements).as("the guard only reads: no write statement (comments and quoted text excluded)")
                .doesNotContainIgnoringCase("delete from").doesNotContainIgnoringCase("update ").doesNotContainIgnoringCase("insert into")
                .doesNotContainIgnoringCase("truncate").doesNotContainIgnoringCase("alter table").doesNotContainIgnoringCase("drop ");
        assertThat(code).as("the hint names the hand fix").contains("delete from pharmacy.catalogue_product where sample");
    }

    @Test
    void inSahajTestAndSahajDevTheSampleRowsAreAllowedAndTheRealScriptPassesAsItIs() throws Exception {
        db().asMigrator(c -> {
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where sample")).as("the premise: 30 sample rows").isEqualTo(30);
            return null;
        });
        run(script(), null); // the real database name is sahaj_test
        run(asDatabase("sahaj_test"), null);
        run(asDatabase("sahaj_dev"), null);
    }

    @Test
    void anyOtherDatabaseNameWithSampleRowsStopsTheStartWithTheCountTheNameAndTheHandFix() throws Exception {
        for (String name : new String[] {"sahaj_staging", "sahaj_prod", "sahaj", "postgres", "sahaj_dev_staging", "sahaj_test_prod",
            "sahaj_dev_1", "sahaj_test2", "SAHAJ_TEST", "Sahaj_Dev", "xsahaj_dev", "sahaj_dev ", "prod_sahaj_test"}) {
            String sql = asDatabase(name);
            assertThatThrownBy(() -> run(sql, null)).as("database " + name).isInstanceOfSatisfying(PSQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo("P0001");
                String message = e.getServerErrorMessage().getMessage();
                assertThat(message).contains("\"" + name + "\"").contains("30 row(s)").contains("sahaj_dev or sahaj_test");
                assertThat(e.getServerErrorMessage().getHint()).contains("delete from pharmacy.catalogue_product where sample");
                assertThat(e.getServerErrorMessage().getDetail()).contains("fake sample rows");
                // a count and a name only: no product name, code or other row value
                assertThat(message + e.getServerErrorMessage().getDetail()).doesNotContain("Morphine").doesNotContain("G-000");
            });
        }
    }

    @Test
    void theCountIsTheNumberOfSampleRowsLeftAndWithNoSampleRowTheGuardPassesAnywhere() throws Exception {
        String staging = asDatabase("sahaj_staging");
        // one sample row left (the fake NDPS product): the message says 1
        assertThatThrownBy(() -> run(staging, c -> {
            DbAccess.execute(c, "update pharmacy.catalogue_product set sample = false where code <> 'G-000117'");
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where sample")).isOne();
            return null;
        })).isInstanceOfSatisfying(PSQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("P0001");
            assertThat(e.getServerErrorMessage().getMessage()).contains("1 row(s)");
        });
        // none left (a real clinic database with only real catalogue rows): nothing happens, the start goes on
        run(staging, c -> {
            DbAccess.execute(c, "update pharmacy.catalogue_product set sample = false");
            return null;
        });
        // sample = false rows never trip it, and it does not matter what else the table holds
        db().asMigrator(c -> {
            DbAccess.execute(c, "update pharmacy.catalogue_product set sample = false");
            try (var st = c.createStatement()) {
                st.execute(staging);
            }
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product")).isEqualTo(30);
            return null;
        });
    }

    @Test
    void aDatabaseWhereTheTableDoesNotExistYetPassesAndTheGuardChangesNothing() throws Exception {
        // a fresh database: the callback runs after the migrations, but a missing table must not fail it (to_regclass is null)
        String missing = asDatabase("sahaj_staging").replace("pharmacy.catalogue_product", "pharmacy.table_that_does_not_exist");
        assertThat(missing).contains("table_that_does_not_exist");
        run(missing, null);
        String devScript = asDatabase("sahaj_dev");
        String stagingScript = asDatabase("sahaj_staging");
        // read only: the same run on a real name leaves the catalogue exactly as it was
        db().asMigrator(c -> {
            long total = DbAccess.count(c, "select count(*) from pharmacy.catalogue_product");
            long samples = DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where sample");
            try (var st = c.createStatement()) {
                st.execute(devScript);
            }
            try {
                try (var st = c.createStatement()) {
                    st.execute(stagingScript);
                }
            } catch (PSQLException expected) {
                c.rollback(); // the aborted transaction is the proof that nothing was changed by it
            }
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product")).isEqualTo(total);
            assertThat(DbAccess.count(c, "select count(*) from pharmacy.catalogue_product where sample")).isEqualTo(samples);
            return null;
        });
    }
}
