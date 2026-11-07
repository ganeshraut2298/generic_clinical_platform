package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SAH-38 (HARD-3) the replaced update guard {@code platform.guard_staff_pin_update()} and the two new columns of
 * {@code platform.staff_pin} on the real {@code sahaj_test} database (docs/database/SAH-38.md section 5, items 1 to 16 and
 * the first run of migration V2026_12_01_1000). Statements are the ones the application sends (section 4 of the note).
 * The guard is a trigger and applies to every role, so the state machine is exercised by the table owner in one rolled-back
 * transaction (a state the application cannot reach, such as a lock that is already over, is written with the trigger
 * switched off and on again before the statement under test); one test repeats the key statements as {@code sahaj_app}
 * with a bound clinic. Every refused statement leaves the row exactly as it was. Item 16 (the SAH-31 negatives) is the
 * unchanged {@code StaffPinTableIT}. Test plan: docs/test-plans/SAH-38.md.
 */
@OnTestDatabase
@WithPinPepper
class StaffPinColleagueGuardIT extends PinTestSupport {

    private static final String HASH = "$2a$12$" + "a".repeat(53);
    private static final String HASH2 = "$2a$12$" + "b".repeat(53);
    private static final String GUARD = "trg_staff_pin_guard_update";

    private static final String WHERE = " where membership_id = ?::uuid";
    private static final String BASE = "update platform.staff_pin set ";
    private static final String TAIL = ", updated_by = ?::uuid, version = version + 1" + WHERE;

    /** Own wrong try: the flag is not touched. */
    private static final String OWN_WRONG = BASE + "failed_count = failed_count + 1" + TAIL;
    /** A colleague's wrong try. */
    private static final String COL_WRONG = BASE + "failed_count = failed_count + 1, run_has_colleague_try = true" + TAIL;
    /** A right PIN with tries counted: count back to 0, run flag false. */
    private static final String RIGHT = BASE + "failed_count = 0, run_has_colleague_try = false" + TAIL;
    /** A colleague lock (5th wrong try of a run with a colleague try). Count and window untouched. */
    private static final String COL_LOCK = BASE + "failed_count = 0, run_has_colleague_try = false, lock_by_colleagues = true, "
            + "locked_until = clock_timestamp() + interval '15 minutes'" + TAIL;
    /** First own lock of a window. */
    private static final String OWN_LOCK_FIRST = BASE + "failed_count = 0, run_has_colleague_try = false, "
            + "lock_by_colleagues = false, lock_count = 1, locked_until = clock_timestamp() + interval '15 minutes', "
            + "lock_window_started_at = clock_timestamp()" + TAIL;
    /** Own lock inside an open window (the window start is not sent). */
    private static final String OWN_LOCK_NEXT = BASE + "failed_count = 0, run_has_colleague_try = false, "
            + "lock_by_colleagues = false, lock_count = lock_count + 1, locked_until = clock_timestamp() + interval '15 minutes'"
            + TAIL;
    private static final String SWITCH_OFF = BASE + "pin_hash = null, set_at = null, not_set_reason = 'TOO_MANY_LOCKS', "
            + "failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null, "
            + "run_has_colleague_try = false, lock_by_colleagues = false" + TAIL;
    private static final String RESET = BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', "
            + "failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null, "
            + "run_has_colleague_try = false, lock_by_colleagues = false, reset_by = ?::uuid, reset_at = clock_timestamp()"
            + TAIL;
    private static final String NEW_PIN = BASE + "pin_hash = ?, pepper_version = 1, failed_count = 0, locked_until = null, "
            + "lock_count = 0, lock_window_started_at = null, run_has_colleague_try = false, lock_by_colleagues = false, "
            + "set_at = clock_timestamp(), not_set_reason = null" + TAIL;

    @FunctionalInterface
    private interface Body {
        void run(Connection c, Clinic clinic, Pn p) throws Exception;
    }

    // ---------------- harness ----------------

    private record Refusal(String state, String message) {
    }

    /** Runs a statement that must be refused; the savepoint keeps the transaction usable. */
    private Refusal err(Connection c, String sql, Object... params) throws SQLException {
        Savepoint savepoint = c.setSavepoint();
        try {
            DbAccess.execute(c, sql, params);
        } catch (SQLException e) {
            c.rollback(savepoint);
            return new Refusal(e.getSQLState(), e.getMessage());
        }
        c.rollback(savepoint);
        throw new AssertionError("the statement was accepted: " + sql);
    }

    /** A refused statement: SQLSTATE 23514 and the row exactly as before. */
    private void refused(Connection c, Pn p, String sql, String fragment, Object... params) throws SQLException {
        String before = row(c, p);
        Refusal r = err(c, sql, params);
        assertThat(r.state()).as(r.message()).isEqualTo("23514");
        if (fragment != null) {
            assertThat(r.message()).containsIgnoringCase(fragment);
        }
        assertThat(row(c, p)).as("the row is unchanged after a refusal").isEqualTo(before);
    }

    private static String row(Connection c, Pn p) throws SQLException {
        return DbAccess.text(c, "select to_jsonb(s)::text from platform.staff_pin s" + WHERE, p.membershipId().toString());
    }

    private static String col(Connection c, Pn p, String expression) throws SQLException {
        return DbAccess.text(c, "select (" + expression + ")::text from platform.staff_pin" + WHERE, p.membershipId().toString());
    }

    private static void guard(Connection c, boolean on) throws SQLException {
        DbAccess.execute(c, "alter table platform.staff_pin " + (on ? "enable" : "disable") + " trigger " + GUARD);
    }

    /** Writes a state the application cannot reach: the guard trigger is off for this one statement. */
    private static void force(Connection c, Pn p, String setClause) throws SQLException {
        guard(c, false);
        DbAccess.execute(c, BASE + setClause + WHERE, p.membershipId().toString());
        guard(c, true);
    }

    private static void expire(Connection c, Pn p) throws SQLException {
        force(c, p, "locked_until = clock_timestamp() - interval '1 minute'");
    }

    private static Object[] um(Pn p, Object... leading) {
        List<Object> all = new ArrayList<>(List.of(leading));
        all.add(p.userId().toString());
        all.add(p.membershipId().toString());
        return all.toArray();
    }

    private static Object[] umReset(Pn p) {
        return new Object[] {p.userId().toString(), p.userId().toString(), p.membershipId().toString()};
    }

    private void colleagueWrong(Connection c, Pn p, int n) throws SQLException {
        for (int i = 0; i < n; i++) {
            DbAccess.execute(c, COL_WRONG, um(p));
        }
    }

    private void ownWrong(Connection c, Pn p, int n) throws SQLException {
        for (int i = 0; i < n; i++) {
            DbAccess.execute(c, OWN_WRONG, um(p));
        }
    }

    /** The table owner, one rolled-back transaction, a row with a PIN inserted exactly as the SAH-31 application does. */
    private void asOwnerWithRow(Body body) throws Exception {
        asOwnerWithRow(PIN_TABLE, body);
    }

    /**
     * The same, with the given tables switched to NO FORCE row level security for this rolled-back transaction. With the
     * colleague event table in the list the owner can write and read event rows (it is the table owner and RLS is no longer
     * forced on it), so the proof of a colleague try can be given in an owner session.
     */
    private void asOwnerWithRow(String[] tables, Body body) throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        asOwner(false, tables, c -> {
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

    // ---------------- 1 to 3 : colleague tries and colleague locks ----------------

    @Test
    void item1FourColleagueWrongTriesAreAcceptedTheFlagStaysTrueAndAnOwnWrongTryInTheMiddleKeepsIt() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            assertThat(col(c, p, "run_has_colleague_try::text || lock_by_colleagues::text")).as("defaults").isEqualTo("falsefalse");
            DbAccess.execute(c, COL_WRONG, um(p));
            assertThat(col(c, p, "run_has_colleague_try")).isEqualTo("true");
            DbAccess.execute(c, OWN_WRONG, um(p));
            assertThat(col(c, p, "run_has_colleague_try")).as("an own try does not clear the flag").isEqualTo("true");
            DbAccess.execute(c, COL_WRONG, um(p));
            DbAccess.execute(c, COL_WRONG, um(p));
            assertThat(col(c, p, "failed_count::text || run_has_colleague_try::text")).isEqualTo("4true");
            assertThat(col(c, p, "version")).isEqualTo("4");
        });
    }

    @Test
    void item2AColleagueLockFromFailedCountFourWithTheFlagTrueOrFalseKeepsCountAndWindowAndTakesTheDatabaseClock()
            throws Exception {
        for (boolean flagBefore : new boolean[] {true, false}) {
            for (int locks : new int[] {0, 2}) {
                // S38-1: a colleague lock from a run with no colleague try needs a WRONG event row of this transaction
                String[] tables = flagBefore ? PIN_TABLE : new String[] {"platform.staff_pin", EVENT};
                asOwnerWithRow(tables, (c, clinic, p) -> {
                    if (locks == 2) {
                        force(c, p, "lock_count = 2, lock_window_started_at = clock_timestamp() - interval '2 hours'");
                    }
                    if (flagBefore) {
                        colleagueWrong(c, p, 4);
                    } else {
                        ownWrong(c, p, 4);
                        Pn colleague = newPn(clinic, "NURSE");
                        // the owner session has no default tenant: bind the clinic for this transaction, as the application does
                        DbAccess.execute(c, "select set_config('app.tenant_id', ?, true)", clinic.tenantId().toString());
                        wrongEvent(c, p, colleague);
                    }
                    assertThat(col(c, p, "run_has_colleague_try")).isEqualTo(String.valueOf(flagBefore));
                    String window = col(c, p, "lock_window_started_at");
                    DbAccess.execute(c, COL_LOCK.replace("interval '15 minutes'", "interval '1 day'"), um(p));
                    assertThat(col(c, p, "failed_count::text || run_has_colleague_try::text || lock_by_colleagues::text"))
                            .as("flag " + flagBefore + ", locks " + locks).isEqualTo("0falsetrue");
                    assertThat(col(c, p, "lock_count")).isEqualTo(String.valueOf(locks));
                    assertThat(col(c, p, "lock_window_started_at")).isEqualTo(window);
                    assertThat(col(c, p, "locked_until > clock_timestamp() + interval '14 minutes' "
                            + "and locked_until < clock_timestamp() + interval '16 minutes'"))
                            .as("the guard stamps now + 15 minutes even when the statement sent a day ahead").isEqualTo("true");
                    assertThat(col(c, p, "pin_hash is not null")).isEqualTo("true");
                });
            }
        }
    }

    @Test
    void item3ManyColleagueLocksNeverSwitchThePinOffAndNeverMoveTheLockCount() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "lock_count = 1, lock_window_started_at = clock_timestamp() - interval '3 hours'");
            String window = col(c, p, "lock_window_started_at");
            for (int round = 1; round <= 5; round++) {
                colleagueWrong(c, p, 4);
                DbAccess.execute(c, COL_LOCK, um(p));
                assertThat(col(c, p, "lock_count::text || (pin_hash is not null)::text || lock_by_colleagues::text"))
                        .as("round " + round).isEqualTo("1truetrue");
                assertThat(col(c, p, "lock_window_started_at")).isEqualTo(window);
                expire(c, p);
            }
            assertThat(col(c, p, "not_set_reason is null and pin_hash is not null")).isEqualTo("true");
        });
    }

    // ---------------- 4 to 8 : own locks, switch-off, clean-up, compatibility ----------------

    @Test
    void item4AnOwnLockAfterAColleagueLockClearsTheCauseAndFollowsTheSah31CountPlusOneInsideTheWindowOrRestartAtOne()
            throws Exception {
        // lock count 0 -> 1 (first own lock; the colleague lock before it left the window empty)
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            DbAccess.execute(c, COL_LOCK, um(p));
            expire(c, p);
            ownWrong(c, p, 4);
            assertThat(col(c, p, "lock_by_colleagues")).as("the stale cause stays until the next own lock").isEqualTo("true");
            DbAccess.execute(c, OWN_LOCK_FIRST, um(p));
            assertThat(col(c, p, "lock_count::text || lock_by_colleagues::text || run_has_colleague_try::text"))
                    .isEqualTo("1falsefalse");
        });
        // lock count 1 inside the window -> 2
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "lock_count = 1, lock_window_started_at = clock_timestamp() - interval '2 hours', "
                    + "locked_until = clock_timestamp() - interval '1 minute', lock_by_colleagues = true");
            String window = col(c, p, "lock_window_started_at");
            ownWrong(c, p, 4);
            DbAccess.execute(c, OWN_LOCK_NEXT, um(p));
            assertThat(col(c, p, "lock_count::text || lock_by_colleagues::text")).isEqualTo("2false");
            assertThat(col(c, p, "lock_window_started_at")).isEqualTo(window);
        });
        // window older than 24 hours -> restart at 1
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "lock_count = 2, lock_window_started_at = clock_timestamp() - interval '30 hours', "
                    + "locked_until = clock_timestamp() - interval '1 minute', lock_by_colleagues = true");
            ownWrong(c, p, 4);
            DbAccess.execute(c, OWN_LOCK_FIRST, um(p));
            assertThat(col(c, p, "lock_count::text || lock_by_colleagues::text")).isEqualTo("1false");
            assertThat(col(c, p, "lock_window_started_at > clock_timestamp() - interval '1 minute'")).isEqualTo("true");
        });
    }

    @Test
    void item5TheThirdOwnLockSwitchesThePinOffWithBothFlagsFalseEvenWhenAnOldColleagueLockLeftItsCause() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "failed_count = 4, lock_count = 2, lock_window_started_at = clock_timestamp() - interval '2 hours'");
            DbAccess.execute(c, SWITCH_OFF, um(p));
            assertThat(col(c, p, "pin_hash is null and not_set_reason = 'TOO_MANY_LOCKS' and not run_has_colleague_try "
                    + "and not lock_by_colleagues")).isEqualTo("true");
        });
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "failed_count = 4, lock_count = 2, lock_window_started_at = clock_timestamp() - interval '2 hours', "
                    + "lock_by_colleagues = true, locked_until = clock_timestamp() - interval '1 minute'");
            DbAccess.execute(c, SWITCH_OFF, um(p));
            assertThat(col(c, p, "pin_hash is null and not_set_reason = 'TOO_MANY_LOCKS' and not run_has_colleague_try "
                    + "and not lock_by_colleagues")).isEqualTo("true");
        });
    }

    @Test
    void item6ARightPinAfterAColleagueTryANewPinAnOwnerResetAndTheSwitchOffAreAcceptedFromAColleagueLockState()
            throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 3);
            DbAccess.execute(c, RIGHT, um(p));
            assertThat(col(c, p, "failed_count::text || run_has_colleague_try::text")).isEqualTo("0false");
        });
        String[] kinds = {"new pin", "owner reset", "switch off"};
        for (String kind : kinds) {
            asOwnerWithRow((c, clinic, p) -> {
                if (kind.equals("switch off")) {
                    force(c, p, "failed_count = 4, lock_count = 2, lock_window_started_at = clock_timestamp() - interval '1 hour', "
                            + "lock_by_colleagues = true, locked_until = clock_timestamp() - interval '1 minute'");
                } else {
                    colleagueWrong(c, p, 4);
                    DbAccess.execute(c, COL_LOCK, um(p));
                    assertThat(col(c, p, "lock_by_colleagues")).isEqualTo("true");
                }
                switch (kind) {
                    case "new pin" -> DbAccess.execute(c, NEW_PIN, um(p, HASH2));
                    case "owner reset" -> DbAccess.execute(c, RESET, umReset(p));
                    default -> DbAccess.execute(c, SWITCH_OFF, um(p));
                }
                assertThat(col(c, p, "run_has_colleague_try::text || lock_by_colleagues::text")).as(kind).isEqualTo("falsefalse");
                assertThat(col(c, p, "locked_until is null and lock_count = 0 and failed_count = 0")).as(kind).isEqualTo("true");
            });
        }
    }

    @Test
    void item7AnExpiredColleagueLockCanBeClearedWithTheCauseOrKeptExpiredWithTheCauseOnAWrongTryOrARightPin() throws Exception {
        // cleared with the cause on a wrong try
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            DbAccess.execute(c, COL_LOCK, um(p));
            expire(c, p);
            DbAccess.execute(c, BASE + "failed_count = failed_count + 1, locked_until = null, lock_by_colleagues = false" + TAIL,
                    um(p));
            assertThat(col(c, p, "failed_count::text || (locked_until is null)::text || lock_by_colleagues::text"))
                    .isEqualTo("1truefalse");
        });
        // kept expired with the cause on a wrong try
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            DbAccess.execute(c, COL_LOCK, um(p));
            expire(c, p);
            DbAccess.execute(c, OWN_WRONG, um(p));
            assertThat(col(c, p, "failed_count::text || (locked_until is not null)::text || lock_by_colleagues::text"))
                    .isEqualTo("1truetrue");
        });
        // cleared on a right PIN
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            DbAccess.execute(c, COL_LOCK, um(p));
            expire(c, p);
            DbAccess.execute(c, BASE + "failed_count = 0, locked_until = null, lock_by_colleagues = false" + TAIL, um(p));
            assertThat(col(c, p, "(locked_until is null)::text || lock_by_colleagues::text")).isEqualTo("truefalse");
        });
    }

    @Test
    void item8ARowWrittenTheOldWayHasBothFlagsFalseAndEveryOldStatementStillPassesTheNewGuard() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            assertThat(col(c, p, "run_has_colleague_try::text || lock_by_colleagues::text")).isEqualTo("falsefalse");
            assertThat(DbAccess.text(c, "select string_agg(column_name || ':' || is_nullable || ':' || column_default, ',' "
                    + "order by column_name) from information_schema.columns where table_schema = 'platform' and table_name = "
                    + "'staff_pin' and column_name in ('run_has_colleague_try', 'lock_by_colleagues')"))
                    .isEqualTo("lock_by_colleagues:NO:false,run_has_colleague_try:NO:false");
            // the statements of docs/database/SAH-31-pin.md, unchanged (they never name a flag)
            DbAccess.execute(c, OWN_WRONG, um(p));
            DbAccess.execute(c, BASE + "failed_count = 0" + TAIL, um(p));
            ownWrong(c, p, 4);
            DbAccess.execute(c, BASE + "failed_count = 0, lock_count = 1, locked_until = clock_timestamp() + interval '15 minutes', "
                    + "lock_window_started_at = clock_timestamp()" + TAIL, um(p));
            assertThat(col(c, p, "lock_count::text || run_has_colleague_try::text || lock_by_colleagues::text"))
                    .isEqualTo("1falsefalse");
        });
    }

    // ---------------- 9 to 15 : refusals ----------------

    @Test
    void item9TheRunFlagWithNoWrongTriesCountedIsRefusedByTheGuardAndByTheCheckWhenTheGuardIsOff() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            refused(c, p, BASE + "run_has_colleague_try = true" + TAIL, "colleague-try flag must be false", um(p));
            refused(c, p, BASE + "failed_count = 0, run_has_colleague_try = true" + TAIL, "colleague-try flag", um(p));
            guard(c, false);
            Refusal r = err(c, BASE + "run_has_colleague_try = true" + WHERE, p.membershipId().toString());
            assertThat(r.state()).isEqualTo("23514");
            assertThat(r.message()).contains("ck_staff_pin_run_colleague");
            guard(c, true);
        });
    }

    @Test
    void item10TheRunFlagCannotFallInsideARunOfWrongTries() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 2);
            refused(c, p, BASE + "failed_count = failed_count + 1, run_has_colleague_try = false" + TAIL, "cannot be cleared", um(p));
            refused(c, p, BASE + "failed_count = failed_count, run_has_colleague_try = false" + TAIL, "staff pin", um(p));
            refused(c, p, BASE + "run_has_colleague_try = false" + TAIL, "staff pin", um(p));
            // and the run keeps going with the flag kept
            DbAccess.execute(c, OWN_WRONG, um(p));
            assertThat(col(c, p, "failed_count::text || run_has_colleague_try::text")).isEqualTo("3true");
        });
    }

    @Test
    void item11AColleagueLockThatMovesTheLockCountOrTheWindowOrComesFromTheWrongFailedCountIsRefused() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "lock_count = 1, lock_window_started_at = clock_timestamp() - interval '2 hours'");
            colleagueWrong(c, p, 4);
            refused(c, p, COL_LOCK.replace("lock_by_colleagues = true,", "lock_by_colleagues = true, lock_count = 2,"),
                    "keeps the lock count", um(p));
            refused(c, p, COL_LOCK.replace("lock_by_colleagues = true,",
                    "lock_by_colleagues = true, lock_window_started_at = clock_timestamp(),"), "keeps the lock count", um(p));
            refused(c, p, BASE + "failed_count = 1, run_has_colleague_try = true, lock_by_colleagues = true, "
                    + "locked_until = clock_timestamp() + interval '15 minutes'" + TAIL, "staff pin", um(p));
            // the right statement still works from here
            DbAccess.execute(c, COL_LOCK, um(p));
            assertThat(col(c, p, "lock_count::text || lock_by_colleagues::text")).isEqualTo("1true");
        });
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 3);
            refused(c, p, COL_LOCK, "locks only after the fifth wrong try", um(p));
            ownWrong(c, p, 1);
            DbAccess.execute(c, COL_LOCK, um(p));
        });
    }

    @Test
    void item12AnOwnTypeLockAfterARunWithAColleagueTryIsRefusedAndMustBeMarkedAsCausedByColleagues() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            refused(c, p, OWN_LOCK_FIRST, "must be marked as caused by colleagues", um(p));
            refused(c, p, OWN_LOCK_FIRST.replace("run_has_colleague_try = false", "run_has_colleague_try = true"), "staff pin",
                    um(p));
            DbAccess.execute(c, COL_LOCK, um(p));
        });
    }

    @Test
    void item13ASwitchOffAfterARunWithAColleagueTryAndAnySwitchOffResetOrNewPinWithAFlagTrueAreRefused() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "failed_count = 4, lock_count = 2, lock_window_started_at = clock_timestamp() - interval '2 hours', "
                    + "run_has_colleague_try = true");
            refused(c, p, SWITCH_OFF, "not switched off after a run with a colleague try", um(p));
            // the colleague lock is the only way out of this state
            DbAccess.execute(c, COL_LOCK, um(p));
            assertThat(col(c, p, "(pin_hash is not null)::text || lock_count")).isEqualTo("true2");
        });
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "failed_count = 4, lock_count = 2, lock_window_started_at = clock_timestamp() - interval '2 hours'");
            refused(c, p, SWITCH_OFF.replace("run_has_colleague_try = false", "run_has_colleague_try = true"), "staff pin", um(p));
            refused(c, p, SWITCH_OFF.replace("lock_by_colleagues = false", "lock_by_colleagues = true"), "staff pin", um(p));
        });
        asOwnerWithRow((c, clinic, p) -> {
            refused(c, p, RESET.replace("run_has_colleague_try = false", "run_has_colleague_try = true"), "staff pin", umReset(p));
            refused(c, p, RESET.replace("lock_by_colleagues = false", "lock_by_colleagues = true"), "staff pin", umReset(p));
            refused(c, p, NEW_PIN.replace("run_has_colleague_try = false", "run_has_colleague_try = true"), "staff pin",
                    um(p, HASH2));
            refused(c, p, NEW_PIN.replace("lock_by_colleagues = false", "lock_by_colleagues = true"), "staff pin", um(p, HASH2));
        });
        // a lock cause left by an earlier colleague lock must also be cleared by the new PIN, the reset and the switch-off
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            DbAccess.execute(c, COL_LOCK, um(p));
            refused(c, p, NEW_PIN.replace(", lock_by_colleagues = false", ""), "staff pin", um(p, HASH2));
        });
    }

    @Test
    void item14TheLockCauseCannotBeSetOnAWrongTryOrARightPinAndCannotFallWhileTheLockEndIsKept() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            refused(c, p, BASE + "failed_count = failed_count + 1, lock_by_colleagues = true, "
                    + "locked_until = clock_timestamp() + interval '15 minutes'" + TAIL, "staff pin", um(p));
            ownWrong(c, p, 2);
            refused(c, p, BASE + "failed_count = 0, lock_by_colleagues = true" + TAIL, "lock cause", um(p));
            refused(c, p, BASE + "failed_count = failed_count + 1, lock_by_colleagues = true" + TAIL, "lock cause", um(p));
        });
        asOwnerWithRow((c, clinic, p) -> {
            colleagueWrong(c, p, 4);
            DbAccess.execute(c, COL_LOCK, um(p));
            expire(c, p);
            refused(c, p, BASE + "failed_count = failed_count + 1, lock_by_colleagues = false" + TAIL, "lock cause", um(p));
            refused(c, p, BASE + "failed_count = 0, lock_by_colleagues = false" + TAIL, "lock cause", um(p));
            // an expired lock cleared with the cause kept is refused by the table check, the second line
            Refusal r = err(c, BASE + "failed_count = failed_count + 1, locked_until = null" + TAIL, um(p));
            assertThat(r.state()).isEqualTo("23514");
            assertThat(r.message()).contains("ck_staff_pin_lock_by_colleagues");
        });
    }

    @Test
    void item15TheTableChecksAreTheSecondLineWhenTheGuardIsSwitchedOff() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            guard(c, false);
            Refusal a = err(c, BASE + "lock_by_colleagues = true" + WHERE, p.membershipId().toString());
            assertThat(a.state()).isEqualTo("23514");
            assertThat(a.message()).contains("ck_staff_pin_lock_by_colleagues");
            Refusal b = err(c, BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', reset_by = updated_by, "
                    + "reset_at = clock_timestamp(), lock_by_colleagues = true, locked_until = clock_timestamp()"
                    + WHERE, p.membershipId().toString());
            assertThat(b.state()).isEqualTo("23514");
            Refusal d = err(c, BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', reset_by = created_by, "
                    + "reset_at = clock_timestamp(), run_has_colleague_try = true, failed_count = 0" + WHERE,
                    p.membershipId().toString());
            assertThat(d.state()).isEqualTo("23514");
            guard(c, true);
        });
    }

    @Test
    void item15bAPinThatIsOffCarriesNeitherFlagByTheTableCheckAlone() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            guard(c, false);
            // A lock cause on a PIN that is off cannot pass ck_staff_pin_cleared_when_off (locked_until must be null) together with
            // ck_staff_pin_lock_by_colleagues (locked_until must be set), so the run flag isolates the new check: failed_count 0
            // satisfies the SAH-31 check, and PostgreSQL evaluates check constraints in name order (colleague_flags before run_...).
            Refusal r = err(c, BASE + "pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', "
                    + "reset_by = created_by, reset_at = clock_timestamp(), run_has_colleague_try = true" + WHERE,
                    p.membershipId().toString());
            assertThat(r.state()).isEqualTo("23514");
            assertThat(r.message()).contains("ck_staff_pin_colleague_flags_when_off");
            guard(c, true);
        });
    }

    // ---------------- the application role ----------------

    @Test
    void theApplicationRoleRunsTheColleagueStatementsWithTheFifteenColumnGrantAndCannotWriteAnyOtherColumn() throws Exception {
        Clinic clinic = hospital();
        Pn p = newPn(clinic, "PHARMACIST");
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, created_by) "
                    + "values (?::uuid, ?, 1, now(), ?::uuid)", p.membershipId().toString(), HASH, p.userId().toString());
            colleagueWrong(c, p, 4);
            assertThat(col(c, p, "run_has_colleague_try")).isEqualTo("true");
            DbAccess.execute(c, COL_LOCK, um(p));
            assertThat(col(c, p, "lock_by_colleagues::text || lock_count")).isEqualTo("true0");
            // a column outside the grant: 42501 (the grant is 15 columns; the tenant never changes)
            Savepoint s = c.setSavepoint();
            try {
                DbAccess.execute(c, BASE + "tenant_id = gen_random_uuid()" + WHERE, p.membershipId().toString());
                throw new AssertionError("tenant_id is updatable");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).isEqualTo("42501");
                c.rollback(s);
            }
            return null;
        });
    }

    // ---------------- 30 to 39 : S38-1, a colleague-caused lock needs proof (migration V2026_12_03_1000) ----------------

    private static final String EVENT = "platform.staff_pin_colleague_event";
    private static final String NEEDS = "needs a colleague try";
    private static final String EV_WRONG = "insert into " + EVENT + " (membership_id, by_membership_id, kind, created_by) "
            + "values (?::uuid, ?::uuid, 'WRONG', ?::uuid)";
    private static final String EV_LOCKED = "insert into " + EVENT + " (membership_id, kind, created_by) "
            + "values (?::uuid, 'LOCKED', ?::uuid)";
    private static final String EV_RIGHT = "insert into " + EVENT + " (membership_id, by_membership_id, kind, created_by) "
            + "values (?::uuid, ?::uuid, 'RIGHT_AFTER_WRONG', ?::uuid)";
    private static final String EV_SEEN = "insert into " + EVENT + " (membership_id, kind, seen_through, created_by) "
            + "values (?::uuid, 'NOTICE_SEEN', now() - interval '1 minute', ?::uuid)";

    private static String id(Pn p) {
        return p.membershipId().toString();
    }

    private static String uid(Pn p) {
        return p.userId().toString();
    }

    /** A staff_pin row written by the application role, as the SAH-31 application writes it (the clinic is bound). */
    private static void insertPin(Connection c, Pn p) throws SQLException {
        DbAccess.execute(c, "insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, created_by) "
                + "values (?::uuid, ?, 1, now(), ?::uuid)", id(p), HASH, uid(p));
    }

    /** The colleague's wrong try is a WRONG event of the PIN owner written by the colleague (what the application writes first). */
    private static void wrongEvent(Connection c, Pn owner, Pn colleague) throws SQLException {
        DbAccess.execute(c, EV_WRONG, id(owner), id(colleague), uid(colleague));
    }

    private static long events(Connection c, Pn owner, String kind) throws SQLException {
        return DbAccess.count(c, "select count(*) from " + EVENT + " where membership_id = ?::uuid and kind = ?", id(owner), kind);
    }

    @FunctionalInterface
    private interface Body2 {
        void run(Connection c, Clinic clinic, Pn n1, Pn n2) throws Exception;
    }

    /** The application role, the clinic bound, one rolled-back transaction: N1 and N2 (a colleague), both with a PIN row. */
    private void asAppWithTwoNurses(Body2 body) throws Exception {
        Clinic clinic = hospital();
        Pn n1 = newPn(clinic, "NURSE");
        Pn n2 = newPn(clinic, "NURSE");
        db().inTenant(clinic.tenantId(), c -> {
            insertPin(c, n1);
            insertPin(c, n2);
            try {
                body.run(c, clinic, n1, n2);
            } catch (SQLException | RuntimeException | AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    void item30AColleagueLockWithNoProofIsRefusedWithTheColleagueTryMessageAndTheRowIsUnchanged() throws Exception {
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            ownWrong(c, n1, 4);
            assertThat(col(c, n1, "run_has_colleague_try::text || lock_by_colleagues::text || failed_count")).isEqualTo("falsefalse4");
            assertThat(events(c, n1, "WRONG")).as("no WRONG row").isZero();
            refused(c, n1, COL_LOCK, NEEDS, um(n1));
            // the same statement with the lock sent a day ahead is refused for the same reason
            refused(c, n1, COL_LOCK.replace("interval '15 minutes'", "interval '1 day'"), NEEDS, um(n1));
            assertThat(col(c, n1, "failed_count::text || lock_by_colleagues::text || (locked_until is null)::text"))
                    .isEqualTo("4falsetrue");
        });
    }

    @Test
    void item31AWrongEventOfTheSameTransactionIsTheProofAndTheLockKeepsCountAndWindowAndTakesTheDatabaseClock() throws Exception {
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            ownWrong(c, n1, 4);
            String window = col(c, n1, "lock_window_started_at");
            wrongEvent(c, n1, n2);
            DbAccess.execute(c, COL_LOCK, um(n1));
            assertThat(col(c, n1, "failed_count::text || run_has_colleague_try::text || lock_by_colleagues::text || lock_count"))
                    .isEqualTo("0falsetrue0");
            assertThat(col(c, n1, "lock_window_started_at")).isEqualTo(window);
            assertThat(col(c, n1, "locked_until > clock_timestamp() + interval '14 minutes' "
                    + "and locked_until < clock_timestamp() + interval '16 minutes'")).isEqualTo("true");
            assertThat(col(c, n1, "pin_hash is not null")).isEqualTo("true");
        });
    }

    @Test
    void item32ARunThatAlreadyHasAColleagueTryIsTheProofWithoutAnyEventRow() throws Exception {
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            colleagueWrong(c, n1, 4);
            assertThat(col(c, n1, "run_has_colleague_try")).isEqualTo("true");
            assertThat(events(c, n1, "WRONG")).as("no WRONG row in this transaction").isZero();
            DbAccess.execute(c, COL_LOCK, um(n1));
            assertThat(col(c, n1, "lock_by_colleagues::text || lock_count || failed_count")).isEqualTo("true00");
        });
    }

    @Test
    void item33TheOwnLockAndEveryOtherBranchBehaveAsBeforeWithAndWithoutProof() throws Exception {
        // own lock with no proof: accepted as before (first lock of a window)
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            ownWrong(c, n1, 4);
            DbAccess.execute(c, OWN_LOCK_FIRST, um(n1));
            assertThat(col(c, n1, "lock_count::text || lock_by_colleagues::text || failed_count")).isEqualTo("1false0");
            assertThat(events(c, n1, "WRONG")).isZero();
        });
        // a run with a colleague try and the flag false: still refused, with or without a WRONG row
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            colleagueWrong(c, n1, 4);
            refused(c, n1, OWN_LOCK_FIRST, "must be marked as caused by colleagues", um(n1));
            wrongEvent(c, n1, n2);
            refused(c, n1, OWN_LOCK_FIRST, "must be marked as caused by colleagues", um(n1));
            DbAccess.execute(c, COL_LOCK, um(n1));
        });
        // wrong try, right PIN, new PIN: no event row is needed or read
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            DbAccess.execute(c, COL_WRONG, um(n1));
            DbAccess.execute(c, OWN_WRONG, um(n1));
            DbAccess.execute(c, RIGHT, um(n1));
            assertThat(col(c, n1, "failed_count::text || run_has_colleague_try::text")).isEqualTo("0false");
            DbAccess.execute(c, NEW_PIN, um(n1, HASH2));
            assertThat(col(c, n1, "pin_hash")).isEqualTo(HASH2);
        });
    }

    @Test
    void item33bTheThirdOwnLockStillSwitchesThePinOffAndTheOwnerResetStillWorks() throws Exception {
        asOwnerWithRow((c, clinic, p) -> {
            force(c, p, "failed_count = 4, lock_count = 2, lock_window_started_at = clock_timestamp() - interval '2 hours'");
            DbAccess.execute(c, SWITCH_OFF, um(p));
            assertThat(col(c, p, "pin_hash is null and not_set_reason = 'TOO_MANY_LOCKS'")).isEqualTo("true");
        });
        asOwnerWithRow((c, clinic, p) -> {
            ownWrong(c, p, 2);
            DbAccess.execute(c, RESET, umReset(p));
            assertThat(col(c, p, "pin_hash is null and not_set_reason = 'RESET_BY_OWNER'")).isEqualTo("true");
        });
    }

    @Test
    void item34AWrongRowCommittedBeforeTheTransactionDoesNotCountButOneWrittenInTheTransactionDoes() throws Exception {
        Clinic clinic = hospital();
        Pn n1 = newPn(clinic, "NURSE");
        Pn n2 = newPn(clinic, "NURSE");
        setPin(n1, goodPin(n1));
        setPin(n2, goodPin(n2));
        try (Connection first = tenantConnection(clinic)) {
            ownWrong(first, n1, 4);
            wrongEvent(first, n1, n2);
            first.commit();
        }
        try (Connection second = tenantConnection(clinic)) {
            assertThat(events(second, n1, "WRONG")).as("the committed row is visible").isOne();
            assertThat(col(second, n1, "failed_count::text || run_has_colleague_try::text")).isEqualTo("4false");
            refused(second, n1, COL_LOCK, NEEDS, um(n1));
            // the same transaction, now with its own WRONG row: accepted (then rolled back)
            wrongEvent(second, n1, n2);
            DbAccess.execute(second, COL_LOCK, um(n1));
            assertThat(col(second, n1, "lock_by_colleagues")).isEqualTo("true");
            second.rollback();
        }
        try (Connection third = tenantConnection(clinic)) {
            assertThat(col(third, n1, "failed_count::text || lock_by_colleagues::text")).as("nothing of the rollback stayed")
                    .isEqualTo("4false");
            assertThat(events(third, n1, "WRONG")).isOne();
            third.rollback();
        }
    }

    @Test
    void item35ARowOfAnotherMembershipOrOfAnotherKindDoesNotCount() throws Exception {
        asAppWithTwoNurses((c, clinic, n1, n2) -> {
            ownWrong(c, n1, 4);
            // N1 typed a wrong try on N2's PIN: a WRONG row of N2, not of N1
            wrongEvent(c, n2, n1);
            assertThat(events(c, n2, "WRONG")).isOne();
            refused(c, n1, COL_LOCK, NEEDS, um(n1));
            // N1's own LOCKED, RIGHT_AFTER_WRONG and NOTICE_SEEN rows of this transaction are not a colleague try
            DbAccess.execute(c, EV_LOCKED, id(n1), uid(n2));
            refused(c, n1, COL_LOCK, NEEDS, um(n1));
            DbAccess.execute(c, EV_RIGHT, id(n1), id(n2), uid(n2));
            refused(c, n1, COL_LOCK, NEEDS, um(n1));
            DbAccess.execute(c, EV_SEEN, id(n1), uid(n1));
            refused(c, n1, COL_LOCK, NEEDS, um(n1));
            assertThat(events(c, n1, "WRONG")).isZero();
            // control: a WRONG row of N1 makes the same statement pass
            wrongEvent(c, n1, n2);
            DbAccess.execute(c, COL_LOCK, um(n1));
            assertThat(col(c, n1, "lock_by_colleagues")).isEqualTo("true");
        });
    }

    @Test
    void item36ARowOfAnotherClinicDoesNotCount() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        Pn n1 = newPn(a, "NURSE");
        Pn m1 = newPn(b, "NURSE");
        Pn m2 = newPn(b, "NURSE");
        setPin(m1, goodPin(m1));
        setPin(m2, goodPin(m2));
        db().inTenant(a.tenantId(), c -> {
            insertPin(c, n1);
            ownWrong(c, n1, 4);
            // after A's transaction began, clinic B writes and commits a WRONG row on its own connection
            try (Connection other = tenantConnection(b)) {
                wrongEvent(other, m1, m2);
                other.commit();
            }
            assertThat(DbAccess.count(c, "select count(*) from " + EVENT)).as("clinic A cannot see clinic B rows").isZero();
            refused(c, n1, COL_LOCK, NEEDS, um(n1));
            return null;
        });
        assertThat(count(b, "select count(*) from " + EVENT + " where kind = 'WRONG'")).as("clinic B kept its row").isOne();
    }

    @Test
    void item37TheTableOwnerSeesNoEventRowsSoItCannotSatisfyTheProofByAnEventRow() throws Exception {
        Clinic clinic = hospital();
        Pn n2 = newPn(clinic, "NURSE");
        Pn n3 = newPn(clinic, "NURSE");
        setPin(n2, goodPin(n2));
        setPin(n3, goodPin(n3));
        try (Connection c = tenantConnection(clinic)) {
            wrongEvent(c, n2, n3);
            c.commit();
            assertThat(DbAccess.count(c, "select count(*) from " + EVENT)).as("the application role sees the row").isOne();
            c.rollback();
        }
        asOwnerWithRow((c, ownerClinic, p) -> {
            assertThat(DbAccess.count(c, "select count(*) from " + EVENT)).as("FORCE RLS and no owner policy: no rows").isZero();
            ownWrong(c, p, 4);
            refused(c, p, COL_LOCK, NEEDS, um(p));
        });
        // no tenant bound: the PIN row itself is hidden, so the proof query has nothing to read and no lock can be written
        db().asApp(c -> {
            assertThat(DbAccess.count(c, "select count(*) from " + EVENT)).as("no tenant bound: no event row visible").isZero();
            assertThat(DbAccess.count(c, "select count(*) from platform.staff_pin")).as("and no PIN row").isZero();
            return null;
        });
    }

    @Test
    void item38TheGuardFunctionIsStillSecurityInvokerWithTheSafeSearchPathAndNotExecutableByTheApplicationRole() throws Exception {
        db().asMigrator(c -> {
            String fn = "platform.guard_staff_pin_update()";
            assertThat(DbAccess.text(c, "select prosecdef::text from pg_proc where oid = '" + fn + "'::regprocedure"))
                    .as("SECURITY INVOKER").isEqualTo("false");
            assertThat(DbAccess.text(c, "select proconfig::text from pg_proc where oid = '" + fn + "'::regprocedure"))
                    .isEqualTo("{search_path=pg_catalog}");
            assertThat(DbAccess.text(c, "select has_function_privilege('sahaj_app', '" + fn + "', 'execute')::text"))
                    .isEqualTo("false");
            assertThat(DbAccess.text(c, "select has_function_privilege('public', '" + fn + "', 'execute')::text")).as("no execute for public").isEqualTo("false");
            assertThat(DbAccess.text(c, "select (prosrc like '%needs a colleague try%')::text from pg_proc where oid = '" + fn
                    + "'::regprocedure")).isEqualTo("true");
            assertThat(DbAccess.text(c, "select tgenabled::text from pg_trigger where tgname = 'trg_staff_pin_guard_update' "
                    + "and tgrelid = 'platform.staff_pin'::regclass")).as("trigger enabled").isEqualTo("O");
            return null;
        });
    }

    @Test
    void item38bFlywayAppliedTheProofMigrationSuccessfully() throws Exception {
        db().asMigrator(c -> {
            assertThat(DbAccess.text(c, "select success::text from platform.flyway_schema_history where script = "
                    + "'V2026_12_03_1000__platform_staff_pin_lock_by_colleagues_proof.sql'")).isEqualTo("true");
            return null;
        });
    }
}
