package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the Doctor EMR phase 4 integration tests (SAH-49, 60 to 66, 69 to 71; ADR-023, docs/api/opd.yaml 0.5.1): a
 * record that has taken the layout of one of the seven specialty starters, builders for the specialty component values
 * ({@code values.specialty}), saves that carry the screen facts (visit mode, Oncology decision), a fully worked oncology
 * assessment that makes every chemotherapy safety check pass, the order sheet calls, and the staff PIN set-up for the
 * second check at the prescriber's device (the code is read the one permitted way, the dev/test log line, as in
 * {@link PinTestSupport}). Everything else goes through the real endpoints as {@code sahaj_app}. Throwaway data only.
 */
abstract class EmrSpecialtySupport extends EmrPhase3Support {

    static final String REFERENCE = EMR + "/specialty-reference/";
    static final String CHEMO_CHECKS = EMR + "/chemo-checks";
    static final String VISIT_TEMPLATES = EMR + "/visit-templates";
    static final String ME_URL = "/api/v1/platform/me";
    static final String TAG = "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed";
    static final String[] PROFILE_KEYS = {"gm", "dental", "og", "paeds", "physio", "onco", "surgery"};
    static final String[] CHECKER_ROLES = {"NURSE", "PHARMACIST", "DOCTOR", "VISITING_DOCTOR"};
    static final String[] NOT_CHECKER_ROLES = {"FRONT_DESK", "BILLING", "COUNSELLOR"};

    private static final Pattern CODE_IN_LOG = Pattern.compile("One-time code for [^:]+:\\s*(\\d{6})");
    private static final String[] GOOD_PINS = {"4827", "7391", "5164", "8203", "3946", "6058", "9417", "1583", "7249", "3681", "5972", "4306"};
    private static int nextGood;

    @Autowired
    protected ApplicationContext applicationContext;

    // ---------------- records of a specialty ----------------

    /** A new clinic with a General physician, a patient and a visit; the record takes the layout of this starter department. */
    protected Rec specialtyRec(String starterKey) throws Exception {
        Clinic clinic = newClinic();
        return specialtyRec(clinic, addDoctor(clinic, "General physician"), newPatient(clinic), starterKey);
    }

    protected Rec specialtyRec(Clinic clinic, Staff doctor, UUID patientId, String starterKey) throws Exception {
        Rec rec = switchDepartment(startRecord(clinic, doctor, patientId), starterKey);
        assertThat(rec.record().get("layout").get("starterKey").asText()).as("the record took the " + starterKey + " layout").isEqualTo(starterKey);
        return rec;
    }

    /** A second visit of the same patient with the same doctor, on the same starter layout. */
    protected Rec anotherVisit(Rec rec, String starterKey) throws Exception {
        return specialtyRec(rec.clinic(), rec.doctor(), rec.patientId(), starterKey);
    }

    // ---------------- component values ----------------

    protected ObjectNode sp(String component, String bodyJson) throws Exception {
        ObjectNode value = (ObjectNode) objectMapper.readTree(bodyJson);
        value.put("component", component);
        value.put("schemaVersion", 1);
        return value;
    }

    protected ObjectNode spSection(String sectionKey, ObjectNode value) {
        return oneField(sectionKey, "specialty", value);
    }

    protected ObjectNode spSection(String sectionKey, String component, String bodyJson) throws Exception {
        return spSection(sectionKey, sp(component, bodyJson));
    }

    /** A draft save with the screen facts (mode and/or Oncology decision) and these sections. */
    protected Rec saveScreen(Rec rec, String screenJson, ObjectNode... sections) throws Exception {
        ObjectNode body = draftBody(rec.revision(), "ASSESSMENT", null, sections);
        body.set("screen", objectMapper.readTree(screenJson));
        return rec.with(saveRecordDraft(rec.token(), rec.id(), body, 200));
    }

    protected JsonNode refuseScreen(Rec rec, int status, String slug, String screenJson, ObjectNode... sections) throws Exception {
        ObjectNode body = draftBody(rec.revision(), "ASSESSMENT", null, sections);
        body.set("screen", objectMapper.readTree(screenJson));
        return problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(), body.toString(), status, slug);
    }

    protected static JsonNode specialtyOf(Rec rec) {
        return rec.record().get("specialty");
    }

    protected static JsonNode specialtyOf(JsonNode record) {
        return record.get("specialty");
    }

    protected static List<String> hiddenKeys(JsonNode record) {
        List<String> keys = new ArrayList<>();
        specialtyOf(record).get("hiddenSectionKeys").forEach(n -> keys.add(n.asText()));
        return keys;
    }

    protected static List<String> neededKeys(JsonNode record) {
        List<String> keys = new ArrayList<>();
        record.get("stillNeededItems").forEach(n -> keys.add(n.get("key").asText()));
        return keys;
    }

    protected static List<String> neededSections(JsonNode record) {
        List<String> keys = new ArrayList<>();
        record.get("stillNeeded").forEach(n -> keys.add(n.asText()));
        return keys;
    }

    // ---------------- reference ----------------

    protected JsonNode reference(Rec rec, String profile) throws Exception {
        return get(REFERENCE + profile, rec.token());
    }

    protected static List<String> keysOfList(JsonNode reference, String listName) {
        List<String> keys = new ArrayList<>();
        reference.get("lists").get(listName).forEach(n -> keys.add(n.get("key").asText()));
        return keys;
    }

    // ---------------- the oncology assessment and the order ----------------

    protected ObjectNode oncExam(double heightCm, double weightKg, String lineDevice, String... lineChecks) throws Exception {
        ObjectNode exam = sp("ONC_EXAM", "{\"capAt2\":false,\"signs\":{},\"lesions\":[],\"newLesionSeen\":false,\"breastSkin\":[],\"systems\":{},\"line\":{\"device\":"
                + (lineDevice == null ? "null" : "\"" + lineDevice + "\"") + ",\"checks\":[]}}");
        exam.put("heightCm", heightCm);
        exam.put("weightKg", weightKg);
        ArrayNode checks = (ArrayNode) exam.get("line").get("checks");
        for (String check : lineChecks) {
            checks.add(check);
        }
        return exam;
    }

    /** Labs typed by the doctor, drawn this many hours ago; hb 12.0, ANC 3000, platelets 250, creatinine 0.8, bilirubin 0.6, ALT 25, LVEF 60. */
    protected ObjectNode oncLabs(long hoursAgo, String valuesJson) throws Exception {
        ObjectNode labs = sp("ONC_LABS", "{\"values\":" + valuesJson + "}");
        labs.put("drawnAt", Instant.now().minus(hoursAgo, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString());
        return labs;
    }

    protected static final String NORMAL_LABS = "{\"hb\":12.0,\"anc\":3000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":60}";

    /** A record on the oncology layout with today's weight and height, fresh normal labs, ECOG 1 and the decision Give as planned. */
    protected Rec oncoAssessed(Rec onco, double heightCm, double weightKg) throws Exception {
        Rec saved = saveScreen(onco, "{\"oncDecision\":{\"choice\":\"GIVE_AS_PLANNED\",\"reasonKey\":null}}",
                spSection("onc_bsa", oncExam(heightCm, weightKg, "PORT", "FLUSHES_FREELY", "BLOOD_RETURN", "NO_REDNESS_SWELLING")),
                spSection("onc_labs", oncLabs(1, NORMAL_LABS)), spSection("onc_ecog", "ONC_ECOG", "{\"grade\":1}"));
        return saved;
    }

    protected Rec oncoAssessed(Rec onco) throws Exception {
        return oncoAssessed(onco, 160, 60.0);
    }

    protected Rec newOnco() throws Exception {
        return specialtyRec("onco");
    }

    protected static String orderUrl(Rec rec) {
        return RECORDS + "/" + rec.id() + "/chemo-order";
    }

    /** The body that saves the order that passes all nine checks of a protocol without a vesicant (paclitaxel 3-weekly). */
    protected static String passingOrder(long revision) {
        return orderBody(revision, "PACLI_3W", 100, "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\"");
    }

    protected static String orderBody(long revision, String protocol, int level, String more) {
        return "{\"revision\":" + revision + ",\"protocolKey\":\"" + protocol + "\",\"levelPct\":" + level + (more == null || more.isEmpty() ? "" : "," + more) + "}";
    }

    protected JsonNode saveOrder(Rec rec, String body) throws Exception {
        return put(orderUrl(rec), rec.token(), body, 200);
    }

    protected JsonNode getOrder(Rec rec) throws Exception {
        return get(orderUrl(rec), rec.token());
    }

    /** An assessed oncology record whose order is saved and passes every check: returns the record and the order sheet. */
    protected record Sheet(Rec rec, JsonNode order) {

        long revision() {
            return order.get("revision").asLong();
        }

        String fingerprint() {
            return order.get("fingerprint").asText();
        }
    }

    protected Sheet passingSheet() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode order = saveOrder(rec, passingOrder(0));
        assertThat(order.get("status").asText()).isEqualTo("DRAFT");
        assertThat(order.get("passedCount").asInt()).as("every check passes: " + order.get("checks")).isEqualTo(order.get("totalCount").asInt());
        return new Sheet(rec, order);
    }

    protected Sheet nameChecker(Sheet sheet, Staff checker) throws Exception {
        JsonNode named = put(orderUrl(sheet.rec()) + "/checker", sheet.rec().token(),
                "{\"revision\":" + sheet.revision() + ",\"checkerMembershipId\":\"" + checker.membershipId() + "\"}", 200);
        return new Sheet(sheet.rec(), named);
    }

    protected Sheet reread(Sheet sheet) throws Exception {
        return new Sheet(sheet.rec(), getOrder(sheet.rec()));
    }

    protected JsonNode summary(Clinic clinic, Staff checker, String orderId) throws Exception {
        return get(CHEMO_CHECKS + "/" + orderId, checker.token(clinic));
    }

    protected String confirmBody(String fingerprint) {
        return "{\"fingerprint\":\"" + fingerprint + "\"}";
    }

    protected String pinBody(Sheet sheet, Staff checker, String pin) {
        return "{\"orderRevision\":" + sheet.revision() + ",\"fingerprint\":\"" + sheet.fingerprint() + "\",\"checkerMembershipId\":\"" + checker.membershipId()
                + "\",\"pin\":\"" + pin + "\"}";
    }

    protected String sendBody(Sheet sheet) {
        return "{\"revision\":" + sheet.revision() + ",\"fingerprint\":\"" + sheet.fingerprint() + "\"}";
    }

    protected String orderId(Sheet sheet) {
        return sheet.order().get("id").asText();
    }

    /** The order row as text columns: status, checker, confirmed_via, confirmed_at set, confirmed fingerprint equals fingerprint, cleared stamp set. */
    protected List<String> orderRow(Clinic clinic, String orderId) throws SQLException {
        return row(clinic, "select status, checker_membership_id::text, confirmed_via, (confirmed_at is not null)::text, (confirmed_fingerprint = fingerprint)::text, "
                + "(confirmation_cleared_at is not null)::text, confirmed_by_user_id::text from opd.chemo_order where id = ?::uuid", orderId);
    }

    /** Sends the sheet confirmed by a checker's own sign-in: the checker reads the summary and confirms. Returns the new sheet read. */
    protected Sheet confirmBySignIn(Sheet named, Staff checker) throws Exception {
        String orderId = orderId(named);
        JsonNode summary = summary(named.rec().clinic(), checker, orderId);
        post(CHEMO_CHECKS + "/" + orderId + "/confirm", checker.token(named.rec().clinic()), confirmBody(summary.get("fingerprint").asText()), 200);
        return reread(named);
    }

    // ---------------- staff PIN (a colleague's PIN at the prescriber's device) ----------------

    protected String mobileOf(Staff staff) throws SQLException {
        return globalRows("select mobile from platform.app_user where id = ?::uuid", staff.userId().toString()).get(0).get(0);
    }

    protected static synchronized String goodPin(String mobile) {
        for (int i = 0; i < GOOD_PINS.length; i++) {
            String candidate = GOOD_PINS[nextGood++ % GOOD_PINS.length];
            if (!mobile.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no good pin");
    }

    protected static String wrongPin(String right) {
        return "9306".equals(right) ? "9307" : "9306";
    }

    /** The person sets their own PIN through the real endpoints (code by the permitted dev log line); returns the PIN. */
    protected String setPin(Clinic clinic, Staff staff) throws Exception {
        String mobile = mobileOf(staff);
        String pin = goodPin(mobile);
        try (Connection c = db().app(); PreparedStatement ps = c.prepareStatement(
                "update platform.otp_challenge set last_sent_at = now() - interval '10 minutes' where mobile = ?")) {
            ps.setString(1, mobile);
            ps.executeUpdate();
        }
        String code;
        try (LogCapture logs = new LogCapture()) {
            call(HttpMethod.POST, "/api/v1/platform/me/pin/code", staff.token(clinic), null).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
            Matcher m = CODE_IN_LOG.matcher(logs.allText());
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            assertThat(last).as("the permitted dev log line with the code").isNotNull();
            code = last;
        }
        ok(HttpMethod.PUT, "/api/v1/platform/me/pin", staff.token(clinic),
                "{\"code\":\"" + code + "\",\"newPin\":\"" + pin + "\",\"confirmPin\":\"" + pin + "\"}", 200);
        return pin;
    }

    /** Forgets this caller's failed-PIN buckets, so a test that needs many wrong tries is not stopped by the rate limit (the limits have their own tests). */
    protected void freshPinAllowance(Clinic clinic, Staff caller) {
        try {
            Object limits = applicationContext.getBean(com.sahaj.platform.infrastructure.ratelimit.InMemoryStaffPinLimits.class);
            for (String limiter : new String[] {"pinFailures", "pinSets", "colleagueFailuresShort", "colleagueFailuresDay"}) {
                java.lang.reflect.Field field = limits.getClass().getDeclaredField(limiter);
                field.setAccessible(true);
                Object bucket = field.get(limits);
                java.lang.reflect.Field hits = bucket.getClass().getDeclaredField("hits");
                hits.setAccessible(true);
                ((java.util.Map<?, ?>) hits.get(bucket)).remove(clinic.tenantId() + ":" + caller.userId());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the limiter changed shape: " + e, e);
        }
    }

    protected static boolean hasBarePin(String text, String pin) {
        return Pattern.compile("(?<![0-9A-Za-z.:\\-])" + Pattern.quote(pin) + "(?![0-9A-Za-z.:\\-])").matcher(text).find();
    }

    // ---------------- small things ----------------

    protected static String uuid() {
        return UUID.randomUUID().toString();
    }

    protected String allAuditText(Clinic clinic) throws SQLException {
        return row(clinic, "select coalesce(string_agg(action || ' ' || coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, ''), E'\\n'), '') "
                + "from platform.audit_event").get(0);
    }
}
