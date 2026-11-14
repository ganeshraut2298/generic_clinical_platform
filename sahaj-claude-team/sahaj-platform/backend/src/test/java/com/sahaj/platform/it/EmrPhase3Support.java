package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the Doctor EMR phase 3 integration tests (SAH-48, 50, 55, 56, 57, 58, 59; ADR-022): builders for the
 * component values (EMR list, prescription, body map), a doctor with an open EMR record, a signed earlier record (set by
 * the one {@code update} the column grants allow, because SAH-51 does not exist yet), calls that carry an
 * {@code Idempotency-Key}, services and forms. Everything else goes through the real endpoints as {@code sahaj_app}.
 * Throwaway data only: made-up names, made-up mobile numbers.
 */
abstract class EmrPhase3Support extends EmrSupport {

    static final String BILLING = "/api/v1/billing";
    static final String COURSES = EMR + "/courses";
    static final String FORMS = EMR + "/forms";
    static final String FORM_STARTERS = EMR + "/form-starters";
    static final String ALLERGY_CLASSES = EMR + "/allergy-classes";
    static final String EMR_MEDICINES = EMR + "/medicines";
    static final String FAVOURITES = EMR + "/medicine-favourites";
    static final String SERVICES = "/api/v1/opd/services";
    static final String PATIENTS_URL = "/api/v1/patient/patients";
    static final String TRIAL_URL = "/api/v1/platform/modules/ipd-trial";

    static final String SULFA_MEDICINE = "Co-trimoxazole 960 mg";
    static final String PENICILLIN_MEDICINE = "Amoxicillin 500 mg";
    static final String PLAIN_MEDICINE = "Paracetamol 650 mg";

    // ---------------- ids and small builders ----------------

    protected static String newId() {
        return UUID.randomUUID().toString();
    }

    /** A typed EMR list item (not in the master): no key, "New to list". */
    protected ObjectNode typedItem(String text) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", newId());
        item.putNull("itemKey");
        item.put("text", text);
        item.put("newToList", true);
        return item;
    }

    /** An EMR list item picked from a master (key = master id, ICD-10 code or service id). */
    protected ObjectNode keyedItem(String itemKey, String text) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", newId());
        item.put("itemKey", itemKey);
        item.put("text", text);
        item.put("newToList", false);
        return item;
    }

    protected ObjectNode listValue(String kind, ObjectNode... items) {
        ObjectNode list = objectMapper.createObjectNode();
        list.put("component", "EMR_LIST");
        list.put("kind", kind);
        list.put("schemaVersion", 1);
        ArrayNode array = list.putArray("items");
        for (ObjectNode item : items) {
            array.add(item);
        }
        return list;
    }

    protected ObjectNode rxRow(String medicineId, String name, String frequency, int days, String when) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", newId());
        if (medicineId == null) {
            row.putNull("medicineId");
        } else {
            row.put("medicineId", medicineId);
        }
        row.put("name", name);
        row.putNull("form");
        row.put("frequency", frequency);
        row.put("days", days);
        row.put("when", when);
        row.put("source", medicineId == null ? "TYPED" : "SEARCH");
        return row;
    }

    protected ObjectNode typedRx(String name) {
        return rxRow(null, name, "1-0-1", 3, "AFTER_FOOD");
    }

    protected ObjectNode rxValue(ObjectNode... rows) {
        ObjectNode rx = objectMapper.createObjectNode();
        rx.put("component", "PRESCRIPTION");
        rx.put("schemaVersion", 1);
        ArrayNode array = rx.putArray("rows");
        for (ObjectNode row : rows) {
            array.add(row);
        }
        return rx;
    }

    protected ObjectNode pin(int no, String cause, boolean mlc) {
        ObjectNode pin = objectMapper.createObjectNode();
        pin.put("no", no);
        pin.put("side", "FRONT");
        pin.put("x", 0.5);
        pin.put("y", 0.25);
        pin.put("region", "chest");
        pin.put("where", "Chest");
        pin.put("finding", "WOUND");
        pin.put("cause", cause);
        pin.put("severity", "MILD");
        pin.put("sizeCm", 2.5);
        pin.put("note", "");
        pin.put("mlc", mlc);
        return pin;
    }

    protected ObjectNode bodyMap(boolean noFindings, ObjectNode... pins) {
        ObjectNode map = objectMapper.createObjectNode();
        map.put("component", "BODY_MAP");
        map.put("schemaVersion", 1);
        ArrayNode array = map.putArray("pins");
        for (ObjectNode pin : pins) {
            array.add(pin);
        }
        map.put("noFindingsConfirmed", noFindings);
        return map;
    }

    /** One section of a draft save whose values are one field. */
    protected ObjectNode oneField(String sectionKey, String fieldKey, JsonNode value) {
        ObjectNode values = objectMapper.createObjectNode();
        values.set(fieldKey, value);
        ObjectNode section = objectMapper.createObjectNode();
        section.put("key", sectionKey);
        section.set("values", values);
        return section;
    }

    protected ObjectNode complaintsSection(ObjectNode... items) {
        return oneField("complaints", "complaint", listValue("COMPLAINTS", items));
    }

    protected ObjectNode rxSection(ObjectNode... rows) {
        return oneField("rx", "drug", rxValue(rows));
    }

    protected ObjectNode allergySection(String... classKeysAndTypedTexts) {
        ArrayNode list = objectMapper.createArrayNode();
        for (String entry : classKeysAndTypedTexts) {
            ObjectNode item = list.addObject();
            if (entry.startsWith("typed:")) {
                item.putNull("itemKey");
                item.put("text", entry.substring("typed:".length()));
                item.put("newToList", true);
            } else {
                item.put("itemKey", entry);
                item.put("text", "SULFA".equals(entry) ? "Sulfa drugs" : "Penicillin");
                item.put("newToList", false);
            }
        }
        return oneField("allergy", "drug_allergy", list);
    }

    // ---------------- people, patients, visits, records ----------------

    protected record Rec(Clinic clinic, Staff doctor, UUID patientId, JsonNode visit, JsonNode record) {

        RequestPostProcessor token() {
            return doctor.token(clinic);
        }

        String id() {
            return record.get("id").asText();
        }

        String visitId() {
            return visit.get("id").asText();
        }

        long revision() {
            return record.get("revision").asLong();
        }

        Rec with(JsonNode newRecord) {
            return new Rec(clinic, doctor, patientId, visit, newRecord);
        }
    }

    /** A patient with a made-up name, allergy and chronic text (both may be null). */
    protected UUID patientWith(Clinic clinic, String name, String allergy, String chronic) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("age", 45);
        body.put("sex", "M");
        body.put("mobile", uniqueMobile());
        body.put("area", "Shivaji Nagar");
        body.put("language", "mr");
        if (allergy != null) {
            body.put("allergy", allergy);
        }
        if (chronic != null) {
            body.put("chronic", chronic);
        }
        return UUID.fromString(ok(HttpMethod.POST, PATIENTS_URL, clinic.owner(), body.toString(), 201).get("id").asText());
    }

    protected JsonNode visitFor(Clinic clinic, UUID patientId, Staff doctor) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patientId + "\",\"doctorMembershipId\":\"" + doctor.membershipId() + "\"}", 201);
    }

    /** Opens the EMR of a new visit of this patient as the doctor. */
    protected Rec startRecord(Clinic clinic, Staff doctor, UUID patientId) throws Exception {
        JsonNode visit = visitFor(clinic, patientId, doctor);
        JsonNode record = openVisit(doctor.token(clinic), visit.get("id").asText(), 201).get("record");
        return new Rec(clinic, doctor, patientId, visit, record);
    }

    /** A new patient, a new visit and an open EMR (General Medicine, the doctor's specialty). */
    protected Rec startRecord(Clinic clinic, Staff doctor) throws Exception {
        return startRecord(clinic, doctor, newPatient(clinic));
    }

    protected Rec newRec() throws Exception {
        Clinic clinic = newClinic();
        return startRecord(clinic, addDoctor(clinic, "General physician"));
    }

    /** Saves the sections (revision from the record you hold) and returns the record the server answers. */
    protected Rec save(Rec rec, ObjectNode... sections) throws Exception {
        JsonNode saved = saveRecordDraft(rec.token(), rec.id(), draftBody(rec.revision(), "ASSESSMENT", null, sections), 200);
        return rec.with(saved);
    }

    /** A save that must be refused with this status and problem type; returns the problem. */
    protected JsonNode refuseSave(Rec rec, int status, String slug, ObjectNode... sections) throws Exception {
        return problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(),
                draftBody(rec.revision(), "ASSESSMENT", null, sections).toString(), status, slug);
    }

    protected JsonNode current(Rec rec) throws Exception {
        return record(rec.token(), rec.id());
    }

    /** The header switch: the record takes the layout of another department (no value is deleted). */
    protected Rec switchDepartment(Rec rec, String departmentKey) throws Exception {
        JsonNode switched = put(RECORDS + "/" + rec.id() + "/department", rec.token(),
                "{\"departmentKey\":\"" + departmentKey + "\",\"revision\":" + rec.revision() + "}", 200);
        return rec.with(switched);
    }

    /**
     * Marks a record signed by the one update the database accepts since SAH-51 (signed_at may be moved back to order the
     * history): the signer's membership and a sign key are required and the edit hold the open step took is cleared
     * ({@code ck_emr_record_signed_complete}). A record is signed last: after this update only the print choices change.
     */
    protected void signRecord(Clinic clinic, Object recordId, int daysAgo) throws SQLException {
        commit(clinic.tenantId(), "update opd.emr_record set status = 'SIGNED', signed_at = now() - make_interval(days => ?), "
                + "signed_by = ?::uuid, signed_by_membership_id = (select m.id from platform.membership m where m.user_id = ?::uuid limit 1), "
                + "sign_idempotency_key = ?, edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null "
                + "where id = ?::uuid", daysAgo, clinic.ownerUserId(), clinic.ownerUserId(), "fixture-" + shortId(), recordId.toString());
    }

    protected void signRecord(Clinic clinic, Object recordId) throws SQLException {
        signRecord(clinic, recordId, 0);
    }

    protected static JsonNode valueOf(JsonNode record, String sectionKey, String fieldKey) {
        return recordSection(record, sectionKey).get("values").get(fieldKey);
    }

    protected static List<String> itemTexts(JsonNode listValue) {
        return texts(listValue.get("items"), "text");
    }

    protected String medicineId(String name) throws SQLException {
        return globalRows("select id::text from opd.medicine where lower(name) = lower(?)", name).get(0).get(0);
    }

    /** Makes a service in the clinic's price list (a whole-rupee price); returns its id. */
    protected String serviceId(Clinic clinic, String name, int rupees) throws Exception {
        return ok(HttpMethod.POST, SERVICES, clinic.owner(), "{\"name\":\"" + name + " " + shortId()
                + "\",\"category\":\"Lab\",\"priceRupees\":" + rupees + "}", 201).get("id").asText();
    }

    /** Shows the Procedures section of General Medicine (it is off at the start). */
    protected void showProcedures(Clinic clinic) throws Exception {
        publishOneChange(clinic, "gm", "procedures", true);
    }

    protected long auditCount(Clinic clinic, String action) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ?", action);
    }

    // ---------------- calls with an Idempotency-Key ----------------

    protected ResultActions callKeyed(HttpMethod method, String path, RequestPostProcessor auth, String body, String key)
            throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, java.net.URI.create(path)).with(auth);
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    protected JsonNode okKeyed(HttpMethod method, String path, RequestPostProcessor auth, String body, String key,
                               int expected) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response = callKeyed(method, path, auth, body, key).andReturn().getResponse();
        String text = response.getContentAsString();
        if (response.getStatus() != expected) { // the answer names what was refused (test data only)
            throw new AssertionError("Response status expected:<" + expected + "> but was:<" + response.getStatus() + "> for " + method + " "
                    + path.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<id>") + " answer: " + (text.length() > 700 ? text.substring(0, 700) : text));
        }
        return text.isEmpty() ? null : objectMapper.readTree(text);
    }

    protected JsonNode problemKeyed(HttpMethod method, String path, RequestPostProcessor auth, String body, String key,
                                    int status, String slug) throws Exception {
        JsonNode problem = problem(callKeyed(method, path, auth, body, key), status);
        assertThat(problem.get("type").asText()).as("problem type of " + method + " " + path).isEqualTo(PROBLEM_BASE + slug);
        return problem;
    }

    protected static String key() {
        return "k" + UUID.randomUUID().toString().replace("-", "");
    }

    // ---------------- treatment courses and their money ----------------

    protected static String sittingJson(String option, Long chargePaise, String note) {
        return "{\"addOptionKey\":\"" + option + "\"" + (chargePaise == null ? "" : ",\"chargePaise\":" + chargePaise)
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    /** A course of this kind made by the record's doctor with these planned sittings (a JSON array text, or null). */
    protected JsonNode newCourse(Rec rec, String kind, String sittingsJson) throws Exception {
        String body = "{\"fromRecordId\":\"" + rec.id() + "\",\"kind\":\"" + kind + "\""
                + (sittingsJson == null ? "" : ",\"sittings\":" + sittingsJson) + "}";
        return okKeyed(HttpMethod.POST, COURSES, rec.token(), body, key(), 201);
    }

    /** A Dental course of three OPD sittings of Rs 1,000, Rs 1,500 and Rs 500 (estimate Rs 3,000). */
    protected JsonNode threeSittingCourse(Rec rec) throws Exception {
        return newCourse(rec, "DENTAL", "[" + sittingJson("opd_sitting", 100000L, null) + "," + sittingJson("opd_sitting", 150000L, null) + ","
                + sittingJson("opd_sitting", 50000L, null) + "]");
    }

    /** What SAH-51 will do in its sign transaction: the sitting is done. */
    protected void markSittingDone(Clinic clinic, String sittingId) throws SQLException {
        commit(clinic.tenantId(), "update opd.treatment_course_sitting set done_at = now() where id = ?::uuid", sittingId);
    }

    protected static String accountPath(JsonNode course) {
        return BILLING + "/courses/" + course.get("id").asText() + "/account";
    }

    protected static String paymentsPath(JsonNode course) {
        return BILLING + "/courses/" + course.get("id").asText() + "/payments";
    }

    protected static String paymentBody(String type, long amountPaise, String mode, boolean useCredit, String againstSittingId) {
        return "{\"type\":\"" + type + "\",\"amountPaise\":" + amountPaise + ",\"mode\":\"" + mode + "\",\"useCredit\":" + useCredit
                + ",\"againstSittingId\":" + (againstSittingId == null ? "null" : "\"" + againstSittingId + "\"") + "}";
    }

    /** Collects a payment as the owner; returns the answer (201 for a new payment). */
    protected JsonNode collect(Clinic clinic, JsonNode course, String type, long amountPaise, String mode) throws Exception {
        return okKeyed(HttpMethod.POST, paymentsPath(course), clinic.owner(), paymentBody(type, amountPaise, mode, false, null), key(), 201);
    }

    protected JsonNode account(Clinic clinic, JsonNode course) throws Exception {
        return get(accountPath(course), clinic.owner());
    }

    // ---------------- forms ----------------

    protected JsonNode createForm(Clinic clinic, String starterKey, String name) throws Exception {
        String body = "{\"starterKey\":\"" + starterKey + "\",\"name\":" + (name == null ? "null" : "\"" + name + "\"") + "}";
        return okKeyed(HttpMethod.POST, FORMS, clinic.owner(), body, key(), 201);
    }

    /** The Save draft body that sends the form exactly as the view shows it (settings pointed at General Medicine). */
    protected ObjectNode formDraftBody(JsonNode view) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("revision", view.get("revision").asLong());
        JsonNode definition = view.get("definition");
        body.put("name", definition.get("name").asText());
        ObjectNode settings = (ObjectNode) definition.get("settings").deepCopy();
        ObjectNode useIn = settings.putObject("useIn");
        useIn.putArray("departmentKeys").add("gm");
        useIn.putArray("places");
        body.set("settings", settings);
        body.set("fields", definition.get("fields").deepCopy());
        return body;
    }

    protected static String revisionBody(JsonNode view) {
        return "{\"revision\":" + view.get("revision").asLong() + "}";
    }

    protected JsonNode form(Clinic clinic, String formKey) throws Exception {
        return get(FORMS + "/" + formKey, clinic.owner());
    }

    protected JsonNode saveFormDraft(Clinic clinic, JsonNode view, ObjectNode body, int expected) throws Exception {
        return put(FORMS + "/" + view.get("formKey").asText() + "/draft", clinic.owner(), body.toString(), expected);
    }

    protected JsonNode formAction(Clinic clinic, JsonNode view, String action, int expected) throws Exception {
        return post(FORMS + "/" + view.get("formKey").asText() + "/" + action, clinic.owner(), revisionBody(view), expected);
    }

    /** A form with one field of each of the types the record tests need, approval off, used in General Medicine. */
    protected JsonNode liveSimpleForm(Clinic clinic, String name, ObjectNode... fields) throws Exception {
        JsonNode created = createForm(clinic, "untitled", name);
        ObjectNode draft = formDraftBody(created);
        ((ObjectNode) draft.get("settings")).put("approvalRequired", false);
        ArrayNode list = draft.putArray("fields");
        for (ObjectNode field : fields) {
            list.add(field);
        }
        JsonNode saved = saveFormDraft(clinic, created, draft, 200);
        return formAction(clinic, saved, "publish", 200);
    }

    protected ObjectNode formField(String key, String type, String label) {
        ObjectNode field = objectMapper.createObjectNode();
        field.put("key", key);
        field.put("type", type);
        field.put("label", label);
        field.put("required", false);
        field.put("width", 6);
        field.put("print", true);
        field.put("copyFromLast", false);
        field.putArray("whoCanFill").add("DOCTOR");
        return field;
    }

    protected ObjectNode choiceField(String key, String type, String label, String... optionKeyAndScore) {
        ObjectNode field = formField(key, type, label);
        ArrayNode options = field.putArray("options");
        for (String spec : optionKeyAndScore) {
            String[] parts = spec.split(":");
            ObjectNode option = options.addObject();
            option.put("key", parts[0]);
            option.put("label", parts[0].substring(0, 1).toUpperCase() + parts[0].substring(1));
            option.put("score", Integer.parseInt(parts[1]));
        }
        return field;
    }

    protected ObjectNode numberField(String key, String label, Integer min, Integer max) {
        ObjectNode field = formField(key, "NUMBER", label);
        if (min != null) {
            field.put("min", min);
        }
        if (max != null) {
            field.put("max", max);
        }
        return field;
    }
}
