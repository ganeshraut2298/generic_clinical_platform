package com.sahaj.platform.it.devsample;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Plain JDBC access to {@code sahaj_test} for the DEC-016 tests, outside any application pool. Connection details
 * come from the same environment variables the application uses ({@code SAHAJ_TEST_DB_URL},
 * {@code SAHAJ_DB_APP_PASSWORD}, {@code SAHAJ_DB_MIGRATOR_PASSWORD}); no password appears in a test.
 *
 * <p><b>Preconditions.</b> The admin tables, the role {@code sahaj_admin_definer} and (after one dev-tools start) the
 * function {@code platform.admin_dev_create_sample_admin} are human prerequisites (ADR-008). Tests that need them call
 * one of the {@code assume...} methods; a skipped test names the missing precondition in its message and nothing else
 * is ever skipped.
 *
 * <p><b>Clean-up.</b> The dev endpoint and its function commit, and the admin tables are append-only by design (the
 * audit table refuses UPDATE, DELETE and TRUNCATE by trigger, and a foreign key ties audit rows to the admin). A test
 * that creates rows therefore takes a {@link Snapshot} first and calls {@link #cleanup} in a {@code finally}. The
 * clean-up runs as the table owner in {@code sahaj_test} only, disables the USER triggers of the audit table for the
 * length of one transaction, removes only rows created since the snapshot, and re-enables them before it commits.
 * Tests that create rows first require that no admin existed (so nothing that was there before can be touched).
 */
public final class SampleAdminDb {

    public static final String NO_TABLES = "the admin tables (platform.platform_admin and the five platform_admin_* "
            + "tables) do not exist in sahaj_test: human prerequisite, activate the parked admin migrations (DEC-016)";
    public static final String NO_ROLE = "role sahaj_admin_definer does not exist: human prerequisite, "
            + "create it in scripts/db/init.sql and scripts/setup/create-database.mjs (DEC-016)";
    public static final String NO_FUNCTION = "function platform.admin_dev_create_sample_admin does not exist in sahaj_test: "
            + "it is created by the dev-tools callback on the first start of a -Pdev-tools build with the flag, "
            + "which needs the two prerequisites above";
    public static final String ADMIN_EXISTS = "an admin already exists in sahaj_test: these tests need a database "
            + "with no admin (the endpoint is one-shot and the admin tables are append-only)";

    public static final String FUNCTION_SIGNATURE =
            "platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)";

    private SampleAdminDb() {
    }

    public static String url() {
        String url = System.getenv("SAHAJ_TEST_DB_URL");
        return url == null || url.isBlank() ? "jdbc:postgresql://localhost:5432/sahaj_test" : url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is not set");
        }
        return value;
    }

    public static Connection app() throws SQLException {
        return DriverManager.getConnection(url(), "sahaj_app", required("SAHAJ_DB_APP_PASSWORD"));
    }

    public static Connection migrator() throws SQLException {
        return DriverManager.getConnection(url(), "sahaj_migrator", required("SAHAJ_DB_MIGRATOR_PASSWORD"));
    }

    /** Refuses to touch anything that is not the database named sahaj_test. */
    private static void requireTestDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select current_database()")) {
            rs.next();
            if (!"sahaj_test".equals(rs.getString(1))) {
                throw new IllegalStateException("DEC-016 tests may only change the database sahaj_test");
            }
        }
    }

    public static boolean scalarBoolean(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public static long scalarLong(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public static String scalarText(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    public static boolean roleExists() {
        try (Connection c = migrator()) {
            return scalarBoolean(c, "select exists (select 1 from pg_roles where rolname = 'sahaj_admin_definer')");
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the role state", ex);
        }
    }

    public static boolean tablesPresent() {
        try (Connection c = migrator()) {
            return scalarBoolean(c, """
                    select to_regclass('platform.platform_admin') is not null
                       and to_regclass('platform.platform_admin_audit') is not null
                       and to_regclass('platform.platform_admin_session') is not null
                       and to_regclass('platform.platform_admin_preauth') is not null
                       and to_regclass('platform.platform_admin_recovery_code') is not null
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the table state", ex);
        }
    }

    public static boolean devFunctionPresent() {
        try (Connection c = migrator()) {
            return scalarBoolean(c, "select to_regprocedure(?) is not null", FUNCTION_SIGNATURE);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the function state", ex);
        }
    }

    /** Role and tables: what the dev-tools callback needs before it can run. */
    public static boolean prerequisitesPresent() {
        return roleExists() && tablesPresent();
    }

    public static long adminCount() {
        if (!tablesPresent()) {
            return 0;
        }
        try (Connection c = migrator()) {
            return scalarLong(c, "select count(*) from platform.platform_admin");
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not count admins", ex);
        }
    }

    public static void assumePrerequisites() {
        assumeTrue(tablesPresent(), "PRECONDITION NOT MET: " + NO_TABLES);
        assumeTrue(roleExists(), "PRECONDITION NOT MET: " + NO_ROLE);
    }

    public static void assumeDevFunction() {
        assumePrerequisites();
        assumeTrue(devFunctionPresent(), "PRECONDITION NOT MET: " + NO_FUNCTION);
    }

    public static void assumeNoAdmin() {
        assumeTrue(adminCount() == 0, "PRECONDITION NOT MET: " + ADMIN_EXISTS);
    }

    /** A point in database time, taken before a test creates rows. */
    public record Snapshot(Timestamp at, long adminsBefore) {
    }

    public static Snapshot snapshot() {
        try (Connection c = migrator()) {
            Timestamp now;
            try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery("select clock_timestamp()")) {
                rs.next();
                now = rs.getTimestamp(1);
            }
            long admins = tablesPresent() ? scalarLong(c, "select count(*) from platform.platform_admin") : 0;
            return new Snapshot(now, admins);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not take a snapshot", ex);
        }
    }

    /**
     * Removes the admin rows and everything that hangs on them that were created after the snapshot. Does nothing when
     * the tables do not exist or when an admin existed before the snapshot (then the test was not allowed to create
     * anything and never did).
     */
    public static void cleanup(Snapshot snapshot) {
        if (!tablesPresent() || snapshot.adminsBefore() != 0) {
            return;
        }
        try (Connection c = migrator()) {
            requireTestDatabase(c);
            c.setAutoCommit(false);
            try (Statement statement = c.createStatement()) {
                statement.execute("alter table platform.platform_admin_audit disable trigger user");
            }
            String ids = "(select id from platform.platform_admin where created_at >= ?)";
            for (String table : new String[] {"platform_admin_session", "platform_admin_preauth",
                    "platform_admin_recovery_code"}) {
                try (PreparedStatement delete = c.prepareStatement(
                        "delete from platform." + table + " where admin_id in " + ids)) {
                    delete.setTimestamp(1, snapshot.at());
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement delete = c.prepareStatement(
                    "delete from platform.platform_admin_audit where occurred_at >= ?")) {
                delete.setTimestamp(1, snapshot.at());
                delete.executeUpdate();
            }
            try (PreparedStatement delete = c.prepareStatement(
                    "delete from platform.platform_admin where created_at >= ?")) {
                delete.setTimestamp(1, snapshot.at());
                delete.executeUpdate();
            }
            try (Statement statement = c.createStatement()) {
                statement.execute("alter table platform.platform_admin_audit enable trigger user");
            }
            c.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Clean-up of the sample admin rows failed", ex);
        }
    }

    /** Inserts an admin as the table owner, as the dev seed or a human would (BCrypt-shaped hash, not a real password). */
    public static UUID insertAdmin(String email, boolean active) {
        UUID id = UUID.randomUUID();
        try (Connection c = migrator()) {
            requireTestDatabase(c);
            try (PreparedStatement insert = c.prepareStatement("""
                    insert into platform.platform_admin (id, email, full_name, password_hash, active)
                    values (?, ?, 'Existing admin', ?, ?)
                    """)) {
                insert.setObject(1, id);
                insert.setString(2, email);
                insert.setString(3, "$2a$12$" + "a".repeat(53));
                insert.setBoolean(4, active);
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not insert the test admin", ex);
        }
        return id;
    }

    /** The audit rows created since the snapshot, as JSON text per row (every column), newest last. */
    public static java.util.List<String> auditRowsSince(Snapshot snapshot) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        try (Connection c = migrator();
             PreparedStatement select = c.prepareStatement(
                     "select row_to_json(a)::text from platform.platform_admin_audit a where occurred_at >= ? order by occurred_at")) {
            select.setTimestamp(1, snapshot.at());
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the audit rows", ex);
        }
        return rows;
    }
}
