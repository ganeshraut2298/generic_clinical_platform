package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-76 dose and nursing-settings integration tests (work packages 3 and 4, docs/api/ipd.yaml 0.4.1,
 * docs/database/SAH-76.md). It stands on the SAH-32/34 support classes (ward, nurses with PINs, the clock seam of
 * {@link MarSupport}, ward scope) and adds what the 0.4.0 contract changed:
 *
 * <ul>
 *   <li>orders are placed with the new body (dose, unit, route, priority, PRN fields) and verified with an order version;</li>
 *   <li>a scheduled order is made old by the table owner (version 1 start, creation and verification the day before) so that the
 *       fixed clock of {@link MarSupport#clockAt} decides the slot states; a STAT order and a PRN order need no clock seam (a
 *       STAT slot is its creation minute, a PRN order has no slot) and run on the real database clock;</li>
 *   <li>medicine codes: {@code ipd.drug_code} ships empty and is immutable, so a test that needs a code inserts it as the table
 *       owner and every code of these tests starts with {@value #CODE_PREFIX}; they are deleted after each test (trigger off for
 *       that one delete) so that the shipped-empty check of Sah76MigrationIT stays true;</li>
 *   <li>a call with the {@code X-Sahaj-Device} and {@code User-Agent} headers (audit device label).</li>
 * </ul>
 *
 * The texts and urls are copied from the contract, never read from the application. Test plan:
 * docs/test-plans/SAH-76-dose-settings.md.
 */
abstract class Sah76NursingSupport extends ScopeSupport {

    static final String CODE_PREFIX = "QAS76";
    static final String SETTINGS = IPD + "/nursing-settings";
    static final String PROFILES = IPD + "/nursing-profiles";

    static final String ONLY_OWNER_SETTINGS = "Only the clinic owner can change settings.";
    static final String ORDER_ON_HOLD = "This order is on hold. Do not give it.";
    static final String ORDER_CHANGED = "This order was changed. Reload and check the new order.";
    static final String SCAN_MEDICINE = "Scan or type the medicine code.";
    static final String CHOOSE_OVERRIDE = "Choose why you cannot scan.";
    static final String OVERRIDE_NOTE = "Write what you checked instead.";
    static final String WRITE_WHAT_HAPPENED = "Write what happened.";
    static final String CHOOSE_IN_ERROR = "Choose why it is in error.";
    static final String ONLY_CORRECTORS = "Only nurses and the clinic owner can correct a dose.";
    static final String ONLY_RECORDER = "Only the nurse who recorded this dose can undo it.";
    static final String CANNOT_UNDO = "This dose cannot be undone.";
    static final String CANNOT_MARK_ERROR = "This dose cannot be marked in error.";
    static final String UNDO_EXPIRED = "The time to undo has passed. Mark it as recorded in error instead.";
    static final String WINDOW_CLOSED = "This dose is from an earlier day and cannot be changed.";
    static final String CANNOT_NOW = "This dose cannot be given now. Reload the page.";
    static final String ON_OFF = "Choose On or Off.";
    static final String NAME_TAKEN = "A profile with this name already exists.";
    static final String PROFILE_LIMIT = "A clinic can have up to 20 profiles.";
    static final String ENTER_NAME = "Enter a profile name.";
    static final String CHOOSE_PROFILE = "Choose a profile from the list.";

    static final String[] SETTINGS_READERS = {"OWNER", "DOCTOR", "NURSE", "PHARMACIST"};
    static final String[] SETTINGS_NON_READERS = {"FRONT_DESK", "BILLING", "VISITING_DOCTOR", "COUNSELLOR"};
    static final String[] SETTINGS_NON_WRITERS = {"DOCTOR", "NURSE", "PHARMACIST", "FRONT_DESK", "BILLING", "VISITING_DOCTOR",
            "COUNSELLOR"};
    static final String[] CORRECT_NON_ROLES = {"DOCTOR", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST", "COUNSELLOR"};

    private final Map<String, String> codes = new LinkedHashMap<>();

    // ---------------- urls ----------------

    static String undoUrl(Object doseId) {
        return IPD + "/doses/" + doseId + "/undo";
    }

    static String errorUrl(Object doseId) {
        return IPD + "/doses/" + doseId + "/recorded-in-error";
    }

    static String profileUrl(String key) {
        return PROFILES + "/" + key;
    }

    static String wardProfileUrl(Object wardId) {
        return IPD + "/wards/" + wardId + "/nursing-profile";
    }

    // ---------------- a call with the device header ----------------

    protected ResultActions callWith(HttpMethod method, String path, RequestPostProcessor auth, String body, String device,
                                     String userAgent) throws Exception {
        var builder = request(method, URI.create(path)).with(auth);
        if (device != null) {
            builder.header("X-Sahaj-Device", device);
        }
        if (userAgent != null) {
            builder.header("User-Agent", userAgent);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    // ---------------- orders (contract 0.4.0 body) ----------------

    protected static Map<String, Object> sched(String drug, String frequency, String unit, String route) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        m.put("drugKey", drug);
        m.put("frequencyKey", frequency);
        m.put("doseAmount", 1);
        m.put("doseUnit", unit);
        m.put("routeKey", route);
        m.put("priority", "ROUTINE");
        return m;
    }

    protected static Map<String, Object> prnOrder(String drug, String unit, String route, int minIntervalMinutes, int maxPer24h) {
        Map<String, Object> m = sched(drug, "prn", unit, route);
        m.put("indication", "Fever above 38 C");
        m.put("prnMinIntervalMinutes", minIntervalMinutes);
        m.put("prnMaxPer24h", maxPer24h);
        return m;
    }

    protected static Map<String, Object> statOrder(String drug, String unit, String route) {
        Map<String, Object> m = sched(drug, null, unit, route);
        m.remove("frequencyKey");
        m.put("priority", "STAT");
        return m;
    }

    /** An order that must be placed (201 PLACED); returns its id. */
    protected String placeOrder(RequestPostProcessor auth, Object admissionId, Map<String, Object> body) throws Exception {
        JsonNode a = ok(HttpMethod.POST, ordersUrl(admissionId), auth, js(body), 201);
        assertThat(a.get("outcome").asText()).isEqualTo("PLACED");
        return orderIdOf(a);
    }

    /** The pharmacist verifies the order with its PIN, the check token and the order version read from the queue. */
    protected JsonNode verifyNow(Ward w, String orderId) throws Exception {
        JsonNode row = queueRow(queue(w.pharmacist().auth(), "limit=100"), orderId);
        assertThat(row).as("the order is in the pharmacist queue").isNotNull();
        String body = "{\"pin\":\"" + w.pharmacistPin() + "\",\"checkToken\":\"" + row.get("check").get("checkToken").asText()
                + "\",\"orderVersion\":" + row.get("orderVersion").asInt() + "}";
        freshPinAllowance(w.pharmacist());
        return ok(HttpMethod.POST, verifyUrl(orderId), w.pharmacist().auth(), body, 200);
    }

    /** Scheduled order placed by the doctor, verified, then made old (start, creation and verification the day before). */
    protected String activeScheduled(Ward w, String drug, String frequency, String unit, String route) throws Exception {
        String id = placeOrder(w.doctor().auth(), w.adm().id(), sched(drug, frequency, unit, route));
        verifyNow(w, id);
        backdate(w.clinic(), id, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0));
        return id;
    }

    /** A PRN order placed, verified and made old. */
    protected String activePrn(Ward w, String drug, String unit, String route, int minInterval, int max) throws Exception {
        String id = placeOrder(w.doctor().auth(), w.adm().id(), prnOrder(drug, unit, route, minInterval, max));
        verifyNow(w, id);
        backdate(w.clinic(), id, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0));
        return id;
    }

    /**
     * Version 1 and the head of an order get these times (table owner, committed, guard triggers off inside the one
     * transaction). A version that needed no verification keeps its null verification time.
     */
    protected void backdate(Clinic clinic, String orderId, Instant startAt, Instant createdAt, Instant verifiedAt) throws SQLException {
        asOwner(true, new String[] {"ipd.medicine_order_version", "ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order_version disable trigger trg_medicine_order_version_guard_update");
            try (PreparedStatement ps = c.prepareStatement("update ipd.medicine_order_version set start_at = ?, created_at = ?, "
                    + "verified_at = case when verified_by is null then null else ?::timestamptz end where order_id = ?::uuid and version_no = 1")) {
                ps.setTimestamp(1, Timestamp.from(startAt));
                ps.setTimestamp(2, Timestamp.from(createdAt));
                ps.setTimestamp(3, Timestamp.from(verifiedAt));
                ps.setString(4, orderId);
                assertThat(ps.executeUpdate()).as("version 1 backdated").isOne();
            }
            DbAccess.execute(c, "alter table ipd.medicine_order_version enable trigger trg_medicine_order_version_guard_update");
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger trg_medicine_order_guard_update");
            try (PreparedStatement ps = c.prepareStatement("update ipd.medicine_order set ordered_at = ?, "
                    + "verified_at = case when verified_by is null then null else ?::timestamptz end where id = ?::uuid")) {
                ps.setTimestamp(1, Timestamp.from(createdAt));
                ps.setTimestamp(2, Timestamp.from(verifiedAt));
                ps.setString(3, orderId);
                assertThat(ps.executeUpdate()).as("head backdated").isOne();
            }
            DbAccess.execute(c, "set constraints all immediate");
            DbAccess.execute(c, "alter table ipd.medicine_order enable trigger trg_medicine_order_guard_update");
            return null;
        });
    }

    // ---------------- medicine codes (global, immutable table: inserted by the owner, removed after the test) ----------------

    /** A medicine code of this drug in the drug master (made once per drug and test). */
    protected String codeOf(String drugKey) throws SQLException {
        String known = codes.get(drugKey);
        if (known != null) {
            return known;
        }
        String code = CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "insert into ipd.drug_code (code, drug_key, source) values (?, ?, 'QA_TEST')", code, drugKey);
        }
        codes.put(drugKey, code);
        return code;
    }

    /** Before each test: a QAS76 code left behind by a killed run must not leak into this test (code review round 3 #3). */
    @BeforeEach
    void sweepMedicineCodesBefore() throws SQLException {
        codes.clear();
        sweepQaMedicineCodes(db());
    }

    @AfterEach
    void removeMedicineCodes() throws SQLException {
        codes.clear();
        sweepQaMedicineCodes(db());
    }

    /**
     * Deletes every medicine code with the QA prefix (migrator connection, the immutability trigger off for that one delete and on
     * again in {@code finally}). Only the prefix {@value #CODE_PREFIX} is swept, so a real code still fails the shipped-empty check.
     */
    static void sweepQaMedicineCodes(DbAccess db) throws SQLException {
        try (Connection c = db.migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "alter table ipd.drug_code disable trigger trg_drug_code_no_update_delete");
            try {
                DbAccess.execute(c, "delete from ipd.drug_code where code like '" + CODE_PREFIX + "%'");
            } finally {
                DbAccess.execute(c, "alter table ipd.drug_code enable trigger trg_drug_code_no_update_delete");
            }
        }
    }

    // ---------------- dose bodies ----------------

    protected Map<String, Object> scanGiven(Ward w, Instant slot, int version, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        if (slot != null) {
            m.put("slotAt", slot.toString());
        }
        m.put("outcome", "GIVEN");
        m.put("orderVersion", version);
        m.put("wristband", w.uhid());
        if (code != null) {
            m.put("medicineCode", code);
        }
        m.put("identityConfirmed", true);
        return m;
    }

    protected Map<String, Object> overrideGiven(Ward w, Instant slot, int version, String reasonKey, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        if (slot != null) {
            m.put("slotAt", slot.toString());
        }
        m.put("outcome", "GIVEN");
        m.put("orderVersion", version);
        m.put("wristband", w.uhid()); // contract 0.4.3: the wristband is always sent, an override only waives the medicine code
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("reasonKey", reasonKey);
        o.put("note", note);
        m.put("override", o);
        m.put("identityConfirmed", true);
        return m;
    }

    protected Map<String, Object> notGivenBody(Instant slot, int version, String reason, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", newKey());
        if (slot != null) {
            m.put("slotAt", slot.toString());
        }
        m.put("outcome", "NOT_GIVEN");
        m.put("orderVersion", version);
        m.put("reasonKey", reason);
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    protected static String corrBody(String key, String reason) {
        return "{\"clientKey\":\"" + key + "\"" + (reason == null ? "" : ",\"reasonKey\":\"" + reason + "\"") + "}";
    }

    protected ResultActions undoCall(RequestPostProcessor auth, Object doseId, String key) throws Exception {
        return call(HttpMethod.POST, undoUrl(doseId), auth, corrBody(key, null));
    }

    protected ResultActions errorCall(RequestPostProcessor auth, Object doseId, String key, String reason) throws Exception {
        return call(HttpMethod.POST, errorUrl(doseId), auth, corrBody(key, reason));
    }

    protected JsonNode undoOk(RequestPostProcessor auth, Object doseId) throws Exception {
        return ok(HttpMethod.POST, undoUrl(doseId), auth, corrBody(newKey(), null), 200);
    }

    protected JsonNode errorOk(RequestPostProcessor auth, Object doseId, String reason) throws Exception {
        return ok(HttpMethod.POST, errorUrl(doseId), auth, corrBody(newKey(), reason), 200);
    }

    // ---------------- state read by SQL ----------------

    /** Every row of a query as text columns (the older {@code values} helper returns the first row only). */
    protected List<List<String>> table(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<List<String>> out = new ArrayList<>();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                            row.add(rs.getString(i));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    /** One stored dose of an order: the columns SAH-76 added. */
    protected record Dose(String id, int attempt, String supersedes, int orderVersion, String doseKind, String scanMethod,
                          String overrideReason, String overrideNote, String medicineScan, boolean beforeVerification,
                          double undoSeconds, String outcome, String reason, String notGivenNote, String recordedBy,
                          String identityScan) {
    }

    protected List<Dose> dosesOf(Clinic clinic, String orderId) throws SQLException {
        List<Dose> out = new ArrayList<>();
        for (List<String> r : table(clinic, "select id::text, attempt::text, coalesce(supersedes_dose_id::text, ''), order_version::text, "
                + "dose_kind, coalesce(scan_method, ''), coalesce(override_reason_key, ''), coalesce(override_note, ''), "
                + "coalesce(medicine_scan, ''), before_verification::text, coalesce(extract(epoch from (undo_until - recorded_at))::text, ''), "
                + "outcome, coalesce(reason_key, ''), coalesce(not_given_note, ''), recorded_by::text, coalesce(identity_scan, '') "
                + "from ipd.dose_administration where order_id = ?::uuid order by recorded_at, attempt", orderId)) {
            out.add(new Dose(r.get(0), Integer.parseInt(r.get(1)), r.get(2), Integer.parseInt(r.get(3)), r.get(4), r.get(5), r.get(6),
                    r.get(7), r.get(8), Boolean.parseBoolean(r.get(9)), r.get(10).isEmpty() ? -1 : Double.parseDouble(r.get(10)),
                    r.get(11), r.get(12), r.get(13), r.get(14), r.get(15)));
        }
        return out;
    }

    protected long correctionRows(Clinic clinic, String doseId) throws SQLException {
        return count(clinic, "select count(*) from ipd.dose_correction where dose_id = ?::uuid", doseId);
    }

    protected long correctionRowsOfClinic(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from ipd.dose_correction");
    }

    protected JsonNode auditAfter(Clinic clinic, String action, int index) throws Exception {
        return objectMapper.readTree(audit(clinic, action).get(index)[3]);
    }

    protected List<String> allAuditTexts(Clinic clinic, String actionLike) throws SQLException {
        List<String> out = new ArrayList<>();
        for (List<String> r : table(clinic, "select coalesce(before_json::text, '') || ' ' || coalesce(after_json::text, '') "
                + "from platform.audit_event where action like ? order by at", actionLike)) {
            out.add(r.get(0));
        }
        return out;
    }

    /** The IST time as the contract writes it in a PRN answer: "2:30 PM". */
    protected static String hmm(Instant instant) {
        ZonedDateTime t = instant.atZone(IST);
        int hour = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return hour + ":" + (t.getMinute() < 10 ? "0" : "") + t.getMinute() + (t.getHour() < 12 ? " AM" : " PM");
    }

    /** Moves the recorded time of a dose back (owner, append-only trigger off inside the transaction): ages it for the PRN limits. */
    protected void ageDose(Clinic clinic, String doseId, long minutes) throws SQLException {
        asOwner(true, new String[] {"ipd.dose_administration", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.dose_administration disable trigger trg_dose_administration_no_update_delete");
            DbAccess.execute(c, "update ipd.dose_administration set recorded_at = recorded_at - (? * interval '1 minute'), "
                    + "slot_at = slot_at - (? * interval '1 minute') where id = ?::uuid", minutes, minutes, doseId);
            DbAccess.execute(c, "alter table ipd.dose_administration enable trigger trg_dose_administration_no_update_delete");
            return null;
        });
    }

    /** Closes the undo window of a dose (owner, same technique). */
    protected void expireUndo(Clinic clinic, String doseId) throws SQLException {
        asOwner(true, new String[] {"ipd.dose_administration", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.dose_administration disable trigger trg_dose_administration_no_update_delete");
            DbAccess.execute(c, "update ipd.dose_administration set undo_until = recorded_at - interval '1 second' "
                    + "where id = ?::uuid", doseId);
            DbAccess.execute(c, "alter table ipd.dose_administration enable trigger trg_dose_administration_no_update_delete");
            return null;
        });
    }

    /** Opens the undo window of a dose for an hour (owner, same technique): for the database-level checks of the guard. */
    protected void extendUndo(Clinic clinic, String doseId) throws SQLException {
        asOwner(true, new String[] {"ipd.dose_administration", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.dose_administration disable trigger trg_dose_administration_no_update_delete");
            // one hour after the later of the database clock and the fixed application clock: a test that runs before the fixed
            // time of day (the clock is 8:05 AM IST of today) would otherwise read the window as closed (canUndo false)
            Instant fixed = fixedClock() == null ? Instant.EPOCH : fixedClock();
            DbAccess.execute(c, "update ipd.dose_administration set undo_until = greatest(clock_timestamp(), ?::timestamptz) + interval '1 hour' "
                    + "where id = ?::uuid", java.sql.Timestamp.from(fixed), doseId);
            DbAccess.execute(c, "alter table ipd.dose_administration enable trigger trg_dose_administration_no_update_delete");
            return null;
        });
    }

    /** Runs the work as sahaj_app with the tenant bound (rolled back) and returns the error of its last statement; fails when none. */
    protected SqlErr inTenantErr(Clinic clinic, DbAccess.SqlWork<Void> work) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            java.sql.Savepoint savepoint = c.setSavepoint();
            try {
                work.apply(c);
            } catch (SQLException e) {
                c.rollback(savepoint);
                return new SqlErr(e.getSQLState(), e.getMessage());
            }
            throw new AssertionError("the statements were accepted");
        });
    }

    /** A nurse of the clinic that has a PIN (the second nurse of a high-alert dose). */
    protected static Instant slotOf(JsonNode mar, String orderId, int index) {
        return Instant.parse(slot(mar, orderId, index).get("slotAt").asText());
    }

    /** Sets the module state of the clinic's IPD (off, trial, on or readonly) as the app does. */
    protected void setIpdState(Clinic clinic, String state) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update platform.module_entitlement set ipd_state = ?", state);
    }

    protected Map<String, Object> mutable(Map<String, Object> body) {
        return new LinkedHashMap<>(body);
    }
}
