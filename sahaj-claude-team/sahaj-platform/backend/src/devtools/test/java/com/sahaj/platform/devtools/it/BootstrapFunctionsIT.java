package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * SAH-42 database design cases S4 to S26, S30 and (Revision 3) S31 to S38 (docs/database/SAH-42.md "Cases to cover"; ADR-018
 * R3-I1, R3-I3, R3-I7, R3-I8, R3-I13 at function level): the five SECURITY DEFINER functions called as {@code sahaj_app} exactly as
 * the application calls them (claim, commit, judge), with real SQLSTATEs {@code SB001}, {@code SB003}, {@code SB004}, {@code 22023},
 * {@code 42501}, {@code 55P03} ({@code SB002} is retired, S38). Time is moved by data as {@code sahaj_migrator} ({@link BootstrapDb}),
 * inside the table checks; nothing sleeps except the real 10-second lock wait of S23 and no clock is read in a test.
 *
 * <p>PRECONDITIONS (a skipped class names the one that is missing): role {@code sahaj_admin_definer}, the admin tables and
 * {@code platform.admin_preauth_create} (human prerequisites, DEC-016); the five functions and the state table (made by the
 * dev-tools callback; this class starts the application once if they are not there and FAILS, never skips, if the callback did not
 * create them); an empty {@code platform_admin} (a stray admin row is a failure that names the clean-up).
 *
 * <p>These tests have NEVER run (the SQL has never been executed); read them as the first thing to run once the prerequisites
 * are done. A failure here is a finding against the SQL (db-engineer), not against the test, unless the plan says otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapFunctionsIT {

    private static final String BCRYPT = "$2a$12$" + "a".repeat(53);

    private SampleAdminDb.Snapshot snapshot;

    @BeforeAll
    void preconditions() {
        BootstrapSupport.ensureObjectsOrSkip();
        BootstrapDb.failIfAnAdminExists();
    }

    @BeforeEach
    void cleanSlate() {
        BootstrapDb.failIfAnAdminExists();
        snapshot = SampleAdminDb.snapshot();
        BootstrapDb.resetState();
    }

    @AfterEach
    void clean() {
        if (snapshot != null) {
            SampleAdminDb.cleanup(snapshot);
        }
        BootstrapDb.resetState();
    }

    // ---- helpers -----------------------------------------------------------------------------------------
    // The five functions are called through BootstrapSql exactly as the application calls them (sahaj_app, caller commits).
    // Revision 3 shapes: begin(uuid) -> (outcome, failures, locked_until); unlock_failed(uuid, inet, text) -> (failures,
    // locked_until); unlock_succeeded(uuid, text, text) -> timestamptz; ticket_status -> (status, locked_until);
    // register -> (outcome, locked_until).

    private static BootstrapSql.Begin begin(Connection c, UUID tryId) throws SQLException {
        return BootstrapSql.begin(c, tryId);
    }

    /** A claim with a fresh random try id, as Java makes it. */
    private static BootstrapSql.Begin begin(Connection c) throws SQLException {
        return BootstrapSql.begin(c, UUID.randomUUID());
    }

    private static BootstrapSql.Failed unlockFailed(Connection c, UUID tryId) throws SQLException {
        return BootstrapSql.unlockFailed(c, tryId);
    }

    private static Timestamp unlockSucceeded(Connection c, UUID tryId, String ticket, String binding) throws SQLException {
        return BootstrapSql.unlockSucceeded(c, tryId, ticket, binding);
    }

    private static String ticketStatus(Connection c, String ticket, String binding) throws SQLException {
        return BootstrapSql.ticketStatus(c, ticket, binding).status();
    }

    private static String register(Connection c, String ticket, String binding, UUID id, String email, String name, String hash,
                                   String request) throws SQLException {
        return BootstrapSql.register(c, ticket, binding, id, email, name, hash, request).outcome();
    }

    private static String register(Connection c, String ticket, String binding, String email) throws SQLException {
        return register(c, ticket, binding, UUID.randomUUID(), email, "First Admin", BCRYPT, "qa-request");
    }

    /** 64 lower-case hex characters, different on every call: stands in for an HMAC (the database treats it as opaque). */
    private static String hex64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private static String email() {
        return "qa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    /**
     * One wrong try as the application runs it since Revision 3: the claim (own transaction, committed), then the judge (second
     * transaction, committed). Returns what the judge returned. The claim must have been COUNTED.
     */
    private static BootstrapSql.Failed oneWrongTry() throws SQLException {
        UUID tryId = UUID.randomUUID();
        BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(tryId);
        assertThat(claim.outcome()).as("the claim of a wrong try").isEqualTo("COUNTED");
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            BootstrapSql.Failed failed = unlockFailed(c, tryId);
            c.commit();
            return failed;
        }
    }

    /** One correct unlock: claim, commit, judge, commit. Returns {ticket, binding}. */
    private static String[] oneCorrectUnlock() throws SQLException {
        String ticket = hex64();
        String binding = hex64();
        UUID tryId = UUID.randomUUID();
        BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(tryId);
        assertThat(claim.outcome()).as("the claim of a correct unlock").isEqualTo("COUNTED");
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            unlockSucceeded(c, tryId, ticket, binding);
            c.commit();
        }
        return new String[] {ticket, binding};
    }

    /** {@code updated_at} of the state row as text, to prove that a call wrote nothing. */
    private static String updatedAt() throws SQLException {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarText(c, "select updated_at::text from " + BootstrapDb.TABLE + " where id = 1");
        }
    }

    /** Runs the action inside a savepoint; returns the SQLSTATE, or null when it did not fail. The savepoint is rolled back. */
    private static String sqlState(Connection c, SqlAction action) throws SQLException {
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

    private static volatile String lastMessage;

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private static long scalarLong(String sql, Object... params) throws SQLException {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarLong(c, sql, params);
        }
    }

    private static boolean scalarBoolean(String sql, Object... params) throws SQLException {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarBoolean(c, sql, params);
        }
    }

    // ---- S4: metadata ---------------------------------------------------------------------------------------

    @Test
    void s4TheFiveFunctionsAreOwnedByTheDefinerSecurityDefinerWithAFixedSearchPathAndExecutableOnlyBySahajApp() throws Exception {
        try (Connection c = SampleAdminDb.migrator();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     select p.proname, pg_get_userbyid(p.proowner), p.prosecdef, coalesce(p.proconfig::text, ''), coalesce(p.proacl::text, '')
                       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                      where n.nspname = 'platform' and p.proname like 'admin\\_bootstrap\\_%' order by 1""")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                names.add(name);
                assertThat(rs.getString(2)).as(name + " owner").isEqualTo("sahaj_admin_definer");
                assertThat(rs.getBoolean(3)).as(name + " is SECURITY DEFINER").isTrue();
                String config = rs.getString(4);
                assertThat(config).as(name + " config").contains("search_path=pg_catalog, pg_temp");
                if (name.equals("admin_bootstrap_ticket_status")) {
                    assertThat(config).as(name + " takes no row lock, so it has no lock_timeout").doesNotContain("lock_timeout");
                } else {
                    // Revision 3: all four row-locking functions bound their wait, the two judges included.
                    assertThat(config).as(name + " bounds its wait").contains("lock_timeout=10s");
                }
                String acl = rs.getString(5);
                assertThat(acl).as(name + " ACL: sahaj_app can execute").contains("sahaj_app=X/");
                assertThat(acl).as(name + " ACL: the definer can execute").contains("sahaj_admin_definer=X/");
                assertThat(acl).as(name + " ACL: PUBLIC cannot execute (an empty grantee)").doesNotContainPattern("(^\\{|,)=X/");
                assertThat(acl.split(",").length).as(name + " ACL has exactly two grantees: " + acl).isEqualTo(2);
            }
            assertThat(names).as("exactly five functions: no Revision 2 shape is left").containsExactly("admin_bootstrap_begin",
                    "admin_bootstrap_register", "admin_bootstrap_ticket_status", "admin_bootstrap_unlock_failed",
                    "admin_bootstrap_unlock_succeeded");
        }
        for (String signature : BootstrapDb.FUNCTIONS) {
            assertThat(scalarBoolean("select to_regprocedure(?) is not null", signature)).as(signature).isTrue();
        }
        for (String old : BootstrapDb.OLD_SHAPES) {
            assertThat(scalarBoolean("select to_regprocedure(?) is null", old)).as("Revision 2 shape " + old + " is gone").isTrue();
        }
    }

    @Test
    void s4TheStateTableHasTheNineDesignedColumnsAndNineNamedConstraints() throws Exception {
        try (Connection c = SampleAdminDb.migrator()) {
            assertThat(SampleAdminDb.scalarText(c, "select string_agg(attname, ',' order by attnum) from pg_attribute "
                    + "where attrelid = ?::regclass and attnum > 0 and not attisdropped", BootstrapDb.TABLE))
                    .isEqualTo("id,failures,locked_until,ticket_hash,binding_hash,ticket_expires_at,updated_at,try_id,try_expires_at");
            assertThat(SampleAdminDb.scalarText(c, "select string_agg(conname, ',' order by conname collate \"C\") from pg_constraint where conrelid = ?::regclass",
                    BootstrapDb.TABLE)).isEqualTo("ck_platform_admin_bootstrap_state_binding_hash,ck_platform_admin_bootstrap_state_failures,"
                    + "ck_platform_admin_bootstrap_state_id,ck_platform_admin_bootstrap_state_lock_matches_failures,"
                    + "ck_platform_admin_bootstrap_state_no_ticket_while_locked,ck_platform_admin_bootstrap_state_ticket_all_or_none,"
                    + "ck_platform_admin_bootstrap_state_ticket_hash,ck_platform_admin_bootstrap_state_try_both_or_none,"
                    + "pk_platform_admin_bootstrap_state");
        }
    }

    @Test
    void s4TheStateTableGivesTheDefinerSelectInsertUpdateOnlyAndNobodyElseAnything() throws Exception {
        String table = BootstrapDb.TABLE;
        assertThat(scalarBoolean("select pg_get_userbyid(relowner) = 'sahaj_migrator' from pg_class where oid = ?::regclass", table))
                .as("owner is the migration user").isTrue();
        for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE"}) {
            assertThat(scalarBoolean("select has_table_privilege('sahaj_admin_definer', ?::regclass, ?)", table, privilege))
                    .as("definer " + privilege).isTrue();
        }
        for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(scalarBoolean("select has_table_privilege('sahaj_admin_definer', ?::regclass, ?)", table, privilege))
                    .as("definer " + privilege).isFalse();
        }
        for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
            assertThat(scalarBoolean("select has_table_privilege('sahaj_app', ?::regclass, ?)", table, privilege))
                    .as("sahaj_app " + privilege).isFalse();
        }
        assertThat(scalarLong("select count(*) from pg_class c, aclexplode(c.relacl) a where c.oid = ?::regclass and a.grantee = 0",
                table)).as("no privilege for PUBLIC").isZero();
        assertThat(scalarBoolean("select has_schema_privilege('sahaj_admin_definer', 'platform', 'CREATE')"))
                .as("the temporary CREATE on schema platform was taken back").isFalse();
    }

    @Test
    void s4TheDefinerMayInsertTheFourColumnsAndNothingBeyondTheSevenOfTheSampleAdminCallback() throws Exception {
        List<String> required = List.of("id", "email", "full_name", "password_hash");
        List<String> allowedToo = List.of("totp_secret_enc", "totp_key_version", "totp_enrolled_at");
        for (String column : required) {
            assertThat(scalarBoolean("select has_column_privilege('sahaj_admin_definer', 'platform.platform_admin', ?, 'INSERT')", column))
                    .as("INSERT on " + column).isTrue();
        }
        for (String column : List.of("active", "password_changed_at", "totp_last_used_step", "last_sign_in_at", "created_at",
                "created_by", "updated_at", "updated_by", "version")) {
            assertThat(scalarBoolean("select has_column_privilege('sahaj_admin_definer', 'platform.platform_admin', ?, 'INSERT')", column))
                    .as("no INSERT on " + column + " (allowed extras are only " + allowedToo + ")").isFalse();
        }
        assertThat(scalarBoolean("select has_table_privilege('sahaj_admin_definer', 'platform.platform_admin', 'INSERT')"))
                .as("no table-wide INSERT").isFalse();
    }

    // ---- S5: sahaj_app has no privilege -----------------------------------------------------------------------

    @Test
    void s5SahajAppCannotReadOrWriteTheStateTableDirectly() throws Exception {
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            for (String sql : List.of("select * from " + BootstrapDb.TABLE, "insert into " + BootstrapDb.TABLE + " (id) values (1)",
                    "update " + BootstrapDb.TABLE + " set failures = 0", "delete from " + BootstrapDb.TABLE,
                    "truncate " + BootstrapDb.TABLE)) {
                assertThat(sqlState(c, () -> {
                    try (Statement s = c.createStatement()) {
                        s.execute(sql);
                    }
                })).as(sql + ": " + lastMessage).isEqualTo("42501");
            }
            c.rollback();
        }
    }

    // ---- S6 .. S10: begin (the claim), counts, locks ----------------------------------------------------------

    @Test
    void s6TheFirstClaimCountsOneAndHandsOutAThirtySecondLeaseAndIsVisibleToASecondConnectionAtOnce() throws Exception {
        UUID tryId = UUID.randomUUID();
        Instant before = BootstrapDb.dbNow();

        BootstrapSql.Begin result = BootstrapSql.claimAndCommit(tryId);

        Instant after = BootstrapDb.dbNow();
        assertThat(result.outcome()).isEqualTo("COUNTED");
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.lockedUntil()).isNull();
        // First-run watch item (db design note, "What QA must watch" 3): read through ANOTHER connection (sahaj_migrator)
        // right after begin returned and committed, before any judge exists. The claim is the only write the limit depends on.
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state).as("row 1 exists after the first claim").isNotNull();
        assertThat(state.failures()).as("the count is committed and visible").isEqualTo(1);
        assertThat(state.tryId()).as("the lease belongs to this try").isEqualTo(tryId);
        assertThat(state.tryExpiresAt()).as("the lease is 30 seconds on the database clock")
                .isBetween(before.plusSeconds(29), after.plusSeconds(31));
        assertThat(state.lockedUntil()).isNull();
        assertThat(BootstrapDb.auditActions(snapshot)).as("a claim writes no audit row").isEmpty();
    }

    @Test
    void s6BeginWithoutATryIdRaises22023WithoutCountingAnything() throws Exception {
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> BootstrapSql.begin(c, null))).as("begin(null): " + lastMessage).isEqualTo("22023");
            c.rollback();
        }
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).isZero();
        assertThat(state.hasLease()).isFalse();
    }

    @Test
    void s6WhenAnAdminExistsBeginIsClosedAndWritesNothingNotEvenTheStateRow() throws Exception {
        try (Connection c = SampleAdminDb.migrator(); Statement s = c.createStatement()) {
            s.execute("delete from " + BootstrapDb.TABLE + " where id = 1");
        }
        SampleAdminDb.insertAdmin(email(), true);

        BootstrapSql.Begin result = BootstrapSql.claimAndCommit(UUID.randomUUID());

        assertThat(result).isEqualTo(new BootstrapSql.Begin("CLOSED", 0, null));
        assertThat(BootstrapDb.state()).as("no row was created").isNull();
        assertThat(BootstrapDb.auditActions(snapshot)).as("nothing audited").isEmpty();
    }

    @Test
    void s7FourWrongTriesThenAFifthThatIsCorrectCountOneToFiveThenZeroAndATicketWithFourAuditRowsAndNoLock() throws Exception {
        for (int n = 1; n <= 4; n++) {
            UUID tryId = UUID.randomUUID();
            BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(tryId);
            assertThat(claim.outcome()).isEqualTo("COUNTED");
            assertThat(claim.failures()).as("the claim counts try " + n).isEqualTo(n);
            assertThat(BootstrapDb.state().failures()).as("visible before any judge, try " + n).isEqualTo(n);
            try (Connection c = SampleAdminDb.app()) {
                c.setAutoCommit(false);
                BootstrapSql.Failed failed = unlockFailed(c, tryId);
                c.commit();
                assertThat(failed).as("the judge reports the count the claim made").isEqualTo(new BootstrapSql.Failed(n, null));
            }
            assertThat(BootstrapDb.state().failures()).as("the judge did not change the count, try " + n).isEqualTo(n);
            assertThat(BootstrapDb.state().hasLease()).as("the judge gave the lease back").isFalse();
        }
        UUID fifth = UUID.randomUUID();
        Instant before = BootstrapDb.dbNow();
        BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(fifth);
        assertThat(claim.outcome()).isEqualTo("COUNTED");
        assertThat(claim.failures()).isEqualTo(5);
        assertThat(claim.lockedUntil().toInstant()).as("the 5th claim sets the lock already")
                .isBetween(before.plusSeconds(3598), BootstrapDb.dbNow().plusSeconds(3601));
        String ticket = hex64();
        String binding = hex64();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            unlockSucceeded(c, fifth, ticket, binding);
            c.commit();
        }

        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).as("4 wrong, then correct, unlocks (BR7c)").isZero();
        assertThat(state.lockedUntil()).as("the correct judge lifts the lock its own claim set").isNull();
        assertThat(state.hasLease()).isFalse();
        assertThat(state.ticketHash()).isEqualTo(ticket);
        assertThat(state.bindingHash()).isEqualTo(binding);
        assertThat(state.ticketExpiresAt()).isAfter(BootstrapDb.dbNow().plusSeconds(290)).isBefore(BootstrapDb.dbNow().plusSeconds(301));
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).isEqualTo(4);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).isZero();
        assertThat(BootstrapDb.auditActions(snapshot)).as("the success writes no audit row").hasSize(4);
    }

    @Test
    void s8TheFifthClaimAlreadyLocksSixtyMinutesAndClearsTheTicketBeforeAnyJudgeAndTheJudgeWritesTwoAuditRows() throws Exception {
        oneCorrectUnlock();
        assertThat(BootstrapDb.state().hasTicket()).isTrue();
        for (int n = 1; n <= 4; n++) {
            oneWrongTry();
        }
        UUID fifth = UUID.randomUUID();
        Instant before = BootstrapDb.dbNow();

        BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(fifth);

        assertThat(claim.outcome()).isEqualTo("COUNTED");
        assertThat(claim.failures()).isEqualTo(5);
        assertThat(claim.lockedUntil().toInstant()).isBetween(before.plusSeconds(3598), BootstrapDb.dbNow().plusSeconds(3601));
        BootstrapDb.State afterClaim = BootstrapDb.state();
        assertThat(afterClaim.failures()).as("committed before any judge").isEqualTo(5);
        assertThat(afterClaim.lockedUntil()).as("the lock is committed before any judge").isNotNull();
        assertThat(afterClaim.hasTicket()).as("the lock cancels the open ticket at claim time (BR7e)").isFalse();
        assertThat(afterClaim.tryId()).isEqualTo(fifth);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).as("no row yet for the 5th").isEqualTo(4);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).isZero();

        BootstrapSql.Failed judged;
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            judged = unlockFailed(c, fifth);
            c.commit();
        }

        assertThat(judged.failures()).isEqualTo(5);
        assertThat(judged.lockedUntil()).isEqualTo(claim.lockedUntil());
        assertThat(BootstrapDb.state().hasLease()).as("lease given back").isFalse();
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).isEqualTo(5);
        List<String> failedRows = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED");
        assertThat(failedRows.get(4)).contains("Bootstrap registration: wrong PIN (5 of 5)");
        List<String> locked = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCKED");
        assertThat(locked).hasSize(1);
        assertThat(locked.get(0)).contains("\"lockedUntil\"").containsPattern("\"lockedUntil\"\\s*:\\s*\"[^\"]+Z\"");
    }

    @Test
    void s9WhileLockedWithNoLeaseBeginReportsLockedAndWritesNothingAndTheJudgesRaiseSB004NeverSB002() throws Exception {
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        BootstrapDb.State before = BootstrapDb.state();
        // R3-I8, locked case: the lease columns are null (the judge gave the lease back).
        assertThat(before.tryId()).isNull();
        assertThat(before.tryExpiresAt()).isNull();
        assertThat(before.lockedUntil()).isNotNull();
        String updated = updatedAt();
        int auditBefore = BootstrapDb.auditActions(snapshot).size();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            BootstrapSql.Begin during = begin(c);
            assertThat(during.outcome()).isEqualTo("LOCKED");
            assertThat(during.failures()).isEqualTo(5);
            assertThat(during.lockedUntil()).isEqualTo(Timestamp.from(before.lockedUntil()));
            assertThat(sqlState(c, () -> unlockFailed(c, UUID.randomUUID()))).as("unlock_failed while locked: " + lastMessage).isEqualTo("SB004");
            assertThat(sqlState(c, () -> unlockSucceeded(c, UUID.randomUUID(), hex64(), hex64()))).as("unlock_succeeded while locked: " + lastMessage)
                    .isEqualTo("SB004");
            c.commit();
        }
        assertThat(BootstrapDb.state()).as("nothing changed").isEqualTo(before);
        assertThat(updatedAt()).as("not even updated_at").isEqualTo(updated);
        assertThat(BootstrapDb.auditActions(snapshot)).hasSize(auditBefore);
    }

    @Test
    void s10WhenTheLockHasEndedBeginClearsItAndCountsThisTryAsTheFirstOfANewRun() throws Exception {
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        BootstrapDb.lockEndedAgo(Duration.ofSeconds(1));
        UUID tryId = UUID.randomUUID();

        BootstrapSql.Begin result = BootstrapSql.claimAndCommit(tryId);

        assertThat(result).isEqualTo(new BootstrapSql.Begin("COUNTED", 1, null));
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).isEqualTo(1);
        assertThat(state.lockedUntil()).isNull();
        assertThat(state.tryId()).isEqualTo(tryId);
    }

    @Test
    void s10bAJudgeThatSkippedTheClaimNeverClearsAnEndedLockItRaisesSB004AndTheRowIsUntouched() throws Exception {
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        BootstrapDb.lockEndedAgo(Duration.ofSeconds(1));
        BootstrapDb.State before = BootstrapDb.state();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> unlockFailed(c, UUID.randomUUID()))).as(lastMessage).isEqualTo("SB004");
            c.rollback();
        }
        assertThat(BootstrapDb.state()).isEqualTo(before);
    }

    @Test
    void s10cWhenTheLockHasEndedAndALeaseIsStillLiveBeginClearsTheLockAndAnswersBusyWithTheCountRestarted() throws Exception {
        for (int n = 1; n <= 4; n++) {
            oneWrongTry();
        }
        UUID fifth = UUID.randomUUID();
        BootstrapSql.claimAndCommit(fifth); // lock and a live lease together, the judge never runs
        BootstrapDb.lockEndedAgo(Duration.ofSeconds(1)); // time travel on the lock only; the lease (30 s) is still live

        BootstrapSql.Begin result = BootstrapSql.claimAndCommit(UUID.randomUUID());

        assertThat(result.outcome()).isEqualTo("BUSY");
        assertThat(result.lockedUntil()).isNull();
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).as("the run-out lock was cleared, the count restarted").isZero();
        assertThat(state.lockedUntil()).isNull();
        assertThat(state.tryId()).as("the live lease is still the 5th try's").isEqualTo(fifth);
    }

    // ---- S11 .. S13: tickets -------------------------------------------------------------------------------------

    @Test
    void s11ALockCancelsTheTicketStatusIsLockedWhileItHoldsAndInvalidAfterIt() throws Exception {
        String[] ticket = oneCorrectUnlock();
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        try (Connection c = SampleAdminDb.app()) {
            assertThat(ticketStatus(c, ticket[0], ticket[1])).isEqualTo("LOCKED");
        }
        BootstrapDb.lockEndedAgo(Duration.ofSeconds(1));
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            begin(c);
            c.commit();
            assertThat(ticketStatus(c, ticket[0], ticket[1])).isEqualTo("INVALID");
        }
    }

    @Test
    void s12ANewUnlockReplacesTheTicketTheOldHashesAreInvalidTheNewValid() throws Exception {
        String[] first = oneCorrectUnlock();
        String[] second = oneCorrectUnlock();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(ticketStatus(c, first[0], first[1])).isEqualTo("INVALID");
            assertThat(ticketStatus(c, second[0], second[1])).isEqualTo("VALID");
        }
    }

    @Test
    void s13TicketStatusIsValidOnlyForTheMatchingPairWithinFiveMinutesAndNeverRaises() throws Exception {
        String[] ticket = oneCorrectUnlock();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(ticketStatus(c, ticket[0], ticket[1])).isEqualTo("VALID");
            assertThat(ticketStatus(c, ticket[1], ticket[0])).as("halves swapped").isEqualTo("INVALID");
            assertThat(ticketStatus(c, ticket[0], hex64())).as("binding wrong").isEqualTo("INVALID");
            assertThat(ticketStatus(c, hex64(), ticket[1])).as("ticket wrong").isEqualTo("INVALID");
            assertThat(ticketStatus(c, null, ticket[1])).as("null ticket").isEqualTo("INVALID");
            assertThat(ticketStatus(c, ticket[0], null)).as("null binding").isEqualTo("INVALID");
            assertThat(ticketStatus(c, null, null)).isEqualTo("INVALID");
            assertThat(ticketStatus(c, "", "")).as("empty").isEqualTo("INVALID");
            assertThat(ticketStatus(c, ticket[0].toUpperCase(), ticket[1])).as("upper-case hex").isEqualTo("INVALID");
            assertThat(ticketStatus(c, ticket[0].substring(1), ticket[1])).as("63 characters").isEqualTo("INVALID");
            assertThat(ticketStatus(c, "not hex at all", "x")).as("malformed is INVALID, never an error").isEqualTo("INVALID");
        }
        BootstrapDb.ticketEndsIn(Duration.ofSeconds(30));
        try (Connection c = SampleAdminDb.app()) {
            assertThat(ticketStatus(c, ticket[0], ticket[1])).as("4 minutes 30 seconds").isEqualTo("VALID");
        }
        BootstrapDb.ticketEndsIn(Duration.ofSeconds(-1));
        try (Connection c = SampleAdminDb.app()) {
            assertThat(ticketStatus(c, ticket[0], ticket[1])).as("5 minutes 1 second").isEqualTo("INVALID");
        }
    }

    @Test
    void s13TicketStatusIsClosedOnceAnyAdminExistsEvenWhenInactiveAndBeatsLocked() throws Exception {
        String[] ticket = oneCorrectUnlock();
        SampleAdminDb.insertAdmin(email(), false);
        try (Connection c = SampleAdminDb.app()) {
            assertThat(ticketStatus(c, ticket[0], ticket[1])).isEqualTo("CLOSED");
            assertThat(ticketStatus(c, hex64(), hex64())).as("closed beats invalid").isEqualTo("CLOSED");
        }
    }

    // ---- S14 .. S19: register ------------------------------------------------------------------------------------

    @Test
    void s14RegisterWithAValidTicketCreatesTheAdminNotEnrolledAndAuditsWithoutTheEmail() throws Exception {
        String[] ticket = oneCorrectUnlock();
        String email = email();
        UUID id = UUID.randomUUID();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(register(c, ticket[0], ticket[1], id, email, "First Admin", BCRYPT, "qa-request")).isEqualTo("CREATED");
            c.commit();
        }
        try (Connection c = SampleAdminDb.migrator()) {
            assertThat(SampleAdminDb.scalarLong(c, "select count(*) from platform.platform_admin")).isEqualTo(1);
            assertThat(SampleAdminDb.scalarBoolean(c, "select active and totp_secret_enc is null and totp_key_version is null "
                    + "and totp_enrolled_at is null and email = ? and full_name = 'First Admin' and password_hash = ? "
                    + "from platform.platform_admin where id = ?", email, BCRYPT, id)).isTrue();
        }
        assertThat(BootstrapDb.state().hasTicket()).as("the ticket is spent").isFalse();
        List<String> rows = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains(id.toString()).contains("First Admin").doesNotContain(email).doesNotContain(BCRYPT);
        for (String row : SampleAdminDb.auditRowsSince(snapshot)) {
            assertThat(row).as("no audit column holds the e-mail").doesNotContain(email);
        }
        // The register step of the app no longer calls admin_preauth_create (ADR-018 Revision 5). This block calls admin_login_lookup
        // only: it proves the new admin can be found and is not enrolled, which is what the first sign-in needs. It does NOT issue
        // or check an ENROL token; that the sign-in then issues one is proven by BootstrapRegisterIT (enrolment flow).
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("select totp_enrolled from platform.admin_login_lookup(?)")) {
                s.setString(1, email);
                try (ResultSet rs = s.executeQuery()) {
                    assertThat(rs.next()).as("admin_login_lookup finds the new admin").isTrue();
                    assertThat(rs.getBoolean(1)).as("not enrolled").isFalse();
                }
            }
            c.rollback();
        }
    }

    @Test
    void s15ASecondRegisterWithTheSameTicketIsClosedAndWritesNothing() throws Exception {
        String[] ticket = oneCorrectUnlock();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(register(c, ticket[0], ticket[1], email())).isEqualTo("CREATED");
            assertThat(register(c, ticket[0], ticket[1], email())).isEqualTo("CLOSED");
        }
        assertThat(scalarLong("select count(*) from platform.platform_admin")).isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED")).isEqualTo(1);
    }

    @Test
    void s16RegisterWithASpentExpiredReplacedForeignOrNullTicketIsInvalidAndLeavesTheTicketAlone() throws Exception {
        String[] first = oneCorrectUnlock();
        String[] second = oneCorrectUnlock();
        BootstrapDb.State before = BootstrapDb.state();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(register(c, first[0], first[1], email())).as("replaced").isEqualTo("INVALID");
            assertThat(register(c, hex64(), hex64(), email())).as("foreign").isEqualTo("INVALID");
            assertThat(register(c, second[0], hex64(), email())).as("right ticket, wrong binding").isEqualTo("INVALID");
            assertThat(register(c, null, null, UUID.randomUUID(), email(), "First Admin", BCRYPT, "qa-request")).as("null")
                    .isEqualTo("INVALID");
        }
        assertThat(BootstrapDb.state()).isEqualTo(before);
        BootstrapDb.ticketEndsIn(Duration.ofSeconds(-1));
        try (Connection c = SampleAdminDb.app()) {
            assertThat(register(c, second[0], second[1], email())).as("expired").isEqualTo("INVALID");
        }
        assertThat(scalarLong("select count(*) from platform.platform_admin")).isZero();
    }

    @Test
    void s17RegisterWhileLockedIsLocked() throws Exception {
        String[] ticket = oneCorrectUnlock();
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        try (Connection c = SampleAdminDb.app()) {
            assertThat(register(c, ticket[0], ticket[1], email())).isEqualTo("LOCKED");
        }
        assertThat(scalarLong("select count(*) from platform.platform_admin")).isZero();
    }

    @Test
    void s18RegisterBadInputsRaise22023WithAMessageThatHoldsNoSuppliedValueAndWriteNothing() throws Exception {
        String[] ticket = oneCorrectUnlock();
        String secretEmail = "secret-value-probe@example.test";
        record Bad(String label, UUID id, String email, String name, String hash, String request) {
        }
        List<Bad> cases = List.of(
                new Bad("null id", null, secretEmail, "First Admin", BCRYPT, "r"),
                new Bad("upper-case email", UUID.randomUUID(), "Secret-Value-Probe@Example.Test", "First Admin", BCRYPT, "r"),
                new Bad("no @", UUID.randomUUID(), "secret-value-probe.example.test", "First Admin", BCRYPT, "r"),
                new Bad("255-character email", UUID.randomUUID(), "s".repeat(243) + "@example.test", "First Admin", BCRYPT, "r"),
                new Bad("name with spaces at the ends", UUID.randomUUID(), secretEmail, " Secret Name ", BCRYPT, "r"),
                new Bad("121-character name", UUID.randomUUID(), secretEmail, "N".repeat(121), BCRYPT, "r"),
                new Bad("hash x", UUID.randomUUID(), secretEmail, "First Admin", "x", "r"),
                new Bad("hash of 59 characters", UUID.randomUUID(), secretEmail, "First Admin", BCRYPT.substring(1), "r"),
                new Bad("request id over 100", UUID.randomUUID(), secretEmail, "First Admin", BCRYPT, "q".repeat(101)));
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            for (Bad bad : cases) {
                String state = sqlState(c, () -> register(c, ticket[0], ticket[1], bad.id(), bad.email(), bad.name(), bad.hash(), bad.request()));
                assertThat(state).as(bad.label() + ": " + lastMessage).isEqualTo("22023");
                assertThat(lastMessage).as(bad.label() + ": the message holds no supplied value").doesNotContain("secret-value-probe")
                        .doesNotContain("Secret Name").doesNotContain("$2a$").doesNotContain(ticket[0]).doesNotContain(ticket[1]);
            }
            c.rollback();
        }
        assertThat(scalarLong("select count(*) from platform.platform_admin")).isZero();
        assertThat(BootstrapDb.state().hasTicket()).isTrue();
    }

    @Test
    void s19RegisterWhenAnyAdminExistsIsClosedWithoutTheSampleAdminRefusalRow() throws Exception {
        String[] ticket = oneCorrectUnlock();
        for (boolean active : new boolean[] {true, false}) {
            SampleAdminDb.Snapshot own = SampleAdminDb.snapshot();
            try {
                SampleAdminDb.insertAdmin(email(), active);
                try (Connection c = SampleAdminDb.app()) {
                    assertThat(register(c, ticket[0], ticket[1], email())).as("active=" + active).isEqualTo("CLOSED");
                }
                assertThat(BootstrapDb.countAction(own, "ADMIN_DEV_SAMPLE_REFUSED")).isZero();
                assertThat(scalarLong("select count(*) from platform.platform_admin")).isEqualTo(1);
            } finally {
                SampleAdminDb.cleanup(own);
            }
        }
    }

    // ---- S20 .. S22: races ----------------------------------------------------------------------------------------

    @Test
    void s20TenSessionsRegisteringWithTheSameValidTicketCreateExactlyOneAdmin() throws Exception {
        String[] ticket = oneCorrectUnlock();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    try (Connection c = SampleAdminDb.app()) {
                        c.setAutoCommit(false);
                        go.await();
                        String outcome = register(c, ticket[0], ticket[1], email());
                        c.commit();
                        return outcome;
                    }
                }));
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes.stream().filter("CREATED"::equals).count()).as(outcomes.toString()).isEqualTo(1);
            assertThat(outcomes.stream().filter("CLOSED"::equals).count()).as(outcomes.toString()).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalarLong("select count(*) from platform.platform_admin")).isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED")).isEqualTo(1);
    }

    @Test
    void s21RegisterRacingTheSampleAdminFunctionCreatesExactlyOneAdmin() throws Exception {
        assumeTrue(SampleAdminDb.devFunctionPresent(), "PRECONDITION NOT MET: " + SampleAdminDb.NO_FUNCTION);
        String[] ticket = oneCorrectUnlock();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<String> bootstrap = pool.submit(() -> {
                try (Connection c = SampleAdminDb.app()) {
                    c.setAutoCommit(false);
                    go.await();
                    String outcome = register(c, ticket[0], ticket[1], email());
                    c.commit();
                    return outcome;
                }
            });
            Future<String> sample = pool.submit(() -> {
                try (Connection c = SampleAdminDb.app(); PreparedStatement s = c.prepareStatement(
                        "select platform.admin_dev_create_sample_admin(?, ?, ?, ?::smallint, ?::inet, ?)")) {
                    c.setAutoCommit(false);
                    go.await();
                    s.setObject(1, UUID.randomUUID());
                    s.setString(2, BCRYPT);
                    s.setBytes(3, new byte[48]);
                    s.setInt(4, 1);
                    s.setString(5, "127.0.0.1");
                    s.setString(6, "qa-race");
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        String created = String.valueOf(rs.getBoolean(1));
                        c.commit();
                        return created;
                    }
                }
            });
            go.countDown();
            String bootstrapOutcome = bootstrap.get(60, TimeUnit.SECONDS);
            String sampleOutcome = sample.get(60, TimeUnit.SECONDS);
            assertThat(scalarLong("select count(*) from platform.platform_admin")).as("bootstrap=" + bootstrapOutcome
                    + ", sample=" + sampleOutcome).isEqualTo(1);
            assertThat(bootstrapOutcome.equals("CREATED") ^ sampleOutcome.equals("true")).as("exactly one side wins").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- locked_until travels with the status (Revision 3: ticket_status and register return it) -------------------------

    @Test
    void s13bTicketStatusReturnsLockedUntilOnlyWithLockedAndRegisterReturnsItWithLockedToo() throws Exception {
        String[] ticket = oneCorrectUnlock();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(BootstrapSql.ticketStatus(c, ticket[0], ticket[1])).isEqualTo(new BootstrapSql.Status("VALID", null));
            assertThat(BootstrapSql.ticketStatus(c, hex64(), hex64())).isEqualTo(new BootstrapSql.Status("INVALID", null));
            assertThat(BootstrapSql.ticketStatus(c, null, null)).as("the no-count peek of a blank PIN").isEqualTo(new BootstrapSql.Status("INVALID", null));
        }
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        Timestamp lockedUntil = Timestamp.from(BootstrapDb.state().lockedUntil());
        BootstrapDb.State before = BootstrapDb.state();
        String updated = updatedAt();
        try (Connection c = SampleAdminDb.app()) {
            assertThat(BootstrapSql.ticketStatus(c, null, null)).as("peek while locked").isEqualTo(new BootstrapSql.Status("LOCKED", lockedUntil));
            assertThat(BootstrapSql.ticketStatus(c, ticket[0], ticket[1])).isEqualTo(new BootstrapSql.Status("LOCKED", lockedUntil));
            // S17: register while locked is LOCKED with the same end, and writes nothing (no count, no lease change).
            c.setAutoCommit(false);
            assertThat(BootstrapSql.register(c, ticket[0], ticket[1], UUID.randomUUID(), email(), "First Admin", BCRYPT, "qa-request"))
                    .isEqualTo(new BootstrapSql.Registered("LOCKED", lockedUntil));
            c.commit();
        }
        assertThat(BootstrapDb.state()).isEqualTo(before);
        assertThat(updatedAt()).isEqualTo(updated);
        SampleAdminDb.insertAdmin(email(), true);
        try (Connection c = SampleAdminDb.app()) {
            assertThat(BootstrapSql.ticketStatus(c, null, null)).as("closed beats locked").isEqualTo(new BootstrapSql.Status("CLOSED", null));
        }
    }

    // ---- S22: serialisation by the lease ------------------------------------------------------------------------------

    @Test
    void s22TenSessionsRunningBeginAtOnceGetExactlyOneCountedAndNineBusyAndNoneWaits() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<UUID> ids = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<BootstrapSql.Begin>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                results.add(pool.submit(() -> {
                    go.await();
                    return BootstrapSql.claimAndCommit(id);
                }));
            }
            long started = System.nanoTime();
            go.countDown();
            List<BootstrapSql.Begin> outcomes = new ArrayList<>();
            for (Future<BootstrapSql.Begin> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(outcomes.stream().filter(o -> o.outcome().equals("COUNTED")).count()).as(outcomes.toString()).isEqualTo(1);
            assertThat(outcomes.stream().filter(o -> o.outcome().equals("BUSY")).count()).as(outcomes.toString()).isEqualTo(9);
            // First-run watch item 5: the row lock is released at the commit, so the nine BUSY answers return in milliseconds,
            // they do not queue for the lock timeout (10 s). The bound is generous for a slow laptop.
            assertThat(millis).as("ten parallel begin calls returned within " + millis + " ms").isLessThan(8000);
        } finally {
            pool.shutdownNow();
        }
        BootstrapDb.State state = BootstrapDb.state();
        assertThat(state.failures()).as("only the winner counted").isEqualTo(1);
        assertThat(ids).as("the row holds the winner's try id").contains(state.tryId());
        // Then the winner is judged and the next begin counts again.
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(unlockFailed(c, state.tryId()).failures()).isEqualTo(1);
            c.commit();
        }
        assertThat(BootstrapSql.claimAndCommit(UUID.randomUUID()).outcome()).isEqualTo("COUNTED");
        assertThat(BootstrapDb.state().failures()).isEqualTo(2);
    }

    @Test
    void s22TenSessionsLoopingBeginAndJudgeCountExactlyFiveAndWriteFiveFailedRowsAndOneLockedRowTheRestSeeTheLock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger counted = new AtomicInteger();
        AtomicInteger locked = new AtomicInteger();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Void>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
                    while (System.nanoTime() < give) {
                        UUID id = UUID.randomUUID();
                        BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(id);
                        switch (claim.outcome()) {
                            case "COUNTED":
                                counted.incrementAndGet();
                                try (Connection c = SampleAdminDb.app()) {
                                    c.setAutoCommit(false);
                                    unlockFailed(c, id);
                                    c.commit();
                                }
                                break;
                            case "BUSY":
                                Thread.sleep(20);
                                break;
                            case "LOCKED":
                                locked.incrementAndGet();
                                return null;
                            default:
                                throw new AssertionError("unexpected outcome " + claim.outcome());
                        }
                    }
                    throw new AssertionError("a session never saw the lock");
                }));
            }
            go.countDown();
            for (Future<Void> result : results) {
                result.get(90, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(counted.get()).as("exactly five claims were counted").isEqualTo(5);
        assertThat(locked.get()).as("every session ended on LOCKED").isEqualTo(10);
        assertThat(BootstrapDb.state().failures()).as("the count never passes 5").isEqualTo(5);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).isEqualTo(5);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED")).isZero();
    }

    // ---- S23: bounded waits -------------------------------------------------------------------------------------------

    @Test
    void s23WhileAnotherSessionHoldsTheRowFourFunctionsGive55P03AfterAboutTenSecondsAndTicketStatusAnswersAtOnce() throws Exception {
        String[] ticket = oneCorrectUnlock();
        UUID tryId = UUID.randomUUID();
        BootstrapSql.claimAndCommit(tryId);
        BootstrapDb.State before = BootstrapDb.state();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (Connection holder = SampleAdminDb.migrator()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("select 1 from " + BootstrapDb.TABLE + " where id = 1 for update");
            }
            record Waited(String sqlState, long seconds) {
            }
            List<Future<Waited>> calls = new ArrayList<>();
            List<String> labels = List.of("begin", "unlock_failed", "unlock_succeeded", "register");
            List<SqlAction> actions = List.of(
                    () -> begin(openApp(), UUID.randomUUID()),
                    () -> unlockFailed(openApp(), tryId),
                    () -> unlockSucceeded(openApp(), tryId, hex64(), hex64()),
                    () -> register(openApp(), ticket[0], ticket[1], email()));
            for (SqlAction action : actions) {
                calls.add(pool.submit(() -> {
                    long started = System.nanoTime();
                    String state;
                    try {
                        action.run();
                        state = null;
                    } catch (SQLException ex) {
                        state = ex.getSQLState();
                    } finally {
                        closeOpenApp();
                    }
                    return new Waited(state, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started));
                }));
            }
            long started = System.nanoTime();
            try (Connection c = SampleAdminDb.app()) {
                assertThat(ticketStatus(c, ticket[0], ticket[1])).isEqualTo("VALID");
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("ticket_status takes no row lock").isLessThan(5000);
            for (int i = 0; i < calls.size(); i++) {
                Waited waited = calls.get(i).get(60, TimeUnit.SECONDS);
                assertThat(waited.sqlState()).as(labels.get(i)).isEqualTo("55P03");
                assertThat(waited.seconds()).as(labels.get(i) + " waited for the 10 s lock timeout").isBetween(8L, 25L);
            }
            holder.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(BootstrapDb.state()).as("nothing counted, spent or changed").isEqualTo(before);
        assertThat(scalarLong("select count(*) from platform.platform_admin")).isZero();
    }

    private static final ThreadLocal<Connection> OPEN_APP = new ThreadLocal<>();

    /** A sahaj_app connection owned by the calling thread (opened on first use, closed by {@link #closeOpenApp}). */
    private static Connection openApp() throws SQLException {
        Connection c = OPEN_APP.get();
        if (c == null) {
            c = SampleAdminDb.app();
            c.setAutoCommit(false);
            OPEN_APP.set(c);
        }
        return c;
    }

    private static void closeOpenApp() {
        Connection c = OPEN_APP.get();
        OPEN_APP.remove();
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ex) {
                // nothing to do
            }
        }
    }

    // ---- S31 / R3-I7: only the holder of a live lease may be judged --------------------------------------------------------

    @Test
    void s31AJudgeWithAnotherTryIdNoLeaseOrARunOutLeaseRaisesSB004AndLeavesTheCountAndTheLeaseUntouched() throws Exception {
        // (a) another try id
        UUID holder = UUID.randomUUID();
        BootstrapSql.claimAndCommit(holder);
        BootstrapDb.State before = BootstrapDb.state();
        String updated = updatedAt();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> unlockFailed(c, UUID.randomUUID()))).as("unlock_failed, another try id: " + lastMessage).isEqualTo("SB004");
            assertThat(sqlState(c, () -> unlockSucceeded(c, UUID.randomUUID(), hex64(), hex64()))).as("unlock_succeeded, another try id: " + lastMessage)
                    .isEqualTo("SB004");
            c.rollback();
        }
        assertThat(BootstrapDb.state()).as("count and lease unchanged").isEqualTo(before);
        assertThat(updatedAt()).isEqualTo(updated);

        // (b) no lease at all
        BootstrapDb.resetState();
        BootstrapDb.State clean = BootstrapDb.state();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> unlockFailed(c, holder))).as("no lease: " + lastMessage).isEqualTo("SB004");
            assertThat(sqlState(c, () -> unlockSucceeded(c, holder, hex64(), hex64()))).as("no lease: " + lastMessage).isEqualTo("SB004");
            c.rollback();
        }
        assertThat(BootstrapDb.state()).isEqualTo(clean);

        // (c) the lease ran out (time travel by data)
        UUID late = UUID.randomUUID();
        BootstrapSql.claimAndCommit(late);
        BootstrapDb.expireLease();
        BootstrapDb.State expired = BootstrapDb.state();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> unlockFailed(c, late))).as("run-out lease: " + lastMessage).isEqualTo("SB004");
            assertThat(sqlState(c, () -> unlockSucceeded(c, late, hex64(), hex64()))).as("run-out lease: " + lastMessage).isEqualTo("SB004");
            c.rollback();
        }
        assertThat(BootstrapDb.state()).as("the run-out lease is still there (only begin clears it)").isEqualTo(expired);
        assertThat(BootstrapDb.auditActions(snapshot)).as("a refused judge writes nothing").isEmpty();
    }

    @Test
    void s31AnAdminThatExistsBeatsTheLeaseTheJudgesRaiseSB001() throws Exception {
        UUID tryId = UUID.randomUUID();
        BootstrapSql.claimAndCommit(tryId);
        SampleAdminDb.insertAdmin(email(), false);
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> unlockFailed(c, tryId))).as(lastMessage).isEqualTo("SB001");
            assertThat(sqlState(c, () -> unlockSucceeded(c, tryId, hex64(), hex64()))).as(lastMessage).isEqualTo("SB001");
            c.rollback();
        }
        assertThat(BootstrapDb.state().failures()).as("the claim stays counted").isEqualTo(1);
    }

    // ---- S32 / R3-I13: a counted try that was never judged -----------------------------------------------------------------

    @Test
    void s32AnUnjudgedTryIsRecordedOnceByTheNextBeginAndStaysCounted() throws Exception {
        UUID first = UUID.randomUUID();
        BootstrapSql.claimAndCommit(first);
        BootstrapDb.expireLease();
        UUID second = UUID.randomUUID();

        BootstrapSql.Begin result = BootstrapSql.claimAndCommit(second);

        assertThat(result).as("the lost try stays counted, this is the second").isEqualTo(new BootstrapSql.Begin("COUNTED", 2, null));
        assertThat(BootstrapDb.state().tryId()).isEqualTo(second);
        List<String> rows = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED");
        assertThat(rows).as("exactly one UNJUDGED row").hasSize(1);
        JsonNode row = BootstrapSupport.JSON.readTree(rows.get(0));
        assertThat(row.get("kind").asText()).isEqualTo("AUTH");
        assertThat(row.get("outcome").asText()).isEqualTo("REFUSED");
        assertThat(row.get("refusal_code").asText()).isEqualTo("try-unjudged");
        assertThat(row.get("summary").asText()).isEqualTo("Bootstrap registration: a counted try ended without a result");
        assertThat(row.get("ip").isNull()).as("the lost request is not stored").isTrue();
        assertThat(row.get("request_id").isNull()).isTrue();
        assertThat(row.get("admin_id").isNull()).isTrue();
        assertThat(rows.get(0)).as("no try id in the audit row").doesNotContain(first.toString()).doesNotContain(second.toString());
    }

    @Test
    void s32AndR3I13ALostFifthTryWritesItsUnjudgedRowAtTheFirstBeginAfterTheLeaseEvenWhileTheLockHoldsOnceOnly() throws Exception {
        for (int n = 1; n <= 4; n++) {
            oneWrongTry();
        }
        UUID fifth = UUID.randomUUID();
        BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(fifth);
        assertThat(claim.outcome()).isEqualTo("COUNTED");
        assertThat(claim.failures()).isEqualTo(5);
        BootstrapDb.expireLease(); // the lease ran out; locked_until stays in the future
        BootstrapDb.State before = BootstrapDb.state();
        assertThat(before.lockedUntil()).isNotNull();

        BootstrapSql.Begin first = BootstrapSql.claimAndCommit(UUID.randomUUID());

        assertThat(first.outcome()).as("the lock still holds").isEqualTo("LOCKED");
        assertThat(first.failures()).isEqualTo(5);
        assertThat(first.lockedUntil()).isEqualTo(Timestamp.from(before.lockedUntil()));
        BootstrapDb.State after = BootstrapDb.state();
        assertThat(after.hasLease()).as("the lease was cleared by this begin").isFalse();
        assertThat(after.failures()).isEqualTo(5);
        assertThat(after.lockedUntil()).as("lock unchanged").isEqualTo(before.lockedUntil());
        List<String> unjudged = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED");
        assertThat(unjudged).hasSize(1);
        assertThat(unjudged.get(0)).contains("\"ip\":null").contains("\"request_id\":null");
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_LOCKED")).as("no LOCKED row for a lost 5th try").isZero();
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).isEqualTo(4);

        BootstrapSql.Begin second = BootstrapSql.claimAndCommit(UUID.randomUUID());

        assertThat(second.outcome()).isEqualTo("LOCKED");
        assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED")).as("written once, never again").hasSize(1);
        assertThat(BootstrapDb.state()).isEqualTo(after);
    }

    // ---- S33 / R3-I8: BUSY while a live lease exists -----------------------------------------------------------------------

    @Test
    void s33BeginWhileALiveLeaseExistsIsBusyAndWritesNothing() throws Exception {
        UUID holder = UUID.randomUUID();
        BootstrapSql.claimAndCommit(holder);
        BootstrapDb.State before = BootstrapDb.state();
        String updated = updatedAt();

        for (int i = 0; i < 3; i++) {
            assertThat(BootstrapSql.claimAndCommit(UUID.randomUUID())).isEqualTo(new BootstrapSql.Begin("BUSY", 1, null));
        }

        assertThat(BootstrapDb.state()).as("count, lease and everything else unchanged").isEqualTo(before);
        assertThat(updatedAt()).as("updated_at unchanged").isEqualTo(updated);
        assertThat(BootstrapDb.auditActions(snapshot)).isEmpty();
    }

    @Test
    void r3I8WhileTheFifthTrysLockAndLiveLeaseExistBeginIsLockedNotBusyAndWritesNothing() throws Exception {
        for (int n = 1; n <= 4; n++) {
            oneWrongTry();
        }
        BootstrapSql.claimAndCommit(UUID.randomUUID()); // the 5th: lock and live lease, no judge
        BootstrapDb.State before = BootstrapDb.state();
        String updated = updatedAt();

        BootstrapSql.Begin result = BootstrapSql.claimAndCommit(UUID.randomUUID());

        assertThat(result.outcome()).as("the lock check comes before the lease check").isEqualTo("LOCKED");
        assertThat(BootstrapDb.state()).isEqualTo(before);
        assertThat(updatedAt()).isEqualTo(updated);
    }

    // ---- S34: the judge never changes the count ----------------------------------------------------------------------------

    @Test
    void s34TheJudgeReturnsTheCountTheClaimMadeNeverChangesItAndASecondJudgeOfTheSameTryRaisesSB004() throws Exception {
        BootstrapDb.setFailures(2);
        UUID tryId = UUID.randomUUID();
        assertThat(BootstrapSql.claimAndCommit(tryId).failures()).isEqualTo(3);
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(unlockFailed(c, tryId)).isEqualTo(new BootstrapSql.Failed(3, null));
            c.commit();
            assertThat(BootstrapDb.state().failures()).as("identical before and after the judge").isEqualTo(3);
            assertThat(sqlState(c, () -> unlockFailed(c, tryId))).as("the lease is gone: " + lastMessage).isEqualTo("SB004");
            c.rollback();
        }
        assertThat(BootstrapDb.state().failures()).isEqualTo(3);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).as("one row for one judged try").isEqualTo(1);
    }

    // ---- S36, S37 / R3-I1, R3-I3: forced failures at function level -------------------------------------------------------

    @Test
    void s36AJudgeThatFailsAfterTheClaimRollsBackOnlyItselfTheCountAndTheLeaseStayAndFiveTriesReachTheLock() throws Exception {
        BootstrapDb.failAuditInsertsOf("ADMIN_BOOTSTRAP_UNLOCK_FAILED");
        try {
            for (int n = 1; n <= 5; n++) {
                BootstrapDb.expireLease(); // no-op for the first try
                UUID tryId = UUID.randomUUID();
                BootstrapSql.Begin claim = BootstrapSql.claimAndCommit(tryId);
                assertThat(claim.outcome()).as("claim " + n).isEqualTo("COUNTED");
                assertThat(claim.failures()).isEqualTo(n);
                try (Connection c = SampleAdminDb.app()) {
                    c.setAutoCommit(false);
                    assertThat(sqlState(c, () -> unlockFailed(c, tryId))).as("the judge fails: " + lastMessage).isEqualTo("P0001");
                    c.rollback();
                }
                BootstrapDb.State state = BootstrapDb.state();
                assertThat(state.failures()).as("the count of try " + n + " survived the failed judge").isEqualTo(n);
                assertThat(state.tryId()).as("the lease survived too").isEqualTo(tryId);
            }
            assertThat(BootstrapDb.state().lockedUntil()).as("five counted tries lock without any judge committing").isNotNull();
        } finally {
            BootstrapDb.dropForcedFailures();
        }
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED")).as("no judge committed").isZero();
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED")).as("the four earlier lost tries were recorded").isEqualTo(4);
    }

    @Test
    void s37AJudgeOfACorrectTryThatFailsAfterTheClaimLeavesTheCountAndTheLease() throws Exception {
        BootstrapDb.failTicketWrites();
        try {
            UUID tryId = UUID.randomUUID();
            BootstrapSql.claimAndCommit(tryId);
            try (Connection c = SampleAdminDb.app()) {
                c.setAutoCommit(false);
                assertThat(sqlState(c, () -> unlockSucceeded(c, tryId, hex64(), hex64()))).as(lastMessage).isEqualTo("P0001");
                c.rollback();
            }
            BootstrapDb.State state = BootstrapDb.state();
            assertThat(state.failures()).as("the claim's count stays").isEqualTo(1);
            assertThat(state.tryId()).isEqualTo(tryId);
            assertThat(state.hasTicket()).as("no ticket was issued").isFalse();
        } finally {
            BootstrapDb.dropForcedFailures();
        }
    }

    // ---- S38: SB002 is retired ----------------------------------------------------------------------------------------------

    @Test
    void s38NoFunctionBodyMentionsSB002AndNoTestCanMakeAFunctionRaiseIt() throws Exception {
        assertThat(scalarLong("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'platform' and p.proname like 'admin\\_bootstrap\\_%' and p.prosrc like '%SB002%'"))
                .as("SB002 is retired and reserved, never raised").isZero();
        // The wrong-path calls that used to raise it while locked (Revision 2) now raise SB004.
        for (int n = 1; n <= 5; n++) {
            oneWrongTry();
        }
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            assertThat(sqlState(c, () -> unlockFailed(c, UUID.randomUUID()))).isNotEqualTo("SB002");
            c.rollback();
        }
    }

    // ---- S24: tenant bound ----------------------------------------------------------------------------------------

    @Test
    void s24WithAClinicTenantBoundEveryFunctionRaises42501() throws Exception {
        oneCorrectUnlock();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, UUID.randomUUID().toString());
                bind.execute();
            }
            assertThat(sqlState(c, () -> begin(c))).as("begin " + lastMessage).isEqualTo("42501");
            assertThat(sqlState(c, () -> unlockFailed(c, UUID.randomUUID()))).as("unlock_failed " + lastMessage).isEqualTo("42501");
            assertThat(sqlState(c, () -> unlockSucceeded(c, UUID.randomUUID(), hex64(), hex64()))).as("unlock_succeeded " + lastMessage).isEqualTo("42501");
            assertThat(sqlState(c, () -> ticketStatus(c, hex64(), hex64()))).as("ticket_status " + lastMessage).isEqualTo("42501");
            assertThat(sqlState(c, () -> register(c, hex64(), hex64(), email()))).as("register " + lastMessage).isEqualTo("42501");
            c.rollback();
        }
        assertThat(BootstrapDb.state().failures()).isZero();
        assertThat(BootstrapDb.auditActions(snapshot)).isEmpty();
    }

    @Test
    void s24AnEmptyTenantSettingLeftFromAnEarlierTransactionIsNoTenant() throws Exception {
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', '', true)")) {
                bind.execute();
            }
            assertThat(begin(c).outcome()).isEqualTo("COUNTED");
            c.commit();
        }
    }

    // ---- S25: table checks ----------------------------------------------------------------------------------------

    @Test
    void s25TheTableChecksRefuseEveryHalfStateWhenWrittenByTheOwner() throws Exception {
        record Bad(String label, String sql) {
        }
        String t = BootstrapDb.TABLE;
        String h = "'" + "a".repeat(64) + "'";
        List<Bad> cases = List.of(
                new Bad("failures 5 with no lock", "update " + t + " set failures = 5, locked_until = null where id = 1"),
                new Bad("a lock with fewer than 5 failures", "update " + t + " set failures = 3, locked_until = now() + interval '1 hour' where id = 1"),
                new Bad("failures 6", "update " + t + " set failures = 6 where id = 1"),
                new Bad("failures -1", "update " + t + " set failures = -1 where id = 1"),
                new Bad("a ticket while locked", "update " + t + " set failures = 5, locked_until = now() + interval '1 hour', ticket_hash = " + h
                        + ", binding_hash = " + h + ", ticket_expires_at = now() + interval '5 minutes' where id = 1"),
                new Bad("only ticket_hash set", "update " + t + " set ticket_hash = " + h + " where id = 1"),
                new Bad("only binding_hash set", "update " + t + " set binding_hash = " + h + " where id = 1"),
                new Bad("only the expiry set", "update " + t + " set ticket_expires_at = now() where id = 1"),
                new Bad("a second row (id 2)", "insert into " + t + " (id) values (2)"),
                new Bad("upper-case hex", "update " + t + " set ticket_hash = '" + "A".repeat(64) + "', binding_hash = " + h
                        + ", ticket_expires_at = now() where id = 1"),
                new Bad("short hash", "update " + t + " set ticket_hash = 'abc', binding_hash = " + h + ", ticket_expires_at = now() where id = 1"),
                new Bad("try_id set with try_expires_at null", "update " + t + " set try_id = gen_random_uuid(), try_expires_at = null where id = 1"),
                new Bad("try_expires_at set with try_id null", "update " + t + " set try_id = null, try_expires_at = now() where id = 1"));
        try (Connection c = SampleAdminDb.migrator()) {
            c.setAutoCommit(false);
            for (Bad bad : cases) {
                assertThat(sqlState(c, () -> {
                    try (Statement s = c.createStatement()) {
                        s.execute(bad.sql());
                    }
                })).as(bad.label() + ": " + lastMessage).isEqualTo("23514");
            }
            c.rollback();
        }
        BootstrapDb.State untouched = BootstrapDb.state();
        assertThat(untouched.failures()).isZero();
    }

    @Test
    void s25bALeaseMayCoexistWithALockBecauseTheFifthClaimSetsBothAtOnce() throws Exception {
        try (Connection c = SampleAdminDb.migrator(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("update " + BootstrapDb.TABLE + " set failures = 5, locked_until = clock_timestamp() + interval '60 minutes', "
                    + "try_id = gen_random_uuid(), try_expires_at = clock_timestamp() + interval '30 seconds' where id = 1");
            s.executeUpdate("update " + BootstrapDb.TABLE + " set try_expires_at = clock_timestamp() - interval '1 second' where id = 1");
            c.rollback();
        }
    }

    // ---- S26: audit is append-only ---------------------------------------------------------------------------------

    @Test
    void s26EvenTheOwnerCannotUpdateAnAuditRow() throws Exception {
        oneWrongTry();
        try (Connection c = SampleAdminDb.migrator()) {
            c.setAutoCommit(false);
            String state = sqlState(c, () -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("update platform.platform_admin_audit set summary = 'changed' where action = 'ADMIN_BOOTSTRAP_UNLOCK_FAILED'");
                }
            });
            assertThat(state).as("the append-only trigger refuses the update: " + lastMessage).isNotNull();
            c.rollback();
        }
        assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_UNLOCK_FAILED").get(0)).doesNotContain("changed");
    }

    // ---- S30: search-path hijack -----------------------------------------------------------------------------------

    @Test
    void s30ATempTableOrFunctionPlacedEarlyOnTheSearchPathChangesNothingBecauseTheFunctionsPinTheirOwnPath() throws Exception {
        String[] ticket = oneCorrectUnlock();
        String email = email();
        try (Connection c = SampleAdminDb.app()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("set local search_path = pg_temp, public");
                s.execute("create temp table platform_admin (id uuid, email text, full_name text, password_hash text) on commit drop");
                s.execute("create temp table platform_admin_audit (id uuid) on commit drop");
                s.execute("create function pg_temp.lower(text) returns text language sql as $$ select 'hijacked'::text $$");
                s.execute("create function pg_temp.clock_timestamp() returns timestamptz language sql as $$ select '2000-01-01'::timestamptz $$");
            }
            assertThat(register(c, ticket[0], ticket[1], email)).isEqualTo("CREATED");
            c.commit();
        }
        assertThat(scalarLong("select count(*) from platform.platform_admin where email = ?", email)).as("the real table got the row").isEqualTo(1);
        assertThat(BootstrapDb.countAction(snapshot, "ADMIN_BOOTSTRAP_ADMIN_CREATED")).isEqualTo(1);
    }
}
