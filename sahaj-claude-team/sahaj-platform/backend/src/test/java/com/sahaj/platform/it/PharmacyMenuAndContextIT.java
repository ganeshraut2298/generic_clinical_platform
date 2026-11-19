package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78-1 and 78-4 (working store) on the real database with the module On: the {@code pharmacy} menu key for the owner, the
 * pharmacist and a mapped member; an empty menu for an unmapped Store staff member; the access levels of the contract
 * (SETUP_EDIT, SETUP_VIEW, PHARMACY_ACCESS); GET /context; the working store; the setup summary. Test plan:
 * docs/test-plans/SAH-78.md.
 */
@OnTestDatabase
class PharmacyMenuAndContextIT extends Sah78Support {

    private static final String NOT_ALLOWED_TEXT = "You do not have access to this.";
    private static final String ONLY_OWNER_TEXT = "Only the clinic owner can do this.";

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---------------- the menu ----------------

    @Test
    void theOwnerGetsOnePharmacyEntryBeforeConfigInBothMenuModes() throws Exception {
        Clinic clinic = pharmacyClinic();
        for (String mode : new String[] {"simple", "advanced"}) {
            ok(HttpMethod.PATCH, ME + "/preferences", clinic.owner(), "{\"menuMode\":\"" + mode + "\"}", 200);
            List<String> menu = menuOf(clinic.owner());
            assertThat(menu.stream().filter("pharmacy"::equals).count()).as(mode).isOne();
            assertThat(menu.indexOf("pharmacy")).as(mode + " before config").isLessThan(menu.indexOf("config"));
        }
    }

    @Test
    void aPharmacistWithNoMappingGetsTheEntryAndAMappedNurseDoesAndAnUnmappedNurseDoesNot() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn mappedNurse = crew(clinic, "NURSE");
        Pn plainNurse = crew(clinic, "NURSE");
        Pn doctor = crew(clinic, "DOCTOR");
        mapStaff(clinic, idOf(stores[1]), mappedNurse, "STORE_USER");
        assertThat(menuOf(pharmacist.auth())).contains("pharmacy");
        assertThat(menuOf(mappedNurse.auth())).contains("pharmacy");
        assertThat(menuOf(plainNurse.auth())).doesNotContain("pharmacy");
        assertThat(menuOf(doctor.auth())).doesNotContain("pharmacy");
    }

    @Test
    void aStoreStaffMemberWithNoMappingHasAnEmptyMenuAndOneMappedHasOnlyPharmacy() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn unmapped = crew(clinic, "STORE_STAFF");
        Pn mapped = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[1]), mapped, "STORE_USER");
        assertThat(menuOf(unmapped.auth())).isEmpty();
        for (String mode : new String[] {"simple", "advanced"}) {
            ok(HttpMethod.PATCH, ME + "/preferences", mapped.auth(), "{\"menuMode\":\"" + mode + "\"}", 200);
            assertThat(menuOf(mapped.auth())).as(mode).containsExactly("pharmacy");
        }
    }

    @Test
    void theEntryGoesWhenTheMappingIsRemovedOrTheStoreIsSwitchedOff() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn a = crew(clinic, "STORE_STAFF");
        Pn b = crew(clinic, "NURSE");
        JsonNode mapA = mapStaff(clinic, idOf(stores[1]), a, "STORE_USER");
        mapStaff(clinic, idOf(stores[1]), b, "STORE_USER");
        assertThat(menuOf(a.auth())).containsExactly("pharmacy");
        ok(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(mapA)) + "/remove", clinic.owner(), null, 200);
        assertThat(menuOf(a.auth())).as("mapping removed").isEmpty();
        assertThat(menuOf(b.auth())).contains("pharmacy");
        ok(HttpMethod.POST, STORES + "/" + idOf(stores[1]) + "/switch-off", clinic.owner(), null, 200);
        assertThat(menuOf(b.auth())).as("store switched off").doesNotContain("pharmacy");
    }

    @Test
    void aMemberMappedOnlyInAnotherClinicGetsNoEntryHere() throws Exception {
        Clinic here = pharmacyClinic();
        Clinic there = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(there);
        Pn nurseThere = crew(there, "NURSE");
        mapStaff(there, idOf(stores[1]), nurseThere, "STORE_USER");
        Pn nurseHere = crew(here, "NURSE");
        assertThat(menuOf(nurseThere.auth())).contains("pharmacy");
        assertThat(menuOf(nurseHere.auth())).doesNotContain("pharmacy");
    }

    // ---------------- access levels ----------------

    @Test
    void anUnmappedStoreStaffMemberFrontDeskAndNurseGet403ForbiddenWithTheNoAccessTextOnEveryOperation() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralAndPharmacy(clinic);
        for (String role : new String[] {"STORE_STAFF", "FRONT_DESK", "NURSE", "BILLING", "COUNSELLOR", "VISITING_DOCTOR", "DOCTOR"}) {
            Pn p = crew(clinic, role);
            for (String path : new String[] {CONTEXT}) {
                JsonNode body = problem(call(HttpMethod.GET, path, p.auth(), null), 403);
                assertType(body, "forbidden");
                assertThat(body.get("detail").asText()).as(role).isEqualTo(NO_ACCESS_TEXT);
            }
            // the setup APIs as well (the access check comes before everything else but the module switch)
            for (String path : new String[] {STORES, UNIT, SUMMARY}) {
                JsonNode body = problem(call(HttpMethod.GET, path, p.auth(), null), 403);
                assertType(body, "forbidden");
                assertThat(body.get("detail").asText()).as(role + " " + path).isEqualTo(NO_ACCESS_TEXT);
            }
            assertType(problem(call(HttpMethod.PUT, WORKING_STORE, p.auth(),
                    "{\"storeId\":\"" + java.util.UUID.randomUUID() + "\"}"), 403), "forbidden");
        }
    }

    @Test
    void aMappedStoreUserWithoutSetupViewReadsContextButGets403OnTheSetupApisWithTheGenericText() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode context = contextOf(nurse.auth());
        assertThat(context.get("caller").get("setupAccess").asText()).isEqualTo("NONE");
        assertThat(texts(context.get("capabilities"))).doesNotContain("SETUP_VIEW", "SETUP_EDIT");
        for (String path : new String[] {STORES, UNIT, SUMMARY, staffUrl(idOf(stores[1]))}) {
            JsonNode body = problem(call(HttpMethod.GET, path, nurse.auth(), null), 403);
            assertType(body, "forbidden");
            assertThat(body.get("detail").asText()).as(path).isEqualTo(NOT_ALLOWED_TEXT);
        }
    }

    @Test
    void thePharmacistAndACentralStoreUserMayReadTheSetupButEveryChangeIs403OnlyTheOwnerCan() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn centralUser = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[0]), centralUser, "CENTRAL_STORE_USER");
        for (Pn reader : new Pn[] {pharmacist, centralUser}) {
            ok(HttpMethod.GET, STORES, reader.auth(), null, 200);
            ok(HttpMethod.GET, UNIT, reader.auth(), null, 200);
            ok(HttpMethod.GET, SUMMARY, reader.auth(), null, 200);
            ok(HttpMethod.GET, STORES + "/" + idOf(stores[1]), reader.auth(), null, 200);
            ok(HttpMethod.GET, staffUrl(idOf(stores[1])), reader.auth(), null, 200);
            String sid = idOf(stores[1]);
            for (String[] change : new String[][] {
                {"PUT", UNIT, "{\"version\":0,\"integrationMode\":\"STANDALONE\"}"},
                {"POST", STORES, "{\"name\":\"Ward A\",\"code\":\"WA\",\"type\":\"WARD\"}"},
                {"PUT", STORES + "/" + sid, "{\"version\":0,\"name\":\"X\",\"code\":\"X\",\"type\":\"PHARMACY\"}"},
                {"POST", STORES + "/" + sid + "/switch-off", null},
                {"POST", staffUrl(sid), "{\"membershipId\":\"" + pharmacist.membershipId() + "\",\"storeRole\":\"STORE_USER\"}"},
                {"GET", CANDIDATES + "?storeId=" + sid, null}}) {
                JsonNode body = problem(call(HttpMethod.valueOf(change[0]), change[1], reader.auth(), change[2]), 403);
                assertType(body, "forbidden");
                assertThat(body.get("detail").asText()).as(change[0] + " " + change[1]).isEqualTo(ONLY_OWNER_TEXT);
            }
        }
        assertThat(pharmacyRows(clinic, "store")).as("nothing was changed by the refused calls").isEqualTo(2);
        assertThat(ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).get("saved").asBoolean()).isFalse();
    }

    @Test
    void aStoreUserMappedAtTheCentralStoreNotAsCentralStoreUserCannotReadTheSetup() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn user = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[0]), user, "STORE_USER");
        assertType(problem(call(HttpMethod.GET, STORES, user.auth(), null), 403), "forbidden");
    }

    @Test
    void aSwitchedOffTeamMemberCanNoLongerUseTheModuleAndStopsCounting() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        Pn keeper = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(stores[1]), keeper, "STORE_USER");
        assertThat(ok(HttpMethod.GET, SUMMARY, clinic.owner(), null, 200).get("mappedStaffCount").asInt()).isEqualTo(2);
        ok(HttpMethod.POST, TEAM + "/" + nurse.membershipId() + "/deactivate", clinic.owner(), null, 200);
        JsonNode row = mappingOf(clinic, idOf(stores[1]), nurse);
        assertThat(row.get("memberState").asText()).isEqualTo("CANNOT_SIGN_IN");
        assertThat(row.get("access").toString()).doesNotContain("true");
        assertThat(ok(HttpMethod.GET, SUMMARY, clinic.owner(), null, 200).get("mappedStaffCount").asInt()).isEqualTo(1);
        problem(call(HttpMethod.GET, CONTEXT, nurse.auth(), null), 401);
        JsonNode store = ok(HttpMethod.GET, STORES + "/" + idOf(stores[1]), clinic.owner(), null, 200);
        assertThat(store.get("staffCount").asInt()).isEqualTo(1);
    }

    // ---------------- GET /context ----------------

    @Test
    void theOwnersContextHasTheUnitTheEditCapabilitiesAndNoStoreUntilMapped() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralAndPharmacy(clinic);
        JsonNode c = contextOf(clinic.owner());
        assertThat(c.get("unit").get("name").asText()).isEqualTo("Sahaj test clinic");
        assertThat(c.get("unit").get("code").asText()).matches("[A-Z0-9]{3}");
        assertThat(c.get("unit").get("integrationMode").asText()).isEqualTo("INTEGRATED");
        assertThat(c.get("caller").get("role").asText()).isEqualTo("OWNER");
        assertThat(c.get("caller").get("roleLabel").asText()).isEqualTo("Owner");
        assertThat(c.get("caller").get("setupAccess").asText()).isEqualTo("EDIT");
        assertThat(texts(c.get("capabilities"))).contains("SETUP_VIEW", "SETUP_EDIT", "OPENING_STOCK_UPLOAD", "OPENING_STOCK_APPROVE");
        assertThat(c.get("stores")).as("the owner is mapped to nothing").isEmpty();
        assertThat(c.get("workingStoreId").isNull()).isTrue();
        assertThat(c.get("mustPickWorkingStore").asBoolean()).isFalse();
        assertThat(c.get("fixedRules").get("fefo").asBoolean()).isTrue();
        assertThat(c.get("fixedRules").get("negativeStockBlocked").asBoolean()).isTrue();
        assertThat(c.toString()).as("no mobile number and no OPD or IPD capability in Phase 1").doesNotContain("OPD_").doesNotContain("IPD_");
    }

    @Test
    void thePharmacistWithTwoStoresMustPickAndSeesViewCapabilitiesOnly() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn pharmacist = crew(clinic, "PHARMACIST", "Priya Pharmacist");
        JsonNode unmapped = contextOf(pharmacist.auth());
        assertThat(texts(unmapped.get("capabilities"))).containsExactly("SETUP_VIEW");
        assertThat(unmapped.get("caller").get("setupAccess").asText()).isEqualTo("VIEW");
        assertThat(unmapped.get("stores")).isEmpty();
        assertThat(unmapped.get("mustPickWorkingStore").asBoolean()).isFalse();
        mapStaff(clinic, idOf(stores[0]), pharmacist, "STORE_USER");
        mapStaff(clinic, idOf(stores[1]), pharmacist, "STORE_USER");
        JsonNode c = contextOf(pharmacist.auth());
        assertThat(c.get("stores")).hasSize(2);
        assertThat(c.get("stores").get(0).get("type").asText()).as("the Central Store first").isEqualTo("CENTRAL");
        assertThat(c.get("stores").get(1).get("access").get("receiveScheduleX").asBoolean()).isTrue();
        assertThat(c.get("workingStoreId").isNull()).isTrue();
        assertThat(c.get("mustPickWorkingStore").asBoolean()).isTrue();
        assertThat(texts(c.get("capabilities"))).contains("SETUP_VIEW", "OPENING_STOCK_UPLOAD").doesNotContain("SETUP_EDIT", "OPENING_STOCK_APPROVE");
        assertThat(c.get("caller").get("name").asText()).isEqualTo("Priya Pharmacist");
    }

    // ---------------- working store ----------------

    @Test
    void aPersonMappedToOneStoreHasItAsWorkingStoreWithoutChoosing() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[1]), keeper, "STORE_USER");
        JsonNode c = contextOf(keeper.auth());
        assertThat(c.get("workingStoreId").asText()).isEqualTo(idOf(stores[1]));
        assertThat(c.get("mustPickWorkingStore").asBoolean()).isFalse();
        assertThat(texts(c.get("capabilities"))).doesNotContain("SETUP_VIEW", "SETUP_EDIT");
    }

    @Test
    void aPersonWithSeveralStoresPicksOneItIsRememberedAndSwitchingChangesIt() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(ward), nurse, "STORE_USER");
        assertThat(contextOf(nurse.auth()).get("mustPickWorkingStore").asBoolean()).isTrue();
        JsonNode first = ok(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(ward) + "\"}", 200);
        assertThat(first.get("workingStoreId").asText()).isEqualTo(idOf(ward));
        assertThat(first.get("mustPickWorkingStore").asBoolean()).isFalse();
        JsonNode later = contextOf(nurse.auth());
        assertThat(later.get("workingStoreId").asText()).as("remembered for the next sign-in").isEqualTo(idOf(ward));
        ok(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(stores[1]) + "\"}", 200);
        assertThat(contextOf(nurse.auth()).get("workingStoreId").asText()).isEqualTo(idOf(stores[1]));
        assertThat(pharmacyRows(clinic, "staff_preference")).as("one row per person, updated in place").isOne();
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%PREFERENCE%' "
                + "or action like 'PHARMACY\\_WORKING%'")).as("the preference is not audited").isZero();
    }

    @Test
    void anUnmappedUnknownForeignOrSwitchedOffStoreAnswers404AndTheWorkingStoreDoesNotChange() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode other = createStore(clinic, "Ward 3B", "W3B", "WARD");
        JsonNode third = createStore(clinic, "OPD Store", "OPD", "SUB_STORE");
        Clinic foreignClinic = pharmacyClinic();
        JsonNode foreign = centralStore(foreignClinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(other), nurse, "STORE_USER");
        mapStaff(clinic, idOf(third), nurse, "STORE_USER");
        ok(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(stores[1]) + "\"}", 200);
        ok(HttpMethod.POST, STORES + "/" + idOf(third) + "/switch-off", clinic.owner(), null, 200);
        String[] bad = {idOf(stores[0]), java.util.UUID.randomUUID().toString(), idOf(foreign), idOf(third)};
        for (String storeId : bad) {
            assertType(problem(call(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + storeId + "\"}"), 404), "not-found");
        }
        assertThat(contextOf(nurse.auth()).get("workingStoreId").asText()).isEqualTo(idOf(stores[1]));
        // the owner is mapped to nothing: the working store is for mapped stores only
        assertType(problem(call(HttpMethod.PUT, WORKING_STORE, clinic.owner(), "{\"storeId\":\"" + idOf(stores[1]) + "\"}"), 404), "not-found");
    }

    @Test
    void aMissingOrMalformedStoreIdOnTheWorkingStoreIs400WithAProblemBody() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode missing = problem(call(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{}"), 400);
        assertType(missing, "validation");
        assertFieldMessage(missing, "storeId", "Choose a store");
        JsonNode malformed = problem(call(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"nope\"}"), 400);
        assertThat(malformed.get("fieldErrors").get(0).get("field").asText()).isEqualTo("storeId");
        assertType(problem(call(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(stores[1]) + "\",\"x\":1}"), 400), "validation");
        assertType(problem(call(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "not json"), 400), "malformed-request");
    }

    @Test
    void aRememberedStoreThatLaterLostItsMappingIsDroppedAndThePersonIsAskedToPickAgain() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        JsonNode sub = createStore(clinic, "OPD Store", "OPD", "SUB_STORE");
        Pn nurse = crew(clinic, "NURSE");
        JsonNode m1 = mapStaff(clinic, idOf(ward), nurse, "STORE_USER");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(sub), nurse, "STORE_USER");
        ok(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(ward) + "\"}", 200);
        ok(HttpMethod.POST, staffUrl(idOf(ward), idOf(m1)) + "/remove", clinic.owner(), null, 200);
        JsonNode c = contextOf(nurse.auth());
        assertThat(c.get("workingStoreId").isNull()).isTrue();
        assertThat(c.get("mustPickWorkingStore").asBoolean()).isTrue();
        assertThat(c.toString()).doesNotContain(idOf(ward));
        ok(HttpMethod.POST, STORES + "/" + idOf(sub) + "/switch-off", clinic.owner(), null, 200);
        JsonNode one = contextOf(nurse.auth());
        assertThat(one.get("stores")).hasSize(1);
        assertThat(one.get("workingStoreId").asText()).as("the only store left is the working store").isEqualTo(idOf(stores[1]));
    }

    // ---------------- setup summary ----------------

    @Test
    void theSetupSummaryCountsActiveStoresAndDistinctMappedPeopleForThisClinicOnly() throws Exception {
        Clinic clinic = pharmacyClinic();
        Clinic other = pharmacyClinic();
        centralStore(other);
        assertThat(ok(HttpMethod.GET, SUMMARY, clinic.owner(), null, 200).toString())
                .contains("\"storeCount\":0").contains("\"mappedStaffCount\":0").contains("\"activeVendorCount\":0");
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn nurse = crew(clinic, "NURSE");
        Pn keeper = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        mapStaff(clinic, idOf(ward), nurse, "STORE_USER");
        mapStaff(clinic, idOf(ward), keeper, "STORE_USER");
        JsonNode s = ok(HttpMethod.GET, SUMMARY, clinic.owner(), null, 200);
        assertThat(s.get("storeCount").asInt()).isEqualTo(3);
        assertThat(s.get("mappedStaffCount").asInt()).as("distinct people, not mappings").isEqualTo(2);
        assertThat(s.get("activeVendorCount").asInt()).isZero();
        ok(HttpMethod.POST, STORES + "/" + idOf(ward) + "/switch-off", clinic.owner(), null, 200);
        s = ok(HttpMethod.GET, SUMMARY, clinic.owner(), null, 200);
        assertThat(s.get("storeCount").asInt()).isEqualTo(2);
        assertThat(s.get("mappedStaffCount").asInt()).as("the mappings of a switched-off store stop counting").isEqualTo(1);
    }
}
