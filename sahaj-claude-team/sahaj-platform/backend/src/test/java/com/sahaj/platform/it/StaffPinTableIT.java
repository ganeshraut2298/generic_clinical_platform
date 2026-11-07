package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SAH-31 {@code platform.staff_pin} on the real {@code sahaj_test} database (docs/database/SAH-31-pin.md "QA test list",
 * items 1 to 26): the statements the application sends, the guard trigger's lockout state machine and its refusals, the
 * checks as the second line, the grants, row level security, the forged and duplicate keys, delete and truncate, the
 * catalog, the query plans, and two connections for the row-lock cases. Rows are made by the application role with a bound
 * clinic, or by the table owner in a transaction that is rolled back; a state the application cannot reach (a lock already
 * over, a window older than 24 hours, lock count 2) is written by the owner with the guard trigger switched off and on again
 * before the statement under test. Flyway from scratch (item "Flyway") is not repeated here: a clean of the shared test
 * database would destroy the other tests' data; the migration chain ran on this database when the context started.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinTableIT extends PinTestSupport {

    private static final String HASH = "$2a$12$" + "a".repeat(53);
    private static final String HASH2 = "$2a$12$" + "b".repeat(53);
    private static final String GUARD = "trg_staff_pin_guard_update";

    private static final String INSERT = "insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, "
            + "created_by) values (?::uuid, ?, 1, now(), ?::uuid)";
    private static final String WHERE = " where membership_id = ?::uuid";
    private static final String BASE = "update platform.staff_pin set ";
    private static final String TAIL = ", updated_by = ?::uuid, version = version + 1" + WHERE;

    private static final String WRONG = BASE + "failed_count = failed_count + 1" + TAIL;
    private static final String RIGHT = BASE + "failed_count = 0" + TAIL;
    private static final String LOCK1 = BASE + "failed_count = 0, lock_count = 1, locked_until = clock_timestamp() + "
            + "interval '15 minutes', lock_window_started_at = clock_timestamp()" + TAIL;
    private static final String LOCK2 = LOCK1.replace("lock_count = 1", "lock_count = 2");
    private static final String SWITCH_OFF = BASE + "pin_hash = null, set_at = null, not_set_reason = 'TOO_MANY_LOCKS', "
            + "failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null" + TAIL;
    private static final String RESET = BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', "
            + "failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null, "
            + "reset_by = ?::uuid, reset_at = clock_timestamp()" + TAIL;
    private static final String NEW_PIN = BASE + "pin_hash = ?, pepper_version = 1, failed_count = 0, locked_until = null, "
            + "lock_count = 0, lock_window_started_at = null, set_at = clock_timestamp(), not_set_reason = null" + TAIL;

    @FunctionalInterface
    private interface Body {
        void run(Connection c, Clinic clinic, Pn p) throws Exception;
    }

    // ---------------- harness ----------------

    private SqlErr err(Connection c, String sql, Object... params) throws SQLException {
        Savepoint savepoint = c.setSavepoint();
        try {
            DbAccess.execute(c, sql, params);
        } catch (SQLException e) {
            c.rollback(savepoint);
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        c.rollback(savepoint);
        throw new AssertionError("the statement was accepted: " + sql);
    }

    private static String col(Connection c, UUID membership, String expression) throws SQLException {
        return DbAccess.text(c, "select (" + expression + ")::text from platform.staff_pin" + WHERE, membership.toString());
    }

    private static void guard(Connection c, boolean on) throws SQLException {
        DbAccess.execute(c, "alter table platform.staff_pin " + (on ? "enable" : "disable") + " trigger " + GUARD);
    }

    /** The table owner, RLS lifted, one rolled-back transaction, a row inserted with a PIN. */
    private void asOwnerWithRow(Body body) throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        asOwner(false, PIN_TABLE, c -> {
            DbAccess.execute(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", clinic.tenantId().toString(),
                    p.membershipId().toString(), HASH, p.userId().toString());
            try {
                body.run(c, clinic, p);
            } catch (SQLException | RuntimeException | AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /** The application role with the clinic bound, one rolled-back transaction, a row inserted with a PIN. */
    private void asAppWithRow(Body body) throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, INSERT, p.membershipId().toString(), HASH, p.userId().toString());
            try {
                body.run(c, clinic, p);
            } catch (SQLException | RuntimeException | AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /** A statement with the user and membership as its last two parameters. */
    private static Object[] um(Pn p, Object... leading) {
        List<Object> all = new ArrayList<>(List.of(leading));
        all.add(p.userId().toString());
        all.add(p.membershipId().toString());
        return all.toArray();
    }

    private static Object[] umReset(Pn p) {
        return new Object[] {p.userId().toString(), p.userId().toString(), p.membershipId().toString()};
    }

    private static void sqlState(SqlErr e, String state, String... fragments) {
        assertThat(e.state()).as(e.message()).isEqualTo(state);
        for (String f : fragments) {
            assertThat(e.message()).containsIgnoringCase(f);
        }
    }

    private static void refused(SqlErr e, String... fragments) {
        sqlState(e, "23514", fragments);
    }

    /** Four wrong tries as the application sends them. */
    private void fourWrong(Connection c, Pn p) throws SQLException {
        for (int i = 0; i < 4; i++) {
            DbAccess.execute(c, WRONG, um(p));
        }
    }

    // ---------------- 1 to 3 : insert, wrong tries, first lock ----------------

    @Test
    void item1TheFirstInsertTakesTheDefaultsTheSessionClinicAndTheDatabaseClockEvenWhenTheStatementSentAYearAgo()
            throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, created_by) "
                    + "values (?::uuid, ?, 1, now() - interval '1 year', ?::uuid)", p.membershipId().toString(), HASH,
                    p.userId().toString());
            String m = p.membershipId().toString();
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "lock_count")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "version")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "tenant_id")).isEqualTo(clinic.tenantId().toString());
            assertThat(col(c, p.membershipId(), "abs(extract(epoch from (clock_timestamp() - set_at))) < 1")).isEqualTo("true");
            assertThat(col(c, p.membershipId(), "not_set_reason is null and locked_until is null and reset_at is null "
                    + "and reset_by is null and updated_at is null and lock_window_started_at is null")).isEqualTo("true");
            assertThat(m).isNotEmpty();
            return null;
        });
    }

    @Test
    void item2FourWrongTriesCountOneToFourAndARightPinGoesBackToZeroWithTheLockCountKept() throws Exception {
        asAppWithRow((c, clinic, p) -> {
            for (int n = 1; n <= 4; n++) {
                DbAccess.execute(c, WRONG, um(p));
                assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo(String.valueOf(n));
                assertThat(col(c, p.membershipId(), "version")).isEqualTo(String.valueOf(n));
                assertThat(col(c, p.membershipId(), "updated_at is not null")).isEqualTo("true");
            }
            DbAccess.execute(c, RIGHT, um(p));
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "lock_count")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "version")).isEqualTo("5");
            // a right-PIN write while the count is already 0 is accepted
            DbAccess.execute(c, RIGHT, um(p));
            assertThat(col(c, p.membershipId(), "version")).isEqualTo("6");
        });
    }

    @Test
    void item3TheFirstLockTakesTheTimesFromTheDatabaseClockNotFromTheValueSent() throws Exception {
        asAppWithRow((c, clinic, p) -> {
            fourWrong(c, p);
            DbAccess.execute(c, BASE + "failed_count = 0, lock_count = 1, locked_until = clock_timestamp() + interval '1 day', "
                    + "lock_window_started_at = clock_timestamp() - interval '3 days'" + TAIL, um(p));
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "lock_count")).isEqualTo("1");
            assertThat(col(c, p.membershipId(), "locked_until > clock_timestamp() + interval '14 minutes' "
                    + "and locked_until < clock_timestamp() + interval '16 minutes'")).isEqualTo("true");
            assertThat(col(c, p.membershipId(), "abs(extract(epoch from (clock_timestamp() - lock_window_started_at))) < 2"))
                    .isEqualTo("true");
        });
    }

    // ---------------- 4 to 7 : locked, second, third, window ----------------

    @Test
    void item4WhileLockedAWrongTryARightPinAndAnotherLockAreRefusedAndAfterTheLockEndsAWrongTryIsAcceptedAgain()
            throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            fourWrong(c, p);
            DbAccess.execute(c, LOCK1, um(p));
            refused(err(c, WRONG, um(p)), "staff pin is locked");
            refused(err(c, RIGHT, um(p)), "staff pin is locked");
            refused(err(c, LOCK2, um(p)), "staff pin");
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("0");
            assertThat(col(c, p.membershipId(), "version")).isEqualTo("5");
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set locked_until = clock_timestamp() - interval '1 minute'" + WHERE,
                    p.membershipId().toString());
            guard(c, true);
            DbAccess.execute(c, WRONG, um(p));
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("1");
        });
    }

    @Test
    void item5TheSecondLockInsideTheWindowCountsToTwoAndKeepsTheWindowStart() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            fourWrong(c, p);
            DbAccess.execute(c, LOCK1, um(p));
            String window = col(c, p.membershipId(), "lock_window_started_at");
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set locked_until = clock_timestamp() - interval '1 minute'" + WHERE,
                    p.membershipId().toString());
            guard(c, true);
            fourWrong(c, p);
            DbAccess.execute(c, LOCK2, um(p));
            assertThat(col(c, p.membershipId(), "lock_count")).isEqualTo("2");
            assertThat(col(c, p.membershipId(), "lock_window_started_at")).isEqualTo(window);
            assertThat(col(c, p.membershipId(), "locked_until > clock_timestamp()")).isEqualTo("true");
        });
    }

    private void reachSecondLock(Connection c, Pn p) throws SQLException {
        fourWrong(c, p);
        DbAccess.execute(c, LOCK1, um(p));
        guard(c, false);
        DbAccess.execute(c, "update platform.staff_pin set locked_until = clock_timestamp() - interval '1 minute'" + WHERE,
                p.membershipId().toString());
        guard(c, true);
        fourWrong(c, p);
        DbAccess.execute(c, LOCK2, um(p));
        guard(c, false);
        DbAccess.execute(c, "update platform.staff_pin set locked_until = clock_timestamp() - interval '1 minute'" + WHERE,
                p.membershipId().toString());
        guard(c, true);
        fourWrong(c, p);
    }

    @Test
    void item6TheThirdLockWithinTheWindowSwitchesThePinOffAndTheOrdinaryLockFromThatStateIsRefused() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            reachSecondLock(c, p);
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("4");
            assertThat(col(c, p.membershipId(), "lock_count")).isEqualTo("2");
            refused(err(c, LOCK2, um(p)), "third lock");
            refused(err(c, BASE + "failed_count = 0, lock_count = 3, locked_until = clock_timestamp() + interval '15 minutes', "
                    + "lock_window_started_at = clock_timestamp()" + TAIL, um(p)));
            assertThat(col(c, p.membershipId(), "pin_hash is not null")).isEqualTo("true");
            DbAccess.execute(c, SWITCH_OFF, um(p));
            assertThat(col(c, p.membershipId(), "pin_hash is null and set_at is null and locked_until is null "
                    + "and lock_window_started_at is null")).isEqualTo("true");
            assertThat(col(c, p.membershipId(), "not_set_reason")).isEqualTo("TOO_MANY_LOCKS");
            assertThat(col(c, p.membershipId(), "failed_count::text || lock_count::text")).isEqualTo("00");
        });
    }

    @Test
    void item7AWindowOlderThan24HoursRestartsTheLockAtOneAndRefusesTheSwitchOffAndACountOfTwo() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            reachSecondLock(c, p);
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set lock_window_started_at = clock_timestamp() - interval '25 hours'"
                    + WHERE, p.membershipId().toString());
            guard(c, true);
            refused(err(c, SWITCH_OFF, um(p)), "within 24 hours");
            refused(err(c, LOCK2, um(p)), "lock count");
            DbAccess.execute(c, LOCK1, um(p));
            assertThat(col(c, p.membershipId(), "lock_count")).isEqualTo("1");
            assertThat(col(c, p.membershipId(), "lock_window_started_at > clock_timestamp() - interval '1 minute'"))
                    .as("a new window").isEqualTo("true");
        });
    }

    // ---------------- 8 and 9 : new PIN, owner reset ----------------

    @Test
    void item8ANewPinAfterASwitchOffOrResetStampsTheTimeClearsTheCountersKeepsTheResetRecordAndWorksWhileLocked()
            throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            DbAccess.execute(c, RESET, umReset(p));
            String resetAt = col(c, p.membershipId(), "reset_at");
            DbAccess.execute(c, NEW_PIN, um(p, HASH2));
            assertThat(col(c, p.membershipId(), "pin_hash")).isEqualTo(HASH2);
            assertThat(col(c, p.membershipId(), "abs(extract(epoch from (clock_timestamp() - set_at))) < 2")).isEqualTo("true");
            assertThat(col(c, p.membershipId(), "not_set_reason is null and failed_count = 0 and lock_count = 0 "
                    + "and locked_until is null and lock_window_started_at is null")).isEqualTo("true");
            assertThat(col(c, p.membershipId(), "reset_at")).isEqualTo(resetAt);
            assertThat(col(c, p.membershipId(), "reset_by")).isEqualTo(p.userId().toString());
            // different reset_at or reset_by with a new PIN is refused
            refused(err(c, NEW_PIN.replace("not_set_reason = null", "not_set_reason = null, reset_by = gen_random_uuid()"),
                    um(p, HASH)), "reset record");
            refused(err(c, NEW_PIN.replace("not_set_reason = null", "not_set_reason = null, reset_at = now() - interval '1 day'"),
                    um(p, HASH)), "reset record");
            // a change while locked succeeds and clears the lock
            fourWrong(c, p);
            DbAccess.execute(c, LOCK1, um(p));
            DbAccess.execute(c, NEW_PIN, um(p, HASH));
            assertThat(col(c, p.membershipId(), "lock_count = 0 and locked_until is null and failed_count = 0")).isEqualTo("true");
        });
    }

    @Test
    void item9OwnerResetFromSetAndFromLockedStampsTheTimeAndRefusesMissingResetterOffRowAndUnclearedCounters()
            throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            // without reset_by
            refused(err(c, BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', failed_count = 0, "
                    + "locked_until = null, lock_count = 0, lock_window_started_at = null, reset_at = clock_timestamp()"
                    + TAIL, um(p)), "reset");
            // with counters left behind
            fourWrong(c, p);
            sqlState(err(c, BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', failed_count = 4, "
                    + "locked_until = null, lock_count = 0, lock_window_started_at = null, reset_by = ?::uuid, "
                    + "reset_at = clock_timestamp()" + TAIL, umReset(p)), "23514");
            // from a locked state, with a reset time sent a year ago
            DbAccess.execute(c, LOCK1, um(p));
            DbAccess.execute(c, RESET.replace("reset_at = clock_timestamp()", "reset_at = now() - interval '1 year'"),
                    umReset(p));
            assertThat(col(c, p.membershipId(), "pin_hash is null and set_at is null and failed_count = 0 and lock_count = 0 "
                    + "and locked_until is null and lock_window_started_at is null")).isEqualTo("true");
            assertThat(col(c, p.membershipId(), "not_set_reason")).isEqualTo("RESET_BY_OWNER");
            assertThat(col(c, p.membershipId(), "reset_by")).isEqualTo(p.userId().toString());
            assertThat(col(c, p.membershipId(), "abs(extract(epoch from (clock_timestamp() - reset_at))) < 2"))
                    .isEqualTo("true");
            // the off row cannot be reset again
            refused(err(c, RESET, umReset(p)), "already off");
        });
    }

    // ---------------- 10 to 15 : refusals and the checks ----------------

    @Test
    void item10TheVersionMustGoUpByExactlyOneAndAChangedMembershipIdIsRefused() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            for (String v : new String[] {"version", "version + 2", "version - 1", "version + 0"}) {
                refused(err(c, BASE + "failed_count = failed_count + 1, version = " + v + WHERE, p.membershipId().toString()),
                        "version");
            }
            refused(err(c, BASE + "failed_count = failed_count + 1, version = version + 1, membership_id = gen_random_uuid()"
                    + WHERE, p.membershipId().toString()), "identity");
            assertThat(col(c, p.membershipId(), "version::text || failed_count::text")).isEqualTo("00");
        });
        asAppWithRow((c, clinic, p) -> sqlState(err(c, BASE + "failed_count = failed_count + 1, version = version + 1, "
                + "membership_id = gen_random_uuid()" + WHERE, p.membershipId().toString()), "42501"));
    }

    @Test
    void item11TheFailedCountGoesUpByOneOrBackToZeroAndALockComesOnlyAfterTheFifthWrongTry() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            refused(err(c, BASE + "failed_count = failed_count + 2" + TAIL, um(p)), "failed count");
            fourWrong(c, p);
            sqlState(err(c, BASE + "failed_count = failed_count + 1" + TAIL, um(p)), "23514", "ck_staff_pin_failed_count");
            // unchanged at four with a lock time
            refused(err(c, BASE + "locked_until = clock_timestamp() + interval '15 minutes'" + TAIL, um(p)));
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set failed_count = 3" + WHERE, p.membershipId().toString());
            guard(c, true);
            refused(err(c, BASE + "failed_count = 2" + TAIL, um(p)), "failed count");
            refused(err(c, LOCK1, um(p)), "fifth wrong try");
            assertThat(col(c, p.membershipId(), "failed_count")).isEqualTo("3");
        });
    }

    @Test
    void item12TheSamePinWithAChangedSetAtReasonResetRecordPepperVersionOrWindowIsRefused() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            String[] changes = {"set_at = now() - interval '1 day'", "not_set_reason = 'RESET_BY_OWNER'",
                    "reset_at = now(), reset_by = gen_random_uuid()", "reset_by = gen_random_uuid()",
                    "pepper_version = 2", "lock_window_started_at = clock_timestamp(), lock_count = 1"};
            for (String change : changes) {
                refused(err(c, BASE + change + TAIL, um(p)));
            }
            assertThat(col(c, p.membershipId(), "version")).isEqualTo("0");
        });
    }

    @Test
    void item13TheSwitchOffKindsAreRefusedWithoutAReasonOutOfPlaceOrWithADifferentPepperVersion() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            String clear = "failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null";
            refused(err(c, BASE + "pin_hash = null, set_at = null, " + clear + TAIL, um(p)), "switch-off");
            // TOO_MANY_LOCKS at lock count 0 (now), at lock count 1, at failed count 3
            refused(err(c, SWITCH_OFF, um(p)), "within 24 hours");
            fourWrong(c, p);
            refused(err(c, SWITCH_OFF, um(p)), "within 24 hours");
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set failed_count = 3, lock_count = 1, "
                    + "lock_window_started_at = clock_timestamp()" + WHERE, p.membershipId().toString());
            guard(c, true);
            refused(err(c, SWITCH_OFF, um(p)), "within 24 hours");
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set failed_count = 4, lock_count = 2" + WHERE,
                    p.membershipId().toString());
            guard(c, true);
            // while locked, and with a changed pepper version
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set locked_until = clock_timestamp() + interval '5 minutes'" + WHERE,
                    p.membershipId().toString());
            guard(c, true);
            refused(err(c, SWITCH_OFF, um(p)));
            guard(c, false);
            DbAccess.execute(c, "update platform.staff_pin set locked_until = null" + WHERE, p.membershipId().toString());
            guard(c, true);
            refused(err(c, SWITCH_OFF.replace("set_at = null", "set_at = null, pepper_version = 2"), um(p)), "pepper");
            sqlState(err(c, SWITCH_OFF.replace("'TOO_MANY_LOCKS'", "'OTHER'"), um(p)), "23514");
            DbAccess.execute(c, SWITCH_OFF, um(p)); // the real one works from this state
        });
    }

    @Test
    void item14InsertsWithoutAPinWithAnUnshapedHashWithPepperVersionZeroOrNamingTheTenantAreRefused() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        db().inTenant(clinic.tenantId(), c -> {
            refused(err(c, INSERT, p.membershipId().toString(), null, p.userId().toString()), "staff pin");
            String shaped = "$2a$12$" + "a".repeat(52);
            for (String bad : new String[] {"1234", HASH.substring(0, 59), HASH + "a", HASH.replace("$2a$", "$2x$"),
                    "$2a$12$" + "a".repeat(52) + "!", shaped}) {
                sqlState(err(c, INSERT, p.membershipId().toString(), bad, p.userId().toString()), "23514",
                        "ck_staff_pin_hash_format");
            }
            sqlState(err(c, "insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, created_by) "
                    + "values (?::uuid, ?, 0, now(), ?::uuid)", p.membershipId().toString(), HASH, p.userId().toString()),
                    "23514", "ck_staff_pin_pepper_version");
            sqlState(err(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", clinic.tenantId().toString(),
                    p.membershipId().toString(), HASH, p.userId().toString()), "42501");
            for (String named : new String[] {"failed_count", "lock_count", "version", "locked_until", "not_set_reason",
                    "created_at"}) {
                String value = named.equals("locked_until") || named.equals("created_at") ? "now()"
                        : named.equals("not_set_reason") ? "'RESET_BY_OWNER'" : "0";
                sqlState(err(c, "insert into platform.staff_pin (membership_id, pin_hash, " + named + ") values (?::uuid, ?, "
                        + value + ")", p.membershipId().toString(), HASH), "42501");
            }
            return null;
        });
    }

    @Test
    void item15TheCheckConstraintsAreTheSecondLineBehindTheGuard() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            guard(c, false);
            String off = "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', ";
            Object[][] cases = {
                    {"lock_count = 1, lock_window_started_at = null", "ck_staff_pin_lock_window"},
                    {"lock_window_started_at = now()", "ck_staff_pin_lock_window"},
                    {"set_at = null", "ck_staff_pin_set_at"},
                    {off.replace("set_at = null, ", "") + "set_at = now()", "ck_staff_pin_set_at"},
                    {"not_set_reason = 'RESET_BY_OWNER'", "ck_staff_pin_reason_only_when_off"},
                    {"reset_at = now()", "ck_staff_pin_reset_pair"},
                    {"reset_by = gen_random_uuid()", "ck_staff_pin_reset_pair"},
                    {"lock_count = 3, lock_window_started_at = now()", "ck_staff_pin_lock_count"},
                    {"failed_count = 5", "ck_staff_pin_failed_count"},
                    {off + "failed_count = 1", "ck_staff_pin_cleared_when_off"},
                    {off + "locked_until = now()", "ck_staff_pin_cleared_when_off"},
                    {off + "lock_count = 1, lock_window_started_at = now()", "ck_staff_pin_cleared_when_off"},
                    {"not_set_reason = 'OTHER'", "ck_staff_pin_not_set_reason"},
                    {"pepper_version = 0", "ck_staff_pin_pepper_version"},
                    {"pin_hash = 'not a hash'", "ck_staff_pin_hash_format"}};
            for (Object[] kase : cases) {
                String set = (String) kase[0];
                sqlState(err(c, BASE + set + WHERE, p.membershipId().toString()), "23514", (String) kase[1]);
            }
            guard(c, true);
        });
    }

    // ---------------- 16 to 19 : tenants, keys ----------------

    @Test
    void item16AnotherClinicsRowIsInvisibleToSelectUpdateAndSelectForUpdateAndNothingShowsWithoutATenant() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Pn inB = newPn(b, "PHARMACIST");
        setPin(inB, goodPin(inB));
        String m = inB.membershipId().toString();
        db().inTenant(a.tenantId(), c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.staff_pin" + WHERE, m)).isZero();
            assertThat(DbAccess.count(c, "select count(*) from platform.staff_pin where tenant_id = ?::uuid",
                    b.tenantId().toString())).isZero();
            try (PreparedStatement ps = c.prepareStatement(WRONG)) {
                ps.setString(1, inB.userId().toString());
                ps.setString(2, m);
                assertThat(ps.executeUpdate()).as("rows of the other clinic updated").isZero();
            }
            try (PreparedStatement ps = c.prepareStatement("select pin_hash from platform.staff_pin" + WHERE + " for update")) {
                ps.setString(1, m);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isFalse();
                }
            }
            return null;
        });
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.staff_pin" + WHERE, m)).isZero();
            SqlErr e = err(c, INSERT, m, HASH, inB.userId().toString());
            assertThat(e.state()).as(e.message()).isIn("42501", "23502");
            return null;
        });
        assertThat(pinRow(b, inB.membershipId()).failed()).isZero();
    }

    @Test
    void item17AForgedMembershipOrClinicInAnInsertOrUpdateIsRefused() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Pn inA = newPn(a, "PHARMACIST");
        Pn inB = newPn(b, "PHARMACIST");
        db().inTenant(a.tenantId(), c -> {
            sqlState(err(c, INSERT, inB.membershipId().toString(), HASH, inA.userId().toString()), "23503",
                    "fk_staff_pin_membership");
            sqlState(err(c, INSERT, UUID.randomUUID().toString(), HASH, inA.userId().toString()), "23503",
                    "fk_staff_pin_membership");
            sqlState(err(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", b.tenantId().toString(),
                    inB.membershipId().toString(), HASH, inA.userId().toString()), "42501");
            DbAccess.execute(c, INSERT, inA.membershipId().toString(), HASH, inA.userId().toString());
            sqlState(err(c, BASE + "tenant_id = ?::uuid" + WHERE, b.tenantId().toString(), inA.membershipId().toString()),
                    "42501");
            return null;
        });
        // as the owner: tenant B with A's membership is the composite foreign key; moving a row is an identity change
        asOwner(false, PIN_TABLE, c -> {
            sqlState(err(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", b.tenantId().toString(),
                    inA.membershipId().toString(), HASH, inA.userId().toString()), "23503", "fk_staff_pin_membership");
            DbAccess.execute(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) values (?::uuid, ?::uuid, ?, 1, now(), ?::uuid)", a.tenantId().toString(),
                    inA.membershipId().toString(), HASH, inA.userId().toString());
            refused(err(c, BASE + "tenant_id = ?::uuid, version = version + 1" + WHERE, b.tenantId().toString(),
                    inA.membershipId().toString()), "identity");
            return null;
        });
    }

    @Test
    void item18ASecondRowForTheSameMembershipIsRefusedAsDuplicate() throws Exception {
        asAppWithRow((c, clinic, p) -> sqlState(err(c, INSERT, p.membershipId().toString(), HASH2, p.userId().toString()),
                "23505"));
    }

    @Test
    void item19AnInactiveMembershipDoesNotMatterToTheDatabaseAndTheRowStaysWhenTheMembershipIsRemoved() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "update platform.membership set status = 'INACTIVE' where id = ?::uuid", p.membershipId().toString());
            DbAccess.execute(c, INSERT, p.membershipId().toString(), HASH, p.userId().toString());
            DbAccess.execute(c, "update platform.membership set status = 'REMOVED' where id = ?::uuid", p.membershipId().toString());
            assertThat(DbAccess.count(c, "select count(*) from platform.staff_pin" + WHERE, p.membershipId().toString())).isOne();
            return null;
        });
    }

    // ---------------- 20 to 22 : grants, delete, catalog ----------------

    @Test
    void item20TheGrantsOfTheApplicationRoleAreExactlyTheListedColumnsAndNothingIsOpenToPublic() throws Exception {
        Set<String> update = Set.of("pin_hash", "pepper_version", "failed_count", "locked_until", "lock_count",
                "lock_window_started_at", "set_at", "not_set_reason", "reset_at", "reset_by", "updated_at", "updated_by", "version",
                "run_has_colleague_try", "lock_by_colleagues");   // SAH-38: 13 columns of SAH-31 plus the two flags (15)
        Set<String> insert = Set.of("membership_id", "pin_hash", "pepper_version", "set_at", "created_by");
        db().asApp(c -> {
            Set<String> updatable = new HashSet<>();
            Set<String> insertable = new HashSet<>();
            List<String> all = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select column_name from information_schema.columns where "
                    + "table_schema = 'platform' and table_name = 'staff_pin'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(rs.getString(1));
                }
            }
            for (String column : all) {
                if (Boolean.parseBoolean(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'platform.staff_pin', ?, "
                        + "'UPDATE')::text", column))) {
                    updatable.add(column);
                }
                if (Boolean.parseBoolean(DbAccess.text(c, "select has_column_privilege('sahaj_app', 'platform.staff_pin', ?, "
                        + "'INSERT')::text", column))) {
                    insertable.add(column);
                }
            }
            assertThat(all).contains("membership_id", "tenant_id", "created_at", "created_by", "pin_hash", "version");
            assertThat(updatable).isEqualTo(update);
            assertThat(insertable).isEqualTo(insert);
            assertThat(updatable).doesNotContain("membership_id", "tenant_id", "created_at", "created_by");
            assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', 'platform.staff_pin', 'SELECT')::text"))
                    .isEqualTo("true");
            for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(DbAccess.text(c, "select has_table_privilege('sahaj_app', 'platform.staff_pin', ?)::text",
                        privilege)).as(privilege).isEqualTo("false");
            }
            assertThat(DbAccess.count(c, "select count(*) from pg_class cl join pg_namespace n on n.oid = cl.relnamespace, "
                    + "aclexplode(cl.relacl) a where n.nspname = 'platform' and cl.relname = 'staff_pin' and a.grantee = 0"))
                    .as("grants to PUBLIC").isZero();
            assertThat(DbAccess.count(c, "select count(*) from pg_attribute att join pg_class cl on cl.oid = att.attrelid join "
                    + "pg_namespace n on n.oid = cl.relnamespace, aclexplode(att.attacl) a where n.nspname = 'platform' and "
                    + "cl.relname = 'staff_pin' and a.grantee = 0")).as("column grants to PUBLIC").isZero();
            return null;
        });
    }

    @Test
    void item21DeleteAndTruncateAreRefusedForTheApplicationRoleByPrivilegeAndForTheOwnerByTheTrigger() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        setPin(p, goodPin(p));
        PinRow before = pinRow(clinic, p.membershipId());
        db().inTenant(clinic.tenantId(), c -> {
            sqlState(err(c, "delete from platform.staff_pin" + WHERE, p.membershipId().toString()), "42501");
            sqlState(err(c, "truncate platform.staff_pin"), "42501");
            return null;
        });
        asOwner(false, PIN_TABLE, c -> {
            sqlState(err(c, "delete from platform.staff_pin" + WHERE, p.membershipId().toString()), "42501", "append-only");
            // SAH-38: the new event table has a foreign key to staff_pin, so PostgreSQL refuses a plain TRUNCATE of staff_pin before any
            // trigger runs (0A000); the cascade form reaches the event table, whose statement trigger refuses it for every role
            sqlState(err(c, "truncate platform.staff_pin"), "0A000", "foreign key");
            sqlState(err(c, "truncate platform.staff_pin cascade"), "42501", "append-only");
            return null;
        });
        assertThat(pinRow(clinic, p.membershipId())).isEqualTo(before);
    }

    @Test
    void item22TheCatalogShowsEnabledAndForcedRlsThreePoliciesFourTriggersGuardsWithAFixedSearchPathAndACleanComment()
            throws Exception {
        db().asApp(c -> {
            try (PreparedStatement ps = c.prepareStatement("select relrowsecurity, relforcerowsecurity from pg_class cl "
                    + "join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'platform' and cl.relname = 'staff_pin'");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBoolean(2)).isTrue();
            }
            List<String> policies = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select p.polcmd::text, "
                    + "coalesce((select string_agg(r.rolname, ',') from pg_roles r where r.oid = any (p.polroles)), '') as roles, "
                    + "lower(coalesce(pg_get_expr(p.polqual, p.polrelid), '')) || ' ' || "
                    + "lower(coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')) as expr from pg_policy p join pg_class cl "
                    + "on cl.oid = p.polrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'platform' and "
                    + "cl.relname = 'staff_pin' order by 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    policies.add(rs.getString(1));
                    assertThat(rs.getString(2)).isEqualTo("sahaj_app");
                    String expr = rs.getString(3);
                    assertThat(expr).contains("current_tenant()").doesNotContain("true").doesNotContain(" or ")
                            .doesNotContain("coalesce").doesNotContain("is null");
                }
            }
            assertThat(policies).containsExactlyInAnyOrder("r", "a", "w");
            List<String> triggers = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select t.tgname || ':' || t.tgenabled::text from pg_trigger t join pg_class cl "
                    + "on cl.oid = t.tgrelid join pg_namespace n on n.oid = cl.relnamespace where n.nspname = 'platform' and "
                    + "cl.relname = 'staff_pin' and not t.tgisinternal order by 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(rs.getString(1));
                }
            }
            assertThat(triggers).containsExactlyInAnyOrder("trg_staff_pin_guard_insert:O", "trg_staff_pin_guard_update:O",
                    "trg_staff_pin_no_delete:O", "trg_staff_pin_no_truncate:O");
            for (String fn : new String[] {"guard_staff_pin_insert", "guard_staff_pin_update"}) {
                try (PreparedStatement ps = c.prepareStatement("select prosecdef, proconfig::text, "
                        + "has_function_privilege('sahaj_app', p.oid, 'EXECUTE'), "
                        + "(p.proacl is not null and not exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0)) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                        + "where n.nspname = 'platform' and p.proname = ?")) {
                    ps.setString(1, fn);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getBoolean(1)).as(fn + " is security invoker").isFalse();
                        assertThat(rs.getString(2)).contains("search_path=pg_catalog");
                        assertThat(rs.getBoolean(3)).as(fn + " executable by sahaj_app").isFalse();
                        assertThat(rs.getBoolean(4)).as(fn + " revoked from public").isTrue();
                    }
                }
            }
            String comment = DbAccess.text(c, "select obj_description('platform.staff_pin'::regclass, 'pg_class')");
            assertThat(comment).isNotBlank().doesNotContain("$2a$").doesNotContainPattern("\\b[0-9]{4}\\b");
            return null;
        });
    }

    // ---------------- 23 : query plans ----------------

    @Test
    void item23TheSingleMembershipReadAndTheTeamListJoinUseTheIndexesWith20000RowsAcrossClinics() throws Exception {
        Clinic clinic = hospital();
        asOwner(false, new String[] {"platform.staff_pin", "platform.membership"}, c -> {
            DbAccess.execute(c, "alter table platform.staff_pin drop constraint fk_staff_pin_membership");
            DbAccess.execute(c, "alter table platform.staff_pin drop constraint fk_staff_pin_tenant");
            DbAccess.execute(c, "insert into platform.staff_pin (tenant_id, membership_id, pin_hash, pepper_version, set_at, "
                    + "created_by) select md5((g / 100)::text)::uuid, gen_random_uuid(), '" + HASH + "', 1, now(), "
                    + "gen_random_uuid() from generate_series(1, 20000) g");
            DbAccess.execute(c, "analyze platform.staff_pin");
            String tenant = DbAccess.text(c, "select md5('5')::text::uuid::text");
            String membership = DbAccess.text(c, "select membership_id::text from platform.staff_pin where tenant_id = ?::uuid "
                    + "limit 1", tenant);
            String single = explain(c, "select pin_hash, failed_count, locked_until from platform.staff_pin where tenant_id = '"
                    + tenant + "'::uuid and membership_id = '" + membership + "'::uuid");
            assertThat(single).contains("Index").doesNotContain("Seq Scan on staff_pin");
            assertThat(single).containsAnyOf("pk_staff_pin", "uq_staff_pin_tenant_membership");
            String team = explain(c, "select m.id, p.failed_count from platform.membership m left join platform.staff_pin p on "
                    + "p.tenant_id = m.tenant_id and p.membership_id = m.id where m.tenant_id = '" + clinic.tenantId() + "'::uuid");
            assertThat(team).doesNotContain("Seq Scan on staff_pin");
            String count = explain(c, "select count(*) from platform.staff_pin where tenant_id = '" + tenant + "'::uuid "
                    + "and pin_hash is not null");
            assertThat(count).doesNotContain("Seq Scan on staff_pin").contains("uq_staff_pin_tenant_membership");
            assertThat(clinic).isNotNull();
            return null;
        });
    }

    private static String explain(Connection c, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("explain " + sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    // ---------------- 24 to 26 : two connections ----------------

    private Connection bound(Clinic clinic) throws SQLException {
        return tenantConnection(clinic);
    }

    @Test
    void item24TwoParallelWrongTriesOnOneRowAreBothCountedBecauseTheSecondWaitsForTheRowLock() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        setPin(p, goodPin(p));
        String select = "select failed_count from platform.staff_pin" + WHERE + " for update";
        try (Connection one = bound(clinic); Connection two = bound(clinic)) {
            DbAccess.count(one, select, p.membershipId().toString());
            DbAccess.execute(one, WRONG, um(p));
            CompletableFuture<Long> second = CompletableFuture.supplyAsync(() -> {
                try {
                    long seen = DbAccess.count(two, select, p.membershipId().toString());
                    DbAccess.execute(two, WRONG, um(p));
                    two.commit();
                    return seen;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(awaitLockWaiters("%from platform.staff_pin%for update%", 1, 20_000)).isTrue();
            assertThat(second.isDone()).isFalse();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).as("the second sees the first one's count after the wait").isEqualTo(1L);
        }
        assertThat(pinRow(clinic, p.membershipId()).failed()).isEqualTo(2);
        assertThat(pinRow(clinic, p.membershipId()).version()).isEqualTo(2);
    }

    @Test
    void item25TwoParallelFirstInsertsForOneMembershipGiveOneRowAndOneDuplicateKey() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        try (Connection one = bound(clinic); Connection two = bound(clinic)) {
            DbAccess.execute(one, INSERT, p.membershipId().toString(), HASH, p.userId().toString());
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try {
                    DbAccess.execute(two, INSERT, p.membershipId().toString(), HASH2, p.userId().toString());
                    two.commit();
                    return "inserted";
                } catch (SQLException e) {
                    return e.getSQLState();
                }
            });
            assertThat(awaitLockWaiters("%insert into platform.staff_pin%", 1, 20_000)).isTrue();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("23505");
        }
        assertThat(count(clinic, "select count(*) from platform.staff_pin" + WHERE, p.membershipId().toString())).isOne();
        assertThat(pinRow(clinic, p.membershipId()).hash()).isEqualTo(HASH);
    }

    @Test
    void item26AResetBeforeAWrongTryIsSeenUnderTheRowLockAndAWrongTryThatSkippedTheReadIsRefusedAsAlreadyOff()
            throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        setPin(p, goodPin(p));
        String select = "select pin_hash from platform.staff_pin" + WHERE + " for update";
        try (Connection one = bound(clinic); Connection two = bound(clinic)) {
            DbAccess.text(one, "select pin_hash from platform.staff_pin" + WHERE + " for update", p.membershipId().toString());
            DbAccess.execute(one, RESET, umReset(p));
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try (PreparedStatement ps = two.prepareStatement(select)) {
                    ps.setString(1, p.membershipId().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1) == null ? "off" : "on";
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(awaitLockWaiters("%from platform.staff_pin%for update%", 1, 20_000)).isTrue();
            one.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).as("the second sees the committed reset").isEqualTo("off");
            // the backstop: a wrong try written without the read is refused by the guard
            SqlErr e = err(two, WRONG, um(p));
            refused(e, "already off");
            two.rollback();
        }
        PinRow row = pinRow(clinic, p.membershipId());
        assertThat(row.hasPin()).isFalse();
        assertThat(row.reason()).isEqualTo("RESET_BY_OWNER");
    }
}
