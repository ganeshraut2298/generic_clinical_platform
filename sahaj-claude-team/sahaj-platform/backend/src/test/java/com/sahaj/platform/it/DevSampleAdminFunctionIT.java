package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DEC-016 / ADR-008 test T14 and the function-level parts of T8, T9 and T10 (db design A1 to A8, D5, D7): the database
 * function {@code platform.admin_dev_create_sample_admin} called as {@code sahaj_app}, exactly as the endpoint does.
 *
 * <p>PRECONDITIONS (all three named when a test skips): the admin tables, the role {@code sahaj_admin_definer} and the
 * function itself, which exists only after one start of a {@code -Pdev-tools} build with Flyway on (the dev-tools
 * callback creates it). Tests that create rows also need an empty {@code platform_admin} and clean up after themselves
 * (see {@link SampleAdminDb}).
 */
class DevSampleAdminFunctionIT {

    private static final String CALL = "select platform.admin_dev_create_sample_admin(?, ?, ?, ?::smallint, ?::inet, ?)";
    private static final String HASH = "$2a$12$" + "a".repeat(53);
    private static final List<String> ADMIN_TABLES = List.of("platform_admin", "platform_admin_audit",
            "platform_admin_session", "platform_admin_preauth", "platform_admin_recovery_code", "platform_admin_auth_throttle");

    @BeforeEach
    void preconditions() {
        SampleAdminDb.assumeDevFunction();
    }

    private static byte[] cipher(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        return bytes;
    }

    /** Calls the function; null arguments are bound with their SQL types. */
    private static Boolean call(Connection c, UUID id, String hash, byte[] enc, Integer version, String ip, String request)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(CALL)) {
            if (id == null) {
                s.setNull(1, Types.OTHER);
            } else {
                s.setObject(1, id);
            }
            s.setString(2, hash);
            if (enc == null) {
                s.setNull(3, Types.BINARY);
            } else {
                s.setBytes(3, enc);
            }
            if (version == null) {
                s.setNull(4, Types.SMALLINT);
            } else {
                s.setInt(4, version);
            }
            s.setString(5, ip);
            s.setString(6, request);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static String sqlState(Connection c, Callable action) throws SQLException {
        Savepoint savepoint = c.setSavepoint();
        try {
            action.run();
            return null;
        } catch (SQLException ex) {
            c.rollback(savepoint);
            lastMessage = ex.getMessage();
            return ex.getSQLState();
        }
    }

    private static String lastMessage;

    @FunctionalInterface
    private interface Callable {
        void run() throws SQLException;
    }

    @Test
    void itRefusesWithPermissionDeniedWhenAClinicTenantIsBoundAndWritesNothing() throws Exception {
        SampleAdminDb.assumeNoAdmin();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, UUID.randomUUID().toString());
                bind.execute();
            }
            String state = sqlState(c, () -> call(c, UUID.randomUUID(), HASH, cipher(48), 1, "127.0.0.1", "qa"));
            assertThat(state).as("SQLSTATE with a tenant bound").isEqualTo("42501");
            c.rollback();
        }
        assertThat(SampleAdminDb.adminCount()).as("no admin written").isZero();
    }

    @Test
    void anEmptyTenantSettingLeftOverFromAPreviousTransactionIsTreatedAsNoTenant() throws Exception {
        SampleAdminDb.assumeNoAdmin();
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', '', true)")) {
                bind.execute();
            }
            assertThat(call(c, UUID.randomUUID(), HASH, cipher(48), 1, "127.0.0.1", "qa-empty-tenant")).isTrue();
            c.commit();
        } finally {
            SampleAdminDb.cleanup(snapshot);
        }
    }

    @Test
    void sahajAppHasNoPrivilegeOnAnyAdminTableAndCannotReadOrWriteThemDirectly() throws Exception {
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            for (String table : ADMIN_TABLES) {
                assertThat(sqlState(c, () -> {
                    try (PreparedStatement s = c.prepareStatement("select * from platform." + table + " limit 1")) {
                        s.executeQuery();
                    }
                })).as("select on " + table).isEqualTo("42501");
            }
            assertThat(sqlState(c, () -> {
                try (PreparedStatement s = c.prepareStatement(
                        "insert into platform.platform_admin (id, email, full_name, password_hash) values (gen_random_uuid(), "
                                + "'direct@example.test', 'Direct', '" + HASH + "')")) {
                    s.executeUpdate();
                }
            })).as("insert on platform_admin").isEqualTo("42501");
            c.rollback();
        }
        try (Connection c = SampleAdminDb.migrator()) {
            for (String table : ADMIN_TABLES) {
                assertThat(SampleAdminDb.scalarBoolean(c, "select has_table_privilege('sahaj_app', 'platform." + table
                        + "', 'SELECT,INSERT,UPDATE,DELETE') or has_any_column_privilege('sahaj_app', 'platform." + table
                        + "', 'SELECT,INSERT,UPDATE')")).as("sahaj_app privilege on " + table).isFalse();
            }
        }
    }

    @Test
    void theFunctionAndTheDefinerGrantAreExactlyAsDesigned() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            String sig = SampleAdminDb.FUNCTION_SIGNATURE;
            try (PreparedStatement s = c.prepareStatement("""
                    select pg_get_userbyid(proowner), prosecdef, coalesce(proconfig::text, ''), coalesce(proacl::text, '')
                      from pg_proc where oid = to_regprocedure(?)
                    """)) {
                s.setString(1, sig);
                try (ResultSet rs = s.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).as("owner").isEqualTo("sahaj_admin_definer");
                    assertThat(rs.getBoolean(2)).as("security definer").isTrue();
                    assertThat(rs.getString(3)).as("fixed search_path").contains("search_path=pg_catalog, pg_temp");
                    assertThat(rs.getString(4)).as("no EXECUTE for PUBLIC").doesNotContainPattern("(^\\{|,)=X/");
                }
            }
            assertThat(SampleAdminDb.scalarBoolean(c, "select has_function_privilege('sahaj_app', ?, 'EXECUTE')", sig))
                    .as("sahaj_app may execute it").isTrue();
            assertThat(SampleAdminDb.scalarBoolean(c, "select has_schema_privilege('sahaj_admin_definer', 'platform', 'CREATE')"))
                    .as("CREATE on schema platform taken back").isFalse();
            assertThat(SampleAdminDb.scalarBoolean(c,
                    "select has_table_privilege('sahaj_admin_definer', 'platform.platform_admin', 'INSERT')"))
                    .as("no table-wide INSERT").isFalse();
            TreeSet<String> columns = new TreeSet<>();
            try (PreparedStatement s = c.prepareStatement("""
                    select attname from pg_attribute
                     where attrelid = 'platform.platform_admin'::regclass and attnum > 0 and not attisdropped
                       and has_column_privilege('sahaj_admin_definer', attrelid, attnum, 'INSERT')
                    """); ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            assertThat(columns).as("INSERT columns of the definer").containsExactlyInAnyOrder("id", "email", "full_name",
                    "password_hash", "totp_secret_enc", "totp_key_version", "totp_enrolled_at");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash-x", "hash-59", "hash-61", "hash-prefix", "enc-47", "enc-65", "enc-null", "version-0",
            "version-null", "id-null", "request-101"})
    void aBadParameterIsRejectedWith22023AndTheMessageHoldsNoSuppliedValue(String bad) throws Exception {
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            UUID id = UUID.randomUUID();
            String hash = switch (bad) {
                case "hash-x" -> "x";
                case "hash-59" -> HASH.substring(1);
                case "hash-61" -> HASH + "a";
                case "hash-prefix" -> "$3a$12$" + "a".repeat(53);
                default -> HASH;
            };
            byte[] enc = switch (bad) {
                case "enc-47" -> cipher(47);
                case "enc-65" -> cipher(65);
                case "enc-null" -> null;
                default -> cipher(48);
            };
            Integer version = switch (bad) {
                case "version-0" -> 0;
                case "version-null" -> null;
                default -> 1;
            };
            String request = "request-101".equals(bad) ? "r".repeat(101) : "qa-bad";
            UUID sentId = "id-null".equals(bad) ? null : id;
            String state = sqlState(c, () -> call(c, sentId, hash, enc, version, "127.0.0.1", request));
            assertThat(state).as("SQLSTATE for " + bad).isEqualTo("22023");
            assertThat(lastMessage).doesNotContain(HASH).doesNotContain(id.toString()).doesNotContain("rrrrrrrrrr")
                    .doesNotContain("@example.test");
            c.rollback();
        }
    }

    @Test
    void theRequestIdOfExactly100CharactersAndANullAddressAreAccepted() throws Exception {
        SampleAdminDb.assumeNoAdmin();
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(call(c, UUID.randomUUID(), HASH, cipher(48), 1, null, "r".repeat(100))).isTrue();
        } finally {
            SampleAdminDb.cleanup(snapshot);
        }
    }

    @Test
    void itCreatesOnlyTheFixedIdentityOnceAndTheSecondCallChangesNothingAndLeavesOneRefusedAuditRow() throws Exception {
        SampleAdminDb.assumeNoAdmin();
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        UUID first = UUID.randomUUID();
        byte[] enc = cipher(48);
        try (Connection app = SampleAdminDb.app(); Connection owner = SampleAdminDb.migrator()) {
            assertThat(call(app, first, HASH, enc, 1, "127.0.0.1", "qa-create")).as("first call").isTrue();

            String before;
            try (PreparedStatement s = owner.prepareStatement("""
                    select id, email, full_name, active, totp_key_version, totp_enrolled_at is not null, totp_last_used_step is null,
                           password_hash = ?, totp_secret_enc = ?, md5(password_hash || encode(totp_secret_enc, 'hex') || coalesce(updated_at::text, ''))
                      from platform.platform_admin
                    """)) {
                s.setString(1, HASH);
                s.setBytes(2, enc);
                try (ResultSet rs = s.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1, UUID.class)).isEqualTo(first);
                    assertThat(rs.getString(2)).isEqualTo("sample-admin@example.test");
                    assertThat(rs.getString(3)).isEqualTo("Sample admin");
                    assertThat(rs.getBoolean(4)).isTrue();
                    assertThat(rs.getInt(5)).isEqualTo(1);
                    assertThat(rs.getBoolean(6)).as("enrolled").isTrue();
                    assertThat(rs.getBoolean(7)).as("totp_last_used_step is null").isTrue();
                    assertThat(rs.getBoolean(8)).as("hash stored as given").isTrue();
                    assertThat(rs.getBoolean(9)).as("ciphertext stored as given").isTrue();
                    before = rs.getString(10);
                    assertThat(rs.next()).as("exactly one admin row").isFalse();
                }
            }
            assertThat(SampleAdminDb.scalarLong(owner, "select count(*) from platform.platform_admin_recovery_code"))
                    .as("no recovery codes").isZero();

            assertThat(call(app, UUID.randomUUID(), "$2a$12$" + "b".repeat(53), cipher(49), 1, "127.0.0.1", "qa-second"))
                    .as("second call").isFalse();

            String after = SampleAdminDb.scalarText(owner, "select md5(password_hash || encode(totp_secret_enc, 'hex') || coalesce(updated_at::text, '')) "
                    + "from platform.platform_admin");
            assertThat(after).as("password hash, ciphertext and updated_at untouched").isEqualTo(before);
            assertThat(SampleAdminDb.scalarLong(owner, "select count(*) from platform.platform_admin")).isEqualTo(1);

            List<String> audit = SampleAdminDb.auditRowsSince(snapshot);
            assertThat(audit).hasSize(2);
            assertThat(audit.get(0)).contains("\"action\":\"ADMIN_DEV_SAMPLE_CREATED\"").contains("\"outcome\":\"APPLIED\"")
                    .contains("\"admin_id\":\"" + first + "\"").contains("\"admin_name\":\"Sample admin\"")
                    .contains("\"kind\":\"AUTH\"").contains("\"request_id\":\"qa-create\"");
            assertThat(audit.get(1)).contains("\"action\":\"ADMIN_DEV_SAMPLE_REFUSED\"").contains("\"outcome\":\"REFUSED\"")
                    .contains("\"refusal_code\":\"admin-exists\"").contains("\"admin_id\":null")
                    .contains("\"request_id\":\"qa-second\"");
            for (String row : audit) {
                assertThat(row).as("audit rows hold no password, hash, ciphertext or email")
                        .doesNotContain("$2a$").doesNotContain("@example.test").doesNotContain("\\\\x");
            }
        } finally {
            SampleAdminDb.cleanup(snapshot);
        }
    }

    @Test
    void anyExistingAdminActiveOrNotAndWhateverItsEmailClosesTheFunction() throws Exception {
        SampleAdminDb.assumeNoAdmin();
        for (boolean active : new boolean[] {true, false}) {
            SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
            try (Connection app = SampleAdminDb.app(); Connection owner = SampleAdminDb.migrator()) {
                SampleAdminDb.insertAdmin("seeded-" + active + "@example.test", active);
                assertThat(call(app, UUID.randomUUID(), HASH, cipher(48), 1, "127.0.0.1", "qa-other")).as("active=" + active).isFalse();
                assertThat(SampleAdminDb.scalarLong(owner, "select count(*) from platform.platform_admin")).isEqualTo(1);
                assertThat(SampleAdminDb.scalarLong(owner,
                        "select count(*) from platform.platform_admin where email = 'sample-admin@example.test'")).isZero();
            } finally {
                SampleAdminDb.cleanup(snapshot);
            }
        }
    }

    @Test
    void tenConcurrentCallersCreateExactlyOneAdmin() throws Exception {
        SampleAdminDb.assumeNoAdmin();
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    try (Connection c = SampleAdminDb.app()) {
                        start.await();
                        return call(c, UUID.randomUUID(), HASH, cipher(48), 1, "127.0.0.1", "qa-race-" + n);
                    }
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(60, TimeUnit.SECONDS))) {
                    created++;
                }
            }
            assertThat(created).as("callers that created the admin").isEqualTo(1);
            assertThat(SampleAdminDb.adminCount()).isEqualTo(1);
            List<String> audit = SampleAdminDb.auditRowsSince(snapshot);
            assertThat(audit.stream().filter(r -> r.contains("ADMIN_DEV_SAMPLE_CREATED")).count()).isEqualTo(1);
            assertThat(audit.stream().filter(r -> r.contains("ADMIN_DEV_SAMPLE_REFUSED")).count()).isEqualTo(9);
        } finally {
            pool.shutdownNow();
            SampleAdminDb.cleanup(snapshot);
        }
    }
}
