package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-30 the four new tables, proved on the real {@code sahaj_test} database with plain SQL: vitals, nursing_note and
 * clinical_event are append-only (no privilege and a trigger, also for the table owner); ews_escalation has no delete,
 * a column-level update grant and a guard trigger (a DONE row never changes, the score and risk never fall); the
 * column-level insert grants; every check constraint at its edge; the partial unique index of the open escalation;
 * another clinic's admission cannot be pointed at. Test plan: docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsTableIT extends VitalsSupport {

    private static final String APPEND_ONLY = "append-only";
    private static final String DENIED = "permission denied";

    /** A clinic with an admission that has one vitals row, one note, two events and one open escalation (score 3). */
    private record World(Clinic clinic, Adm adm) {
    }

    private World world() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        save(clinic, adm.id(), noted(medium3(), "Patient restless"));
        assertThat(vitalsCount(clinic, adm.id())).isOne();
        assertThat(noteCount(clinic, adm.id())).isOne();
        assertThat(eventCount(clinic, adm.id())).isEqualTo(2);
        assertThat(openEscalationCount(clinic, adm.id())).isOne();
        return new World(clinic, adm);
    }

    // ---------------- append-only: sahaj_app ----------------

    @Test
    void sahajAppCannotUpdateDeleteOrTruncateVitalsNursingNotesOrTheEventLog() throws Exception {
        World w = world();
        String[][] statements = {
                {"update ipd.vitals set pulse = pulse + 1"}, {"update ipd.vitals set ews_score = 0"},
                {"update ipd.vitals set recorded_at = now()"}, {"delete from ipd.vitals"}, {"truncate ipd.vitals"},
                {"update ipd.nursing_note set note = 'changed'"}, {"delete from ipd.nursing_note"},
                {"truncate ipd.nursing_note"}, {"update ipd.clinical_event set text = 'changed'"},
                {"delete from ipd.clinical_event"}, {"truncate ipd.clinical_event"},
                // the escalation has no delete and no truncate either
                {"delete from ipd.ews_escalation"}, {"truncate ipd.ews_escalation"}};
        for (String[] s : statements) {
            SqlErr e = appErr(w.clinic(), s[0]);
            assertThat(e.state()).as(s[0]).isEqualTo("42501");
            assertThat(e.message()).as(s[0]).contains(DENIED);
        }
        // nothing moved
        assertThat(vitalsCount(w.clinic(), w.adm().id())).isOne();
        assertThat(noteCount(w.clinic(), w.adm().id())).isOne();
        assertThat(eventCount(w.clinic(), w.adm().id())).isEqualTo(2);
        assertThat(escalationCount(w.clinic(), w.adm().id())).isOne();
    }

    // ---------------- append-only: the table owner (triggers) ----------------

    @Test
    void theTableOwnerCannotUpdateDeleteOrTruncateTheAppendOnlyTablesEitherBecauseOfTheTriggers() throws Exception {
        World w = world();
        // a row in the senior call table, so that the row triggers of update and delete have a row to refuse
        insertSeniorCall(w.clinic(), openEscalationId(w.clinic(), w.adm().id()), w.clinic().ownerUserId(), "ALREADY_CALLED", null,
                java.time.Instant.now());
        String[][] statements = {
                {"ipd.vitals", "update ipd.vitals set pulse = pulse + 1"},
                {"ipd.vitals", "delete from ipd.vitals"},
                {"ipd.vitals", "truncate ipd.vitals"},
                {"ipd.nursing_note", "update ipd.nursing_note set note = 'changed'"},
                {"ipd.nursing_note", "delete from ipd.nursing_note"},
                {"ipd.nursing_note", "truncate ipd.nursing_note"},
                {"ipd.clinical_event", "update ipd.clinical_event set text = 'changed'"},
                {"ipd.clinical_event", "delete from ipd.clinical_event"},
                {"ipd.clinical_event", "truncate ipd.clinical_event"},
                {"ipd.ews_escalation", "delete from ipd.ews_escalation"},
                {"ipd.ews_escalation", "truncate ipd.ews_escalation cascade"},
                // SAH-33 R4: the senior call table is append-only for the owner too (trigger, 42501)
                {"ipd.ews_senior_call", "update ipd.ews_senior_call set called_by = gen_random_uuid()"},
                {"ipd.ews_senior_call", "delete from ipd.ews_senior_call"},
                {"ipd.ews_senior_call", "truncate ipd.ews_senior_call"}};
        for (String[] s : statements) {
            SqlErr e = ownerErr(s[0], s[1]);
            assertThat(e.state()).as(s[1]).isEqualTo("42501");
            assertThat(e.message()).as(s[1]).contains(APPEND_ONLY);
        }
        // a plain TRUNCATE of the escalation table is refused even before the trigger: ews_senior_call references it
        // through a foreign key (0A000, "cannot truncate a table referenced in a foreign key constraint")
        SqlErr plain = ownerErr("ipd.ews_escalation", "truncate ipd.ews_escalation");
        assertThat(plain.state()).isEqualTo("0A000");
        assertThat(plain.message()).contains("referenced in a foreign key");
        assertThat(vitalsCount(w.clinic(), w.adm().id())).isOne();
        assertThat(escalationCount(w.clinic(), w.adm().id())).isOne();
    }

    @Test
    void theTableOwnerIsStoppedByTheGuardTriggerToo() throws Exception {
        World w = world();
        save(w.clinic(), w.adm().id(), high7()); // the open escalation is now 7, High
        assertThat(openEscalation(w.clinic(), w.adm().id())).isEqualTo("7|HIGH|15|OPEN|1");
        SqlErr lower = ownerErr("ipd.ews_escalation",
                "update ipd.ews_escalation set ews_score = 6, ews_risk = 'HIGH', target_minutes = 15 where admission_id = '" + w.adm().id() + "'");
        assertThat(lower.state()).isEqualTo("23514");
        assertThat(lower.message()).contains("score cannot go down");
    }

    // ---------------- the escalation: update grant and guard ----------------

    @Test
    void sahajAppMayUpdateOnlyTheTenGrantedColumnsOfAnEscalationAndTheGuardOrACheckAnswersForTheNewSixOnTheirOwn()
            throws Exception {
        World w = world();
        String id = w.adm().id();
        // the four SAH-30 granted columns together are accepted (rolled back)
        appOk(w.clinic(), "update ipd.ews_escalation set ews_score = 6, ews_risk = 'MEDIUM', target_minutes = 60, "
                + "version = version + 1 where admission_id = ?::uuid", id);
        // not granted (SAH-33 keeps the times with the database): 42501, the privilege answers before the guard
        for (String set : new String[] {"raised_at = now()", "raised_by = gen_random_uuid()", "acknowledged_at = now()",
                "closed_at = now()", "reopened_at = now()", "admission_id = gen_random_uuid()",
                "tenant_id = gen_random_uuid()", "id = gen_random_uuid()"}) {
            SqlErr e = appErr(w.clinic(), "update ipd.ews_escalation set " + set + " where admission_id = ?::uuid", id);
            assertThat(e.state()).as(set).isEqualTo("42501");
            assertThat(e.message()).as(set).contains(DENIED);
        }
        // the six columns SAH-33 grants are no longer 42501: the guard (never a check) answers a wrong use
        for (String set : new String[] {"status = 'ACK'", "acknowledged_by = gen_random_uuid()",
                "response_action = 'BEDSIDE_REVIEW'", "response_note = 'x'", "closed_by = gen_random_uuid()",
                "status = 'DONE'", "senior_calls = 5"}) {
            SqlErr e = appErr(w.clinic(), "update ipd.ews_escalation set " + set + " where admission_id = ?::uuid", id);
            assertThat(e.state()).as(set).isEqualTo("23514");
            assertThat(e.message()).as(set).contains("escalation").doesNotContain(DENIED);
        }
        // and a granted column together with a not granted one is refused as a whole (privilege first)
        assertThat(appErr(w.clinic(), "update ipd.ews_escalation set ews_score = 6, ews_risk = 'MEDIUM', "
                + "target_minutes = 60, closed_at = now() where admission_id = ?::uuid", id).state()).isEqualTo("42501");
        // the one new column that is accepted on its own: a senior call on an open escalation (rolled back)
        appOk(w.clinic(), "update ipd.ews_escalation set senior_calls = senior_calls + 1 where admission_id = ?::uuid", id);
        assertThat(openEscalation(w.clinic(), id)).isEqualTo("3|MEDIUM|60|OPEN|0");
        assertThat(escalationColumn(w.clinic(), id, "senior_calls")).isEqualTo("0");
    }

    @Test
    void theGuardTriggerRefusesALowerScoreAHighChangingToMediumAndAnyChangeToADoneRow() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        Adm high = admitOne(clinic);
        save(clinic, high.id(), high7());
        assertThat(openEscalation(clinic, high.id())).isEqualTo("7|HIGH|15|OPEN|0");

        SqlErr lower = appErr(clinic, "update ipd.ews_escalation set ews_score = 6, ews_risk = 'HIGH', target_minutes = 15 "
                + "where admission_id = ?::uuid", high.id());
        assertThat(lower.state()).isEqualTo("23514");
        assertThat(lower.message()).contains("score cannot go down");
        SqlErr downgrade = appErr(clinic, "update ipd.ews_escalation set ews_score = 8, ews_risk = 'MEDIUM', "
                + "target_minutes = 60 where admission_id = ?::uuid", high.id());
        assertThat(downgrade.state()).isEqualTo("23514");
        assertThat(downgrade.message()).contains("risk cannot go down");
        // equal values pass, and a higher score passes with exactly version + 1 (positive controls, rolled back)
        appOk(clinic, "update ipd.ews_escalation set ews_score = 7 where admission_id = ?::uuid", high.id());
        appOk(clinic, "update ipd.ews_escalation set ews_score = 9, version = version + 1 where admission_id = ?::uuid",
                high.id());
        // SAH-33: the version goes up by exactly one with a change and only then
        SqlErr bumpOnly = appErr(clinic, "update ipd.ews_escalation set ews_score = 7, version = version + 1 "
                + "where admission_id = ?::uuid", high.id());
        assertThat(bumpOnly.state()).isEqualTo("23514");
        assertThat(bumpOnly.message()).contains("version changes only together with a change");
        SqlErr noBump = appErr(clinic, "update ipd.ews_escalation set ews_score = 9 where admission_id = ?::uuid", high.id());
        assertThat(noBump.state()).isEqualTo("23514");
        assertThat(noBump.message()).contains("version must go up by exactly one");
        // a Medium may go up to High, with version + 1
        appOk(clinic, "update ipd.ews_escalation set ews_score = 7, ews_risk = 'HIGH', target_minutes = 15, "
                + "version = version + 1 where admission_id = ?::uuid", w.adm().id());
        assertThat(appErr(clinic, "update ipd.ews_escalation set ews_score = 7, ews_risk = 'HIGH', target_minutes = 15 "
                + "where admission_id = ?::uuid", w.adm().id()).message()).contains("version must go up by exactly one");

        closeEscalation(clinic, high.id());
        for (String set : new String[] {"version = version + 1", "ews_score = 9", "ews_score = 7",
                "ews_score = 15, ews_risk = 'HIGH', target_minutes = 15"}) {
            SqlErr e = appErr(clinic, "update ipd.ews_escalation set " + set + " where admission_id = ?::uuid", high.id());
            assertThat(e.state()).as(set).isEqualTo("23514");
            assertThat(e.message()).as(set).contains("closed");
        }
        // the owner is stopped by the same guard
        SqlErr owner = ownerErr("ipd.ews_escalation", "update ipd.ews_escalation set version = version + 1 "
                + "where admission_id = ?::uuid", high.id());
        assertThat(owner.state()).isEqualTo("23514");
        // the helper closed it with exactly one more version
        assertThat(count(clinic, "select version from ipd.ews_escalation where admission_id = ?::uuid", high.id())).isOne();
    }

    @Test
    void theStateColumnsAreAnsweredByTheGuardFirstAndKeepTheirPairingChecksBehindIt() throws Exception {
        World w = world();
        String id = w.adm().id();
        String where = " where admission_id = ?::uuid";
        String update = "update ipd.ews_escalation set ";
        // First line (SAH-33): the guard trigger answers before any check, with SQLSTATE 23514 and "escalation"
        Object[][] guard = {
                {"status = 'DONE'", "version must go up by exactly one"},
                {"status = 'DONE', version = version + 1", "needs an action and a closing user"},
                {"closed_at = now()", "closed_at is set by the database"},
                {"acknowledged_by = gen_random_uuid()", "version must go up by exactly one"},
                {"acknowledged_by = gen_random_uuid(), version = version + 1", "cannot be acknowledged without moving"},
                {"status = 'GONE', version = version + 1", "status change is not allowed"},
                {"senior_calls = -1", "senior calls go up by one"}};
        for (Object[] g : guard) {
            SqlErr e = ownerErr("ipd.ews_escalation", update + g[0] + where, id);
            assertThat(e.state()).as((String) g[0]).isEqualTo("23514");
            assertThat(e.message()).as((String) g[0]).contains("escalation").contains((String) g[1]);
        }
        // Second line: the check constraints, proved with the trigger disabled in a rolled-back owner transaction.
        // A valid close from OPEN with one part taken out (or spoiled) breaks exactly one constraint.
        String g = "'11111111-1111-1111-1111-111111111111'";
        java.util.function.Function<String[], String> doneWithout = skip -> {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("status", "'DONE'");
            m.put("response_action", "'NO_CHANGE'");
            m.put("response_note", "'Plan'");
            m.put("closed_by", g);
            m.put("acknowledged_by", g);
            m.put("acknowledged_at", "now()");
            m.put("closed_at", "now()");
            for (String s : skip) {
                m.remove(s);
            }
            StringBuilder sb = new StringBuilder(update);
            m.forEach((k, v) -> sb.append(sb.length() > update.length() ? ", " : "").append(k).append(" = ").append(v));
            return sb + where;
        };
        Object[][] checks = {
                {doneWithout.apply(new String[] {"closed_at"}), "ck_ews_escalation_closed"},
                {update + "closed_at = now()" + where, "ck_ews_escalation_closed"},
                {doneWithout.apply(new String[] {"response_action"}), "ck_ews_escalation_done_action"},
                {doneWithout.apply(new String[] {"closed_by"}), "ck_ews_escalation_done_closed_by"},
                {doneWithout.apply(new String[] {"response_note"}), "ck_ews_escalation_response_note_done"},
                {doneWithout.apply(new String[] {"acknowledged_by", "acknowledged_at"}), "ck_ews_escalation_ack_by_status"},
                {update + "status = 'ACK', acknowledged_by = " + g + where, "ck_ews_escalation_ack_pair"},
                {doneWithout.apply(new String[] {}).replace("'NO_CHANGE'", "'BOGUS'"), "ck_ews_escalation_response_action"},
                {doneWithout.apply(new String[] {}).replace("'Plan'", "''"), "ck_ews_escalation_response_note"},
                {update + "reopened_at = raised_at - interval '1 second'" + where, "ck_ews_escalation_reopened"},
                {update + "status = 'GONE'" + where, "ck_ews_escalation_status"},
                {update + "senior_calls = -1" + where, "ck_ews_escalation_senior_calls"}};
        for (Object[] c : checks) {
            SqlErr e = ownerNoGuardErr((String) c[0], id);
            assertThat(e.state()).as((String) c[0]).isEqualTo("23514");
            assertThat(e.message()).as((String) c[0]).contains("\"" + c[1] + "\"");
        }
        SqlErr tooLong = ownerNoGuardErr(doneWithout.apply(new String[] {"response_note"}) .replace(" where", ", response_note = '"
                + "a".repeat(1001) + "' where"), id);
        assertThat(tooLong.message()).contains("\"ck_ews_escalation_response_note\"");
        // the trigger was only disabled inside the rolled-back transactions
        assertThat(ownerText("select tgenabled::text from pg_trigger where tgname = 'trg_ews_escalation_guard_update'"))
                .isEqualTo("O");
        assertThat(openEscalation(w.clinic(), id)).isEqualTo("3|MEDIUM|60|OPEN|0");
    }

    // ---------------- column-level insert grants ----------------

    @Test
    void anInsertThatNamesAColumnOutsideTheGrantIsRefusedOnEveryTableAndTheGrantedColumnsAreAccepted() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        Adm free = admitOne(clinic); // no vitals, no escalation
        String id = free.id();
        Object[][] extras = {
                // table, base columns, extra column, value
                {"ipd.vitals", vitalsCols(id), "id", UUID.randomUUID()},
                {"ipd.vitals", vitalsCols(id), "tenant_id", clinic.tenantId()},
                {"ipd.vitals", vitalsCols(id), "recorded_at", Timestamp.from(Instant.now().minusSeconds(3600))},
                {"ipd.nursing_note", noteCols(id), "id", UUID.randomUUID()},
                {"ipd.nursing_note", noteCols(id), "tenant_id", clinic.tenantId()},
                {"ipd.nursing_note", noteCols(id), "recorded_at", Timestamp.from(Instant.now().minusSeconds(3600))},
                {"ipd.clinical_event", eventCols(id), "id", UUID.randomUUID()},
                {"ipd.clinical_event", eventCols(id), "seq", 5L},
                {"ipd.clinical_event", eventCols(id), "tenant_id", clinic.tenantId()},
                {"ipd.clinical_event", eventCols(id), "at", Timestamp.from(Instant.now().minusSeconds(3600))},
                {"ipd.ews_escalation", escalationCols(id), "id", UUID.randomUUID()},
                {"ipd.ews_escalation", escalationCols(id), "tenant_id", clinic.tenantId()},
                {"ipd.ews_escalation", escalationCols(id), "status", "DONE"},
                {"ipd.ews_escalation", escalationCols(id), "raised_at", Timestamp.from(Instant.now().minusSeconds(3600))},
                {"ipd.ews_escalation", escalationCols(id), "acknowledged_by", UUID.randomUUID()},
                {"ipd.ews_escalation", escalationCols(id), "response_note", "x"},
                {"ipd.ews_escalation", escalationCols(id), "senior_calls", 2},
                {"ipd.ews_escalation", escalationCols(id), "closed_at", Timestamp.from(Instant.now())},
                {"ipd.ews_escalation", escalationCols(id), "version", 4}};
        for (Object[] e : extras) {
            @SuppressWarnings("unchecked")
            Map<String, Object> base = (Map<String, Object>) e[1];
            Map<String, Object> cols = with(base, new String[] {(String) e[2]}, new Object[] {e[3]});
            SqlErr err = insertErr(clinic, (String) e[0], cols);
            if ("seq".equals(e[2])) {
                // a GENERATED ALWAYS identity column refuses an explicit value (428C9) before the privilege check
                assertThat(err.state()).as("clinical_event.seq").isIn("42501", "428C9");
            } else {
                assertThat(err.state()).as(e[0] + "." + e[2]).isEqualTo("42501");
                assertThat(err.message()).as(e[0] + "." + e[2]).contains(DENIED);
            }
        }
        assertThat(ownerText("select has_column_privilege('sahaj_app', 'ipd.clinical_event', 'seq', 'INSERT')::text"))
                .as("sahaj_app has no insert privilege on seq").isEqualTo("false");
        // the granted columns alone are accepted (rolled back)
        insertOk(clinic, "ipd.vitals", vitalsCols(id));
        insertOk(clinic, "ipd.nursing_note", noteCols(id));
        insertOk(clinic, "ipd.clinical_event", eventCols(id));
        insertOk(clinic, "ipd.ews_escalation", escalationCols(id));
        assertThat(vitalsCount(clinic, id)).isZero();
    }

    @Test
    void theDatabaseFixesTheTimeStatusAndVersionOfAnInsertedRow() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        // what the API wrote carries database values
        assertThat(openEscalation(clinic, w.adm().id())).isEqualTo("3|MEDIUM|60|OPEN|0");
        assertThat(escalationColumn(clinic, w.adm().id(), "senior_calls")).isEqualTo("0");
        assertThat(escalationColumn(clinic, w.adm().id(), "(closed_at is null)")).isEqualTo("true");
        Instant recorded = instantOf(clinic, "select recorded_at from ipd.vitals where admission_id = ?::uuid",
                w.adm().id());
        assertThat(recorded).isBetween(Instant.now().minusSeconds(120), Instant.now().plusSeconds(5));
    }

    // ---------------- check constraints at their edges ----------------

    @Test
    void everyVitalsRangeIsEnforcedAtItsEdgesByTheDatabase() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        String id = w.adm().id();
        Object[][] bad = {
                {"bp_systolic", 39, "ck_vitals_bp_systolic"}, {"bp_systolic", 301, "ck_vitals_bp_systolic"},
                {"bp_diastolic", 19, "ck_vitals_bp_diastolic"}, {"bp_diastolic", 201, "ck_vitals_bp_diastolic"},
                {"pulse", 0, "ck_vitals_pulse"}, {"pulse", 301, "ck_vitals_pulse"},
                {"spo2", 0, "ck_vitals_spo2"}, {"spo2", 101, "ck_vitals_spo2"},
                {"temp_c", new BigDecimal("24.9"), "ck_vitals_temp_c"}, {"temp_c", new BigDecimal("45.1"), "ck_vitals_temp_c"},
                {"rr", 0, "ck_vitals_rr"}, {"rr", 301, "ck_vitals_rr"},
                {"ews_score", -1, "ck_vitals_ews_score"}, {"ews_risk", "NONE", "ck_vitals_ews_risk"}};
        for (Object[] b : bad) {
            SqlErr e = insertErr(clinic, "ipd.vitals", with(vitalsCols(id), new String[] {(String) b[0]}, new Object[] {b[1]}));
            assertThat(e.state()).as(b[0] + "=" + b[1]).isEqualTo("23514");
            assertThat(e.message()).as(b[0] + "=" + b[1]).contains((String) b[2]);
        }
        SqlErr high = insertErr(clinic, "ipd.vitals", with(vitalsCols(id), new String[] {"ews_score", "ews_risk"},
                new Object[] {16, "HIGH"}));
        assertThat(high.state()).isEqualTo("23514");
        assertThat(high.message()).contains("ck_vitals_ews_score");
        Object[][] good = {
                {"bp_systolic", 40}, {"bp_systolic", 300}, {"bp_diastolic", 20}, {"bp_diastolic", 200}, {"pulse", 1},
                {"pulse", 300}, {"spo2", 1}, {"spo2", 100}, {"temp_c", new BigDecimal("25.0")},
                {"temp_c", new BigDecimal("45.0")}, {"rr", 1}, {"rr", 300}};
        for (Object[] g : good) {
            insertOk(clinic, "ipd.vitals", with(vitalsCols(id), new String[] {(String) g[0]}, new Object[] {g[1]}));
        }
    }

    @Test
    void riskAndScoreMustAgreeInTheDatabaseButScoresBelowFiveMayStillBeMedium() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        String id = w.adm().id();
        Object[][] bad = {{7, "MEDIUM", "ck_vitals_risk_high"}, {15, "MEDIUM", "ck_vitals_risk_high"},
                {6, "HIGH", "ck_vitals_risk_high"}, {0, "HIGH", "ck_vitals_risk_high"}, {5, "LOW", "ck_vitals_risk_low"},
                {6, "LOW", "ck_vitals_risk_low"}};
        for (Object[] b : bad) {
            SqlErr e = insertErr(clinic, "ipd.vitals", with(vitalsCols(id), new String[] {"ews_score", "ews_risk"},
                    new Object[] {b[0], b[1]}));
            assertThat(e.state()).as(b[0] + " " + b[1]).isEqualTo("23514");
            assertThat(e.message()).as(b[0] + " " + b[1]).contains((String) b[2]);
        }
        Object[][] good = {{7, "HIGH"}, {15, "HIGH"}, {6, "MEDIUM"}, {5, "MEDIUM"}, {4, "LOW"}, {3, "MEDIUM"}, {0, "MEDIUM"},
                {0, "LOW"}};
        for (Object[] g : good) {
            insertOk(clinic, "ipd.vitals", with(vitalsCols(id), new String[] {"ews_score", "ews_risk"},
                    new Object[] {g[0], g[1]}));
        }
    }

    @Test
    void aClientKeyIsUniqueWithinAClinicButAnotherClinicMayUseTheSameKey() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        Map<String, Object> body = low();
        save(clinic, w.adm().id(), body);
        UUID key = UUID.fromString((String) body.get("clientKey"));
        SqlErr dup = insertErr(clinic, "ipd.vitals",
                with(vitalsCols(w.adm().id()), new String[] {"client_key"}, new Object[] {key}));
        assertThat(dup.state()).isEqualTo("23505");
        assertThat(dup.message()).contains("uq_vitals_tenant_client_key");
        // another clinic, same key: accepted, and the first clinic's row is not seen
        Clinic other = hospital();
        Adm theirs = admitOne(other);
        JsonNode saved = ok(org.springframework.http.HttpMethod.POST, vitalsUrl(theirs.id()), other.owner(), js(body), 201);
        assertThat(saved.get("saved").get("replayed").asBoolean()).isFalse();
        assertThat(vitalsCount(other, theirs.id())).isOne();
    }

    @Test
    void aNursingNoteHasOneToAThousandCharactersCountedAsCodePoints() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        String id = w.adm().id();
        String emoji = "😀";
        for (String bad : new String[] {"", "a".repeat(1001), emoji.repeat(1001)}) {
            SqlErr e = insertErr(clinic, "ipd.nursing_note", with(noteCols(id), new String[] {"note"}, new Object[] {bad}));
            assertThat(e.state()).as("length " + bad.length()).isEqualTo("23514");
            assertThat(e.message()).contains("ck_nursing_note_note");
        }
        for (String good : new String[] {"a", "a".repeat(1000), emoji.repeat(1000)}) {
            insertOk(clinic, "ipd.nursing_note", with(noteCols(id), new String[] {"note"}, new Object[] {good}));
        }
    }

    @Test
    void anEventLineHasOneToThreeHundredCharactersAndAKnownKindAndMayHaveNoAdmission() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        String id = w.adm().id();
        for (String bad : new String[] {"", "a".repeat(301)}) {
            SqlErr e = insertErr(clinic, "ipd.clinical_event", with(eventCols(id), new String[] {"text"}, new Object[] {bad}));
            assertThat(e.state()).isEqualTo("23514");
            assertThat(e.message()).contains("ck_clinical_event_text");
        }
        SqlErr kind = insertErr(clinic, "ipd.clinical_event", with(eventCols(id), new String[] {"kind"}, new Object[] {"bogus"}));
        assertThat(kind.state()).isEqualTo("23514");
        assertThat(kind.message()).contains("ck_clinical_event_kind");
        insertOk(clinic, "ipd.clinical_event", with(eventCols(id), new String[] {"text"}, new Object[] {"a".repeat(300)}));
        for (String k : new String[] {"give", "order", "verify", "esc", "view", "bg", "deny", "stop", "info"}) {
            insertOk(clinic, "ipd.clinical_event", with(eventCols(id), new String[] {"kind"}, new Object[] {k}));
        }
        // an event that belongs to no admission is allowed (the Safety board and the medicine log may write one)
        insertOk(clinic, "ipd.clinical_event", eventCols(null));
    }

    @Test
    void anEscalationHasRiskAndTargetThatAgreeAndAtMostOneOpenRowPerAdmission() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        Adm free = admitOne(clinic);
        String id = free.id();
        Object[][] bad = {
                {new String[] {"ews_risk", "target_minutes"}, new Object[] {"HIGH", 60}, "ck_ews_escalation_risk_target"},
                {new String[] {"ews_risk", "target_minutes"}, new Object[] {"MEDIUM", 15}, "ck_ews_escalation_risk_target"},
                {new String[] {"ews_risk"}, new Object[] {"LOW"}, "ck_ews_escalation_risk"},
                {new String[] {"target_minutes"}, new Object[] {30}, "ck_ews_escalation_target"},
                {new String[] {"ews_score"}, new Object[] {16}, "ck_ews_escalation_score"},
                {new String[] {"ews_score"}, new Object[] {-1}, "ck_ews_escalation_score"}};
        for (Object[] b : bad) {
            SqlErr e = insertErr(clinic, "ipd.ews_escalation", with(escalationCols(id), (String[]) b[0], (Object[]) b[1]));
            assertThat(e.state()).as(b[2].toString()).isEqualTo("23514");
            assertThat(e.message()).contains((String) b[2]);
        }
        insertOk(clinic, "ipd.ews_escalation", with(escalationCols(id), new String[] {"ews_risk", "target_minutes", "ews_score"},
                new Object[] {"HIGH", 15, 8}));
        insertOk(clinic, "ipd.ews_escalation", escalationCols(id));

        // a second open row for an admission that has one: 23505
        SqlErr second = insertErr(clinic, "ipd.ews_escalation", escalationCols(w.adm().id()));
        assertThat(second.state()).isEqualTo("23505");
        assertThat(second.message()).contains("ux_ews_escalation_tenant_admission_open");
        // after it is closed a new one is allowed; another admission was never blocked
        closeEscalation(clinic, w.adm().id());
        insertOk(clinic, "ipd.ews_escalation", escalationCols(w.adm().id()));
        assertThat(escalationCount(clinic, w.adm().id())).isOne();
    }

    // ---------------- the fix pass: V2026_11_25_1000 and V2026_11_25_1010 ----------------

    @Test
    void anEscalationScoreIsAtLeastThreeAndHighExactlyFromSevenByTwoChecksWithTheirOwnNames() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        String id = admitOne(clinic).id();
        String[] cols = {"ews_score", "ews_risk", "target_minutes"};
        // score_min: a score of 0, 1 or 2 is never escalated (the lowest Medium is 3: one parameter at 3)
        for (int low : new int[] {0, 1, 2}) {
            SqlErr e = insertErr(clinic, "ipd.ews_escalation", with(escalationCols(id), cols, new Object[] {low, "MEDIUM", 60}));
            assertThat(e.state()).as("score " + low).isEqualTo("23514");
            assertThat(e.message()).as("score " + low).contains("ck_ews_escalation_score_min");
        }
        // score_risk: High exactly from 7 (the renamed check; the old name must be gone)
        Object[][] mismatch = {{7, "MEDIUM", 60}, {8, "MEDIUM", 60}, {15, "MEDIUM", 60}, {6, "HIGH", 15}, {3, "HIGH", 15}};
        for (Object[] m : mismatch) {
            SqlErr e = insertErr(clinic, "ipd.ews_escalation", with(escalationCols(id), cols, m));
            assertThat(e.state()).as(m[0] + " " + m[1]).isEqualTo("23514");
            assertThat(e.message()).as(m[0] + " " + m[1]).contains("ck_ews_escalation_score_risk");
        }
        // a score outside 0 to 15 is still reported by the plain range check (it sorts first by name)
        assertThat(insertErr(clinic, "ipd.ews_escalation", with(escalationCols(id), cols, new Object[] {16, "HIGH", 15}))
                .message()).contains("ck_ews_escalation_score\"");
        // positive controls: the edges that are allowed
        Object[][] good = {{3, "MEDIUM", 60}, {4, "MEDIUM", 60}, {6, "MEDIUM", 60}, {7, "HIGH", 15}, {15, "HIGH", 15}};
        for (Object[] g : good) {
            insertOk(clinic, "ipd.ews_escalation", with(escalationCols(id), cols, g));
        }
        // the constraint names in the catalog: the new two exist, the first name of the fix pass is gone
        List<String> names = List.of(values(clinic, "select string_agg(conname, ',' order by conname) from pg_constraint "
                + "where conrelid = 'ipd.ews_escalation'::regclass and conname like 'ck_ews_escalation_%score%'").get(0).split(","));
        assertThat(names).contains("ck_ews_escalation_score", "ck_ews_escalation_score_min", "ck_ews_escalation_score_risk")
                .doesNotContain("ck_ews_escalation_risk_score");
    }

    @Test
    void theEscalationVersionIsABigintAndTheGuardRefusesAFallingVersionForAppAndOwner() throws Exception {
        World w = world();
        Clinic clinic = w.clinic();
        String id = w.adm().id();
        assertThat(values(clinic, "select data_type from information_schema.columns where table_schema = 'ipd' "
                + "and table_name = 'ews_escalation' and column_name = 'version'")).containsExactly("bigint");
        save(clinic, id, high7()); // the open escalation is now 7, High, version 1
        assertThat(openEscalation(clinic, id)).isEqualTo("7|HIGH|15|OPEN|1");

        // a lower version, with everything else equal: refused by the guard, SQLSTATE 23514
        for (String set : new String[] {"version = 0", "version = version - 1", "version = -5"}) {
            SqlErr e = appErr(clinic, "update ipd.ews_escalation set " + set + " where admission_id = ?::uuid", id);
            assertThat(e.state()).as(set).isEqualTo("23514");
            assertThat(e.message()).as(set).contains("version cannot go down");
        }
        // even together with a higher score (the version still must not fall)
        SqlErr withHigherScore = appErr(clinic, "update ipd.ews_escalation set ews_score = 9, version = 0 "
                + "where admission_id = ?::uuid", id);
        assertThat(withHigherScore.state()).isEqualTo("23514");
        assertThat(withHigherScore.message()).contains("version cannot go down");
        // the owner is stopped by the same guard
        SqlErr owner = ownerErr("ipd.ews_escalation", "update ipd.ews_escalation set version = 0 where admission_id = ?::uuid", id);
        assertThat(owner.state()).isEqualTo("23514");
        assertThat(owner.message()).contains("version cannot go down");
        // positive control (rolled back): an unchanged version is accepted
        appOk(clinic, "update ipd.ews_escalation set version = version where admission_id = ?::uuid", id);
        // SAH-33: a version that moves alone (one more, or beyond the range of an integer) is refused by the guard
        for (String set : new String[] {"version = version + 1", "version = 3000000000"}) {
            SqlErr e = appErr(clinic, "update ipd.ews_escalation set " + set + " where admission_id = ?::uuid", id);
            assertThat(e.state()).as(set).isEqualTo("23514");
            assertThat(e.message()).as(set).contains("version changes only together with a change");
        }
        // a change moves it by exactly one: one more is accepted, two more and a huge jump are refused
        appOk(clinic, "update ipd.ews_escalation set ews_score = 9, version = version + 1 where admission_id = ?::uuid", id);
        for (String set : new String[] {"ews_score = 9, version = version + 2", "ews_score = 9, version = 3000000000"}) {
            SqlErr e = appErr(clinic, "update ipd.ews_escalation set " + set + " where admission_id = ?::uuid", id);
            assertThat(e.state()).as(set).isEqualTo("23514");
            assertThat(e.message()).as(set).contains("version must go up by exactly one");
        }
        // the column really is a bigint: the owner, with the guard disabled in a rolled-back transaction, stores 3000000000
        String stored = asOwner(false, ESCALATION_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.ews_escalation disable trigger trg_ews_escalation_guard_update");
            DbAccess.execute(c, "update ipd.ews_escalation set version = 3000000000 where admission_id = ?::uuid", id);
            return DbAccess.text(c, "select version::text from ipd.ews_escalation where admission_id = ?::uuid", id);
        });
        assertThat(stored).isEqualTo("3000000000");
        assertThat(openEscalation(clinic, id)).as("nothing of this was kept").isEqualTo("7|HIGH|15|OPEN|1");
    }

    // ---------------- other clinics ----------------

    @Test
    void anotherClinicsAdmissionCannotBePointedAtAndItsRowsAreInvisible() throws Exception {
        World a = world();
        Clinic b = hospital();
        String theirs = a.adm().id();
        for (String[] t : new String[][] {{"ipd.vitals"}, {"ipd.nursing_note"}, {"ipd.clinical_event"}, {"ipd.ews_escalation"}}) {
            Map<String, Object> cols = switch (t[0]) {
                case "ipd.vitals" -> vitalsCols(theirs);
                case "ipd.nursing_note" -> noteCols(theirs);
                case "ipd.clinical_event" -> eventCols(theirs);
                default -> escalationCols(theirs);
            };
            SqlErr e = insertErr(b, t[0], cols);
            assertThat(e.state()).as(t[0]).isIn("23503", "42501");
        }
        // B sees none of A's rows, A still sees its own
        for (String table : new String[] {"vitals", "nursing_note", "clinical_event", "ews_escalation"}) {
            assertThat(rows(b, table)).as(table + " seen by the other clinic").isZero();
            assertThat(rows(a.clinic(), table)).as(table + " seen by its clinic").isPositive();
        }
        // with no clinic bound the app role sees nothing and writes nothing
        long unboundVitals = db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from ipd.vitals"));
        long unboundEvents = db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from ipd.clinical_event"));
        assertThat(unboundVitals).isZero();
        assertThat(unboundEvents).isZero();
        SqlErr unbound = appErrNoTenant(insertSql("ipd.vitals", vitalsCols(theirs)), valuesOf(vitalsCols(theirs)));
        assertThat(unbound.state()).isNotNull();
        // an event with no admission belongs to the clinic that wrote it only
        insertOkCommitted(b, eventCols(null));
        assertThat(rows(b, "clinical_event")).isOne();
        assertThat(rows(a.clinic(), "clinical_event")).isEqualTo(2);
    }

    private void insertOkCommitted(Clinic clinic, Map<String, Object> columns) throws SQLException {
        commitAsTenant(clinic.tenantId(), insertSql("ipd.clinical_event", columns), valuesOf(columns));
    }
}
