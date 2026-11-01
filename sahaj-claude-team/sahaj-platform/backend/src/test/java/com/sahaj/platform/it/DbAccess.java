package com.sahaj.platform.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.core.env.Environment;

/**
 * Plain JDBC access to the test database for the integration tests, outside the application's
 * own pool. Connection settings come from the same properties the application uses (profile
 * {@code test}: {@code spring.datasource.*} for sahaj_app, {@code spring.flyway.*} for
 * sahaj_migrator), so no password appears in a test.
 *
 * <p>Every helper rolls back and closes its connection, so a probe never changes data. Note that
 * sahaj_migrator owns the tables but is not exempt from row level security (the tables are FORCE),
 * and no policy names it, so it sees no rows of tenant tables. It is used for tables without row
 * level security ({@code platform.sales_enquiry}), for the Flyway history and for catalog checks.
 */
final class DbAccess {

    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Environment env;

    DbAccess(Environment env) {
        this.env = env;
    }

    Connection app() throws SQLException {
        return DriverManager.getConnection(
                env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"));
    }

    Connection migrator() throws SQLException {
        return DriverManager.getConnection(
                env.getRequiredProperty("spring.flyway.url"),
                env.getRequiredProperty("spring.flyway.user"),
                env.getRequiredProperty("spring.flyway.password"));
    }

    /** Runs the work as sahaj_app with no tenant bound. Rolled back afterwards. */
    <T> T asApp(SqlWork<T> work) throws SQLException {
        try (Connection connection = app()) {
            return inRolledBackTransaction(connection, work);
        }
    }

    /** Runs the work as sahaj_app with the tenant bound for the transaction only. Rolled back afterwards. */
    <T> T inTenant(UUID tenantId, SqlWork<T> work) throws SQLException {
        try (Connection connection = app()) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            T result = work.apply(connection);
            connection.rollback();
            return result;
        }
    }

    /** Runs the work as sahaj_migrator. Rolled back afterwards. */
    <T> T asMigrator(SqlWork<T> work) throws SQLException {
        try (Connection connection = migrator()) {
            return inRolledBackTransaction(connection, work);
        }
    }

    private static <T> T inRolledBackTransaction(Connection connection, SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        T result = work.apply(connection);
        connection.rollback();
        return result;
    }

    /** Executes a statement that returns one number. */
    static long count(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Executes a statement that returns one text value. */
    static String text(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** Executes an update/DDL statement. */
    static void execute(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }
}
