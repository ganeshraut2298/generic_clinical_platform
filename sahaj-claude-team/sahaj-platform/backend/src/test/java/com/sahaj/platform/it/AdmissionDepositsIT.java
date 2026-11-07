package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-23 IPD admission and deposits on the real {@code sahaj_test} database: module state gate, deposit rules,
 * admit sheet options, the admit check order and messages, IP numbers, concurrency, deposits (append-only),
 * list and detail, the bed delete guard, audit and privacy, the menu key and row level security.
 * Test plan: docs/test-plans/SAH-23.md.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class AdmissionDepositsIT extends SweepSupport {

    static final String IPD = "/api/v1/ipd";
    static final String RULES = IPD + "/admission-rules";
    static final String CONFIRM = RULES + "/confirm";
    static final String OPTIONS = IPD + "/admission-options";
    static final String ADMISSIONS = IPD + "/admissions";
    static final String BEDS = IPD + "/beds";
    static final String WARDS = IPD + "/wards";
    static final String TRIAL = "/api/v1/platform/modules/ipd-trial";
    static final String MODULES = "/api/v1/platform/modules";
    static final String ME = "/api/v1/platform/me";

    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] READERS = {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"};
    private static final String[] NON_READERS = {"VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] NON_ADMITTERS = {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"};
    private static final String[] IPD_ACTIONS = {"IPD_ADMITTED", "IPD_DEPOSIT_COLLECTED",
            "IPD_ADMISSION_RULES_CHANGED", "IPD_ADMISSION_RULES_CONFIRMED"};
    private static final String[] NEW_TABLES = {"admission", "deposit", "ip_sequence"};

    // ---------------- helpers ----------------

    private Clinic ipdClinic() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        return clinic;
    }

    /** A clinic with IPD in trial and a ward called "General ward" (one room). */
    private Clinic hospital() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"General ward\"}", 201);
        return clinic;
    }

    private JsonNode layout(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, IPD + "/layout", clinic.owner(), null, 200);
    }

    private static Set<String> bedIds(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        for (JsonNode w : layout.get("wards")) {
            w.get("beds").forEach(b -> ids.add(b.get("id").asText()));
        }
        return ids;
    }

    private static JsonNode bedNode(JsonNode layout, UUID bedId) {
        for (JsonNode w : layout.get("wards")) {
            for (JsonNode b : w.get("beds")) {
                if (bedId.toString().equals(b.get("id").asText())) {
                    return b;
                }
            }
        }
        throw new AssertionError("bed not found " + bedId);
    }

    private static JsonNode wardNode(JsonNode layout, String name) {
        for (JsonNode w : layout.get("wards")) {
            if (name.equals(w.get("name").asText())) {
                return w;
            }
        }
        throw new AssertionError("ward not found " + name);
    }

    /** Adds a bed through the SAH-22 API in "General ward" and sets its category. */
    private UUID newBed(Clinic clinic, String category) throws Exception {
        JsonNode before = layout(clinic);
        String wardId = wardNode(before, "General ward").get("id").asText();
        Set<String> known = bedIds(before);
        JsonNode after = ok(HttpMethod.POST, BEDS, clinic.owner(), "{\"wardId\":\"" + wardId + "\"}", 201);
        Set<String> now = bedIds(after);
        now.removeAll(known);
        assertThat(now).hasSize(1);
        UUID id = UUID.fromString(now.iterator().next());
        if (!"gen".equals(bedNode(after, id).get("categoryKey").asText()) || !"gen".equals(category)) {
            ok(HttpMethod.PUT, BEDS + "/" + id, clinic.owner(), "{\"categoryKey\":\"" + category + "\"}", 200);
        }
        return id;
    }

    private record Person(UUID id, String name, String mobile) {
    }

    private Person person(Clinic clinic, String name) throws Exception {
        String mobile = uniqueMobile();
        JsonNode created = ok(HttpMethod.POST, "/api/v1/patient/patients", clinic.owner(),
                "{\"name\":" + objectMapper.writeValueAsString(name) + ",\"age\":56,\"sex\":\"M\",\"mobile\":\""
                        + mobile + "\",\"area\":\"Shivaji Nagar\",\"language\":\"mr\"}", 201);
        return new Person(UUID.fromString(created.get("id").asText()), name, mobile);
    }

    private UUID patient(Clinic clinic) throws Exception {
        return newPatient(clinic);
    }

    /** A valid admission: planned, Self, deposit ₹10,000 by UPI, consent signed, reason Fever. */
    private Map<String, Object> body(UUID patientId, UUID doctorId, UUID bedId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", patientId);
        m.put("doctorMembershipId", doctorId);
        m.put("admissionType", "planned");
        m.put("bedId", bedId);
        m.put("reason", "Fever");
        m.put("payer", "Self");
        m.put("depositPaise", 1000000);
        m.put("depositMode", "UPI");
        m.put("consentSigned", true);
        return m;
    }

    private Map<String, Object> body(Clinic clinic, UUID bedId) throws Exception {
        return body(patient(clinic), clinic.doctorMembershipId(), bedId);
    }

    private String js(Map<String, Object> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    private org.springframework.test.web.servlet.ResultActions admit(Clinic clinic, Map<String, Object> map) throws Exception {
        return call(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(map));
    }

    private JsonNode admitOk(Clinic clinic, Map<String, Object> map) throws Exception {
        return ok(HttpMethod.POST, ADMISSIONS, clinic.owner(), js(map), 201);
    }

    /** Admits a fresh patient to a fresh General bed with the default body. */
    private JsonNode quickAdmit(Clinic clinic) throws Exception {
        return admitOk(clinic, body(clinic, newBed(clinic, "gen")));
    }

    private static String rulesBody(long planned, long emergency, long daycare, long maternity, long gen, long semi,
                                    long pvt, long icu, boolean emergencyFree, int alert) {
        return "{\"depositPaise\":{\"planned\":" + planned + ",\"emergency\":" + emergency + ",\"daycare\":" + daycare
                + ",\"maternity\":" + maternity + "},\"categoryAddPaise\":{\"gen\":" + gen + ",\"semi\":" + semi
                + ",\"pvt\":" + pvt + ",\"icu\":" + icu + "},\"emergencyWithoutDeposit\":" + emergencyFree
                + ",\"alertPercent\":" + alert + "}";
    }

    private static String defaultRules() {
        return rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 80);
    }

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long rows(Clinic clinic, String table) throws SQLException {
        return count(clinic, "select count(*) from ipd." + table);
    }

    private long audits(Clinic clinic, String action) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ?", action);
    }

    private long ipdAudits(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action like 'IPD\\_%'");
    }

    private List<String> row(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> values = new ArrayList<>();
                    if (rs.next()) {
                        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                            values.add(rs.getString(i));
                        }
                    }
                    return values;
                }
            }
        });
    }

    /** Audit rows: entity_type, entity_id, before_json, after_json, actor_user_id, oldest first. */
    private List<String[]> audit(Clinic clinic, String action) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select entity_type, entity_id::text, before_json::text, after_json::text, actor_user_id::text "
                            + "from platform.audit_event where action = ? order by at")) {
                ps.setString(1, action);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5)});
                    }
                }
            }
            return rows;
        });
    }

    private void commitAsTenant(UUID tenantId, String sql, Object... params) throws SQLException {
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

    private void expireTrial(Clinic clinic) throws SQLException {
        commitAsTenant(clinic.tenantId(),
                "update platform.module_entitlement set ipd_trial_ends_at = now() - interval '1 day'");
    }

    private void assertDenied(Clinic clinic, String sql, Object... params) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql, params);
            return null;
        })).isInstanceOf(SQLException.class);
    }

    private static void assertType(JsonNode problem, String suffix) {
        assertThat(problem.get("type").asText()).endsWith(suffix);
    }

    private static void assertFieldError(JsonNode problem, String field) {
        assertThat(problem.get("fieldErrors")).isNotNull();
        assertThat(problem.get("fieldErrors").get(0).get("field").asText()).isEqualTo(field);
    }

    private static String financialYear() {
        ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        int start = z.getMonthValue() >= 4 ? z.getYear() : z.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }

    private record Resp(int status, String body) {
    }

    private List<Resp> parallel(Clinic clinic, String path, List<String> bodies) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (String payload : bodies) {
                Callable<Resp> attempt = () -> {
                    start.await();
                    var r = mvc.perform(post(path).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                            .content(payload)).andReturn().getResponse();
                    return new Resp(r.getStatus(), r.getContentAsString());
                };
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            List<Resp> result = new ArrayList<>();
            for (Future<Resp> f : futures) {
                result.add(f.get());
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> menu(Clinic clinic, String role) throws Exception {
        List<String> items = new ArrayList<>();
        ok(HttpMethod.GET, ME, clinic.as(role), null, 200).get("menu").forEach(n -> items.add(n.asText()));
        return items;
    }

    /** Every endpoint of the story once, with random ids so that the gate answer shows. */
    private List<Object[]> allCalls() {
        UUID id = UUID.randomUUID();
        return List.of(
                new Object[] {HttpMethod.GET, RULES, null},
                new Object[] {HttpMethod.PUT, RULES, defaultRules()},
                new Object[] {HttpMethod.POST, CONFIRM, null},
                new Object[] {HttpMethod.GET, OPTIONS, null},
                new Object[] {HttpMethod.GET, ADMISSIONS, null},
                new Object[] {HttpMethod.POST, ADMISSIONS, "{\"patientId\":\"" + id + "\"}"},
                new Object[] {HttpMethod.GET, ADMISSIONS + "/" + id, null},
                new Object[] {HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", "{\"amountPaise\":100000,\"mode\":\"UPI\"}"});
    }

    // ---------------- AC1 module state ----------------

    @Test
    void ipdOffAllEightEndpointsAre403ModuleOffAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic();
        for (String role : new String[] {"OWNER", "NURSE", "FRONT_DESK", "DOCTOR"}) {
            for (Object[] c : allCalls()) {
                JsonNode p = problem(call((HttpMethod) c[0], (String) c[1], clinic.as(role), (String) c[2]), 403);
                if ("OWNER".equals(role)) {
                    assertType(p, "module-off");
                }
            }
        }
        for (String t : NEW_TABLES) {
            assertThat(rows(clinic, t)).as(t).isZero();
        }
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
    }

    @Test
    void readonlyReadsWorkAndEveryWriteIs409IpdReadonlyWithNothingChanged() throws Exception {
        Clinic clinic = hospital();
        JsonNode admitted = quickAdmit(clinic);
        UUID spareBed = newBed(clinic, "gen");
        Map<String, Object> next = body(clinic, spareBed);
        expireTrial(clinic);
        long deposits = rows(clinic, "deposit");
        long admissions = rows(clinic, "admission");
        long audits = ipdAudits(clinic);

        ok(HttpMethod.GET, RULES, clinic.owner(), null, 200);
        assertThat(ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200).get("beds")).hasSize(1);
        JsonNode list = ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("canAdmit").asBoolean()).isFalse();
        JsonNode detail = ok(HttpMethod.GET, ADMISSIONS + "/" + admitted.get("id").asText(), clinic.owner(), null, 200);
        assertThat(detail.get("canCollectDeposit").asBoolean()).isFalse();

        for (Object[] c : new Object[][] {
                {HttpMethod.PUT, RULES, rulesBody(1100000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 80)},
                {HttpMethod.POST, CONFIRM, null},
                {HttpMethod.POST, ADMISSIONS, js(next)},
                {HttpMethod.POST, ADMISSIONS + "/" + admitted.get("id").asText() + "/deposits",
                        "{\"amountPaise\":100000,\"mode\":\"UPI\"}"}}) {
            JsonNode p = problem(call((HttpMethod) c[0], (String) c[1], clinic.owner(), (String) c[2]), 409);
            assertType(p, "ipd-readonly");
            assertThat(p.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
        }
        assertThat(rows(clinic, "deposit")).isEqualTo(deposits);
        assertThat(rows(clinic, "admission")).isEqualTo(admissions);
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        assertThat(row(clinic, "select status from ipd.bed where id = ?", spareBed)).containsExactly("free");
    }

    // ---------------- AC7 deposit rules ----------------

    @Test
    void getRulesReturnsDefaultsWithoutWritingARowAndOnlyFourRolesMayRead() throws Exception {
        Clinic clinic = ipdClinic();
        for (String role : READERS) {
            JsonNode r = ok(HttpMethod.GET, RULES, clinic.as(role), null, 200);
            assertThat(r.get("depositPaise").get("planned").asLong()).as(role).isEqualTo(1000000);
            assertThat(r.get("depositPaise").get("emergency").asLong()).isEqualTo(500000);
            assertThat(r.get("depositPaise").get("daycare").asLong()).isEqualTo(300000);
            assertThat(r.get("depositPaise").get("maternity").asLong()).isEqualTo(1500000);
            assertThat(r.get("categoryAddPaise").get("gen").asLong()).isZero();
            assertThat(r.get("categoryAddPaise").get("semi").asLong()).isEqualTo(200000);
            assertThat(r.get("categoryAddPaise").get("pvt").asLong()).isEqualTo(500000);
            assertThat(r.get("categoryAddPaise").get("icu").asLong()).isEqualTo(1000000);
            assertThat(r.get("emergencyWithoutDeposit").asBoolean()).isTrue();
            assertThat(r.get("alertPercent").asInt()).isEqualTo(80);
            assertThat(r.get("confirmed").asBoolean()).isFalse();
        }
        for (String role : NON_READERS) {
            assertThat(problem(call(HttpMethod.GET, RULES, clinic.as(role), null), 403)).isNotNull();
        }
        assertThat(rows(clinic, "config")).as("a GET must not create the lazy config row").isZero();
        assertThat(ipdAudits(clinic)).isZero();
        mvc.perform(get(RULES)).andExpect(status().isUnauthorized());
    }

    @Test
    void putRulesSavesWholeRupeesAuditsOnceAndKeepsTheConfirmedFlag() throws Exception {
        Clinic clinic = ipdClinic();
        String changed = rulesBody(1200000, 600000, 350000, 1600000, 10000, 250000, 550000, 1100000, false, 90);
        JsonNode saved = ok(HttpMethod.PUT, RULES, clinic.owner(), changed, 200);
        assertThat(saved.get("depositPaise").get("planned").asLong()).isEqualTo(1200000);
        assertThat(saved.get("categoryAddPaise").get("gen").asLong()).isEqualTo(10000);
        assertThat(saved.get("categoryAddPaise").get("icu").asLong()).isEqualTo(1100000);
        assertThat(saved.get("emergencyWithoutDeposit").asBoolean()).isFalse();
        assertThat(saved.get("alertPercent").asInt()).isEqualTo(90);
        assertThat(saved.get("confirmed").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.GET, RULES, clinic.as("NURSE"), null, 200)).isEqualTo(saved);
        assertThat(rows(clinic, "config")).isOne();
        assertThat(row(clinic, "select dep_planned_paise, cat_add_gen_paise, emergency_without_deposit, "
                + "deposit_alert_percent, deposit_rules_confirmed from ipd.config"))
                .containsExactly("1200000", "10000", "f", "90", "f");
        assertThat(audits(clinic, "IPD_ADMISSION_RULES_CHANGED")).isOne();

        ok(HttpMethod.POST, CONFIRM, clinic.owner(), null, 200);
        JsonNode again = ok(HttpMethod.PUT, RULES, clinic.owner(),
                rulesBody(1300000, 600000, 350000, 1600000, 10000, 250000, 550000, 1100000, false, 90), 200);
        assertThat(again.get("confirmed").asBoolean()).as("changing a value after confirming keeps it confirmed").isTrue();
        assertThat(again.get("depositPaise").get("planned").asLong()).isEqualTo(1300000);
        assertThat(audits(clinic, "IPD_ADMISSION_RULES_CHANGED")).isEqualTo(2);
        assertThat(rows(clinic, "config")).isOne();
    }

    @Test
    void rulesBoundariesAreAcceptedAndBadValuesGet400WithFieldErrorsAndChangeNothing() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, RULES, clinic.owner(), rulesBody(0, 0, 0, 0, 0, 0, 0, 0, true, 50), 200);
        JsonNode top = ok(HttpMethod.PUT, RULES, clinic.owner(),
                rulesBody(100000000, 100000000, 100000000, 100000000, 100000000, 100000000, 100000000, 100000000, false, 100), 200);
        assertThat(top.get("depositPaise").get("maternity").asLong()).isEqualTo(100000000);
        assertThat(top.get("alertPercent").asInt()).isEqualTo(100);
        JsonNode stored = ok(HttpMethod.GET, RULES, clinic.owner(), null, 200);
        long changes = audits(clinic, "IPD_ADMISSION_RULES_CHANGED");

        Object[][] bad = {
                {rulesBody(100050, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 80), "depositPaise.planned"},
                {rulesBody(1000000, 500001, 300000, 1500000, 0, 200000, 500000, 1000000, true, 80), "depositPaise.emergency"},
                {rulesBody(1000000, 500000, -100, 1500000, 0, 200000, 500000, 1000000, true, 80), "depositPaise.daycare"},
                {rulesBody(1000000, 500000, 300000, 100000100, 0, 200000, 500000, 1000000, true, 80), "depositPaise.maternity"},
                {rulesBody(1000000, 500000, 300000, 1500000, 150, 200000, 500000, 1000000, true, 80), "categoryAddPaise.gen"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, -1, 500000, 1000000, true, 80), "categoryAddPaise.semi"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 100000100, 1000000, true, 80), "categoryAddPaise.pvt"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000050, true, 80), "categoryAddPaise.icu"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 45), "alertPercent"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 49), "alertPercent"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 82), "alertPercent"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 105), "alertPercent"},
                {rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 0), "alertPercent"},
                {"{}", "depositPaise"},
                {"{\"depositPaise\":{\"planned\":1000000},\"categoryAddPaise\":{},\"emergencyWithoutDeposit\":true,"
                        + "\"alertPercent\":80}", "depositPaise.emergency"},
                {"{\"depositPaise\":{\"planned\":1,\"emergency\":1,\"daycare\":1,\"maternity\":1},\"categoryAddPaise\":"
                        + "{\"gen\":0,\"semi\":0,\"pvt\":0,\"icu\":0},\"alertPercent\":80}", "depositPaise"},
        };
        for (Object[] b : bad) {
            JsonNode p = problem(call(HttpMethod.PUT, RULES, clinic.owner(), (String) b[0]), 400);
            if (!"depositPaise".equals(b[1]) || !"{}".equals(b[0])) {
                assertThat(p.get("fieldErrors")).as((String) b[0]).isNotNull();
            }
        }
        // the first error names the right field for the single-field cases
        for (int i = 0; i < 13; i++) {
            assertFieldError(problem(call(HttpMethod.PUT, RULES, clinic.owner(), (String) bad[i][0]), 400), (String) bad[i][1]);
        }
        problem(call(HttpMethod.PUT, RULES, clinic.owner(), "not json"), 400);
        problem(call(HttpMethod.PUT, RULES, clinic.owner(), null), 400);
        assertThat(ok(HttpMethod.GET, RULES, clinic.owner(), null, 200)).isEqualTo(stored);
        assertThat(audits(clinic, "IPD_ADMISSION_RULES_CHANGED")).isEqualTo(changes);
    }

    @Test
    void nonOwnersGet403OnRulesWritesAndNothingChanges() throws Exception {
        Clinic clinic = ipdClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, RULES, clinic.as(role), rulesBody(1, 1, 1, 1, 1, 1, 1, 1, true, 80)), 403);
            problem(call(HttpMethod.POST, CONFIRM, clinic.as(role), null), 403);
        }
        mvc.perform(post(CONFIRM)).andExpect(status().isUnauthorized());
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
    }

    @Test
    void confirmIsIdempotentAuditsOnceAndCreatesTheRowWithDefaults() throws Exception {
        Clinic clinic = ipdClinic();
        JsonNode first = ok(HttpMethod.POST, CONFIRM, clinic.owner(), null, 200);
        assertThat(first.get("confirmed").asBoolean()).isTrue();
        assertThat(first.get("depositPaise").get("planned").asLong()).isEqualTo(1000000);
        JsonNode second = ok(HttpMethod.POST, CONFIRM, clinic.owner(), null, 200);
        assertThat(second).isEqualTo(first);
        assertThat(rows(clinic, "config")).isOne();
        assertThat(audits(clinic, "IPD_ADMISSION_RULES_CONFIRMED")).isOne();
        assertThat(audits(clinic, "IPD_ADMISSION_RULES_CHANGED")).isZero();
        assertThat(ok(HttpMethod.GET, RULES, clinic.as("DOCTOR"), null, 200).get("confirmed").asBoolean()).isTrue();
    }

    @Test
    void aPutThatChangesNothingWritesNoRowAndNoAudit() throws Exception {
        Clinic clinic = ipdClinic();
        ok(HttpMethod.PUT, RULES, clinic.owner(), defaultRules(), 200);
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
        String changed = rulesBody(1100000, 500000, 300000, 1500000, 0, 200000, 500000, 1000000, true, 80);
        ok(HttpMethod.PUT, RULES, clinic.owner(), changed, 200);
        ok(HttpMethod.PUT, RULES, clinic.owner(), changed, 200);
        assertThat(audits(clinic, "IPD_ADMISSION_RULES_CHANGED")).isOne();
    }

    // ---------------- AC3 options ----------------

    @Test
    void optionsListFreeBedsDoctorsAndRulesAndAnAdmittedBedDisappears() throws Exception {
        Clinic clinic = hospital();
        UUID bed1 = newBed(clinic, "gen");
        UUID bed2 = newBed(clinic, "icu");
        Member doctor = addMember(clinic, "DOCTOR");
        Member nurse = addMember(clinic, "NURSE");

        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            assertThat(ok(HttpMethod.GET, OPTIONS, clinic.as(role), null, 200).get("beds")).as(role).hasSize(2);
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.GET, OPTIONS, clinic.as(role), null), 403);
        }
        JsonNode o = ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        Set<String> doctorIds = new HashSet<>();
        o.get("doctors").forEach(d -> doctorIds.add(d.get("membershipId").asText()));
        assertThat(doctorIds).contains(clinic.doctorMembershipId().toString(), doctor.membershipId().toString())
                .doesNotContain(nurse.membershipId().toString());
        JsonNode first = null;
        for (JsonNode b : o.get("beds")) {
            if (b.get("id").asText().equals(bed1.toString())) {
                first = b;
            }
        }
        assertThat(first).isNotNull();
        assertThat(first.get("wardName").asText()).isEqualTo("General ward");
        assertThat(first.get("categoryKey").asText()).isEqualTo("gen");
        assertThat(first.get("categoryName").asText()).isEqualTo("General");
        assertThat(first.get("ratePaise").asLong()).isEqualTo(80000);
        assertThat(o.get("rules").get("depositPaise").get("planned").asLong()).isEqualTo(1000000);

        admitOk(clinic, body(patient(clinic), clinic.doctorMembershipId(), bed1));
        JsonNode after = ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        assertThat(after.get("beds")).hasSize(1);
        assertThat(after.get("beds").get(0).get("id").asText()).isEqualTo(bed2.toString());
        assertThat(after.get("beds").get(0).get("categoryKey").asText()).isEqualTo("icu");
    }

    // ---------------- AC4, AC5 admit ----------------

    @Test
    void admitHappyPathStoresEverythingIssuesIpNumbersInSequenceAndOccupiesTheBed() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        JsonNode before = layout(clinic);
        int wardBeds = wardNode(before, "General ward").get("bedCount").asInt();
        Person p = person(clinic, "Sunil Deshmukh");
        UUID doctor = clinic.doctorMembershipId();

        JsonNode a = admitOk(clinic, body(p.id(), doctor, bed));
        String ip = a.get("ipNumber").asText();
        assertThat(ip).matches("IP/\\d{2}-\\d{2}/\\d{4}").isEqualTo("IP/" + financialYear() + "/0001");
        assertThat(a.get("patientName").asText()).isEqualTo("Sunil Deshmukh");
        assertThat(a.get("age").asInt()).isEqualTo(56);
        assertThat(a.get("sex").asText()).isEqualTo("M");
        assertThat(a.get("mobile").asText()).isEqualTo(p.mobile());
        assertThat(a.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(a.get("bedCode").asText()).isEqualTo("101-A");
        assertThat(a.get("categoryName").asText()).isEqualTo("General");
        assertThat(a.get("wardName").asText()).isEqualTo("General ward");
        assertThat(a.get("admissionType").asText()).isEqualTo("planned");
        assertThat(a.get("payer").asText()).isEqualTo("Self");
        assertThat(a.get("dayNumber").asInt()).isEqualTo(1);
        assertThat(a.get("depositPaise").asLong()).isEqualTo(1000000);
        assertThat(a.get("minimumDepositPaise").asLong()).isEqualTo(1000000);
        assertThat(a.get("depositState").asText()).isEqualTo("OK");
        assertThat(a.get("reason").asText()).isEqualTo("Fever");
        assertThat(a.get("deposits")).hasSize(1);
        assertThat(a.get("deposits").get(0).get("amountPaise").asLong()).isEqualTo(1000000);
        assertThat(a.get("deposits").get(0).get("mode").asText()).isEqualTo("UPI");
        assertThat(a.get("deposits").get(0).get("collectedByName").asText()).isNotBlank();
        assertThat(a.get("canCollectDeposit").asBoolean()).isTrue();
        assertThat(a.get("admittedAt").asText()).isNotBlank();

        JsonNode after = layout(clinic);
        assertThat(bedNode(after, bed).get("status").asText()).isEqualTo("occ");
        assertThat(wardNode(after, "General ward").get("bedCount").asInt()).isEqualTo(wardBeds);

        String id = a.get("id").asText();
        assertThat(row(clinic, "select ip_number, status, source, admitted_by::text, consent_signed::text, reason, "
                + "admission_type, payer, doctor_membership_id::text, bed_id::text, patient_id::text, version::text, "
                + "(admitted_at > now() - interval '1 minute')::text, discharged_at is null "
                + "from ipd.admission where id = ?::uuid", id))
                .containsExactly(ip, "ADMITTED", "DIRECT", clinic.ownerUserId().toString(), "true", "Fever", "planned",
                        "Self", doctor.toString(), bed.toString(), p.id().toString(), "0", "true", "t");
        assertThat(count(clinic, "select count(*) from ipd.deposit where admission_id = ?::uuid and amount_paise = 1000000 "
                + "and mode = 'UPI' and collected_by = ?", id, clinic.ownerUserId())).isOne();
        assertThat(rows(clinic, "deposit")).isOne();
        assertThat(row(clinic, "select fy, last_no::text from ipd.ip_sequence")).containsExactly(financialYear(), "1");

        JsonNode b = admitOk(clinic, body(patient(clinic), doctor, newBed(clinic, "gen")));
        assertThat(b.get("ipNumber").asText()).isEqualTo("IP/" + financialYear() + "/0002");
        assertThat(row(clinic, "select last_no::text from ipd.ip_sequence")).containsExactly("2");
        assertThat(rows(clinic, "ip_sequence")).isOne();
    }

    @Test
    void aReasonOfOnlyNoBreakSpacesAndOtherSpaceSeparatorsIsBlankAndStoredAsUnderEvaluation() throws Exception {
        // ADR-007 R6: the shared blank rule also applies to the reason for admission (SAH-23)
        Clinic clinic = hospital();
        String[] blanks = {"\u00A0", "\u2007", "\u202F", "\u3000", "\u00A0\u2007\u202F\u3000", "\u00A0".repeat(150),
                " \u00A0\u202F\u3000 "};
        for (String blank : blanks) {
            Map<String, Object> body = body(clinic, newBed(clinic, "gen"));
            body.put("reason", blank);
            assertThat(admitOk(clinic, body).get("reason").asText()).as("U+" + Integer.toHexString(blank.codePointAt(0)))
                    .isEqualTo("Under evaluation");
        }
        Map<String, Object> padded = body(clinic, newBed(clinic, "gen"));
        padded.put("reason", "\u00A0Chest pain\u202F");
        assertThat(admitOk(clinic, padded).get("reason").asText()).isEqualTo("Chest pain");
        assertThat(count(clinic, "select count(*) from ipd.admission where reason = 'Under evaluation'")).isEqualTo(blanks.length);
    }

    @Test
    void blankOrMissingReasonIsStoredAsUnderEvaluationAndAReasonIsTrimmedAndBoundaryIs200() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> blank = body(clinic, newBed(clinic, "gen"));
        blank.put("reason", "   ");
        assertThat(admitOk(clinic, blank).get("reason").asText()).isEqualTo("Under evaluation");
        Map<String, Object> missing = body(clinic, newBed(clinic, "gen"));
        missing.remove("reason");
        assertThat(admitOk(clinic, missing).get("reason").asText()).isEqualTo("Under evaluation");
        Map<String, Object> nul = body(clinic, newBed(clinic, "gen"));
        nul.put("reason", null);
        assertThat(admitOk(clinic, nul).get("reason").asText()).isEqualTo("Under evaluation");
        Map<String, Object> trimmed = body(clinic, newBed(clinic, "gen"));
        trimmed.put("reason", "  Chest pain  ");
        assertThat(admitOk(clinic, trimmed).get("reason").asText()).isEqualTo("Chest pain");
        Map<String, Object> max = body(clinic, newBed(clinic, "gen"));
        String r200 = "r".repeat(200);
        max.put("reason", r200);
        assertThat(admitOk(clinic, max).get("reason").asText()).isEqualTo(r200);
        assertThat(count(clinic, "select count(*) from ipd.admission where reason = 'Under evaluation'")).isEqualTo(3);
    }

    @Test
    void checkOrderAndMessagesEachRefusalLeavesNothingBehind() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        UUID doctor = clinic.doctorMembershipId();
        Member nurse = addMember(clinic, "NURSE");
        UUID freeBed = newBed(clinic, "gen");
        UUID takenBed = newBed(clinic, "gen");
        UUID admittedPatient = patient(clinic);
        admitOk(clinic, body(admittedPatient, doctor, takenBed));
        UUID freshPatient = patient(clinic);
        UUID foreignPatient = patient(other);
        UUID foreignBed = newBed(other, "gen");
        long admissions = rows(clinic, "admission");
        long deposits = rows(clinic, "deposit");
        long audits = ipdAudits(clinic);
        String lastNo = row(clinic, "select last_no::text from ipd.ip_sequence").get(0);

        // 1 patient unknown or from another clinic: 404 (before the doctor, bed, consent and deposit checks)
        Map<String, Object> m = body(UUID.randomUUID(), nurse.membershipId(), takenBed);
        m.put("consentSigned", false);
        m.put("depositPaise", 0);
        problem(admit(clinic, m), 404);
        problem(admit(clinic, body(foreignPatient, doctor, freeBed)), 404);
        // 2 already admitted: 409 (before the doctor check)
        JsonNode p = problem(admit(clinic, body(admittedPatient, nurse.membershipId(), freeBed)), 409);
        assertType(p, "already-admitted");
        assertThat(p.get("detail").asText()).isEqualTo("This patient is already admitted.");
        // 3 doctor must be an active doctor: 400 (before the bed check)
        p = problem(admit(clinic, body(freshPatient, nurse.membershipId(), takenBed)), 400);
        assertFieldError(p, "doctorMembershipId");
        problem(admit(clinic, body(freshPatient, UUID.randomUUID(), freeBed)), 400);
        // 4 bed occupied, unknown or from another clinic: 409 bed-not-free (before consent and deposit)
        m = body(freshPatient, doctor, takenBed);
        m.put("consentSigned", false);
        m.put("depositPaise", 0);
        p = problem(admit(clinic, m), 409);
        assertType(p, "bed-not-free");
        assertThat(p.get("detail").asText()).isEqualTo("That bed is no longer free. Pick another.");
        p = problem(admit(clinic, body(freshPatient, doctor, UUID.randomUUID())), 409);
        assertType(p, "bed-not-free");
        assertType(problem(admit(clinic, body(freshPatient, doctor, foreignBed)), 409), "bed-not-free");
        // 5 consent: 400 with the exact message (before the deposit check)
        m = body(freshPatient, doctor, freeBed);
        m.put("consentSigned", false);
        m.put("depositPaise", 0);
        p = problem(admit(clinic, m), 400);
        assertThat(p.get("detail").asText()).isEqualTo("Record the admission consent first");
        assertFieldError(p, "consentSigned");
        m.remove("consentSigned");
        assertThat(problem(admit(clinic, m), 400).get("detail").asText()).isEqualTo("Record the admission consent first");
        m.put("consentSigned", null);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText()).isEqualTo("Record the admission consent first");
        // 6 deposit
        m = body(freshPatient, doctor, freeBed);
        m.put("depositPaise", 0);
        p = problem(admit(clinic, m), 400);
        assertThat(p.get("detail").asText()).isEqualTo(
                "The deposit is below the minimum of ₹10,000. Collect at least that, or choose Insurance or Corporate.");
        assertFieldError(p, "depositPaise");

        assertThat(rows(clinic, "admission")).isEqualTo(admissions);
        assertThat(rows(clinic, "deposit")).isEqualTo(deposits);
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        assertThat(row(clinic, "select last_no::text from ipd.ip_sequence")).as("no IP number is used by a refused request")
                .containsExactly(lastNo);
        assertThat(bedNode(layout(clinic), freeBed).get("status").asText()).isEqualTo("free");
        assertThat(rows(other, "admission")).isZero();
        assertThat(bedNode(layout(other), foreignBed).get("status").asText()).isEqualTo("free");
        // and the same patient and bed are fine once the request is valid
        admitOk(clinic, body(freshPatient, doctor, freeBed));
    }

    @Test
    void selfPayerBelowTheMinimumGetsTheExactMessageForEachTypeAndCategoryAndRuleChangesApply() throws Exception {
        Clinic clinic = hospital();
        UUID doctor = clinic.doctorMembershipId();
        UUID gen = newBed(clinic, "gen");
        UUID icu = newBed(clinic, "icu");
        UUID semi = newBed(clinic, "semi");
        UUID pvt = newBed(clinic, "pvt");
        String tail = ". Collect at least that, or choose Insurance or Corporate.";

        Map<String, Object> m = body(clinic, gen);
        m.put("depositPaise", 999900);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹10,000" + tail);
        m = body(clinic, icu);
        m.put("depositPaise", 1000000);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹20,000" + tail);
        m = body(clinic, semi);
        m.put("depositPaise", 1199900);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹12,000" + tail);
        m = body(clinic, pvt);
        m.put("depositPaise", 1499900);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹15,000" + tail);
        m = body(clinic, gen);
        m.put("admissionType", "daycare");
        m.put("depositPaise", 299900);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹3,000" + tail);
        m = body(clinic, icu);
        m.put("admissionType", "maternity");
        m.put("depositPaise", 2499900);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹25,000" + tail);
        assertThat(rows(clinic, "admission")).isZero();

        // exactly the minimum is accepted: planned / ICU = ₹20,000
        m = body(clinic, icu);
        m.put("depositPaise", 2000000);
        JsonNode ok = admitOk(clinic, m);
        assertThat(ok.get("minimumDepositPaise").asLong()).isEqualTo(2000000);
        assertThat(ok.get("depositState").asText()).isEqualTo("OK");

        // a rule change applies to the next admission: planned ₹1,25,000, General add-on ₹500
        ok(HttpMethod.PUT, RULES, clinic.owner(), rulesBody(12500000, 500000, 300000, 1500000, 50000, 200000, 500000,
                1000000, true, 80), 200);
        m = body(clinic, gen);
        m.put("depositPaise", 12500000);
        assertThat(problem(admit(clinic, m), 400).get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹1,25,500" + tail);
        m.put("depositPaise", 12550000);
        assertThat(admitOk(clinic, m).get("depositState").asText()).isEqualTo("OK");
    }

    @Test
    void emergencyWithoutDepositIsAllowedWhenTheSwitchIsOnAndRefusedWhenOff() throws Exception {
        Clinic clinic = hospital();
        UUID doctor = clinic.doctorMembershipId();
        Map<String, Object> m = body(clinic, newBed(clinic, "gen"));
        m.put("admissionType", "emergency");
        m.put("depositPaise", 0);
        m.remove("depositMode");
        JsonNode a = admitOk(clinic, m);
        assertThat(a.get("depositPaise").asLong()).isZero();
        assertThat(a.get("deposits")).isEmpty();
        assertThat(a.get("depositState").asText()).isEqualTo("DUE");
        assertThat(a.get("minimumDepositPaise").asLong()).isEqualTo(500000);
        assertThat(rows(clinic, "deposit")).isZero();

        Map<String, Object> noField = body(clinic, newBed(clinic, "gen"));
        noField.put("admissionType", "emergency");
        noField.remove("depositPaise");
        noField.remove("depositMode");
        admitOk(clinic, noField);

        Map<String, Object> partial = body(clinic, newBed(clinic, "gen"));
        partial.put("admissionType", "emergency");
        partial.put("depositPaise", 100000);
        assertThat(admitOk(clinic, partial).get("depositState").asText()).isEqualTo("DUE");

        Map<String, Object> planned = body(clinic, newBed(clinic, "gen"));
        planned.put("depositPaise", 0);
        problem(admit(clinic, planned), 400);

        ok(HttpMethod.PUT, RULES, clinic.owner(), rulesBody(1000000, 500000, 300000, 1500000, 0, 200000, 500000,
                1000000, false, 80), 200);
        Map<String, Object> off = body(clinic, newBed(clinic, "gen"));
        off.put("admissionType", "emergency");
        off.put("depositPaise", 0);
        assertThat(problem(admit(clinic, off), 400).get("detail").asText()).isEqualTo(
                "The deposit is below the minimum of ₹5,000. Collect at least that, or choose Insurance or Corporate.");
        off.put("depositPaise", 500000);
        assertThat(admitOk(clinic, off).get("depositState").asText()).isEqualTo("OK");
        assertThat(doctor).isNotNull();
    }

    @Test
    void insuranceAndCorporatePayersNeedNoDeposit() throws Exception {
        Clinic clinic = hospital();
        for (String payer : new String[] {"Insurance", "Corporate"}) {
            Map<String, Object> m = body(clinic, newBed(clinic, "icu"));
            m.put("payer", payer);
            m.put("depositPaise", 0);
            m.remove("depositMode");
            JsonNode a = admitOk(clinic, m);
            assertThat(a.get("payer").asText()).isEqualTo(payer);
            assertThat(a.get("depositState").asText()).isEqualTo("NOT_NEEDED");
            assertThat(a.get("depositPaise").asLong()).isZero();
            Map<String, Object> nul = body(clinic, newBed(clinic, "gen"));
            nul.put("payer", payer);
            nul.put("depositPaise", null);
            nul.remove("depositMode");
            assertThat(admitOk(clinic, nul).get("depositState").asText()).isEqualTo("NOT_NEEDED");
        }
        Map<String, Object> withDeposit = body(clinic, newBed(clinic, "gen"));
        withDeposit.put("payer", "Insurance");
        withDeposit.put("depositPaise", 100000);
        JsonNode d = admitOk(clinic, withDeposit);
        assertThat(d.get("depositState").asText()).isEqualTo("NOT_NEEDED");
        assertThat(d.get("depositPaise").asLong()).isEqualTo(100000);
        assertThat(rows(clinic, "deposit")).isOne();
    }

    @Test
    void malformedAdmissionInputGets400ProblemDetailsAndWritesNothing() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        UUID patient = patient(clinic);
        UUID doctor = clinic.doctorMembershipId();
        List<Object[]> cases = new ArrayList<>();
        Map<String, Object> m = body(patient, doctor, bed);
        m.put("admissionType", "surgery");
        cases.add(new Object[] {m, "admissionType"});
        m = body(patient, doctor, bed);
        m.remove("admissionType");
        cases.add(new Object[] {m, "admissionType"});
        m = body(patient, doctor, bed);
        m.put("payer", "Cash");
        cases.add(new Object[] {m, "payer"});
        m = body(patient, doctor, bed);
        m.remove("payer");
        cases.add(new Object[] {m, "payer"});
        m = body(patient, doctor, bed);
        m.put("reason", "r".repeat(201));
        cases.add(new Object[] {m, "reason"});
        m = body(patient, doctor, bed);
        m.remove("depositMode");
        cases.add(new Object[] {m, "depositMode"});
        m = body(patient, doctor, bed);
        m.put("depositMode", "Cheque");
        cases.add(new Object[] {m, "depositMode"});
        m = body(patient, doctor, bed);
        m.put("depositPaise", -100);
        cases.add(new Object[] {m, "depositPaise"});
        m = body(patient, doctor, bed);
        m.put("depositPaise", 1000050);
        cases.add(new Object[] {m, "depositPaise"});
        m = body(patient, doctor, bed);
        m.put("depositPaise", 100000100);
        cases.add(new Object[] {m, "depositPaise"});
        m = body(patient, doctor, bed);
        m.remove("patientId");
        cases.add(new Object[] {m, "patientId"});
        m = body(patient, doctor, bed);
        m.remove("doctorMembershipId");
        cases.add(new Object[] {m, "doctorMembershipId"});
        m = body(patient, doctor, bed);
        m.remove("bedId");
        cases.add(new Object[] {m, "bedId"});
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) c[0];
            JsonNode p = problem(admit(clinic, input), 400);
            assertFieldError(p, (String) c[1]);
        }
        problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), "{\"patientId\":\"not-a-uuid\"}"), 400);
        problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), "{\"depositPaise\":\"abc\"}"), 400);
        problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), "not json"), 400);
        problem(call(HttpMethod.POST, ADMISSIONS, clinic.owner(), "{}"), 400);
        assertThat(rows(clinic, "admission")).isZero();
        assertThat(rows(clinic, "ip_sequence")).isZero();
        assertThat(audits(clinic, "IPD_ADMITTED")).isZero();
        assertThat(bedNode(layout(clinic), bed).get("status").asText()).isEqualTo("free");

        // the largest deposit is accepted
        Map<String, Object> top = body(patient, doctor, bed);
        top.put("depositPaise", 100000000);
        assertThat(admitOk(clinic, top).get("depositPaise").asLong()).isEqualTo(100000000);
    }

    @Test
    void ownerFrontDeskAndNurseMayAdmitAndTheOtherRolesGet403() throws Exception {
        Clinic clinic = hospital();
        for (String role : NON_ADMITTERS) {
            problem(call(HttpMethod.POST, ADMISSIONS, clinic.as(role), js(body(clinic, newBed(clinic, "gen")))), 403);
        }
        assertThat(rows(clinic, "admission")).isZero();
        assertThat(rows(clinic, "ip_sequence")).isZero();
        int n = 0;
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE"}) {
            JsonNode a = ok(HttpMethod.POST, ADMISSIONS, clinic.as(role), js(body(clinic, newBed(clinic, "gen"))), 201);
            assertThat(a.get("ipNumber").asText()).endsWith(String.format("/%04d", ++n));
            assertThat(a.get("canCollectDeposit").asBoolean()).as(role).isEqualTo(!"NURSE".equals(role));
        }
        mvc.perform(post(ADMISSIONS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(rows(clinic, "admission")).isEqualTo(3);
    }

    // ---------------- concurrency ----------------

    @Test
    void twoSimultaneousAdmitsOfDifferentPatientsToTheSameBedGiveOneCreatedAndOneBedNotFree() throws Exception {
        Clinic clinic = hospital();
        UUID bed = newBed(clinic, "gen");
        List<String> bodies = List.of(js(body(clinic, bed)), js(body(clinic, bed)));
        List<Resp> r = parallel(clinic, ADMISSIONS, bodies);
        assertThat(r.stream().filter(x -> x.status() == 201).count()).isOne();
        Resp lost = r.stream().filter(x -> x.status() == 409).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(lost.body()).get("type").asText()).endsWith("bed-not-free");
        assertThat(rows(clinic, "admission")).isOne();
        assertThat(rows(clinic, "deposit")).isOne();
        assertThat(row(clinic, "select last_no::text from ipd.ip_sequence")).containsExactly("1");
        assertThat(audits(clinic, "IPD_ADMITTED")).isOne();
    }

    @Test
    void sixSimultaneousAdmitsToDifferentBedsAllSucceedWithDistinctConsecutiveIpNumbers() throws Exception {
        Clinic clinic = hospital();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bodies.add(js(body(clinic, newBed(clinic, "gen"))));
        }
        List<Resp> r = parallel(clinic, ADMISSIONS, bodies);
        assertThat(r.stream().map(Resp::status)).containsOnly(201);
        Set<String> numbers = new HashSet<>();
        for (Resp x : r) {
            numbers.add(objectMapper.readTree(x.body()).get("ipNumber").asText());
        }
        Set<String> expected = new HashSet<>();
        for (int i = 1; i <= 6; i++) {
            expected.add("IP/" + financialYear() + "/" + String.format("%04d", i));
        }
        assertThat(numbers).isEqualTo(expected);
        assertThat(row(clinic, "select last_no::text from ipd.ip_sequence")).containsExactly("6");
        assertThat(rows(clinic, "ip_sequence")).isOne();
        assertThat(count(clinic, "select count(distinct ip_number) from ipd.admission")).isEqualTo(6);
        assertThat(count(clinic, "select count(*) from ipd.bed where status = 'occ'")).isEqualTo(6);
    }

    @Test
    void simultaneousAdmitsOfTheSamePatientToDifferentBedsGiveExactlyOneAdmission() throws Exception {
        Clinic clinic = hospital();
        UUID patient = patient(clinic);
        UUID doctor = clinic.doctorMembershipId();
        UUID b1 = newBed(clinic, "gen");
        UUID b2 = newBed(clinic, "gen");
        UUID b3 = newBed(clinic, "gen");
        List<Resp> r = parallel(clinic, ADMISSIONS,
                List.of(js(body(patient, doctor, b1)), js(body(patient, doctor, b2)), js(body(patient, doctor, b3))));
        assertThat(r.stream().filter(x -> x.status() == 201).count()).isOne();
        assertThat(r.stream().filter(x -> x.status() == 409).count()).isEqualTo(2);
        for (Resp x : r) {
            if (x.status() == 409) {
                assertThat(objectMapper.readTree(x.body()).get("type").asText()).endsWith("already-admitted");
            }
        }
        assertThat(rows(clinic, "admission")).isOne();
        assertThat(count(clinic, "select count(*) from ipd.bed where status = 'occ'")).isOne();
        assertThat(row(clinic, "select last_no::text from ipd.ip_sequence")).containsExactly("1");
    }

    // ---------------- AC6 deposits ----------------

    @Test
    void collectingADepositAddsARowUpdatesTheTotalAndMovesDueToOk() throws Exception {
        Clinic clinic = hospital();
        Map<String, Object> m = body(clinic, newBed(clinic, "gen"));
        m.put("admissionType", "emergency");
        m.put("depositPaise", 100000);
        m.put("depositMode", "Cash");
        JsonNode a = admitOk(clinic, m);
        String id = a.get("id").asText();
        assertThat(a.get("depositState").asText()).isEqualTo("DUE");
        assertThat(a.get("depositPaise").asLong()).isEqualTo(100000);

        JsonNode b = ok(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.owner(),
                "{\"amountPaise\":300000,\"mode\":\"Card\"}", 200);
        assertThat(b.get("depositPaise").asLong()).isEqualTo(400000);
        assertThat(b.get("depositState").asText()).isEqualTo("DUE");
        assertThat(b.get("deposits")).hasSize(2);
        JsonNode c = ok(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.as("FRONT_DESK"),
                "{\"amountPaise\":100000,\"mode\":\"UPI\"}", 200);
        assertThat(c.get("depositPaise").asLong()).isEqualTo(500000);
        assertThat(c.get("depositState").asText()).as("reaching the minimum").isEqualTo("OK");
        assertThat(c.get("deposits")).hasSize(3);
        assertThat(c.get("deposits").get(0).get("mode").asText()).isEqualTo("Cash");
        assertThat(c.get("deposits").get(2).get("mode").asText()).isEqualTo("UPI");
        assertThat(c.get("deposits").get(2).get("collectedByName").asText()).isNotBlank();
        assertThat(c.get("deposits").get(2).get("collectedAt").asText()).isNotBlank();
        assertThat(count(clinic, "select count(*) from ipd.deposit where admission_id = ?::uuid", id)).isEqualTo(3);
        assertThat(count(clinic, "select sum(amount_paise) from ipd.deposit where admission_id = ?::uuid", id))
                .isEqualTo(500000);
        assertThat(audits(clinic, "IPD_DEPOSIT_COLLECTED")).isEqualTo(2);
        // the list agrees
        assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200).get("items").get(0)
                .get("depositState").asText()).isEqualTo("OK");
        // the boundaries of a single deposit
        ok(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.owner(), "{\"amountPaise\":100,\"mode\":\"Cash\"}", 200);
        ok(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.owner(),
                "{\"amountPaise\":100000000,\"mode\":\"Cash\"}", 200);
    }

    @Test
    void depositRolesValidationAndNotFoundAnd409WhenNotAdmitted() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        JsonNode a = quickAdmit(clinic);
        String url = ADMISSIONS + "/" + a.get("id").asText() + "/deposits";
        String good = "{\"amountPaise\":100000,\"mode\":\"UPI\"}";
        for (String role : new String[] {"NURSE", "DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.POST, url, clinic.as(role), good), 403);
        }
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(good)).andExpect(status().isUnauthorized());
        assertThat(rows(clinic, "deposit")).isOne();

        String[] bad = {"{\"amountPaise\":99,\"mode\":\"UPI\"}", "{\"amountPaise\":0,\"mode\":\"UPI\"}",
                "{\"amountPaise\":-100,\"mode\":\"UPI\"}", "{\"amountPaise\":150,\"mode\":\"UPI\"}",
                "{\"amountPaise\":100000100,\"mode\":\"UPI\"}", "{\"mode\":\"UPI\"}", "{\"amountPaise\":null,\"mode\":\"UPI\"}"};
        for (String b : bad) {
            assertFieldError(problem(call(HttpMethod.POST, url, clinic.owner(), b), 400), "amountPaise");
        }
        for (String b : new String[] {"{\"amountPaise\":100000,\"mode\":\"Cheque\"}", "{\"amountPaise\":100000}",
                "{\"amountPaise\":100000,\"mode\":\"upi\"}"}) {
            assertFieldError(problem(call(HttpMethod.POST, url, clinic.owner(), b), 400), "mode");
        }
        problem(call(HttpMethod.POST, url, clinic.owner(), "{\"amountPaise\":\"abc\",\"mode\":\"UPI\"}"), 400);
        problem(call(HttpMethod.POST, url, clinic.owner(), "not json"), 400);
        assertThat(rows(clinic, "deposit")).isOne();

        // unknown and other-tenant ids are 404
        problem(call(HttpMethod.POST, ADMISSIONS + "/" + UUID.randomUUID() + "/deposits", clinic.owner(), good), 404);
        JsonNode foreign = quickAdmit(other);
        problem(call(HttpMethod.POST, ADMISSIONS + "/" + foreign.get("id").asText() + "/deposits", clinic.owner(), good), 404);
        assertThat(rows(other, "deposit")).isOne();

        // a discharged admission takes no deposit
        commitAsTenant(clinic.tenantId(), "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid",
                a.get("id").asText());
        JsonNode p = problem(call(HttpMethod.POST, url, clinic.owner(), good), 409);
        assertType(p, "not-admitted");
        assertThat(p.get("detail").asText()).isEqualTo("This patient is not admitted any more. A deposit cannot be added.");
        assertThat(rows(clinic, "deposit")).isOne();
        assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + a.get("id").asText(), clinic.owner(), null, 200)
                .get("canCollectDeposit").asBoolean()).isFalse();
    }

    @Test
    void depositTableIsAppendOnlyAndColumnGrantsHoldForSahajApp() throws Exception {
        Clinic clinic = hospital();
        JsonNode a = quickAdmit(clinic);
        String id = a.get("id").asText();
        assertDenied(clinic, "update ipd.deposit set amount_paise = 1");
        assertDenied(clinic, "delete from ipd.deposit");
        assertDenied(clinic, "truncate ipd.deposit");
        assertDenied(clinic, "delete from ipd.admission");
        assertDenied(clinic, "update ipd.admission set ip_number = 'IP/00-00/9999' where id = ?::uuid", id);
        assertDenied(clinic, "update ipd.admission set tenant_id = gen_random_uuid() where id = ?::uuid", id);
        assertDenied(clinic, "update ipd.admission set admitted_at = now() where id = ?::uuid", id);
        assertThat(rows(clinic, "deposit")).isOne();
        assertThat(row(clinic, "select amount_paise::text from ipd.deposit")).containsExactly("1000000");
        // the owner of the table cannot truncate either (trigger)
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "truncate ipd.deposit");
            return null;
        })).isInstanceOf(SQLException.class);

        UUID bed = newBed(clinic, "gen");
        UUID patient = patient(clinic);
        UUID doctor = clinic.doctorMembershipId();
        // insert with an explicit id or admitted_at is permission denied
        assertDenied(clinic, "insert into ipd.admission (id, patient_id, ip_number, bed_id, doctor_membership_id, "
                + "admission_type, payer, consent_signed, status, admitted_by) values (gen_random_uuid(), ?, 'X/1', ?, ?, "
                + "'planned', 'Self', true, 'ADMITTED', ?)", patient, bed, doctor, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission (admitted_at, patient_id, ip_number, bed_id, doctor_membership_id, "
                + "admission_type, payer, consent_signed, status, admitted_by) values (now(), ?, 'X/1', ?, ?, "
                + "'planned', 'Self', true, 'ADMITTED', ?)", patient, bed, doctor, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.deposit (id, admission_id, amount_paise, mode, collected_by) "
                + "values (gen_random_uuid(), ?::uuid, 100, 'UPI', ?)", id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.deposit (collected_at, admission_id, amount_paise, mode, collected_by) "
                + "values (now(), ?::uuid, 100, 'UPI', ?)", id, clinic.ownerUserId());
        // positive control: the permitted columns work, so the denials above are the grants
        db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "insert into ipd.admission (patient_id, ip_number, bed_id, doctor_membership_id, "
                    + "admission_type, payer, consent_signed, status, admitted_by) values (?, 'X/1', ?, ?, "
                    + "'planned', 'Self', true, 'ADMITTED', ?)", patient, bed, doctor, clinic.ownerUserId());
            DbAccess.execute(c, "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by) "
                    + "values (?::uuid, 100, 'UPI', ?)", id, clinic.ownerUserId());
            return null;
        });
        // check constraints
        assertDenied(clinic, "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by) "
                + "values (?::uuid, 0, 'UPI', ?)", id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by) "
                + "values (?::uuid, 100, 'Cheque', ?)", id, clinic.ownerUserId());
        assertDenied(clinic, "insert into ipd.admission (patient_id, ip_number, bed_id, doctor_membership_id, "
                + "admission_type, payer, consent_signed, status, admitted_by) values (?, 'X/2', ?, ?, "
                + "'planned', 'Self', false, 'ADMITTED', ?)", patient, bed, doctor, clinic.ownerUserId());
    }

    // ---------------- AC2 list and detail ----------------

    @Test
    void listIsNewestFirstWithCountsRowFieldsAndCanAdmitByRole() throws Exception {
        Clinic clinic = hospital();
        UUID b1 = newBed(clinic, "gen");
        UUID b2 = newBed(clinic, "icu");
        UUID b3 = newBed(clinic, "gen");
        newBed(clinic, "gen");
        Person first = person(clinic, "Anil Joshi");
        Map<String, Object> m1 = body(first.id(), clinic.doctorMembershipId(), b1);
        JsonNode a1 = admitOk(clinic, m1);
        Map<String, Object> m2 = body(clinic, b2);
        m2.put("depositPaise", 2000000);
        JsonNode a2 = admitOk(clinic, m2);
        Map<String, Object> m3 = body(clinic, b3);
        m3.put("payer", "Corporate");
        m3.put("depositPaise", 0);
        m3.remove("depositMode");
        JsonNode a3 = admitOk(clinic, m3);

        JsonNode list = ok(HttpMethod.GET, ADMISSIONS + "?status=ADMITTED", clinic.owner(), null, 200);
        assertThat(list.get("bedsTotal").asInt()).isEqualTo(4);
        assertThat(list.get("bedsOccupied").asInt()).isEqualTo(3);
        assertThat(list.get("bedsAvailable").asInt()).isEqualTo(1);
        assertThat(list.get("canAdmit").asBoolean()).isTrue();
        JsonNode items = list.get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("id").asText()).isEqualTo(a3.get("id").asText());
        assertThat(items.get(1).get("id").asText()).isEqualTo(a2.get("id").asText());
        assertThat(items.get(2).get("id").asText()).isEqualTo(a1.get("id").asText());
        JsonNode row = items.get(2);
        assertThat(row.get("patientName").asText()).isEqualTo("Anil Joshi");
        assertThat(row.get("age").asInt()).isEqualTo(56);
        assertThat(row.get("sex").asText()).isEqualTo("M");
        assertThat(row.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(row.get("bedCode").asText()).isEqualTo("101-A");
        assertThat(row.get("categoryName").asText()).isEqualTo("General");
        assertThat(row.get("wardName").asText()).isEqualTo("General ward");
        assertThat(row.get("dayNumber").asInt()).isEqualTo(1);
        assertThat(row.get("depositPaise").asLong()).isEqualTo(1000000);
        assertThat(row.get("minimumDepositPaise").asLong()).isEqualTo(1000000);
        assertThat(row.get("depositState").asText()).isEqualTo("OK");
        assertThat(row.get("ipNumber").asText()).isEqualTo(a1.get("ipNumber").asText());
        assertThat(row.has("reason")).as("the list carries no clinical text").isFalse();
        assertThat(row.has("mobile")).isFalse();
        // SAH-36 (ADR-013 D1): the row also carries the UHID, the ward id and the room name; the clinical object is for the clinical roles
        assertThat(row.get("uhid").asText()).isNotBlank();
        assertThat(row.get("wardId").asText()).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        assertThat(row.has("roomName")).isTrue();
        assertThat(row.has("clinical")).as("the owner is a clinical role").isTrue();
        assertThat(list.get("wards")).isNotEmpty();
        assertThat(list.get("card").get("transfer").asBoolean()).isTrue();
        assertThat(Instant.parse(list.get("asOf").asText())).isNotNull();
        assertThat(items.get(1).get("categoryName").asText()).isEqualTo("ICU");
        assertThat(items.get(0).get("depositState").asText()).isEqualTo("NOT_NEEDED");

        for (String role : READERS) {
            JsonNode l = ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200);
            assertThat(l.get("canAdmit").asBoolean()).as(role).isEqualTo(!"DOCTOR".equals(role));
            assertThat(l.get("items")).hasSize(3);
            // SAH-36: clinical facts only for OWNER, DOCTOR, NURSE; absent (not null) for the front desk
            assertThat(l.get("items").get(0).has("clinical")).as(role + " clinical object").isEqualTo(!"FRONT_DESK".equals(role));
            assertThat(l.get("items").get(0).has("uhid")).as(role + " uhid").isTrue();
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, ADMISSIONS, clinic.as(role), null), 403);
        }
        for (String bad : new String[] {"?status=DISCHARGED", "?status=ALL", "?status=admitted", "?status="}) {
            assertFieldError(problem(call(HttpMethod.GET, ADMISSIONS + bad, clinic.owner(), null), 400), "status");
        }
        // a discharged admission leaves the Admitted list
        commitAsTenant(clinic.tenantId(), "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid",
                a1.get("id").asText());
        assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200).get("items")).hasSize(2);
    }

    @Test
    void detailShowsDepositsReasonMobileAndCanCollectByRoleAnd404ForUnknown() throws Exception {
        Clinic clinic = hospital();
        Person p = person(clinic, "Mahesh Gaikwad");
        Map<String, Object> m = body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "gen"));
        m.put("reason", "Dengue fever, platelets low");
        JsonNode a = admitOk(clinic, m);
        String url = ADMISSIONS + "/" + a.get("id").asText();
        ok(HttpMethod.POST, url + "/deposits", clinic.owner(), "{\"amountPaise\":200000,\"mode\":\"Cash\"}", 200);
        String accessSql = "select count(*) from patient.record_access where patient_id = ?";
        // The deposit POST returns the full sheet, so it logs one access row itself (security review L2).
        long opened = count(clinic, accessSql, p.id());
        assertThat(opened).isOne();
        for (String role : READERS) {
            JsonNode d = ok(HttpMethod.GET, url, clinic.as(role), null, 200);
            // every detail read writes one access row; the mobile number goes to the owner and front desk only
            assertThat(count(clinic, accessSql, p.id())).as(role + " access rows").isEqualTo(++opened);
            assertThat(d.get("patientName").asText()).isEqualTo("Mahesh Gaikwad");
            if ("OWNER".equals(role) || "FRONT_DESK".equals(role)) {
                assertThat(d.get("mobile").asText()).as(role).isEqualTo(p.mobile());
            } else {
                assertThat(d.hasNonNull("mobile")).as(role + " mobile").isFalse();
            }
            assertThat(d.get("reason").asText()).isEqualTo("Dengue fever, platelets low");
            assertThat(d.get("deposits")).hasSize(2);
            assertThat(d.get("deposits").get(1).get("amountPaise").asLong()).isEqualTo(200000);
            assertThat(d.get("deposits").get(1).get("mode").asText()).isEqualTo("Cash");
            assertThat(d.get("deposits").get(1).get("collectedByName").asText()).isNotBlank();
            assertThat(d.get("depositPaise").asLong()).isEqualTo(1200000);
            assertThat(d.get("canCollectDeposit").asBoolean()).as(role)
                    .isEqualTo("OWNER".equals(role) || "FRONT_DESK".equals(role));
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, url, clinic.as(role), null), 403);
        }
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + UUID.randomUUID(), clinic.owner(), null), 404);
        problem(call(HttpMethod.GET, ADMISSIONS + "/not-a-uuid", clinic.owner(), null), 400);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    // ---------------- AC8 bed delete guard ----------------

    @Test
    void removingABedWithAdmissionHistoryIs409AndANeverUsedBedStillDeletes() throws Exception {
        Clinic clinic = hospital();
        UUID used = newBed(clinic, "gen");
        UUID never = newBed(clinic, "gen");
        JsonNode a = admitOk(clinic, body(clinic, used));

        JsonNode occupied = problem(call(HttpMethod.DELETE, BEDS + "/" + used, clinic.owner(), null), 409);
        assertType(occupied, "bed-occupied");

        // discharge by SQL and free the bed: the history still protects it
        commitAsTenant(clinic.tenantId(), "update ipd.admission set status = 'DISCHARGED' where id = ?::uuid",
                a.get("id").asText());
        commitAsTenant(clinic.tenantId(), "update ipd.bed set status = 'free' where id = ?", used);
        JsonNode history = problem(call(HttpMethod.DELETE, BEDS + "/" + used, clinic.owner(), null), 409);
        assertType(history, "bed-has-history");
        assertThat(history.get("detail").asText()).isEqualTo("This bed has admission history and cannot be removed.");
        assertThat(count(clinic, "select count(*) from ipd.bed where id = ?", used)).isOne();
        assertThat(audits(clinic, "IPD_BED_REMOVED")).isZero();

        ok(HttpMethod.DELETE, BEDS + "/" + never, clinic.owner(), null, 200);
        assertThat(count(clinic, "select count(*) from ipd.bed where id = ?", never)).isZero();
        assertThat(audits(clinic, "IPD_BED_REMOVED")).isOne();
        // a raw delete of a used bed is stopped by the composite foreign key too
        commitAsTenant(clinic.tenantId(), "update ipd.bed set status = 'free' where id = ?", used);
        assertDenied(clinic, "delete from ipd.bed where id = ?", used);
    }

    // ---------------- AC9 audit and privacy ----------------

    @Test
    void auditCarriesIdsAndAmountsButNeverTheReasonNameOrMobileAndNothingIsLogged(CapturedOutput output) throws Exception {
        Clinic clinic = hospital();
        String secretName = "Zaheer Pimpalkhute" + uniqueMobile().substring(5);
        String secretReason = "ZXQ-" + UUID.randomUUID();
        Person p = person(clinic, secretName);
        UUID bed = newBed(clinic, "gen");
        Map<String, Object> m = body(p.id(), clinic.doctorMembershipId(), bed);
        m.put("reason", secretReason);
        JsonNode a = admitOk(clinic, m);
        String id = a.get("id").asText();
        String ip = a.get("ipNumber").asText();
        JsonNode withDeposit = ok(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.owner(),
                "{\"amountPaise\":250000,\"mode\":\"Card\"}", 200);
        String depositId = withDeposit.get("deposits").get(1).get("id").asText();
        ok(HttpMethod.PUT, RULES, clinic.owner(), rulesBody(1100000, 500000, 300000, 1500000, 0, 200000, 500000,
                1000000, true, 85), 200);
        ok(HttpMethod.POST, CONFIRM, clinic.owner(), null, 200);
        ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.owner(), null, 200);
        ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);

        List<String[]> admitted = audit(clinic, "IPD_ADMITTED");
        assertThat(admitted).hasSize(1);
        assertThat(admitted.get(0)[0]).isEqualTo("IPD_ADMISSION");
        assertThat(admitted.get(0)[1]).isEqualTo(id);
        assertThat(admitted.get(0)[2]).isNull();
        assertThat(admitted.get(0)[3]).contains(ip).contains(p.id().toString()).contains("planned").contains("Self")
                .contains("101-A").contains("1000000").contains("UPI").contains(bed.toString())
                .contains(clinic.doctorMembershipId().toString());
        assertThat(admitted.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());

        List<String[]> collected = audit(clinic, "IPD_DEPOSIT_COLLECTED");
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0)[0]).isEqualTo("IPD_DEPOSIT");
        assertThat(collected.get(0)[1]).isEqualTo(depositId);
        assertThat(collected.get(0)[3]).contains(id).contains(ip).contains("250000").contains("Card");
        assertThat(collected.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());

        List<String[]> changed = audit(clinic, "IPD_ADMISSION_RULES_CHANGED");
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0)[2]).contains("1000000").contains("80");
        assertThat(changed.get(0)[3]).contains("1100000").contains("85");
        List<String[]> confirmed = audit(clinic, "IPD_ADMISSION_RULES_CONFIRMED");
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.get(0)[2]).contains("false");
        assertThat(confirmed.get(0)[3]).contains("true");
        for (String action : IPD_ACTIONS) {
            assertThat(audits(clinic, action)).as(action).isOne();
        }

        // the typed texts exist in the data (so the checks below have teeth) and nowhere in the audit rows
        assertThat(count(clinic, "select count(*) from ipd.admission where reason = ?", secretReason)).isOne();
        assertThat(count(clinic, "select count(*) from patient.patient where name = ? and mobile = ?", secretName, p.mobile()))
                .isOne();
        for (String secret : new String[] {secretReason, secretName, p.mobile(), "Zaheer"}) {
            assertThat(count(clinic, "select count(*) from platform.audit_event where audit_event::text like ?",
                    "%" + secret + "%")).as("audit rows must not contain " + secret).isZero();
            assertThat(output.getAll()).as("log must not contain " + secret).doesNotContain(secret);
        }
    }

    // ---------------- AC1 menu ----------------

    @Test
    void menuHasIpdForFourRolesWhenIpdIsOnOrTrialOrReadonlyAndNeverWhenOff() throws Exception {
        Clinic clinic = newClinic();
        Map<String, int[]> offCounts = new LinkedHashMap<>();
        for (String role : ALL_ROLES) {
            assertThat(menu(clinic, role)).as(role + " off").doesNotContain("ipd").doesNotContain("emergency");
            JsonNode m = ok(HttpMethod.GET, MODULES, clinic.as(role), null, 200);
            offCounts.put(role, new int[] {m.get("menuCounts").get("simple").asInt(), m.get("menuCounts").get("advanced").asInt()});
        }
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        for (String role : ALL_ROLES) {
            // SAH-31 (DEC-017 Q2, ADR-009 D11): the PHARMACIST also has the item ipd (the Safety-only page), right after today
            boolean has = java.util.Arrays.asList(READERS).contains(role) || "PHARMACIST".equals(role);
            assertThat(menu(clinic, role).contains("ipd")).as(role + " trial").isEqualTo(has);
            JsonNode m = ok(HttpMethod.GET, MODULES, clinic.as(role), null, 200);
            int[] off = offCounts.get(role);
            assertThat(m.get("menuCounts").get("simple").asInt()).as(role + " simple count").isEqualTo(off[0] + (has ? 1 : 0));
            // SAH-28: OWNER, FRONT_DESK and NURSE also get Emergency in the advanced menu (Trial or On); DOCTOR does not.
            int advancedPlus = !has ? 0 : java.util.Arrays.asList("OWNER", "FRONT_DESK", "NURSE").contains(role) ? 2 : 1;
            assertThat(m.get("menuCounts").get("advanced").asInt()).as(role + " advanced count").isEqualTo(off[1] + advancedPlus);
        }
        List<String> owner = menu(clinic, "OWNER");
        assertThat(owner.indexOf("emergency")).as("emergency after follow-ups").isEqualTo(owner.indexOf("followups") + 1);
        assertThat(owner.indexOf("ipd")).as("ipd after emergency").isEqualTo(owner.indexOf("emergency") + 1);
        List<String> frontDesk = menu(clinic, "FRONT_DESK");
        assertThat(frontDesk.indexOf("emergency")).as("front desk: emergency after follow-ups")
                .isEqualTo(frontDesk.indexOf("followups") + 1);
        assertThat(frontDesk.indexOf("ipd")).isEqualTo(frontDesk.indexOf("emergency") + 1);
        List<String> nurse = menu(clinic, "NURSE");
        assertThat(nurse).as("nurse has no follow-ups").doesNotContain("followups");
        assertThat(nurse.indexOf("emergency")).as("nurse: emergency then ipd").isGreaterThanOrEqualTo(0);
        assertThat(nurse.indexOf("ipd")).isEqualTo(nurse.indexOf("emergency") + 1);
        List<String> pharmacist = menu(clinic, "PHARMACIST");
        assertThat(pharmacist).as("pharmacist has ipd and no Emergency item").contains("ipd").doesNotContain("emergency");
        assertThat(pharmacist.indexOf("ipd")).as("pharmacist: ipd right after today").isEqualTo(pharmacist.indexOf("today") + 1);
        List<String> doctor = menu(clinic, "DOCTOR");
        assertThat(doctor).as("doctor has no Emergency item").doesNotContain("emergency");
        assertThat(doctor.indexOf("ipd")).isEqualTo(doctor.indexOf("followups") + 1);
        expireTrial(clinic);
        for (String role : READERS) {
            assertThat(menu(clinic, role)).as(role + " readonly").contains("ipd").doesNotContain("emergency");
        }
        assertThat(menu(clinic, "PHARMACIST")).as("PHARMACIST readonly").contains("ipd").doesNotContain("emergency");
        for (String role : NON_READERS) {
            if ("PHARMACIST".equals(role)) {
                continue; // SAH-31: the PHARMACIST has the item ipd (checked above); the API of this story stays closed to it
            }
            assertThat(menu(clinic, role)).as(role + " readonly").doesNotContain("ipd").doesNotContain("emergency");
        }
    }

    // ---------------- AC9 row level security ----------------

    @Test
    void anotherClinicSeesNothingThroughTheApiOrSqlAndIpNumbersStartAgainAt0001() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        JsonNode a1 = quickAdmit(a);
        quickAdmit(a);
        String url = ADMISSIONS + "/" + a1.get("id").asText();

        assertThat(ok(HttpMethod.GET, ADMISSIONS, b.owner(), null, 200).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, ADMISSIONS, b.owner(), null, 200).get("bedsTotal").asInt()).isZero();
        problem(call(HttpMethod.GET, url, b.owner(), null), 404);
        problem(call(HttpMethod.POST, url + "/deposits", b.owner(), "{\"amountPaise\":100000,\"mode\":\"UPI\"}"), 404);
        for (String t : NEW_TABLES) {
            assertThat(rows(b, t)).as("tenant B " + t).isZero();
            assertThat(rows(a, t)).as("tenant A " + t).isPositive();
            long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd." + t)); assertThat(unbound).as("no tenant bound " + t).isZero();
        }
        assertThat(rows(b, "config")).isZero();
        assertThat(count(b, "select count(*) from ipd.admission where id = ?::uuid", a1.get("id").asText())).isZero();

        // tenant B's own first admission is 0001, A is at 0002
        JsonNode b1 = quickAdmit(b);
        assertThat(b1.get("ipNumber").asText()).isEqualTo("IP/" + financialYear() + "/0001");
        assertThat(row(a, "select last_no::text from ipd.ip_sequence")).containsExactly("2");
        assertThat(row(b, "select last_no::text from ipd.ip_sequence")).containsExactly("1");
        assertThat(rows(a, "admission")).isEqualTo(2);
    }

    @Test
    void anAdmissionCannotPointAtAnotherClinicsBedOrBeBoundToAnotherTenant() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        UUID bedA = newBed(a, "gen");
        UUID bedB = newBed(b, "gen");
        UUID patientB = patient(b);
        String insert = "insert into ipd.admission (patient_id, ip_number, bed_id, doctor_membership_id, admission_type, "
                + "payer, consent_signed, status, admitted_by) values (?, ?, ?, ?, 'planned', 'Self', true, 'ADMITTED', ?)";
        // positive control: B's own bed works (rolled back)
        db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, insert, patientB, "X/1", bedB, b.doctorMembershipId(), b.ownerUserId());
            return null;
        });
        // A's bed from B's session: the composite foreign key finds no such bed in tenant B
        assertDenied(b, insert, patientB, "X/2", bedA, b.doctorMembershipId(), b.ownerUserId());
        // an explicit foreign tenant_id is refused by the RLS check (tenant_id is not insertable at all)
        assertDenied(b, "insert into ipd.admission (tenant_id, patient_id, ip_number, bed_id, doctor_membership_id, "
                + "admission_type, payer, consent_signed, status, admitted_by) values (?, ?, ?, ?, ?, 'planned', 'Self', "
                + "true, 'ADMITTED', ?)", a.tenantId(), patientB, "X/3", bedB, b.doctorMembershipId(), b.ownerUserId());
        // no tenant bound: nothing can be inserted
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, insert, patientB, "X/4", bedB, b.doctorMembershipId(), b.ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class);
        // one current admission per patient (partial unique index)
        JsonNode adm = admitOk(b, body(patientB, b.doctorMembershipId(), newBed(b, "gen")));
        assertThat(adm.get("id").asText()).isNotBlank();
        assertDenied(b, insert, patientB, "X/5", bedB, b.doctorMembershipId(), b.ownerUserId());
        // a duplicate IP number in the same clinic is refused
        assertDenied(b, insert, patient(b), adm.get("ipNumber").asText(), bedB, b.doctorMembershipId(), b.ownerUserId());
    }
}
