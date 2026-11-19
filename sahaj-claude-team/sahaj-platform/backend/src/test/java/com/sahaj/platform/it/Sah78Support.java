package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-78 WP-A integration tests (Pharmacy and Inventory masters: module switch, menu, unit settings,
 * stores, staff mapping, working store, store guard) on the real {@code sahaj_test} database. Clinics, owners and team members
 * are real (sign-up, setup wizard, a membership row per person); the module switch is written the way the design note
 * (docs/database/SAH-78-pharmacy-masters.md section 5.3) says: as sahaj_app with the tenant bound.
 *
 * <p>Texts, slugs and urls are copied from docs/api/pharmacy.yaml 0.1.0 and docs/stories/SAH-78.md, never read from the
 * application. Test plan: docs/test-plans/SAH-78.md.
 */
abstract class Sah78Support extends PinTestSupport {

    static final String PH = "/api/v1/pharmacy";
    static final String CONTEXT = PH + "/context";
    static final String WORKING_STORE = CONTEXT + "/working-store";
    static final String SUMMARY = PH + "/setup-summary";
    static final String UNIT = PH + "/unit-settings";
    static final String STORES = PH + "/stores";
    static final String CANDIDATES = PH + "/staff-candidates";
    static final String ME = "/api/v1/platform/me";
    static final String TEAM = "/api/v1/platform/team";
    static final String PERMS = "/api/v1/platform/role-permissions";
    static final String MODULES = "/api/v1/platform/modules";
    static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    static final String[] EXISTING_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
        "PHARMACIST", "COUNSELLOR"};

    // texts of docs/api/pharmacy.yaml and docs/stories/SAH-78.md
    static final String MODULE_OFF_TEXT = "Pharmacy & Inventory is not switched on for your clinic.";
    static final String NO_ACCESS_TEXT = "You do not have access to Pharmacy & Inventory. Ask your owner.";
    static final String CENTRAL_MISSING_TEXT = "Create the Central Store first.";
    static final String CENTRAL_EXISTS_TEXT = "This unit already has a Central Store.";
    static final String NAME_IN_USE_TEXT = "That store name is already used";
    static final String ALREADY_MAPPED_TEXT = "That person is already in this store";
    static final String GSTIN_TEXT = "Enter a 15-character GSTIN";

    /** A made-up GSTIN of the right shape (test data only; no check digit is validated). */
    static final String GSTIN_OK = "27AAAAA0000A1Z5";

    // ---------------- the module switch ----------------

    /** A signed-up, set-up clinic with an owner and one doctor, Pharmacy and Inventory switched on. */
    protected Clinic pharmacyClinic() throws Exception {
        Clinic clinic = newClinic();
        pharmacyOn(clinic, true);
        return clinic;
    }

    /** The fixture of the design note: an upsert of pharmacy_on as sahaj_app with the tenant bound (committed). */
    protected void pharmacyOn(Clinic clinic, boolean on) throws SQLException {
        commitAsTenant(clinic.tenantId(), "insert into platform.module_entitlement (tenant_id, pharmacy_on, updated_by) "
                + "values (?::uuid, ?, ?::uuid) on conflict (tenant_id) do update set pharmacy_on = excluded.pharmacy_on",
                clinic.tenantId().toString(), on, clinic.ownerUserId().toString());
    }

    // ---------------- people ----------------

    /** A real person of the clinic (own user, membership row, token); not counted by the plan limit of the Team endpoint. */
    protected Pn crew(Clinic clinic, String role, String name) throws Exception {
        return pn(clinic, staff(clinic, role, name));
    }

    protected Pn crew(Clinic clinic, String role) throws Exception {
        return crew(clinic, role, "Test " + role.toLowerCase().replace('_', ' ') + " " + UUID.randomUUID().toString().substring(0, 4));
    }

    protected static String idOf(JsonNode node) {
        return node.get("id").asText();
    }

    // ---------------- stores ----------------

    protected static Map<String, Object> storeBody(String name, String code, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("code", code);
        m.put("type", type);
        return m;
    }

    protected JsonNode createStore(Clinic clinic, String name, String code, String type) throws Exception {
        return ok(HttpMethod.POST, STORES, clinic.owner(), js(storeBody(name, code, type)), 201);
    }

    protected JsonNode centralStore(Clinic clinic) throws Exception {
        return createStore(clinic, "Central Store", "CS", "CENTRAL");
    }

    /** The one Central Store (when missing, created) and a Pharmacy store; returns {central, pharmacy}. */
    protected JsonNode[] centralAndPharmacy(Clinic clinic) throws Exception {
        JsonNode central = centralStore(clinic);
        JsonNode pharmacy = createStore(clinic, "Main Pharmacy", "PHARM", "PHARMACY");
        return new JsonNode[] {central, pharmacy};
    }

    protected static String staffUrl(Object storeId) {
        return STORES + "/" + storeId + "/staff";
    }

    protected static String staffUrl(Object storeId, Object mappingId) {
        return staffUrl(storeId) + "/" + mappingId;
    }

    protected static Map<String, Object> mapBody(Pn person, String storeRole) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("membershipId", person.membershipId().toString());
        m.put("storeRole", storeRole);
        return m;
    }

    protected JsonNode mapStaff(Clinic clinic, Object storeId, Pn person, String storeRole) throws Exception {
        return ok(HttpMethod.POST, staffUrl(storeId), clinic.owner(), js(mapBody(person, storeRole)), 201);
    }

    protected JsonNode contextOf(RequestPostProcessor auth) throws Exception {
        return ok(HttpMethod.GET, CONTEXT, auth, null, 200);
    }

    /** The mapping of one person in the staff list of a store (including removed rows), or null. */
    protected JsonNode mappingOf(Clinic clinic, Object storeId, Pn person) throws Exception {
        JsonNode list = ok(HttpMethod.GET, staffUrl(storeId) + "?includeRemoved=true", clinic.owner(), null, 200);
        for (JsonNode item : list.get("items")) {
            if (person.membershipId().toString().equals(item.get("membershipId").asText())) {
                return item;
            }
        }
        return null;
    }

    // ---------------- menu ----------------

    protected List<String> menuOf(RequestPostProcessor auth) throws Exception {
        List<String> menu = new ArrayList<>();
        ok(HttpMethod.GET, ME, auth, null, 200).get("menu").forEach(k -> menu.add(k.asText()));
        return menu;
    }

    // ---------------- SQL ----------------

    protected long pharmacyRows(Clinic clinic, String table) throws SQLException {
        return count(clinic, "select count(*) from pharmacy." + table);
    }

    protected static LocalDate todayIst() {
        return LocalDate.now(IST_ZONE);
    }

    // ---------------- problem bodies ----------------

    protected void assertFieldMessage(JsonNode problem, String field, String message) {
        assertThat(problem.get("fieldErrors")).as("fieldErrors of " + problem).isNotNull();
        boolean found = false;
        for (JsonNode e : problem.get("fieldErrors")) {
            if (field.equals(e.get("field").asText())) {
                found = true;
                assertThat(e.get("message").asText()).as("message of " + field).isEqualTo(message);
            }
        }
        assertThat(found).as("a field error on " + field + " in " + problem.get("fieldErrors")).isTrue();
    }

    // ---------------- audit ----------------

    /** Audit rows of one action for the clinic, oldest first: entity_type, entity_id, before_json, after_json, actor_user_id. */
    protected List<List<String>> auditRowsOf(Clinic clinic, String action) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<List<String>> out = new ArrayList<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement("select entity_type, entity_id::text, before_json::text, "
                    + "after_json::text, actor_user_id::text from platform.audit_event where action = ? order by at, id")) {
                ps.setString(1, action);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<String> line = new ArrayList<>();
                        for (int i = 1; i <= 5; i++) {
                            line.add(rs.getString(i));
                        }
                        out.add(line);
                    }
                }
            }
            return out;
        });
    }

    // ---------------- concurrency ----------------

    /**
     * Sends n requests at the same moment (one thread each, a start latch) and returns "status|body" of each, in order. The
     * rate limiter and the transaction layer are the real ones; only the servlet container is MockMvc.
     */
    protected List<String> atTheSameMoment(HttpMethod method, String path, List<RequestPostProcessor> callers, List<String> bodies)
            throws Exception {
        int n = callers.size();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(n);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int k = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(method, java.net.URI.create(path)).with(callers.get(k))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(bodies.get(k)))
                            .andReturn().getResponse();
                    return response.getStatus() + "|" + response.getContentAsString();
                }));
            }
            ready.await();
            start.countDown();
            List<String> answers = new ArrayList<>();
            for (var f : futures) {
                answers.add(f.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long statusCount(List<String> answers, int status) {
        return answers.stream().filter(a -> a.startsWith(status + "|")).count();
    }

    protected JsonNode bodyOf(String answer) throws Exception {
        return objectMapper.readTree(answer.substring(answer.indexOf('|') + 1));
    }
}
