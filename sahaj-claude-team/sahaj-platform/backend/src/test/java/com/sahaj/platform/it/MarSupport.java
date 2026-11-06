package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.MedicineOrderRepository;
import com.sahaj.ipd.application.VitalsRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-32 integration tests (Medicines (MAR) and giving a dose): a ward with a doctor, two nurses (the
 * second has a real PIN, set through the real endpoints), a pharmacist with a PIN and one admitted patient; orders that are
 * placed and verified through the real endpoints; the urls and bodies of docs/api/ipd.yaml written out here; the exact
 * texts of the contract and ADR-011 copied here, never read from the application; the dose rows, events, audit rows and
 * record-access rows read back by SQL.
 *
 * <p><b>The clock seam.</b> The MAR decides "due", "late" and "today" with the database clock, read through
 * {@code MedicineOrderRepository.databaseNow()} (ADR-011 D2: never the application clock). A real clock cannot be put at
 * 7:00 AM or 9:01 AM on demand, so the classes that extend this one run in their own Spring context with that call (and
 * {@code VitalsRepository.databaseNow()}, which the Nursing sheet uses for its tab count) replaced by a test spy that answers
 * a fixed instant when {@link #clockAt} was called and the real database clock otherwise. The spy changes nothing else: the
 * lock, the insert, {@code recorded_at} and every guard run against the real database.
 *
 * <p>A fixed clock on today's IST date needs orders that were verified long before it, or the "verified today" rule
 * (AC10) would depend on the real time of the test run; {@link #activeOrder} therefore backdates the order to the day before
 * (as the table owner, guard trigger switched off inside one transaction, the way {@link PinTestSupport#forcePinRow} does).
 * The tests of AC10 itself set the verification time on purpose with {@link #verifiedAt}.
 */
abstract class MarSupport extends MedicineSupport {

    // ---------------- texts of the contract (docs/api/ipd.yaml, ADR-011) ----------------
    static final String ONLY_GIVERS = "Only nurses and the clinic owner can give medicines.";
    /** Build-time choice of the java-developer (DEC-018, not in the contract). */
    static final String MAR_HIDDEN = "You cannot open the medicine round.";
    static final String SCAN_FIRST = "Scan the wristband first.";
    static final String MISMATCH_TEXT = "Wristband does not match this patient. Stop. Check the patient before giving anything.";
    static final String CONFIRM_TEXT = "Confirm the second identifier: name and date of birth.";
    static final String HIGH_ALERT_TEXT = "High-alert medicine: a second nurse must check it and enter a 4-digit PIN.";
    static final String DIFFERENT_NURSE = "The second check must be a different nurse.";
    static final String CHOOSE_NURSE = "Choose a nurse from the list.";
    static final String CHOOSE_REASON = "Choose why the dose is not given.";
    static final String DOSE_NOT_ADMITTED = "This patient is not admitted any more. Doses cannot be recorded.";
    static final String NOT_VERIFIED = "This order is not checked by the pharmacist yet.";
    static final String DOSE_STOPPED = "This medicine was stopped. Its doses can no longer be recorded.";
    static final String DOSE_REJECTED = "This order was rejected by the pharmacist. It cannot be given.";
    static final String NOT_A_DOSE_TIME = "This is not a dose time of this medicine today. Reload the page and try again.";
    static final String BEFORE_START = "This dose time is before the pharmacist checked the order.";
    static final String NOT_DUE = "This dose is not due yet. It can be given from 1 hour before its time.";
    static final String DOSE_RECORDED = "This dose is already recorded.";
    static final String DOSE_KEY_REUSED = "This dose was already recorded with different values. Reload the page.";
    static final String COLLEAGUE_NOT_SET = "The second nurse has not set a PIN yet. Choose another nurse.";
    static final String LOCK_READONLY = "IPD is read-only. Doses cannot be recorded.";
    static final String LOCK_NOT_ADMITTED = "This patient is not admitted any more.";

    static final String[][] REASONS = {{"PATIENT_REFUSED", "Patient refused"}, {"HELD_DOCTOR", "Held on doctor's advice"},
            {"NIL_BY_MOUTH", "Nil by mouth"}, {"NOT_AVAILABLE", "Medicine not available"},
            {"AWAY_FROM_WARD", "Patient away from the ward"}};

    static final String[] MAR_ROLES = {"OWNER", "NURSE", "DOCTOR"};
    static final String[] NON_MAR_ROLES = {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"};
    static final String[] GIVE_ROLES = {"OWNER", "NURSE"};
    static final String[] NON_GIVE_ROLES = {"DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"};

    static final String DOSE_TABLE = "ipd.dose_administration";
    static final String[] DOSE_TABLES = {"ipd.dose_administration", "ipd.medicine_order", "ipd.admission"};

    // ---------------- the clock seam ----------------

    @MockitoSpyBean
    protected MedicineOrderRepository orderClock;

    @MockitoSpyBean
    protected VitalsRepository vitalsClock;

    /** The instant the database clock answers while a test has set it; null means the real clock. */
    private static volatile Instant fixedNow;

    /** The IST date of this test (read once, so a test that runs across midnight still uses one day). */
    protected LocalDate day;

    @BeforeEach
    void installClockHook() {
        day = LocalDate.now(IST);
        Mockito.doAnswer(call -> fixedNow != null ? fixedNow : call.callRealMethod()).when(orderClock).databaseNow();
        Mockito.doAnswer(call -> fixedNow != null ? fixedNow : call.callRealMethod()).when(vitalsClock).databaseNow();
    }

    @AfterEach
    void removeClockHook() {
        fixedNow = null;
    }

    /** The instant of a clock time on this test's IST date. */
    protected Instant ist(int hour, int minute) {
        return day.atTime(hour, minute).atZone(IST).toInstant();
    }

    protected Instant ist(int hour, int minute, int second) {
        return day.atTime(hour, minute, second).atZone(IST).toInstant();
    }

    protected Instant istOn(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(IST).toInstant();
    }

    /** From now on the database clock reads this instant (until the test ends). */
    protected void clockAt(Instant instant) {
        fixedNow = instant;
    }

    protected void clockAt(int hour, int minute) {
        clockAt(ist(hour, minute));
    }

    protected void realClock() {
        fixedNow = null;
    }

    // ---------------- a ward ----------------

    protected record Ward(Clinic clinic, Staff doctor, Pn nurse, Pn nurse2, String nurse2Pin, Pn pharmacist,
                          String pharmacistPin, Adm adm, String uhid) {
    }

    /** A hospital with a doctor, a nurse (no PIN), a second nurse and a pharmacist (both with a PIN) and one admitted patient. */
    protected Ward ward() throws Exception {
        Clinic clinic = hospital();
        Staff doctor = staff(clinic, "DOCTOR");
        Pn nurse = newPn(clinic, "NURSE");
        Pn nurse2 = newPn(clinic, "NURSE");
        String nurse2Pin = goodPin(nurse2);
        setPin(nurse2, nurse2Pin);
        Pn pharmacist = newPn(clinic, "PHARMACIST");
        String pharmacistPin = goodPin(pharmacist);
        setPin(pharmacist, pharmacistPin);
        Adm adm = admitWithAllergy(clinic, "None known");
        String uhid = values(clinic, "select uhid from patient.patient where id = ?::uuid", adm.patientId()).get(0);
        return new Ward(clinic, doctor, nurse, nurse2, nurse2Pin, pharmacist, pharmacistPin, adm, uhid);
    }

    /** Another nurse of the same hospital who has set a PIN. */
    protected record NurseWithPin(Pn nurse, String pin) {
    }

    protected NurseWithPin nurseWithPin(Clinic clinic) throws Exception {
        Pn n = newPn(clinic, "NURSE");
        String pin = goodPin(n);
        setPin(n, pin);
        return new NurseWithPin(n, pin);
    }

    /** The name the directory shows for a person (the membership display name). */
    protected String displayName(Pn p) throws SQLException {
        return nameOf(p.clinic(), p.userId());
    }

    // ---------------- orders ----------------

    /** A PENDING order (placed by the doctor). */
    protected String pendingOrder(Ward w, String drug, String frequency) throws Exception {
        JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), drug, frequency, null, null)), 201);
        assertThat(a.get("outcome").asText()).isEqualTo("PLACED");
        return orderIdOf(a);
    }

    /** An ACTIVE order that the pharmacist verified through the real endpoint; verified_at is the real time. */
    protected String freshActiveOrder(Ward w, String drug, String frequency) throws Exception {
        String id = pendingOrder(w, drug, frequency);
        verifyOk(w.pharmacist(), w.pharmacistPin(), id);
        return id;
    }

    /**
     * An ACTIVE order that was placed and verified the day before this test's date (06:00 and 07:00 IST), so no slot of
     * today is "before the order started" whatever the real time of the run is.
     */
    protected String activeOrder(Ward w, String drug, String frequency) throws Exception {
        String id = freshActiveOrder(w, drug, frequency);
        LocalDate before = day.minusDays(1);
        verifiedAt(w.clinic(), id, istOn(before, 6, 0), istOn(before, 7, 0));
        return id;
    }

    /** An ACTIVE order verified at this IST clock time of the test's date (it was placed an hour earlier). */
    protected String activeOrderVerifiedAt(Ward w, String drug, String frequency, int hour, int minute) throws Exception {
        String id = freshActiveOrder(w, drug, frequency);
        Instant verified = ist(hour, minute);
        verifiedAt(w.clinic(), id, verified.minusSeconds(3600), verified);
        return id;
    }

    /**
     * Sets ordered_at and verified_at of an order as the table owner with the order guard trigger switched off inside one
     * transaction (committed). The application cannot write these columns; the clock seam needs the order to be as old as
     * the fixed clock says. A stopped order needs {@code stopped_at} to stay after verified_at, which the callers see to.
     */
    protected void verifiedAt(Clinic clinic, String orderId, Instant orderedAt, Instant verifiedAt) throws SQLException {
        asOwner(true, new String[] {"ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger trg_medicine_order_guard_update");
            try (PreparedStatement ps = c.prepareStatement("update ipd.medicine_order set ordered_at = ?, verified_at = ? "
                    + "where id = ?::uuid")) {
                ps.setTimestamp(1, java.sql.Timestamp.from(orderedAt));
                ps.setTimestamp(2, java.sql.Timestamp.from(verifiedAt));
                ps.setString(3, orderId);
                assertThat(ps.executeUpdate()).as("rows backdated").isOne();
            }
            DbAccess.execute(c, "alter table ipd.medicine_order enable trigger trg_medicine_order_guard_update");
            return null;
        });
    }

    // ---------------- urls and bodies ----------------

    static String marUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/mar";
    }

    static String dosesUrl(Object orderId) {
        return IPD + "/medicine-orders/" + orderId + "/doses";
    }

    /** A GIVEN body for a non-high-alert medicine with the right wristband. */
    protected Map<String, Object> given(Ward w, Instant slot) {
        return given(slot, w.uhid());
    }

    protected static Map<String, Object> given(Instant slot, String wristband) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        m.put("slotAt", slot.toString());
        m.put("outcome", "GIVEN");
        m.put("wristband", wristband);
        m.put("identityConfirmed", true);
        return m;
    }

    /** A GIVEN body for a high-alert medicine: the second nurse and their PIN added. */
    protected Map<String, Object> givenHighAlert(Ward w, Instant slot, Pn second, String pin) {
        Map<String, Object> m = given(w, slot);
        m.put("secondNurseId", second.membershipId().toString());
        m.put("secondNursePin", pin);
        return m;
    }

    protected static Map<String, Object> notGiven(Instant slot, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        m.put("slotAt", slot.toString());
        m.put("outcome", "NOT_GIVEN");
        m.put("reasonKey", reason);
        return m;
    }

    protected static Map<String, Object> without(Map<String, Object> body, String... fields) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        for (String f : fields) {
            m.remove(f);
        }
        return m;
    }

    protected static Map<String, Object> set(Map<String, Object> body, String field, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put(field, value);
        return m;
    }

    // ---------------- calls ----------------

    protected ResultActions doseCall(RequestPostProcessor auth, String orderId, Map<String, Object> body) throws Exception {
        return call(HttpMethod.POST, dosesUrl(orderId), auth, js(body));
    }

    protected ResultActions doseRaw(RequestPostProcessor auth, String orderId, String json) throws Exception {
        return call(HttpMethod.POST, dosesUrl(orderId), auth, json);
    }

    /** A dose that must be recorded (201). */
    protected JsonNode doseOk(RequestPostProcessor auth, String orderId, Map<String, Object> body) throws Exception {
        JsonNode a = ok(HttpMethod.POST, dosesUrl(orderId), auth, js(body), 201);
        assertThat(a.get("replayed").asBoolean()).isFalse();
        return a;
    }

    protected JsonNode mar(RequestPostProcessor auth, Object admissionId) throws Exception {
        return ok(HttpMethod.GET, marUrl(admissionId), auth, null, 200);
    }

    /** A refusal: expected status, problem type suffix and exact detail (null: not compared). */
    protected JsonNode refused(ResultActions actions, int status, String type, String detail) throws Exception {
        JsonNode p = problem(actions, status);
        assertType(p, type);
        if (detail != null) {
            assertThat(p.get("detail").asText()).isEqualTo(detail);
        }
        return p;
    }

    protected JsonNode invalid(ResultActions actions, String field, String detail) throws Exception {
        JsonNode p = refused(actions, 400, "validation", detail);
        assertThat(fieldNames(p)).as("fields of " + p).contains(field);
        assertThat(fieldMessage(p, field)).isEqualTo(detail);
        return p;
    }

    // ---------------- reading the answer ----------------

    protected static JsonNode marOrder(JsonNode mar, String orderId) {
        for (JsonNode o : mar.get("orders")) {
            if (orderId.equals(o.get("orderId").asText())) {
                return o;
            }
        }
        return null;
    }

    protected static JsonNode slot(JsonNode mar, String orderId, int index) {
        JsonNode o = marOrder(mar, orderId);
        assertThat(o).as("order " + orderId + " in the MAR").isNotNull();
        return o.get("slots").get(index);
    }

    protected static List<String> states(JsonNode mar, String orderId) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : marOrder(mar, orderId).get("slots")) {
            out.add(s.get("state").asText());
        }
        return out;
    }

    protected static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ---------------- state read by SQL ----------------

    protected long doseRows(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.dose_administration where admission_id = ?::uuid", admissionId);
    }

    protected long doseRowsOfOrder(Clinic clinic, String orderId) throws SQLException {
        return count(clinic, "select count(*) from ipd.dose_administration where order_id = ?::uuid", orderId);
    }

    /** One stored dose as text columns; fails when the order has no dose. */
    protected record DoseDb(String id, String outcome, String reason, String recordedBy, String secondCheckBy, boolean highAlert,
                            String scan, String confirmed, String slotAt, String clientKey, boolean recordedAtIsDbClock) {
    }

    protected DoseDb doseDb(Clinic clinic, String orderId, Instant slot) throws SQLException {
        List<String> v = values(clinic, "select id::text, outcome, coalesce(reason_key, ''), recorded_by::text, "
                + "coalesce(second_check_by::text, ''), high_alert::text, coalesce(identity_scan, ''), "
                + "coalesce(identity_confirmed::text, ''), slot_at::text, client_key::text, "
                + "(recorded_at between clock_timestamp() - interval '2 minutes' and clock_timestamp())::text "
                + "from ipd.dose_administration where order_id = ?::uuid and slot_at = ?", orderId,
                java.sql.Timestamp.from(slot));
        assertThat(v).as("a dose of order " + orderId + " at " + slot).isNotEmpty();
        return new DoseDb(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), Boolean.parseBoolean(v.get(5)), v.get(6), v.get(7),
                v.get(8), v.get(9), Boolean.parseBoolean(v.get(10)));
    }

    protected long giveEvents(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.clinical_event where admission_id = ?::uuid and kind = 'give'", admissionId);
    }

    protected long denyEvents(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select count(*) from ipd.clinical_event where admission_id = ?::uuid and kind = 'deny'", admissionId);
    }

    protected long doseAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_DOSE\\_%'");
    }

    protected long colleagueAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = 'PLATFORM_PIN_COLLEAGUE_CHECK_FAILED'");
    }

    /** Everything a dose request may write; a refused request must leave this exactly as it was. */
    protected record DSnap(long doses, long events, long audits, long access, String pins) {
    }

    protected DSnap dsnap(Ward w) throws SQLException {
        return new DSnap(doseRows(w.clinic(), w.adm().id()), eventCount(w.clinic(), w.adm().id()), allAudits(w.clinic()),
                accessRows(w.clinic(), w.adm()), values(w.clinic(), "select coalesce(string_agg(membership_id::text || failed_count "
                        + "|| '/' || lock_count || '/' || version || '/' || (pin_hash is not null)::text, ',' order by membership_id), "
                        + "'') from platform.staff_pin").get(0));
    }

    /** All the text the clinic's events hold, for "never contains" checks. */
    protected String allEventText(Clinic clinic) throws SQLException {
        return values(clinic, "select coalesce(string_agg(kind || ' ' || text, E'\\n'), '') from ipd.clinical_event").get(0);
    }

    /** The dose rows of the clinic as one text, for "never contains" checks. */
    protected String allDoseText(Clinic clinic) throws SQLException {
        return values(clinic, "select coalesce(string_agg(d::text, E'\\n'), '') from ipd.dose_administration d").get(0);
    }

    protected void discharge(Ward w) throws Exception {
        sign(w.clinic(), w.adm().id(), "normal", "general");
        JsonNode sheet = sheet(w.clinic(), w.adm().id());
        ok(HttpMethod.POST, dischargeUrl(w.adm().id()), w.clinic().owner(),
                dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "UPI"), 200);
    }

    /** Runs a statement as the table owner in a committed transaction on the given tables (row level security off). */
    protected void ownerCommit(String[] tables, String sql, Object... params) throws SQLException {
        asOwner(true, tables, c -> {
            DbAccess.execute(c, sql, params);
            return null;
        });
    }
}
