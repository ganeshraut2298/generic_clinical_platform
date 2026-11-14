package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the Doctor EMR phase 5 tests that need a hospital (SAH-67: the IPD round, the Emergency assessment, the
 * admission request): the ipd helpers of {@link ScopeSupport} plus the few EMR calls these tests make through the real
 * endpoints (open, fill, finish check, sign with an {@code Idempotency-Key}). Made-up names and mobile numbers only.
 */
abstract class EmrIpdSupport extends ScopeSupport {

    static final String EMR_API = "/api/v1/opd/emr";
    static final String EMR_OPEN = EMR_API + "/records/open";
    static final String EMR_RECORDS = EMR_API + "/records";
    static final String ER_CASES_PATH = IPD + "/emergency/cases";
    static final String OPD_VISITS = "/api/v1/opd/visits";
    static final String IPD_REQUESTS = IPD + "/admission-requests";
    static final String NONE_KNOWN_ALLERGY = "None known";

    private static final String COMPLAINTS = "{\"complaint\":{\"component\":\"EMR_LIST\",\"kind\":\"COMPLAINTS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000001\",\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true,"
            + "\"attr1\":\"D3\",\"attr2\":\"MODERATE\",\"active\":true}]}}";
    private static final String DIAGNOSIS = "{\"provisional_diagnosis\":{\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000002\",\"itemKey\":null,\"text\":\"Viral fever\",\"newToList\":true,"
            + "\"attr1\":\"PROVISIONAL\",\"attr2\":\"ACTIVE\",\"active\":true}]}}";
    private static final String RX = "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":["
            + "{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000003\",\"medicineId\":null,\"name\":\"Paracetamol 500 mg\",\"form\":null,"
            + "\"frequency\":\"1-0-1\",\"days\":3,\"when\":\"AFTER_FOOD\",\"source\":\"TYPED\"}]}}";
    private static final String RXFORMAT = "{\"header\":{\"text\":\"Header with registration number\"}}";
    private static final String INVESTIGATION = "{\"investigation\":{\"component\":\"EMR_LIST\",\"kind\":\"INVESTIGATIONS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000009\",\"itemKey\":null,\"text\":\"Zxqtest blood count\","
            + "\"newToList\":true,\"active\":true}]}}";

    protected static LocalDate todayIst() {
        return LocalDate.now(IST);
    }

    protected static String idemKey() {
        return "k" + UUID.randomUUID().toString().replace("-", "");
    }

    // ---------------- calls ----------------

    protected ResultActions keyed(HttpMethod method, String path, RequestPostProcessor auth, String body, String key) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, java.net.URI.create(path)).with(auth);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    protected JsonNode okKeyed(HttpMethod method, String path, RequestPostProcessor auth, String body, String key, int expected)
            throws Exception {
        String text = keyed(method, path, auth, body, key)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return text.isEmpty() ? null : objectMapper.readTree(text);
    }

    protected JsonNode problemKeyed(HttpMethod method, String path, RequestPostProcessor auth, String body, String key, int status,
                                    String slug) throws Exception {
        JsonNode problem = problem(keyed(method, path, auth, body, key), status);
        assertThat(problem.get("type").asText()).as("problem type of " + method + " " + path).isEqualTo(PROBLEM_BASE + slug);
        return problem;
    }

    protected JsonNode problemOf(HttpMethod method, String path, RequestPostProcessor auth, String body, int status, String slug)
            throws Exception {
        JsonNode problem = problem(call(method, path, auth, body), status);
        assertThat(problem.get("type").asText()).as("problem type of " + method + " " + path).isEqualTo(PROBLEM_BASE + slug);
        return problem;
    }

    // ---------------- people ----------------

    /** The doctor who admitted the patients of {@link #hospital()} (added through Team: has doctor details). */
    protected RequestPostProcessor admittingDoctor(Clinic clinic) throws SQLException {
        String user = values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                clinic.doctorMembershipId().toString()).get(0);
        return asUser(clinic.tenantId(), UUID.fromString(user), "DOCTOR");
    }

    protected void giveOwnerDoctorDetails(Clinic clinic) throws SQLException {
        try (java.sql.Connection c = db().app()) {
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            DbAccess.execute(c, "insert into platform.doctor_profile (membership_id, specialty, fee_paise, degree, registration_no) "
                    + "select id, 'General physician', 0, 'MBBS', 'REG-OWN-1' from platform.membership where role = 'OWNER' "
                    + "on conflict (membership_id) do update set degree = 'MBBS', registration_no = 'REG-OWN-1'");
            c.commit();
        }
    }

    // ---------------- records ----------------

    protected JsonNode openRecord(RequestPostProcessor who, String type, Object encounterId, int expected) throws Exception {
        return ok(HttpMethod.POST, EMR_OPEN, who, "{\"encounterType\":\"" + type + "\",\"encounterId\":\"" + encounterId + "\"}", expected);
    }

    protected JsonNode getRecord(RequestPostProcessor who, Object recordId) throws Exception {
        return ok(HttpMethod.GET, EMR_RECORDS + "/" + recordId, who, null, 200);
    }

    protected static String section(String key, String valuesJson) {
        return "{\"key\":\"" + key + "\",\"values\":" + valuesJson + "}";
    }

    protected static String draft(long revision, String... sections) {
        return "{\"revision\":" + revision + ",\"uiState\":{\"part\":\"ASSESSMENT\",\"sectionKey\":null},\"sections\":["
                + String.join(",", sections) + "]}";
    }

    protected JsonNode saveDraft(RequestPostProcessor who, Object recordId, String body, int expected) throws Exception {
        return ok(HttpMethod.PUT, EMR_RECORDS + "/" + recordId + "/draft", who, body, expected);
    }

    /** One new medicine row of an IPD round: the EMR row id is the SAH-31 client key. */
    protected static String ipdRow(String rowId, String drugKey, String frequencyKey) {
        return "{\"id\":\"" + rowId + "\",\"drugKey\":\"" + drugKey + "\",\"frequencyKey\":\"" + frequencyKey + "\"}";
    }

    protected static String ipdOrders(String... rows) {
        return "{\"drug\":{\"component\":\"IPD_ORDERS\",\"schemaVersion\":1,\"rows\":[" + String.join(",", rows) + "]}}";
    }

    /** The four required sections of General Medicine with the given Prescription values; returns the saved record. */
    protected JsonNode fillWith(RequestPostProcessor who, JsonNode record, String rxValues) throws Exception {
        return saveDraft(who, record.get("id").asText(), draft(record.get("revision").asLong(),
                section("complaints", COMPLAINTS), section("diagnosis", DIAGNOSIS), section("rx", rxValues),
                section("rxformat", RXFORMAT)), 200);
    }

    /** As {@link #fillWith} with a typed prescription (OPD visit and Emergency assessment). */
    protected JsonNode fillTyped(RequestPostProcessor who, JsonNode record) throws Exception {
        return fillWith(who, record, RX);
    }

    protected JsonNode withInvestigation(RequestPostProcessor who, JsonNode record) throws Exception {
        return saveDraft(who, record.get("id").asText(), draft(record.get("revision").asLong(), section("investigations", INVESTIGATION)), 200);
    }

    protected static String signBody(long revision, String choicesJson) {
        return "{\"revision\":" + revision + ",\"choices\":" + choicesJson + "}";
    }

    protected JsonNode check(RequestPostProcessor who, JsonNode record, String choicesJson, int expected) throws Exception {
        return ok(HttpMethod.POST, EMR_RECORDS + "/" + record.get("id").asText() + "/finish-check", who, choicesJson, expected);
    }

    protected JsonNode sign(RequestPostProcessor who, JsonNode record, String choicesJson) throws Exception {
        return okKeyed(HttpMethod.POST, EMR_RECORDS + "/" + record.get("id").asText() + "/sign", who,
                signBody(record.get("revision").asLong(), choicesJson), idemKey(), 200);
    }

    protected JsonNode signRefused(RequestPostProcessor who, JsonNode record, String choicesJson, int status, String slug)
            throws Exception {
        return problemKeyed(HttpMethod.POST, EMR_RECORDS + "/" + record.get("id").asText() + "/sign", who,
                signBody(record.get("revision").asLong(), choicesJson), idemKey(), status, slug);
    }

    protected static List<String> texts(JsonNode array, String field) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asText() : n.get(field).asText()));
        return out;
    }

    // ---------------- Emergency and OPD set-up ----------------

    protected String caseOf(Clinic clinic, String name, int level) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("identityUnknown", false);
        m.put("name", name);
        m.put("age", 40);
        m.put("sex", "F");
        m.put("mobile", null);
        m.put("complaint", "Chest pain for 1 hour");
        m.put("level", level);
        return ok(HttpMethod.POST, ER_CASES_PATH, clinic.owner(), js(m), 201).get("actedCase").get("id").asText();
    }

    protected List<String> caseRow(Clinic clinic, String caseId) throws SQLException {
        return values(clinic, "select status, (closed_at is not null)::text, (mobile is null)::text, patient_id::text, "
                + "sent_to_opd_visit_id::text, (closed_by is not null)::text from ipd.emergency_case where id = ?::uuid", caseId);
    }

    protected List<String> recordRow(Clinic clinic, String recordId) throws SQLException {
        return values(clinic, "select status, admission_advised::text, referral_line, patient_id::text, revision::text "
                + "from opd.emr_record where id = ?::uuid", recordId);
    }

    /** An OPD visit of an existing patient with the doctor of the clinic, through the real queue endpoint. */
    protected String visitOf(Clinic clinic, UUID patientId) throws Exception {
        return ok(HttpMethod.POST, OPD_VISITS, clinic.owner(), "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\""
                + clinic.doctorMembershipId() + "\"}", 201).get("id").asText();
    }

    /** Admission body of a planned stay with the standard deposit, optionally completing an EMR request. */
    protected Map<String, Object> admitBodyFor(Clinic clinic, UUID patientId, Object admissionRequestId) throws Exception {
        Map<String, Object> m = body(patientId, clinic.doctorMembershipId(), newBed(clinic, "gen"));
        if (admissionRequestId != null) {
            m.put("admissionRequestId", admissionRequestId.toString());
        }
        return m;
    }
}
