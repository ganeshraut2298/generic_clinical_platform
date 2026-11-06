package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-28 the two new tables, the five new {@code ipd.config} columns and the migration, checked by SQL on the real
 * {@code sahaj_test} database: column grants of {@code sahaj_app}, no delete and no truncate, the state guard
 * (SQLSTATE 23514), check constraints, the partial unique bay index, composite foreign keys across clinics, row level
 * security with no clinic bound, and the append-only triage log. Every statement runs in a rolled-back transaction.
 * Test plan: docs/test-plans/SAH-28.md.
 */
@OnTestDatabase
class EmergencyTableIT extends EmergencySupport {

    private static final String INSERT = "insert into ipd.emergency_case (display_name, age, sex, mobile, complaint, "
            + "identity_pending, level, bay, registered_by, client_key) values (?, ?, ?, ?, ?, false, ?, ?, ?, ?)";

    /** Clinic A with an open, a seen, a released, a referred and an admitted case; clinic B with a case and an admission. */
    private record World(Clinic a, String waiting, String seen, String released, String referred, String admitted,
                         String admissionOfA, UUID patientOfA, Clinic b, String caseOfB, String admissionOfB, UUID patientOfB) {
    }

    private World world() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        setBays(a, 5);
        String waiting = reg(a, "Waiting", 1).get("id").asText();            // ER-1
        String seen = reg(a, "Seen", 1).get("id").asText();                  // ER-2
        seen(a, seen);
        String released = reg(a, "Released", 1).get("id").asText();          // ER-3
        release(a, released);
        String referred = reg(a, "Referred", 1).get("id").asText();          // ER-3 again
        refer(a, referred);
        String admitted = reg(a, "Admitted", 2).get("id").asText();
        UUID patientOfA = newPatient(a);
        JsonNode admission = admitOk(a, admitBody(a, patientOfA, newBed(a, "gen"), admitted));
        UUID patientOfB = newPatient(b);
        String admissionOfB = admitOk(b, admitBody(b, patientOfB, newBed(b, "gen"), null)).get("id").asText();
        String caseOfB = reg(b, "Case of B", 1).get("id").asText();
        return new World(a, waiting, seen, released, referred, admitted, admission.get("id").asText(), patientOfA, b, caseOfB,
                admissionOfB, patientOfB);
    }

    // ---------------- running statements ----------------

    private record Err(String state, String message) {
    }

    /** The error of the statements run in order in one rolled-back transaction as sahaj_app, or null when all pass. */
    private Err run(Clinic clinic, String sql, Object... params) throws SQLException {
        try {
            db().inTenant(clinic.tenantId(), c -> {
                DbAccess.execute(c, sql, params);
                return null;
            });
        } catch (SQLException e) {
            return new Err(e.getSQLState(), e.getMessage());
        }
        return null;
    }

    private Err denied(Clinic clinic, String sql, Object... params) throws SQLException {
        Err e = run(clinic, sql, params);
        assertThat(e).as("the statement was accepted: " + sql).isNotNull();
        return e;
    }

    private void accepted(Clinic clinic, String sql, Object... params) throws SQLException {
        Err e = run(clinic, sql, params);
        assertThat(e == null ? null : e.message()).as("a positive control must be accepted: " + sql).isNull();
    }

    private Err insertCase(Clinic clinic, String name, Integer age, String sex, String mobile, String complaint, int level,
                           String bay) throws SQLException {
        return run(clinic, INSERT, name, age, sex, mobile, complaint, level, bay, clinic.ownerUserId(), UUID.randomUUID());
    }

    private void goodInsert(Clinic clinic, String bay) throws SQLException {
        assertThat(insertCase(clinic, "Name", 40, "F", "9876543210", "Pain", 2, bay)).isNull();
    }

    // ---------------- grants ----------------

    @Test
    void sahajAppCanOnlyUpdateTheStatusLevelAndClosureColumnsAndInsertTheTypedColumns() throws Exception {
        World w = world();
        Clinic a = w.a();
        // updatable (positive controls on the open case)
        accepted(a, "update ipd.emergency_case set level = 3, version = version + 1 where id = ?::uuid", w.waiting());
        accepted(a, "update ipd.emergency_case set status = 'SEEN', seen_at = now(), seen_by = ? where id = ?::uuid",
                a.ownerUserId(), w.waiting());
        accepted(a, "update ipd.emergency_case set status = 'RELEASED', closed_at = now(), closed_by = ? where id = ?::uuid",
                a.ownerUserId(), w.waiting());
        // not updatable: the identity, the bay, the arrival time, who registered, the key, the clinic and the id
        String[] frozen = {"bay = 'ER-5'", "arrived_at = now() - interval '1 hour'", "display_name = 'Someone else'", "age = 41",
                "sex = 'M'", "complaint = 'Changed'", "identity_pending = true",
                "registered_by = gen_random_uuid()", "client_key = gen_random_uuid()", "tenant_id = gen_random_uuid()",
                "id = gen_random_uuid()"};
        for (String set : frozen) {
            Err e = denied(a, "update ipd.emergency_case set " + set + " where id = ?::uuid", w.waiting());
            assertThat(e.message()).as(set).contains("permission denied");
            assertThat(e.state()).as(set).isEqualTo("42501");
        }
        // mobile is updatable since the fix pass, but only to null in the update that closes the case: the guard refuses the rest
        Err mobile = denied(a, "update ipd.emergency_case set mobile = '9876543210' where id = ?::uuid", w.waiting());
        assertThat(mobile.state()).isEqualTo("23514");
        assertThat(mobile.message()).contains("emergency case mobile can only be cleared when the case is closed");
        // no delete, no truncate
        assertThat(denied(a, "delete from ipd.emergency_case where id = ?::uuid", w.waiting()).message()).contains("permission denied");
        assertThat(denied(a, "truncate ipd.emergency_case").message()).contains("permission denied");
        assertThat(denied(a, "truncate ipd.emergency_triage_event").message()).contains("permission denied");
        // insertable columns only: the database fixes the id, the clinic, the status, the arrival time and the version
        String[] notInsertable = {"id", "tenant_id", "status", "arrived_at", "version", "patient_id", "seen_at", "seen_by",
                "closed_at", "closed_by", "admission_id"};
        for (String column : notInsertable) {
            Object value = switch (column) {
                case "id", "tenant_id", "seen_by", "closed_by", "admission_id", "patient_id" -> UUID.randomUUID();
                case "status" -> "SEEN";
                case "version" -> 5;
                default -> java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600));
            };
            Err e = denied(a, "insert into ipd.emergency_case (display_name, sex, complaint, level, registered_by, client_key, "
                    + column + ") values ('N', 'M', 'C', 2, ?, ?, ?)", a.ownerUserId(), UUID.randomUUID(), value);
            assertThat(e.message()).as(column).contains("permission denied");
        }
        goodInsert(a, null);
        // a new case always starts WAITING, version 0, at the database clock, with no patient and no admission
        String id = db().inTenant(a.tenantId(), c -> {
            DbAccess.execute(c, INSERT, "N", 1, "M", null, "C", 2, null, a.ownerUserId(), UUID.randomUUID());
            return DbAccess.text(c, "select status || ' ' || version || ' ' || (arrived_at > now() - interval '1 minute') || ' ' "
                    + "|| (patient_id is null) || ' ' || (admission_id is null) from ipd.emergency_case order by arrived_at desc limit 1");
        });
        assertThat(id).isEqualTo("WAITING 0 true true true");
    }

    @Test
    void theTriageLogIsAppendOnlyForSahajAppAndOnlyTheSixColumnsAreInsertable() throws Exception {
        World w = world();
        Clinic a = w.a();
        accepted(a, "insert into ipd.emergency_triage_event (case_id, from_level, level, scale, reason, recorded_by) "
                + "values (?::uuid, 1, 2, 3, 'RETRIAGED', ?)", w.waiting(), a.ownerUserId());
        for (String sql : new String[] {"update ipd.emergency_triage_event set level = 1", "delete from ipd.emergency_triage_event",
                "update ipd.emergency_triage_event set recorded_by = gen_random_uuid()"}) {
            Err e = denied(a, sql);
            assertThat(e.message()).as(sql).contains("permission denied");
            assertThat(e.state()).isEqualTo("42501");
        }
        for (String column : new String[] {"id", "tenant_id", "recorded_at"}) {
            Object value = column.equals("recorded_at") ? java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)) : UUID.randomUUID();
            assertThat(denied(a, "insert into ipd.emergency_triage_event (case_id, from_level, level, scale, reason, recorded_by, "
                    + column + ") values (?::uuid, 1, 2, 3, 'RETRIAGED', ?, ?)", w.waiting(), a.ownerUserId(), value).message())
                    .as(column).contains("permission denied");
        }
    }

    @Test
    void theTableOwnerCannotDeleteTruncateOrChangeTheTriageLogEitherBecauseOfTheTriggers() throws Exception {
        World w = world();
        // the owner is subject to FORCE row level security, so, as the other tests do, switch it off inside one
        // transaction that is rolled back (DDL is transactional in PostgreSQL)
        String[][] statements = {
                {"ipd.emergency_case", "delete from ipd.emergency_case"},
                {"ipd.emergency_case", "truncate ipd.emergency_case"},
                {"ipd.emergency_triage_event", "update ipd.emergency_triage_event set level = 1"},
                {"ipd.emergency_triage_event", "delete from ipd.emergency_triage_event"},
                {"ipd.emergency_triage_event", "truncate ipd.emergency_triage_event"}};
        for (String[] s : statements) {
            try (Connection c = db().migrator()) {
                c.setAutoCommit(false);
                try {
                    DbAccess.execute(c, "set local lock_timeout = '30s'");
                    DbAccess.execute(c, "alter table " + s[0] + " no force row level security");
                    try (PreparedStatement ps = c.prepareStatement(s[1])) {
                        ps.execute();
                        throw new AssertionError("the owner was allowed: " + s[1]);
                    } catch (SQLException e) {
                        if (s[1].equals("truncate ipd.emergency_case")) {
                            // refused earlier still: the table is referenced by the triage log (feature_not_supported)
                            assertThat(e.getSQLState()).as(s[1]).isIn("0A000", "42501");
                        } else {
                            assertThat(e.getSQLState()).as(s[1]).isEqualTo("42501");
                            assertThat(e.getMessage()).as(s[1]).contains("append-only");
                        }
                    }
                } finally {
                    c.rollback();
                }
            }
        }
        assertThat(caseRows(w.a())).isEqualTo(5);
        assertThat(rows(w.a(), "emergency_triage_event")).isGreaterThanOrEqualTo(5);
    }

    // ---------------- the state guard ----------------

    @Test
    void aClosedCaseCanNeverBeUpdatedAgainAndASeenCaseNeverGoesBackToWaiting() throws Exception {
        World w = world();
        Clinic a = w.a();
        for (String closed : new String[] {w.released(), w.referred(), w.admitted()}) {
            for (String set : new String[] {"level = 3", "version = version + 1", "status = 'SEEN', seen_at = now(), seen_by = gen_random_uuid()"}) {
                Err e = denied(a, "update ipd.emergency_case set " + set + " where id = ?::uuid", closed);
                assertThat(e.state()).as(col(a, closed, "status") + " " + set).isEqualTo("23514");
                assertThat(e.message()).contains("closed");
            }
        }
        // SEEN never returns to WAITING, not even with the seen columns cleared so that the other checks would pass
        Err back = denied(a, "update ipd.emergency_case set status = 'WAITING', seen_at = null, seen_by = null where id = ?::uuid", w.seen());
        assertThat(back.state()).isEqualTo("23514");
        assertThat(back.message()).contains("cannot go back to waiting");
        // the forward moves are allowed (positive controls)
        accepted(a, "update ipd.emergency_case set status = 'SEEN', seen_at = now(), seen_by = ? where id = ?::uuid", a.ownerUserId(), w.waiting());
        accepted(a, "update ipd.emergency_case set status = 'RELEASED', closed_at = now(), closed_by = ? where id = ?::uuid", a.ownerUserId(), w.seen());
        accepted(a, "update ipd.emergency_case set level = 2 where id = ?::uuid", w.seen());
        // the API says 409 first; the rows are as the API left them
        assertThat(col(a, w.released(), "status")).isEqualTo("RELEASED");
        assertThat(col(a, w.seen(), "status")).isEqualTo("SEEN");
    }

    // ---------------- the mobile number of an open case (SAH-28 fix pass, security M1(a)) ----------------

    private static final String MOBILE = "9876543210";
    private static final String OTHER_MOBILE = "9123456780";
    private static final String CLOSE_RELEASED = "status = 'RELEASED', closed_at = now(), closed_by = ?";

    /** Registers (through the API) an open case of level 3 (no bay) that holds a mobile number; optionally already seen. */
    private String withMobile(Clinic clinic, boolean alreadySeen) throws Exception {
        Map<String, Object> body = caseBody("Has a mobile", 3);
        body.put("mobile", MOBILE);
        String id = register(clinic, body).get("actedCase").get("id").asText();
        assertThat(col(clinic, id, "mobile")).as("the mobile is kept while the case is open").isEqualTo(MOBILE);
        if (alreadySeen) {
            seen(clinic, id);
        }
        return id;
    }

    /** Runs the update and reads one value back in the same rolled-back transaction. */
    private String updatedThenRead(Clinic clinic, String update, Object[] params, String select, Object... selectParams) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, update, params);
            return DbAccess.text(c, select, selectParams);
        });
    }

    private void assertMobileRefused(Err e, String what) {
        assertThat(e.state()).as(what).isEqualTo("23514");
        assertThat(e.message()).as(what).contains("emergency case mobile can only be cleared when the case is closed");
    }

    @Test
    void mobileCanOnlyBeClearedAndOnlyInTheUpdateThatClosesAnOpenCase() throws Exception {
        World w = world();
        Clinic a = w.a();
        UUID by = a.ownerUserId();
        String waitingWithMobile = withMobile(a, false);
        String seenWithMobile = withMobile(a, true);

        // refused on an open case: to another number, to null while it stays open (also with a non-closing status move),
        // and from null to a number
        for (String open : new String[] {waitingWithMobile, seenWithMobile}) {
            String state = col(a, open, "status");
            assertMobileRefused(denied(a, "update ipd.emergency_case set mobile = '" + OTHER_MOBILE + "' where id = ?::uuid", open),
                    state + " number to number");
            assertMobileRefused(denied(a, "update ipd.emergency_case set mobile = null where id = ?::uuid", open),
                    state + " null while open");
            assertMobileRefused(denied(a, "update ipd.emergency_case set mobile = null, level = 2 where id = ?::uuid", open),
                    state + " null with a level change");
            assertThat(col(a, open, "mobile")).as("the refused update left the number").isEqualTo(MOBILE);
        }
        assertMobileRefused(denied(a, "update ipd.emergency_case set mobile = null, status = 'SEEN', seen_at = now(), seen_by = ? "
                + "where id = ?::uuid", by, waitingWithMobile), "null with the move to SEEN, which does not close");
        // from null to a number on an open case with no mobile
        assertMobileRefused(denied(a, "update ipd.emergency_case set mobile = '" + MOBILE + "' where id = ?::uuid", w.waiting()), "null to number");

        // closing while setting a number is refused, whatever the closing status
        for (String status : new String[] {"RELEASED", "REFERRED"}) {
            assertMobileRefused(denied(a, "update ipd.emergency_case set status = '" + status + "', closed_at = now(), closed_by = ?, "
                    + "mobile = '" + OTHER_MOBILE + "' where id = ?::uuid", by, waitingWithMobile), "close " + status + " with a number");
        }

        // a closed case refuses every update, a mobile-only update included (closed message first)
        for (String closed : new String[] {w.released(), w.referred(), w.admitted()}) {
            for (String set : new String[] {"mobile = null", "mobile = '" + MOBILE + "'"}) {
                Err e = denied(a, "update ipd.emergency_case set " + set + " where id = ?::uuid", closed);
                assertThat(e.state()).as(col(a, closed, "status") + " " + set).isEqualTo("23514");
                assertThat(e.message()).as(set).contains("emergency case is closed (update refused)");
            }
        }

        // allowed: close and clear in one UPDATE (RELEASED, REFERRED; seen or waiting); the number is gone afterwards
        String read = "select coalesce(mobile, 'NULL') from ipd.emergency_case where id = ?::uuid";
        for (String id : new String[] {waitingWithMobile, seenWithMobile}) {
            assertThat(updatedThenRead(a, "update ipd.emergency_case set " + CLOSE_RELEASED + ", mobile = null where id = ?::uuid",
                    new Object[] {by, id}, read, id)).as("RELEASED " + col(a, id, "status")).isEqualTo("NULL");
            assertThat(updatedThenRead(a, "update ipd.emergency_case set status = 'REFERRED', closed_at = now(), closed_by = ?, mobile = null "
                    + "where id = ?::uuid", new Object[] {by, id}, read, id)).as("REFERRED " + col(a, id, "status")).isEqualTo("NULL");
        }
        // ... and ADMITTED, with the admission and the patient of the clinic, in the same UPDATE
        Clinic b = w.b();
        Map<String, Object> body = caseBody("Admitted with a mobile", 3);
        body.put("mobile", MOBILE);
        String toAdmit = register(b, body).get("actedCase").get("id").asText();
        assertThat(updatedThenRead(b, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, "
                + "admission_id = ?::uuid, patient_id = ?, mobile = null where id = ?::uuid",
                new Object[] {b.ownerUserId(), w.admissionOfB(), w.patientOfB(), toAdmit}, read, toAdmit)).as("ADMITTED").isEqualTo("NULL");
        // ... but not ADMITTED with a number kept changed
        assertMobileRefused(denied(b, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, "
                + "admission_id = ?::uuid, patient_id = ?, mobile = '" + OTHER_MOBILE + "' where id = ?::uuid",
                b.ownerUserId(), w.admissionOfB(), w.patientOfB(), toAdmit), "ADMITTED with a number");

        // allowed on an open case with mobile unchanged: level, version, and the move to SEEN keep the number
        for (String update : new String[] {"level = 2", "level = 4, version = version + 1", "version = version + 1"}) {
            assertThat(updatedThenRead(a, "update ipd.emergency_case set " + update + " where id = ?::uuid", new Object[] {waitingWithMobile},
                    read, waitingWithMobile)).as(update).isEqualTo(MOBILE);
        }
        assertThat(updatedThenRead(a, "update ipd.emergency_case set status = 'SEEN', seen_at = now(), seen_by = ? where id = ?::uuid",
                new Object[] {by, waitingWithMobile}, read, waitingWithMobile)).as("move to SEEN").isEqualTo(MOBILE);
        // setting a column to the value it already has is no change of mobile
        assertThat(updatedThenRead(a, "update ipd.emergency_case set mobile = mobile, level = 2 where id = ?::uuid", new Object[] {waitingWithMobile},
                read, waitingWithMobile)).as("mobile = mobile").isEqualTo(MOBILE);
        // nothing of the above was committed
        assertThat(col(a, waitingWithMobile, "status")).isEqualTo("WAITING");
        assertThat(col(a, waitingWithMobile, "mobile")).isEqualTo(MOBILE);
        // another clinic cannot see or change the number of this clinic's case
        assertThat(count(b, "select count(*) from ipd.emergency_case where id = ?::uuid", waitingWithMobile)).isZero();
    }

    @Test
    void patientIdCanOnlyBeSetTogetherWithAdmitted() throws Exception {
        World w = world();
        Clinic a = w.a();
        Clinic b = w.b();
        String seenWithMobile = withMobile(a, true);
        // an open case (waiting or seen) never holds a patient, even the clinic's own
        for (String open : new String[] {w.waiting(), w.seen(), seenWithMobile}) {
            Err e = denied(a, "update ipd.emergency_case set patient_id = ? where id = ?::uuid", w.patientOfA(), open);
            assertThat(e.state()).as(col(a, open, "status")).isEqualTo("23514");
            assertThat(e.message()).contains("ck_emergency_case_patient_admitted");
        }
        // a closed but not admitted case cannot get one through the closing update either
        Err released = denied(a, "update ipd.emergency_case set " + CLOSE_RELEASED + ", patient_id = ? where id = ?::uuid",
                a.ownerUserId(), w.patientOfA(), w.waiting());
        assertThat(released.state()).isEqualTo("23514");
        assertThat(released.message()).contains("ck_emergency_case_patient_admitted");
        // together with ADMITTED it is accepted (own clinic's admission and patient)
        accepted(b, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, admission_id = ?::uuid, "
                + "patient_id = ? where id = ?::uuid", b.ownerUserId(), w.admissionOfB(), w.patientOfB(), w.caseOfB());
        // the constraint exists exactly as designed and holds for every stored row
        assertThat(ownerText("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_emergency_case_patient_admitted' "
                + "and conrelid = 'ipd.emergency_case'::regclass")).contains("patient_id IS NULL").contains("ADMITTED");
        assertThat(ownerText("select count(*)::text from ipd.emergency_case where patient_id is not null and status <> 'ADMITTED'"))
                .isEqualTo("0");
    }

    @Test
    void theArrivalAndTriageTimesNowDefaultToTheClockAtWriteTimeAndBackDatingOpenCasesStillWorks() throws Exception {
        World w = world();
        Clinic a = w.a();
        assertThat(ownerText("select pg_get_expr(d.adbin, d.adrelid) from pg_attrdef d join pg_attribute t on t.attrelid = d.adrelid "
                + "and t.attnum = d.adnum where d.adrelid = 'ipd.emergency_case'::regclass and t.attname = 'arrived_at'"))
                .containsIgnoringCase("clock_timestamp");
        assertThat(ownerText("select pg_get_expr(d.adbin, d.adrelid) from pg_attrdef d join pg_attribute t on t.attrelid = d.adrelid "
                + "and t.attnum = d.adnum where d.adrelid = 'ipd.emergency_triage_event'::regclass and t.attname = 'recorded_at'"))
                .containsIgnoringCase("clock_timestamp");
        // two rows of one transaction no longer share the transaction start time
        String distinct = db().inTenant(a.tenantId(), c -> {
            DbAccess.execute(c, INSERT, "N", 1, "M", null, "C", 3, null, a.ownerUserId(), UUID.randomUUID());
            DbAccess.execute(c, "select pg_sleep(0.05)");
            DbAccess.execute(c, INSERT, "N", 1, "M", null, "C", 3, null, a.ownerUserId(), UUID.randomUUID());
            return DbAccess.text(c, "select count(distinct arrived_at)::text from (select arrived_at from ipd.emergency_case "
                    + "order by arrived_at desc limit 2) t");
        });
        assertThat(distinct).isEqualTo("2");
        // a new case still arrives "now", and back-dating an open case as the table owner (the helper of the other ITs) still works
        String id = w.waiting();
        Instant earlier = Instant.now().minusSeconds(3600);
        arrivedAt(a, id, earlier);
        assertThat(count(a, "select count(*) from ipd.emergency_case where id = ?::uuid and arrived_at < now() - interval '59 minutes'", id))
                .isEqualTo(1);
        // a closed row is unchanged by design: the guard refuses the owner as well
        try {
            arrivedAt(a, w.released(), earlier);
            throw new AssertionError("back-dating a closed case was accepted");
        } catch (SQLException e) {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains("closed");
        }
    }

    // ---------------- check constraints ----------------

    @Test
    void theCheckConstraintsRefuseABlankOrTooLongNameComplaintOutOfRangeValuesAndABadBayOrMobile() throws Exception {
        World w = world();
        Clinic a = w.a();
        goodInsert(a, null);
        assertThat(insertCase(a, "N", 0, "M", null, "C", 1, null)).isNull();
        assertThat(insertCase(a, "N", 120, "O", "6000000000", "C", 5, "ER-10")).isNull();
        assertThat(insertCase(a, "x".repeat(80), null, "M", null, "c".repeat(200), 2, "ER-9")).isNull();
        Object[][] bad = {
                {"ck_emergency_case_display_name", "", 40, "M", null, "C", 2, null},
                {"ck_emergency_case_display_name", "   ", 40, "M", null, "C", 2, null},
                {"ck_emergency_case_display_name", "x".repeat(81), 40, "M", null, "C", 2, null},
                {"ck_emergency_case_age", "N", -1, "M", null, "C", 2, null},
                {"ck_emergency_case_age", "N", 121, "M", null, "C", 2, null},
                {"ck_emergency_case_sex", "N", 40, "X", null, "C", 2, null},
                {"ck_emergency_case_sex", "N", 40, "m", null, "C", 2, null},
                {"ck_emergency_case_mobile", "N", 40, "M", "12345", "C", 2, null},
                {"ck_emergency_case_mobile", "N", 40, "M", "5876543210", "C", 2, null},
                {"ck_emergency_case_mobile", "N", 40, "M", "98765432101", "C", 2, null},
                {"ck_emergency_case_complaint", "N", 40, "M", null, "", 2, null},
                {"ck_emergency_case_complaint", "N", 40, "M", null, "  ", 2, null},
                {"ck_emergency_case_complaint", "N", 40, "M", null, "c".repeat(201), 2, null},
                {"ck_emergency_case_level", "N", 40, "M", null, "C", 0, null},
                {"ck_emergency_case_level", "N", 40, "M", null, "C", 6, null},
                {"ck_emergency_case_bay", "N", 40, "M", null, "C", 1, "ER-0"},
                {"ck_emergency_case_bay", "N", 40, "M", null, "C", 1, "ER-11"},
                {"ck_emergency_case_bay", "N", 40, "M", null, "C", 1, "ER-01"},
                {"ck_emergency_case_bay", "N", 40, "M", null, "C", 1, "Bay 1"},
                {"ck_emergency_case_bay", "N", 40, "M", null, "C", 1, "er-1"}};
        for (Object[] b : bad) {
            Err e = insertCase(a, (String) b[1], (Integer) b[2], (String) b[3], (String) b[4], (String) b[5], (Integer) b[6], (String) b[7]);
            assertThat(e).as(b[0] + " " + b[1]).isNotNull();
            assertThat(e.state()).isEqualTo("23514");
            assertThat(e.message()).contains((String) b[0]);
        }
    }

    @Test
    void theStatusChecksKeepWaitingSeenAndClosedCasesConsistent() throws Exception {
        World w = world();
        Clinic a = w.a();
        UUID by = a.ownerUserId();
        Object[][] bad = {
                {"ck_emergency_case_status", "update ipd.emergency_case set status = 'GONE', closed_at = now(), closed_by = ? where id = ?::uuid", new Object[] {by, w.waiting()}},
                {"ck_emergency_case_level", "update ipd.emergency_case set level = 6 where id = ?::uuid", new Object[] {w.waiting()}},
                {"ck_emergency_case_level", "update ipd.emergency_case set level = 0 where id = ?::uuid", new Object[] {w.waiting()}},
                // an open case has no closure
                {"ck_emergency_case_closed", "update ipd.emergency_case set closed_at = now(), closed_by = ? where id = ?::uuid", new Object[] {by, w.waiting()}},
                // a closed case has both
                {"ck_emergency_case_closed", "update ipd.emergency_case set status = 'RELEASED' where id = ?::uuid", new Object[] {w.waiting()}},
                {"ck_emergency_case_closed", "update ipd.emergency_case set status = 'REFERRED', closed_at = now() where id = ?::uuid", new Object[] {w.waiting()}},
                // SEEN needs the seen pair, the pair is all or nothing, WAITING has none
                {"ck_emergency_case_seen", "update ipd.emergency_case set status = 'SEEN' where id = ?::uuid", new Object[] {w.waiting()}},
                {"ck_emergency_case_seen_pair", "update ipd.emergency_case set seen_at = now() where id = ?::uuid", new Object[] {w.waiting()}},
                {"ck_emergency_case_seen_pair", "update ipd.emergency_case set seen_by = ? where id = ?::uuid", new Object[] {by, w.waiting()}},
                {"ck_emergency_case_waiting_unseen", "update ipd.emergency_case set seen_at = now(), seen_by = ? where id = ?::uuid", new Object[] {by, w.waiting()}},
                // only an ADMITTED case has an admission, and it needs a patient
                {"ck_emergency_case_admitted", "update ipd.emergency_case set status = 'RELEASED', closed_at = now(), closed_by = ?, admission_id = ?::uuid where id = ?::uuid", new Object[] {by, w.admissionOfA(), w.waiting()}},
                {"ck_emergency_case_admitted", "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ? where id = ?::uuid", new Object[] {by, w.waiting()}},
                {"ck_emergency_case_admitted", "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, admission_id = ?::uuid where id = ?::uuid", new Object[] {by, w.admissionOfA(), w.waiting()}}};
        for (Object[] b : bad) {
            Err e = denied(a, (String) b[1], (Object[]) b[2]);
            assertThat(e.state()).as(b[0] + " " + b[1]).isEqualTo("23514");
            assertThat(e.message()).as((String) b[1]).contains((String) b[0]);
        }
        // a seen case that is closed keeps its seen pair; an unseen one is closed without it
        accepted(a, "update ipd.emergency_case set status = 'REFERRED', closed_at = now(), closed_by = ? where id = ?::uuid", by, w.seen());
        accepted(a, "update ipd.emergency_case set status = 'REFERRED', closed_at = now(), closed_by = ? where id = ?::uuid", by, w.waiting());
    }

    // ---------------- keys and indexes ----------------

    @Test
    void aClientKeyIsUniquePerClinicAndABayIsHeldByOneOpenCaseAtATimeAndAnAdmissionHasOneCase() throws Exception {
        World w = world();
        Clinic a = w.a();
        UUID key = UUID.fromString(col(a, w.waiting(), "client_key"));
        Err dupKey = run(a, INSERT, "N", 1, "M", null, "C", 2, null, a.ownerUserId(), key);
        assertThat(dupKey).isNotNull();
        assertThat(dupKey.state()).isEqualTo("23505");
        assertThat(dupKey.message()).contains("uq_emergency_case_tenant_client_key");
        // the same key in another clinic is a different case
        assertThat(run(w.b(), INSERT, "N", 1, "M", null, "C", 2, null, w.b().ownerUserId(), key)).isNull();

        // the open case holds ER-1 and the seen case ER-2: neither bay can be given again
        for (String held : new String[] {col(a, w.waiting(), "bay"), col(a, w.seen(), "bay")}) {
            Err e = run(a, INSERT, "N", 1, "M", null, "C", 1, held, a.ownerUserId(), UUID.randomUUID());
            assertThat(e).as(held).isNotNull();
            assertThat(e.state()).isEqualTo("23505");
            assertThat(e.message()).contains("ux_emergency_case_tenant_bay_open");
        }
        // the closed cases kept ER-3 as history, and it is free: a new open case may hold it, once
        assertThat(col(a, w.released(), "bay")).isEqualTo("ER-3");
        assertThat(col(a, w.referred(), "bay")).isEqualTo("ER-3");
        assertThat(insertCase(a, "N", 1, "M", null, "C", 1, "ER-3")).isNull();
        // inside one transaction a second case on the same free bay fails
        Err sameTx = null;
        try {
            db().inTenant(a.tenantId(), c -> {
                DbAccess.execute(c, INSERT, "N", 1, "M", null, "C", 1, "ER-3", a.ownerUserId(), UUID.randomUUID());
                DbAccess.execute(c, INSERT, "N", 1, "M", null, "C", 1, "ER-3", a.ownerUserId(), UUID.randomUUID());
                return null;
            });
        } catch (SQLException e) {
            sameTx = new Err(e.getSQLState(), e.getMessage());
        }
        assertThat(sameTx).isNotNull();
        assertThat(sameTx.message()).contains("ux_emergency_case_tenant_bay_open");
        // the other clinic holds ER-1 at the same time (its own case), and ER-2 is free there
        assertThat(col(w.b(), w.caseOfB(), "bay")).isEqualTo("ER-1");
        assertThat(col(w.a(), w.waiting(), "bay")).isEqualTo("ER-1");
        assertThat(insertCase(w.b(), "N", 1, "M", null, "C", 1, "ER-2")).isNull();

        // an admission is linked to one case only
        Err second = denied(a, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, "
                + "admission_id = ?::uuid, patient_id = ? where id = ?::uuid", a.ownerUserId(), w.admissionOfA(), w.patientOfA(), w.waiting());
        assertThat(second.state()).isEqualTo("23505");
        assertThat(second.message()).contains("ux_emergency_case_tenant_admission");
    }

    @Test
    void theForeignKeysAreCompositeSoACaseCannotPointAtAnotherClinicsPatientAdmissionOrCase() throws Exception {
        World w = world();
        Clinic b = w.b();
        // positive control in B: its own patient and admission, set together with ADMITTED (ck_emergency_case_patient_admitted)
        accepted(b, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, admission_id = ?::uuid, "
                + "patient_id = ? where id = ?::uuid", b.ownerUserId(), w.admissionOfB(), w.patientOfB(), w.caseOfB());
        // B's case with A's patient or A's admission: no such (clinic, id) pair
        // (the patient is set with ADMITTED and B's own admission, so that only the patient pair is wrong: the check on an
        // open case would be reported first, because check constraints run before foreign keys)
        Err patient = denied(b, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, "
                + "admission_id = ?::uuid, patient_id = ? where id = ?::uuid", b.ownerUserId(), w.admissionOfB(), w.patientOfA(), w.caseOfB());
        assertThat(patient.state()).isEqualTo("23503");
        assertThat(patient.message()).contains("fk_emergency_case_patient");
        Err admission = denied(b, "update ipd.emergency_case set status = 'ADMITTED', closed_at = now(), closed_by = ?, "
                + "admission_id = ?::uuid, patient_id = ? where id = ?::uuid", b.ownerUserId(), w.admissionOfA(), w.patientOfB(), w.caseOfB());
        assertThat(admission.state()).isEqualTo("23503");
        assertThat(admission.message()).contains("fk_emergency_case_admission");
        // an event of B on A's case
        accepted(b, "insert into ipd.emergency_triage_event (case_id, from_level, level, scale, reason, recorded_by) "
                + "values (?::uuid, 1, 2, 3, 'RETRIAGED', ?)", w.caseOfB(), b.ownerUserId());
        Err event = denied(b, "insert into ipd.emergency_triage_event (case_id, from_level, level, scale, reason, recorded_by) "
                + "values (?::uuid, 1, 2, 3, 'RETRIAGED', ?)", w.waiting(), b.ownerUserId());
        assertThat(event.state()).isEqualTo("23503");
        assertThat(event.message()).contains("fk_emergency_triage_event_case");
    }

    // ---------------- triage event checks ----------------

    @Test
    void theTriageEventChecksKeepTheLevelInTheScaleAndTheFromLevelTiedToTheReason() throws Exception {
        World w = world();
        Clinic a = w.a();
        String insert = "insert into ipd.emergency_triage_event (case_id, from_level, level, scale, reason, recorded_by) "
                + "values (?::uuid, ?, ?, ?, ?, ?)";
        UUID by = a.ownerUserId();
        accepted(a, insert, w.waiting(), null, 2, 3, "REGISTERED", by);
        accepted(a, insert, w.waiting(), 5, 3, 3, "RETRIAGED", by);
        accepted(a, insert, w.waiting(), 1, 1, 3, "SCALE_CHANGED", by);          // 5 to 3: Level 1 stays 1
        accepted(a, insert, w.waiting(), 3, 5, 5, "SCALE_CHANGED", by);
        Object[][] bad = {
                {"ck_emergency_triage_event_level_scale", null, 4, 3, "REGISTERED"},
                {"ck_emergency_triage_event_level_scale", 1, 5, 3, "RETRIAGED"},
                {"ck_emergency_triage_event_level", null, 0, 3, "REGISTERED"},
                {"ck_emergency_triage_event_level", null, 6, 5, "REGISTERED"},
                {"ck_emergency_triage_event_from_level", 0, 2, 3, "RETRIAGED"},
                {"ck_emergency_triage_event_from_level", 6, 2, 5, "RETRIAGED"},
                {"ck_emergency_triage_event_scale", null, 2, 4, "REGISTERED"},
                {"ck_emergency_triage_event_reason", 1, 2, 3, "EDITED"},
                {"ck_emergency_triage_event_registered", 1, 2, 3, "REGISTERED"},
                {"ck_emergency_triage_event_registered", null, 2, 3, "RETRIAGED"},
                {"ck_emergency_triage_event_registered", null, 2, 3, "SCALE_CHANGED"}};
        for (Object[] b : bad) {
            Err e = denied(a, insert, w.waiting(), b[1], b[2], b[3], b[4], by);
            assertThat(e.state()).as(b[0] + " " + b[4]).isEqualTo("23514");
            assertThat(e.message()).contains((String) b[0]);
        }
    }

    // ---------------- row level security ----------------

    @Test
    void rowsAreInvisibleWithoutAClinicAndToAnotherClinicAndCannotBeChangedOrInsertedAcrossClinics() throws Exception {
        World w = world();
        for (String table : List.of("emergency_case", "emergency_triage_event")) {
            long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd." + table));
            assertThat(unbound).as(table + " with no clinic bound").isZero();
            assertThat(rows(w.a(), table)).as(table + " of A").isPositive();
            assertThat(count(w.b(), "select count(*) from ipd." + table + " where tenant_id = ?", w.a().tenantId())).as(table + " of A seen by B").isZero();
            assertThat(rows(w.b(), table)).isPositive();
        }
        // B updates nothing of A's: the row is not there for it
        int changed = db().inTenant(w.b().tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("update ipd.emergency_case set level = 3 where id = ?::uuid")) {
                ps.setString(1, w.waiting());
                return ps.executeUpdate();
            }
        });
        assertThat(changed).isZero();
        assertThat(col(w.a(), w.waiting(), "level")).isEqualTo("1");
        // nothing can be inserted with no clinic bound, or for another clinic (tenant_id is not an insertable column)
        try {
            db().asApp(c -> {
                DbAccess.execute(c, INSERT, "N", 1, "M", null, "C", 2, null, w.a().ownerUserId(), UUID.randomUUID());
                return null;
            });
            throw new AssertionError("an insert with no clinic bound was accepted");
        } catch (SQLException expected) {
            assertThat(expected.getSQLState()).isNotNull();
        }
        assertThat(denied(w.b(), "insert into ipd.emergency_case (tenant_id, display_name, sex, complaint, level, registered_by, client_key) "
                + "values (?, 'N', 'M', 'C', 2, ?, ?)", w.a().tenantId(), w.b().ownerUserId(), UUID.randomUUID()).message()).contains("permission denied");
        // catalog: both tables enable and force row level security; the case table has no delete policy
        assertThat(ownerText("select string_agg(relname || ':' || relrowsecurity || ':' || relforcerowsecurity, ',' order by relname) "
                + "from pg_class where relname in ('emergency_case', 'emergency_triage_event') and relnamespace = 'ipd'::regnamespace"))
                .isEqualTo("emergency_case:true:true,emergency_triage_event:true:true");
        assertThat(ownerText("select string_agg(cmd, ',' order by cmd) from pg_policies where schemaname = 'ipd' and tablename = 'emergency_case'"))
                .isEqualTo("INSERT,SELECT,UPDATE");
        assertThat(ownerText("select string_agg(cmd, ',' order by cmd) from pg_policies where schemaname = 'ipd' and tablename = 'emergency_triage_event'"))
                .isEqualTo("INSERT,SELECT");
    }

    // ---------------- Emergency setup columns and the migration ----------------

    @Test
    void theNewConfigColumnsHaveTheDefaultsAndTheChecksOfTheDesign() throws Exception {
        Clinic clinic = hospital();
        putSetup(clinic, 3, T3, T5, 3, true);                       // creates the row
        accepted(clinic, "update ipd.config set triage_scale = 5, er_bays = 10, triage_targets_3 = '{0,0,240}', "
                + "triage_targets_5 = '{0,5,10,15,240}', unknown_patient_registration = false");
        Object[][] bad = {
                {"ck_ipd_config_triage_scale", "triage_scale = 4"}, {"ck_ipd_config_triage_scale", "triage_scale = 0"},
                {"ck_ipd_config_er_bays", "er_bays = 11"}, {"ck_ipd_config_er_bays", "er_bays = -1"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '{5,30}'"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '{5,30,120,200}'"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '{5,30,7}'"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '{5,30,245}'"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '{5,30,-5}'"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '{5,30,NULL}'"},
                {"ck_ipd_config_triage_targets_3", "triage_targets_3 = '[0:2]={5,30,120}'"},
                {"ck_ipd_config_triage_targets_5", "triage_targets_5 = '{0,10,30,60}'"},
                {"ck_ipd_config_triage_targets_5", "triage_targets_5 = '{0,10,30,60,121}'"},
                {"ck_ipd_config_triage_targets_5", "triage_targets_5 = '{{0,10,30,60,120}}'"}};
        for (Object[] b : bad) {
            Err e = denied(clinic, "update ipd.config set " + b[1]);
            assertThat(e.state()).as((String) b[1]).isEqualTo("23514");
            assertThat(e.message()).contains((String) b[0]);
        }
        assertThat(denied(clinic, "update ipd.config set triage_scale = null").state()).isEqualTo("23502");
        // the defaults of a row that never saw the emergency setup
        Clinic other = hospital();
        ok(org.springframework.http.HttpMethod.PUT, "/api/v1/ipd/transfer-rules", other.owner(), "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"same\"}", 200);
        assertThat(values(other, "select triage_scale || ' ' || triage_targets_3::text || ' ' || triage_targets_5::text || ' ' || er_bays "
                + "|| ' ' || unknown_patient_registration from ipd.config")).containsExactly("3 {5,30,120} {0,10,30,60,120} 2 true");
        // the helper function itself
        assertThat(ownerText("select string_agg(ipd.triage_targets_ok(v, n)::text, ',' order by ord) from (values "
                + "(1, '{5,30,120}'::smallint[], 3), (2, '{5,30,125}'::smallint[], 3), (3, '{5,30}'::smallint[], 3), "
                + "(4, '{0,0,0}'::smallint[], 3), (5, '{240,240,240}'::smallint[], 3), (6, null::smallint[], 3), "
                + "(7, '{5,30,121}'::smallint[], 3), (8, '{5,30,245}'::smallint[], 3), (9, '{5,30,-5}'::smallint[], 3)) as t(ord, v, n)"))
                .isEqualTo("true,true,false,true,true,false,false,false,false");
    }

    @Test
    void theMigrationIsAppliedAndNothingNewIsGrantedToPublic() throws Exception {
        assertThat(ownerText("select success::text from platform.flyway_schema_history where script like '%ipd_emergency%'")).isEqualTo("true");
        assertThat(ownerText("select string_agg(grantee || ':' || privilege_type, ',' order by grantee, privilege_type) from "
                + "information_schema.table_privileges where table_schema = 'ipd' and table_name = 'emergency_case' and grantee in ('PUBLIC', 'sahaj_app')"))
                .isEqualTo("sahaj_app:SELECT");
        assertThat(ownerText("select string_agg(grantee || ':' || privilege_type, ',' order by grantee, privilege_type) from "
                + "information_schema.table_privileges where table_schema = 'ipd' and table_name = 'emergency_triage_event' and grantee in ('PUBLIC', 'sahaj_app')"))
                .isEqualTo("sahaj_app:SELECT");
        // the column grants of sahaj_app on the case table
        assertThat(ownerText("select string_agg(column_name || ':' || privilege_type, ',' order by privilege_type, column_name) from "
                + "information_schema.column_privileges where table_schema = 'ipd' and table_name = 'emergency_case' and grantee = 'sahaj_app' "
                + "and privilege_type in ('INSERT', 'UPDATE')"))
                .isEqualTo("age:INSERT,bay:INSERT,client_key:INSERT,complaint:INSERT,display_name:INSERT,identity_pending:INSERT,level:INSERT,"
                        + "mobile:INSERT,registered_by:INSERT,sex:INSERT,admission_id:UPDATE,closed_at:UPDATE,closed_by:UPDATE,level:UPDATE,"
                        + "mobile:UPDATE,patient_id:UPDATE,seen_at:UPDATE,seen_by:UPDATE,status:UPDATE,version:UPDATE");
        assertThat(ownerText("select count(*)::text from pg_trigger where tgrelid in ('ipd.emergency_case'::regclass, "
                + "'ipd.emergency_triage_event'::regclass) and not tgisinternal")).isEqualTo("5");
    }
}
