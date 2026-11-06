package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-33 integration tests (Safety board, the three escalation actions, the clinical audit trail,
 * the discharge rule): real staff with their own user id and role (so "two doctors" are two people), the urls and bodies
 * of the contract written out here (never read from the application), the state of an escalation read back by SQL, an
 * escalation or an event inserted by the table owner with chosen times (the application cannot write a time), and
 * snapshots of everything a refused or repeated call must leave as it was.
 *
 * <p>The contract texts used by the assertions are copied from {@code docs/api/ipd.yaml} and ADR-007, not read from
 * the application.
 */
abstract class SafetyTestSupport extends VitalsSupport {

    static final String SAFETY = IPD + "/safety";
    static final String SUMMARY = SAFETY + "/summary";
    static final String EVENTS = IPD + "/clinical-events";

    static final String[] BOARD_ROLES = {"OWNER", "DOCTOR", "NURSE"};
    static final String[] NON_BOARD_ROLES = {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"};
    static final String[] NON_DOCTOR_ROLES = {"NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"};
    static final String[] NON_NURSE_ROLES = {"OWNER", "DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST",
            "COUNSELLOR"};

    static final String RELOAD = "Reload the page and try again.";
    static final String ALREADY_CLOSED = "This escalation is already closed.";
    static final String PLAN_REQUIRED = "Write the plan and when vitals are next due.";
    static final String PLAN_LONG = "Keep the plan to 1,000 characters.";
    static final String PLAN_CHARS = "Plan cannot contain control or text-direction characters";
    static final String CHOOSE_ACTION = "Choose what was done";
    static final String ESCALATION_OPEN = "This patient has an early warning escalation that is not closed. "
            + "A doctor must record the response first.";
    static final String[][] ACTIONS = {{"BEDSIDE_REVIEW", "Reviewed at the bedside"}, {"TREATMENT_CHANGED", "Treatment changed"},
            {"MOVED_ICU_HDU", "Moved to ICU or HDU"}, {"RAPID_RESPONSE", "Rapid response team called"},
            {"NO_CHANGE", "Reviewed, no change needed"}};

    // ---------------- urls ----------------

    protected static String ackUrl(Object id) {
        return IPD + "/escalations/" + id + "/acknowledge";
    }

    protected static String respondUrl(Object id) {
        return IPD + "/escalations/" + id + "/respond";
    }

    protected static String callUrl(Object id) {
        return IPD + "/escalations/" + id + "/call-senior";
    }

    // ---------------- people ----------------

    /** A real person of the clinic: own user id, a name that is easy to search for, the role in the membership and the token. */
    protected record Staff(UUID userId, String name, String role, RequestPostProcessor auth) {
    }

    /** Adds a team member through the real endpoint with a name of our choice and signs a token for the real user. */
    protected Staff staff(Clinic clinic, String role, String name) throws Exception {
        String mobile = uniqueMobile();
        String doctor = role.contains("DOCTOR")
                ? ",\"doctor\":{\"specialty\":\"General physician\",\"feeRupees\":250,\"degree\":\"MBBS\",\"registrationNo\":\"R1\"}"
                : "";
        JsonNode created = ok(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(),
                "{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\",\"role\":\"" + role + "\"" + doctor + "}", 201);
        String membershipId = created.get("id").asText();
        UUID user = UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                membershipId).get(0));
        return new Staff(user, name, role, tokenOf(clinic, user, role));
    }

    protected Staff staff(Clinic clinic, String role) throws Exception {
        return staff(clinic, role, role.charAt(0) + role.substring(1).toLowerCase().replace('_', ' ') + " "
                + UUID.randomUUID().toString().substring(0, 6));
    }

    /** The owner as a person: the user id of the sign-up and the name the directory shows for it. */
    protected Staff ownerStaff(Clinic clinic) throws SQLException {
        return new Staff(clinic.ownerUserId(), nameOf(clinic, clinic.ownerUserId()), "OWNER", clinic.owner());
    }

    /** The name the staff directory shows for a user: the membership display name, else the user's full name. */
    protected String nameOf(Clinic clinic, UUID userId) throws SQLException {
        return values(clinic, "select coalesce(m.display_name, u.full_name) from platform.membership m "
                + "join platform.app_user u on u.id = m.user_id where m.tenant_id = ?::uuid and m.user_id = ?::uuid",
                clinic.tenantId().toString(), userId.toString()).get(0);
    }

    // ---------------- request bodies and calls ----------------

    protected ResultActions ack(RequestPostProcessor auth, Object escalationId, Object version) throws Exception {
        return call(HttpMethod.POST, ackUrl(escalationId), auth, "{\"version\":" + version + "}");
    }

    protected static String respondJson(Object version, String action, String note) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("action", action);
        m.put("note", note);
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    }

    protected ResultActions respond(RequestPostProcessor auth, Object escalationId, Object version, String action,
                                    String note) throws Exception {
        return call(HttpMethod.POST, respondUrl(escalationId), auth, respondJson(version, action, note));
    }

    protected ResultActions callSenior(RequestPostProcessor auth, Object escalationId, Object version, Object calls)
            throws Exception {
        return call(HttpMethod.POST, callUrl(escalationId), auth, "{\"version\":" + version + ",\"seniorCalls\":" + calls + "}");
    }

    protected JsonNode ackOk(RequestPostProcessor auth, Object escalationId, Object version) throws Exception {
        return ok(HttpMethod.POST, ackUrl(escalationId), auth, "{\"version\":" + version + "}", 200);
    }

    protected JsonNode respondOk(RequestPostProcessor auth, Object escalationId, Object version, String action, String note)
            throws Exception {
        return ok(HttpMethod.POST, respondUrl(escalationId), auth, respondJson(version, action, note), 200);
    }

    protected JsonNode callOk(RequestPostProcessor auth, Object escalationId, Object version, Object calls) throws Exception {
        return ok(HttpMethod.POST, callUrl(escalationId), auth,
                "{\"version\":" + version + ",\"seniorCalls\":" + calls + "}", 200);
    }

    protected JsonNode boardOf(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, SAFETY, auth, null, 200);
    }

    protected JsonNode summaryOf(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, SUMMARY, auth, null, 200);
    }

    protected JsonNode eventsOf(RequestPostProcessor auth, String query) throws Exception {
        return ok(HttpMethod.GET, EVENTS + (query == null ? "" : "?" + query), auth, null, 200);
    }

    /** The row of an escalation on a board answer, or null. */
    protected static JsonNode escRow(JsonNode board, String escalationId) {
        for (JsonNode r : board.get("escalations")) {
            if (escalationId.equals(r.get("id").asText())) {
                return r;
            }
        }
        return null;
    }

    protected static List<String> rowIds(JsonNode board) {
        List<String> ids = new ArrayList<>();
        board.get("escalations").forEach(r -> ids.add(r.get("id").asText()));
        return ids;
    }

    /** A problem body that must be a 409 of the given type. */
    protected JsonNode conflict(ResultActions actions, String type) throws Exception {
        JsonNode p = problem(actions, 409);
        assertType(p, type);
        return p;
    }

    // ---------------- raising and reading escalations ----------------

    /** Saves the vitals (owner) and returns the id of the open escalation. */
    protected String raise(Clinic clinic, Adm adm, Map<String, Object> body) throws Exception {
        save(clinic, adm.id(), body);
        return openEscalationId(clinic, adm.id());
    }

    /** One escalation as stored, read by SQL. */
    protected record Esc(String id, String status, long version, int score, String risk, int target, int seniorCalls,
                         String ackBy, String closedBy, String action, String note, boolean hasAckAt, boolean hasClosedAt,
                         boolean hasReopenedAt) {
    }

    protected Esc esc(Clinic clinic, String escalationId) throws SQLException {
        List<String> v = values(clinic, "select status, version::text, ews_score::text, ews_risk, target_minutes::text, "
                + "senior_calls::text, coalesce(acknowledged_by::text, ''), coalesce(closed_by::text, ''), "
                + "coalesce(response_action, ''), coalesce(response_note, ''), (acknowledged_at is not null)::text, "
                + "(closed_at is not null)::text, (reopened_at is not null)::text from ipd.ews_escalation where id = ?::uuid",
                escalationId);
        return new Esc(escalationId, v.get(0), Long.parseLong(v.get(1)), Integer.parseInt(v.get(2)), v.get(3),
                Integer.parseInt(v.get(4)), Integer.parseInt(v.get(5)), v.get(6), v.get(7), v.get(8), v.get(9),
                Boolean.parseBoolean(v.get(10)), Boolean.parseBoolean(v.get(11)), Boolean.parseBoolean(v.get(12)));
    }

    /** Everything an action may write, of the whole clinic: events, audit rows, record access, and the escalation rows. */
    protected record Side(long events, long audits, long access, String escalations) {
    }

    protected Side side(Clinic clinic) throws SQLException {
        return new Side(rows(clinic, "clinical_event"), allAudits(clinic), count(clinic, "select count(*) from patient.record_access"),
                values(clinic, "select coalesce(string_agg(id::text || status || version || senior_calls || ews_score || "
                        + "coalesce(acknowledged_by::text, '-') || coalesce(response_note, '-') || (closed_at is null) || "
                        + "(reopened_at is null) || (acknowledged_at is null), ',' order by id), '') from ipd.ews_escalation").get(0));
    }

    /** The texts of the esc events of an admission, newest first. */
    protected List<String> escEvents(Clinic clinic, String admissionId) throws SQLException {
        List<String> out = new ArrayList<>();
        for (String line : eventLines(clinic, admissionId)) {
            if (line.startsWith("esc|")) {
                out.add(line.substring(4));
            }
        }
        return out;
    }

    // ---------------- things only the table owner can write ----------------

    /**
     * Inserts an escalation as the table owner (committed) with the times and state we choose: the application cannot
     * write a time. {@code status} OPEN or ACK (ACK gets {@code ackBy} and an acknowledgement time of {@code raisedAt}).
     * Returns the id. One not-closed escalation per admission is still the rule of the database.
     */
    protected String insertEscalation(Clinic clinic, String admissionId, int score, String risk, Instant raisedAt,
                                      Instant reopenedAt, UUID ackBy) throws SQLException {
        int target = "HIGH".equals(risk) ? 15 : 60;
        return asOwner(true, ESCALATION_TABLES, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into ipd.ews_escalation (tenant_id, admission_id, "
                    + "ews_score, ews_risk, target_minutes, raised_by, raised_at, reopened_at, status, acknowledged_by, "
                    + "acknowledged_at) values (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id::text")) {
                ps.setObject(1, clinic.tenantId());
                ps.setString(2, admissionId);
                ps.setInt(3, score);
                ps.setString(4, risk);
                ps.setInt(5, target);
                ps.setObject(6, clinic.ownerUserId());
                ps.setTimestamp(7, Timestamp.from(raisedAt));
                ps.setTimestamp(8, reopenedAt == null ? null : Timestamp.from(reopenedAt));
                ps.setString(9, ackBy == null ? "OPEN" : "ACK");
                ps.setObject(10, ackBy);
                ps.setTimestamp(11, ackBy == null ? null : Timestamp.from(raisedAt));
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    /** An admission with an escalation raised {@code secondsAgo} seconds ago; the escalation is OPEN. */
    protected String openEscalationAgo(Clinic clinic, Adm adm, int score, String risk, long secondsAgo) throws SQLException {
        return insertEscalation(clinic, adm.id(), score, risk, Instant.now().minusSeconds(secondsAgo), null, null);
    }

    /**
     * Inserts {@code n} clinical events as the table owner, all with the same time {@code at} (the application writes
     * every event of one save with one instant, so ties are real). Texts are "Recorded vitals · EWS 1" and so on in
     * insert order, so {@code seq} rises with the number.
     */
    protected void insertEvents(Clinic clinic, String admissionId, UUID actor, Instant at, int n) throws SQLException {
        asOwner(true, new String[] {"ipd.clinical_event"}, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into ipd.clinical_event (tenant_id, admission_id, kind, "
                    + "text, actor, at) select ?, ?::uuid, 'info', 'Recorded vitals ' || chr(183) || ' EWS ' || g, ?, ? "
                    + "from generate_series(1, ?) g")) {
                ps.setObject(1, clinic.tenantId());
                ps.setString(2, admissionId);
                ps.setObject(3, actor);
                ps.setTimestamp(4, Timestamp.from(at));
                ps.setInt(5, n);
                assertThat(ps.executeUpdate()).isEqualTo(n);
            }
            return null;
        });
    }

    /** The database clock, read as sahaj_app. */
    protected Instant dbNow(Clinic clinic) throws SQLException {
        return instantOf(clinic, "select clock_timestamp()");
    }

    /** One statement as the table owner (rolled back) on the escalation and admission tables; returns the error. */
    protected SqlErr ownerEscalationErr(String sql, Object... params) throws SQLException {
        try {
            asOwner(false, ESCALATION_TABLES, c -> {
                DbAccess.execute(c, sql, params);
                return null;
            });
        } catch (SQLException e) {
            return new SqlErr(e.getSQLState(), e.getMessage());
        }
        throw new AssertionError("the owner was allowed: " + sql);
    }

    /** A second connection as sahaj_app with the tenant bound for the whole session (the caller closes it). */
    protected Connection tenantConnection(Clinic clinic) throws SQLException {
        Connection c = db().app();
        c.setAutoCommit(false);
        try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            bind.setString(1, clinic.tenantId().toString());
            bind.execute();
        }
        c.commit(); // a session setting made in a transaction that is rolled back would be undone with it
        return c;
    }

    // ---------------- review fixes (ADR-007 R1 to R5) ----------------

    /** The first column of every row (the older {@code values} helper returns the columns of the first row only). */
    protected List<String> column(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    List<String> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                    return out;
                }
            }
        });
    }

    /** SHA-256 of the empty text, first 16 bytes, base64url without padding: the change token of a clinic with no live escalation. */
    static final String EMPTY_TOKEN = "47DEQpj8HBSa-_TImW-5JA";

    /**
     * The change token worked out here from the rule of ADR-007 R1 (not read from the application): the escalations
     * that are OPEN or ACK on ADMITTED admissions, sorted by the lower-case id text, the lines "id:version" joined by a
     * line feed, SHA-256, the first 16 bytes, base64url without padding.
     */
    protected String expectedChangeToken(Clinic clinic) throws Exception {
        List<String> lines = new ArrayList<>(column(clinic, "select lower(e.id::text) || ':' || e.version::text "
                + "from ipd.ews_escalation e join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id "
                + "where e.status in ('OPEN', 'ACK') and a.status = 'ADMITTED'"));
        java.util.Collections.sort(lines);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
    }

    /** A real team member who signed in by OTP (a genuine session token), with the user id of the membership. */
    protected record Person(UUID userId, UUID membershipId, RequestPostProcessor auth) {
    }

    protected Person realPerson(Clinic clinic, String role) throws Exception {
        Member member = addMember(clinic, role);
        RequestPostProcessor token = bearer(login(member.mobile()).accessToken());
        UUID user = UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                member.membershipId().toString()).get(0));
        return new Person(user, member.membershipId(), token);
    }

    /**
     * Moves a closed escalation back in time as the table owner with the guard trigger switched off inside the
     * transaction (a closed row is final for the application and, through the trigger, for the owner too): raised,
     * acknowledged and closed so that it was closed {@code minutesAgo} minutes ago (raised an hour before that). Committed.
     */
    protected void ageClosedEscalation(Clinic clinic, String escalationId, long minutesAgo) throws SQLException {
        ageClosedEscalation(clinic, escalationId, minutesAgo, 60);
    }

    /** As above, with the escalation open for {@code openMinutes} minutes before it was closed. */
    protected void ageClosedEscalation(Clinic clinic, String escalationId, long minutesAgo, long openMinutes) throws SQLException {
        asOwner(true, ESCALATION_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.ews_escalation disable trigger trg_ews_escalation_guard_update");
            try (PreparedStatement ps = c.prepareStatement("update ipd.ews_escalation set raised_at = ?, "
                    + "acknowledged_at = ?, closed_at = ? where id = ?::uuid and status = 'DONE'")) {
                Instant closed = Instant.now().minusSeconds(minutesAgo * 60);
                ps.setTimestamp(1, Timestamp.from(closed.minusSeconds(openMinutes * 60)));
                ps.setTimestamp(2, Timestamp.from(closed.minusSeconds(openMinutes * 30)));
                ps.setTimestamp(3, Timestamp.from(closed));
                ps.setString(4, escalationId);
                assertThat(ps.executeUpdate()).as("closed escalation aged").isOne();
            }
            DbAccess.execute(c, "alter table ipd.ews_escalation enable trigger trg_ews_escalation_guard_update");
            return null;
        });
    }

    /** Sets the stored senior call count of an escalation as the table owner with the guard switched off in the transaction. */
    protected void forceSeniorCalls(Clinic clinic, String escalationId, int calls) throws SQLException {
        asOwner(true, ESCALATION_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.ews_escalation disable trigger trg_ews_escalation_guard_update");
            try (PreparedStatement ps = c.prepareStatement("update ipd.ews_escalation set senior_calls = ? where id = ?::uuid")) {
                ps.setInt(1, calls);
                ps.setString(2, escalationId);
                assertThat(ps.executeUpdate()).isOne();
            }
            DbAccess.execute(c, "alter table ipd.ews_escalation enable trigger trg_ews_escalation_guard_update");
            return null;
        });
    }

    /** The board's escalation rows for one clinic, as "status" of each row, in order. */
    protected static List<String> statuses(JsonNode board) {
        List<String> out = new ArrayList<>();
        board.get("escalations").forEach(r -> out.add(r.get("status").asText()));
        return out;
    }
}
