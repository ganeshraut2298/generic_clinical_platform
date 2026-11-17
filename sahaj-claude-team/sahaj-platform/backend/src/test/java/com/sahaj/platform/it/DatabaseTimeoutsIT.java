package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DEC-054 review item 7: the request pool bounds every wait on the database. The unit test
 * (DatabaseTimeoutConfigTest) only reads the YAML text; this one asks PostgreSQL what a pooled connection really has.
 * Defaults: lock_timeout 10 s, statement_timeout 30 s (SAHAJ_DB_LOCK_TIMEOUT_MS / SAHAJ_DB_STATEMENT_TIMEOUT_MS unset).
 * PostgreSQL prints 10000 ms as "10s" and 30000 ms as "30s".
 */
@OnTestDatabase
class DatabaseTimeoutsIT extends BaseIntegrationTest {

    @Autowired
    private DataSource requestPool;

    @Test
    void aConnectionFromTheRequestPoolHasA30SecondStatementTimeoutAndA10SecondLockTimeout() throws Exception {
        try (var connection = requestPool.getConnection(); var st = connection.createStatement()) {
            assertThat(show(st, "current_user")).as("this is the app pool").isEqualTo("sahaj_app");
            assertThat(show(st, "statement_timeout")).as("SHOW statement_timeout").isEqualTo("30s");
            assertThat(show(st, "lock_timeout")).as("SHOW lock_timeout").isEqualTo("10s");
        }
    }

    @Test
    void theTimeoutsSurviveARoundTripThroughThePoolOnAnotherConnection() throws Exception {
        // Two checkouts, one after the other: the setting comes from the connection start-up options, not from one lucky session.
        for (int i = 0; i < 2; i++) {
            try (var connection = requestPool.getConnection(); var st = connection.createStatement()) {
                assertThat(show(st, "statement_timeout")).as("checkout " + i).isEqualTo("30s");
                assertThat(show(st, "lock_timeout")).as("checkout " + i).isEqualTo("10s");
            }
        }
    }

    private static String show(java.sql.Statement st, String what) throws java.sql.SQLException {
        String sql = what.equals("current_user") ? "select current_user" : "show " + what;
        try (var rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
