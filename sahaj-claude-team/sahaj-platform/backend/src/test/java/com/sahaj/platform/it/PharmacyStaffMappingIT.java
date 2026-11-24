package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78-4 staff to store mapping on the real database: candidates and the store roles each team role may take, the derived
 * access columns (including the receiveScheduleX rule), duplicates and seats, delegate dates, removal and re-mapping that
 * re-activates the same row, needs-review, roles and tenants. Test plan: docs/test-plans/SAH-78.md (T78-4-*).
 */
@OnTestDatabase
class PharmacyStaffMappingIT extends Sah78Support {

    private static final String BAD_ROLE = "This person cannot have this store role";
    private static final String BAD_MEMBER = "Choose an active team member";

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, Object> delegateBody(Pn p, Object from, Object to) {
        Map<String, Object> m = mapBody(p, "DELEGATE");
        m.put("delegateFrom", from);
        m.put("delegateTo", to);
        return m;
    }

    private static Map<String, Object> changeBody(JsonNode mapping, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", mapping.get("version").asLong());
        m.put("storeRole", role);
        return m;
    }

    private JsonNode candidate(JsonNode candidates, Pn p) {
        for (JsonNode c : candidates.get("items")) {
            if (p.membershipId().toString().equals(c.get("membershipId").asText())) {
                return c;
            }
        }
        return null;
    }

    private void assertAccess(JsonNode access, boolean raise, boolean approve, boolean receive, boolean schedX, boolean consume,
                              boolean upload, String why) {
        assertThat(access.get("raiseIndent").asBoolean()).as(why + " raiseIndent").isEqualTo(raise);
        assertThat(access.get("approve").asBoolean()).as(why + " approve").isEqualTo(approve);
        assertThat(access.get("receive").asBoolean()).as(why + " receive").isEqualTo(receive);
        assertThat(access.get("receiveScheduleX").asBoolean()).as(why + " receiveScheduleX").isEqualTo(schedX);
        assertThat(access.get("consumeReturn").asBoolean()).as(why + " consumeReturn").isEqualTo(consume);
        assertThat(access.get("uploadOpeningStock").asBoolean()).as(why + " uploadOpeningStock").isEqualTo(upload);
    }

    // ---------------- candidates ----------------

    @Test
    void candidatesCarryTheAllowedStoreRolesPerTeamRoleCentralStoreUserOnlyAtTheCentralStoreAndNoMobileNumber() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        Pn keeper = crew(clinic, "STORE_STAFF");
        Pn desk = crew(clinic, "FRONT_DESK");
        Pn billing = crew(clinic, "BILLING");
        JsonNode atCentral = ok(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[0]), clinic.owner(), null, 200);
        assertThat(texts(candidate(atCentral, nurse).get("allowedStoreRoles")))
                .containsExactlyInAnyOrder("STORE_USER", "CENTRAL_STORE_USER", "APPROVER", "DELEGATE");
        assertThat(texts(candidate(atCentral, keeper).get("allowedStoreRoles"))).containsExactlyInAnyOrder("STORE_USER", "CENTRAL_STORE_USER");
        assertThat(texts(candidate(atCentral, billing).get("allowedStoreRoles"))).containsExactlyInAnyOrder("STORE_USER", "CENTRAL_STORE_USER");
        assertThat(texts(candidate(atCentral, desk).get("allowedStoreRoles"))).containsExactly("STORE_USER");
        assertThat(candidate(atCentral, keeper).get("roleLabel").asText()).isEqualTo("Store staff");
        JsonNode atPharmacy = ok(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]), clinic.owner(), null, 200);
        assertThat(texts(candidate(atPharmacy, nurse).get("allowedStoreRoles"))).containsExactlyInAnyOrder("STORE_USER", "APPROVER", "DELEGATE");
        assertThat(texts(candidate(atPharmacy, keeper).get("allowedStoreRoles"))).containsExactlyInAnyOrder("STORE_USER", "CASHIER");
        assertThat(atPharmacy.toString()).as("never a mobile number").doesNotContain(nurse.mobile()).doesNotContain(keeper.mobile());
        // the filter keeps only the people allowed that role
        JsonNode approvers = ok(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]) + "&storeRole=APPROVER", clinic.owner(), null, 200);
        assertThat(candidate(approvers, nurse)).isNotNull();
        assertThat(candidate(approvers, keeper)).isNull();
        assertThat(candidate(approvers, desk)).isNull();
        assertThat(candidate(approvers, billing)).isNull();
        // already mapped here, and a person switched off is not offered
        mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        assertThat(candidate(ok(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]), clinic.owner(), null, 200), nurse)
                .get("alreadyMappedHere").asBoolean()).isTrue();
        ok(HttpMethod.POST, TEAM + "/" + desk.membershipId() + "/deactivate", clinic.owner(), null, 200);
        assertThat(candidate(ok(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]), clinic.owner(), null, 200), desk)).isNull();
    }

    @Test
    void theCandidatesListNeedsAStoreOfThisClinicAndTheOwner() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        // 0.1.1 (F-11): storeId is optional; without it the owner gets the list by team role (see PharmacyCandidatesWithoutStoreIT)
        assertThat(ok(HttpMethod.GET, CANDIDATES, clinic.owner(), null, 200).get("items")).isNotNull();
        assertFieldMessage(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=nope", clinic.owner(), null), 400), "storeId", "Choose a store");
        assertFieldMessage(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]) + "&storeRole=BOSS", clinic.owner(), null), 400),
                "storeRole", "Choose a valid option");
        assertType(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + UUID.randomUUID(), clinic.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]), nurse.auth(), null), 403), "forbidden");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        assertType(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + idOf(stores[1]), pharmacist.auth(), null), 403), "forbidden");
    }

    @Test
    void aNonOwnerWithoutAStoreIdGets403BeforeAnyValidationAsTheContractOrderSays() throws Exception {
        // docs/api/pharmacy.yaml general rule 2: the access level (403) comes before body and parameter validation (400)
        Clinic clinic = pharmacyClinic();
        centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        assertType(problem(call(HttpMethod.GET, CANDIDATES, nurse.auth(), null), 403), "forbidden");
    }

    // ---------------- mapping rules ----------------

    @Test
    void storeStaffCanBeMappedOnlyAsStoreUserOrCentralStoreUserAndNeverAsApproverOrDelegate() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "STORE_STAFF");
        assertFieldMessage(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(mapBody(keeper, "APPROVER"))), 400), "storeRole", BAD_ROLE);
        assertFieldMessage(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(),
                js(delegateBody(keeper, todayIst(), todayIst().plusDays(3)))), 400), "storeRole", BAD_ROLE);
        // there is no Pharmacist store role at all (contract: the pharmacist powers come from the team role)
        JsonNode pharmacistRole = problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(mapBody(keeper, "PHARMACIST"))), 400);
        assertThat(pharmacistRole.get("fieldErrors").get(0).get("field").asText()).isEqualTo("storeRole");
        assertThat(pharmacyRows(clinic, "store_staff")).isZero();
        mapStaff(clinic, idOf(stores[1]), keeper, "STORE_USER");
        assertThat(pharmacyRows(clinic, "store_staff")).isOne();
    }

    @Test
    void aCentralStoreUserRoleIsOnlyForTheCentralStoreAndOnlyForRolesThatMayHoldIt() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "STORE_STAFF");
        Pn nurse = crew(clinic, "NURSE");
        Pn desk = crew(clinic, "FRONT_DESK");
        assertFieldMessage(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(mapBody(keeper, "CENTRAL_STORE_USER"))), 400),
                "storeRole", "Central Store user is only for the Central Store");
        assertFieldMessage(problem(call(HttpMethod.POST, staffUrl(idOf(stores[0])), clinic.owner(), js(mapBody(desk, "CENTRAL_STORE_USER"))), 400),
                "storeRole", BAD_ROLE);
        assertFieldMessage(problem(call(HttpMethod.POST, staffUrl(idOf(stores[0])), clinic.owner(), js(mapBody(desk, "APPROVER"))), 400),
                "storeRole", BAD_ROLE);
        assertFieldMessage(problem(call(HttpMethod.POST, staffUrl(idOf(stores[0])), clinic.owner(),
                js(delegateBody(desk, todayIst(), todayIst().plusDays(2)))), 400), "storeRole", BAD_ROLE);
        assertThat(mapStaff(clinic, idOf(stores[0]), keeper, "CENTRAL_STORE_USER").get("storeRole").asText()).isEqualTo("CENTRAL_STORE_USER");
        assertThat(mapStaff(clinic, idOf(stores[0]), nurse, "CENTRAL_STORE_USER").get("effectiveStoreRole").asText()).isEqualTo("CENTRAL_STORE_USER");
        assertThat(mapStaff(clinic, idOf(stores[0]), desk, "STORE_USER").get("storeRole").asText()).isEqualTo("STORE_USER");
    }

    @Test
    void aMemberThatIsMissingUnknownForeignOrSwitchedOffAndAMissingRoleAreRefusedWithFieldMessages() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Clinic other = pharmacyClinic();
        Pn foreign = crew(other, "NURSE");
        Pn off = crew(clinic, "NURSE");
        ok(HttpMethod.POST, TEAM + "/" + off.membershipId() + "/deactivate", clinic.owner(), null, 200);
        String url = staffUrl(idOf(stores[1]));
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), "{\"storeRole\":\"STORE_USER\"}"), 400), "membershipId", BAD_MEMBER);
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(),
                "{\"membershipId\":\"" + UUID.randomUUID() + "\",\"storeRole\":\"STORE_USER\"}"), 400), "membershipId", BAD_MEMBER);
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(mapBody(foreign, "STORE_USER"))), 400), "membershipId", BAD_MEMBER);
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(mapBody(off, "STORE_USER"))), 400), "membershipId", BAD_MEMBER);
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), "{\"membershipId\":\"" + off.membershipId() + "\"}"), 400),
                "storeRole", "Choose a store role");
        assertThat(pharmacyRows(clinic, "store_staff")).isZero();
        assertThat(pharmacyRows(other, "store_staff")).isZero();
    }

    @Test
    void aClientCannotSendTheAccessColumnsOrAUnitOrTenantId() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        for (String extra : new String[] {"\"access\":{\"approve\":true}", "\"approve\":true", "\"tenantId\":\"" + clinic.tenantId() + "\"",
            "\"storeId\":\"" + idOf(stores[1]) + "\"", "\"status\":\"ACTIVE\""}) {
            String body = "{\"membershipId\":\"" + nurse.membershipId() + "\",\"storeRole\":\"STORE_USER\"," + extra + "}";
            assertType(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), body), 400), "validation");
        }
        assertThat(pharmacyRows(clinic, "store_staff")).isZero();
    }

    @Test
    void aPersonAlreadyInTheStoreIs409AndOnePersonCanBeInSeveralStoresWithDifferentRolesAndSeesTheOthers() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn nurse = crew(clinic, "NURSE");
        JsonNode first = mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode again = problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(mapBody(nurse, "APPROVER"))), 409);
        assertType(again, "already-mapped");
        assertThat(again.get("detail").asText()).isEqualTo(ALREADY_MAPPED_TEXT);
        assertThat(mappingOf(clinic, idOf(stores[1]), nurse).get("storeRole").asText()).as("nothing changed").isEqualTo("STORE_USER");
        JsonNode second = mapStaff(clinic, idOf(ward), nurse, "APPROVER");
        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        List<String> others = new ArrayList<>();
        mappingOf(clinic, idOf(stores[1]), nurse).get("otherStores").forEach(o -> others.add(o.get("name").asText()));
        assertThat(others).containsExactly("Ward 3B");
        assertThat(mappingOf(clinic, idOf(ward), nurse).get("otherStores").get(0).get("storeId").asText()).isEqualTo(idOf(stores[1]));
        ok(HttpMethod.POST, STORES + "/" + idOf(ward) + "/switch-off", clinic.owner(), null, 200);
        assertThat(mappingOf(clinic, idOf(stores[1]), nurse).get("otherStores")).as("a switched-off store is not listed").isEmpty();
    }

    @Test
    void aSecondApproverOrDelegateInOneStoreIs409ApproverExists() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn hod = crew(clinic, "NURSE");
        Pn other = crew(clinic, "DOCTOR");
        Pn deputy = crew(clinic, "PHARMACIST");
        Pn deputy2 = crew(clinic, "NURSE");
        mapStaff(clinic, idOf(stores[1]), hod, "APPROVER");
        JsonNode p = problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(mapBody(other, "APPROVER"))), 409);
        assertType(p, "approver-exists");
        assertThat(p.get("detail").asText()).isEqualTo("This store already has an approver. Change it on the store.");
        ok(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(delegateBody(deputy, todayIst(), todayIst().plusDays(5))), 201);
        JsonNode d = problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(),
                js(delegateBody(deputy2, todayIst(), todayIst().plusDays(5)))), 409);
        assertType(d, "approver-exists");
        assertThat(d.get("detail").asText()).isEqualTo("This store already has a delegate. Change it on the store.");
        assertThat(pharmacyRows(clinic, "store_staff")).isEqualTo(2);
    }

    @Test
    void aDelegateNeedsBothDatesInOrderWithinNinetyDaysAndMustNotBeTheHod() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn hod = crew(clinic, "NURSE");
        Pn deputy = crew(clinic, "DOCTOR");
        mapStaff(clinic, idOf(stores[1]), hod, "APPROVER");
        String url = staffUrl(idOf(stores[1]));
        LocalDate today = todayIst();
        JsonNode none = problem(call(HttpMethod.POST, url, clinic.owner(), js(delegateBody(deputy, null, null))), 400);
        assertFieldMessage(none, "delegateFrom", "Enter the start date");
        assertFieldMessage(none, "delegateTo", "Enter the end date");
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(delegateBody(deputy, today.plusDays(4), today.plusDays(1)))), 400),
                "delegateTo", "The end date must be on or after the start date");
        // 90 calendar days counting both ends (DEC-068 Q1): to - from <= 89. The 91st day (to = from + 90) and beyond are refused.
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(delegateBody(deputy, today, today.plusDays(90)))), 400),
                "delegateTo", "A delegation can be at most 90 days");
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(delegateBody(deputy, today, today.plusDays(91)))), 400),
                "delegateTo", "A delegation can be at most 90 days");
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(delegateBody(deputy, today.minusDays(9), today.minusDays(2)))), 400),
                "delegateTo", "The end date cannot be in the past");
        assertFieldMessage(problem(call(HttpMethod.POST, url, clinic.owner(), js(delegateBody(hod, today, today.plusDays(2)))), 400),
                "membershipId", "The delegate must be a different person from the approver");
        assertThat(pharmacyRows(clinic, "store_staff")).isOne();
        JsonNode ok = ok(HttpMethod.POST, url, clinic.owner(), js(delegateBody(deputy, today, today.plusDays(89))), 201);
        assertThat(ok.get("delegateState").asText()).isEqualTo("CURRENT");
        assertThat(ok.get("delegateTo").asText()).as("the 90th day counting both ends is the last one allowed")
                .isEqualTo(today.plusDays(89).toString());
        // dates on a role that is not DELEGATE are not kept
        Pn user = crew(clinic, "NURSE");
        Map<String, Object> withDates = mapBody(user, "STORE_USER");
        withDates.put("delegateFrom", today.toString());
        withDates.put("delegateTo", today.plusDays(1).toString());
        JsonNode plain = ok(HttpMethod.POST, url, clinic.owner(), js(withDates), 201);
        assertThat(plain.get("delegateFrom").isNull()).isTrue();
        assertThat(plain.get("delegateTo").isNull()).isTrue();
    }

    // ---------------- the access columns ----------------

    @Test
    void theAccessColumnsAreWorkedOutByTheServerFromTheStoreRoleTheTeamRoleAndTheConsumptionFlag() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        JsonNode pharmacy = createStore(clinic, "Main Pharmacy", "PHARM", "PHARMACY"); // consumption No
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD"); // consumption Yes
        Pn nurse = crew(clinic, "NURSE");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn doctor = crew(clinic, "DOCTOR");
        Pn keeper = crew(clinic, "STORE_STAFF");

        assertAccess(mapStaff(clinic, idOf(pharmacy), nurse, "APPROVER").get("access"), true, true, true, false, true, false, "nurse approver");
        assertAccess(mapStaff(clinic, idOf(ward), nurse, "STORE_USER").get("access"), true, false, true, false, true, false, "nurse store user at a ward");
        assertAccess(mapStaff(clinic, idOf(pharmacy), keeper, "STORE_USER").get("access"), true, false, true, false, false, false, "store staff at a pharmacy");
        assertAccess(mapStaff(clinic, idOf(ward), keeper, "STORE_USER").get("access"), true, false, true, false, true, false, "store staff at a ward");
        assertAccess(mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER").get("access"), false, false, true, false, false, true, "store staff central user");
        assertAccess(mapStaff(clinic, idOf(pharmacy), pharmacist, "STORE_USER").get("access"), true, false, true, true, false, true, "pharmacist store user");
        assertAccess(mapStaff(clinic, idOf(central), pharmacist, "CENTRAL_STORE_USER").get("access"), false, false, true, true, false, true, "pharmacist central user");
        assertAccess(mapStaff(clinic, idOf(ward), doctor, "APPROVER").get("access"), true, true, true, false, true, false, "doctor approver");
        // the same numbers come back from the list and from the person's own context
        JsonNode row = mappingOf(clinic, idOf(pharmacy), pharmacist);
        assertAccess(row.get("access"), true, false, true, true, false, true, "list row of the pharmacist");
        JsonNode mine = null;
        for (JsonNode s : contextOf(pharmacist.auth()).get("stores")) {
            if (idOf(pharmacy).equals(s.get("storeId").asText())) {
                mine = s;
            }
        }
        assertThat(mine).isNotNull();
        assertAccess(mine.get("access"), true, false, true, true, false, true, "context of the pharmacist");
        // a changed consumption flag changes the derived column at once (nothing was stored per person)
        Map<String, Object> flip = new LinkedHashMap<>();
        JsonNode fresh = ok(HttpMethod.GET, STORES + "/" + idOf(pharmacy), clinic.owner(), null, 200);
        flip.put("version", fresh.get("version").asLong());
        flip.put("name", "Main Pharmacy");
        flip.put("code", "PHARM");
        flip.put("type", "PHARMACY");
        flip.put("saleAllowed", true);
        flip.put("consumptionAllowed", true);
        flip.put("hodMembershipId", nurse.membershipId().toString());
        flip.put("delegate", null);
        ok(HttpMethod.PUT, STORES + "/" + idOf(pharmacy), clinic.owner(), js(flip), 200);
        assertThat(mappingOf(clinic, idOf(pharmacy), keeper).get("access").get("consumeReturn").asBoolean()).isTrue();
    }

    @Test
    void receiveScheduleXIsGivenOnlyToTheTeamRolePharmacistWhateverTheStoreRoleAndNeverToTheOwnerOrStoreStaff() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Map<String, List<String>> rolesPerTeamRole = new LinkedHashMap<>();
        rolesPerTeamRole.put("PHARMACIST", List.of("STORE_USER", "CENTRAL_STORE_USER"));
        rolesPerTeamRole.put("NURSE", List.of("STORE_USER", "CENTRAL_STORE_USER"));
        rolesPerTeamRole.put("DOCTOR", List.of("STORE_USER", "CENTRAL_STORE_USER"));
        rolesPerTeamRole.put("BILLING", List.of("STORE_USER", "CENTRAL_STORE_USER"));
        rolesPerTeamRole.put("STORE_STAFF", List.of("STORE_USER", "CENTRAL_STORE_USER"));
        rolesPerTeamRole.put("FRONT_DESK", List.of("STORE_USER"));
        rolesPerTeamRole.put("COUNSELLOR", List.of("STORE_USER"));
        rolesPerTeamRole.put("VISITING_DOCTOR", List.of("STORE_USER"));
        for (var e : rolesPerTeamRole.entrySet()) {
            for (String storeRole : e.getValue()) {
                Pn p = crew(clinic, e.getKey());
                String store = "CENTRAL_STORE_USER".equals(storeRole) ? idOf(central) : idOf(ward);
                JsonNode mapped = mapStaff(clinic, store, p, storeRole);
                assertThat(mapped.get("access").get("receiveScheduleX").asBoolean()).as(e.getKey() + " as " + storeRole)
                        .isEqualTo("PHARMACIST".equals(e.getKey()));
                assertThat(mapped.get("access").get("receive").asBoolean()).as("everybody mapped can receive").isTrue();
            }
        }
        // approver and delegate: only a pharmacist gets it
        for (String team : new String[] {"PHARMACIST", "NURSE", "DOCTOR"}) {
            Pn p = crew(clinic, team);
            JsonNode store = createStore(clinic, "Ward " + team, "W-" + team.charAt(0), "WARD");
            JsonNode mapped = mapStaff(clinic, idOf(store), p, "APPROVER");
            assertThat(mapped.get("access").get("receiveScheduleX").asBoolean()).as(team + " as approver").isEqualTo("PHARMACIST".equals(team));
            assertThat(mapped.get("access").get("uploadOpeningStock").asBoolean()).as(team + " approver upload").isEqualTo("PHARMACIST".equals(team));
        }
        // the owner, mapped like anybody, never gets Schedule X (pharmacist-only actions stay out of the owner's reach)
        Pn ownerAsPerson = pn(clinic, ownerStaff(clinic));
        JsonNode owned = mapStaff(clinic, idOf(ward), ownerAsPerson, "STORE_USER");
        assertThat(owned.get("access").get("receiveScheduleX").asBoolean()).isFalse();
        assertThat(contextOf(clinic.owner()).toString()).contains("\"receiveScheduleX\":false").doesNotContain("\"receiveScheduleX\":true");
    }

    @Test
    void aDelegateWhoseDatesHaveEndedIsShownEndedCountsAsStoreUserAndNoLongerApproves() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn hod = crew(clinic, "NURSE");
        Pn deputy = crew(clinic, "DOCTOR");
        mapStaff(clinic, idOf(stores[1]), hod, "APPROVER");
        JsonNode mapped = ok(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(delegateBody(deputy, todayIst(), todayIst().plusDays(5))), 201);
        assertAccess(mapped.get("access"), true, true, true, false, true, false, "current delegate");
        commitAsTenant(clinic.tenantId(), "update pharmacy.store_staff set delegate_from = ?::date, delegate_to = ?::date where id = ?::uuid",
                todayIst().minusDays(10).toString(), todayIst().minusDays(5).toString(), idOf(mapped));
        JsonNode row = mappingOf(clinic, idOf(stores[1]), deputy);
        assertThat(row.get("storeRole").asText()).isEqualTo("DELEGATE");
        assertThat(row.get("delegateState").asText()).isEqualTo("ENDED");
        assertThat(row.get("effectiveStoreRole").asText()).as("the person remains mapped as a Store user").isEqualTo("STORE_USER");
        assertAccess(row.get("access"), true, false, true, false, false, false, "ended delegate");
        assertThat(menuOf(deputy.auth())).as("still mapped").contains("pharmacy");
        // the same ended dates sent back unchanged are not refused
        assertThat(ok(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(mapped)), clinic.owner(),
                js(Map.of("version", row.get("version").asLong(), "storeRole", "DELEGATE", "delegateFrom", todayIst().minusDays(10).toString(),
                        "delegateTo", todayIst().minusDays(5).toString())), 200).get("delegateState").asText()).isEqualTo("ENDED");
    }

    @Test
    void aMappingThatNoLongerFitsTheTeamRoleShowsNeedsReviewAndCountsAsStoreUser() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn doctor = crew(clinic, "DOCTOR");
        mapStaff(clinic, idOf(stores[1]), doctor, "APPROVER");
        assertThat(mappingOf(clinic, idOf(stores[1]), doctor).get("needsReview").asBoolean()).isFalse();
        commitAsTenant(clinic.tenantId(), "update platform.membership set role = 'FRONT_DESK' where id = ?::uuid", doctor.membershipId().toString());
        JsonNode row = mappingOf(clinic, idOf(stores[1]), doctor);
        assertThat(row.get("needsReview").asBoolean()).isTrue();
        assertThat(row.get("storeRole").asText()).isEqualTo("APPROVER");
        assertThat(row.get("effectiveStoreRole").asText()).isEqualTo("STORE_USER");
        assertThat(row.get("access").get("approve").asBoolean()).isFalse();
    }

    // ---------------- list ----------------

    @Test
    void theListShowsTheHodFirstThenTheDelegateThenByNameWithNameAndTheSahajRoleLabel() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn zed = crew(clinic, "NURSE", "Zed Nurse");
        Pn amy = crew(clinic, "STORE_STAFF", "Amy Keeper");
        Pn hod = crew(clinic, "DOCTOR", "Dr Hod");
        Pn deputy = crew(clinic, "PHARMACIST", "Deputy Pharmacist");
        mapStaff(clinic, idOf(stores[1]), zed, "STORE_USER");
        mapStaff(clinic, idOf(stores[1]), amy, "STORE_USER");
        ok(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(delegateBody(deputy, todayIst(), todayIst().plusDays(7))), 201);
        mapStaff(clinic, idOf(stores[1]), hod, "APPROVER");
        JsonNode items = ok(HttpMethod.GET, staffUrl(idOf(stores[1])), clinic.owner(), null, 200).get("items");
        List<String> names = new ArrayList<>();
        items.forEach(i -> names.add(i.get("name").asText()));
        assertThat(names).containsExactly("Dr Hod", "Deputy Pharmacist", "Amy Keeper", "Zed Nurse");
        assertThat(items.get(0).get("isHod").asBoolean()).isTrue();
        assertThat(items.get(0).get("roleLabel").asText()).isEqualTo("Doctor");
        assertThat(items.get(2).get("roleLabel").asText()).isEqualTo("Store staff");
        assertThat(items.toString()).as("no mobile numbers in the table").doesNotContain(zed.mobile()).doesNotContain(amy.mobile());
    }

    // ---------------- remove and re-map ----------------

    @Test
    void removingAMappingIsAtOnceKeepsTheRowAsHistoryAuditsWhoRemovedWhomAndIsSafeToRepeat() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        JsonNode mapped = mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode removed = ok(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(mapped)) + "/remove", clinic.owner(), null, 200);
        assertThat(removed.get("status").asText()).isEqualTo("REMOVED");
        assertThat(removed.get("access").toString()).as("no access for a removed mapping").doesNotContain("true");
        assertThat(ok(HttpMethod.GET, staffUrl(idOf(stores[1])), clinic.owner(), null, 200).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, staffUrl(idOf(stores[1])) + "?includeRemoved=true", clinic.owner(), null, 200).get("items")).hasSize(1);
        assertType(problem(call(HttpMethod.GET, CONTEXT, nurse.auth(), null), 403), "forbidden"); // the person loses the store at once
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_STAFF_REMOVED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(3)).contains(nurse.membershipId().toString()).contains(clinic.ownerUserId().toString()).contains("REMOVED");
        assertThat(audit.get(0).get(2)).doesNotContain(nurse.mobile());
        ok(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(mapped)) + "/remove", clinic.owner(), null, 200);
        assertThat(auditRowsOf(clinic, "PHARMACY_STAFF_REMOVED")).as("a repeat writes nothing").hasSize(1);
        assertThat(pharmacyRows(clinic, "store_staff")).as("never hard-deleted").isOne();
    }

    @Test
    void mappingAgainAPersonWhoWasRemovedReactivatesTheSameRowWithTheNewRole() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        JsonNode first = mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        ok(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(first)) + "/remove", clinic.owner(), null, 200);
        JsonNode again = mapStaff(clinic, idOf(stores[1]), nurse, "APPROVER");
        assertThat(again.get("id").asText()).as("one row per person and store").isEqualTo(idOf(first));
        assertThat(again.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(again.get("storeRole").asText()).isEqualTo("APPROVER");
        assertThat(again.get("version").asLong()).isGreaterThan(first.get("version").asLong());
        assertThat(count(clinic, "select count(*) from pharmacy.store_staff where store_id = ?::uuid and membership_id = ?::uuid",
                idOf(stores[1]), nurse.membershipId().toString())).isOne();
        assertThat(contextOf(nurse.auth()).get("stores")).hasSize(1);
        assertThat(menuOf(nurse.auth())).contains("pharmacy");
        assertThat(auditRowsOf(clinic, "PHARMACY_STAFF_MAPPED")).hasSize(2);
        // the second mapping of the same active person is still 409
        assertType(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(mapBody(nurse, "STORE_USER"))), 409), "already-mapped");
    }

    @Test
    void aMappingOfAnotherStoreOrAnotherClinicOrAnUnknownIdIs404OnChangeAndRemove() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Clinic other = pharmacyClinic();
        JsonNode[] otherStores = centralAndPharmacy(other);
        Pn nurse = crew(clinic, "NURSE");
        Pn foreign = crew(other, "NURSE");
        JsonNode mapped = mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode foreignMapping = mapStaff(other, idOf(otherStores[1]), foreign, "STORE_USER");
        Map<String, Object> change = changeBody(mapped, "STORE_USER");
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(ward), idOf(mapped)), clinic.owner(), js(change)), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, staffUrl(idOf(ward), idOf(mapped)) + "/remove", clinic.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), UUID.randomUUID()), clinic.owner(), js(change)), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(foreignMapping)), clinic.owner(), js(change)), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(foreignMapping)) + "/remove", clinic.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(otherStores[1]), idOf(foreignMapping)), clinic.owner(), js(change)), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), "not-a-uuid"), clinic.owner(), js(change)), 400), "bad-request");
        assertThat(ok(HttpMethod.GET, staffUrl(idOf(otherStores[1])), other.owner(), null, 200).get("items").get(0).get("status").asText()).isEqualTo("ACTIVE");
    }

    // ---------------- change ----------------

    @Test
    void changingTheRoleBumpsTheVersionAuditsBeforeAndAfterAndAStaleVersionIs409SettingsChangedWithCurrent() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        JsonNode mapped = mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        JsonNode saved = ok(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(mapped)), clinic.owner(), js(changeBody(mapped, "APPROVER")), 200);
        assertThat(saved.get("storeRole").asText()).isEqualTo("APPROVER");
        assertThat(saved.get("isHod").asBoolean()).isTrue();
        assertThat(saved.get("version").asLong()).isEqualTo(mapped.get("version").asLong() + 1);
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_STAFF_MAPPING_CHANGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(2)).contains("STORE_USER");
        assertThat(audit.get(0).get(3)).contains("APPROVER");
        JsonNode stale = problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(mapped)), clinic.owner(), js(changeBody(mapped, "STORE_USER"))), 409);
        assertType(stale, "settings-changed");
        assertThat(stale.get("current").get("storeRole").asText()).isEqualTo("APPROVER");
        assertThat(mappingOf(clinic, idOf(stores[1]), nurse).get("storeRole").asText()).isEqualTo("APPROVER");
        // the same values again change nothing and write nothing
        ok(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(mapped)), clinic.owner(), js(changeBody(saved, "APPROVER")), 200);
        assertThat(auditRowsOf(clinic, "PHARMACY_STAFF_MAPPING_CHANGED")).hasSize(1);
    }

    @Test
    void changingToApproverWhileTheStoreHasOneIs409AChangeToARoleThePersonMayNotHoldIs400AndAnEndedDelegationIsDoneByStoreUser() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn hod = crew(clinic, "NURSE");
        Pn other = crew(clinic, "DOCTOR");
        Pn keeper = crew(clinic, "STORE_STAFF");
        Pn deputy = crew(clinic, "PHARMACIST");
        mapStaff(clinic, idOf(stores[1]), hod, "APPROVER");
        JsonNode otherMapping = mapStaff(clinic, idOf(stores[1]), other, "STORE_USER");
        JsonNode keeperMapping = mapStaff(clinic, idOf(stores[1]), keeper, "STORE_USER");
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(otherMapping)), clinic.owner(), js(changeBody(otherMapping, "APPROVER"))), 409),
                "approver-exists");
        assertFieldMessage(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(keeperMapping)), clinic.owner(), js(changeBody(keeperMapping, "APPROVER"))), 400),
                "storeRole", BAD_ROLE);
        assertFieldMessage(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(keeperMapping)), clinic.owner(), js(changeBody(keeperMapping, "CENTRAL_STORE_USER"))), 400),
                "storeRole", "Central Store user is only for the Central Store");
        assertFieldMessage(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(otherMapping)), clinic.owner(), "{\"storeRole\":\"STORE_USER\"}"), 400),
                "version", "Reload the page and try again.");
        JsonNode delegate = ok(HttpMethod.POST, staffUrl(idOf(stores[1])), clinic.owner(), js(delegateBody(deputy, todayIst(), todayIst().plusDays(20))), 201);
        JsonNode ended = ok(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(delegate)), clinic.owner(), js(changeBody(delegate, "STORE_USER")), 200);
        assertThat(ended.get("storeRole").asText()).isEqualTo("STORE_USER");
        assertThat(ended.get("delegateFrom").isNull()).isTrue();
        // a removed mapping cannot be changed
        ok(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(otherMapping)) + "/remove", clinic.owner(), null, 200);
        assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(otherMapping)), clinic.owner(), js(changeBody(otherMapping, "STORE_USER"))), 404), "not-found");
    }

    // ---------------- roles ----------------

    @Test
    void everyMappingChangeByANonOwnerIs403ThePharmacistReadsTheTableAndNothingIsWritten() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn nurse = crew(clinic, "NURSE");
        JsonNode mapped = mapStaff(clinic, idOf(stores[1]), nurse, "STORE_USER");
        long audits = count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        Pn centralUser = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(stores[0]), centralUser, "CENTRAL_STORE_USER");
        for (Pn who : new Pn[] {pharmacist, centralUser, nurse, crew(clinic, "FRONT_DESK"), crew(clinic, "DOCTOR")}) {
            assertType(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1])), who.auth(), js(mapBody(who, "STORE_USER"))), 403), "forbidden");
            assertType(problem(call(HttpMethod.PUT, staffUrl(idOf(stores[1]), idOf(mapped)), who.auth(), js(changeBody(mapped, "APPROVER"))), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, staffUrl(idOf(stores[1]), idOf(mapped)) + "/remove", who.auth(), null), 403), "forbidden");
        }
        assertThat(ok(HttpMethod.GET, staffUrl(idOf(stores[1])), pharmacist.auth(), null, 200).get("items")).hasSize(1);
        assertThat(ok(HttpMethod.GET, staffUrl(idOf(stores[1])), centralUser.auth(), null, 200).get("items")).hasSize(1);
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'")).isEqualTo(audits + 1)
                .as("only the mapping made in this test after the baseline");
        assertThat(mappingOf(clinic, idOf(stores[1]), nurse).get("status").asText()).isEqualTo("ACTIVE");
    }
}
