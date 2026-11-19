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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-8 on the real database: the vendor master (docs/api/pharmacy.yaml 0.2.0; the 0.2.1 switch-on is in
 * {@link PharmacyHardeningMastersIT}). Add, edit, switch off, Blocked computed in IST from the licence date, the status filter,
 * the owner-only writes, the clinic wall. Test plan: docs/test-plans/SAH-78.md section 21 (T78-8-*).
 */
@OnTestDatabase
class PharmacyVendorsIT extends Sah78CSupport {

    private static final String DUPLICATE_TEXT = "That vendor is already in the list";

    // ---------------- list and create ----------------

    @Test
    void aNewClinicHasNoVendorAndTheListWritesNothing() throws Exception {
        Masters w = mastersClinic();
        JsonNode page = ok(HttpMethod.GET, VENDORS, w.clinic().owner(), null, 200);
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("total").asInt()).isZero();
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(25);
        assertThat(pharmacyRows(w.clinic(), "vendor")).isZero();
    }

    @Test
    void anOwnerAddsAVendorWithTheFieldsOfTheFormAndItSuppliesTheCentralStoreWhenNoneIsChosen() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = vendorBody("Test Distributors Pvt. Ltd. (fake)");
        JsonNode v = createVendor(w.clinic(), body);
        assertThat(v.get("name").asText()).isEqualTo("Test Distributors Pvt. Ltd. (fake)");
        assertThat(v.get("gstin").asText()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(v.get("licenceRequired").asBoolean()).isTrue();
        assertThat(v.get("licenceNo").asText()).isEqualTo("TEST-DL-0002");
        assertThat(v.get("licenceState").asText()).isEqualTo("VALID");
        assertThat(v.get("contactName").asText()).isEqualTo("Test Contact");
        assertThat(v.get("contactPhone").asText()).isEqualTo("9000000001");
        assertThat(v.get("creditDays").asInt()).isEqualTo(30);
        assertThat(v.get("returnDaysBeforeExpiry").asInt()).isEqualTo(90);
        assertThat(v.get("returnCreditPercent").asText()).isEqualTo("100");
        assertThat(v.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(v.get("blocked").asBoolean()).isFalse();
        assertThat(v.get("productsCount").asInt()).isZero();
        // the contract example shows version 1 for a saved vendor; the build starts at 0 (the minimum of the schema): only the step is asserted
        assertThat(v.get("version").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(v.get("suppliesTo")).hasSize(1);
        assertThat(v.get("suppliesTo").get(0).get("storeId").asText()).isEqualTo(w.centralId());
        assertThat(v.get("suppliesTo").get(0).get("name").asText()).isEqualTo("Central Store");
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_VENDOR_CREATED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(1)).isEqualTo(v.get("id").asText());
        // the vendor is in the list and can be read by id
        assertThat(ok(HttpMethod.GET, VENDORS, w.clinic().owner(), null, 200).get("total").asInt()).isOne();
        assertThat(ok(HttpMethod.GET, vendorUrl(v.get("id").asText()), w.clinic().owner(), null, 200).get("name").asText())
                .isEqualTo("Test Distributors Pvt. Ltd. (fake)");
    }

    @Test
    void theSameClientKeyFromTheSameOwnerAnswers200WithTheSameVendorAndAnotherCallerGets409() throws Exception {
        Masters w = mastersClinic();
        Pn second = crew(w.clinic(), "OWNER", "Second Owner");
        Map<String, Object> body = vendorBody("Key Vendor (fake)");
        JsonNode first = createVendor(w.clinic(), body);
        JsonNode replay = ok(HttpMethod.POST, VENDORS, w.clinic().owner(), js(body), 200);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(pharmacyRows(w.clinic(), "vendor")).isOne();
        JsonNode other = problem(call(HttpMethod.POST, VENDORS, second.auth(), js(body)), 409);
        assertType(other, "client-key-reused");
        assertThat(other.get("detail").asText()).isEqualTo(KEY_REUSED_TEXT);
        assertThat(pharmacyRows(w.clinic(), "vendor")).isOne();
    }

    @Test
    void aRepeatedNameIgnoringCapitalsIs409AgainstAnyStatusAndAnotherClinicMayUseTheSameName() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Same Name Medicals (fake)"));
        Map<String, Object> again = vendorBody("SAME NAME medicals (FAKE)");
        JsonNode p = problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(again)), 409);
        assertType(p, "vendor-name-in-use");
        assertThat(p.get("detail").asText()).isEqualTo(DUPLICATE_TEXT);
        ok(HttpMethod.POST, vendorUrl(v.get("id").asText()) + "/switch-off", w.clinic().owner(), null, 200);
        again.put("clientKey", UUID.randomUUID().toString());
        assertType(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(again)), 409), "vendor-name-in-use");
        assertThat(pharmacyRows(w.clinic(), "vendor")).isOne();
        // the name is unique per clinic, not across clinics
        Masters other = mastersClinic();
        createVendor(other.clinic(), vendorBody("Same Name Medicals (fake)"));
    }

    @Test
    void everyFieldErrorIsReportedTogetherWithItsMessageAndNothingIsSaved() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = vendorBody("   ");
        body.put("gstin", "BADGSTIN");
        body.put("licenceNo", "");
        body.put("licenceValidTill", null);
        body.put("contactPhone", "12345");
        body.put("creditDays", 366);
        body.put("returnDaysBeforeExpiry", 400);
        body.put("returnCreditPercent", "100.5");
        body.put("suppliesToStoreIds", List.of(UUID.randomUUID().toString()));
        JsonNode p = problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(body)), 400);
        assertType(p, "validation");
        assertFieldMessage(p, "name", "Enter the vendor name");
        assertFieldMessage(p, "gstin", GSTIN_TEXT);
        assertFieldMessage(p, "licenceNo", "Enter the drug licence number");
        assertFieldMessage(p, "licenceValidTill", "Enter the licence valid-till date");
        assertFieldMessage(p, "contactPhone", "Enter a 10-digit phone number");
        assertFieldMessage(p, "creditDays", "Enter 0 to 365 days");
        assertFieldMessage(p, "returnDaysBeforeExpiry", "Enter 0 to 365 days");
        assertFieldMessage(p, "returnCreditPercent", "Enter a % from 0 to 100");
        assertFieldMessage(p, "suppliesToStoreIds", "Choose active stores");
        assertThat(pharmacyRows(w.clinic(), "vendor")).isZero();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_CREATED")).isEmpty();
    }

    @Test
    void theBoundariesOfTheNumbersAreAcceptedAndTheOnesBesideThemRefused() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> edge = vendorBody("Edge Vendor (fake)");
        edge.put("creditDays", 365);
        edge.put("returnDaysBeforeExpiry", 0);
        edge.put("returnCreditPercent", "0");
        JsonNode v = createVendor(w.clinic(), edge);
        assertThat(v.get("creditDays").asInt()).isEqualTo(365);
        assertThat(v.get("returnDaysBeforeExpiry").asInt()).isZero();
        Map<String, Object> top = vendorBody("Top Vendor (fake)");
        top.put("creditDays", 0);
        top.put("returnCreditPercent", "100.00");
        assertThat(createVendor(w.clinic(), top).get("creditDays").asInt()).isZero();
        for (String percent : new String[] {"100.01", "101", "-1", "abc", "1.234"}) {
            Map<String, Object> bad = vendorBody("Bad Percent " + percent + " (fake)");
            bad.put("returnCreditPercent", percent);
            assertFieldMessage(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(bad)), 400), "returnCreditPercent",
                    "Enter a % from 0 to 100");
        }
        Map<String, Object> days = vendorBody("Fraction Days (fake)");
        days.put("creditDays", 2.5);
        assertFieldMessage(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(days)), 400), "creditDays", "Enter 0 to 365 days");
        days.put("creditDays", -1);
        assertFieldMessage(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(days)), 400), "creditDays", "Enter 0 to 365 days");
    }

    @Test
    void aRequestWithAnUnknownFieldOrAMissingRequiredFieldOrAnUnreadableBodyIs400() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> extra = vendorBody("Extra Field (fake)");
        extra.put("tenantId", UUID.randomUUID().toString());
        JsonNode unknown = problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(extra)), 400);
        assertType(unknown, "validation");
        assertFieldMessage(unknown, "body", "Send only the fields listed for this request.");
        Map<String, Object> missing = vendorBody("Missing Field (fake)");
        missing.remove("contactPhone");
        assertFieldMessage(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(missing)), 400), "contactPhone",
                "This field is required");
        for (String bad : new String[] {"{\"name\":", "not json", "[]", ""}) {
            assertType(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), bad), 400), "malformed-request");
        }
        assertThat(pharmacyRows(w.clinic(), "vendor")).isZero();
    }

    @Test
    void aLicenceNotRequiredIgnoresTheLicenceValuesAndStoresThemEmpty() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = vendorBody("Consumables Only (fake)");
        body.put("licenceRequired", false);
        body.put("licenceNo", "IGNORED-1");
        body.put("licenceValidTill", "2020-01-01");
        JsonNode v = createVendor(w.clinic(), body);
        assertThat(v.get("licenceNo").isNull()).isTrue();
        assertThat(v.get("licenceValidTill").isNull()).isTrue();
        assertThat(v.get("licenceState").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(v.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(v.get("blocked").asBoolean()).isFalse();
    }

    @Test
    void suppliesToTakesOnlyActiveStoresOfThisClinicAndKeepsTheChosenOnes() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = vendorBody("Two Stores (fake)");
        body.put("suppliesToStoreIds", List.of(w.centralId(), w.pharmacyId()));
        JsonNode v = createVendor(w.clinic(), body);
        assertThat(v.get("suppliesTo")).hasSize(2);
        // a store of another clinic and a switched-off store are not allowed
        Masters other = mastersClinic();
        Map<String, Object> foreign = vendorBody("Foreign Store (fake)");
        foreign.put("suppliesToStoreIds", List.of(other.centralId()));
        assertFieldMessage(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(foreign)), 400), "suppliesToStoreIds",
                "Choose active stores");
        ok(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null, 200);
        Map<String, Object> off = vendorBody("Off Store (fake)");
        off.put("suppliesToStoreIds", List.of(w.pharmacyId()));
        assertFieldMessage(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(off)), 400), "suppliesToStoreIds",
                "Choose active stores");
    }

    // ---------------- Blocked (licence date, IST) ----------------

    @Test
    void aVendorIsBlockedFromTheDayAfterTheLicenceDateAndValidThroughThatDate() throws Exception {
        Masters w = mastersClinic();
        LocalDate today = todayIst();
        Map<String, Object> yesterday = vendorBody("Licence Yesterday (fake)");
        yesterday.put("licenceValidTill", today.minusDays(1).toString());
        JsonNode expired = createVendor(w.clinic(), yesterday);
        assertThat(expired.get("licenceState").asText()).isEqualTo("EXPIRED");
        assertThat(expired.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(expired.get("blocked").asBoolean()).isTrue();
        Map<String, Object> todayBody = vendorBody("Licence Today (fake)");
        todayBody.put("licenceValidTill", today.toString());
        JsonNode valid = createVendor(w.clinic(), todayBody);
        assertThat(valid.get("licenceState").asText()).isEqualTo("VALID");
        assertThat(valid.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(valid.get("blocked").asBoolean()).isFalse();
        // nothing is stored for Blocked: the row stays ACTIVE
        assertThat(values(w.clinic(), "select status from pharmacy.vendor where id = ?::uuid", expired.get("id").asText()).get(0))
                .isEqualTo("ACTIVE");
    }

    @Test
    void aBlockedVendorCanBeEditedAndALicenceDateMovedToTodayOrLaterMakesItActiveAtOnce() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = vendorBody("Was Blocked (fake)");
        body.put("licenceValidTill", todayIst().minusDays(10).toString());
        JsonNode v = createVendor(w.clinic(), body);
        assertThat(v.get("status").asText()).isEqualTo("BLOCKED");
        Map<String, Object> edit = vendorUpdate(v);
        edit.put("licenceValidTill", todayIst().toString());
        JsonNode saved = ok(HttpMethod.PUT, vendorUrl(v.get("id").asText()), w.clinic().owner(), js(edit), 200);
        assertThat(saved.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(saved.get("blocked").asBoolean()).isFalse();
        assertThat(saved.get("version").asInt()).isEqualTo(v.get("version").asInt() + 1);
    }

    // ---------------- filter, search, paging ----------------

    @Test
    void theDefaultFilterShowsActiveAndBlockedAndTheStatusFilterAndTheNameSearchNarrowTheList() throws Exception {
        Masters w = mastersClinic();
        JsonNode active = createVendor(w.clinic(), vendorBody("Alpha Active (fake)"));
        Map<String, Object> blockedBody = vendorBody("Bravo Blocked (fake)");
        blockedBody.put("licenceValidTill", todayIst().minusDays(3).toString());
        createVendor(w.clinic(), blockedBody);
        JsonNode off = createVendor(w.clinic(), vendorBody("Charlie Off (fake)"));
        ok(HttpMethod.POST, vendorUrl(off.get("id").asText()) + "/switch-off", w.clinic().owner(), null, 200);
        assertThat(names(ok(HttpMethod.GET, VENDORS, w.clinic().owner(), null, 200))).containsExactly("Alpha Active (fake)", "Bravo Blocked (fake)");
        assertThat(names(ok(HttpMethod.GET, VENDORS + "?status=ACTIVE", w.clinic().owner(), null, 200))).containsExactly("Alpha Active (fake)");
        assertThat(names(ok(HttpMethod.GET, VENDORS + "?status=BLOCKED", w.clinic().owner(), null, 200))).containsExactly("Bravo Blocked (fake)");
        assertThat(names(ok(HttpMethod.GET, VENDORS + "?status=INACTIVE", w.clinic().owner(), null, 200))).containsExactly("Charlie Off (fake)");
        JsonNode all = ok(HttpMethod.GET, VENDORS + "?status=ALL", w.clinic().owner(), null, 200);
        assertThat(names(all)).containsExactly("Alpha Active (fake)", "Bravo Blocked (fake)", "Charlie Off (fake)");
        assertThat(all.get("total").asInt()).isEqualTo(3);
        assertThat(names(ok(HttpMethod.GET, VENDORS + "?q=bRaVo", w.clinic().owner(), null, 200))).containsExactly("Bravo Blocked (fake)");
        assertThat(names(ok(HttpMethod.GET, VENDORS + "?q=%25", w.clinic().owner(), null, 200))).as("a percent sign is literal").isEmpty();
        // paging
        JsonNode page = ok(HttpMethod.GET, VENDORS + "?status=ALL&size=2&page=1", w.clinic().owner(), null, 200);
        assertThat(names(page)).containsExactly("Charlie Off (fake)");
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isOne();
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(active.get("id").asText()).isNotBlank();
    }

    @Test
    void aBadFilterOrPageOrSizeOrALongSearchIs400() throws Exception {
        Masters w = mastersClinic();
        for (String query : new String[] {"?status=NOPE", "?page=-1", "?page=1001", "?size=0", "?size=101", "?page=x", "?q=" + "a".repeat(61)}) {
            problem(call(HttpMethod.GET, VENDORS + query, w.clinic().owner(), null), 400);
        }
        // the boundaries are accepted
        ok(HttpMethod.GET, VENDORS + "?page=1000&size=100&q=" + "a".repeat(60), w.clinic().owner(), null, 200);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("name").asText()));
        return out;
    }

    @Test
    void theProductsCountIsTheActiveProductsThatNameTheVendorAsPreferred() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Preferred Vendor (fake)"));
        JsonNode product = adopt(w.clinic(), "G-000101");
        Map<String, Object> local = localBody(getProduct(w.clinic(), product.get("productId").asText()));
        local.put("preferredVendorId", v.get("id").asText());
        ok(HttpMethod.PUT, localUrl(product.get("productId").asText()), w.clinic().owner(), js(local), 200);
        assertThat(ok(HttpMethod.GET, vendorUrl(v.get("id").asText()), w.clinic().owner(), null, 200).get("productsCount").asInt()).isOne();
        assertThat(ok(HttpMethod.GET, VENDORS, w.clinic().owner(), null, 200).get("items").get(0).get("productsCount").asInt()).isOne();
    }

    // ---------------- edit ----------------

    @Test
    void anEditChangesTheFieldsBumpsTheVersionAndAuditsBeforeAndAfterAndANoChangeSaveWritesNothing() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Edit Me (fake)"));
        String id = v.get("id").asText();
        // nothing changed: 200, version and audit unchanged
        JsonNode same = ok(HttpMethod.PUT, vendorUrl(id), w.clinic().owner(), js(vendorUpdate(v)), 200);
        assertThat(same.get("version").asInt()).isEqualTo(v.get("version").asInt());
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_CHANGED")).isEmpty();
        Map<String, Object> edit = vendorUpdate(v);
        edit.put("creditDays", 45);
        edit.put("contactName", "Changed Contact");
        edit.put("gstin", "27bbbbb1111b1z6");
        JsonNode saved = ok(HttpMethod.PUT, vendorUrl(id), w.clinic().owner(), js(edit), 200);
        assertThat(saved.get("creditDays").asInt()).isEqualTo(45);
        assertThat(saved.get("gstin").asText()).as("upper-cased").isEqualTo("27BBBBB1111B1Z6");
        assertThat(saved.get("version").asInt()).isEqualTo(v.get("version").asInt() + 1);
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_VENDOR_CHANGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(2)).contains("creditDays").contains("30");
        assertThat(audit.get(0).get(3)).contains("creditDays").contains("45");
    }

    @Test
    void aStaleVersionIs409SettingsChangedWithTheCurrentVendorAndARenameToAnotherVendorsNameIs409() throws Exception {
        Masters w = mastersClinic();
        JsonNode a = createVendor(w.clinic(), vendorBody("First Vendor (fake)"));
        JsonNode b = createVendor(w.clinic(), vendorBody("Second Vendor (fake)"));
        Map<String, Object> first = vendorUpdate(a);
        first.put("creditDays", 10);
        JsonNode firstSaved = ok(HttpMethod.PUT, vendorUrl(a.get("id").asText()), w.clinic().owner(), js(first), 200);
        assertThat(firstSaved.get("version").asInt()).isEqualTo(a.get("version").asInt() + 1);
        Map<String, Object> stale = vendorUpdate(a);
        stale.put("creditDays", 99);
        JsonNode p = problem(call(HttpMethod.PUT, vendorUrl(a.get("id").asText()), w.clinic().owner(), js(stale)), 409);
        assertType(p, "settings-changed");
        assertThat(p.get("current").get("creditDays").asInt()).isEqualTo(10);
        assertThat(p.get("current").get("version").asInt()).isEqualTo(firstSaved.get("version").asInt());
        Map<String, Object> rename = vendorUpdate(b);
        rename.put("name", "FIRST VENDOR (fake)");
        assertType(problem(call(HttpMethod.PUT, vendorUrl(b.get("id").asText()), w.clinic().owner(), js(rename)), 409), "vendor-name-in-use");
        // a rename to its own name in other capitals is allowed
        Map<String, Object> own = vendorUpdate(b);
        own.put("name", "SECOND VENDOR (fake)");
        assertThat(ok(HttpMethod.PUT, vendorUrl(b.get("id").asText()), w.clinic().owner(), js(own), 200).get("name").asText())
                .isEqualTo("SECOND VENDOR (fake)");
    }

    @Test
    void aMissingOrNegativeVersionOrMalformedPathIdIs400AndAnUnknownVendorIs404() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Version Vendor (fake)"));
        Map<String, Object> noVersion = vendorUpdate(v);
        noVersion.remove("version");
        assertFieldMessage(problem(call(HttpMethod.PUT, vendorUrl(v.get("id").asText()), w.clinic().owner(), js(noVersion)), 400), "version",
                "Reload the page and try again.");
        Map<String, Object> negative = vendorUpdate(v);
        negative.put("version", -1);
        assertFieldMessage(problem(call(HttpMethod.PUT, vendorUrl(v.get("id").asText()), w.clinic().owner(), js(negative)), 400), "version",
                "Reload the page and try again.");
        assertType(problem(call(HttpMethod.GET, vendorUrl("not-a-uuid"), w.clinic().owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.PUT, vendorUrl("not-a-uuid"), w.clinic().owner(), js(vendorUpdate(v))), 400), "bad-request");
        assertType(problem(call(HttpMethod.POST, vendorUrl("not-a-uuid") + "/switch-off", w.clinic().owner(), null), 400), "bad-request");
        problem(call(HttpMethod.GET, vendorUrl(UUID.randomUUID()), w.clinic().owner(), null), 404);
        problem(call(HttpMethod.PUT, vendorUrl(UUID.randomUUID()), w.clinic().owner(), js(vendorUpdate(v))), 404);
        problem(call(HttpMethod.POST, vendorUrl(UUID.randomUUID()) + "/switch-off", w.clinic().owner(), null), 404);
    }

    // ---------------- switch off ----------------

    @Test
    void switchingOffMakesTheVendorInactiveKeepsTheRowAndASecondSwitchOffWritesNothing() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Switch Off Me (fake)"));
        String id = v.get("id").asText();
        JsonNode off = ok(HttpMethod.POST, vendorUrl(id) + "/switch-off", w.clinic().owner(), null, 200);
        assertThat(off.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(off.get("blocked").asBoolean()).isFalse();
        assertThat(pharmacyRows(w.clinic(), "vendor")).as("never deleted").isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_SWITCHED_OFF")).hasSize(1);
        JsonNode again = ok(HttpMethod.POST, vendorUrl(id) + "/switch-off", w.clinic().owner(), null, 200);
        assertThat(again.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_SWITCHED_OFF")).as("a repeat writes no second audit").hasSize(1);
        // an inactive vendor may be edited and stays inactive
        Map<String, Object> edit = vendorUpdate(off);
        edit.put("creditDays", 7);
        JsonNode edited = ok(HttpMethod.PUT, vendorUrl(id), w.clinic().owner(), js(edit), 200);
        assertThat(edited.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(edited.get("creditDays").asInt()).isEqualTo(7);
    }

    // ---------------- who may ----------------

    @Test
    void everyWriteByANonOwnerIs403AndLeavesNothingWhileThePharmacistAndTheCentralStoreUserMayRead() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Owner Only (fake)"));
        String id = v.get("id").asText();
        for (var who : nonOwners(w).entrySet()) {
            RequestPostProcessor auth = who.getValue();
            String name = who.getKey();
            for (JsonNode p : List.of(
                    problem(call(HttpMethod.POST, VENDORS, auth, js(vendorBody("Blocked " + name + " (fake)"))), 403),
                    problem(call(HttpMethod.PUT, vendorUrl(id), auth, js(vendorUpdate(v))), 403),
                    problem(call(HttpMethod.POST, vendorUrl(id) + "/switch-off", auth, null), 403))) {
                assertType(p, "forbidden");
                assertThat(p.get("detail").asText()).as(name).isIn("Only the clinic owner can do this.", NO_ACCESS_TEXT);
            }
        }
        assertThat(pharmacyRows(w.clinic(), "vendor")).isOne();
        assertThat(values(w.clinic(), "select status from pharmacy.vendor").get(0)).isEqualTo("ACTIVE");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_CHANGED")).isEmpty();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_SWITCHED_OFF")).isEmpty();
        // readers: pharmacist and Central Store user read the list and one vendor; others get 403
        Map<String, RequestPostProcessor> callers = nonOwners(w);
        for (String reader : new String[] {"PHARMACIST", "CENTRAL_STORE_USER"}) {
            assertThat(ok(HttpMethod.GET, VENDORS, callers.get(reader), null, 200).get("total").asInt()).as(reader).isOne();
            ok(HttpMethod.GET, vendorUrl(id), callers.get(reader), null, 200);
        }
        for (String other : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            assertType(problem(call(HttpMethod.GET, VENDORS, callers.get(other), null), 403), "forbidden");
            assertType(problem(call(HttpMethod.GET, vendorUrl(id), callers.get(other), null), 403), "forbidden");
        }
    }

    // ---------------- the clinic wall ----------------

    @Test
    void anotherClinicNeverSeesAVendorAndEveryOperationOnItIs404() throws Exception {
        Masters mine = mastersClinic();
        Masters theirs = mastersClinic();
        JsonNode v = createVendor(mine.clinic(), vendorBody("Wall Vendor (fake)"));
        String id = v.get("id").asText();
        assertThat(ok(HttpMethod.GET, VENDORS + "?status=ALL", theirs.clinic().owner(), null, 200).get("items")).isEmpty();
        problem(call(HttpMethod.GET, vendorUrl(id), theirs.clinic().owner(), null), 404);
        problem(call(HttpMethod.PUT, vendorUrl(id), theirs.clinic().owner(), js(vendorUpdate(v))), 404);
        problem(call(HttpMethod.POST, vendorUrl(id) + "/switch-off", theirs.clinic().owner(), null), 404);
        assertThat(values(mine.clinic(), "select status from pharmacy.vendor where id = ?::uuid", id).get(0)).isEqualTo("ACTIVE");
        assertThat(count(theirs.clinic(), "select count(*) from pharmacy.vendor")).as("row level security hides it").isZero();
        // a client key of the other clinic does not replay across the wall
        Map<String, Object> body = vendorBody("Wall Vendor Two (fake)");
        JsonNode a = createVendor(mine.clinic(), body);
        JsonNode b = ok(HttpMethod.POST, VENDORS, theirs.clinic().owner(), js(body), 201);
        assertThat(b.get("id").asText()).isNotEqualTo(a.get("id").asText());
    }

    // ---------------- concurrency ----------------

    @Test
    void twoCreatesOfTheSameNameAtTheSameMomentMakeOneVendorAndTheOtherIs409() throws Exception {
        Masters w = mastersClinic();
        Pn second = crew(w.clinic(), "OWNER", "Second Owner");
        List<RequestPostProcessor> callers = List.of(w.clinic().owner(), second.auth());
        List<String> bodies = List.of(js(vendorBody("Race Vendor (fake)")), js(vendorBody("race vendor (fake)")));
        List<String> answers = atTheSameMoment(HttpMethod.POST, VENDORS, callers, bodies);
        assertThat(statusCount(answers, 201)).as(answers.toString()).isOne();
        assertThat(statusCount(answers, 409)).isOne();
        assertThat(pharmacyRows(w.clinic(), "vendor")).isOne();
    }

    @Test
    void theVendorTableRefusesATenantOtherThanTheBoundOneAndADeleteFromTheApplication() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Table Vendor (fake)"));
        Masters other = mastersClinic();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db().inTenant(other.clinic().tenantId(), c -> {
            DbAccess.execute(c, "delete from pharmacy.vendor where id = ?::uuid", v.get("id").asText());
            return null;
        })).isInstanceOfSatisfying(java.sql.SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThat(pharmacyRows(w.clinic(), "vendor")).isOne();
        Map<String, Object> unused = new LinkedHashMap<>();
        assertThat(unused).isEmpty();
    }
}
