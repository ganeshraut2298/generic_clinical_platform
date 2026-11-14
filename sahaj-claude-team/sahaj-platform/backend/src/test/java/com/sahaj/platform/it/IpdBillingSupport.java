package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the SAH-25 integration tests (running bill, extra charges): a hospital with a ward, beds of a
 * chosen category, admissions through the real API, committed tenant SQL, and the one way to move
 * {@code ipd.admission.admitted_at}.
 *
 * <p>Backdating: {@code admitted_at} has no UPDATE grant for {@code sahaj_app} and the application clock cannot move,
 * so bill days above 1 need the table owner. {@code sahaj_migrator} owns the table but the table is FORCE row level
 * security, so it sees no rows. {@link #backdate} therefore runs, in ONE transaction, {@code NO FORCE ROW LEVEL
 * SECURITY}, the single-row update and {@code FORCE ROW LEVEL SECURITY} again. DDL is transactional in PostgreSQL,
 * so other sessions never see the table unforced, and a crash rolls everything back.
 */
abstract class IpdBillingSupport extends SweepSupport {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String IPD = "/api/v1/ipd";
    static final String RULES = IPD + "/admission-rules";
    static final String ADMISSIONS = IPD + "/admissions";
    static final String BOARD = IPD + "/board";
    static final String BEDS = IPD + "/beds";
    static final String WARDS = IPD + "/wards";
    static final String CHARGES_SETTINGS = IPD + "/charges";
    static final String CHARGE_SERVICES = IPD + "/charge-services";
    static final String TRIAL = "/api/v1/platform/modules/ipd-trial";
    static final String PATIENTS = "/api/v1/patient/patients";

    static final String[] READERS = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"};
    static final String[] NON_READERS = {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};
    static final String[] CHARGERS = {"OWNER", "FRONT_DESK", "NURSE"};
    static final String[] NON_CHARGERS = {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};

    /** The ten services of the mockup in list order: key, exact name, base price in paise. */
    static final Object[][] SERVICES = {
            {"visit", "Extra doctor visit", 50_000L},
            {"ivfluid", "IV fluids (per bottle)", 35_000L},
            {"injection", "Injection (IV/IM)", 15_000L},
            {"cbc", "CBC", 30_000L},
            {"xray", "Chest X-ray", 45_000L},
            {"oxygen", "Oxygen (per day)", 80_000L},
            {"dressing", "Major dressing", 40_000L},
            {"ecg", "ECG", 30_000L},
            {"nebulisation", "Nebulisation", 20_000L},
            {"physio", "Physiotherapy session", 40_000L}};

    /** Default day rate of a General bed, doctor visit and nursing care (paise). */
    static final long GEN_BED = 80_000L;
    static final long VISIT = 50_000L;
    static final long NURSING = 30_000L;
    static final long DAY_ONE_GENERAL = GEN_BED + VISIT + NURSING;

    // ---------------- clinic and beds ----------------

    protected Clinic hospital() throws Exception {
        return hospital(true);
    }

    /** {@code ownerIsDoctor} false: the owner has no doctor details (the setup wizard gives the owner a doctor profile). */
    protected Clinic hospital(boolean ownerIsDoctor) throws Exception {
        Clinic clinic = newClinic(true, ownerIsDoctor);
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"General ward\"}", 201);
        return clinic;
    }

    protected JsonNode layout(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, IPD + "/layout", clinic.owner(), null, 200);
    }

    private static Set<String> bedIds(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        layout.get("wards").forEach(w -> w.get("beds").forEach(b -> ids.add(b.get("id").asText())));
        return ids;
    }

    /** Adds a bed to "General ward" through the SAH-22 API and gives it the category. */
    protected UUID newBed(Clinic clinic, String category) throws Exception {
        JsonNode before = layout(clinic);
        String wardId = before.get("wards").get(0).get("id").asText();
        Set<String> known = bedIds(before);
        Set<String> now = bedIds(ok(HttpMethod.POST, BEDS, clinic.owner(), "{\"wardId\":\"" + wardId + "\"}", 201));
        now.removeAll(known);
        assertThat(now).hasSize(1);
        UUID id = UUID.fromString(now.iterator().next());
        if (!"gen".equals(category)) {
            ok(HttpMethod.PUT, BEDS + "/" + id, clinic.owner(), "{\"categoryKey\":\"" + category + "\"}", 200);
        }
        return id;
    }

    // ---------------- patients and admissions ----------------

    protected record Person(UUID id, String name, String mobile) {
    }

    protected Person person(Clinic clinic, String name) throws Exception {
        String mobile = uniqueMobile();
        JsonNode created = ok(HttpMethod.POST, PATIENTS, clinic.owner(),
                "{\"name\":" + objectMapper.writeValueAsString(name) + ",\"age\":56,\"sex\":\"M\",\"mobile\":\""
                        + mobile + "\",\"area\":\"Shivaji Nagar\",\"language\":\"mr\"}", 201);
        return new Person(UUID.fromString(created.get("id").asText()), name, mobile);
    }

    /** Admission body: planned, Self, deposit 1000000 paise by UPI, consent signed, reason Fever. */
    protected Map<String, Object> body(UUID patientId, UUID doctorId, UUID bedId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", patientId);
        m.put("doctorMembershipId", doctorId);
        m.put("admissionType", "planned");
        m.put("bedId", bedId);
        m.put("reason", "Fever");
        m.put("payer", "Self");
        m.put("depositPaise", 1_000_000);
        m.put("depositMode", "UPI");
        m.put("consentSigned", true);
        return m;
    }

    protected String js(Map<String, Object> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    protected JsonNode admitOk(Clinic clinic, Map<String, Object> map) throws Exception {
        return ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(map), 201);
    }

    /**
     * Admits a new patient to a new bed of the category. A positive deposit is paid by UPI at admission; a zero deposit
     * needs an emergency admission (the default rules allow that for Self).
     */
    protected JsonNode admit(Clinic clinic, String category, String payer, long depositPaise) throws Exception {
        Map<String, Object> m = body(newPatient(clinic), clinic.doctorMembershipId(), newBed(clinic, category));
        m.put("payer", payer);
        m.put("depositPaise", depositPaise);
        if (depositPaise == 0) {
            m.put("admissionType", "emergency");
            m.remove("depositMode");
        }
        return admitOk(clinic, m);
    }

    /** A Self patient on a General bed with a deposit of ₹10,000 (16 percent used on day 1). */
    protected JsonNode quickAdmit(Clinic clinic) throws Exception {
        return admit(clinic, "gen", "Self", 1_000_000);
    }

    protected JsonNode deposit(Clinic clinic, String admissionId, long paise) throws Exception {
        return ok(HttpMethod.POST, ADMISSIONS + "/" + admissionId + "/deposits", clinic.owner(),
                "{\"amountPaise\":" + paise + ",\"mode\":\"Cash\"}", 200);
    }

    protected JsonNode charge(Clinic clinic, String admissionId, String serviceKey) throws Exception {
        return ok(HttpMethod.POST, ADMISSIONS + "/" + admissionId + "/charges", clinic.owner(),
                "{\"serviceKey\":\"" + serviceKey + "\"}", 200);
    }

    protected JsonNode detail(Clinic clinic, String admissionId) throws Exception {
        return ok(HttpMethod.GET, ADMISSIONS + "/" + admissionId, clinic.owner(), null, 200);
    }

    protected JsonNode list(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
    }

    protected JsonNode board(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, BOARD, clinic.owner(), null, 200);
    }

    /** The list row of an admission. */
    protected static JsonNode row(JsonNode list, String admissionId) {
        for (JsonNode r : list.get("items")) {
            if (admissionId.equals(r.get("id").asText())) {
                return r;
            }
        }
        throw new AssertionError("admission not in the list: " + admissionId);
    }

    /** The board tile of a bed. */
    protected static JsonNode tile(JsonNode board, UUID bedId) {
        for (JsonNode ward : board.get("wards")) {
            for (JsonNode room : ward.get("rooms")) {
                for (JsonNode bed : room.get("beds")) {
                    if (bedId.toString().equals(bed.get("id").asText())) {
                        return bed;
                    }
                }
            }
        }
        throw new AssertionError("bed not on the board " + bedId);
    }

    protected static boolean absent(JsonNode node, String field) {
        return !node.hasNonNull(field);
    }

    // ---------------- bill helpers ----------------

    protected static JsonNode billLine(JsonNode bill, String name) {
        for (JsonNode line : bill.get("lines")) {
            if (name.equals(line.get("name").asText())) {
                return line;
            }
        }
        throw new AssertionError("no bill line named '" + name + "' in " + lineNames(bill));
    }

    protected static List<String> lineNames(JsonNode bill) {
        List<String> names = new ArrayList<>();
        bill.get("lines").forEach(l -> names.add(l.get("name").asText()));
        return names;
    }

    protected static void assertLine(JsonNode bill, String name, long unitPaise, int qty) {
        JsonNode line = billLine(bill, name);
        assertThat(line.get("unitPaise").asLong()).as(name + " unit").isEqualTo(unitPaise);
        assertThat(line.get("qty").asInt()).as(name + " qty").isEqualTo(qty);
        assertThat(line.get("amountPaise").asLong()).as(name + " amount").isEqualTo(unitPaise * qty);
    }

    /** Every line is unit times quantity and the lines add up to the total. */
    protected static void assertBillAddsUp(JsonNode bill) {
        long sum = 0;
        for (JsonNode line : bill.get("lines")) {
            assertThat(line.get("amountPaise").asLong()).as(line.get("name").asText())
                    .isEqualTo(line.get("unitPaise").asLong() * line.get("qty").asInt());
            sum += line.get("amountPaise").asLong();
        }
        assertThat(bill.get("totalPaise").asLong()).isEqualTo(sum);
    }

    protected static void assertMultiplier(JsonNode bill, String expected) {
        assertThat(bill.get("multiplier").decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }

    // ---------------- settings through the API ----------------

    protected void setCharges(Clinic clinic, String cut, long nursingPaise, long visitPaise) throws Exception {
        ok(HttpMethod.PUT, CHARGES_SETTINGS, clinic.owner(), "{\"bedDayCut\":\"" + cut + "\",\"nursingPaise\":"
                + nursingPaise + ",\"visitPaise\":" + visitPaise + "}", 200);
    }

    protected void setCategory(Clinic clinic, String key, long ratePaise, String multiplier) throws Exception {
        ok(HttpMethod.PUT, IPD + "/categories/" + key, clinic.owner(),
                "{\"ratePaise\":" + ratePaise + ",\"multiplier\":" + multiplier + "}", 200);
    }

    protected static String rulesBody(int alertPercent) {
        return "{\"depositPaise\":{\"planned\":1000000,\"emergency\":500000,\"daycare\":300000,\"maternity\":1500000},"
                + "\"categoryAddPaise\":{\"gen\":0,\"semi\":200000,\"pvt\":500000,\"icu\":1000000},"
                + "\"emergencyWithoutDeposit\":true,\"alertPercent\":" + alertPercent + "}";
    }

    protected void setAlertPercent(Clinic clinic, int percent) throws Exception {
        ok(HttpMethod.PUT, RULES, clinic.owner(), rulesBody(percent), 200);
    }

    // ---------------- raw SQL ----------------

    protected long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    protected long rows(Clinic clinic, String table) throws SQLException {
        return count(clinic, "select count(*) from ipd." + table);
    }

    protected long audits(Clinic clinic, String action) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ?", action);
    }

    protected long ipdAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_%'");
    }

    protected long recordAccess(Clinic clinic, UUID patientId) throws SQLException {
        return count(clinic, "select count(*) from patient.record_access where patient_id = ?", patientId);
    }

    protected List<String> values(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> out = new ArrayList<>();
                    if (rs.next()) {
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                            out.add(rs.getString(i));
                        }
                    }
                    return out;
                }
            }
        });
    }

    /** Audit rows: entity_type, entity_id, before_json, after_json, actor_user_id, oldest first. */
    protected List<String[]> audit(Clinic clinic, String action) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select entity_type, entity_id::text, before_json::text, after_json::text, actor_user_id::text "
                            + "from platform.audit_event where action = ? order by at")) {
                ps.setString(1, action);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5)});
                    }
                }
            }
            return out;
        });
    }

    protected void commitAsTenant(UUID tenantId, String sql, Object... params) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            DbAccess.execute(c, sql, params);
            c.commit();
        }
    }

    protected void expireTrial(Clinic clinic) throws SQLException {
        commitAsTenant(clinic.tenantId(),
                "update platform.module_entitlement set ipd_trial_ends_at = now() - interval '1 day'");
    }

    protected void discharge(Clinic clinic, String admissionId) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid",
                admissionId);
    }

    protected void assertDenied(Clinic clinic, String sql, Object... params) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql, params);
            return null;
        })).isInstanceOf(SQLException.class);
    }

    /**
     * Moves {@code ipd.admission.admitted_at} (committed). See the class comment: NO FORCE, update, FORCE in one
     * transaction as the table owner {@code sahaj_migrator}.
     */
    protected void backdate(Clinic clinic, String admissionId, Instant admittedAt) throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try {
                DbAccess.execute(c, "set local lock_timeout = '30s'");
                DbAccess.execute(c, "alter table ipd.admission no force row level security");
                try (PreparedStatement u = c.prepareStatement(
                        "update ipd.admission set admitted_at = ? where id = ?::uuid and tenant_id = ?")) {
                    u.setObject(1, java.sql.Timestamp.from(admittedAt));
                    u.setString(2, admissionId);
                    u.setObject(3, clinic.tenantId());
                    assertThat(u.executeUpdate()).as("backdated rows").isEqualTo(1);
                }
                DbAccess.execute(c, "alter table ipd.admission force row level security");
                c.commit();
            } catch (SQLException | RuntimeException | AssertionError e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ---------------- time ----------------

    protected static LocalDate istToday() {
        return LocalDate.now(IST);
    }

    protected static Instant istMidnight(LocalDate date) {
        return date.atStartOfDay(IST).toInstant();
    }

    // ---------------- problem bodies ----------------

    protected static void assertType(JsonNode problem, String suffix) {
        assertThat(problem.get("type").asText()).endsWith(suffix);
    }

    protected static void assertFieldError(JsonNode problem, String field) {
        assertThat(problem.get("fieldErrors")).isNotNull();
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo(field);
    }
}
