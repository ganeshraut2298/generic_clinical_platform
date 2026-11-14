package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the Doctor EMR phase 5 integration tests (SAH-51, SAH-67, SAH-68; ADR-025, docs/api/opd.yaml 0.6.1):
 * a General Medicine record with the four required sections filled, the finish check and the sign with an
 * {@code Idempotency-Key}, the stored sign columns and the queue card. Everything goes through the real endpoints as
 * {@code sahaj_app}; throwaway data only (made-up names, fake mobile numbers, no real clinical notes).
 */
abstract class EmrSignSupport extends EmrSpecialtySupport {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String FOLLOW_UPS = "/api/v1/opd/follow-ups";
    static final String APPOINTMENTS = "/api/v1/opd/appointments";

    // The same four required General Medicine sections as EmrRecordsIT (component fields, ADR-022 D2).
    static final String COMPLAINTS_JSON = "{\"complaint\":{\"component\":\"EMR_LIST\",\"kind\":\"COMPLAINTS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000001\",\"itemKey\":null,\"text\":\"Fever\",\"newToList\":true,"
            + "\"attr1\":\"D3\",\"attr2\":\"MODERATE\",\"active\":true}]}}";
    static final String DIAGNOSIS_JSON = "{\"provisional_diagnosis\":{\"component\":\"EMR_LIST\",\"kind\":\"DIAGNOSIS\",\"schemaVersion\":1,"
            + "\"items\":[{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000002\",\"itemKey\":null,\"text\":\"Viral fever\",\"newToList\":true,"
            + "\"attr1\":\"PROVISIONAL\",\"attr2\":\"ACTIVE\",\"active\":true}]}}";
    static final String RX_JSON = "{\"drug\":{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":["
            + "{\"id\":\"5b1f1f0e-0a00-4c00-8000-000000000003\",\"medicineId\":null,\"name\":\"Paracetamol 500 mg\",\"form\":null,"
            + "\"frequency\":\"1-0-1\",\"days\":3,\"when\":\"AFTER_FOOD\",\"source\":\"TYPED\"}]}}";
    static final String RXFORMAT_JSON = "{\"header\":{\"text\":\"Header with registration number\"}}";
    // General Medicine Vitals is the vitals log editor (SAH-60) and V2026_12_14_1010 makes it a required section (SAH-75).
    static final String VITALS_LOG_JSON = "{\"entries\":[{\"id\":\"ENTRY_ID\",\"bpSystolic\":120,\"bpDiastolic\":80,\"pulse\":72,\"spo2\":98,"
            + "\"temperature\":{\"value\":98.6,\"unit\":\"F\"},\"respRate\":16}]}";

    protected static LocalDate today() {
        return LocalDate.now(IST);
    }

    // ---------------- records ----------------

    /** Saves the four required sections of General Medicine; returns the record after the save. */
    protected Rec fillRequired(Rec rec) throws Exception {
        return save(rec, spSection("vitals", "VITALS_LOG", VITALS_LOG_JSON.replace("ENTRY_ID", uuid())), sectionValues("complaints", COMPLAINTS_JSON),
                sectionValues("diagnosis", DIAGNOSIS_JSON), sectionValues("rx", RX_JSON), sectionValues("rxformat", RXFORMAT_JSON));
    }

    /** A new clinic, a General physician, a visit and an open record whose required sections are all filled. */
    protected Rec readyRec() throws Exception {
        return fillRequired(newRec());
    }

    /** The follow-up section with one interval (D3, D7, D15, M1 or M3). */
    protected Rec chooseInterval(Rec rec, String interval) throws Exception {
        return save(rec, oneField("followup", "next_visit", objectMapper.readTree(
                "{\"component\":\"FOLLOW_UP\",\"schemaVersion\":1,\"interval\":\"" + interval + "\"}")));
    }

    // ---------------- finish check and sign ----------------

    protected static String signBody(long revision, String choicesJson) {
        return "{\"revision\":" + revision + ",\"choices\":" + choicesJson + "}";
    }

    protected String signPath(Object recordId) {
        return RECORDS + "/" + recordId + "/sign";
    }

    protected String checkPath(Object recordId) {
        return RECORDS + "/" + recordId + "/finish-check";
    }

    /** The finish check as the record's doctor with no choices. */
    protected JsonNode check(Rec rec) throws Exception {
        return post(checkPath(rec.id()), rec.token(), "{}", 200);
    }

    protected JsonNode check(Rec rec, String choicesJson) throws Exception {
        return post(checkPath(rec.id()), rec.token(), choicesJson, 200);
    }

    /** The sign as the record's doctor, a fresh key, the record's own revision; must answer 200. */
    protected JsonNode signOk(Rec rec) throws Exception {
        return signOk(rec, "{}");
    }

    protected JsonNode signOk(Rec rec, String choicesJson) throws Exception {
        return okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(), choicesJson), key(), 200);
    }

    protected JsonNode signWithKey(Rec rec, String key, String choicesJson, int expected) throws Exception {
        return okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(), choicesJson), key, expected);
    }

    /** A sign that must be refused with this status and problem type; returns the problem. */
    protected JsonNode signRefused(Rec rec, RequestPostProcessor who, String body, String key, int status, String slug)
            throws Exception {
        return problemKeyed(HttpMethod.POST, signPath(rec.id()), who, body, key, status, slug);
    }

    protected JsonNode signRefused(Rec rec, int status, String slug) throws Exception {
        return signRefused(rec, rec.token(), signBody(rec.revision(), "{}"), key(), status, slug);
    }

    // ---------------- stored state ----------------

    /** status, signed_by, signed_by_membership_id, sign key, sign hash, has result, no hold, has signed_at, revision. */
    protected List<String> signColumns(Clinic clinic, String recordId) throws SQLException {
        return row(clinic, "select status, signed_by::text, signed_by_membership_id::text, sign_idempotency_key, "
                + "sign_request_hash, (sign_result is not null)::text, (edit_hold_membership_id is null)::text, "
                + "(signed_at is not null)::text, revision::text from opd.emr_record where id = ?::uuid", recordId);
    }

    protected String statusOf(Clinic clinic, String recordId) throws SQLException {
        return row(clinic, "select status from opd.emr_record where id = ?::uuid", recordId).get(0);
    }

    /** The card of the visit on today's queue. */
    protected JsonNode boardVisit(Clinic clinic, String visitId) throws Exception {
        for (JsonNode v : get(VISITS + "/today", clinic.owner())) {
            if (visitId.equals(v.get("id").asText())) {
                return v;
            }
        }
        throw new AssertionError("the visit is not on today's queue: " + visitId);
    }

    /** The follow-up of the visit, or null. */
    protected JsonNode followUpOf(Clinic clinic, String visitId) throws Exception {
        // the list needs a status (400 without one): a follow-up is in exactly one of the four
        for (String status : new String[] {"DUE", "MISSED", "UPCOMING", "RETURNED"}) {
            for (JsonNode f : get(FOLLOW_UPS + "?status=" + status, clinic.owner()).get("items")) {
                if (visitId.equals(f.path("sourceVisitId").asText())) {
                    return f;
                }
            }
        }
        return null;
    }

    protected long followUpRows(Clinic clinic, String visitId) throws SQLException {
        return count(clinic, "select count(*) from opd.follow_up where source_visit_id = ?::uuid", visitId);
    }

    protected static void assertProblemMentionsNoClinicalText(JsonNode problem) {
        assertThat(problem.toString()).doesNotContain("Fever").doesNotContain("Viral fever").doesNotContain("Paracetamol");
    }
}
