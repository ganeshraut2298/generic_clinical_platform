package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-76 order and notice integration tests (docs/api/ipd.yaml 0.4.1, ADR-027, docs/database/SAH-76.md):
 * the urls and bodies of the new operations written out here, the exact texts of the contract copied here (never read from the
 * application), and state read back by SQL. The ward, nurses and pharmacist are those of {@link MarSupport#ward()}.
 */
abstract class Sah76Support extends ScopeSupport {

    static final String NOTICES_ALL = IPD + "/order-notices";
    static final String NOTICES_SUMMARY = NOTICES_ALL + "/summary";
    static final String REVIEWS = IPD + "/pharmacist-reviews";
    static final String NURSING_SETTINGS = IPD + "/nursing-settings";

    static final String ORDER_CHANGED_TEXT = "This order was changed by someone else. Reload and check the new order.";
    static final String NOTHING_CHANGED_TEXT = "Nothing was changed.";
    static final String RESUME_FIRST_TEXT = "Resume this order before changing it.";
    static final String STOPPED_TEXT = "This order is stopped. Order the medicine again.";
    static final String NOT_ACTIVE_TEXT = "This order is not active.";
    static final String NOT_ON_HOLD_TEXT = "This order is not on hold.";
    static final String NOT_WAITING_TEXT = "This order is not waiting for the pharmacist.";
    static final String NO_OPEN_QUERY_TEXT = "This order has no open query.";
    static final String WAITING_FOR_REPLY_TEXT = "This order is waiting for the doctor's reply.";
    static final String SELF_QUERY_TEXT = "The doctor who ordered this cannot check it. Ask the pharmacist.";
    static final String LIST_HIDDEN_TEXT = "You cannot open the order list.";
    static final String ACK_ONLY_TEXT = "Only nurses and the clinic owner can acknowledge orders.";
    static final String TEXT_CHARACTERS_TEXT = "Text cannot contain control or text-direction characters";

    // ---------------- urls ----------------

    static String changeUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/change";
    }

    static String holdUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/hold";
    }

    static String resumeUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/resume";
    }

    static String queryUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/query";
    }

    static String replyUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/query-reply";
    }

    static String noticesUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/order-notices";
    }

    static String noticeAckUrl(Object noticeId) {
        return NOTICES_ALL + "/" + noticeId + "/acknowledge";
    }

    static String markReviewedUrl(Object reviewId) {
        return REVIEWS + "/" + reviewId + "/mark-reviewed";
    }

    // ---------------- bodies ----------------

    /** hold, stop, query and reply bodies: clientKey, orderVersion and one text field. */
    protected static Map<String, Object> actBody(int orderVersion, String textField, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        m.put("orderVersion", orderVersion);
        if (textField != null) {
            m.put(textField, text);
        }
        return m;
    }

    /** A change body for a paracetamol order of {@link MedicineSupport#orderBody}: the dose changes, everything else is as placed. */
    protected static Map<String, Object> changeBody(int orderVersion, String reason, Object doseAmount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        m.put("orderVersion", orderVersion);
        m.put("reason", reason);
        m.put("doseAmount", doseAmount);
        m.put("doseUnit", "TABLET");
        m.put("routeKey", "ORAL");
        m.put("frequencyKey", "od");
        m.put("priority", "ROUTINE");
        return m;
    }

    /** A valid order body with the SAH-76 fields (mutable). */
    protected static Map<String, Object> newOrderBody(String drug) {
        return orderBody(newKey(), drug, "od", null, null);
    }

    protected static Map<String, Object> statBody(String drug) {
        Map<String, Object> m = newOrderBody(drug);
        m.remove("frequencyKey");
        m.put("priority", "STAT");
        return m;
    }

    protected ResultActions post(RequestPostProcessor auth, String url, Map<String, Object> body) throws Exception {
        return call(HttpMethod.POST, url, auth, js(body));
    }

    // ---------------- flows ----------------

    /** A PENDING order of the drug placed by the doctor through the real endpoint. */
    protected String placed(Ward w, String drug) throws Exception {
        return orderIdOf(place(w.doctor().auth(), w.adm().id(), drug));
    }

    /** An ACTIVE order: placed by the doctor, verified by the pharmacist (version 1). */
    protected String active(Ward w, String drug) throws Exception {
        String id = placed(w, drug);
        verifyOk(w.pharmacist(), w.pharmacistPin(), id);
        return id;
    }

    /** Switches pharmacist verification for the clinic's wards (no ward profile) off or on, through the real endpoint. */
    protected void verificationRequired(Clinic clinic, boolean required) throws Exception {
        ok(HttpMethod.PUT, NURSING_SETTINGS, clinic.owner(), "{\"medVerificationRequired\":" + required
                + ",\"doseWindowBeforeMinutes\":60,\"doseWindowAfterMinutes\":60,\"statWindowMinutes\":15,"
                + "\"unackFlagMinutes\":30,\"unackFlagStatMinutes\":5,\"undoSeconds\":10}", 200);
    }

    protected JsonNode emrOf(Ward w) throws Exception {
        return emr(w.doctor().auth(), w.adm().id());
    }

    protected JsonNode rowOf(Ward w, String orderId) throws Exception {
        JsonNode row = sheetRow(emrOf(w), orderId);
        assertThat(row).as("order " + orderId + " on the sheet").isNotNull();
        return row;
    }

    protected JsonNode noticesOf(RequestPostProcessor auth, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, noticesUrl(admissionId), auth, null, 200);
    }

    /** The notices of the admission whose kind is this one. */
    protected static List<JsonNode> noticesOfKind(JsonNode list, String kind) {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode n : list.get("items")) {
            if (kind.equals(n.get("kind").asText())) {
                out.add(n);
            }
        }
        return out;
    }

    // ---------------- state read by SQL ----------------

    protected String one(Clinic clinic, String sql, Object... params) throws SQLException {
        List<String> v = values(clinic, sql, params);
        return v.isEmpty() ? null : v.get(0);
    }

    protected String status(Clinic clinic, String orderId) throws SQLException {
        return one(clinic, "select status from ipd.medicine_order where id = ?::uuid", orderId);
    }

    protected long versions(Clinic clinic, String orderId) throws SQLException {
        return count(clinic, "select count(*) from ipd.medicine_order_version where order_id = ?::uuid", orderId);
    }

    protected long noticeRows(Clinic clinic, String orderId, String kind) throws SQLException {
        return count(clinic, "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid and kind = ?", orderId, kind);
    }

    protected long noticeRows(Clinic clinic, String orderId) throws SQLException {
        return count(clinic, "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid", orderId);
    }

    /** All clinical event text of the clinic as one text, for "never contains" checks. */
    protected String allEventText(Clinic clinic) throws SQLException {
        return one(clinic, "select coalesce(string_agg(text, E'\\n'), '') from ipd.clinical_event");
    }

    /** Everything the order actions may write, as one line, so a refused or repeated call is proved to have written nothing. */
    protected String writes(Clinic clinic, String admissionId) throws SQLException {
        return one(clinic, "select (select count(*) from ipd.medicine_order_version)::text || '/' || "
                + "(select count(*) from ipd.order_notice)::text || '/' || (select count(*) from ipd.order_hold)::text || '/' || "
                + "(select count(*) from ipd.medicine_order_query)::text || '/' || (select count(*) from ipd.pharmacist_review)::text "
                + "|| '/' || (select count(*) from ipd.clinical_event where admission_id = ?::uuid)::text || '/' || "
                + "(select count(*) from platform.audit_event)::text || '/' || "
                + "(select coalesce(string_agg(id::text || status || version || current_version, ',' order by id), '') "
                + "from ipd.medicine_order where admission_id = ?::uuid)", admissionId, admissionId);
    }

    protected static void assertProblem(JsonNode p, String type, String detail) {
        assertType(p, type);
        if (detail != null) {
            assertThat(p.get("detail").asText()).isEqualTo(detail);
        }
    }

    /** The message of a field error, or null. */
    protected static String msg(JsonNode problem, String field) {
        return problem.has("fieldErrors") ? fieldMessage(problem, field) : null;
    }

    /** The STAT slot of an order read from the nurse's MAR (first slot of the order). */
    protected Instant firstSlot(Ward w, String orderId) throws Exception {
        JsonNode mar = mar(w.nurse().auth(), w.adm().id());
        JsonNode slot = slot(mar, orderId, 0);
        return Instant.parse(slot.get("slotAt").asText());
    }

    protected static String uuid() {
        return UUID.randomUUID().toString();
    }
}
