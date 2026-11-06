package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-31 medicine order integration tests: a hospital with a doctor, a pharmacist who has set a real
 * PIN through the real endpoints, a nurse and an admitted patient with an allergy text of our choice; the urls and bodies
 * of docs/api/ipd.yaml written out here; the exact 403, 409 and 400 texts of the contract copied here, never read from the
 * application; state read back by SQL; and the forced-order technique of {@link SafetyConcurrencyIT} (a second connection
 * holds the admission row, the requests are started one after the other and each is seen waiting in pg_stat_activity, then
 * the lock is released).
 */
abstract class MedicineSupport extends PinTestSupport {

    static final String EMR_HIDDEN = "Clinical details are hidden for your role.";
    static final String ONLY_DOCTOR = "Only a doctor can do this.";
    static final String ONLY_PHARMACIST = "Only the pharmacist or the clinic owner can check orders.";
    static final String SELF_VERIFY = "The doctor who ordered this cannot verify it. Ask the pharmacist.";
    static final String NOT_ADMITTED_TEXT = "This patient is not admitted any more. Medicines cannot be ordered or changed.";
    static final String CLIENT_KEY_REUSED = "This order was already placed with different values. Reload the page and try again.";
    static final String ALREADY_STOPPED = "This order is already stopped.";
    static final String REJECTED_CANNOT_STOP = "This order was rejected by the pharmacist. It cannot be stopped.";
    static final String ALREADY_REJECTED = "This order was already rejected.";
    static final String STOPPED_BY_DOCTOR = "The doctor stopped this order.";
    static final String HARD_STOP_ON_ORDER = "Hard stop on this order. Reject it.";
    static final String CHECKS_CHANGED = "The checks on this order changed. Review them and try again.";
    static final String OFF_TEXT = "In-patients (IPD) is not switched on for your clinic.";
    static final String READONLY_TEXT = "IPD is read-only. Nothing can be changed.";
    static final String RELOAD_TEXT = "Reload the page and try again.";
    static final String INTERNAL_TEXT = "Something went wrong on our side. Please try again.";

    static final String CHECKS = IPD + "/pharmacist-checks";
    static final String CHECKS_SUMMARY = CHECKS + "/summary";

    static final String[] EMR_ROLES = {"OWNER", "DOCTOR", "NURSE"};
    static final String[] NON_EMR_ROLES = {"FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"};
    static final String[] ORDER_ROLES = {"OWNER", "DOCTOR"};
    static final String[] NON_ORDER_ROLES = {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "VISITING_DOCTOR", "COUNSELLOR"};
    static final String[] CHECK_ROLES = {"OWNER", "PHARMACIST"};
    static final String[] NON_CHECK_ROLES = {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "COUNSELLOR"};

    /** The 14 medicines of the mockup in list order: key, exact name, route, high alert (or null). */
    static final String[][] DRUGS = {
            {"pcm", "Tab. Paracetamol 650 mg", "Oral", null},
            {"panto", "Inj. Pantoprazole 40 mg", "IV", null},
            {"ondan", "Inj. Ondansetron 4 mg", "IV", null},
            {"ns", "IV Normal saline 500 ml", "IV", null},
            {"ceft", "Inj. Ceftriaxone 1 g", "IV", null},
            {"amox", "Tab. Amoxicillin + clavulanate 625 mg", "Oral", null},
            {"pip", "Inj. Piperacillin + tazobactam 4.5 g", "IV", null},
            {"cotri", "Tab. Cotrimoxazole 960 mg", "Oral", null},
            {"asp", "Tab. Aspirin 75 mg", "Oral", null},
            {"ibu", "Tab. Ibuprofen 400 mg", "Oral", null},
            {"met", "Tab. Metformin 500 mg", "Oral", null},
            {"hep", "Inj. Heparin 5,000 IU", "SC", "Anticoagulant"},
            {"ins", "Inj. Regular insulin, sliding scale", "SC", "Insulin"},
            {"kcl", "Inj. Potassium chloride 20 mmol in 500 ml", "IV infusion", "Concentrated electrolyte"}};

    static String drugName(String key) {
        for (String[] d : DRUGS) {
            if (d[0].equals(key)) {
                return d[1];
            }
        }
        throw new IllegalArgumentException(key);
    }

    static final String[][] REJECT_REASONS = {{"ALLERGY_CONFLICT", "Allergy conflict"},
            {"INTERACTION_RISK", "Interaction risk too high"}, {"WRONG_DOSE", "Wrong dose or frequency"},
            {"DUPLICATE_THERAPY", "Duplicate therapy"}};

    static final String[][] OVERRIDE_REASONS = {
            {"BENEFIT_MONITORED", "Benefit outweighs the risk, monitoring in place"},
            {"SPECIALIST_ADVISED", "Specialist advised this combination"},
            {"ALLERGY_HISTORY_CHECKED", "Allergy history checked with the patient: mild rash only, no anaphylaxis"},
            {"CARDIOLOGY_STENT", "Recent coronary stent, cardiology advised"}};

    // ---------------- urls ----------------

    static String emrUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/emr";
    }

    static String ordersUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/medicine-orders";
    }

    static String stopUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/stop";
    }

    static String verifyUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/verify";
    }

    static String rejectUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/reject";
    }

    // ---------------- a ward ----------------

    protected record World(Clinic clinic, Staff owner, Staff doctor, Pn pharmacist, String pin, Staff nurse, Adm adm) {
    }

    /** A hospital, a doctor, a nurse, a pharmacist with a PIN, and one admitted patient with this allergy text (may be null). */
    protected World world(String allergy) throws Exception {
        Clinic clinic = hospital();
        Staff doctor = staff(clinic, "DOCTOR");
        Staff nurse = staff(clinic, "NURSE");
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        String pin = goodPin(pharmacist);
        setPin(pharmacist, pin);
        return new World(clinic, ownerStaff(clinic), doctor, pharmacist, pin, nurse, admitWithAllergy(clinic, allergy));
    }

    protected Adm admitWithAllergy(Clinic clinic, String allergy) throws Exception {
        String name = "Meera Kulkarni " + uniqueMobile().substring(5);
        String mobile = uniqueMobile();
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("name", name);
        patient.put("age", 56);
        patient.put("sex", "F");
        patient.put("mobile", mobile);
        patient.put("area", "Shivaji Nagar");
        patient.put("language", "mr");
        if (allergy != null) {
            patient.put("allergy", allergy);
        }
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.owner(), js(patient), 201);
        UUID patientId = UUID.fromString(created.get("id").asText());
        JsonNode a = admitOk(clinic, body(patientId, clinic.doctorMembershipId(), newBed(clinic, "gen")));
        return new Adm(a.get("id").asText(), patientId, name, mobile, a.get("bedCode").asText());
    }

    protected void setAllergy(Clinic clinic, Adm adm, String allergy) throws Exception {
        ok(HttpMethod.PATCH, PATIENTS + "/" + adm.patientId(), clinic.owner(),
                "{\"allergy\":" + objectMapper.writeValueAsString(allergy) + "}", 200);
    }

    // ---------------- calls ----------------

    protected static Map<String, Object> orderBody(String key, String drug, String frequency, String reason, String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key);
        m.put("drugKey", drug);
        m.put("frequencyKey", frequency);
        if (reason != null) {
            m.put("overrideReasonKey", reason);
        }
        if (token != null) {
            m.put("checkToken", token);
        }
        return m;
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected ResultActions orderCall(RequestPostProcessor auth, Object admissionId, Map<String, Object> body) throws Exception {
        return call(HttpMethod.POST, ordersUrl(admissionId), auth, js(body));
    }

    protected ResultActions orderCall(RequestPostProcessor auth, Object admissionId, String drug) throws Exception {
        return orderCall(auth, admissionId, orderBody(newKey(), drug, "od", null, null));
    }

    /** An order that must be placed (201 PLACED). Returns the whole answer. */
    protected JsonNode place(RequestPostProcessor auth, Object admissionId, String drug) throws Exception {
        JsonNode a = ok(HttpMethod.POST, ordersUrl(admissionId), auth, js(orderBody(newKey(), drug, "od", null, null)), 201);
        assertThat(a.get("outcome").asText()).isEqualTo("PLACED");
        return a;
    }

    protected static String orderIdOf(JsonNode answer) {
        return answer.get("order").get("id").asText();
    }

    /** An order with warnings placed through NEEDS_REASON and then the reason with the token (201). */
    protected JsonNode placeWithReason(RequestPostProcessor auth, Object admissionId, String drug, String reason) throws Exception {
        String key = newKey();
        JsonNode first = ok(HttpMethod.POST, ordersUrl(admissionId), auth, js(orderBody(key, drug, "od", null, null)), 200);
        assertThat(first.get("outcome").asText()).isEqualTo("NEEDS_REASON");
        JsonNode second = ok(HttpMethod.POST, ordersUrl(admissionId), auth,
                js(orderBody(key, drug, "od", reason, first.get("check").get("checkToken").asText())), 201);
        assertThat(second.get("outcome").asText()).isEqualTo("PLACED");
        return second;
    }

    protected JsonNode emr(RequestPostProcessor auth, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, emrUrl(admissionId), auth, null, 200);
    }

    protected JsonNode queue(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, CHECKS, auth, null, 200);
    }

    protected JsonNode queue(RequestPostProcessor auth, String query) throws Exception {
        return ok(HttpMethod.GET, CHECKS + "?" + query, auth, null, 200);
    }

    protected JsonNode summary(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, CHECKS_SUMMARY, auth, null, 200);
    }

    /** The queue row of an order, or null. */
    protected static JsonNode queueRow(JsonNode queue, String orderId) {
        for (JsonNode r : queue.get("items")) {
            if (orderId.equals(r.get("orderId").asText())) {
                return r;
            }
        }
        return null;
    }

    protected String tokenOfOrder(RequestPostProcessor auth, String orderId) throws Exception {
        JsonNode row = queueRow(queue(auth, "limit=100"), orderId);
        assertThat(row).as("the order is in the queue").isNotNull();
        return row.get("check").get("checkToken").asText();
    }

    protected static String verifyBody(String pin, String token) {
        return "{\"pin\":" + (pin == null ? "null" : "\"" + pin + "\"") + ",\"checkToken\":"
                + (token == null ? "null" : "\"" + token + "\"") + "}";
    }

    protected ResultActions verifyCall(Pn p, String orderId, String pin, String token) throws Exception {
        freshPinAllowance(p);
        return call(HttpMethod.POST, verifyUrl(orderId), p.auth(), verifyBody(pin, token));
    }

    /** A verify that must succeed: reads the token from the queue first. */
    protected JsonNode verifyOk(Pn p, String pin, String orderId) throws Exception {
        String token = tokenOfOrder(p.auth(), orderId);
        freshPinAllowance(p);
        return ok(HttpMethod.POST, verifyUrl(orderId), p.auth(), verifyBody(pin, token), 200);
    }

    protected ResultActions rejectCall(RequestPostProcessor auth, String orderId, String reason) throws Exception {
        return call(HttpMethod.POST, rejectUrl(orderId), auth,
                "{\"reasonKey\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}");
    }

    protected JsonNode rejectOk(RequestPostProcessor auth, String orderId, String reason) throws Exception {
        return ok(HttpMethod.POST, rejectUrl(orderId), auth, "{\"reasonKey\":\"" + reason + "\"}", 200);
    }

    protected JsonNode stopOk(RequestPostProcessor auth, String orderId) throws Exception {
        return ok(HttpMethod.POST, stopUrl(orderId), auth, null, 200);
    }

    protected static JsonNode sheetRow(JsonNode sheet, String orderId) {
        for (JsonNode r : sheet.get("orders")) {
            if (orderId.equals(r.get("id").asText())) {
                return r;
            }
        }
        return null;
    }

    // ---------------- state read by SQL ----------------

    protected record OrderState(String status, long version, String orderedBy, String verifiedBy, String rejectedBy,
                                String stoppedBy, String rejectReason, String overrideReason, String warnings,
                                boolean hasVerifiedAt, boolean hasRejectedAt, boolean hasStoppedAt) {
    }

    protected OrderState state(Clinic clinic, String orderId) throws SQLException {
        List<String> v = values(clinic, "select status, version::text, ordered_by::text, coalesce(verified_by::text, ''), "
                + "coalesce(rejected_by::text, ''), coalesce(stopped_by::text, ''), coalesce(reject_reason_key, ''), "
                + "coalesce(override_reason_key, ''), warnings::text, (verified_at is not null)::text, "
                + "(rejected_at is not null)::text, (stopped_at is not null)::text from ipd.medicine_order where id = ?::uuid",
                orderId);
        return new OrderState(v.get(0), Long.parseLong(v.get(1)), v.get(2), v.get(3), v.get(4), v.get(5), v.get(6), v.get(7),
                v.get(8), Boolean.parseBoolean(v.get(9)), Boolean.parseBoolean(v.get(10)), Boolean.parseBoolean(v.get(11)));
    }

    protected long orderCount(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.medicine_order where admission_id = ?::uuid", admissionId);
    }

    protected long medAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_MED\\_%'");
    }

    /**
     * The audit rows written by the medicine order actions (IPD_MED_*) and by the staff PIN (PLATFORM_PIN_*) as one text. Since
     * the review fix pass (S2) the patient audit rows hold only the two recorded flags, so the privacy checks may also look at
     * the whole trail with {@code allAuditText}; this narrower view stays for the tests that want the rows of SAH-31 alone.
     */
    protected String sahaj31AuditText(Clinic clinic) throws SQLException {
        return values(clinic, "select coalesce(string_agg(action || ' ' || coalesce(before_json::text, '') || ' ' || "
                + "coalesce(after_json::text, ''), E'\\n'), '') from platform.audit_event where action like 'IPD\\_MED\\_%' "
                + "or action like 'PLATFORM\\_PIN\\_%'").get(0);
    }

    protected long accessRows(Clinic clinic, Adm adm) throws SQLException {
        return recordAccess(clinic, adm.patientId());
    }

    /** Everything an order action may write, so a refused or repeated call can be proved to have written nothing. */
    protected record MedSnap(String orders, long events, long audits, long access, String pinRows) {
    }

    protected MedSnap medSnap(Clinic clinic, Adm adm) throws SQLException {
        return new MedSnap(values(clinic, "select coalesce(string_agg(id::text || status || version || coalesce(verified_by::text, '-') "
                + "|| coalesce(rejected_by::text, '-') || coalesce(stopped_by::text, '-') || coalesce(override_reason_key, '-') "
                + "|| warnings::text, ',' order by id), '') from ipd.medicine_order where admission_id = ?::uuid", adm.id()).get(0),
                eventCount(clinic, adm.id()), allAudits(clinic), accessRows(clinic, adm),
                values(clinic, "select coalesce(string_agg(membership_id::text || failed_count || lock_count || version, ',' "
                        + "order by membership_id), '') from platform.staff_pin").get(0));
    }

    protected static JsonNode finding(JsonNode check, String list, String code) {
        for (JsonNode f : check.get(list)) {
            if (code.equals(f.get("code").asText())) {
                return f;
            }
        }
        return null;
    }

    // ---------------- two requests at the same moment, in a forced order ----------------

    protected record Resp(int status, String body) {
        JsonNode json(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            return mapper.readTree(body);
        }

        String type(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            String t = mapper.readTree(body).get("type").asText();
            return t.substring(t.lastIndexOf('/') + 1);
        }

        String detail(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            return mapper.readTree(body).get("detail").asText();
        }
    }

    protected record Req(HttpMethod method, String url, RequestPostProcessor auth, String body, Runnable beforeSend) {
        Req(HttpMethod method, String url, RequestPostProcessor auth, String body) {
            this(method, url, auth, body, null);
        }
    }

    private Callable<Resp> task(Req r) {
        return () -> {
            if (r.beforeSend() != null) {
                r.beforeSend().run();
            }
            var builder = request(r.method(), URI.create(r.url())).with(r.auth());
            if (r.body() != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(r.body());
            }
            var res = mvc.perform(builder).andReturn().getResponse();
            return new Resp(res.getStatus(), res.getContentAsString());
        };
    }

    /** Starts all requests at the same moment. */
    protected List<Resp> parallel(Req... requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Req r : requests) {
                Callable<Resp> t = task(r);
                futures.add(pool.submit(() -> {
                    start.await();
                    return t.call();
                }));
            }
            start.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    protected Connection holdAdmissionLock(Clinic clinic, String admissionId) throws Exception {
        Connection holder = db().app();
        holder.setAutoCommit(false);
        try (PreparedStatement bind = holder.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            bind.setString(1, clinic.tenantId().toString());
            bind.execute();
        }
        try (PreparedStatement lock = holder.prepareStatement("select id from ipd.admission where id = ?::uuid for update")) {
            lock.setString(1, admissionId);
            assertThat(lock.executeQuery().next()).isTrue();
        }
        return holder;
    }

    /**
     * Runs the requests in the given order: the admission row is held by another connection, each request is started and
     * seen waiting on the admission row before the next one starts, then the lock is released. The first in the queue gets
     * the row first. Answers come back in request order. No answer may be a server error.
     */
    protected List<Resp> ordered(Clinic clinic, String admissionId, Req... reqs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(reqs.length);
        List<Future<Resp>> futures = new ArrayList<>();
        try (Connection holder = holdAdmissionLock(clinic, admissionId)) {
            for (int i = 0; i < reqs.length; i++) {
                futures.add(pool.submit(task(reqs[i])));
                assertThat(awaitLockWaiters("%from ipd.admission%update%", i + 1, 60_000))
                        .as("request " + (i + 1) + " waits on the admission row").isTrue();
            }
            for (Future<Resp> f : futures) {
                assertThat(f.isDone()).isFalse();
            }
            holder.rollback();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                Resp r = f.get(90, TimeUnit.SECONDS);
                assertThat(r.status()).as("no server error: " + r.body()).isLessThan(500);
                out.add(r);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    protected Req orderReq(RequestPostProcessor auth, String admissionId, String key, String drug, String reason, String token)
            throws Exception {
        return new Req(HttpMethod.POST, ordersUrl(admissionId), auth, js(orderBody(key, drug, "od", reason, token)));
    }

    protected Req stopReq(RequestPostProcessor auth, String orderId) {
        return new Req(HttpMethod.POST, stopUrl(orderId), auth, null);
    }

    protected Req rejectReq(RequestPostProcessor auth, String orderId, String reason) {
        return new Req(HttpMethod.POST, rejectUrl(orderId), auth, "{\"reasonKey\":\"" + reason + "\"}");
    }

    protected Req verifyReq(Pn p, String orderId, String pin, String token) {
        return new Req(HttpMethod.POST, verifyUrl(orderId), p.auth(), verifyBody(pin, token), () -> freshPinAllowance(p));
    }

    protected Req dischargeReq(Clinic clinic, Adm adm) throws Exception {
        sign(clinic, adm.id(), "normal", "general");
        JsonNode sheet = sheet(clinic, adm.id());
        return new Req(HttpMethod.POST, dischargeUrl(adm.id()), clinic.owner(),
                dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "UPI"));
    }
}
