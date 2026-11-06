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
 * Shared set-up of the SAH-28 integration tests (emergency queue, triage, Emergency setup, the admit link): a hospital
 * with Trial IPD, registrations and actions through the real API, arrival times moved into the past as the table owner
 * (the application cannot write {@code arrived_at}), the exact messages of the contract, and state read back by SQL.
 *
 * <p>Default setup: 3 colours with targets 5 / 30 / 120 minutes, 5 levels with 0 / 10 / 30 / 60 / 120 minutes, 2 bays,
 * Unknown patient registration on.
 */
abstract class EmergencySupport extends BedTransferSupport {

    static final String ER = IPD + "/emergency";
    static final String ER_CASES = ER + "/cases";
    static final String ER_SUMMARY = ER + "/summary";
    static final String ER_SETTINGS = IPD + "/emergency-settings";

    static final String CLOSED = "This patient is no longer in the queue.";
    static final String MODE_OFF = "In-patients (IPD) is not switched on for your clinic.";
    static final String NAME_REQUIRED = "Enter a name, or tick \"Name not known yet\"";
    static final String COMPLAINT_REQUIRED = "Enter the main complaint";
    static final String LEVEL_MESSAGE = "Choose a triage level";

    static final String[] ALL_ROLES = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST",
            "COUNSELLOR"};
    /** Read the queue and the settings, register, re-triage. */
    static final String[] ER_ROLES = READERS;
    static final String[] NOT_ER_ROLES = NON_READERS;
    /** Doctor has seen, Treat and release, Refer out. */
    static final String[] ACTORS = {"OWNER", "NURSE", "DOCTOR"};
    static final String[] NOT_ACTORS = {"FRONT_DESK", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};
    /** Admit (the roles of SAH-23). */
    static final String[] ADMITTERS = CHARGERS;
    static final String[] NOT_ADMITTERS = {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};

    // ---------------- bodies ----------------

    /** A valid registration of a known patient: Asha Patil, 40, F, no mobile, chest pain, the given level. */
    protected Map<String, Object> caseBody(String name, int level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("identityUnknown", false);
        m.put("name", name);
        m.put("age", 40);
        m.put("sex", "F");
        m.put("mobile", null);
        m.put("complaint", "Chest pain for 1 hour");
        m.put("level", level);
        return m;
    }

    protected Map<String, Object> unknownBody(String sex, Integer age, int level) {
        Map<String, Object> m = caseBody(null, level);
        m.put("identityUnknown", true);
        m.put("sex", sex);
        m.put("age", age);
        return m;
    }

    protected ResultActions registerCall(RequestPostProcessor auth, Map<String, Object> body) throws Exception {
        return call(HttpMethod.POST, ER_CASES, auth, js(body));
    }

    /** A registration by the owner that must be accepted (201); returns the whole queue answer. */
    protected JsonNode register(Clinic clinic, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, ER_CASES, clinic.owner(), js(body), 201);
    }

    /** Registers a known patient of the level; returns the new case (actedCase). */
    protected JsonNode reg(Clinic clinic, String name, int level) throws Exception {
        return register(clinic, caseBody(name, level)).get("actedCase");
    }

    protected JsonNode queue(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, ER, clinic.owner(), null, 200);
    }

    protected JsonNode queueAs(Clinic clinic, String role) throws Exception {
        return ok(HttpMethod.GET, ER, clinic.as(role), null, 200);
    }

    protected static String caseUrl(Object caseId, String action) {
        return ER_CASES + "/" + caseId + "/" + action;
    }

    protected ResultActions act(RequestPostProcessor auth, Object caseId, String action) throws Exception {
        return call(action.equals("level") ? HttpMethod.PUT : HttpMethod.POST, caseUrl(caseId, action), auth,
                action.equals("level") ? "{\"level\":1}" : null);
    }

    protected JsonNode actOk(Clinic clinic, Object caseId, String action) throws Exception {
        return ok(HttpMethod.POST, caseUrl(caseId, action), clinic.owner(), null, 200);
    }

    protected JsonNode seen(Clinic clinic, Object caseId) throws Exception {
        return actOk(clinic, caseId, "seen");
    }

    protected JsonNode release(Clinic clinic, Object caseId) throws Exception {
        return actOk(clinic, caseId, "release");
    }

    protected JsonNode refer(Clinic clinic, Object caseId) throws Exception {
        return actOk(clinic, caseId, "refer");
    }

    protected JsonNode retriage(Clinic clinic, Object caseId, int level) throws Exception {
        return ok(HttpMethod.PUT, caseUrl(caseId, "level"), clinic.owner(), "{\"level\":" + level + "}", 200);
    }

    protected ResultActions retriageCall(RequestPostProcessor auth, Object caseId, String json) throws Exception {
        return call(HttpMethod.PUT, caseUrl(caseId, "level"), auth, json);
    }

    protected static String settingsJson(int scale, String targets3, String targets5, int bays, boolean unknown) {
        return "{\"scale\":" + scale + ",\"targets3\":" + targets3 + ",\"targets5\":" + targets5 + ",\"bays\":" + bays
                + ",\"unknownPatientRegistration\":" + unknown + "}";
    }

    protected static final String T3 = "[5,30,120]";
    protected static final String T5 = "[0,10,30,60,120]";

    protected JsonNode putSetup(Clinic clinic, int scale, String t3, String t5, int bays, boolean unknown) throws Exception {
        return ok(HttpMethod.PUT, ER_SETTINGS, clinic.owner(), settingsJson(scale, t3, t5, bays, unknown), 200);
    }

    protected JsonNode setScale(Clinic clinic, int scale) throws Exception {
        return putSetup(clinic, scale, T3, T5, 2, true);
    }

    protected JsonNode setBays(Clinic clinic, int bays) throws Exception {
        return putSetup(clinic, 3, T3, T5, bays, true);
    }

    protected JsonNode settings(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, ER_SETTINGS, clinic.owner(), null, 200);
    }

    // ---------------- reading the queue answer ----------------

    protected static JsonNode caseIn(JsonNode queue, Object caseId) {
        for (JsonNode c : queue.get("cases")) {
            if (caseId.toString().equals(c.get("id").asText())) {
                return c;
            }
        }
        return null;
    }

    protected static List<String> ids(JsonNode queue) {
        List<String> out = new ArrayList<>();
        queue.get("cases").forEach(c -> out.add(c.get("id").asText()));
        return out;
    }

    protected static List<String> names(JsonNode queue) {
        List<String> out = new ArrayList<>();
        queue.get("cases").forEach(c -> out.add(c.get("displayName").asText()));
        return out;
    }

    protected static List<String> fieldsOf(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.get("fieldErrors").forEach(e -> out.add(e.get("field").asText()));
        return out;
    }

    protected static String messageOf(JsonNode problem, String field) {
        for (JsonNode e : problem.get("fieldErrors")) {
            if (field.equals(e.get("field").asText())) {
                return e.get("message").asText();
            }
        }
        return null;
    }

    protected static void assertCaseClosed(JsonNode problem) {
        assertType(problem, "case-closed");
        assertThat(problem.get("detail").asText()).isEqualTo(CLOSED);
    }

    // ---------------- arrival times in the past ----------------

    /**
     * Moves {@code ipd.emergency_case.arrived_at} (committed). {@code sahaj_app} cannot write it and the migrator is
     * subject to FORCE row level security, so, as {@link IpdBillingSupport#backdate} does for an admission, this runs
     * in ONE transaction as the table owner: NO FORCE, the update, FORCE again.
     */
    protected void arrivedAt(Clinic clinic, Object caseId, Instant at) throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try {
                DbAccess.execute(c, "set local lock_timeout = '30s'");
                DbAccess.execute(c, "alter table ipd.emergency_case no force row level security");
                try (PreparedStatement u = c.prepareStatement(
                        "update ipd.emergency_case set arrived_at = ? where id = ?::uuid and tenant_id = ?")) {
                    u.setTimestamp(1, Timestamp.from(at));
                    u.setString(2, caseId.toString());
                    u.setObject(3, clinic.tenantId());
                    assertThat(u.executeUpdate()).as("rows moved").isEqualTo(1);
                }
                DbAccess.execute(c, "alter table ipd.emergency_case force row level security");
                c.commit();
            } catch (SQLException | RuntimeException | AssertionError e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * The case arrived {@code minutes} whole minutes and 30 seconds ago, so that it has waited exactly {@code minutes}
     * minutes (rounded down) for the next half minute, whatever the speed of the test.
     */
    protected void waited(Clinic clinic, Object caseId, int minutes) throws SQLException {
        arrivedAt(clinic, caseId, Instant.now().minusSeconds(minutes * 60L + 30L));
    }

    /** Registers a case of the level that has already waited {@code minutes}. */
    protected JsonNode regWaited(Clinic clinic, String name, int level, int minutes) throws Exception {
        JsonNode c = reg(clinic, name, level);
        waited(clinic, c.get("id").asText(), minutes);
        return c;
    }

    // ---------------- state read by SQL ----------------

    protected String col(Clinic clinic, Object caseId, String column) throws SQLException {
        return values(clinic, "select " + column + "::text from ipd.emergency_case where id = ?::uuid", caseId.toString()).get(0);
    }

    protected long caseRows(Clinic clinic) throws SQLException {
        return rows(clinic, "emergency_case");
    }

    protected long eventRows(Clinic clinic) throws SQLException {
        return rows(clinic, "emergency_triage_event");
    }

    protected long erAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_ER\\_%'");
    }

    protected long allAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event");
    }

    /** The events of a case, oldest first, as "REASON from>to scale". */
    protected List<String> events(Clinic clinic, Object caseId) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select reason || ' ' || coalesce(from_level::text, '-') || '>' || level "
                    + "|| ' s' || scale from ipd.emergency_triage_event where case_id = ?::uuid order by recorded_at, id")) {
                ps.setString(1, caseId.toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    /** Everything a refused or repeated call must leave exactly as it was. */
    protected record Written(long cases, long events, long erAudits, long audits, long admissions, String dump,
                             long access) {
    }

    protected Written written(Clinic clinic) throws SQLException {
        return new Written(caseRows(clinic), eventRows(clinic), erAudits(clinic), allAudits(clinic), rows(clinic, "admission"),
                values(clinic, "select coalesce(string_agg(id::text || status || level || coalesce(bay, '-') || version || "
                        + "coalesce(seen_at::text, '-') || coalesce(closed_at::text, '-') || coalesce(patient_id::text, '-') "
                        + "|| coalesce(admission_id::text, '-'), ',' order by id), '') from ipd.emergency_case").get(0),
                count(clinic, "select count(*) from patient.record_access"));
    }

    // ---------------- admit ----------------

    protected Map<String, Object> admitBody(Clinic clinic, UUID patient, UUID bed, Object caseId) {
        Map<String, Object> m = body(patient, clinic.doctorMembershipId(), bed);
        if (caseId != null) {
            m.put("emergencyCaseId", caseId.toString());
        }
        return m;
    }

    protected ResultActions admitCall(RequestPostProcessor auth, Map<String, Object> body) throws Exception {
        return call(HttpMethod.POST, ADMISSIONS, auth, js(body));
    }

    protected String sourceOf(Clinic clinic, String admissionId) throws SQLException {
        return values(clinic, "select source from ipd.admission where id = ?::uuid", admissionId).get(0);
    }

    protected JsonNode optionsFor(Clinic clinic, String role, Object caseId) throws Exception {
        return ok(HttpMethod.GET, OPTIONS + "?emergencyCaseId=" + caseId, clinic.as(role), null, 200);
    }

    /** No credentials at all. */
    protected static RequestPostProcessor anonymous() {
        return request -> request;
    }
}
