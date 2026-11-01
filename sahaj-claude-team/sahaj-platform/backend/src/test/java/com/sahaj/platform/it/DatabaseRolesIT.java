package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SAH-1 criterion 9: the application connects as {@code sahaj_app} (not a superuser, no BYPASSRLS,
 * not the table owner) and Flyway ran as {@code sahaj_migrator}; the test profile uses sahaj_test.
 */
@OnTestDatabase
class DatabaseRolesIT extends BaseIntegrationTest {

    @Autowired
    private DataSource requestPool;

    @Test
    void theRequestPoolConnectsAsSahajAppToSahajTest() throws Exception {
        try (var connection = requestPool.getConnection(); var st = connection.createStatement()) {
            try (var rs = st.executeQuery("select current_user, session_user, current_database()")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("current_user").isEqualTo("sahaj_app");
                assertThat(rs.getString(2)).as("session_user").isEqualTo("sahaj_app");
                assertThat(rs.getString(3)).as("database").isEqualTo("sahaj_test");
            }
        }
    }

    @Test
    void sahajAppIsNotASuperuserAndCannotBypassRowLevelSecurity() throws Exception {
        db().asApp(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "select rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolreplication from pg_roles where rolname = current_user")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("rolsuper")).as("superuser").isFalse();
                assertThat(rs.getBoolean("rolbypassrls")).as("bypassrls").isFalse();
                assertThat(rs.getBoolean("rolcreaterole")).as("createrole").isFalse();
                assertThat(rs.getBoolean("rolcreatedb")).as("createdb").isFalse();
                assertThat(rs.getBoolean("rolreplication")).as("replication").isFalse();
            }
            return null;
        });
    }

    @Test
    void sahajAppOwnsNoTableInTheModuleSchemas() throws Exception {
        db().asApp(c -> {
            assertThat(DbAccess.count(c,
                    "select count(*) from pg_tables where schemaname in ('platform','patient','opd','ipd','hra','billing') and tableowner = 'sahaj_app'"))
                    .isZero();
            assertThat(DbAccess.count(c, "select count(*) from pg_tables where schemaname = 'platform' and tableowner = 'sahaj_migrator'"))
                    .as("the platform tables are owned by sahaj_migrator").isGreaterThanOrEqualTo(5);
            return null;
        });
    }

    @Test
    void sahajAppCannotTurnRowLevelSecurityOff() {
        // A role that cannot bypass RLS gets an error, not extra rows, when it asks for row_security = off.
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "set row_security = off");
            return DbAccess.count(c, "select count(*) from platform.tenant");
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void sahajAppCannotChangeTheSchemaOrBecomeTheMigrator() {
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "create table platform.created_by_app (id int)");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "alter table platform.tenant disable row level security");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "set role sahaj_migrator");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void flywayRanAsSahajMigratorAndAppliedEveryPlatformMigration() throws Exception {
        db().asMigrator(c -> {
            assertThat(DbAccess.text(c, "select current_user")).isEqualTo("sahaj_migrator");
            List<String> versions = new ArrayList<>();
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "select version, installed_by, success from platform.flyway_schema_history where version is not null order by installed_rank")) {
                while (rs.next()) {
                    assertThat(rs.getString("installed_by")).as("installed_by of " + rs.getString("version")).isEqualTo("sahaj_migrator");
                    assertThat(rs.getBoolean("success")).as("success of " + rs.getString("version")).isTrue();
                    versions.add(rs.getString("version"));
                }
            }
            assertThat(versions).contains("2026.11.01.1000", "2026.11.01.1001", "2026.11.01.1002",
                    "2026.11.01.1003", "2026.11.01.1004", "2026.11.01.1005");
            return null;
        });
    }

    @Test
    void theFlywayConnectionIsNotTheRequestPool() throws Exception {
        assertThat(env.getRequiredProperty("spring.flyway.user")).isEqualTo("sahaj_migrator");
        assertThat(env.getRequiredProperty("spring.datasource.username")).isEqualTo("sahaj_app");
        assertThat(env.getRequiredProperty("spring.flyway.user")).isNotEqualTo(env.getRequiredProperty("spring.datasource.username"));
    }

    @Test
    void theTestProfileUsesTheSahajTestDatabaseForBothConnections() throws Exception {
        assertThat(env.getRequiredProperty("spring.datasource.url")).endsWith("/sahaj_test");
        assertThat(env.getRequiredProperty("spring.flyway.url")).endsWith("/sahaj_test");
        String database = db().asMigrator(c -> DbAccess.text(c, "select current_database()"));
        assertThat(database).isEqualTo("sahaj_test");
    }
}
