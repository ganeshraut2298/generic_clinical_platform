package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78 package C, the cross-cutting checks: the module switch Off answers 403 module-off on all 18 operations before anything
 * else, a person who is not the owner gets 403 on every write, the gate is checked before the body is read, and row level
 * security hides one clinic's rows from another on every new table. Test plan: docs/test-plans/SAH-78.md section 24 (T78-C-*).
 */
@OnTestDatabase
class PharmacyPackageCCrossIT extends Sah78CSupport {

    private record Call(HttpMethod method, String path, String body) {
    }

    /** The 18 operations of package C (docs/api/pharmacy.yaml 0.2.0), well-formed requests, ids that exist nowhere. */
    private static List<Call> eighteenOperations() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<Call> calls = new ArrayList<>();
        calls.add(new Call(HttpMethod.GET, VENDORS, null));
        calls.add(new Call(HttpMethod.POST, VENDORS, jsonOf(vendorBody("Module Off Vendor (fake)"))));
        calls.add(new Call(HttpMethod.GET, VENDORS + "/" + id, null));
        calls.add(new Call(HttpMethod.PUT, VENDORS + "/" + id, "{\"version\":0}"));
        calls.add(new Call(HttpMethod.POST, VENDORS + "/" + id + "/switch-off", null));
        calls.add(new Call(HttpMethod.GET, RULES_URL, null));
        calls.add(new Call(HttpMethod.PUT, RULES_URL, jsonOf(rulesBody(0, null, Map.of()))));
        calls.add(new Call(HttpMethod.POST, PRESET_URL, jsonOf(presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", 1, null, false))))));
        calls.add(new Call(HttpMethod.GET, VERSIONS_URL, null));
        calls.add(new Call(HttpMethod.GET, VERSIONS_URL + "/1", null));
        calls.add(new Call(HttpMethod.GET, SERIES_URL, null));
        calls.add(new Call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", jsonOf(seriesBody(0, "PO-", false, 4, false))));
        calls.add(new Call(HttpMethod.GET, REASON_LISTS, null));
        calls.add(new Call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", "{\"label\":\"Packaging torn\"}"));
        calls.add(new Call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.expired/switch-off", null));
        calls.add(new Call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.expired/switch-on", null));
        calls.add(new Call(HttpMethod.GET, TEMPLATES, null));
        calls.add(new Call(HttpMethod.PUT, TEMPLATES + "/INVOICE", jsonOf(templateBody(0, List.of("x"), null, "A4"))));
        assertThat(other).isNotNull();
        return calls;
    }

    private static String jsonOf(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void thereAreEighteenOperationsAndTenOfThemWrite() {
        List<Call> calls = eighteenOperations();
        assertThat(calls).hasSize(18);
        assertThat(calls.stream().filter(c -> c.method() != HttpMethod.GET).count()).isEqualTo(10);
    }

    @Test
    void withTheModuleOffEveryOneOfTheEighteenOperationsAnswers403ModuleOffEvenForTheOwnerAndWritesNothing() throws Exception {
        Clinic clinic = newClinic(); // no module row: Off by default
        Map<String, RequestPostProcessor> callers = new LinkedHashMap<>();
        callers.put("OWNER", clinic.owner());
        callers.put("PHARMACIST", crew(clinic, "PHARMACIST").auth());
        callers.put("STORE_STAFF", crew(clinic, "STORE_STAFF").auth());
        callers.put("NURSE", crew(clinic, "NURSE").auth());
        for (Call c : eighteenOperations()) {
            for (var who : callers.entrySet()) {
                JsonNode p = problem(call(c.method(), c.path(), who.getValue(), c.body()), 403);
                assertType(p, "module-off");
                assertThat(p.get("detail").asText()).as(who.getKey() + " " + c.method() + " " + c.path()).isEqualTo(MODULE_OFF_TEXT);
            }
        }
        for (String table : new String[] {"unit", "vendor", "vendor_store", "approval_rule_version", "approval_rule", "approval_rule_level",
            "approval_rule_set", "document_series", "document_counter", "reason", "reason_default_state", "print_template_setting"}) {
            assertThat(pharmacyRows(clinic, table)).as(table).isZero();
        }
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'")).isZero();
    }

    @Test
    void theModuleSwitchIsPerClinicSoTheOtherClinicStillWorksWhileThisOneIsOff() throws Exception {
        Masters on = mastersClinic();
        Clinic off = newClinic();
        assertType(problem(call(HttpMethod.GET, VENDORS, off.owner(), null), 403), "module-off");
        assertThat(ok(HttpMethod.GET, VENDORS, on.clinic().owner(), null, 200).get("total").asInt()).isZero();
        // switching the module off after use: the same owner is refused again, and the data stays
        createVendor(on.clinic(), vendorBody("Before Off (fake)"));
        pharmacyOn(on.clinic(), false);
        assertType(problem(call(HttpMethod.GET, VENDORS, on.clinic().owner(), null), 403), "module-off");
        pharmacyOn(on.clinic(), true);
        assertThat(ok(HttpMethod.GET, VENDORS, on.clinic().owner(), null, 200).get("total").asInt()).isOne();
    }

    /** Operations that read a JSON body (read after the gate). */
    private static List<Call> bodyOperations() {
        UUID id = UUID.randomUUID();
        List<Call> calls = new ArrayList<>();
        calls.add(new Call(HttpMethod.POST, VENDORS, null));
        calls.add(new Call(HttpMethod.PUT, VENDORS + "/" + id, null));
        calls.add(new Call(HttpMethod.PUT, RULES_URL, null));
        calls.add(new Call(HttpMethod.POST, PRESET_URL, null));
        calls.add(new Call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", null));
        calls.add(new Call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", null));
        calls.add(new Call(HttpMethod.PUT, TEMPLATES + "/INVOICE", null));
        return calls;
    }

    private static final String[] MALFORMED = {"{\"name\":", "not json at all", "[]", ""};

    @Test
    void aMalformedBodyIsNeverReadBeforeTheGateModuleOffAndNonOwnerAnswer403NotA400() throws Exception {
        // module off
        Clinic off = newClinic();
        for (Call c : bodyOperations()) {
            for (String body : MALFORMED) {
                JsonNode p = problem(call(c.method(), c.path(), off.owner(), body), 403);
                assertType(p, "module-off");
            }
        }
        // module on, a person who is not the owner
        Masters w = mastersClinic();
        var callers = nonOwners(w);
        int checked = 0;
        for (Call c : bodyOperations()) {
            for (String body : MALFORMED) {
                for (var who : callers.entrySet()) {
                    JsonNode p = problem(call(c.method(), c.path(), who.getValue(), body), 403);
                    assertType(p, "forbidden");
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(7 * 4 * 6);
        // the owner, once past the gate, gets the 400 (the vendor id of the update does not exist, so the id is read before the body)
        for (Call c : bodyOperations()) {
            if (c.path().startsWith(VENDORS + "/")) {
                continue;
            }
            for (String body : MALFORMED) {
                assertType(problem(call(c.method(), c.path(), w.clinic().owner(), body), 400), "malformed-request");
            }
        }
        // a path that is not valid is read after the gate too (no module: 403 first)
        assertType(problem(call(HttpMethod.PUT, VENDORS + "/not-a-uuid", off.owner(), "x"), 403), "module-off");
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/NOPE", off.owner(), "x"), 403), "module-off");
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/NOPE", callers.get("NURSE"), "x"), 403), "forbidden");
    }

    @Test
    void everyWriteOfThePackageByEverySixKindsOfNonOwnerIs403AndNoRowOrAuditIsWritten() throws Exception {
        Masters w = mastersClinic();
        var callers = nonOwners(w);
        int writes = 0;
        for (Call c : eighteenOperations()) {
            if (c.method() == HttpMethod.GET) {
                continue;
            }
            String path = c.path().replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", UUID.randomUUID().toString());
            for (var who : callers.entrySet()) {
                JsonNode p = problem(call(c.method(), path, who.getValue(), c.body()), 403);
                assertType(p, "forbidden");
                writes++;
            }
        }
        assertThat(writes).isEqualTo(10 * 6);
        for (String table : new String[] {"vendor", "vendor_store", "approval_rule_version", "approval_rule", "approval_rule_level",
            "approval_rule_set", "document_series", "document_counter", "reason", "reason_default_state", "print_template_setting"}) {
            assertThat(pharmacyRows(w.clinic(), table)).as(table).isZero();
        }
        assertThat(count(w.clinic(), "select count(*) from platform.audit_event where action like 'PHARMACY\\_%' and action not like 'PHARMACY\\_STORE%' "
                + "and action not like 'PHARMACY\\_STAFF%' and action not like 'PHARMACY\\_UNIT%'")).isZero();
    }

    @Test
    void anUnauthenticatedCallIs401OnEveryOperation() throws Exception {
        for (Call c : eighteenOperations()) {
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .request(c.method(), java.net.URI.create(c.path())).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(c.body() == null ? "" : c.body())).andReturn().getResponse();
            assertThat(response.getStatus()).as(c.method() + " " + c.path()).isEqualTo(401);
        }
    }

    // ---------------- row level security on the new tables ----------------

    private static final String[] TENANT_TABLES = {"vendor", "vendor_store", "approval_rule_version", "approval_rule", "approval_rule_level",
        "approval_rule_set", "document_series", "document_counter", "reason", "reason_default_state", "print_template_setting"};

    private void fillEverything(Masters w) throws Exception {
        JsonNode v = createVendor(w.clinic(), vendorBody("Wall Vendor " + UUID.randomUUID().toString().substring(0, 4) + " (fake)"));
        assertThat(idOf(v)).isNotBlank();
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null))))), 200);
        ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "ZP-", false, 4, false)), 200);
        issueNumber(w.clinic(), "PURCHASE_ORDER");
        ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"Own damage\"}", 201);
        ok(HttpMethod.POST, REASON_LISTS + "/transfer/reasons/transfer.store-closure/switch-off", w.clinic().owner(), null, 200);
        ok(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of("Wall"), null, "A4")), 200);
    }

    @Test
    void everyNewTenantTableShowsOneClinicsRowsToThatClinicOnlyAndTheOtherCannotChangeOrDeleteThem() throws Exception {
        Masters mine = mastersClinic();
        Masters theirs = mastersClinic();
        fillEverything(mine);
        for (String table : TENANT_TABLES) {
            assertThat(count(mine.clinic(), "select count(*) from pharmacy." + table)).as("mine: " + table).isPositive();
            assertThat(count(theirs.clinic(), "select count(*) from pharmacy." + table)).as("theirs: " + table).isZero();
        }
        // a delete is refused to the application (no grant), an update by the other clinic touches no row
        for (String table : TENANT_TABLES) {
            assertThatThrownBy(() -> db().inTenant(theirs.clinic().tenantId(), c -> {
                DbAccess.execute(c, "delete from pharmacy." + table);
                return null;
            })).as("delete from " + table).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        for (String update : new String[] {"update pharmacy.vendor set name = name", "update pharmacy.document_series set prefix = prefix",
            "update pharmacy.reason set label = label", "update pharmacy.print_template_setting set paper_size = paper_size"}) {
            long touched = db().inTenant(theirs.clinic().tenantId(), c -> {
                try (var st = c.createStatement()) {
                    return (long) st.executeUpdate(update);
                }
            });
            assertThat(touched).as(update).isZero();
        }
        assertThat(count(mine.clinic(), "select count(*) from pharmacy.vendor")).isOne();
    }

    @Test
    void aRowCannotBeInsertedForAnotherTenantAndWithNoTenantBoundNothingIsVisible() throws Exception {
        Masters mine = mastersClinic();
        Masters theirs = mastersClinic();
        fillEverything(mine);
        String unit = values(mine.clinic(), "select id::text from pharmacy.unit").get(0);
        // the bound tenant is theirs; the row names my tenant and my unit
        assertThatThrownBy(() -> db().inTenant(theirs.clinic().tenantId(), c -> {
            DbAccess.execute(c, "insert into pharmacy.vendor (tenant_id, unit_id, name, licence_required, status, created_by) "
                    + "values (?::uuid, ?::uuid, 'Smuggled (fake)', false, 'ACTIVE', ?::uuid)", mine.clinic().tenantId().toString(), unit,
                    theirs.clinic().ownerUserId().toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isIn("42501", "23503"));
        assertThatThrownBy(() -> db().inTenant(theirs.clinic().tenantId(), c -> {
            DbAccess.execute(c, "insert into pharmacy.document_series (tenant_id, unit_id, doc_type, prefix, financial_year_part, sequence_length, "
                    + "restarts_each_fy, created_by) values (?::uuid, ?::uuid, 'GRN', 'SM-', false, 4, false, ?::uuid)",
                    mine.clinic().tenantId().toString(), unit, theirs.clinic().ownerUserId().toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isIn("42501", "23503"));
        // no tenant bound: every table is empty to the application
        db().asApp(c -> {
            for (String table : TENANT_TABLES) {
                assertThat(DbAccess.count(c, "select count(*) from pharmacy." + table)).as(table + " with no tenant bound").isZero();
            }
            return null;
        });
        assertThat(count(mine.clinic(), "select count(*) from pharmacy.vendor")).isOne();
    }
}
