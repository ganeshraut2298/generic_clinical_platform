package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-30 integration tests (nursing vitals, nursing notes, early warning score and escalation):
 * a hospital with Trial IPD and admitted patients through the real API, saves through the real endpoint, the exact
 * texts of the contract written out here (never read from the application), state read back by SQL, plain SQL
 * statements as {@code sahaj_app} and as the table owner, vitals dated in the past, an escalation closed or
 * acknowledged as the owner with the statements the SAH-33 guard accepts, and a forced database failure.
 *
 * <p>Owner access: {@code sahaj_migrator} owns the tables but they are FORCE row level security, so (as
 * {@link IpdBillingSupport#backdate} does) every owner statement runs in ONE transaction with {@code NO FORCE}, the
 * statement and {@code FORCE} again. DDL is transactional in PostgreSQL, so other sessions never see an unforced
 * table. The append-only triggers still fire for the owner, so an old vitals row is INSERTed with an explicit
 * {@code recorded_at}, never updated.
 */
abstract class VitalsSupport extends DischargeSupport {

    // ---------------- the texts of the contract ----------------

    static final String FORMAT = "Enter all five vitals. BP as 120/80.";
    static final String BP_RANGE = "Check the BP. Systolic 40 to 300, diastolic 20 to 200.";
    static final String PULSE_RANGE = "Check the pulse. Enter 1 to 300 per minute.";
    static final String SPO2_RANGE = "Check the SpO₂. Enter 1 to 100 %.";
    static final String TEMP_RANGE = "Check the temperature. Enter 25.0 to 45.0 °C.";
    static final String RR_RANGE = "Check the RR. Enter 1 to 300 per minute.";
    static final String NOTE_CHARS = "Note cannot contain control or text-direction characters";
    static final String NOTE_LONG = "Keep the note to 1,000 characters.";
    static final String KEY_MESSAGE = "Reload the page and try again.";
    static final String VITALS_NOT_ADMITTED = "This patient is not admitted any more. Vitals cannot be saved.";
    static final String DOT = "·";

    static final String[] NURSING_VIEWERS = {"OWNER", "NURSE", "DOCTOR"};
    static final String[] NURSING_NON_VIEWERS = {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"};
    static final String[] NURSING_RECORDERS = {"OWNER", "NURSE"};
    static final String[] NURSING_NON_RECORDERS = {"DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST",
            "COUNSELLOR"};

    protected static String nursingUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/nursing";
    }

    protected static String vitalsUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/vitals";
    }

    // ---------------- admissions ----------------

    /** One admitted patient: the admission id, the patient and the identifying data that must never leak. */
    protected record Adm(String id, UUID patientId, String name, String mobile, String bedCode) {
    }

    /** A patient with a name that is easy to search for, on a new General bed. */
    protected Adm admitNamed(Clinic clinic, String name) throws Exception {
        Person p = person(clinic, name);
        JsonNode a = admitOk(clinic, body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "gen")));
        return new Adm(a.get("id").asText(), p.id(), name, p.mobile(), a.get("bedCode").asText());
    }

    protected Adm admitOne(Clinic clinic) throws Exception {
        return admitNamed(clinic, "Zubin Wadkar " + uniqueMobile().substring(5));
    }

    // ---------------- request bodies ----------------

    /** A body with the five typed values (as text, like the five text fields) and a fresh client key. */
    protected static Map<String, Object> vit(String bp, String pulse, String spo2, String temp, String rr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("bp", bp);
        m.put("pulse", pulse);
        m.put("spo2", spo2);
        m.put("temp", temp);
        m.put("rr", rr);
        return m;
    }

    /** Score 0, Low. */
    protected static Map<String, Object> low() {
        return vit("120/80", "80", "98", "37.0", "16");
    }

    /** Only SpO2 91 scores (3): total 3, Medium by the "any one parameter at 3" rule; target 1 hour. */
    protected static Map<String, Object> medium3() {
        return vit("120/80", "80", "91", "37.0", "16");
    }

    /** SpO2 93 (2) + RR 22 (2) + pulse 95 (1) = 5: Medium by the total. */
    protected static Map<String, Object> medium5() {
        return vit("120/80", "95", "93", "37.0", "22");
    }

    /** SpO2 91 (3) + RR 22 (2) + pulse 95 (1) = 6: Medium. */
    protected static Map<String, Object> medium6() {
        return vit("120/80", "95", "91", "37.0", "22");
    }

    /** SpO2 91 (3) + RR 22 (2) + pulse 115 (2) = 7: High; target 15 minutes. */
    protected static Map<String, Object> high7() {
        return vit("120/80", "115", "91", "37.0", "22");
    }

    /** Systolic 90 (3) + pulse 131 (3) + SpO2 91 (3) + RR 25 (3) + temp 39.1 (2) = 14: High. */
    protected static Map<String, Object> high14() {
        return vit("90/60", "131", "91", "39.1", "25");
    }

    protected static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put(field, value);
        return m;
    }

    protected static Map<String, Object> noted(Map<String, Object> body, String note) {
        return with(body, "note", note);
    }

    protected static Map<String, Object> newKey(Map<String, Object> body) {
        return with(body, "clientKey", UUID.randomUUID().toString());
    }

    // ---------------- calls ----------------

    protected ResultActions saveCall(RequestPostProcessor auth, Object admissionId, Map<String, Object> body)
            throws Exception {
        return call(HttpMethod.POST, vitalsUrl(admissionId), auth, js(body));
    }

    protected ResultActions saveRaw(RequestPostProcessor auth, Object admissionId, String json) throws Exception {
        return call(HttpMethod.POST, vitalsUrl(admissionId), auth, json);
    }

    /** A save by the owner that must be accepted as new (201). */
    protected JsonNode save(Clinic clinic, Object admissionId, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, vitalsUrl(admissionId), clinic.owner(), js(body), 201);
    }

    protected JsonNode saveAs(Clinic clinic, String role, Object admissionId, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, vitalsUrl(admissionId), clinic.as(role), js(body), 201);
    }

    /** A save the contract refuses with a 400: returns the problem body. */
    protected JsonNode refused(Clinic clinic, Object admissionId, Map<String, Object> body) throws Exception {
        JsonNode p = problem(saveCall(clinic.owner(), admissionId, body), 400);
        assertType(p, "validation");
        return p;
    }

    protected ResultActions sheetCall(RequestPostProcessor auth, Object admissionId) throws Exception {
        return call(HttpMethod.GET, nursingUrl(admissionId), auth, null);
    }

    protected JsonNode nurseSheet(Clinic clinic, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, nursingUrl(admissionId), clinic.owner(), null, 200);
    }

    protected JsonNode nurseSheetAs(Clinic clinic, String role, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, nursingUrl(admissionId), clinic.as(role), null, 200);
    }

    /** No credentials at all. */
    protected static RequestPostProcessor noSession() {
        return request -> request;
    }

    // ---------------- reading answers ----------------

    protected static List<String> fieldNames(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.get("fieldErrors").forEach(e -> out.add(e.get("field").asText()));
        return out;
    }

    protected static String fieldMessage(JsonNode problem, String field) {
        for (JsonNode e : problem.get("fieldErrors")) {
            if (field.equals(e.get("field").asText())) {
                return e.get("message").asText();
            }
        }
        return null;
    }

    protected static Instant instant(JsonNode node) {
        return Instant.parse(node.asText());
    }

    /** The risk the contract gives a total and the parameter scores (written from the story, not from the code). */
    protected static String riskOf(int total, boolean anyParameterAtThree) {
        if (total >= 7) {
            return "HIGH";
        }
        return total >= 5 || anyParameterAtThree ? "MEDIUM" : "LOW";
    }

    // ---------------- state read by SQL ----------------

    protected long vitalsCount(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.vitals where admission_id = ?::uuid", admissionId);
    }

    protected long noteCount(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.nursing_note where admission_id = ?::uuid", admissionId);
    }

    protected long eventCount(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.clinical_event where admission_id = ?::uuid", admissionId);
    }

    protected long escalationCount(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.ews_escalation where admission_id = ?::uuid", admissionId);
    }

    protected long openEscalationCount(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.ews_escalation where admission_id = ?::uuid and status <> 'DONE'",
                admissionId);
    }

    protected long vitalsAudits(Clinic clinic) throws SQLException {
        return audits(clinic, "IPD_VITALS_RECORDED");
    }

    /** The open escalation as "score|risk|target|status|version"; null when none is open. */
    protected String openEscalation(Clinic clinic, String admissionId) throws SQLException {
        List<String> v = values(clinic, "select ews_score || '|' || ews_risk || '|' || target_minutes || '|' || status || '|' "
                + "|| version from ipd.ews_escalation where admission_id = ?::uuid and status <> 'DONE'", admissionId);
        return v.isEmpty() ? null : v.get(0);
    }

    protected String openEscalationId(Clinic clinic, String admissionId) throws SQLException {
        List<String> v = values(clinic, "select id::text from ipd.ews_escalation where admission_id = ?::uuid "
                + "and status <> 'DONE'", admissionId);
        return v.isEmpty() ? null : v.get(0);
    }

    protected String escalationColumn(Clinic clinic, String admissionId, String column) throws SQLException {
        return values(clinic, "select " + column + "::text from ipd.ews_escalation where admission_id = ?::uuid "
                + "and status <> 'DONE'", admissionId).get(0);
    }

    /** The events of an admission as "kind|text", newest first (the reading order: at desc, seq desc). */
    protected List<String> eventLines(Clinic clinic, String admissionId) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select kind || '|' || text from ipd.clinical_event "
                    + "where admission_id = ?::uuid order by at desc, seq desc")) {
                ps.setString(1, admissionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    protected Instant instantOf(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, OffsetDateTime.class).toInstant();
                }
            }
        });
    }

    /** Everything a refused or repeated call must leave exactly as it was. */
    protected record Snap(long vitals, long notes, long events, long escalations, long audits, long access,
                          String dump) {
    }

    /** The same snapshot with {@code n} more record-access rows (a replay and a sheet open write one each, nothing else). */
    protected static Snap plusAccess(Snap s, long n) {
        return new Snap(s.vitals(), s.notes(), s.events(), s.escalations(), s.audits(), s.access() + n, s.dump());
    }

    protected Snap snap(Clinic clinic, Adm adm) throws SQLException {
        return new Snap(vitalsCount(clinic, adm.id()), noteCount(clinic, adm.id()), eventCount(clinic, adm.id()),
                escalationCount(clinic, adm.id()), allAudits(clinic), recordAccess(clinic, adm.patientId()),
                values(clinic, "select coalesce(string_agg(id::text || ews_score || ews_risk || target_minutes || status "
                        + "|| version || raised_at::text, ',' order by id), '') from ipd.ews_escalation "
                        + "where admission_id = ?::uuid", adm.id()).get(0));
    }

    protected long allAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event");
    }

    // ---------------- plain SQL, as sahaj_app and as the table owner ----------------

    /** What a statement raised. */
    protected record SqlErr(String state, String message) {
    }

    /** Runs the statement as sahaj_app with the tenant bound and returns the error; fails when it is accepted. */
    protected SqlErr appErr(Clinic clinic, String sql, Object... params) throws SQLException {
        try {
            db().inTenant(clinic.tenantId(), c -> {
                DbAccess.execute(c, sql, params);
                return null;
            });
        } catch (SQLException e) {
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        throw new AssertionError("the statement was accepted: " + sql);
    }

    /** Runs the statement as sahaj_app with the tenant bound (rolled back); it must be accepted. */
    protected void appOk(Clinic clinic, String sql, Object... params) throws SQLException {
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql, params);
            return null;
        });
    }

    /** Statement as sahaj_app with no tenant bound at all. */
    protected SqlErr appErrNoTenant(String sql, Object... params) throws SQLException {
        try {
            db().asApp(c -> {
                DbAccess.execute(c, sql, params);
                return null;
            });
        } catch (SQLException e) {
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        throw new AssertionError("the statement was accepted: " + sql);
    }

    /**
     * Runs the work as the table owner with row level security switched off on the tables, in one transaction. With
     * {@code commit} false everything (including the NO FORCE) is rolled back.
     */
    protected <T> T asOwner(boolean commit, String[] tables, DbAccess.SqlWork<T> work) throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try {
                DbAccess.execute(c, "set local lock_timeout = '30s'");
                for (String t : tables) {
                    DbAccess.execute(c, "alter table " + t + " no force row level security");
                }
                T result = work.apply(c);
                for (String t : tables) {
                    DbAccess.execute(c, "alter table " + t + " force row level security");
                }
                if (commit) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException | AssertionError e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** One statement as the table owner (rolled back); returns the error, fails when it is accepted. */
    protected SqlErr ownerErr(String table, String sql, Object... params) throws SQLException {
        try {
            asOwner(false, new String[] {table}, c -> {
                DbAccess.execute(c, sql, params);
                return null;
            });
        } catch (SQLException e) {
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        throw new AssertionError("the owner was allowed: " + sql);
    }

    /**
     * One statement as the table owner with the escalation guard trigger disabled in a transaction that is rolled back
     * (DDL is transactional): proves the check constraints, the second line behind the trigger. Returns the error; fails
     * when the statement is accepted.
     */
    protected SqlErr ownerNoGuardErr(String sql, Object... params) throws SQLException {
        return asOwner(false, ESCALATION_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.ews_escalation disable trigger trg_ews_escalation_guard_update");
            java.sql.Savepoint savepoint = c.setSavepoint();
            try {
                DbAccess.execute(c, sql, params);
            } catch (SQLException e) {
                c.rollback(savepoint);
                return new SqlErr(e.getSQLState(), e.getMessage());
            }
            throw new AssertionError("the owner was allowed without the guard: " + sql);
        });
    }

    /** Column map to INSERT statement text. */
    protected static String insertSql(String table, Map<String, Object> columns) {
        return "insert into " + table + " (" + String.join(", ", columns.keySet()) + ") values ("
                + "?, ".repeat(columns.size() - 1) + "?)";
    }

    protected static Object[] valuesOf(Map<String, Object> columns) {
        return columns.values().toArray();
    }

    protected SqlErr insertErr(Clinic clinic, String table, Map<String, Object> columns) throws SQLException {
        return appErr(clinic, insertSql(table, columns), valuesOf(columns));
    }

    protected void insertOk(Clinic clinic, String table, Map<String, Object> columns) throws SQLException {
        appOk(clinic, insertSql(table, columns), valuesOf(columns));
    }

    /** Default columns of a valid vitals row: 120/80, 80, 98, 37.0, 16, score 0 Low. */
    protected static Map<String, Object> vitalsCols(String admissionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("admission_id", UUID.fromString(admissionId));
        m.put("client_key", UUID.randomUUID());
        m.put("bp_systolic", 120);
        m.put("bp_diastolic", 80);
        m.put("pulse", 80);
        m.put("spo2", 98);
        m.put("temp_c", new BigDecimal("37.0"));
        m.put("rr", 16);
        m.put("ews_score", 0);
        m.put("ews_risk", "LOW");
        m.put("note_added", false);
        m.put("recorded_by", UUID.randomUUID());
        return m;
    }

    protected static Map<String, Object> noteCols(String admissionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("admission_id", UUID.fromString(admissionId));
        m.put("note", "Rested well");
        m.put("recorded_by", UUID.randomUUID());
        return m;
    }

    /** {@code admissionId} may be null: an event that belongs to no admission. */
    protected static Map<String, Object> eventCols(String admissionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("admission_id", admissionId == null ? null : UUID.fromString(admissionId));
        m.put("kind", "info");
        m.put("text", "Recorded vitals " + DOT + " EWS 0 (low)");
        m.put("actor", UUID.randomUUID());
        return m;
    }

    protected static Map<String, Object> escalationCols(String admissionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("admission_id", UUID.fromString(admissionId));
        m.put("ews_score", 3);
        m.put("ews_risk", "MEDIUM");
        m.put("target_minutes", 60);
        m.put("raised_by", UUID.randomUUID());
        return m;
    }

    protected static Map<String, Object> with(Map<String, Object> columns, String[] names, Object[] values) {
        Map<String, Object> m = new LinkedHashMap<>(columns);
        for (int i = 0; i < names.length; i++) {
            m.put(names[i], values[i]);
        }
        return m;
    }

    // ---------------- things only the owner of the tables can do ----------------

    /**
     * Inserts a vitals row dated {@code at} as the table owner (committed). The application cannot write
     * {@code recorded_at} and the append-only trigger refuses an UPDATE for every role, so an old reading is INSERTed.
     */
    protected void insertVitalsAt(Clinic clinic, String admissionId, Instant at) throws SQLException {
        asOwner(true, new String[] {"ipd.vitals"}, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into ipd.vitals (tenant_id, admission_id, client_key, "
                    + "bp_systolic, bp_diastolic, pulse, spo2, temp_c, rr, ews_score, ews_risk, note_added, recorded_at, "
                    + "recorded_by) values (?, ?::uuid, ?, 120, 80, 80, 98, 37.0, 16, 0, 'LOW', false, ?, ?)")) {
                ps.setObject(1, clinic.tenantId());
                ps.setString(2, admissionId);
                ps.setObject(3, UUID.randomUUID());
                ps.setTimestamp(4, Timestamp.from(at));
                ps.setObject(5, clinic.ownerUserId());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            return null;
        });
    }

    /** Vitals recorded {@code minutes} whole minutes and 30 seconds ago, so they read {@code minutes} for 30 seconds. */
    protected void vitalsAged(Clinic clinic, String admissionId, int minutes) throws SQLException {
        insertVitalsAt(clinic, admissionId, Instant.now().minusSeconds(minutes * 60L + 30L));
    }

    /** The tables the escalation guard reads: the trigger looks at the admission, so the owner must see it too. */
    protected static final String[] ESCALATION_TABLES = {"ipd.ews_escalation", "ipd.admission"};

    /**
     * Closes the open escalation with a doctor's response, as the table owner, with the statement the guard trigger
     * accepts (docs/database/SAH-33.md): status DONE, action, plan, closing user and version + 1. From OPEN the
     * acknowledging user must be the closing user ({@code coalesce} keeps the acknowledging doctor of an ACK row).
     * The three times are stamped by the trigger and never sent. {@code ipd.admission} is listed too because the
     * guard reads the admission and, under FORCE row level security, the owner would see none.
     */
    protected void closeEscalation(Clinic clinic, String admissionId) throws SQLException {
        asOwner(true, ESCALATION_TABLES, c -> {
            try (PreparedStatement ps = c.prepareStatement("update ipd.ews_escalation set status = 'DONE', "
                    + "response_action = 'BEDSIDE_REVIEW', response_note = 'Closed by the test helper', closed_by = ?, "
                    + "acknowledged_by = coalesce(acknowledged_by, ?), version = version + 1 "
                    + "where tenant_id = ? and admission_id = ?::uuid and status <> 'DONE'")) {
                ps.setObject(1, clinic.ownerUserId());
                ps.setObject(2, clinic.ownerUserId());
                ps.setObject(3, clinic.tenantId());
                ps.setString(4, admissionId);
                assertThat(ps.executeUpdate()).as("escalations closed").isEqualTo(1);
            }
            return null;
        });
    }

    /**
     * Acknowledges the open escalation as the table owner with the statement the guard accepts: status ACK, the
     * acknowledging user and version + 1 (the trigger stamps {@code acknowledged_at}).
     */
    protected void acknowledgeEscalation(Clinic clinic, String admissionId) throws SQLException {
        asOwner(true, ESCALATION_TABLES, c -> {
            try (PreparedStatement ps = c.prepareStatement("update ipd.ews_escalation set status = 'ACK', "
                    + "acknowledged_by = ?, version = version + 1 where tenant_id = ? and admission_id = ?::uuid "
                    + "and status = 'OPEN'")) {
                ps.setObject(1, clinic.ownerUserId());
                ps.setObject(2, clinic.tenantId());
                ps.setString(3, admissionId);
                assertThat(ps.executeUpdate()).as("escalations acknowledged").isEqualTo(1);
            }
            return null;
        });
    }

    /**
     * Inserts one {@code ipd.ews_senior_call} row as the table owner (committed) with a chosen {@code called_at}: the
     * application cannot write a time, and the table is append-only, so this is the only way to make a past entry (the
     * throttle of 60 seconds is read against the database clock). {@code callNo} is null for ALREADY_CALLED.
     */
    protected void insertSeniorCall(Clinic clinic, String escalationId, UUID calledBy, String outcome, Integer callNo,
                                    Instant calledAt) throws SQLException {
        asOwner(true, new String[] {"ipd.ews_senior_call", "ipd.ews_escalation"}, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into ipd.ews_senior_call (tenant_id, escalation_id, "
                    + "called_by, outcome, call_no, called_at) values (?, ?::uuid, ?, ?, ?, ?)")) {
                ps.setObject(1, clinic.tenantId());
                ps.setString(2, escalationId);
                ps.setObject(3, calledBy);
                ps.setString(4, outcome);
                ps.setObject(5, callNo);
                ps.setTimestamp(6, java.sql.Timestamp.from(calledAt));
                assertThat(ps.executeUpdate()).isOne();
            }
            return null;
        });
    }

    // ---------------- a replay, a role change, a lock wait ----------------

    /** A repeat of a stored client key with the same values: 200. */
    protected JsonNode replay(Clinic clinic, Object admissionId, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, vitalsUrl(admissionId), clinic.owner(), js(body), 200);
    }

    protected static final String KEY_REUSED_DETAIL =
            "This reading was already saved with different values. Reload the page and try again.";

    /**
     * The user of the clinic's added doctor membership. The only owner cannot be demoted (the database keeps one active
     * owner), so the role-change tests use this user with a token role of their own.
     */
    protected UUID doctorUserId(Clinic clinic) throws SQLException {
        return UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                clinic.doctorMembershipId()).get(0));
    }

    /** A token for a user of the clinic with the given role claim (the membership may say something else). */
    protected static RequestPostProcessor tokenOf(Clinic clinic, UUID userId, String role) {
        return com.sahaj.platform.support.TestTenantSecurity.asUser(clinic.tenantId(), userId, role);
    }

    /**
     * Changes the role of one membership (committed), as a membership change by an administrator would, while the
     * access token of that user still carries the old role.
     */
    protected void setMembershipRole(Clinic clinic, UUID userId, String role) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "update platform.membership set role = ? where tenant_id = ?::uuid and user_id = ?::uuid")) {
                ps.setString(1, role);
                ps.setString(2, clinic.tenantId().toString());
                ps.setString(3, userId.toString());
                assertThat(ps.executeUpdate()).as("membership rows changed").isEqualTo(1);
            }
            c.commit();
        }
    }

    /** Sessions of this database that wait on a lock inside a statement whose text matches the LIKE pattern. */
    protected long lockWaiters(String likePattern) throws SQLException {
        return db().asApp(c -> DbAccess.count(c, "select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock' and pid <> pg_backend_pid() and query like '" + likePattern + "'"));
    }

    /** Polls until at least {@code n} sessions wait on a lock in a matching statement; false after the time is up. */
    protected boolean awaitLockWaiters(String likePattern, long n, long millis) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (lockWaiters(likePattern) >= n) {
                return true;
            }
            Thread.sleep(25);
        }
        return lockWaiters(likePattern) >= n;
    }

    /** The statement a save, a transfer and a discharge use to lock the admission row. */
    protected static final String ADMISSION_LOCK_NO_KEY = "%from ipd.admission%for no key update%";
    protected static final String ADMISSION_LOCK_UPDATE = "%from ipd.admission%for update%";

    // ---------------- a forced database failure ----------------

    @FunctionalInterface
    protected interface Action {
        void run() throws Exception;
    }

    /**
     * Runs the action while a trigger makes every INSERT into {@code table} of this clinic that matches {@code when}
     * (a SQL condition on NEW) fail, then removes the trigger. The trigger is limited to the clinic, so no other
     * test is touched. The function and trigger are created and dropped as the table owner.
     */
    protected void failingInsert(Clinic clinic, String table, String when, Action action) throws Exception {
        String function = "ipd.it_fail_insert_" + clinic.tenantId().toString().replace('-', '_');
        String trigger = "trg_it_fail_" + clinic.tenantId().toString().replace('-', '_');
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ "
                    + "begin raise exception 'forced failure for the rollback test'; end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before insert on " + table + " for each row when "
                    + "(new.tenant_id = '" + clinic.tenantId() + "'::uuid and " + when + ") execute function " + function
                    + "()");
            try {
                action.run();
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on " + table);
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
    }
}
