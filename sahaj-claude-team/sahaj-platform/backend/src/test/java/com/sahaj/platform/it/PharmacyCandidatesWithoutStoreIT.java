package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78 code review F-11 (docs/api/pharmacy.yaml 0.1.1, GET /staff-candidates): {@code storeId} is optional. Without it (absent
 * or sent empty) the list is by team role only, {@code allowedStoreRoles} is the full team-role list (Central Store user
 * included where the team role allows it), {@code alreadyMappedHere} is always false, and the answer is never 404. Check order
 * is the same in both forms: module off 403, owner only 403, then 400, then 404 for a given storeId. The form with a store is
 * proved in {@link PharmacyStaffMappingIT}. Test plan: docs/test-plans/SAH-78.md (T78-F11-*).
 */
@OnTestDatabase
class PharmacyCandidatesWithoutStoreIT extends Sah78Support {

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode candidate(JsonNode candidates, String membershipId) {
        for (JsonNode c : candidates.get("items")) {
            if (membershipId.equals(c.get("membershipId").asText())) {
                return c;
            }
        }
        return null;
    }

    @Test
    void theOwnerGets200WithoutAStoreAndEachTeamRoleListsItsFullStoreRolesCentralStoreUserIncluded() throws Exception {
        Clinic clinic = pharmacyClinic(); // no store exists yet: the first Central Store form
        Pn nurse = crew(clinic, "NURSE");
        Pn doctor = crew(clinic, "DOCTOR");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn keeper = crew(clinic, "STORE_STAFF");
        Pn billing = crew(clinic, "BILLING");
        Pn desk = crew(clinic, "FRONT_DESK");
        Pn visiting = crew(clinic, "VISITING_DOCTOR");
        Pn counsellor = crew(clinic, "COUNSELLOR");
        JsonNode list = ok(HttpMethod.GET, CANDIDATES, clinic.owner(), null, 200);
        for (Pn p : new Pn[] {nurse, doctor, pharmacist}) {
            assertThat(texts(candidate(list, p.membershipId().toString()).get("allowedStoreRoles"))).as(p.role())
                    .containsExactlyInAnyOrder("STORE_USER", "CENTRAL_STORE_USER", "APPROVER", "DELEGATE");
        }
        for (Pn p : new Pn[] {keeper, billing}) {
            assertThat(texts(candidate(list, p.membershipId().toString()).get("allowedStoreRoles"))).as(p.role())
                    .containsExactlyInAnyOrder("STORE_USER", "CENTRAL_STORE_USER");
        }
        for (Pn p : new Pn[] {desk, visiting, counsellor}) {
            assertThat(texts(candidate(list, p.membershipId().toString()).get("allowedStoreRoles"))).as(p.role())
                    .containsExactly("STORE_USER");
        }
        // the owner is a candidate too, as every team member with an active seat
        JsonNode ownerRow = null;
        for (JsonNode c : list.get("items")) {
            if ("OWNER".equals(c.get("role").asText())) {
                ownerRow = c;
            }
        }
        assertThat(ownerRow).isNotNull();
        assertThat(texts(ownerRow.get("allowedStoreRoles"))).containsExactlyInAnyOrder("STORE_USER", "CENTRAL_STORE_USER", "APPROVER", "DELEGATE");
        assertThat(candidate(list, keeper.membershipId().toString()).get("roleLabel").asText()).isEqualTo("Store staff");
        assertThat(list.toString()).as("never a mobile number").doesNotContain(nurse.mobile()).doesNotContain(keeper.mobile());
        assertThat(pharmacyRows(clinic, "store")).as("a read writes nothing").isZero();
    }

    @Test
    void alreadyMappedHereIsFalseForEveryoneEvenForAMemberWhoIsMappedToAStore() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[0]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode withStore = ok(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]), clinic.owner(), null, 200);
        assertThat(candidate(withStore, nurse.membershipId().toString()).get("alreadyMappedHere").asBoolean()).as("with a store, control").isTrue();
        JsonNode without = ok(HttpMethod.GET, CANDIDATES, clinic.owner(), null, 200);
        for (JsonNode c : without.get("items")) {
            assertThat(c.get("alreadyMappedHere").asBoolean()).as("no store, so no mapping to compare: " + c.get("role").asText()).isFalse();
        }
        assertThat(candidate(without, nurse.membershipId().toString())).isNotNull();
    }

    @Test
    void theApproverAndDelegateFiltersListTheMembersWhoMayHoldThatRoleAndCentralStoreUserNeedsNoStoreType() throws Exception {
        Clinic clinic = pharmacyClinic();
        Pn nurse = crew(clinic, "NURSE");
        Pn doctor = crew(clinic, "DOCTOR");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn keeper = crew(clinic, "STORE_STAFF");
        Pn billing = crew(clinic, "BILLING");
        Pn desk = crew(clinic, "FRONT_DESK");
        for (String role : new String[] {"APPROVER", "DELEGATE"}) {
            JsonNode list = ok(HttpMethod.GET, CANDIDATES + "?storeRole=" + role, clinic.owner(), null, 200);
            for (Pn p : new Pn[] {nurse, doctor, pharmacist}) {
                assertThat(candidate(list, p.membershipId().toString())).as(role + " " + p.role()).isNotNull();
            }
            for (Pn p : new Pn[] {keeper, billing, desk}) {
                assertThat(candidate(list, p.membershipId().toString())).as(role + " " + p.role() + " is not offered").isNull();
            }
            for (JsonNode c : list.get("items")) {
                assertThat(texts(c.get("allowedStoreRoles"))).contains(role);
                assertThat(c.get("alreadyMappedHere").asBoolean()).isFalse();
            }
        }
        JsonNode central = ok(HttpMethod.GET, CANDIDATES + "?storeRole=CENTRAL_STORE_USER", clinic.owner(), null, 200);
        assertThat(candidate(central, keeper.membershipId().toString())).as("Store staff may be a Central Store user").isNotNull();
        assertThat(candidate(central, desk.membershipId().toString())).isNull();
    }

    @Test
    void anEmptyStoreIdIsTheSameAsNoStoreIdAndAnEmptyStoreRoleIsNoFilter() throws Exception {
        Clinic clinic = pharmacyClinic();
        Pn keeper = crew(clinic, "STORE_STAFF");
        JsonNode absent = ok(HttpMethod.GET, CANDIDATES, clinic.owner(), null, 200);
        JsonNode empty = ok(HttpMethod.GET, CANDIDATES + "?storeId=", clinic.owner(), null, 200);
        JsonNode emptyBoth = ok(HttpMethod.GET, CANDIDATES + "?storeId=&storeRole=", clinic.owner(), null, 200);
        assertThat(empty).isEqualTo(absent);
        assertThat(emptyBoth).isEqualTo(absent);
        assertThat(candidate(empty, keeper.membershipId().toString())).isNotNull();
    }

    @Test
    void withoutAStoreIdTheAnswerIsNeverNotFoundAndAnUnknownOrForeignStoreIdIsStill404() throws Exception {
        Clinic clinic = pharmacyClinic();
        Clinic other = pharmacyClinic();
        JsonNode foreign = centralStore(other);
        // the no-storeId form never answers 404, also for a clinic with no store at all
        ok(HttpMethod.GET, CANDIDATES, clinic.owner(), null, 200);
        ok(HttpMethod.GET, CANDIDATES + "?storeRole=APPROVER", clinic.owner(), null, 200);
        // a given storeId is checked as before
        assertType(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + UUID.randomUUID(), clinic.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(foreign), clinic.owner(), null), 404), "not-found");
    }

    @Test
    void aBadStoreIdOrStoreRoleIs400WithAFieldMessageAndNothingIsEchoed() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode p = problem(call(HttpMethod.GET, CANDIDATES + "?storeId=not-a-uuid", clinic.owner(), null), 400);
        assertType(p, "validation");
        assertFieldMessage(p, "storeId", "Choose a store");
        assertThat(p.toString()).doesNotContain("not-a-uuid");
        assertFieldMessage(problem(call(HttpMethod.GET, CANDIDATES + "?storeRole=BOSS", clinic.owner(), null), 400), "storeRole", "Choose a valid option");
        // a bad storeId with no storeRole problem: 400 comes before any 404
        assertFieldMessage(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=12345&storeRole=APPROVER", clinic.owner(), null), 400), "storeId", "Choose a store");
    }

    @Test
    void aNonOwnerGets403WithOrWithoutAStoreIdAndBeforeAnyValidationAndTheModuleOffGets403ModuleOff() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        for (String role : new String[] {"NURSE", "DOCTOR", "PHARMACIST", "STORE_STAFF", "FRONT_DESK"}) {
            Pn p = crew(clinic, role);
            for (String query : new String[] {"", "?storeId=", "?storeRole=APPROVER", "?storeId=" + idOf(stores[1]), "?storeId=not-a-uuid&storeRole=BOSS"}) {
                assertType(problem(call(HttpMethod.GET, CANDIDATES + query, p.auth(), null), 403), "forbidden");
            }
        }
        Clinic off = newClinic();
        for (String query : new String[] {"", "?storeId=", "?storeId=not-a-uuid&storeRole=BOSS"}) {
            JsonNode p = problem(call(HttpMethod.GET, CANDIDATES + query, off.owner(), null), 403);
            assertType(p, "module-off");
            assertThat(p.get("detail").asText()).isEqualTo(MODULE_OFF_TEXT);
        }
    }

    @Test
    void anotherClinicsTeamIsNeverListed() throws Exception {
        Clinic a = pharmacyClinic();
        Clinic b = pharmacyClinic();
        Pn ofB = crew(b, "NURSE");
        Pn ofA = crew(a, "NURSE");
        JsonNode list = ok(HttpMethod.GET, CANDIDATES, a.owner(), null, 200);
        assertThat(candidate(list, ofA.membershipId().toString())).isNotNull();
        assertThat(candidate(list, ofB.membershipId().toString())).isNull();
    }
}
