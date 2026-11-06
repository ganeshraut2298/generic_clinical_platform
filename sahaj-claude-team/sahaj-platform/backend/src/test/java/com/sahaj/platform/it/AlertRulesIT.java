package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SAH-18 Alerts and escalation rules on the real {@code sahaj_test} database: read-only starter rules,
 * materialisation on the first write, the seeded marker, validation, suggestions, owner-only writes,
 * audit, row level security and the concurrent first write.
 */
@OnTestDatabase
class AlertRulesIT extends SweepSupport {

    private static final String BASE = "/api/v1/opd/alert-rules";
    private static final String AUDIT = "/api/v1/platform/audit-events?limit=100";
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[][] STARTER = {
            {"Vitals", "not done in 10 min", "Alert nurse", "true"},
            {"Case paper print", "bill not paid", "Block print", "true"},
            {"Discount", "above role limit", "Needs owner approval", "true"},
            {"Follow-up", "missed by 2 days", "Send WhatsApp reminder", "true"},
            {"Consultation", "waiting over 30 min", "Alert owner", "false"}};
    private static final String NEW_RULE = "{\"when\":\"Token\",\"cond\":\"waiting over 45 min\",\"then\":\"Alert owner\"}";

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long rules(Clinic c) throws SQLException {
        return count(c, "select count(*) from opd.alert_rule");
    }

    private long states(Clinic c) throws SQLException {
        return count(c, "select count(*) from opd.alert_rule_state");
    }

    private JsonNode view(Clinic c) throws Exception {
        return ok(HttpMethod.GET, BASE, c.owner(), null, 200);
    }

    private void assertStarter(JsonNode rules, boolean withIds) {
        assertThat(rules.size()).isGreaterThanOrEqualTo(5);
        for (int i = 0; i < 5; i++) {
            JsonNode r = rules.get(i);
            assertThat(r.get("when").asText()).isEqualTo(STARTER[i][0]);
            assertThat(r.get("cond").asText()).isEqualTo(STARTER[i][1]);
            assertThat(r.get("then").asText()).isEqualTo(STARTER[i][2]);
            assertThat(r.get("enabled").asBoolean()).isEqualTo(Boolean.parseBoolean(STARTER[i][3]));
            assertThat(r.get("id") == null || r.get("id").isNull()).isEqualTo(!withIds);
        }
    }

    private UUID idOf(JsonNode view, String when, String cond) {
        for (JsonNode r : view.get("rules")) {
            if (r.get("when").asText().equals(when) && r.get("cond").asText().equals(cond)) {
                return UUID.fromString(r.get("id").asText());
            }
        }
        throw new AssertionError("rule not found " + when + "/" + cond);
    }

    private List<JsonNode> auditOf(Clinic c, String action) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode e : ok(HttpMethod.GET, AUDIT, c.owner(), null, 200)) {
            if (action.equals(e.get("action").asText())) {
                found.add(e);
            }
        }
        return found;
    }

    private static boolean isNull(JsonNode n) {
        return n == null || n.isNull();
    }

    // ---------------- Read (AC1, AC2, AC5, AC7) ----------------

    @Test
    void freshTenantSeesStarterRulesSuggestionsAndOptionsAndGetWritesNothing() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = view(clinic);
        assertThat(v.get("rules")).hasSize(5);
        assertStarter(v.get("rules"), false);
        assertThat(v.get("suggestions")).hasSize(5);
        for (int i = 0; i < 5; i++) {
            JsonNode s = v.get("suggestions").get(i);
            assertThat(s.get("index").asInt()).isEqualTo(i);
            assertThat(s.get("why").asText()).isNotBlank();
        }
        assertThat(v.get("options").get("when")).hasSize(7);
        assertThat(v.get("options").get("cond")).hasSize(7);
        assertThat(v.get("options").get("then")).hasSize(7);
        view(clinic);
        assertThat(rules(clinic)).isZero();
        assertThat(states(clinic)).isZero();
    }

    @Test
    void allEightClinicRolesCanRead() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ALL_ROLES) {
            JsonNode v = ok(HttpMethod.GET, BASE, clinic.as(role), null, 200);
            assertThat(v.get("rules")).hasSize(5);
        }
        assertThat(rules(clinic)).isZero();
        assertThat(states(clinic)).isZero();
    }

    // ---------------- First write, starter (AC2, AC3) ----------------

    @Test
    void firstAddMaterialisesStarterPlusNewRuleInOrder() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE, clinic.owner(), NEW_RULE, 200);
        assertThat(v.get("rules")).hasSize(6);
        assertStarter(v.get("rules"), true);
        JsonNode added = v.get("rules").get(5);
        assertThat(added.get("when").asText()).isEqualTo("Token");
        assertThat(added.get("enabled").asBoolean()).isTrue();
        assertThat(isNull(added.get("id"))).isFalse();
        assertThat(rules(clinic)).isEqualTo(6);
        assertThat(states(clinic)).isEqualTo(1);
        JsonNode read = view(clinic);
        assertThat(read.get("rules")).hasSize(6);
        assertStarter(read.get("rules"), true);
    }

    @Test
    void starterEndpointSeedsOnceAndIsIdempotent() throws Exception {
        Clinic clinic = newClinic();
        JsonNode first = ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200);
        assertThat(first.get("rules")).hasSize(5);
        assertStarter(first.get("rules"), true);
        assertThat(rules(clinic)).isEqualTo(5);
        assertThat(states(clinic)).isEqualTo(1);
        JsonNode second = ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200);
        assertThat(second.get("rules")).hasSize(5);
        assertThat(rules(clinic)).isEqualTo(5);
        assertThat(states(clinic)).isEqualTo(1);
    }

    @Test
    void afterDeletingEveryRuleStarterRulesDoNotComeBack() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200);
        for (JsonNode r : v.get("rules")) {
            v = ok(HttpMethod.DELETE, BASE + "/" + r.get("id").asText(), clinic.owner(), null, 200);
        }
        assertThat(v.get("rules")).isEmpty();
        assertThat(view(clinic).get("rules")).isEmpty();
        assertThat(ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200).get("rules")).isEmpty();
        assertThat(rules(clinic)).isZero();
        assertThat(states(clinic)).isEqualTo(1);
    }

    // ---------------- Validation (AC3) ----------------

    @Test
    void unknownOrMissingFieldsAreRefusedWithFieldErrorsAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic();
        String[][] bad = {
                {"{\"when\":\"Nope\",\"cond\":\"bill not paid\",\"then\":\"Alert owner\"}", "when"},
                {"{\"when\":\"Token\",\"cond\":\"Nope\",\"then\":\"Alert owner\"}", "cond"},
                {"{\"when\":\"Token\",\"cond\":\"bill not paid\",\"then\":\"Nope\"}", "then"},
                {"{\"cond\":\"bill not paid\",\"then\":\"Alert owner\"}", "when"},
                {"{\"when\":\"Token\",\"then\":\"Alert owner\"}", "cond"},
                {"{\"when\":\"Token\",\"cond\":\"bill not paid\"}", "then"},
                {"{\"when\":\"Token\",\"cond\":\"bill not paid\",\"then\":\"Charge full fee, tell front desk\"}", "then"},
                {"{}", "when"}};
        for (String[] b : bad) {
            JsonNode p = problem(call(HttpMethod.POST, BASE, clinic.owner(), b[0]), 400);
            assertThat(p.get("fieldErrors")).as(b[0]).isNotNull();
            assertThat(p.get("fieldErrors").get(0).get("field").asText()).as(b[0]).isEqualTo(b[1]);
        }
        assertThat(rules(clinic)).isZero();
        assertThat(states(clinic)).isZero();
    }

    @Test
    void duplicateRuleGets409AndNoSecondRow() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, BASE, clinic.owner(), NEW_RULE, 200);
        JsonNode p = problem(call(HttpMethod.POST, BASE, clinic.owner(), NEW_RULE), 409);
        assertThat(p.get("detail").asText()).isEqualTo("That rule already exists.");
        problem(call(HttpMethod.POST, BASE, clinic.owner(),
                "{\"when\":\"Vitals\",\"cond\":\"not done in 10 min\",\"then\":\"Alert nurse\"}"), 409);
        assertThat(rules(clinic)).isEqualTo(6);
    }

    // ---------------- Suggestions (AC5) ----------------

    @Test
    void suggestionAddWorksHidesItAndRefusesAgain() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE + "/suggestions/3", clinic.owner(), null, 200);
        assertThat(v.get("rules")).hasSize(6);
        JsonNode added = v.get("rules").get(5);
        assertThat(added.get("when").asText()).isEqualTo("Billing");
        assertThat(added.get("cond").asText()).isEqualTo("unpaid at day closing");
        assertThat(added.get("enabled").asBoolean()).isTrue();
        assertThat(v.get("suggestions")).hasSize(4);
        JsonNode read = view(clinic);
        assertThat(read.get("suggestions")).hasSize(4);
        for (JsonNode s : read.get("suggestions")) {
            assertThat(s.get("index").asInt()).isNotEqualTo(3);
        }
        problem(call(HttpMethod.POST, BASE + "/suggestions/3", clinic.owner(), null), 409);
        assertThat(rules(clinic)).isEqualTo(6);
    }

    @Test
    void suggestionsOutsideTheBuilderListsAreAllowedAndAddingAllHidesThePanelContent() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = null;
        for (int i = 0; i < 5; i++) {
            v = ok(HttpMethod.POST, BASE + "/suggestions/" + i, clinic.owner(), null, 200);
        }
        assertThat(v.get("suggestions")).isEmpty();
        assertThat(v.get("rules")).hasSize(10);
        JsonNode last = v.get("rules").get(9);
        assertThat(last.get("then").asText()).isEqualTo("Charge full fee, tell front desk");
        assertThat(last.get("cond").asText()).isEqualTo("after the free window");
        assertThat(view(clinic).get("suggestions")).isEmpty();
    }

    @Test
    void suggestionHiddenWhenSameWhenAndCondAlreadyAddedThroughBuilder() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE, clinic.owner(),
                "{\"when\":\"Consultation\",\"cond\":\"waiting over 45 min\",\"then\":\"Alert nurse\"}", 200);
        assertThat(v.get("suggestions")).hasSize(4);
        problem(call(HttpMethod.POST, BASE + "/suggestions/2", clinic.owner(), null), 409);
    }

    @Test
    void unknownSuggestionIndexIs404() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, BASE + "/suggestions/5", clinic.owner(), null), 404);
        problem(call(HttpMethod.POST, BASE + "/suggestions/-1", clinic.owner(), null), 404);
        problem(call(HttpMethod.POST, BASE + "/suggestions/abc", clinic.owner(), null), 400);
        assertThat(rules(clinic)).isZero();
    }

    // ---------------- Toggle and delete (AC4) ----------------

    @Test
    void toggleOffOnPersistsAndMissingEnabledIs400() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200);
        UUID id = idOf(v, "Vitals", "not done in 10 min");
        ok(HttpMethod.PUT, BASE + "/" + id, clinic.owner(), "{\"enabled\":false}", 200);
        assertThat(count(clinic, "select count(*) from opd.alert_rule where id = ? and not enabled", id)).isEqualTo(1);
        assertThat(view(clinic).get("rules").get(0).get("enabled").asBoolean()).isFalse();
        ok(HttpMethod.PUT, BASE + "/" + id, clinic.owner(), "{\"enabled\":true}", 200);
        assertThat(view(clinic).get("rules").get(0).get("enabled").asBoolean()).isTrue();
        problem(call(HttpMethod.PUT, BASE + "/" + id, clinic.owner(), "{}"), 400);
        assertThat(view(clinic).get("rules").get(0).get("enabled").asBoolean()).isTrue();
    }

    @Test
    void unknownIdIs404OnPutAndDelete() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, BASE + "/" + UUID.randomUUID(), clinic.owner(), "{\"enabled\":true}"), 404);
        problem(call(HttpMethod.DELETE, BASE + "/" + UUID.randomUUID(), clinic.owner(), null), 404);
    }

    @Test
    void deleteRemovesTheRuleAndASecondDeleteIs404() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE, clinic.owner(), NEW_RULE, 200);
        UUID id = idOf(v, "Token", "waiting over 45 min");
        JsonNode after = ok(HttpMethod.DELETE, BASE + "/" + id, clinic.owner(), null, 200);
        assertThat(after.get("rules")).hasSize(5);
        assertThat(rules(clinic)).isEqualTo(5);
        problem(call(HttpMethod.DELETE, BASE + "/" + id, clinic.owner(), null), 404);
        problem(call(HttpMethod.PUT, BASE + "/" + id, clinic.owner(), "{\"enabled\":true}"), 404);
    }

    @Test
    void anotherTenantsRuleIdIs404OnPutAndDeleteAndStaysUntouched() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID idA = idOf(ok(HttpMethod.POST, BASE + "/starter", a.owner(), null, 200), "Vitals", "not done in 10 min");
        ok(HttpMethod.POST, BASE + "/starter", b.owner(), null, 200);
        problem(call(HttpMethod.PUT, BASE + "/" + idA, b.owner(), "{\"enabled\":false}"), 404);
        problem(call(HttpMethod.DELETE, BASE + "/" + idA, b.owner(), null), 404);
        assertThat(count(a, "select count(*) from opd.alert_rule where id = ? and enabled", idA)).isEqualTo(1);
        assertThat(rules(a)).isEqualTo(5);
    }

    @Test
    void malformedUuidIs400() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.PUT, BASE + "/not-a-uuid", clinic.owner(), "{\"enabled\":true}"), 400);
        problem(call(HttpMethod.DELETE, BASE + "/not-a-uuid", clinic.owner(), null), 400);
    }

    // ---------------- Roles (AC7) ----------------

    @Test
    void everyNonOwnerRoleIsRefusedOnEveryWriteAndNothingIsChanged() throws Exception {
        Clinic clinic = newClinic();
        UUID id = idOf(ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200), "Vitals", "not done in 10 min");
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, BASE, clinic.as(role), NEW_RULE), 403);
            problem(call(HttpMethod.POST, BASE + "/starter", clinic.as(role), null), 403);
            problem(call(HttpMethod.POST, BASE + "/suggestions/0", clinic.as(role), null), 403);
            problem(call(HttpMethod.PUT, BASE + "/" + id, clinic.as(role), "{\"enabled\":false}"), 403);
            problem(call(HttpMethod.DELETE, BASE + "/" + id, clinic.as(role), null), 403);
        }
        assertThat(rules(clinic)).isEqualTo(5);
        assertThat(count(clinic, "select count(*) from opd.alert_rule where id = ? and enabled", id)).isEqualTo(1);
    }

    @Test
    void nonOwnerWritesOnAFreshTenantSaveNothing() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, BASE + "/starter", clinic.as(role), null), 403);
            problem(call(HttpMethod.POST, BASE, clinic.as(role), NEW_RULE), 403);
        }
        assertThat(rules(clinic)).isZero();
        assertThat(states(clinic)).isZero();
    }

    @Test
    void withoutLoginIs401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    // ---------------- Audit (AC6) ----------------

    @Test
    void addToggleAndDeleteAreAuditedWithBeforeAndAfterAndOnlyRuleTexts() throws Exception {
        Clinic clinic = newClinic();
        JsonNode v = ok(HttpMethod.POST, BASE, clinic.owner(), NEW_RULE, 200);
        UUID id = idOf(v, "Token", "waiting over 45 min");
        ok(HttpMethod.PUT, BASE + "/" + id, clinic.owner(), "{\"enabled\":false}", 200);
        ok(HttpMethod.DELETE, BASE + "/" + id, clinic.owner(), null, 200);

        List<JsonNode> addedEvents = auditOf(clinic, "ALERT_RULE_ADDED");
        assertThat(addedEvents).hasSize(6); // 5 starter + 1 new
        JsonNode added = null;
        for (JsonNode e : addedEvents) {
            if ("Token".equals(e.get("after").get("when").asText())) {
                added = e;
            }
        }
        assertThat(added).isNotNull();
        assertThat(isNull(added.get("before"))).isTrue();
        assertThat(added.get("after").get("cond").asText()).isEqualTo("waiting over 45 min");
        assertThat(added.get("after").get("enabled").asBoolean()).isTrue();

        List<JsonNode> toggled = auditOf(clinic, "ALERT_RULE_TOGGLED");
        assertThat(toggled).hasSize(1);
        assertThat(toggled.get(0).get("before").get("enabled").asBoolean()).isTrue();
        assertThat(toggled.get(0).get("after").get("enabled").asBoolean()).isFalse();
        assertThat(toggled.get(0).get("before").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("when", "cond", "then", "enabled");

        List<JsonNode> deleted = auditOf(clinic, "ALERT_RULE_DELETED");
        assertThat(deleted).hasSize(1);
        assertThat(deleted.get(0).get("before").get("cond").asText()).isEqualTo("waiting over 45 min");
        assertThat(isNull(deleted.get(0).get("after"))).isTrue();

        assertThat(count(clinic, "select count(*) from platform.audit_event where entity_type = 'ALERT_RULE'"))
                .isEqualTo(8);
    }

    @Test
    void refusedWritesAreNotAudited() throws Exception {
        Clinic clinic = newClinic();
        problem(call(HttpMethod.POST, BASE, clinic.as("DOCTOR"), NEW_RULE), 403);
        problem(call(HttpMethod.POST, BASE, clinic.owner(), "{\"when\":\"x\",\"cond\":\"y\",\"then\":\"z\"}"), 400);
        assertThat(count(clinic, "select count(*) from platform.audit_event where entity_type = 'ALERT_RULE'")).isZero();
    }

    // ---------------- Row level security ----------------

    @Test
    void tenantBSeesOnlyItsOwnRulesAndState() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ok(HttpMethod.POST, BASE, a.owner(), NEW_RULE, 200);
        JsonNode vb = view(b);
        assertThat(vb.get("rules")).hasSize(5);
        assertStarter(vb.get("rules"), false);
        assertThat(rules(b)).isZero();
        assertThat(states(b)).isZero();
        assertThat(rules(a)).isEqualTo(6);
        assertThat(states(a)).isEqualTo(1);

        assertThat(count(b, "select count(*) from opd.alert_rule where tenant_id = ?", a.tenantId())).isZero();
        assertThat(count(b, "select count(*) from opd.alert_rule_state where tenant_id = ?", a.tenantId())).isZero();
        assertThat((long) db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from opd.alert_rule"))).isZero();
        assertThat((long) db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from opd.alert_rule_state"))).isZero();

        ok(HttpMethod.POST, BASE + "/starter", b.owner(), null, 200);
        assertThat(rules(b)).isEqualTo(5);
        assertThat(states(b)).isEqualTo(1);
        assertThat(rules(a)).isEqualTo(6);
    }

    @Test
    void appRoleCannotInsertARuleForAnotherTenant() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into opd.alert_rule (tenant_id, when_step, cond, then_action, created_by) "
                    + "values (?, 'Token', 'bill not paid', 'Alert owner', ?)", a.tenantId(), a.ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(rules(a)).isZero();
    }

    @Test
    void appRoleCannotUpdateOrDeleteTheSeededMarker() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, BASE + "/starter", clinic.owner(), null, 200);
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "update opd.alert_rule_state set seeded_at = now()");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "delete from opd.alert_rule_state");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(states(clinic)).isEqualTo(1);
    }

    // ---------------- Concurrency ----------------

    @Test
    void twoSimultaneousFirstWritesSeedTheStarterRulesExactlyOnce() throws Exception {
        Clinic clinic = newClinic();
        String[] bodies = {NEW_RULE, "{\"when\":\"Billing\",\"cond\":\"bill not paid\",\"then\":\"Alert nurse\"}"};
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (String body : bodies) {
            futures.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(BASE).with(clinic.owner()).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            codes.add(f.get().getResponse().getStatus());
        }
        pool.shutdown();
        assertThat(codes).as("no 500").allMatch(c -> c == 200 || c == 409);
        long okCount = codes.stream().filter(c -> c == 200).count();
        assertThat(okCount).isGreaterThanOrEqualTo(1);
        assertThat(states(clinic)).isEqualTo(1);
        assertThat(rules(clinic)).isEqualTo(5 + okCount);
        assertThat(count(clinic, "select count(*) from opd.alert_rule where when_step = 'Vitals'")).isEqualTo(1);
        assertThat(auditOf(clinic, "ALERT_RULE_ADDED")).hasSize((int) (5 + okCount));
    }

    @Test
    void twoSimultaneousStarterCallsSeedOnce() throws Exception {
        Clinic clinic = newClinic();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(BASE + "/starter").with(clinic.owner())).andReturn();
            }));
        }
        go.countDown();
        for (Future<MvcResult> f : futures) {
            assertThat(f.get().getResponse().getStatus()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(states(clinic)).isEqualTo(1);
        assertThat(rules(clinic)).isEqualTo(5);
    }
}
