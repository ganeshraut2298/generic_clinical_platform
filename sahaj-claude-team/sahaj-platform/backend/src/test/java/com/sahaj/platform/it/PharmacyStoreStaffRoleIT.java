package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SAH-78-4 "Store staff" team role on the real stack: a Store staff member reaches Pharmacy and Inventory only through store
 * mappings and gets 403 on every other module's APIs (the role is in no existing role list); the role counts toward the plan
 * user limit (OQ-78-40, BA reading; the Team endpoint refuses the sixth person with 409 plan-limit). Test plan:
 * docs/test-plans/SAH-78.md (T78-4-SS-*).
 */
@OnTestDatabase
class PharmacyStoreStaffRoleIT extends Sah78Support {

    private static final String[] EXISTING_GETS = {
        "/api/v1/opd/booking-rules", "/api/v1/opd/alert-rules", "/api/v1/opd/case-paper-settings",
        "/api/v1/billing/counter", "/api/v1/billing/today-summary",
        "/api/v1/patient/patients",
        "/api/v1/ipd/admission-rules", "/api/v1/ipd/admission-requests",
        "/api/v1/hra/roster", "/api/v1/hra/summary",
        "/api/v1/platform/team", "/api/v1/platform/modules", "/api/v1/platform/clinic/profile", "/api/v1/platform/clinic/holidays",
        "/api/v1/platform/role-permissions", "/api/v1/platform/setup/overview", "/api/v1/platform/audit-events"};

    private void assertDeniedEverywhere(Clinic clinic, Pn storeStaff) throws Exception {
        for (String url : EXISTING_GETS) {
            MvcResult owner = call(HttpMethod.GET, url, clinic.owner(), null).andReturn();
            assertThat(owner.getResponse().getStatus()).as("the url exists for the owner: " + url).isNotEqualTo(404).isLessThan(500);
            JsonNode p = problem(call(HttpMethod.GET, url, storeStaff.auth(), null), 403);
            assertType(p, "forbidden");
        }
        // writes of the other modules
        assertType(problem(call(HttpMethod.POST, "/api/v1/platform/team", storeStaff.auth(),
                "{\"name\":\"X\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"NURSE\"}"), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, "/api/v1/platform/clinic/profile", storeStaff.auth(), "{}"), 403), "forbidden");
        assertType(problem(call(HttpMethod.POST, "/api/v1/platform/modules/ipd-trial", storeStaff.auth(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, "/api/v1/platform/modules/hra", storeStaff.auth(), "{\"on\":true}"), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, "/api/v1/platform/role-permissions", storeStaff.auth(), "[]"), 403), "forbidden");
        assertType(problem(call(HttpMethod.POST, "/api/v1/patient/patients", storeStaff.auth(),
                "{\"name\":\"Test Patient\",\"age\":40,\"sex\":\"M\",\"mobile\":\"" + uniqueMobile() + "\"}"), 403), "forbidden");
        assertType(problem(call(HttpMethod.POST, "/api/v1/billing/bills/" + java.util.UUID.randomUUID() + "/items", storeStaff.auth(), "{}"), 403), "forbidden");
    }

    @Test
    void aStoreStaffMemberGets403OnTheApisOfOpdBillingPatientIpdHraAndPlatformWhetherOrNotTheyAreMappedAndWhetherTheModuleIsOnOrOff() throws Exception {
        Clinic clinic = newClinic(); // module off
        Pn unmapped = crew(clinic, "STORE_STAFF");
        assertDeniedEverywhere(clinic, unmapped);
        pharmacyOn(clinic, true);
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn mapped = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[1]), mapped, "STORE_USER");
        assertDeniedEverywhere(clinic, mapped);
        assertDeniedEverywhere(clinic, unmapped);
    }

    @Test
    void theShellStillLoadsForAStoreStaffMemberAndItsMenuIsEmptyOrOnlyPharmacy() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn unmapped = crew(clinic, "STORE_STAFF");
        Pn mapped = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[1]), mapped, "STORE_USER");
        JsonNode me = ok(HttpMethod.GET, ME, mapped.auth(), null, 200);
        assertThat(me.get("role").asText()).isEqualTo("STORE_STAFF");
        assertThat(me.get("menu").toString()).isEqualTo("[\"pharmacy\"]");
        assertThat(ok(HttpMethod.GET, ME, unmapped.auth(), null, 200).get("menu")).isEmpty();
        ok(HttpMethod.PATCH, ME + "/preferences", mapped.auth(), "{\"language\":\"hi\"}", 200);
    }

    @Test
    void storeStaffCountsTowardThePlanUserLimitAndSwitchingOneOffReleasesTheSeat() throws Exception {
        Clinic clinic = newClinic(); // owner + doctor = 2 of 5 users
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(),
                    "{\"name\":\"Store person " + i + "\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"STORE_STAFF\"}", 201);
            assertThat(created.get("role").asText()).isEqualTo("STORE_STAFF");
            ids.add(created.get("id").asText());
        }
        JsonNode full = problem(call(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Sixth store person\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"STORE_STAFF\"}"), 409);
        assertType(full, "plan-limit");
        assertType(problem(call(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"A nurse\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"NURSE\"}"), 409), "plan-limit");
        assertThat(count(clinic, "select count(*) from platform.membership where status = 'ACTIVE'")).isEqualTo(5);
        ok(HttpMethod.POST, TEAM + "/" + ids.get(0) + "/deactivate", clinic.owner(), null, 200);
        JsonNode again = ok(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Replacement\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"STORE_STAFF\"}", 201);
        assertType(problem(call(HttpMethod.POST, TEAM + "/" + ids.get(0) + "/reactivate", clinic.owner(), null), 409), "plan-limit");
        assertThat(again.get("role").asText()).isEqualTo("STORE_STAFF");
    }

    @Test
    void theStoreStaffRoleIsInTheTeamListAndAnInvalidRoleIsStillRefused() throws Exception {
        Clinic clinic = newClinic();
        JsonNode created = ok(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Sana Store\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"STORE_STAFF\"}", 201);
        assertThat(ok(HttpMethod.GET, TEAM, clinic.owner(), null, 200).toString()).contains(created.get("id").asText()).contains("STORE_STAFF");
        problem(call(HttpMethod.POST, TEAM, clinic.owner(),
                "{\"name\":\"Nobody\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"STORE_KEEPER\"}"), 400);
        // no doctor details are asked for or kept for Store staff
        assertThat(created.has("doctor")).isFalse();
    }
}
