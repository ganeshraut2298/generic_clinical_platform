package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;

/**
 * DEC-016 / ADR-008 test T18, part 2 (review L1): the pre-flight of the dev-tools callback
 * {@code afterMigrate__dev_tools_sample_admin.sql} accepts only the exact names {@code sahaj_dev} and {@code sahaj_test}.
 * The text is loaded from the class path (the file that ships in the dev-tools jar) and every {@code current_database()} is
 * replaced by a string literal in memory (test code only). It runs as {@code sahaj_migrator} in a transaction that is rolled
 * back.
 *
 * <p>Wrong names must raise "Dev-tools sample admin refused" from the very first statement, before any grant. For the two
 * good names the name check must pass; what happens next depends on the human prerequisites (with the role or the tables
 * missing the pre-flight stops with its own message, which is accepted here; with them present the whole callback runs inside
 * the rolled-back transaction).
 */
class DevToolsCallbackPreflightIT {

    private static final String REFUSED = "Dev-tools sample admin refused";
    private static final String NAME_MESSAGE = "is not a Sahaj dev or test database";

    private String callback;
    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql")) {
            assertThat(in).as("dev-tools callback on the class path").isNotNull();
            callback = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(callback).contains("current_database()").contains("sahaj_(dev|test)$");
        connection = SampleAdminDb.migrator();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        connection.rollback();
        connection.close();
    }

    private PSQLException run(String databaseName) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(callback.replace("current_database()", "'" + databaseName + "'"));
            return null;
        } catch (PSQLException ex) {
            return ex;
        } finally {
            connection.rollback(savepoint);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_dev_staging", "sahaj_test_prod", "sahaj_dev_1", "sahaj_test_1", "sahaj_prod", "sahaj_staging",
            "Sahaj_Dev", "sahaj_dev "})
    void anyOtherDatabaseNameIsRefusedBeforeAnythingIsGranted(String name) throws SQLException {
        PSQLException error = run(name);
        assertThat(error != null).as("callback with database name '" + name + "' must raise").isTrue();
        String message = error.getServerErrorMessage().getMessage();
        assertThat(message).contains(REFUSED).contains(NAME_MESSAGE).contains("\"" + name + "\"");
        assertThat(message).doesNotContain("@example.test").doesNotContain("$2a$");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sahaj_dev", "sahaj_test"})
    void theTwoExactNamesPassTheNameCheck(String name) throws SQLException {
        PSQLException error = run(name);
        if (error != null) {
            // Allowed only for the prerequisite messages that come after the name check.
            assertThat(error.getServerErrorMessage().getMessage())
                    .as("the name check must not be what stops '" + name + "'").doesNotContain(NAME_MESSAGE);
        }
    }
}
