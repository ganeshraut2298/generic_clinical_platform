package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-1 on the real database: Pharmacy and Inventory switched Off (the default for every clinic, DEC-061 Answer 2). Every
 * pharmacy endpoint answers 403 module-off, even for the owner, and nothing existing changes: the menu of every existing
 * role, the role list of Team and access, the module list, and the JSON of the existing endpoints. Test plan:
 * docs/test-plans/SAH-78.md (T78-1-02, T78-1-03).
 */
@OnTestDatabase
class PharmacyModuleOffIT extends Sah78Support {

    private record Call(HttpMethod method, String path, String body) {
    }

    /** Every operation of WP-A and WP-B (docs/api/pharmacy.yaml), with a well-formed request and ids that exist nowhere. */
    private static List<Call> everyEndpoint() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<Call> calls = new ArrayList<>();
        calls.add(new Call(HttpMethod.GET, CONTEXT, null));
        calls.add(new Call(HttpMethod.PUT, WORKING_STORE, "{\"storeId\":\"" + id + "\"}"));
        calls.add(new Call(HttpMethod.GET, SUMMARY, null));
        calls.add(new Call(HttpMethod.GET, UNIT, null));
        calls.add(new Call(HttpMethod.PUT, UNIT, "{\"version\":0,\"integrationMode\":\"INTEGRATED\"}"));
        calls.add(new Call(HttpMethod.GET, STORES, null));
        calls.add(new Call(HttpMethod.POST, STORES, "{\"name\":\"Central Store\",\"code\":\"CS\",\"type\":\"CENTRAL\"}"));
        calls.add(new Call(HttpMethod.GET, STORES + "/" + id, null));
        calls.add(new Call(HttpMethod.PUT, STORES + "/" + id,
                "{\"version\":0,\"name\":\"X\",\"code\":\"X\",\"type\":\"PHARMACY\",\"saleAllowed\":true,\"consumptionAllowed\":false}"));
        calls.add(new Call(HttpMethod.POST, STORES + "/" + id + "/switch-off", null));
        calls.add(new Call(HttpMethod.GET, CANDIDATES + "?storeId=" + id, null));
        calls.add(new Call(HttpMethod.GET, staffUrl(id), null));
        calls.add(new Call(HttpMethod.POST, staffUrl(id), "{\"membershipId\":\"" + other + "\",\"storeRole\":\"STORE_USER\"}"));
        calls.add(new Call(HttpMethod.PUT, staffUrl(id, other), "{\"version\":0,\"storeRole\":\"STORE_USER\"}"));
        calls.add(new Call(HttpMethod.POST, staffUrl(id, other) + "/remove", null));
        // WP-B (78-5, 78-6, 78-7): 18 more operations, ids that exist nowhere
        String products = PH + "/products";
        calls.add(new Call(HttpMethod.GET, products + "?q=ceft", null));
        calls.add(new Call(HttpMethod.POST, products, "{\"clientKey\":\"" + other + "\"}"));
        calls.add(new Call(HttpMethod.GET, products + "/duplicates?salt=a&strength=1&form=b", null));
        calls.add(new Call(HttpMethod.GET, PH + "/product-options", null));
        calls.add(new Call(HttpMethod.POST, PH + "/catalogue-products/" + id + "/adopt", null));
        calls.add(new Call(HttpMethod.GET, products + "/" + id, null));
        calls.add(new Call(HttpMethod.PUT, products + "/" + id, "{\"version\":0}"));
        calls.add(new Call(HttpMethod.PUT, products + "/" + id + "/local-settings", "{\"version\":0}"));
        calls.add(new Call(HttpMethod.GET, PH + "/opening-stock/template", null));
        calls.add(new Call(HttpMethod.GET, PH + "/opening-stock-imports", null));
        String imports = STORES + "/" + id + "/opening-stock-imports";
        calls.add(new Call(HttpMethod.GET, imports, null));
        calls.add(new Call(HttpMethod.POST, imports, "{\"clientKey\":\"" + other + "\",\"columns\":[\"store code\"],\"rowCount\":1}"));
        calls.add(new Call(HttpMethod.GET, imports + "/" + other, null));
        calls.add(new Call(HttpMethod.GET, imports + "/" + other + "/lines", null));
        calls.add(new Call(HttpMethod.POST, imports + "/" + other + "/lines", "{\"rows\":[]}"));
        calls.add(new Call(HttpMethod.POST, imports + "/" + other + "/submit", null));
        calls.add(new Call(HttpMethod.POST, imports + "/" + other + "/approve", null));
        calls.add(new Call(HttpMethod.POST, imports + "/" + other + "/reject", "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}"));
        return calls;
    }

    @Test
    void everyPharmacyEndpointAnswers403ModuleOffEvenForTheOwnerAndWritesNothing() throws Exception {
        Clinic clinic = newClinic(); // no module_entitlement row at all: the default is Off
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn storeStaff = crew(clinic, "STORE_STAFF");
        Map<String, RequestPostProcessor> callers = new LinkedHashMap<>();
        callers.put("OWNER", clinic.owner());
        callers.put("PHARMACIST", pharmacist.auth());
        callers.put("STORE_STAFF", storeStaff.auth());
        callers.put("NURSE", crew(clinic, "NURSE").auth());
        for (Call c : everyEndpoint()) {
            for (var who : callers.entrySet()) {
                JsonNode p = problem(call(c.method(), c.path(), who.getValue(), c.body()), 403);
                assertType(p, "module-off");
                assertThat(p.get("detail").asText()).as(who.getKey() + " " + c.method() + " " + c.path())
                        .isEqualTo(MODULE_OFF_TEXT);
            }
        }
        assertThat(pharmacyRows(clinic, "unit")).as("no unit row is made by a refused call").isZero();
        assertThat(pharmacyRows(clinic, "store")).isZero();
        assertThat(pharmacyRows(clinic, "store_staff")).isZero();
        assertThat(pharmacyRows(clinic, "staff_preference")).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'")).isZero();
    }

    @Test
    void theOperationListCoversEveryOperationOfWpAAndWpB() {
        assertThat(everyEndpoint()).as("15 operations of WP-A and 18 of WP-B").hasSize(33);
    }

    /** Operations that read a JSON body (every one of them reads it after the gate: module, then access level). */
    private static List<Call> bodyOperations() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<Call> calls = new ArrayList<>();
        calls.add(new Call(HttpMethod.PUT, WORKING_STORE, null));
        calls.add(new Call(HttpMethod.PUT, UNIT, null));
        calls.add(new Call(HttpMethod.POST, STORES, null));
        calls.add(new Call(HttpMethod.PUT, STORES + "/" + id, null));
        calls.add(new Call(HttpMethod.POST, staffUrl(id), null));
        calls.add(new Call(HttpMethod.PUT, staffUrl(id, other), null));
        calls.add(new Call(HttpMethod.POST, PH + "/products", null));
        calls.add(new Call(HttpMethod.PUT, PH + "/products/" + id, null));
        calls.add(new Call(HttpMethod.PUT, PH + "/products/" + id + "/local-settings", null));
        calls.add(new Call(HttpMethod.POST, STORES + "/" + id + "/opening-stock-imports", null));
        calls.add(new Call(HttpMethod.POST, STORES + "/" + id + "/opening-stock-imports/" + other + "/lines", null));
        calls.add(new Call(HttpMethod.POST, STORES + "/" + id + "/opening-stock-imports/" + other + "/reject", null));
        return calls;
    }

    private static final String[] MALFORMED_BODIES = {"{\"name\":", "not json at all", "[]", "\"text\"", "{\"version\":\"x\",\"rows\":7}", ""};

    @Test
    void aMalformedBodySentByACallerWhoseModuleIsOffGets403ModuleOffNotA400() throws Exception {
        // the gate runs before the body is read (contract general rule 2; security review L1)
        Clinic clinic = newClinic(); // module off
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Map<String, RequestPostProcessor> callers = new LinkedHashMap<>();
        callers.put("OWNER", clinic.owner());
        callers.put("PHARMACIST", pharmacist.auth());
        callers.put("NURSE", crew(clinic, "NURSE").auth());
        int checked = 0;
        for (Call c : bodyOperations()) {
            for (String body : MALFORMED_BODIES) {
                for (var who : callers.entrySet()) {
                    JsonNode p = problem(call(c.method(), c.path(), who.getValue(), body), 403);
                    assertType(p, "module-off");
                    assertThat(p.get("detail").asText()).as(who.getKey() + " " + c.method() + " " + c.path() + " body '" + body + "'")
                            .isEqualTo(MODULE_OFF_TEXT);
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(12 * MALFORMED_BODIES.length * 3);
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'")).isZero();
    }

    @Test
    void aMalformedBodySentByAPersonWithoutTheAccessLevelGets403ForbiddenNotA400() throws Exception {
        Clinic clinic = pharmacyClinic(); // module on
        centralAndPharmacy(clinic);
        Map<String, RequestPostProcessor> callers = new LinkedHashMap<>();
        callers.put("NURSE (not mapped)", crew(clinic, "NURSE").auth());
        callers.put("FRONT_DESK", crew(clinic, "FRONT_DESK").auth());
        callers.put("STORE_STAFF (not mapped)", crew(clinic, "STORE_STAFF").auth());
        callers.put("DOCTOR", crew(clinic, "DOCTOR").auth());
        long unitsBefore = pharmacyRows(clinic, "unit");
        long storesBefore = pharmacyRows(clinic, "store");
        long auditBefore = count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'");
        for (Call c : bodyOperations()) {
            for (String body : MALFORMED_BODIES) {
                for (var who : callers.entrySet()) {
                    JsonNode p = problem(call(c.method(), c.path(), who.getValue(), body), 403);
                    assertType(p, "forbidden");
                    assertThat(p.toString()).as(who.getKey() + " " + c.method() + " " + c.path() + " body '" + body + "'")
                            .doesNotContain("validation");
                }
            }
        }
        // a pharmacist is not the owner: every owner-only operation with a malformed body is 403 too (checked on the SETUP_EDIT ones)
        Pn pharmacist = crew(clinic, "PHARMACIST");
        for (Call c : bodyOperations()) {
            if (c.path().equals(WORKING_STORE) || c.path().endsWith("/opening-stock-imports") || c.path().endsWith("/lines")) {
                continue; // PHARMACY_ACCESS operations: a pharmacist passes the access level and is stopped by the store guard instead
            }
            for (String body : MALFORMED_BODIES) {
                assertType(problem(call(c.method(), c.path(), pharmacist.auth(), body), 403), "forbidden");
            }
        }
        assertThat(pharmacyRows(clinic, "unit")).as("nothing was written by a refused call").isEqualTo(unitsBefore);
        assertThat(pharmacyRows(clinic, "store")).isEqualTo(storesBefore);
        assertThat(pharmacyRows(clinic, "store_staff")).isZero();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'")).isEqualTo(auditBefore);
    }

    @Test
    void anExplicitPharmacyOffRowAnswersTheSameAsNoRow() throws Exception {
        Clinic clinic = newClinic();
        pharmacyOn(clinic, true);
        assertThat(ok(HttpMethod.GET, STORES, clinic.owner(), null, 200).get("items")).isEmpty();
        pharmacyOn(clinic, false); // switched on, then off again
        JsonNode p = problem(call(HttpMethod.GET, STORES, clinic.owner(), null), 403);
        assertType(p, "module-off");
        assertThat(p.get("detail").asText()).isEqualTo(MODULE_OFF_TEXT);
    }

    @Test
    void theModuleSwitchIsPerClinicAnotherClinicsSwitchDoesNotOpenMine() throws Exception {
        Clinic off = newClinic();
        Clinic on = pharmacyClinic();
        assertThat(ok(HttpMethod.GET, CONTEXT, on.owner(), null, 200).get("unit")).isNotNull();
        assertType(problem(call(HttpMethod.GET, CONTEXT, off.owner(), null), 403), "module-off");
    }

    @Test
    void aMalformedPathIdWithTheModuleOffAnswersModuleOffFirstAsTheContractOrderSays() throws Exception {
        // docs/api/pharmacy.yaml general rule 2: module off, then access level, then a path id that is not a UUID (400)
        Clinic clinic = newClinic();
        JsonNode p = problem(call(HttpMethod.GET, STORES + "/not-a-uuid", clinic.owner(), null), 403);
        assertType(p, "module-off");
    }

    @Test
    void withTheModuleOffNoExistingRoleSeesAPharmacyMenuEntryAndStoreStaffHasAnEmptyMenu() throws Exception {
        Clinic clinic = newClinic();
        for (String role : EXISTING_ROLES) {
            RequestPostProcessor auth = "OWNER".equals(role) ? clinic.owner() : crew(clinic, role).auth();
            List<String> menu = menuOf(auth);
            assertThat(menu).as(role).doesNotContain("pharmacy").contains("today");
        }
        assertThat(menuOf(crew(clinic, "STORE_STAFF").auth())).as("Store staff with the module off").isEmpty();
    }

    @Test
    void menusOfEveryExistingRoleAreIdenticalWithTheModuleOffAndWithItOnExceptTheOneNewKey() throws Exception {
        Clinic clinic = newClinic();
        Map<String, RequestPostProcessor> who = new LinkedHashMap<>();
        for (String role : EXISTING_ROLES) {
            who.put(role, "OWNER".equals(role) ? clinic.owner() : crew(clinic, role).auth());
        }
        Map<String, List<String>> off = new LinkedHashMap<>();
        for (var e : who.entrySet()) {
            off.put(e.getKey(), menuOf(e.getValue()));
        }
        pharmacyOn(clinic, true);
        for (var e : who.entrySet()) {
            List<String> on = new ArrayList<>(menuOf(e.getValue()));
            boolean gets = e.getKey().equals("OWNER") || e.getKey().equals("PHARMACIST");
            assertThat(on.contains("pharmacy")).as(e.getKey() + " gets the entry only as owner or pharmacist, never unmapped")
                    .isEqualTo(gets);
            on.remove("pharmacy");
            assertThat(on).as(e.getKey() + " menu otherwise unchanged").isEqualTo(off.get(e.getKey()));
        }
    }

    @Test
    void theTeamAndAccessMatrixAndTheModuleListAreUnchangedAndNeverMentionPharmacy() throws Exception {
        Clinic clinic = newClinic();
        JsonNode grid = ok(HttpMethod.GET, PERMS, clinic.owner(), null, 200);
        assertThat(grid.size()).as("8 roles x 7 permissions as before").isEqualTo(56);
        assertThat(grid.toString()).doesNotContain("STORE_STAFF");
        String modules = ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200).toString();
        assertThat(modules.toLowerCase()).doesNotContain("pharmacy");
        String me = ok(HttpMethod.GET, ME, clinic.owner(), null, 200).toString();
        assertThat(me.toLowerCase()).doesNotContain("pharmacy");
        // with the module on the matrix is still the same 56 cells and /modules is still without a pharmacy key
        pharmacyOn(clinic, true);
        assertThat(ok(HttpMethod.GET, PERMS, clinic.owner(), null, 200).size()).isEqualTo(56);
        assertThat(ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200).toString().toLowerCase()).doesNotContain("pharmacy");
    }

    @Test
    void aPermissionCellForStoreStaffIsRefusedWith400AndTheMatrixIsNotChanged() throws Exception {
        Clinic clinic = newClinic();
        JsonNode p = problem(call(HttpMethod.PUT, PERMS, clinic.owner(),
                "[{\"role\":\"STORE_STAFF\",\"permission\":\"token\",\"allowed\":true}]"), 400);
        assertThat(p.toString()).contains("Store staff does not use these permissions.");
        assertThat(ok(HttpMethod.GET, PERMS, clinic.owner(), null, 200).toString()).doesNotContain("STORE_STAFF");
    }

    @Test
    void existingTeamMembersKeepTheirRolesAndAStoreStaffMemberCanBeAddedWhileTheModuleIsOff() throws Exception {
        Clinic clinic = newClinic(); // owner and Dr Asha
        JsonNode before = ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200);
        List<String> rolesBefore = before.findValuesAsText("role");
        JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Sana Store\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"STORE_STAFF\"}", 201);
        assertThat(created.get("role").asText()).isEqualTo("STORE_STAFF");
        List<String> rolesAfter = ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200).findValuesAsText("role");
        assertThat(rolesAfter).containsAll(rolesBefore).contains("STORE_STAFF");
        for (String role : rolesBefore) {
            assertThat(rolesAfter.stream().filter(role::equals).count())
                    .as(role).isEqualTo(rolesBefore.stream().filter(role::equals).count());
        }
    }

    @Test
    void existingEndpointsAnswerAsBeforeWithTheModuleOffForTheOwner() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200);
        ok(HttpMethod.GET, "/api/v1/platform/clinic/profile", clinic.owner(), null, 200);
        ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200);
        ok(HttpMethod.GET, "/api/v1/billing/counter", clinic.owner(), null, 200);
        ok(HttpMethod.GET, "/api/v1/opd/booking-rules", clinic.owner(), null, 200);
    }
}
