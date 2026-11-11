package com.sahaj.platform.it.devsample;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plain JDBC access to the SAH-42 bootstrap objects in {@code sahaj_test} for the QA tests (ADR-018, docs/database/SAH-42.md).
 * It sits on top of {@link SampleAdminDb} and follows the same rules: connection details only from the environment, no
 * password in a test, never touches any database but {@code sahaj_test}, and a test that needs an object that is not there
 * is skipped with a message that names the missing human prerequisite.
 *
 * <p><b>Preconditions (three levels).</b>
 * <ol>
 *   <li>the human prerequisites of DEC-016: role {@code sahaj_admin_definer} and the six admin tables ({@link SampleAdminDb});
 *   <li>{@code platform.admin_preauth_create(uuid,text,text,text,inet)} from the parked migration {@code V2026_11_15_1010}
 *       (the callback's pre-flight needs it);
 *   <li>the bootstrap table and the five functions, which only the dev-tools callback creates, on the first start of a
 *       {@code -Pdev-tools} build with the switch on and Flyway on.
 * </ol>
 *
 * <p><b>Time.</b> The functions read only {@code clock_timestamp()}, so a test never reads a clock and never sleeps: it moves
 * the stored times with data ("time travel by data", the table in docs/database/SAH-42.md "Tests: how to simulate time"),
 * as {@code sahaj_migrator} on its own connection, committed.
 *
 * <p><b>State row.</b> {@link #resetState()} puts the single state row into "clean slate" (count 0, no lock, no ticket). It
 * never deletes the row and writes no audit row; it is the same update the design note and the clear-lock runbook describe.
 */
public final class BootstrapDb {

    public static final String NO_PREAUTH = "function platform.admin_preauth_create(uuid,text,text,text,inet) does not exist: "
            + "human prerequisite, activate the parked admin migrations V2026_11_15_1000 and V2026_11_15_1010 (DEC-016)";
    public static final String NO_OBJECTS = "the bootstrap table and the five platform.admin_bootstrap_* functions do not exist in "
            + "sahaj_test: they are created by the dev-tools callback on the first start of a -Pdev-tools build with "
            + "SAHAJ_ADMIN_BOOTSTRAP_ENABLED=true and Flyway on, which needs the human prerequisites above";

    public static final String TABLE = "platform.platform_admin_bootstrap_state";
    public static final String BEGIN = "platform.admin_bootstrap_begin(uuid)";
    public static final String UNLOCK_FAILED = "platform.admin_bootstrap_unlock_failed(uuid,inet,text)";
    public static final String UNLOCK_SUCCEEDED = "platform.admin_bootstrap_unlock_succeeded(uuid,text,text)";
    public static final String TICKET_STATUS = "platform.admin_bootstrap_ticket_status(text,text)";
    public static final String REGISTER = "platform.admin_bootstrap_register(text,text,uuid,text,text,text,inet,text)";
    /** The Revision 2 shapes of the three functions whose argument lists changed. */
    public static final List<String> OLD_SHAPES = List.of("platform.admin_bootstrap_begin()",
            "platform.admin_bootstrap_unlock_failed(inet,text)", "platform.admin_bootstrap_unlock_succeeded(text,text)");
    public static final List<String> FUNCTIONS =List.of(BEGIN, UNLOCK_FAILED, UNLOCK_SUCCEEDED, TICKET_STATUS, REGISTER);

    private BootstrapDb() {
    }

    // ---- preconditions ---------------------------------------------------------------------------------------

    public static boolean preauthFunctionPresent() {
        if (!SampleAdminDb.tablesPresent()) {
            return false;
        }
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarBoolean(c, "select to_regprocedure('platform.admin_preauth_create(uuid,text,text,text,inet)') is not null");
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the function state", ex);
        }
    }

    public static boolean tablePresent() {
        try (Connection c = SampleAdminDb.migrator()) {
            return SampleAdminDb.scalarBoolean(c, "select to_regclass(?) is not null", TABLE);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the table state", ex);
        }
    }

    public static boolean objectsPresent() {
        try (Connection c = SampleAdminDb.migrator()) {
            if (!SampleAdminDb.scalarBoolean(c, "select to_regclass(?) is not null", TABLE)) {
                return false;
            }
            for (String function : FUNCTIONS) {
                if (!SampleAdminDb.scalarBoolean(c, "select to_regprocedure(?) is not null", function)) {
                    return false;
                }
            }
            // Revision 3: the lease columns. A table that only has the Revision 2 shape is "not present" until the callback has
            // run once more (the first database class starts the application with Flyway on).
            return SampleAdminDb.scalarLong(c, "select count(*) from pg_attribute where attrelid = ?::regclass "
                    + "and attname in ('try_id', 'try_expires_at') and not attisdropped", TABLE) == 2;
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the object state", ex);
        }
    }

    /** Any function of the Revision 2 shapes (ADR-018 Revision 3 retired them; the callback drops them). */
    public static boolean oldShapePresent() {
        try (Connection c = SampleAdminDb.migrator()) {
            for (String function : OLD_SHAPES) {
                if (SampleAdminDb.scalarBoolean(c, "select to_regprocedure(?) is not null", function)) {
                    return true;
                }
            }
            return false;
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the object state", ex);
        }
    }

    /** Levels 1 and 2. */
    public static void assumePrerequisites() {
        SampleAdminDb.assumePrerequisites();
        assumeTrue(preauthFunctionPresent(), "PRECONDITION NOT MET: " + NO_PREAUTH);
    }

    /** Levels 1, 2 and 3. */
    public static void assumeObjects() {
        assumePrerequisites();
        assumeTrue(objectsPresent(), "PRECONDITION NOT MET: " + NO_OBJECTS);
    }

    // ---- the state row ---------------------------------------------------------------------------------------

    /** What is stored in the single state row. {@code null} fields are SQL null. */
    public record State(int failures, Instant lockedUntil, String ticketHash, String bindingHash, Instant ticketExpiresAt,
                        UUID tryId, Instant tryExpiresAt) {
        public boolean hasTicket() {
            return ticketHash != null;
        }

        /** Revision 3: a try was counted and is (or was) being judged. The lease may have run out. */
        public boolean hasLease() {
            return tryId != null;
        }
    }

    /** The state row, or {@code null} when it does not exist yet (it is created by the first {@code begin}). */
    public static State state() {
        try (Connection c = SampleAdminDb.migrator();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select failures, locked_until, ticket_hash, binding_hash, ticket_expires_at, "
                     + "try_id, try_expires_at from " + TABLE + " where id = 1")) {
            if (!rs.next()) {
                return null;
            }
            return new State(rs.getInt(1), instant(rs.getTimestamp(2)), rs.getString(3), rs.getString(4),
                    instant(rs.getTimestamp(5)), (UUID) rs.getObject(6), instant(rs.getTimestamp(7)));
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the state row", ex);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void update(String sql, Object... params) {
        try (Connection c = SampleAdminDb.migrator()) {
            requireTestDatabase(c);
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    s.setObject(i + 1, params[i]);
                }
                s.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not change the state row", ex);
        }
    }

    private static void requireTestDatabase(Connection c) throws SQLException {
        if (!"sahaj_test".equals(SampleAdminDb.scalarText(c, "select current_database()"))) {
            throw new IllegalStateException("SAH-42 tests may only change the database sahaj_test");
        }
    }

    /**
     * Clean slate: count 0, no lock, no ticket, no lease (Revision 3). Creates the row if it is missing. No audit row. A forced
     * failure installed by a test ({@link #failAuditInsertsOf}, {@link #failTicketWrites}) is removed first, so a crashed test
     * can never leave a trigger behind.
     */
    public static void resetState() {
        dropForcedFailures();
        update("insert into " + TABLE + " (id) values (1) on conflict (id) do update set failures = 0, locked_until = null, "
                + "ticket_hash = null, binding_hash = null, ticket_expires_at = null, try_id = null, try_expires_at = null, "
                + "updated_at = clock_timestamp()");
    }

    // ---- the lease (ADR-018 Revision 3, D4): time travel by data, within the table's check constraints -------------------

    /** Some try holds a live lease for 30 seconds (as after a claim whose judge has not run). Needs no lock state change. */
    public static void leaseLiveFor(UUID tryId, Duration remaining) {
        update("update " + TABLE + " set try_id = ?, try_expires_at = clock_timestamp() + (? * interval '1 second') where id = 1",
                tryId, remaining.toSeconds());
    }

    /** The lease ran out one second ago ({@code try_id} stays, as after a lost request). No effect when there is no lease. */
    public static void expireLease() {
        update("update " + TABLE + " set try_expires_at = clock_timestamp() - interval '1 second' where id = 1 and try_id is not null");
    }

    /** The lease, if any, is gone, both columns together (the table check requires that). */
    public static void clearLease() {
        update("update " + TABLE + " set try_id = null, try_expires_at = null where id = 1");
    }

    // ---- forced failures (ADR-018 R3-I1, R3-I3): temporary triggers, always dropped ----------------------------------

    private static final String AUDIT_TRIGGER = "qa_sah42_fail_audit";
    private static final String TICKET_TRIGGER = "qa_sah42_fail_ticket";

    /**
     * R3-I1: a {@code BEFORE INSERT} trigger on the audit table that raises for rows of this action, so the judge
     * {@code unlock_failed} fails after the claim. The action text is a constant of the test, never a value.
     */
    public static void failAuditInsertsOf(String action) {
        if (!action.matches("[A-Z_]{1,60}")) {
            throw new IllegalArgumentException("action");
        }
        try (Connection c = SampleAdminDb.migrator()) {
            requireTestDatabase(c);
            try (Statement s = c.createStatement()) {
                s.execute("create or replace function platform.qa_sah42_fail_audit() returns trigger language plpgsql as $f$ "
                        + "begin if new.action = '" + action + "' then raise exception 'qa forced failure' using errcode = 'P0001'; end if; "
                        + "return new; end $f$");
                // The trigger runs as the user of the statement (the SECURITY DEFINER owner): make sure it may execute it even if the
                // schema revokes EXECUTE from PUBLIC by default.
                s.execute("grant execute on function platform.qa_sah42_fail_audit() to sahaj_admin_definer, sahaj_app");
                s.execute("drop trigger if exists " + AUDIT_TRIGGER + " on platform.platform_admin_audit");
                s.execute("create trigger " + AUDIT_TRIGGER + " before insert on platform.platform_admin_audit "
                        + "for each row execute function platform.qa_sah42_fail_audit()");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not install the forced audit failure", ex);
        }
    }

    /**
     * R3-I3: a {@code BEFORE UPDATE} trigger on the state table that raises when the new row holds a ticket, so
     * {@code unlock_succeeded} fails after the claim (the claim itself, and the 5th try's claim that nulls the ticket, are not hit).
     */
    public static void failTicketWrites() {
        try (Connection c = SampleAdminDb.migrator()) {
            requireTestDatabase(c);
            try (Statement s = c.createStatement()) {
                s.execute("create or replace function platform.qa_sah42_fail_ticket() returns trigger language plpgsql as $f$ "
                        + "begin raise exception 'qa forced failure' using errcode = 'P0001'; end $f$");
                s.execute("grant execute on function platform.qa_sah42_fail_ticket() to sahaj_admin_definer, sahaj_app");
                s.execute("drop trigger if exists " + TICKET_TRIGGER + " on " + TABLE);
                s.execute("create trigger " + TICKET_TRIGGER + " before update on " + TABLE
                        + " for each row when (new.ticket_hash is not null) execute function platform.qa_sah42_fail_ticket()");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not install the forced ticket failure", ex);
        }
    }

    /** Removes both forced failures. Safe to call when none exists and when the tables do not exist. Always called in a finally. */
    public static void dropForcedFailures() {
        try (Connection c = SampleAdminDb.migrator()) {
            requireTestDatabase(c);
            try (Statement s = c.createStatement()) {
                if (SampleAdminDb.scalarBoolean(c, "select to_regclass('platform.platform_admin_audit') is not null")) {
                    s.execute("drop trigger if exists " + AUDIT_TRIGGER + " on platform.platform_admin_audit");
                }
                if (SampleAdminDb.scalarBoolean(c, "select to_regclass(?) is not null", TABLE)) {
                    s.execute("drop trigger if exists " + TICKET_TRIGGER + " on " + TABLE);
                }
                s.execute("drop function if exists platform.qa_sah42_fail_audit()");
                s.execute("drop function if exists platform.qa_sah42_fail_ticket()");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not remove the forced failures", ex);
        }
    }

    // ---- skip or fail (code review findings 2 and 3) -----------------------------------------------------------------

    /** True when the build is told that the database tests are mandatory: {@code -Dsahaj.bootstrap.requireDb=true}. */
    public static boolean requireDb() {
        return Boolean.getBoolean("sahaj.bootstrap.requireDb")
                || "true".equalsIgnoreCase(System.getenv("SAHAJ_BOOTSTRAP_REQUIRE_DB"));
    }

    public static final String ADMIN_STRAY = "an admin row exists in sahaj_test, so every bootstrap database test would be meaningless "
            + "(registration is closed once any admin exists). This is a FAILURE, not a skip: a stray row can hide a real regression. "
            + "Cause: another test or a failed clean-up left an admin. Fix: admin and audit rows are append-only, so remove the stray "
            + "admin as sahaj_migrator (see SampleAdminDb.cleanup) or rebuild sahaj_test (node scripts/validate.mjs db). "
            + "docs/database/runbooks/dev-tools-sample-admin-cleanup.sql removes the dev-tools objects and grants but deletes no admin row";

    /** Finding 3: a stray admin fails the test with a message that says what to do; it never turns the class into a skip. */
    public static void failIfAnAdminExists() {
        long admins = SampleAdminDb.adminCount();
        if (admins != 0) {
            throw new AssertionError("SAH-42 precondition broken, " + admins + " admin row(s): " + ADMIN_STRAY);
        }
    }

    /** Finding 2: after the application start attempt the objects must exist; a callback that did not create them is a failure. */
    public static void requireObjectsAfterStart() {
        if (!objectsPresent()) {
            throw new AssertionError("The dev-tools callback did not create the bootstrap objects although the application started "
                    + "with the switch on and Flyway on and every human prerequisite present. " + NO_OBJECTS
                    + ". Check that the location classpath:db/bootstrap-dev-tools was added (BootstrapFlywayCustomizer) and that "
                    + "the callback ran (Flyway log). This is a failure, never a skip.");
        }
    }

    /** The count is {@code n} (0 to 4), no lock. The open ticket is kept. */
    public static void setFailures(int n) {
        update("update " + TABLE + " set failures = ?, locked_until = null where id = 1", n);
    }

    /** As after the 5th wrong PIN, but with {@code remaining} left of the lock. Clears the ticket (a lock cancels it). */
    public static void lockFor(Duration remaining) {
        update("update " + TABLE + " set failures = 5, locked_until = clock_timestamp() + (? * interval '1 second'), "
                + "ticket_hash = null, binding_hash = null, ticket_expires_at = null where id = 1", remaining.toSeconds());
    }

    /** The lock ended {@code ago} ago. {@code failures} stays 5; the next {@code begin} resets both. */
    public static void lockEndedAgo(Duration ago) {
        update("update " + TABLE + " set locked_until = clock_timestamp() - (? * interval '1 second') where id = 1",
                ago.toSeconds());
    }

    /** The open ticket ends in {@code remaining} (negative: it ended that long ago). */
    public static void ticketEndsIn(Duration remaining) {
        update("update " + TABLE + " set ticket_expires_at = clock_timestamp() + (? * interval '1 second') where id = 1",
                remaining.toSeconds());
    }

    /** Database time now. */
    public static Instant dbNow() {
        try (Connection c = SampleAdminDb.migrator();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select clock_timestamp()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the database clock", ex);
        }
    }

    // ---- audit and admin rows --------------------------------------------------------------------------------

    /** The {@code action} of every audit row created since the snapshot, oldest first. */
    public static List<String> auditActions(SampleAdminDb.Snapshot snapshot) {
        List<String> actions = new ArrayList<>();
        try (Connection c = SampleAdminDb.migrator();
             PreparedStatement s = c.prepareStatement("select action from platform.platform_admin_audit "
                     + "where occurred_at >= ? order by occurred_at, id")) {
            s.setTimestamp(1, snapshot.at());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    actions.add(rs.getString(1));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the audit actions", ex);
        }
        return actions;
    }

    public static long countAction(SampleAdminDb.Snapshot snapshot, String action) {
        return auditActions(snapshot).stream().filter(action::equals).count();
    }

    /** The audit rows of one action since the snapshot, every column as JSON text. */
    public static List<String> auditRows(SampleAdminDb.Snapshot snapshot, String action) {
        return SampleAdminDb.auditRowsSince(snapshot).stream()
                .filter(row -> row.contains("\"action\":\"" + action + "\"")).toList();
    }
}
