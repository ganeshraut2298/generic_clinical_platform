package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-34 integration tests (IPD ward scoping and break-glass emergency access) on the real
 * {@code sahaj_test} database: a hospital with two wards, real nurses of their own (own user id, own membership, token role
 * and membership role NURSE) assigned to wards through the real owner endpoint, patients on beds of a chosen ward, the clinic
 * switch set through the real endpoint, and a grant that is already close to its end (the table owner switches the stamping
 * trigger off for one insert, the technique of docs/database/SAH-34.md).
 *
 * <p>The texts and urls of the assertions are copied from {@code docs/api/ipd.yaml} and the SAH-34 design note, not read
 * from the application. Test plan: docs/test-plans/SAH-34.md.
 */
abstract class ScopeSupport extends RoundSupport {

    static final String EA = IPD + "/emergency-access";
    static final String EA_PICKER = EA + "/patients";
    static final String WARD_SCOPE = IPD + "/ward-scope";
    static final String WARD_ASSIGNMENTS = IPD + "/ward-assignments";

    static final String OUTSIDE = "This patient is not in your wards.";
    static final String OUTSIDE_TITLE = "Outside your scope";
    static final String EA_ONLY = "Emergency access is only for staff who see some wards.";
    static final String ALREADY_IN_LIST = "This patient is already in your list.";
    static final String ALREADY_REVIEWED_TEXT = "Already reviewed.";
    static final String OWN_REVIEW_TEXT = "You cannot review your own emergency access.";
    static final String CHOOSE_PATIENT_TEXT = "Choose a patient.";
    static final String CHOOSE_REASON_TEXT = "Choose a reason for emergency access.";
    static final String TICK_TEXT = "Tick the box to confirm this access is recorded.";
    static final String CHOOSE_NURSES_TEXT = "Choose nurses from the list.";
    static final String NURSE_WITHOUT_WARD_TEXT = "Assign a ward to every nurse before you switch this on.";
    static final String MALFORMED_TITLE = "Request could not be read";
    static final String MALFORMED_DETAIL = "The request body is missing or is not valid JSON.";

    static final String[][] EA_REASONS = {{"DETERIORATING", "Patient deteriorating, rapid response"},
        {"COVERING_BREAK", "Covering for a colleague on break"}, {"DUTY_DOCTOR_ASKED", "Asked to help by the duty doctor"}};

    static final String[] GRANT_TABLES = {"ipd.emergency_access_grant", "ipd.admission", "ipd.bed", "ipd.clinical_event"};
    static final String[] ASSIGNMENT_TABLES = {"ipd.ward_assignment", "ipd.ward"};

    /** Two wards, two nurses (one per ward), a patient in each ward and the switch on. */
    protected record Hosp(Clinic clinic, String wardA, String wardB, Pn nurseA, Pn nurseB, Adm inA, Adm inB) {
    }

    // ---------------- wards, beds, patients ----------------

    protected String wardIdByName(Clinic clinic, String name) throws Exception {
        for (JsonNode w : layout(clinic).get("wards")) {
            if (name.equals(w.get("name").asText())) {
                return w.get("id").asText();
            }
        }
        throw new AssertionError("no ward " + name);
    }

    protected String newWard(Clinic clinic, String name) throws Exception {
        ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"" + name + "\"}", 201);
        return wardIdByName(clinic, name);
    }

    private static Set<String> bedIdsOf(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        layout.get("wards").forEach(w -> w.get("beds").forEach(b -> ids.add(b.get("id").asText())));
        return ids;
    }

    /** A new General bed in the given ward. */
    protected UUID bedIn(Clinic clinic, String wardId) throws Exception {
        Set<String> known = bedIdsOf(layout(clinic));
        Set<String> now = bedIdsOf(ok(HttpMethod.POST, BEDS, clinic.owner(), "{\"wardId\":\"" + wardId + "\"}", 201));
        now.removeAll(known);
        assertThat(now).hasSize(1);
        return UUID.fromString(now.iterator().next());
    }

    protected Adm admitIn(Clinic clinic, String wardId, String name) throws Exception {
        String mobile = uniqueMobile();
        java.util.Map<String, Object> patient = new java.util.LinkedHashMap<>();
        patient.put("name", name);
        patient.put("age", 56);
        patient.put("sex", "F");
        patient.put("mobile", mobile);
        patient.put("area", "Shivaji Nagar");
        patient.put("language", "mr");
        patient.put("allergy", "None known");
        UUID patientId = UUID.fromString(ok(HttpMethod.POST, PATIENTS, clinic.owner(), js(patient), 201).get("id").asText());
        JsonNode a = admitOk(clinic, body(patientId, clinic.doctorMembershipId(), bedIn(clinic, wardId)));
        return new Adm(a.get("id").asText(), patientId, name, mobile, a.get("bedCode").asText());
    }

    protected Adm admitInNamed(Clinic clinic, String wardId, String first) throws Exception {
        return admitIn(clinic, wardId, first + " " + uniqueMobile().substring(5));
    }

    // ---------------- the owner endpoints of 34A ----------------

    protected static String idsJson(UUID... membershipIds) {
        StringBuilder sb = new StringBuilder("{\"membershipIds\":[");
        for (int i = 0; i < membershipIds.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(membershipIds[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    protected JsonNode assign(Clinic clinic, String wardId, Pn... nurses) throws Exception {
        UUID[] ids = new UUID[nurses.length];
        for (int i = 0; i < nurses.length; i++) {
            ids[i] = nurses[i].membershipId();
        }
        return ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + wardId, clinic.owner(), idsJson(ids), 200);
    }

    protected ResultActions assignCall(RequestPostProcessor auth, Object wardId, String json) throws Exception {
        return call(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + wardId, auth, json);
    }

    protected JsonNode switchScope(Clinic clinic, boolean on) throws Exception {
        return ok(HttpMethod.PUT, WARD_SCOPE, clinic.owner(), "{\"on\":" + on + "}", 200);
    }

    protected ResultActions switchCall(RequestPostProcessor auth, String json) throws Exception {
        return call(HttpMethod.PUT, WARD_SCOPE, auth, json);
    }

    protected Hosp hosp() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        String b = newWard(clinic, "Surgical ward");
        Pn nurseA = newPn(clinic, "NURSE");
        Pn nurseB = newPn(clinic, "NURSE");
        assign(clinic, a, nurseA);
        assign(clinic, b, nurseB);
        Adm inA = admitInNamed(clinic, a, "Asha Ward-A");
        Adm inB = admitInNamed(clinic, b, "Bhim Ward-B");
        switchScope(clinic, true);
        return new Hosp(clinic, a, b, nurseA, nurseB, inA, inB);
    }

    // ---------------- reading ----------------

    protected JsonNode listOf(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, ADMISSIONS, auth, null, 200);
    }

    protected static List<String> idsOf(JsonNode list) {
        List<String> ids = new java.util.ArrayList<>();
        list.get("items").forEach(r -> ids.add(r.get("id").asText()));
        return ids;
    }

    /** Expects 403 outside-scope with the fixed body and nothing else in it. */
    protected JsonNode outside(ResultActions actions) throws Exception {
        JsonNode p = problem(actions, 403);
        assertType(p, "outside-scope");
        assertThat(p.get("title").asText()).isEqualTo(OUTSIDE_TITLE);
        assertThat(p.get("detail").asText()).isEqualTo(OUTSIDE);
        return p;
    }

    /** Expects a 403 that is not outside-scope (the role answer comes first, AC 14). */
    protected JsonNode forbidden(ResultActions actions) throws Exception {
        JsonNode p = problem(actions, 403);
        assertThat(p.get("type").asText()).doesNotEndWith("outside-scope");
        return p;
    }

    /** The grant stamped for a nurse on an admission: id, or null. */
    protected String grantOf(Clinic clinic, Pn nurse, String admissionId) throws SQLException {
        List<String> v = values(clinic, "select id::text from ipd.emergency_access_grant where membership_id = ?::uuid "
                + "and admission_id = ?::uuid order by opened_at desc limit 1", nurse.membershipId().toString(), admissionId);
        return v.isEmpty() ? null : v.get(0);
    }

    protected long grantRows(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from ipd.emergency_access_grant");
    }

    protected JsonNode openGrant(Pn nurse, String admissionId, String reasonKey) throws Exception {
        return ok(HttpMethod.POST, EA, nurse.auth(), eaBody(admissionId, reasonKey, true), 201);
    }

    protected static String eaBody(String admissionId, String reasonKey, Object confirmed) {
        return "{\"admissionId\":" + (admissionId == null ? "null" : "\"" + admissionId + "\"") + ",\"reasonKey\":"
                + (reasonKey == null ? "null" : "\"" + reasonKey + "\"") + ",\"confirmed\":" + confirmed + "}";
    }

    /**
     * Inserts a grant as the table owner with the stamping trigger switched off, so that it can be old: it opened
     * {@code ageSeconds} ago and ends 30 minutes after that (the check keeps expires_at = opened_at + 30 minutes). Returns
     * the grant id. A bg entry is not written.
     */
    protected String insertGrantAged(Clinic clinic, Pn nurse, String admissionId, String reasonKey, long ageSeconds,
                                     UUID reviewedBy) throws SQLException {
        String bed = values(clinic, "select bed_id::text from ipd.admission where id = ?::uuid", admissionId).get(0);
        return asOwner(true, GRANT_TABLES, c -> {
            DbAccess.execute(c, "alter table ipd.emergency_access_grant disable trigger trg_emergency_access_grant_stamp");
            String id;
            try (var ps = c.prepareStatement("insert into ipd.emergency_access_grant (tenant_id, admission_id, bed_id, user_id, "
                    + "membership_id, role, reason_key, opened_at, expires_at, reviewed_by, reviewed_at) select ?::uuid, ?::uuid, "
                    + "?::uuid, ?::uuid, ?::uuid, 'NURSE', ?, o, o + interval '30 minutes', ?::uuid, "
                    + "case when ?::uuid is null then null else clock_timestamp() end "
                    + "from (select clock_timestamp() - (? * interval '1 second') as o) t returning id::text")) {
                ps.setString(1, clinic.tenantId().toString());
                ps.setString(2, admissionId);
                ps.setString(3, bed);
                ps.setObject(4, nurse.userId());
                ps.setObject(5, nurse.membershipId());
                ps.setString(6, reasonKey);
                ps.setObject(7, reviewedBy);
                ps.setObject(8, reviewedBy);
                ps.setLong(9, ageSeconds);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getString(1);
                }
            }
            DbAccess.execute(c, "alter table ipd.emergency_access_grant enable trigger trg_emergency_access_grant_stamp");
            return id;
        });
    }

    /** A grant that has already expired (it opened 31 minutes ago). */
    protected String insertExpiredGrant(Clinic clinic, Pn nurse, String admissionId) throws SQLException {
        return insertGrantAged(clinic, nurse, admissionId, "COVERING_BREAK", 31 * 60, null);
    }

    /** A grant that ends in about {@code secondsLeft} seconds. */
    protected String insertGrantEndingIn(Clinic clinic, Pn nurse, String admissionId, long secondsLeft) throws SQLException {
        return insertGrantAged(clinic, nurse, admissionId, "DETERIORATING", 30 * 60 - secondsLeft, null);
    }

    /**
     * One snapshot of everything a refused request must leave as it was: the rows of every IPD table a scoped call may
     * write, the clinic's audit and record-access rows, and the version of every admission.
     */
    protected String written(Clinic clinic) throws SQLException {
        return values(clinic, "select (select count(*) from ipd.vitals) || '/' || (select count(*) from ipd.nursing_note) || '/' "
                + "|| (select count(*) from ipd.clinical_event) || '/' || (select count(*) from ipd.deposit) || '/' "
                + "|| (select count(*) from ipd.admission_charge) || '/' || (select count(*) from ipd.bed_transfer) || '/' "
                + "|| (select count(*) from ipd.dose_administration) || '/' || (select count(*) from ipd.ews_escalation) || '/' "
                + "|| (select count(*) from ipd.ews_senior_call) || '/' || (select count(*) from ipd.medicine_order) || '/' "
                + "|| (select count(*) from ipd.discharge) || '/' || (select count(*) from ipd.discharge_signoff) || '/' "
                + "|| (select count(*) from ipd.final_bill) || '/' || (select count(*) from ipd.emergency_access_grant) || '/' "
                + "|| (select count(*) from platform.audit_event) || '/' || (select count(*) from patient.record_access) || '/' "
                + "|| coalesce((select string_agg(id::text || ':' || version || ':' || status || ':' || bed_id, ',' order by id) "
                + "from ipd.admission), '') || '/' "
                + "|| coalesce((select string_agg(id::text || ':' || version || ':' || status || ':' || senior_calls, ',' order by id) "
                + "from ipd.ews_escalation), '')").get(0);
    }

    /** Hospital with an active order and an open escalation for the patient of ward B (the patient the nurse of A must not reach). */
    protected record Full(Hosp h, Ward mar, String orderId, String escalationId) {
    }

    protected Full full() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Staff doctor = staff(clinic, "DOCTOR");
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        String pin = goodPin(pharmacist);
        setPin(pharmacist, pin);
        String uhid = values(clinic, "select uhid from patient.patient where id = ?::uuid", h.inB().patientId()).get(0);
        Ward mar = new Ward(clinic, doctor, h.nurseA(), h.nurseB(), "0000", pharmacist, pin, h.inB(), uhid);
        String order = activeOrder(mar, "pcm", "od");
        String esc = raise(clinic, h.inB(), high7());
        clockAt(8, 5);
        return new Full(h, mar, order, esc);
    }

}
