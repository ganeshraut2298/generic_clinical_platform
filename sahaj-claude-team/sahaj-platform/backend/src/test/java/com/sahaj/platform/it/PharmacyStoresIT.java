package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-3 stores on the real database: the Central Store first, one per unit, defaults, validation, duplicates, HOD and
 * delegate, change, switch-off, locks, read access, tenant isolation and concurrent creates. Test plan:
 * docs/test-plans/SAH-78.md (T78-3-*).
 */
@OnTestDatabase
class PharmacyStoresIT extends Sah78Support {

    private static Map<String, Object> delegate(Pn person, LocalDate from, LocalDate to) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("membershipId", person.membershipId().toString());
        d.put("from", from == null ? null : from.toString());
        d.put("to", to == null ? null : to.toString());
        return d;
    }

    private static Map<String, Object> updateBody(JsonNode store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", store.get("version").asLong());
        m.put("name", store.get("name").asText());
        m.put("code", store.get("code").asText());
        m.put("type", store.get("type").asText());
        m.put("saleAllowed", store.get("saleAllowed").asBoolean());
        m.put("consumptionAllowed", store.get("consumptionAllowed").asBoolean());
        m.put("hodMembershipId", store.get("hod").isNull() ? null : store.get("hod").get("membershipId").asText());
        m.put("delegate", null);
        return m;
    }

    private JsonNode getStore(Clinic clinic, String id) throws Exception {
        return ok(HttpMethod.GET, STORES + "/" + id, clinic.owner(), null, 200);
    }

    private JsonNode update(Clinic clinic, String id, Map<String, Object> body, int status) throws Exception {
        return ok(HttpMethod.PUT, STORES + "/" + id, clinic.owner(), js(body), status);
    }

    private void setHasStock(Clinic clinic, String storeId) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update pharmacy.store set has_stock = true where id = ?::uuid", storeId);
    }

    // ---------------- list and create ----------------

    @Test
    void anEmptyUnitListsNoStoreAndNoCentralStore() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode list = ok(HttpMethod.GET, STORES, clinic.owner(), null, 200);
        assertThat(list.get("items")).isEmpty();
        assertThat(list.get("centralStoreId").isNull()).isTrue();
    }

    @Test
    void theCentralStoreComesFirstAndEveryOtherStoreIssuesFromItWithTheDocumentedDefaults() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        assertThat(central.get("type").asText()).isEqualTo("CENTRAL");
        assertThat(central.get("issuesFromStoreId").isNull()).isTrue();
        assertThat(central.get("saleAllowed").asBoolean()).isFalse();
        assertThat(central.get("consumptionAllowed").asBoolean()).isFalse();
        assertThat(central.get("approverState").asText()).as("No approver set").isEqualTo("NONE");
        assertThat(central.get("staffCount").asInt()).isZero();
        assertThat(central.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(central.get("hasStock").asBoolean()).isFalse();
        assertThat(central.get("version").asLong()).isZero();
        assertThat(central.get("lockedFields")).isEmpty();

        JsonNode pharmacy = createStore(clinic, "Main Pharmacy", "PHARM", "PHARMACY");
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        JsonNode sub = createStore(clinic, "OPD Store", "OPD", "SUB_STORE");
        assertThat(pharmacy.get("saleAllowed").asBoolean()).as("Sale defaults Yes for a Pharmacy").isTrue();
        assertThat(pharmacy.get("consumptionAllowed").asBoolean()).isFalse();
        assertThat(ward.get("saleAllowed").asBoolean()).isFalse();
        assertThat(ward.get("consumptionAllowed").asBoolean()).as("Consumption defaults Yes for a Ward store").isTrue();
        assertThat(sub.get("saleAllowed").asBoolean()).isFalse();
        assertThat(sub.get("consumptionAllowed").asBoolean()).isFalse();
        for (JsonNode s : new JsonNode[] {pharmacy, ward, sub}) {
            assertThat(s.get("issuesFromStoreId").asText()).isEqualTo(idOf(central));
            assertThat(s.get("issuesFromName").asText()).isEqualTo("Central Store");
        }
        // explicit values win over the defaults
        Map<String, Object> explicit = storeBody("Sale Ward", "SW", "WARD");
        explicit.put("saleAllowed", true);
        explicit.put("consumptionAllowed", false);
        JsonNode both = ok(HttpMethod.POST, STORES, clinic.owner(), js(explicit), 201);
        assertThat(both.get("saleAllowed").asBoolean()).isTrue();
        assertThat(both.get("consumptionAllowed").asBoolean()).isFalse();

        JsonNode list = ok(HttpMethod.GET, STORES, clinic.owner(), null, 200);
        assertThat(list.get("centralStoreId").asText()).isEqualTo(idOf(central));
        List<String> names = new ArrayList<>();
        list.get("items").forEach(i -> names.add(i.get("name").asText()));
        assertThat(names).as("Central first, then by name").containsExactly("Central Store", "Main Pharmacy", "OPD Store", "Sale Ward", "Ward 3B");
        assertThat(auditRowsOf(clinic, "PHARMACY_STORE_CREATED")).hasSize(5);
        assertThat(auditRowsOf(clinic, "PHARMACY_STORE_CREATED").get(1).get(3)).contains("Main Pharmacy").contains("PHARMACY");
    }

    @Test
    void anotherStoreBeforeTheCentralStoreIs409CreateTheCentralStoreFirstAndASecondCentralStoreIs409() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode missing = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("Main Pharmacy", "PHARM", "PHARMACY"))), 409);
        assertType(missing, "central-store-missing");
        assertThat(missing.get("detail").asText()).isEqualTo(CENTRAL_MISSING_TEXT);
        assertThat(pharmacyRows(clinic, "store")).isZero();
        centralStore(clinic);
        JsonNode exists = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("Second Central", "CS2", "CENTRAL"))), 409);
        assertType(exists, "central-store-exists");
        assertThat(exists.get("detail").asText()).isEqualTo(CENTRAL_EXISTS_TEXT);
        assertThat(pharmacyRows(clinic, "store")).isOne();
    }

    @Test
    void theIssuesFromAndTheStatusCannotBeSetByTheClient() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        Map<String, Object> body = storeBody("Main Pharmacy", "PHARM", "PHARMACY");
        body.put("issuesFromStoreId", idOf(central));
        assertType(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(body)), 400), "validation");
        Map<String, Object> withStatus = storeBody("Main Pharmacy", "PHARM", "PHARMACY");
        withStatus.put("status", "INACTIVE");
        withStatus.put("hasStock", true);
        assertType(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(withStatus)), 400), "validation");
        assertThat(pharmacyRows(clinic, "store")).isOne();
    }

    // ---------------- validation ----------------

    @Test
    void emptyOrBadNameCodeAndTypeAreRefusedTogetherWithTheFieldMessages() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        JsonNode empty = problem(call(HttpMethod.POST, STORES, clinic.owner(), "{\"name\":\"  \",\"code\":\"\"}"), 400);
        assertType(empty, "validation");
        assertFieldMessage(empty, "name", "Enter the store name");
        assertFieldMessage(empty, "code", "Enter the store code");
        assertFieldMessage(empty, "type", "Choose the store type");
        JsonNode longName = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("N".repeat(61), "OK", "WARD"))), 400);
        assertFieldMessage(longName, "name", "Keep the store name to 60 characters");
        for (String badCode : new String[] {"ELEVENCHARS", "AB C", "A_B", "A/B", "सहज"}) {
            JsonNode p = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("Ward " + badCode.length(), badCode, "WARD"))), 400);
            assertFieldMessage(p, "code", "Use up to 10 letters, digits or hyphens");
        }
        assertThat(pharmacyRows(clinic, "store")).as("nothing was saved").isOne();
        // the limits themselves are accepted: 60 characters, 10 characters, letters digits and hyphen, lower case upper-cased
        JsonNode edge = createStore(clinic, "N".repeat(60), "a1-b2-c3-d", "WARD");
        assertThat(edge.get("code").asText()).isEqualTo("A1-B2-C3-D");
    }

    @Test
    void aCodeOfExactlyTenCharactersAndAMixedCaseCodeAreAcceptedAndStoredUpperCaseAndTheNameIsTrimmed() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        JsonNode s = createStore(clinic, "  Ward Ten  ", "w-1234abcd", "WARD");
        assertThat(s.get("code").asText()).isEqualTo("W-1234ABCD");
        assertThat(s.get("name").asText()).isEqualTo("Ward Ten");
    }

    @Test
    void aRepeatedNameOrCodeIgnoringCapitalsIs409EvenAgainstASwitchedOffStoreAndAnotherClinicMayUseTheSame() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        JsonNode pharmacy = createStore(clinic, "Main Pharmacy", "PHARM", "PHARMACY");
        JsonNode name = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("MAIN pharmacy", "OTHER", "WARD"))), 409);
        assertType(name, "store-name-in-use");
        assertThat(name.get("detail").asText()).isEqualTo(NAME_IN_USE_TEXT);
        JsonNode code = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("Other", "pharm", "WARD"))), 409);
        assertType(code, "store-code-in-use");
        assertThat(code.get("detail").asText()).isEqualTo("That store code is already used");
        ok(HttpMethod.POST, STORES + "/" + idOf(pharmacy) + "/switch-off", clinic.owner(), null, 200);
        assertType(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("Main Pharmacy", "NEWCODE", "WARD"))), 409), "store-name-in-use");
        assertThat(pharmacyRows(clinic, "store")).isEqualTo(2);
        Clinic other = pharmacyClinic();
        centralStore(other);
        createStore(other, "Main Pharmacy", "PHARM", "PHARMACY"); // 201: names and codes are per unit
    }

    // ---------------- HOD and delegate ----------------

    @Test
    void anHodChosenOnTheStoreIsMappedAsApproverInTheSameStepAndTheStoreShowsThem() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        Pn nurse = crew(clinic, "NURSE", "Nina Nurse");
        Map<String, Object> body = storeBody("Ward 3B", "W3B", "WARD");
        body.put("hodMembershipId", nurse.membershipId().toString());
        JsonNode ward = ok(HttpMethod.POST, STORES, clinic.owner(), js(body), 201);
        assertThat(ward.get("approverState").asText()).isEqualTo("SET");
        assertThat(ward.get("hod").get("membershipId").asText()).isEqualTo(nurse.membershipId().toString());
        assertThat(ward.get("hod").get("name").asText()).isEqualTo("Nina Nurse");
        assertThat(ward.get("hod").get("roleLabel").asText()).isEqualTo("Nurse");
        assertThat(ward.get("hod").get("canSignIn").asBoolean()).isTrue();
        assertThat(ward.get("staffCount").asInt()).isOne();
        JsonNode row = mappingOf(clinic, idOf(ward), nurse);
        assertThat(row.get("storeRole").asText()).isEqualTo("APPROVER");
        assertThat(row.get("isHod").asBoolean()).isTrue();
        assertThat(row.get("access").get("approve").asBoolean()).isTrue();
        assertThat(auditRowsOf(clinic, "PHARMACY_STAFF_MAPPED")).hasSize(1);
        assertThat(menuOf(nurse.auth())).as("the HOD is mapped, so the menu entry shows").contains("pharmacy");
    }

    @Test
    void onlyAnOwnerDoctorNurseOrPharmacistMayBeHodOrDelegateAndOthersAreRefusedWithAFieldMessage() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        String message = "Choose an active owner, doctor, nurse or pharmacist";
        for (String role : new String[] {"FRONT_DESK", "BILLING", "COUNSELLOR", "VISITING_DOCTOR", "STORE_STAFF"}) {
            Pn p = crew(clinic, role);
            Map<String, Object> asHod = storeBody("Ward " + role, "W" + role.length() + role.charAt(0), "WARD");
            asHod.put("hodMembershipId", p.membershipId().toString());
            assertFieldMessage(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(asHod)), 400), "hodMembershipId", message);
            Map<String, Object> asDelegate = storeBody("Ward D" + role, "D" + role.length() + role.charAt(0), "WARD");
            asDelegate.put("delegate", delegate(p, todayIst(), todayIst().plusDays(5)));
            assertFieldMessage(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(asDelegate)), 400), "delegate.membershipId", message);
        }
        // an unknown member and another clinic's member are refused the same way
        Map<String, Object> unknown = storeBody("Ward U", "WU", "WARD");
        unknown.put("hodMembershipId", UUID.randomUUID().toString());
        assertFieldMessage(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(unknown)), 400), "hodMembershipId", message);
        Clinic other = pharmacyClinic();
        Pn foreign = crew(other, "NURSE");
        Map<String, Object> foreignBody = storeBody("Ward F", "WF", "WARD");
        foreignBody.put("hodMembershipId", foreign.membershipId().toString());
        assertFieldMessage(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(foreignBody)), 400), "hodMembershipId", message);
        assertThat(pharmacyRows(clinic, "store")).as("none of them was saved").isOne();
        assertThat(pharmacyRows(clinic, "store_staff")).isZero();
        assertThat(pharmacyRows(other, "store_staff")).isZero();
    }

    @Test
    void aDelegateNeedsDatesInOrderAtMostNinetyDaysNotInThePastAndMustBeADifferentPersonFromTheHod() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        Pn hod = crew(clinic, "NURSE");
        Pn deputy = crew(clinic, "DOCTOR");
        LocalDate today = todayIst();
        Object[][] cases = {
            {delegate(deputy, null, today.plusDays(3)), "delegate.from", "Enter the start date"},
            {delegate(deputy, today, null), "delegate.to", "Enter the end date"},
            {delegate(deputy, today.plusDays(5), today.plusDays(2)), "delegate.to", "The end date must be on or after the start date"},
            {delegate(deputy, today, today.plusDays(90)), "delegate.to", "A delegation can be at most 90 days"}, // 91 days counting both ends
            {delegate(deputy, today, today.plusDays(91)), "delegate.to", "A delegation can be at most 90 days"},
            {delegate(deputy, today.minusDays(10), today.minusDays(5)), "delegate.to", "The end date cannot be in the past"},
            {delegate(hod, today, today.plusDays(5)), "delegate.membershipId", "The delegate must be a different person from the approver"}};
        int n = 0;
        for (Object[] c : cases) {
            Map<String, Object> body = storeBody("Ward " + n, "W" + n, "WARD");
            body.put("hodMembershipId", hod.membershipId().toString());
            body.put("delegate", c[0]);
            JsonNode p = problem(call(HttpMethod.POST, STORES, clinic.owner(), js(body)), 400);
            assertFieldMessage(p, (String) c[1], (String) c[2]);
            n++;
        }
        assertThat(pharmacyRows(clinic, "store")).isOne();
        // 90 calendar days counting both ends (to = from + 89) is allowed
        Map<String, Object> ok90 = storeBody("Ward Ninety", "W90", "WARD");
        ok90.put("hodMembershipId", hod.membershipId().toString());
        ok90.put("delegate", delegate(deputy, today, today.plusDays(89)));
        JsonNode ward = ok(HttpMethod.POST, STORES, clinic.owner(), js(ok90), 201);
        assertThat(ward.get("delegate").get("state").asText()).isEqualTo("CURRENT");
        assertThat(ward.get("delegate").get("from").asText()).isEqualTo(today.toString());
        assertThat(ward.get("delegate").get("to").asText()).isEqualTo(today.plusDays(89).toString());
    }

    @Test
    void aStoreWithNoActiveHodShowsNoApproverSetAlsoWhenTheHodLosesAccessOrIsRemoved() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        Pn hod = crew(clinic, "DOCTOR");
        Map<String, Object> body = storeBody("Ward 3B", "W3B", "WARD");
        body.put("hodMembershipId", hod.membershipId().toString());
        JsonNode ward = ok(HttpMethod.POST, STORES, clinic.owner(), js(body), 201);
        assertThat(ward.get("approverState").asText()).isEqualTo("SET");
        ok(HttpMethod.POST, TEAM + "/" + hod.membershipId() + "/deactivate", clinic.owner(), null, 200);
        JsonNode after = getStore(clinic, idOf(ward));
        assertThat(after.get("approverState").asText()).as("the person's access was switched off").isEqualTo("NONE");
        assertThat(after.get("hod").get("canSignIn").asBoolean()).isFalse();
        assertThat(after.get("staffCount").asInt()).as("a mapping of a person who cannot sign in does not count").isZero();
        // a removed HOD mapping also leaves no approver
        Pn second = crew(clinic, "NURSE");
        Map<String, Object> body2 = storeBody("Ward 4", "W4", "WARD");
        body2.put("hodMembershipId", second.membershipId().toString());
        JsonNode ward2 = ok(HttpMethod.POST, STORES, clinic.owner(), js(body2), 201);
        JsonNode row = mappingOf(clinic, idOf(ward2), second);
        ok(HttpMethod.POST, staffUrl(idOf(ward2), idOf(row)) + "/remove", clinic.owner(), null, 200);
        assertThat(getStore(clinic, idOf(ward2)).get("approverState").asText()).isEqualTo("NONE");
    }

    // ---------------- change ----------------

    @Test
    void changingAStoreBumpsTheVersionAuditsBeforeAndAfterAndAStaleVersionIs409SettingsChangedWithCurrent() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Map<String, Object> change = updateBody(ward);
        change.put("name", "Ward 3B Store");
        change.put("saleAllowed", true);
        change.put("consumptionAllowed", false);
        JsonNode saved = update(clinic, idOf(ward), change, 200);
        assertThat(saved.get("name").asText()).isEqualTo("Ward 3B Store");
        assertThat(saved.get("saleAllowed").asBoolean()).isTrue();
        assertThat(saved.get("consumptionAllowed").asBoolean()).isFalse();
        assertThat(saved.get("version").asLong()).isEqualTo(ward.get("version").asLong() + 1);
        List<List<String>> changed = auditRowsOf(clinic, "PHARMACY_STORE_CHANGED");
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0).get(2)).contains("Ward 3B").contains("W3B");
        assertThat(changed.get(0).get(3)).contains("Ward 3B Store");
        // the old version again: someone else saved first
        Map<String, Object> stale = updateBody(ward);
        stale.put("name", "Overwritten");
        JsonNode p = problem(call(HttpMethod.PUT, STORES + "/" + idOf(ward), clinic.owner(), js(stale)), 409);
        assertType(p, "settings-changed");
        assertThat(p.get("current").get("name").asText()).isEqualTo("Ward 3B Store");
        assertThat(p.get("current").get("version").asLong()).isEqualTo(saved.get("version").asLong());
        assertThat(getStore(clinic, idOf(ward)).get("name").asText()).isEqualTo("Ward 3B Store");
    }

    @Test
    void aChangeThatOmitsFieldsTheContractRequiresIs400AndDoesNotSilentlyClearTheFlagsOrTheHod() throws Exception {
        // docs/api/pharmacy.yaml StoreUpdate: required [version, name, code, type, saleAllowed, consumptionAllowed, hodMembershipId, delegate]
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        Pn hod = crew(clinic, "NURSE");
        Map<String, Object> create = storeBody("Main Pharmacy", "PHARM", "PHARMACY");
        create.put("hodMembershipId", hod.membershipId().toString());
        JsonNode pharmacy = ok(HttpMethod.POST, STORES, clinic.owner(), js(create), 201);
        assertThat(pharmacy.get("saleAllowed").asBoolean()).isTrue();
        String partial = "{\"version\":" + pharmacy.get("version").asLong() + ",\"name\":\"Main Pharmacy\",\"code\":\"PHARM\",\"type\":\"PHARMACY\"}";
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(pharmacy), clinic.owner(), partial), 400), "validation");
        JsonNode after = getStore(clinic, idOf(pharmacy));
        assertThat(after.get("saleAllowed").asBoolean()).as("Sale to patients must not flip to No because the field was not sent").isTrue();
        assertThat(after.get("hod").isNull()).as("the HOD must not be dropped because the field was not sent").isFalse();
        assertThat(after.get("version").asLong()).isEqualTo(pharmacy.get("version").asLong());
    }

    @Test
    void renamingToAnotherStoresNameOrCodeIs409AndKeepingYourOwnNameAndCodeIsFine() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        JsonNode sub = createStore(clinic, "OPD Store", "OPD", "SUB_STORE");
        Map<String, Object> toName = updateBody(sub);
        toName.put("name", "ward 3b");
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(sub), clinic.owner(), js(toName)), 409), "store-name-in-use");
        Map<String, Object> toCode = updateBody(sub);
        toCode.put("code", "w3b");
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(sub), clinic.owner(), js(toCode)), 409), "store-code-in-use");
        update(clinic, idOf(ward), updateBody(ward), 200); // same name and code, nothing else
    }

    @Test
    void aNewHodDemotesTheOldOneToStoreUserAndEndingTheDelegationKeepsThePersonAsStoreUser() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        Pn first = crew(clinic, "NURSE", "First Nurse");
        Pn second = crew(clinic, "DOCTOR", "Second Doctor");
        Pn deputy = crew(clinic, "PHARMACIST", "Deputy Pharmacist");
        Map<String, Object> create = storeBody("Ward 3B", "W3B", "WARD");
        create.put("hodMembershipId", first.membershipId().toString());
        create.put("delegate", delegate(deputy, todayIst(), todayIst().plusDays(10)));
        JsonNode ward = ok(HttpMethod.POST, STORES, clinic.owner(), js(create), 201);
        assertThat(ward.get("delegate").get("membershipId").asText()).isEqualTo(deputy.membershipId().toString());
        Map<String, Object> change = updateBody(ward);
        change.put("hodMembershipId", second.membershipId().toString());
        change.put("delegate", null);
        JsonNode saved = update(clinic, idOf(ward), change, 200);
        assertThat(saved.get("hod").get("membershipId").asText()).isEqualTo(second.membershipId().toString());
        assertThat(saved.get("delegate").isNull()).isTrue();
        assertThat(mappingOf(clinic, idOf(ward), first).get("storeRole").asText()).as("kept, not removed").isEqualTo("STORE_USER");
        assertThat(mappingOf(clinic, idOf(ward), first).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(mappingOf(clinic, idOf(ward), deputy).get("storeRole").asText()).isEqualTo("STORE_USER");
        assertThat(mappingOf(clinic, idOf(ward), second).get("isHod").asBoolean()).isTrue();
        assertThat(saved.get("staffCount").asInt()).isEqualTo(3);
    }

    @Test
    void theTypeCannotMoveToOrFromTheCentralStoreWhileItWouldBreakTheRules() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Map<String, Object> toCentral = updateBody(ward);
        toCentral.put("type", "CENTRAL");
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(ward), clinic.owner(), js(toCentral)), 409), "central-store-exists");
        Map<String, Object> fromCentral = updateBody(central);
        fromCentral.put("type", "SUB_STORE");
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(central), clinic.owner(), js(fromCentral)), 409), "central-store-in-use");
        assertThat(getStore(clinic, idOf(central)).get("type").asText()).isEqualTo("CENTRAL");
        // a store may change between the three non-central types
        Map<String, Object> toPharmacy = updateBody(ward);
        toPharmacy.put("type", "PHARMACY");
        assertThat(update(clinic, idOf(ward), toPharmacy, 200).get("type").asText()).isEqualTo("PHARMACY");
    }

    // ---------------- switch off ----------------

    @Test
    void switchingAStoreOffKeepsItsHistoryHidesItFromTheDefaultListAndIsSafeToRepeat() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        Pn nurse = crew(clinic, "NURSE");
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        mapStaff(clinic, idOf(ward), nurse, "STORE_USER");
        mapStaff(clinic, idOf(central), nurse, "STORE_USER"); // a second store, so the nurse keeps access and the lost store answers 404
        JsonNode off = ok(HttpMethod.POST, STORES + "/" + idOf(ward) + "/switch-off", clinic.owner(), null, 200);
        assertThat(off.get("status").asText()).isEqualTo("INACTIVE");
        List<String> active = new ArrayList<>();
        ok(HttpMethod.GET, STORES, clinic.owner(), null, 200).get("items").forEach(i -> active.add(i.get("name").asText()));
        assertThat(active).containsExactly("Central Store");
        List<String> all = new ArrayList<>();
        ok(HttpMethod.GET, STORES + "?includeInactive=true", clinic.owner(), null, 200).get("items").forEach(i -> all.add(i.get("name").asText()));
        assertThat(all).containsExactly("Central Store", "Ward 3B");
        assertThat(getStore(clinic, idOf(ward)).get("status").asText()).as("a switched-off store is readable").isEqualTo("INACTIVE");
        assertThat(pharmacyRows(clinic, "store")).as("never hard-deleted").isEqualTo(2);
        assertThat(count(clinic, "select count(*) from pharmacy.store_staff where status = 'ACTIVE'"))
                .as("the mapping stays as a record").isEqualTo(2);
        assertThat(auditRowsOf(clinic, "PHARMACY_STORE_SWITCHED_OFF")).hasSize(1);
        ok(HttpMethod.POST, STORES + "/" + idOf(ward) + "/switch-off", clinic.owner(), null, 200);
        assertThat(auditRowsOf(clinic, "PHARMACY_STORE_SWITCHED_OFF")).as("a repeat writes nothing").hasSize(1);
        // not changeable, no one can be mapped to it
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(ward), clinic.owner(), js(updateBody(off))), 409), "store-inactive");
        assertType(problem(call(HttpMethod.POST, staffUrl(idOf(ward)), clinic.owner(), js(mapBody(crew(clinic, "NURSE"), "STORE_USER"))), 409), "store-inactive");
        assertType(problem(call(HttpMethod.PUT, WORKING_STORE, nurse.auth(), "{\"storeId\":\"" + idOf(ward) + "\"}"), 404), "not-found");
    }

    @Test
    void theCentralStoreCannotBeSwitchedOffWhileAnotherStoreIsActiveAndThenAnotherCentralStoreCanBeCreated() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode central = centralStore(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        JsonNode p = problem(call(HttpMethod.POST, STORES + "/" + idOf(central) + "/switch-off", clinic.owner(), null), 409);
        assertType(p, "central-store-in-use");
        assertThat(p.get("detail").asText()).isEqualTo("Switch off the other stores first.");
        ok(HttpMethod.POST, STORES + "/" + idOf(ward) + "/switch-off", clinic.owner(), null, 200);
        ok(HttpMethod.POST, STORES + "/" + idOf(central) + "/switch-off", clinic.owner(), null, 200);
        // with no active Central Store, other types are refused and a new Central Store is accepted
        assertType(problem(call(HttpMethod.POST, STORES, clinic.owner(), js(storeBody("New Ward", "NW", "WARD"))), 409), "central-store-missing");
        JsonNode again = createStore(clinic, "Central Store 2", "CS2", "CENTRAL");
        assertThat(ok(HttpMethod.GET, STORES, clinic.owner(), null, 200).get("centralStoreId").asText()).isEqualTo(idOf(again));
    }

    @Test
    void aStoreWithStockLocksTypeAndCodeCannotBeSwitchedOffButNameAndFlagsStillChange() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        setHasStock(clinic, idOf(ward));
        JsonNode view = getStore(clinic, idOf(ward));
        assertThat(view.get("hasStock").asBoolean()).isTrue();
        List<String> locked = new ArrayList<>();
        view.get("lockedFields").forEach(f -> locked.add(f.asText()));
        assertThat(locked).containsExactlyInAnyOrder("TYPE", "CODE", "ISSUES_FROM");
        Map<String, Object> type = updateBody(view);
        type.put("type", "PHARMACY");
        JsonNode p = problem(call(HttpMethod.PUT, STORES + "/" + idOf(ward), clinic.owner(), js(type)), 409);
        assertType(p, "store-has-stock");
        assertThat(p.get("detail").asText()).isEqualTo("This store has stock. Type and code cannot change.");
        assertThat(p.get("lockedFields").toString()).contains("TYPE").doesNotContain("CODE");
        Map<String, Object> code = updateBody(view);
        code.put("code", "NEWC");
        assertThat(problem(call(HttpMethod.PUT, STORES + "/" + idOf(ward), clinic.owner(), js(code)), 409).get("lockedFields").toString())
                .contains("CODE");
        Map<String, Object> name = updateBody(view);
        name.put("name", "Ward 3B Renamed");
        name.put("saleAllowed", true);
        assertThat(update(clinic, idOf(ward), name, 200).get("name").asText()).isEqualTo("Ward 3B Renamed");
        JsonNode off = problem(call(HttpMethod.POST, STORES + "/" + idOf(ward) + "/switch-off", clinic.owner(), null), 409);
        assertType(off, "store-has-stock");
        assertThat(off.get("detail").asText()).isEqualTo("This store has stock. Clear it in Inventory first.");
        assertThat(getStore(clinic, idOf(ward)).get("status").asText()).isEqualTo("ACTIVE");
    }

    // ---------------- read, roles, tenants ----------------

    @Test
    void aPharmacistReadsEveryStoreNotOnlyTheMappedOnesAndAnUnknownStoreIs404() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn pharmacist = crew(clinic, "PHARMACIST");
        mapStaff(clinic, idOf(stores[1]), pharmacist, "STORE_USER");
        JsonNode list = ok(HttpMethod.GET, STORES, pharmacist.auth(), null, 200);
        assertThat(list.get("items")).as("contract: SETUP_VIEW lists all stores of the unit").hasSize(3);
        ok(HttpMethod.GET, STORES + "/" + idOf(ward), pharmacist.auth(), null, 200);
        assertType(problem(call(HttpMethod.GET, STORES + "/" + UUID.randomUUID(), clinic.owner(), null), 404), "not-found");
    }

    @Test
    void anotherClinicsStoreIs404OnEveryOperationAndItsListNeverShowsMyStores() throws Exception {
        Clinic a = pharmacyClinic();
        Clinic b = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(a);
        centralStore(b);
        Pn nurseB = crew(b, "NURSE");
        String foreign = idOf(stores[1]);
        assertType(problem(call(HttpMethod.GET, STORES + "/" + foreign, b.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + foreign, b.owner(), js(updateBody(stores[1]))), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, STORES + "/" + foreign + "/switch-off", b.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, staffUrl(foreign), b.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, staffUrl(foreign), b.owner(), js(mapBody(nurseB, "STORE_USER"))), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, CANDIDATES + "?storeId=" + foreign, b.owner(), null), 404), "not-found");
        List<String> names = new ArrayList<>();
        ok(HttpMethod.GET, STORES, b.owner(), null, 200).get("items").forEach(i -> names.add(i.get("name").asText()));
        assertThat(names).containsExactly("Central Store");
        assertThat(ok(HttpMethod.GET, STORES + "/" + foreign, a.owner(), null, 200).get("status").asText()).as("A's store untouched").isEqualTo("ACTIVE");
        assertThat(pharmacyRows(b, "store")).isOne();
        // a malformed id is 400 bad-request, not a 404 or a 500
        assertType(problem(call(HttpMethod.GET, STORES + "/not-a-uuid", a.owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.PUT, STORES + "/12345", a.owner(), js(updateBody(stores[1]))), 400), "bad-request");
        assertType(problem(call(HttpMethod.POST, STORES + "/x/switch-off", a.owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.GET, STORES + "?includeInactive=maybe", a.owner(), null), 400), "validation");
    }

    @Test
    void everyChangeToStoresByANonOwnerIs403AndLeavesNothingBehind() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        long audits = count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'");
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "STORE_STAFF"}) {
            Pn p = crew(clinic, role);
            assertType(problem(call(HttpMethod.POST, STORES, p.auth(), js(storeBody("Ward X", "WX", "WARD"))), 403), "forbidden");
            assertType(problem(call(HttpMethod.PUT, STORES + "/" + idOf(stores[1]), p.auth(), js(updateBody(stores[1]))), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, STORES + "/" + idOf(stores[1]) + "/switch-off", p.auth(), null), 403), "forbidden");
        }
        assertThat(pharmacyRows(clinic, "store")).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from platform.audit_event where action like 'PHARMACY\\_%'")).isEqualTo(audits);
    }

    // ---------------- concurrency ----------------

    @Test
    void twoCentralStoreCreatesAtTheSameMomentOneIs201AndTheOtherIs409CentralStoreExists() throws Exception {
        Clinic clinic = pharmacyClinic();
        List<RequestPostProcessor> owners = List.of(clinic.owner(), crew(clinic, "OWNER", "Second owner").auth());
        List<String> answers = atTheSameMoment(HttpMethod.POST, STORES, owners,
                List.of(js(storeBody("Central One", "C1", "CENTRAL")), js(storeBody("Central Two", "C2", "CENTRAL"))));
        assertThat(statusCount(answers, 201)).as(answers.toString()).isOne();
        assertThat(statusCount(answers, 409)).as(answers.toString()).isOne();
        String loser = answers.stream().filter(a -> a.startsWith("409|")).findFirst().orElseThrow();
        assertType(bodyOf(loser), "central-store-exists");
        assertThat(count(clinic, "select count(*) from pharmacy.store where type = 'CENTRAL' and status = 'ACTIVE'")).isOne();
        assertThat(pharmacyRows(clinic, "unit")).as("one unit row, however many first writers").isOne();
    }

    @Test
    void twoCreatesOfTheSameNameAtTheSameMomentOneIs201AndTheOtherIs409NameInUse() throws Exception {
        Clinic clinic = pharmacyClinic();
        centralStore(clinic);
        List<RequestPostProcessor> owners = List.of(clinic.owner(), crew(clinic, "OWNER", "Second owner").auth());
        List<String> answers = atTheSameMoment(HttpMethod.POST, STORES, owners,
                List.of(js(storeBody("Ward 3B", "W3B", "WARD")), js(storeBody("ward 3b", "W3C", "WARD"))));
        assertThat(statusCount(answers, 201)).as(answers.toString()).isOne();
        assertThat(statusCount(answers, 409)).as(answers.toString()).isOne();
        assertType(bodyOf(answers.stream().filter(a -> a.startsWith("409|")).findFirst().orElseThrow()), "store-name-in-use");
        assertThat(pharmacyRows(clinic, "store")).isEqualTo(2);
    }
}
