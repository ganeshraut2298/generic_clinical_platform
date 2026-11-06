package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * SAH-33 the escalation state machine in the database (guard trigger {@code ipd.guard_ews_escalation_update}, ADR-007 D2,
 * docs/database/SAH-33.md), proved with plain SQL on the real {@code sahaj_test} database as {@code sahaj_app} and as the
 * table owner: every allowed transition with the exact statement the repository sends (and the times the database
 * stamps), every refusal (SQLSTATE 23514, message contains "escalation", nothing changed), the column grant, the catalog,
 * another clinic's row, and two connections racing for one row. Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class EscalationTriggerIT extends SafetyTestSupport {

    private static final String UPD = "update ipd.ews_escalation set ";

    private record World(Clinic clinic, Adm adm, String id, String user) {
        String where() {
            return " where id = '" + id + "'::uuid";
        }

        /** OPEN to ACK, the statement the Safety board sends. */
        String ack() {
            return UPD + "status = 'ACK', acknowledged_by = '" + user + "'::uuid, version = version + 1" + where();
        }

        /** OPEN or ACK to DONE with a doctor's response; from OPEN the closing doctor is the acknowledging doctor. */
        String doneFromOpen() {
            return UPD + "status = 'DONE', response_action = 'NO_CHANGE', response_note = 'Plan', closed_by = '" + user
                    + "'::uuid, acknowledged_by = '" + user + "'::uuid, version = version + 1" + where();
        }

        String doneFromAck() {
            return UPD + "status = 'DONE', response_action = 'NO_CHANGE', response_note = 'Plan', closed_by = '" + user
                    + "'::uuid, version = version + 1" + where();
        }

        String atDischarge() {
            return UPD + "status = 'DONE', response_action = 'CLOSED_AT_DISCHARGE', closed_by = '" + user
                    + "'::uuid, version = version + 1" + where();
        }

        String dischargeAdmission() {
            return "update ipd.admission set status = 'DISCHARGED' where id = '" + adm.id() + "'::uuid";
        }

        String set(String assignments) {
            return UPD + assignments + where();
        }
    }

    private World world(boolean high) throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        String id = raise(clinic, adm, high ? high7() : medium3());
        return new World(clinic, adm, id, clinic.ownerUserId().toString());
    }

    private static final String READ = "select status || '|' || version || '|' || coalesce(acknowledged_by::text, '-') || '|' "
            + "|| (acknowledged_at is not null) || '|' || (closed_at is not null) || '|' || (reopened_at is not null) "
            + "|| '|' || senior_calls || '|' || coalesce(response_action, '-') || '|' || (response_note is not null) "
            + "|| '|' || coalesce(closed_by::text, '-') from ipd.ews_escalation where id = '%s'::uuid";

    /** Runs the statements in one transaction (rolled back) and returns the row afterwards. */
    private String run(World w, boolean owner, String... statements) throws SQLException {
        String read = String.format(READ, w.id());
        if (owner) {
            return asOwner(false, ESCALATION_TABLES, c -> {
                for (String s : statements) {
                    DbAccess.execute(c, s);
                }
                return DbAccess.text(c, read);
            });
        }
        return db().inTenant(w.clinic().tenantId(), c -> {
            for (String s : statements) {
                DbAccess.execute(c, s);
            }
            return DbAccess.text(c, read);
        });
    }

    /** Runs the setup statements, then a statement that must be refused; returns the error. */
    private SqlErr refused(World w, boolean owner, String failing, String... setups) throws SQLException {
        if (owner) {
            return asOwner(false, ESCALATION_TABLES, c -> attempt(c, failing, setups));
        }
        return db().inTenant(w.clinic().tenantId(), c -> attempt(c, failing, setups));
    }

    private static SqlErr attempt(Connection c, String failing, String... setups) throws SQLException {
        for (String s : setups) {
            DbAccess.execute(c, s);
        }
        Savepoint sp = c.setSavepoint();
        try {
            DbAccess.execute(c, failing);
        } catch (SQLException e) {
            c.rollback(sp);
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        throw new AssertionError("the statement was accepted: " + failing);
    }

    private void assertRefused(World w, boolean owner, String failing, String fragment, String... setups) throws SQLException {
        SqlErr e = refused(w, owner, failing, setups);
        assertThat(e.state()).as(failing).isEqualTo("23514");
        assertThat(e.message()).as(failing).contains("escalation").contains(fragment);
    }

    /**
     * Many refusals of one kind in one transaction (rolled back): the setup statements run once, each statement that must
     * be refused is tried in its own savepoint (23514, a message with "escalation" and the fragment). One owner
     * transaction costs DDL on two tables, so the loops over the owner use this.
     */
    private void assertAllRefused(World w, boolean owner, String fragment, String[] setups, String... failings)
            throws SQLException {
        DbAccess.SqlWork<Void> work = c -> {
            for (String setup : setups) {
                DbAccess.execute(c, setup);
            }
            for (String failing : failings) {
                Savepoint sp = c.setSavepoint();
                try {
                    DbAccess.execute(c, failing);
                } catch (SQLException e) {
                    c.rollback(sp);
                    assertThat(e.getSQLState()).as(failing).isEqualTo("23514");
                    assertThat(e.getMessage()).as(failing).contains("escalation").contains(fragment);
                    continue;
                }
                throw new AssertionError("the statement was accepted: " + failing);
            }
            return null;
        };
        if (owner) {
            asOwner(false, ESCALATION_TABLES, work);
        } else {
            db().inTenant(w.clinic().tenantId(), work);
        }
    }

    private void assertUnchanged(World w, String before) throws SQLException {
        assertThat(values(w.clinic(), String.format(READ, w.id())).get(0)).isEqualTo(before);
    }

    // ---------------- the allowed transitions, with the statements the repository sends ----------------

    @Test
    void openToAcknowledgedIsAcceptedTheDatabaseStampsTheTimeAndTheVersionGoesUpByOne() throws Exception {
        World w = world(false);
        String before = values(w.clinic(), String.format(READ, w.id())).get(0);
        assertThat(before).startsWith("OPEN|0|-|false|false|false|0|-|false|-");
        // status|version|ack_by|ack_at set|closed_at set|reopened_at set|senior|action|note set|closed_by
        assertThat(run(w, false, w.ack())).isEqualTo("ACK|1|" + w.user() + "|true|false|false|0|-|false|-");
        // the time is the database clock of the statement, not a value we sent (the owner tries to send a date)
        String owner = run(w, true, UPD + "status = 'ACK', acknowledged_by = '" + w.user()
                + "'::uuid, acknowledged_at = '2001-01-01 00:00:00+00', version = version + 1" + w.where());
        assertThat(owner).startsWith("ACK|1|");
        String stamped = asOwner(false, ESCALATION_TABLES, c -> {
            DbAccess.execute(c, UPD + "status = 'ACK', acknowledged_by = '" + w.user()
                    + "'::uuid, acknowledged_at = '2001-01-01 00:00:00+00', version = version + 1" + w.where());
            return DbAccess.text(c, "select (acknowledged_at between statement_timestamp() - interval '30 seconds' "
                    + "and clock_timestamp())::text from ipd.ews_escalation where id = '" + w.id() + "'::uuid");
        });
        assertThat(stamped).as("acknowledged_at is the database clock, not the 2001 value sent").isEqualTo("true");
        assertUnchanged(w, before);
    }

    @Test
    void openToDoneByADoctorSetsTheAcknowledgementToTheCloseAndAcknowledgedFromAckKeepsTheOriginalTime() throws Exception {
        World w = world(false);
        // OPEN to DONE: the closing doctor is also the acknowledging doctor, acknowledged_at equals closed_at
        assertThat(run(w, false, w.doneFromOpen()))
                .isEqualTo("DONE|1|" + w.user() + "|true|true|false|0|NO_CHANGE|true|" + w.user());
        String equal = db().<String>inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, w.doneFromOpen());
            return DbAccess.text(c, "select (acknowledged_at = closed_at and closed_at between statement_timestamp() - "
                    + "interval '30 seconds' and clock_timestamp())::text from ipd.ews_escalation where id = '" + w.id()
                    + "'::uuid");
        });
        assertThat(equal).isEqualTo("true");
        // ACK to DONE: acknowledged_at is the one of the acknowledgement, closed_at is later
        String sameAck = db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, w.ack());
            String ackAt = DbAccess.text(c, "select acknowledged_at::text from ipd.ews_escalation where id = '" + w.id() + "'::uuid");
            DbAccess.execute(c, w.doneFromAck());
            return ackAt + "|" + DbAccess.text(c, "select acknowledged_at::text || '|' || (closed_at > acknowledged_at) "
                    + "from ipd.ews_escalation where id = '" + w.id() + "'::uuid");
        });
        String[] parts = sameAck.split("\\|");
        assertThat(parts[1]).as("acknowledged_at not rewritten by the close").isEqualTo(parts[0]);
        assertThat(parts[2]).isEqualTo("true");
        assertThat(run(w, false, w.ack(), w.doneFromAck()))
                .isEqualTo("DONE|2|" + w.user() + "|true|true|false|0|NO_CHANGE|true|" + w.user());
    }

    @Test
    void acknowledgedToOpenOnlyOnAMediumToHighRiseClearsTheAcknowledgementAndStampsReopenedAtWithTheTransactionTime()
            throws Exception {
        World w = world(false);
        String reopen = w.set("status = 'OPEN', acknowledged_by = null, ews_score = 7, ews_risk = 'HIGH', "
                + "target_minutes = 15, version = version + 1") + " and status = 'ACK' and ews_risk = 'MEDIUM'";
        assertThat(run(w, false, w.ack(), reopen)).isEqualTo("OPEN|2|-|false|false|true|0|-|false|-");
        String sql = db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, w.ack());
            DbAccess.execute(c, reopen);
            return DbAccess.text(c, "select (reopened_at = now() and ews_score = 7 and ews_risk = 'HIGH' and target_minutes = 15 "
                    + "and acknowledged_by is null and acknowledged_at is null)::text from ipd.ews_escalation where id = '"
                    + w.id() + "'::uuid");
        });
        assertThat(sql).as("reopened_at is the transaction time; the acknowledgement is cleared").isEqualTo("true");
        // the original raise stays: reopened_at is later than raised_at
        String later = db().<String>inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, w.ack());
            DbAccess.execute(c, reopen);
            return DbAccess.text(c, "select (reopened_at >= raised_at)::text from ipd.ews_escalation where id = '" + w.id() + "'::uuid");
        });
        assertThat(later).isEqualTo("true");
    }

    @Test
    void theScoreUpdateOfSah30IsAcceptedOnOpenAndOnAcknowledgedInsideTheSameRiskAndRefusedOnARiskRiseWhichNeedsTheReopen()
            throws Exception {
        World w = world(false);
        String raise = "update ipd.ews_escalation set ews_score = 6, ews_risk = 'MEDIUM', target_minutes = 60, "
                + "version = version + 1 where tenant_id = '" + w.clinic().tenantId() + "'::uuid and id = '" + w.id()
                + "'::uuid and status <> 'DONE' and ews_score < 6";
        assertThat(run(w, false, raise)).isEqualTo("OPEN|1|-|false|false|false|0|-|false|-");
        assertThat(run(w, false, w.ack(), raise)).isEqualTo("ACK|2|" + w.user() + "|true|false|false|0|-|false|-");
        String toHigh = "update ipd.ews_escalation set ews_score = 7, ews_risk = 'HIGH', target_minutes = 15, version = version + 1 "
                + "where id = '" + w.id() + "'::uuid and status <> 'DONE' and ews_score < 7";
        assertThat(run(w, false, toHigh)).startsWith("OPEN|1|");
        assertRefused(w, false, toHigh, "risk rise needs a re-open", w.ack());
    }

    @Test
    void aSeniorCallIsAcceptedOnlyOnOpenAloneAndLeavesTheVersionUnchanged() throws Exception {
        World w = world(false);
        String call = w.set("senior_calls = senior_calls + 1");
        assertThat(run(w, false, call)).isEqualTo("OPEN|0|-|false|false|false|1|-|false|-");
        assertThat(run(w, false, call, call)).isEqualTo("OPEN|0|-|false|false|false|2|-|false|-");
        // after a score update the call still leaves the version where the update put it
        assertThat(run(w, false, w.set("ews_score = 5, version = version + 1"), call))
                .isEqualTo("OPEN|1|-|false|false|false|1|-|false|-");
        // refusals
        assertRefused(w, false, w.set("senior_calls = senior_calls + 2"), "senior calls go up by one");
        assertRefused(w, false, w.set("senior_calls = senior_calls - 1"), "senior calls go up by one", call);
        assertRefused(w, false, w.set("senior_calls = senior_calls + 1, ews_score = 4, version = version + 1"),
                "senior calls go up by one");
        assertRefused(w, false, w.set("senior_calls = senior_calls + 1, version = version + 1"),
                "version changes only together with a change");
        assertRefused(w, false, call, "senior calls go up by one", w.ack());
        assertRefused(w, false, call, "is closed", w.doneFromOpen());
        assertRefused(w, true, call, "senior calls go up by one", w.ack());
    }

    @Test
    void closedAtDischargeKeepsTheAcknowledgementAsItWasNullFromOpenAndKeptFromAckAndTakesNoNote() throws Exception {
        World w = world(false);
        // from OPEN: the acknowledgement fields stay null, closed_by is the discharging user, no note
        assertThat(run(w, false, w.dischargeAdmission(), w.atDischarge()))
                .isEqualTo("DONE|1|-|false|true|false|0|CLOSED_AT_DISCHARGE|false|" + w.user());
        // from ACK: both the doctor and the time are kept
        String fromAck = run(w, false, w.ack(), w.dischargeAdmission(), w.atDischarge());
        assertThat(fromAck).isEqualTo("DONE|2|" + w.user() + "|true|true|false|0|CLOSED_AT_DISCHARGE|false|" + w.user());
        String same = db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, w.ack());
            String before = DbAccess.text(c, "select acknowledged_at::text from ipd.ews_escalation where id = '" + w.id() + "'::uuid");
            DbAccess.execute(c, w.dischargeAdmission());
            DbAccess.execute(c, w.atDischarge());
            return before + "|" + DbAccess.text(c, "select acknowledged_at::text from ipd.ews_escalation where id = '" + w.id() + "'::uuid");
        });
        assertThat(same.split("\\|")[1]).isEqualTo(same.split("\\|")[0]);
        // the owner does the same (it sees the admission through the lifted row level security)
        assertThat(run(w, true, w.dischargeAdmission(), w.atDischarge())).startsWith("DONE|1|-|false|true");
    }

    // ---------------- refusals ----------------

    @Test
    void aClosedRowRefusesEveryUpdateForTheAppAndForTheOwner() throws Exception {
        World w = world(false);
        String[] updates = {w.set("version = version + 1"), w.set("senior_calls = senior_calls + 1"),
                w.set("ews_score = 9, ews_risk = 'MEDIUM', target_minutes = 60, version = version + 1"),
                w.set("status = 'OPEN', acknowledged_by = null, version = version + 1"),
                w.set("response_note = 'changed', version = version + 1")};
        for (boolean owner : new boolean[] {false, true}) {
            assertAllRefused(w, owner, "is closed", new String[] {w.doneFromOpen()}, updates);
        }
        // the same at discharge
        assertRefused(w, false, w.set("version = version + 1"), "is closed", w.dischargeAdmission(), w.atDischarge());
    }

    @Test
    void theVersionMovesByExactlyOneWithAChangeAndNeverWithoutOneAndNeverDown() throws Exception {
        World w = world(false);
        String bump = "version must go up by exactly one";
        assertRefused(w, false, w.set("ews_score = 5, version = version"), bump);
        assertRefused(w, false, w.set("ews_score = 5, version = version + 2"), bump);
        assertRefused(w, false, w.set("ews_score = 5, version = 3000000000"), bump);
        assertRefused(w, false, w.set("status = 'ACK', acknowledged_by = '" + w.user() + "'::uuid"), bump);
        assertRefused(w, false, w.set("status = 'ACK', acknowledged_by = '" + w.user() + "'::uuid, version = version + 2"), bump);
        assertRefused(w, false, w.set("version = version + 1"), "version changes only together with a change");
        assertRefused(w, false, w.set("version = 3000000000"), "version changes only together with a change");
        assertRefused(w, false, w.set("ews_score = 6, version = 0"), "version cannot go down", w.set("ews_score = 5, version = version + 1"));
        assertRefused(w, true, w.set("ews_score = 5, version = version + 2"), bump);
    }

    @Test
    void anAcknowledgementNeedsAUserOnceAndCannotChangeScoreAndAnAcknowledgedRowCannotBeAcknowledgedByAnother() throws Exception {
        World w = world(false);
        assertRefused(w, false, w.set("status = 'ACK', version = version + 1"), "acknowledgement needs a user, once");
        assertRefused(w, false, w.set("status = 'ACK', acknowledged_by = '" + w.user() + "'::uuid, ews_score = 2, "
                + "version = version + 1"), "score cannot go down");
        assertRefused(w, false, w.set("status = 'ACK', acknowledged_by = '" + w.user() + "'::uuid, ews_score = 5, "
                + "version = version + 1"), "score cannot change while it is acknowledged");
        assertRefused(w, false, w.set("acknowledged_by = gen_random_uuid(), version = version + 1"),
                "acknowledgement cannot change", w.ack());
        assertRefused(w, false, w.set("ews_score = 7, ews_risk = 'HIGH', target_minutes = 15, version = version + 1"),
                "risk rise needs a re-open", w.ack());
        assertRefused(w, false, w.set("acknowledged_by = '" + w.user() + "'::uuid, version = version + 1"),
                "cannot be acknowledged without moving to acknowledged");
        assertRefused(w, true, w.set("acknowledged_by = gen_random_uuid(), version = version + 1"),
                "acknowledgement cannot change", w.ack());
    }

    @Test
    void anAcknowledgedEscalationReopensOnlyOnAMediumToHighRiseWithAHigherScoreAndNoAcknowledgement() throws Exception {
        World medium = world(false);
        String msg = "re-opens only when the risk rises from medium to high";
        // MEDIUM to MEDIUM with a higher score, the acknowledgement cleared
        assertRefused(medium, false, medium.set("status = 'OPEN', acknowledged_by = null, ews_score = 5, "
                + "version = version + 1"), msg, medium.ack());
        // MEDIUM to HIGH but the acknowledgement kept
        assertRefused(medium, false, medium.set("status = 'OPEN', ews_score = 7, ews_risk = 'HIGH', target_minutes = 15, "
                + "version = version + 1"), msg, medium.ack());
        // HIGH to HIGH with a higher score
        World high = world(true);
        assertRefused(high, false, high.set("status = 'OPEN', acknowledged_by = null, ews_score = 9, version = version + 1"),
                msg, high.ack());
        // a plain OPEN to OPEN that clears nothing is not a re-open either
        assertRefused(high, true, high.set("status = 'OPEN', acknowledged_by = null, ews_score = 9, version = version + 1"),
                msg, high.ack());
        // positive control: the allowed statement
        assertThat(run(medium, false, medium.ack(), medium.set("status = 'OPEN', acknowledged_by = null, ews_score = 7, "
                + "ews_risk = 'HIGH', target_minutes = 15, version = version + 1"))).startsWith("OPEN|2|-|false");
    }

    @Test
    void closingNeedsActionClosingUserNoteAndTheRightAdmissionStateAndTheChecksAnswerAfterTheTrigger() throws Exception {
        World w = world(false);
        String u = w.user();
        String base = "status = 'DONE', version = version + 1";
        assertRefused(w, false, w.set(base + ", closed_by = '" + u + "'::uuid, response_note = 'x', acknowledged_by = '" + u
                + "'::uuid"), "needs an action and a closing user");
        assertRefused(w, false, w.set(base + ", response_action = 'NO_CHANGE', response_note = 'x', acknowledged_by = '" + u
                + "'::uuid"), "needs an action and a closing user");
        assertRefused(w, false, w.set(base + ", response_action = 'NO_CHANGE', closed_by = '" + u + "'::uuid, acknowledged_by = '"
                + u + "'::uuid"), "response needs a note");
        assertRefused(w, false, w.set(base + ", response_action = 'NO_CHANGE', response_note = 'x', closed_by = '" + u
                + "'::uuid, acknowledged_by = gen_random_uuid()"), "closed from open is acknowledged by the closing user");
        assertRefused(w, false, w.set(base + ", response_action = 'NO_CHANGE', response_note = 'x', closed_by = '" + u
                + "'::uuid"), "closed from open is acknowledged by the closing user");
        // score, risk or target cannot change in the same statement
        assertRefused(w, false, w.set(base + ", response_action = 'NO_CHANGE', response_note = 'x', closed_by = '" + u
                + "'::uuid, acknowledged_by = '" + u + "'::uuid, ews_score = 5"), "score cannot change when it is closed");
        // the admission decides which kind of close is allowed
        assertRefused(w, false, w.doneFromOpen(), "only for an admitted patient", w.dischargeAdmission());
        assertRefused(w, false, w.atDischarge(), "only for a discharged admission");
        assertRefused(w, false, w.set(base + ", response_action = 'CLOSED_AT_DISCHARGE', closed_by = '" + u
                + "'::uuid, response_note = 'x'"), "has no note and keeps its acknowledgement", w.dischargeAdmission());
        assertRefused(w, false, w.set(base + ", response_action = 'CLOSED_AT_DISCHARGE', closed_by = '" + u
                + "'::uuid, acknowledged_by = '" + u + "'::uuid"), "has no note and keeps its acknowledgement",
                w.dischargeAdmission());
        // the same refusals for the owner (it reads the admission through the lifted row level security)
        assertRefused(w, true, w.doneFromOpen(), "only for an admitted patient", w.dischargeAdmission());
        assertRefused(w, true, w.atDischarge(), "only for a discharged admission");
        // the trigger accepts an unknown code and an empty or long plan: the checks answer next (the second line)
        for (Object[] c : new Object[][] {
                {"response_action = 'BOGUS', response_note = 'x'", "ck_ews_escalation_response_action"},
                {"response_action = 'NO_CHANGE', response_note = ''", "ck_ews_escalation_response_note"},
                {"response_action = 'NO_CHANGE', response_note = '" + "a".repeat(1001) + "'", "ck_ews_escalation_response_note"}}) {
            SqlErr e = refused(w, false, w.set(base + ", " + c[0] + ", closed_by = '" + u + "'::uuid, acknowledged_by = '" + u
                    + "'::uuid"), new String[0]);
            assertThat(e.state()).as((String) c[0]).isEqualTo("23514");
            assertThat(e.message()).as((String) c[0]).contains("\"" + c[1] + "\"");
        }
        // note of exactly 1000 characters and of 1 character: accepted
        assertThat(run(w, false, w.set(base + ", response_action = 'NO_CHANGE', response_note = '" + "a".repeat(1000)
                + "', closed_by = '" + u + "'::uuid, acknowledged_by = '" + u + "'::uuid"))).startsWith("DONE|1|");
    }

    @Test
    void theResponseFieldsAreRecordedOnlyWhenTheStatusBecomesDone() throws Exception {
        World w = world(false);
        String only = "response is recorded only when it is closed";
        assertRefused(w, false, w.set("status = 'ACK', acknowledged_by = '" + w.user() + "'::uuid, response_note = 'x', "
                + "version = version + 1"), only);
        assertRefused(w, false, w.set("closed_by = '" + w.user() + "'::uuid, version = version + 1"), only);
        assertRefused(w, false, w.set("response_action = 'NO_CHANGE', version = version + 1"), only);
        assertRefused(w, false, w.set("response_note = 'x', version = version + 1"), only, w.ack());
        assertRefused(w, true, w.set("response_note = 'x', version = version + 1"), only, w.ack());
    }

    @Test
    void theOwnerCannotChangeTheIdentityOrRaiseFieldsOrSetATimeByHand() throws Exception {
        World w = world(false);
        assertAllRefused(w, true, "identity and raise fields cannot change", new String[0],
                w.set("raised_at = raised_at - interval '1 minute'"), w.set("raised_by = gen_random_uuid()"),
                w.set("admission_id = gen_random_uuid()"), w.set("id = gen_random_uuid()"), w.set("tenant_id = gen_random_uuid()"));
        assertAllRefused(w, true, "closed_at is set by the database", new String[0], w.set("closed_at = now()"));
        assertAllRefused(w, true, "reopened_at is set by the database", new String[0], w.set("reopened_at = now()"));
        assertAllRefused(w, true, "acknowledged_at is set by the database", new String[0], w.set("acknowledged_at = now()"));
        assertRefused(w, true, w.set("reopened_at = now()"), "reopened_at is set by the database", w.ack());
        assertUnchanged(w, "OPEN|0|-|false|false|false|0|-|false|-");
    }

    @Test
    void theColumnGrantTheTriggerTheFunctionPrivilegesAndTheIndexAreInTheCatalog() throws Exception {
        String granted = ownerText("select string_agg(attname, ',' order by attname) from pg_attribute where "
                + "attrelid = 'ipd.ews_escalation'::regclass and attnum > 0 and not attisdropped "
                + "and has_column_privilege('sahaj_app', 'ipd.ews_escalation', attname, 'UPDATE')");
        assertThat(granted).as("update privileges of sahaj_app are exactly the ten columns")
                .isEqualTo("acknowledged_by,closed_by,ews_risk,ews_score,response_action,response_note,senior_calls,status,"
                        + "target_minutes,version");
        for (String column : new String[] {"closed_by", "reopened_at"}) {
            assertThat(ownerText("select count(*) from pg_attribute where attrelid = 'ipd.ews_escalation'::regclass "
                    + "and attname = '" + column + "' and not attisdropped")).as(column).isEqualTo("1");
        }
        assertThat(ownerText("select tgenabled::text from pg_trigger where tgname = 'trg_ews_escalation_guard_update' "
                + "and tgrelid = 'ipd.ews_escalation'::regclass")).isEqualTo("O");
        assertThat(ownerText("select has_function_privilege('sahaj_app', 'ipd.guard_ews_escalation_update()', 'EXECUTE')::text"))
                .isEqualTo("false");
        assertThat(ownerText("select has_function_privilege('public', 'ipd.guard_ews_escalation_update()', 'EXECUTE')::text"))
                .isEqualTo("false");
        assertThat(ownerText("select prosecdef::text from pg_proc where oid = 'ipd.guard_ews_escalation_update()'::regprocedure"))
                .as("SECURITY INVOKER").isEqualTo("false");
        assertThat(ownerText("select (to_regclass('ipd.ix_ews_escalation_tenant_admission') is not null)::text")).isEqualTo("true");
        assertThat(ownerText("select (to_regclass('ipd.ux_ews_escalation_tenant_admission_open') is not null)::text")).isEqualTo("true");
        // the grant stays tight for the insert: only the five SAH-30 columns
        assertThat(ownerText("select string_agg(attname, ',' order by attname) from pg_attribute where "
                + "attrelid = 'ipd.ews_escalation'::regclass and attnum > 0 and not attisdropped "
                + "and has_column_privilege('sahaj_app', 'ipd.ews_escalation', attname, 'INSERT')"))
                .isEqualTo("admission_id,ews_risk,ews_score,raised_by,target_minutes");
        // Code review 1, ADR-007 R7: the four checks that old test rows could break exist, and the test says nothing about
        // whether they are validated (a later migration validates them once the test data is cleaned); the other three are validated
        assertThat(ownerText("select string_agg(conname || ':' || convalidated, ',' order by conname) from pg_constraint "
                + "where conrelid = 'ipd.ews_escalation'::regclass and conname in ('ck_ews_escalation_done_action', "
                + "'ck_ews_escalation_done_closed_by', 'ck_ews_escalation_response_note_done', 'ck_ews_escalation_ack_by_status', "
                + "'ck_ews_escalation_response_action', 'ck_ews_escalation_response_note', 'ck_ews_escalation_reopened')"))
                .contains("ck_ews_escalation_response_action:true").contains("ck_ews_escalation_response_note:true")
                .contains("ck_ews_escalation_reopened:true").contains("ck_ews_escalation_done_action:")
                .contains("ck_ews_escalation_ack_by_status:");
    }

    // ---------------- another clinic ----------------

    @Test
    void anotherClinicsEscalationCannotBeUpdatedOrSeenAndATenantChangeIsRefused() throws Exception {
        World a = world(false);
        Clinic b = hospital();
        long changed = db().inTenant(b.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(UPD + "status = 'ACK', acknowledged_by = ?, version = version + 1 "
                    + "where id = ?::uuid")) {
                ps.setObject(1, b.ownerUserId());
                ps.setString(2, a.id());
                return (long) ps.executeUpdate();
            }
        });
        assertThat(changed).as("row level security hides the row: nothing matched").isZero();
        assertThat(count(b, "select count(*) from ipd.ews_escalation where id = ?::uuid", a.id())).isZero();
        SqlErr moved = appErr(a.clinic(), UPD + "tenant_id = ?::uuid where id = ?::uuid", b.tenantId().toString(), a.id());
        assertThat(moved.state()).isEqualTo("42501");
        // with no clinic bound at all nothing matches either
        long unbound = db().<Long>asApp(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPD + "senior_calls = senior_calls + 1 where id = ?::uuid")) {
                ps.setString(1, a.id());
                return (long) ps.executeUpdate();
            }
        });
        assertThat(unbound).isZero();
        assertUnchanged(a, "OPEN|0|-|false|false|false|0|-|false|-");
    }

    // ---------------- two connections, one row ----------------

    @Test
    void twoConnectionsAcknowledgingOneRowGiveOneWriteAndTheSecondMatchesNothingOrIsRefusedByTheTrigger() throws Exception {
        World w = world(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = tenantConnection(w.clinic()); Connection second = tenantConnection(w.clinic())) {
            // the first acknowledges and keeps its transaction open
            assertThat(execUpdate(first, UPD + "status = 'ACK', acknowledged_by = '" + w.user()
                    + "'::uuid, version = version + 1 where id = '" + w.id() + "'::uuid and status = 'OPEN'")).isOne();
            // the second, with the same predicates as the repository, waits for the row lock
            Future<Integer> guarded = pool.submit(() -> execUpdate(second, UPD + "status = 'ACK', acknowledged_by = '"
                    + UUID.randomUUID() + "'::uuid, version = version + 1 where id = '" + w.id()
                    + "'::uuid and status = 'OPEN' and version = 0"));
            assertThat(awaitLockWaiters("%update ipd.ews_escalation%", 1, 30_000)).as("the second update waits").isTrue();
            assertThat(guarded.isDone()).isFalse();
            first.commit();
            // READ COMMITTED: the where is evaluated again on the committed row (status is ACK) and matches nothing
            assertThat(guarded.get(30, TimeUnit.SECONDS)).isZero();
            second.rollback();

            // a statement that names the row by id only reaches the trigger and is refused: no double write
            Future<SqlErr> byId = pool.submit(() -> {
                try {
                    execUpdate(second, UPD + "status = 'ACK', acknowledged_by = '" + UUID.randomUUID()
                            + "'::uuid, version = version + 1 where id = '" + w.id() + "'::uuid");
                    return null;
                } catch (SQLException e) {
                    return new SqlErr(e.getSQLState(), e.getMessage());
                }
            });
            SqlErr refused = byId.get(30, TimeUnit.SECONDS);
            assertThat(refused).isNotNull();
            assertThat(refused.state()).isEqualTo("23514");
            assertThat(refused.message()).contains("acknowledgement cannot change");
        } finally {
            pool.shutdownNow();
        }
        assertThat(values(w.clinic(), String.format(READ, w.id())).get(0)).isEqualTo("ACK|1|" + w.user() + "|true|false|false|0|-|false|-");
    }

    @Test
    void anAcknowledgeWaitingBehindACloseMatchesNothingAndACloseWaitingBehindACallSeniorStillSucceeds() throws Exception {
        World w = world(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = tenantConnection(w.clinic()); Connection second = tenantConnection(w.clinic())) {
            assertThat(execUpdate(first, w.doneFromOpen() + " and status = 'OPEN'")).isOne();
            Future<Integer> ack = pool.submit(() -> execUpdate(second, UPD + "status = 'ACK', acknowledged_by = '" + w.user()
                    + "'::uuid, version = version + 1 where id = '" + w.id() + "'::uuid and status = 'OPEN' and version = 0"));
            assertThat(awaitLockWaiters("%update ipd.ews_escalation%", 1, 30_000)).isTrue();
            first.commit();
            assertThat(ack.get(30, TimeUnit.SECONDS)).as("the row is DONE now").isZero();
            second.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertThat(values(w.clinic(), String.format(READ, w.id())).get(0)).startsWith("DONE|1|");

        // a senior call leaves the version alone, so a close that carries the version it saw still matches
        World v = world(false);
        ExecutorService pool2 = Executors.newSingleThreadExecutor();
        try (Connection first = tenantConnection(v.clinic()); Connection second = tenantConnection(v.clinic())) {
            assertThat(execUpdate(first, UPD + "senior_calls = senior_calls + 1 where id = '" + v.id()
                    + "'::uuid and status = 'OPEN' and version = 0 and senior_calls = 0")).isOne();
            Future<Integer> close = pool2.submit(() -> execUpdate(second, v.doneFromOpen()
                    + " and status = 'OPEN' and version = 0"));
            assertThat(awaitLockWaiters("%update ipd.ews_escalation%", 1, 30_000)).isTrue();
            first.commit();
            assertThat(close.get(30, TimeUnit.SECONDS)).isOne();
            second.commit();
        } finally {
            pool2.shutdownNow();
        }
        assertThat(values(v.clinic(), String.format(READ, v.id())).get(0))
                .isEqualTo("DONE|1|" + v.user() + "|true|true|false|1|NO_CHANGE|true|" + v.user());
    }

    private static int execUpdate(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }
}
