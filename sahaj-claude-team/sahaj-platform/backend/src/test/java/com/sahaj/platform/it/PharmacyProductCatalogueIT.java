package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78-5 on the real database: search the global catalogue and the unit's own products together, adopt (idempotent), the
 * withdrawn state, and the local settings (reorder level and rack per store, preferred vendor, alias, barcode, returnable, stop
 * using). Texts and field names are those of docs/api/pharmacy.yaml 0.1.2. Test plan: docs/test-plans/SAH-78.md (T78-5-*).
 */
@OnTestDatabase
class PharmacyProductCatalogueIT extends Sah78BSupport {

    private static final String PARACETAMOL_650 = "G-000103";
    private static final String PANTOPRAZOLE_TAB = "G-000105";

    private static List<String> codes(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("code").asText()));
        return out;
    }

    private JsonNode search(Clinic clinic, String query) throws Exception {
        return ok(HttpMethod.GET, PRODUCTS + query, clinic.owner(), null, 200);
    }

    // ---------------- search ----------------

    @Test
    void underTwoCharactersTheFirstPageIsUnfilteredAndPagesHold25ByDefault() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode none = search(clinic, "");
        assertThat(none.get("size").asInt()).as("default page size").isEqualTo(25);
        assertThat(none.get("page").asInt()).isZero();
        assertThat(none.get("items")).hasSize(25);
        assertThat(none.get("total").asLong()).as("30 sample products, nothing of this clinic yet").isEqualTo(30);
        // a single character is not a filter (q of 2 or more characters is)
        assertThat(search(clinic, "?q=a")).isEqualTo(none);
        assertThat(search(clinic, "?q=%20%20")).as("only spaces: trimmed, no filter").isEqualTo(none);
        assertThat(search(clinic, "?q=")).isEqualTo(none);
        // the second page holds the other 5
        JsonNode second = search(clinic, "?page=1");
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.get("page").asInt()).isOne();
        // the order is by name (no exact match); nothing repeats across the pages
        List<String> all = new ArrayList<>(codes(none));
        all.addAll(codes(second));
        assertThat(all).doesNotHaveDuplicates().hasSize(30);
        // size and page boundaries
        assertThat(search(clinic, "?size=50").get("items")).hasSize(30);
        assertThat(search(clinic, "?size=1").get("items")).hasSize(1);
        assertThat(search(clinic, "?page=2000").get("items")).isEmpty();
        // a global row not adopted: no unit product, no alias, marked as sample
        JsonNode first = none.get("items").get(0);
        assertThat(first.get("source").asText()).isEqualTo("GLOBAL");
        assertThat(first.get("productId").isNull()).isTrue();
        assertThat(first.get("inUnit").asText()).isEqualTo("NOT_ADOPTED");
        assertThat(first.get("sample").asBoolean()).isTrue();
        assertThat(first.get("alias").isNull()).isTrue();
        assertThat(first.get("madeInHouse").asBoolean()).isFalse();
    }

    @Test
    void theSearchIsCaseInsensitiveOnNameSaltAndCodeAndPercentOrUnderscoreAreLiteral() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode lower = search(clinic, "?q=ceftriaxone");
        JsonNode upper = search(clinic, "?q=CEFTRIAXONE");
        JsonNode mixed = search(clinic, "?q=CefTriaXone");
        assertThat(codes(lower)).containsExactly("G-000101", "G-000102");
        assertThat(upper).isEqualTo(lower);
        assertThat(mixed).isEqualTo(lower);
        assertThat(lower.get("total").asLong()).isEqualTo(2);
        // salt: "Cholecalciferol" is only in the salt of the vitamin D product, the name says "Vitamin D3"
        assertThat(codes(search(clinic, "?q=cholecalciferol"))).containsExactly("G-000119");
        // a code, whatever the case; the leading and trailing spaces are not part of the search
        assertThat(codes(search(clinic, "?q=g-000117"))).containsExactly("G-000117");
        assertThat(codes(search(clinic, "?q=%20G-000117%20"))).containsExactly("G-000117");
        // nothing matches
        JsonNode miss = search(clinic, "?q=zzzzqqq");
        assertThat(miss.get("items")).isEmpty();
        assertThat(miss.get("total").asLong()).isZero();
        // % and _ are plain characters: they do not match everything
        assertThat(search(clinic, "?q=%25%25").get("total").asLong()).isZero();
        assertThat(search(clinic, "?q=__").get("total").asLong()).isZero();
        assertThat(search(clinic, "?q=%5C%5C").get("total").asLong()).isZero();
    }

    @Test
    void anExactCodeOrBarcodeMatchComesFirstThenTheOthersByName() throws Exception {
        Clinic clinic = pharmacyClinic();
        // an own product whose NAME holds the code and sorts before the sample names, so only the exact rule can put the global first
        Map<String, Object> body = ownBody("Kit for G-000101 trays");
        JsonNode own = createOwn(clinic, body);
        JsonNode result = search(clinic, "?q=g-000101");
        assertThat(codes(result)).as("the exact code is first, the name match second").containsExactly("G-000101", own.get("code").asText());
        assertThat(search(clinic, "?q=G-000101").get("items").get(0).get("code").asText()).isEqualTo("G-000101");
        // a barcode matches only when it is equal (ignoring case): the whole barcode finds the product, a part does not
        assertThat(codes(search(clinic, "?q=SAMPLE-8900000101"))).containsExactly("G-000101");
        assertThat(codes(search(clinic, "?q=sample-8900000101"))).containsExactly("G-000101");
        assertThat(codes(search(clinic, "?q=Sample-8900000203"))).as("a second barcode of G-000103").containsExactly("G-000103");
        assertThat(search(clinic, "?q=sample-89000001").get("total").asLong()).as("part of a barcode does not match").isZero();
        assertThat(search(clinic, "?q=8900000101").get("total").asLong()).isZero();
    }

    @Test
    void theSourceFilterAndTheLocalBarcodeAndAliasAreSearchableAndOtherClinicsOwnProductsAreNot() throws Exception {
        Clinic clinic = pharmacyClinic();
        Clinic other = pharmacyClinic();
        JsonNode mine = createOwn(clinic, ownBody("Aqua dressing pad"));
        JsonNode theirs = createOwn(other, ownBody("Aqua dressing pad"));
        JsonNode own = search(clinic, "?source=OWN");
        assertThat(codes(own)).containsExactly(mine.get("code").asText());
        assertThat(own.get("items").get(0).get("source").asText()).isEqualTo("OWN");
        assertThat(own.get("items").get(0).get("catalogueProductId").isNull()).isTrue();
        assertThat(own.get("items").get(0).get("inUnit").asText()).isEqualTo("ACTIVE");
        assertThat(search(clinic, "?source=GLOBAL").get("total").asLong()).isEqualTo(30);
        assertThat(search(clinic, "?q=aqua%20dressing").get("total").asLong()).as("only my own product, never the other clinic's").isOne();
        assertThat(search(other, "?q=aqua%20dressing").get("items").get(0).get("productId").asText()).isEqualTo(theirs.get("productId").asText());
        // the adopted product found by its local alias and local barcode (exact, ignoring case)
        JsonNode product = adopt(clinic, PARACETAMOL_650);
        Map<String, Object> local = localBody(product);
        local.put("alias", "Crocin tab");
        local.put("barcode", "LOC-BC-77");
        ok(HttpMethod.PUT, localUrl(product.get("productId").asText()), clinic.owner(), js(local), 200);
        for (String q : new String[] {"crocin", "CROCIN%20TAB", "loc-bc-77", "LOC-BC-77"}) {
            JsonNode r = search(clinic, "?q=" + q);
            assertThat(codes(r)).as(q).containsExactly(PARACETAMOL_650);
            assertThat(r.get("items").get(0).get("inUnit").asText()).isEqualTo("ADOPTED");
            assertThat(r.get("items").get(0).get("alias").asText()).isEqualTo("Crocin tab");
        }
        assertThat(search(clinic, "?q=loc-bc").get("total").asLong()).as("a part of the local barcode does not match").isZero();
        assertThat(search(other, "?q=crocin").get("total").asLong()).as("another clinic's alias is not visible").isZero();
    }

    @Test
    void aBadQueryValueIs400WithAFieldMessageAndAnyReaderOfSetupMaySearch() throws Exception {
        Clinic clinic = pharmacyClinic();
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?q=" + "x".repeat(61), clinic.owner(), null), 400), "q", "Type at most 60 characters.");
        assertThat(search(clinic, "?q=" + "x".repeat(60)).get("total").asLong()).isZero();
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?source=BOTH", clinic.owner(), null), 400), "source", "Choose a valid option");
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?page=-1", clinic.owner(), null), 400), "page", "Enter a whole number from 0 to 2000");
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?page=2001", clinic.owner(), null), 400), "page", "Enter a whole number from 0 to 2000");
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?size=0", clinic.owner(), null), 400), "size", "Enter a whole number from 1 to 50");
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?size=51", clinic.owner(), null), 400), "size", "Enter a whole number from 1 to 50");
        assertFieldMessage(problem(call(HttpMethod.GET, PRODUCTS + "?size=ten", clinic.owner(), null), 400), "size", "Enter a whole number from 1 to 50");
        // readers: the owner, any pharmacist (setup view); not an unmapped nurse, doctor or front desk
        Pn pharmacist = crew(clinic, "PHARMACIST");
        ok(HttpMethod.GET, PRODUCTS + "?q=ceft", pharmacist.auth(), null, 200);
        ok(HttpMethod.GET, OPTIONS, pharmacist.auth(), null, 200);
        for (String role : new String[] {"NURSE", "DOCTOR", "FRONT_DESK", "STORE_STAFF", "BILLING"}) {
            Pn p = crew(clinic, role);
            assertType(problem(call(HttpMethod.GET, PRODUCTS, p.auth(), null), 403), "forbidden");
            assertType(problem(call(HttpMethod.GET, OPTIONS, p.auth(), null), 403), "forbidden");
        }
        // a Central Store user can read the setup too
        JsonNode central = centralStore(clinic);
        Pn keeper = crew(clinic, "STORE_STAFF");
        mapStaff(clinic, idOf(central), keeper, "CENTRAL_STORE_USER");
        ok(HttpMethod.GET, PRODUCTS, keeper.auth(), null, 200);
    }

    // ---------------- adopt ----------------

    @Test
    void adoptCreatesThe201ProductAndARepeatIs200WithTheSameProductAndWritesNothing() throws Exception {
        Clinic clinic = pharmacyClinic();
        UUID catalogue = catalogueId("G-000101");
        JsonNode first = ok(HttpMethod.POST, adoptUrl(catalogue), clinic.owner(), null, 201);
        assertThat(first.get("source").asText()).isEqualTo("GLOBAL");
        assertThat(first.get("catalogueProductId").asText()).isEqualTo(catalogue.toString());
        assertThat(first.get("productId").isNull()).isFalse();
        assertThat(first.get("code").asText()).isEqualTo("G-000101");
        assertThat(first.get("name").asText()).isEqualTo("Sample Ceftriaxone 1 g injection");
        assertThat(first.get("inUnit").asText()).isEqualTo("ADOPTED");
        assertThat(first.get("sample").asBoolean()).isTrue();
        assertThat(first.get("hasStock").asBoolean()).isFalse();
        assertThat(first.get("unitsLocked").asBoolean()).isFalse();
        assertThat(first.get("version").asLong()).isZero();
        assertThat(first.get("barcodes")).hasSize(1);
        assertThat(first.get("barcodes").get(0).asText()).isEqualTo("SAMPLE-8900000101");
        JsonNode local = first.get("localSettings");
        assertThat(local.get("preferredVendor").isNull()).isTrue();
        assertThat(local.get("alias").isNull()).isTrue();
        assertThat(local.get("barcode").isNull()).isTrue();
        assertThat(local.get("returnable").asBoolean()).isTrue();
        assertThat(local.get("active").asBoolean()).isTrue();
        assertThat(local.get("storeSettings")).as("no store yet").isEmpty();
        // the unit row holds no global field: they are read from the catalogue (a correction reaches every adopter)
        assertThat(values(clinic, "select kind, name, salt, strength, form, code, hsn, purchase_unit, conversion from pharmacy.product"))
                .containsExactly("ADOPTED", null, null, null, null, null, null, null, null);
        // a repeat answers 200 with the same product and writes nothing
        JsonNode again = ok(HttpMethod.POST, adoptUrl(catalogue), clinic.owner(), null, 200);
        assertThat(again.get("productId").asText()).isEqualTo(first.get("productId").asText());
        assertThat(again.get("version").asLong()).isZero();
        assertThat(pharmacyRows(clinic, "product")).isOne();
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_PRODUCT_ADOPTED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(3)).contains("G-000101").contains(catalogue.toString());
        // the search now shows it as adopted, with the unit product id
        JsonNode row = search(clinic, "?q=g-000101").get("items").get(0);
        assertThat(row.get("inUnit").asText()).isEqualTo("ADOPTED");
        assertThat(row.get("productId").asText()).isEqualTo(first.get("productId").asText());
        // the detail by the unit product id; the catalogue id is not a product id
        assertThat(getProduct(clinic, first.get("productId").asText()).get("code").asText()).isEqualTo("G-000101");
        assertType(problem(call(HttpMethod.GET, productUrl(catalogue), clinic.owner(), null), 404), "not-found");
    }

    @Test
    void adoptNeedsTheOwnerAnExistingCatalogueIdAndAWellFormedId() throws Exception {
        Clinic clinic = pharmacyClinic();
        UUID catalogue = catalogueId("G-000101");
        for (String role : new String[] {"PHARMACIST", "NURSE", "DOCTOR", "STORE_STAFF"}) {
            assertType(problem(call(HttpMethod.POST, adoptUrl(catalogue), crew(clinic, role).auth(), null), 403), "forbidden");
        }
        assertType(problem(call(HttpMethod.POST, adoptUrl(UUID.randomUUID()), clinic.owner(), null), 404), "not-found");
        JsonNode bad = problem(call(HttpMethod.POST, adoptUrl("not-a-uuid"), clinic.owner(), null), 400);
        assertType(bad, "bad-request");
        assertThat(bad.toString()).doesNotContain("not-a-uuid");
        assertThat(pharmacyRows(clinic, "product")).isZero();
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_ADOPTED")).isEmpty();
        // two clinics adopt the same catalogue product: each has its own row, nobody sees the other's
        Clinic other = pharmacyClinic();
        JsonNode mine = ok(HttpMethod.POST, adoptUrl(catalogue), clinic.owner(), null, 201);
        JsonNode theirs = ok(HttpMethod.POST, adoptUrl(catalogue), other.owner(), null, 201);
        assertThat(mine.get("productId").asText()).isNotEqualTo(theirs.get("productId").asText());
        assertType(problem(call(HttpMethod.GET, productUrl(mine.get("productId").asText()), other.owner(), null), 404), "not-found");
    }

    @Test
    void aWithdrawnCatalogueProductIsShownWithdrawnToWhoAdoptedItHiddenFromOthersAndCannotBeAdoptedNew() throws Exception {
        Clinic adopter = pharmacyClinic();
        Clinic newcomer = pharmacyClinic();
        String code = "G-000130";
        JsonNode adopted = adopt(adopter, code);
        setCatalogueActive(code, false);
        try {
            JsonNode row = search(adopter, "?q=cotton").get("items").get(0);
            assertThat(row.get("code").asText()).isEqualTo(code);
            assertThat(row.get("inUnit").asText()).isEqualTo("WITHDRAWN");
            assertThat(getProduct(adopter, adopted.get("productId").asText()).get("inUnit").asText()).isEqualTo("WITHDRAWN");
            assertThat(search(adopter, "").get("total").asLong()).as("still listed for the adopter").isEqualTo(30);
            // others do not see it, and cannot adopt it
            assertThat(search(newcomer, "?q=cotton").get("total").asLong()).isZero();
            assertThat(search(newcomer, "").get("total").asLong()).isEqualTo(29);
            JsonNode refused = problem(call(HttpMethod.POST, adoptUrl(catalogueId(code)), newcomer.owner(), null), 409);
            assertType(refused, "product-withdrawn");
            assertThat(refused.get("detail").asText()).isEqualTo("This product was withdrawn from the catalogue.");
            assertThat(pharmacyRows(newcomer, "product")).isZero();
            // the adopter repeating adopt still gets the existing product (200)
            ok(HttpMethod.POST, adoptUrl(catalogueId(code)), adopter.owner(), null, 200);
        } finally {
            setCatalogueActive(code, true);
        }
        assertThat(search(newcomer, "?q=cotton").get("total").asLong()).as("the catalogue is back as it was").isOne();
    }

    private void setCatalogueActive(String code, boolean active) throws SQLException {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            DbAccess.execute(c, "update pharmacy.catalogue_product set active = ? where code = ?", active, code);
            c.commit();
        }
    }

    // ---------------- local settings ----------------

    @Test
    void localSettingsSaveReorderLevelAndRackPerStoreAndAuditTheChange() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode product = adopt(clinic, PARACETAMOL_650);
        String id = product.get("productId").asText();
        // every ACTIVE store is listed, with nothing set
        JsonNode fresh = getProduct(clinic, id);
        assertThat(fresh.get("localSettings").get("storeSettings")).hasSize(2);
        for (JsonNode s : fresh.get("localSettings").get("storeSettings")) {
            assertThat(s.get("reorderLevel").isNull()).isTrue();
            assertThat(s.get("rack").isNull()).isTrue();
            assertThat(s.get("storeName").asText()).isIn("Central Store", "Main Pharmacy");
        }
        Map<String, Object> body = localBody(fresh);
        settings(body, setting(idOf(stores[0]), 50, "A-3"), setting(idOf(stores[1]), 0, "P-12"));
        body.put("alias", "Para 650");
        body.put("returnable", false);
        JsonNode saved = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(body), 200);
        assertThat(saved.get("version").asLong()).isOne();
        assertThat(settingOf(saved, idOf(stores[0])).get("reorderLevel").asInt()).isEqualTo(50);
        assertThat(settingOf(saved, idOf(stores[0])).get("rack").asText()).isEqualTo("A-3");
        assertThat(settingOf(saved, idOf(stores[1])).get("reorderLevel").asInt()).as("zero is a value, not 'not set'").isZero();
        assertThat(settingOf(saved, idOf(stores[1])).get("rack").asText()).isEqualTo("P-12");
        assertThat(saved.get("localSettings").get("alias").asText()).isEqualTo("Para 650");
        assertThat(saved.get("localSettings").get("returnable").asBoolean()).isFalse();
        assertThat(saved.get("name").asText()).as("global fields are untouched").isEqualTo("Sample Paracetamol 650 mg tablet");
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_LOCAL_SETTINGS_CHANGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(3)).contains("Para 650").contains("A-3").contains("P-12");
        assertThat(audit.get(0).get(2)).as("before values hold what was there").doesNotContain("A-3");
        // a repeat of exactly the same values changes nothing: no new version, no second audit row
        Map<String, Object> same = localBody(saved);
        settings(same, setting(idOf(stores[0]), 50, "A-3"), setting(idOf(stores[1]), 0, "P-12"));
        JsonNode repeat = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(same), 200);
        assertThat(repeat.get("version").asLong()).isOne();
        assertThat(auditRowsOf(clinic, "PHARMACY_LOCAL_SETTINGS_CHANGED")).hasSize(1);
    }

    @Test
    void storesThatAreNotListedKeepTheirSettingsAndAnEntryWithBothValuesNullClearsThatStore() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        String id = adopt(clinic, PARACETAMOL_650).get("productId").asText();
        Map<String, Object> body = localBody(getProduct(clinic, id));
        settings(body, setting(idOf(stores[0]), 50, "A-3"), setting(idOf(stores[1]), 10, "P-1"), setting(idOf(ward), 5, "W-1"));
        JsonNode all = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(body), 200);
        // only the pharmacy is sent: the other two keep their values (0.1.2: not listed means unchanged)
        Map<String, Object> only = localBody(all);
        settings(only, setting(idOf(stores[1]), 99, "P-9"));
        JsonNode second = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(only), 200);
        assertThat(settingOf(second, idOf(stores[0])).get("reorderLevel").asInt()).isEqualTo(50);
        assertThat(settingOf(second, idOf(stores[0])).get("rack").asText()).isEqualTo("A-3");
        assertThat(settingOf(second, idOf(stores[1])).get("reorderLevel").asInt()).isEqualTo(99);
        assertThat(settingOf(second, idOf(ward)).get("rack").asText()).isEqualTo("W-1");
        // an empty list changes no store
        Map<String, Object> empty = localBody(second);
        JsonNode third = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(empty), 200);
        assertThat(settingOf(third, idOf(stores[0])).get("reorderLevel").asInt()).isEqualTo(50);
        assertThat(third.get("version").asLong()).as("nothing changed, no new version").isEqualTo(second.get("version").asLong());
        // both null clears that one store only
        Map<String, Object> clear = localBody(third);
        settings(clear, setting(idOf(ward), null, null));
        JsonNode cleared = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(clear), 200);
        assertThat(settingOf(cleared, idOf(ward)).get("reorderLevel").isNull()).isTrue();
        assertThat(settingOf(cleared, idOf(ward)).get("rack").isNull()).isTrue();
        assertThat(settingOf(cleared, idOf(stores[0])).get("reorderLevel").asInt()).isEqualTo(50);
        assertThat(settingOf(cleared, idOf(stores[1])).get("rack").asText()).isEqualTo("P-9");
        // only the level cleared keeps the rack
        Map<String, Object> half = localBody(cleared);
        settings(half, setting(idOf(stores[1]), null, "P-9"));
        JsonNode halfCleared = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(half), 200);
        assertThat(settingOf(halfCleared, idOf(stores[1])).get("reorderLevel").isNull()).isTrue();
        assertThat(settingOf(halfCleared, idOf(stores[1])).get("rack").asText()).isEqualTo("P-9");
    }

    @Test
    void localSettingsRefuseBadValuesAllTogetherAndChangeNothing() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode off = createStore(clinic, "Old Store", "OLD", "SUB_STORE");
        ok(HttpMethod.POST, STORES + "/" + idOf(off) + "/switch-off", clinic.owner(), null, 200);
        JsonNode product = adopt(clinic, PARACETAMOL_650);
        String id = product.get("productId").asText();
        String url = localUrl(id);
        // boundaries accepted: level 999999 and 0, rack and alias of exactly their limits, a 40-character barcode
        Map<String, Object> edge = localBody(product);
        settings(edge, setting(idOf(stores[0]), 999_999, "R".repeat(30)), setting(idOf(stores[1]), 0, "x"));
        edge.put("alias", "a".repeat(40));
        edge.put("barcode", "B".repeat(40));
        JsonNode accepted = ok(HttpMethod.PUT, url, clinic.owner(), js(edge), 200);
        assertThat(accepted.get("localSettings").get("barcode").asText()).hasSize(40);
        String version = accepted.get("version").asText();
        // refused, each on the field it belongs to
        Map<String, Object> bad = localBody(accepted);
        settings(bad, setting(idOf(stores[0]), -1, "ok"), setting(idOf(stores[1]), 1_000_000, "ok"));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[0].reorderLevel", "Enter a whole number, 0 or more");
        JsonNode p = problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400);
        assertFieldMessage(p, "storeSettings[1].reorderLevel", "Enter a whole number, 0 or more");
        settings(bad, setting(idOf(stores[0]), 2.5, "ok"));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[0].reorderLevel", "Enter a whole number, 0 or more");
        settings(bad, setting(idOf(stores[0]), 5, "R".repeat(31)));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[0].rack", "Keep the rack to 30 characters");
        settings(bad);
        bad.put("alias", "a".repeat(41));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "alias", "Keep the alias to 40 characters");
        bad.put("alias", null);
        for (String barcode : new String[] {"B".repeat(41), "has space", "bad_code!", "ab/cd"}) {
            bad.put("barcode", barcode);
            assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "barcode", "Use up to 40 letters, digits or hyphens");
        }
        bad.put("barcode", null);
        // a store that is switched off, unknown, foreign or listed twice, and a missing store id
        settings(bad, setting(idOf(off), 1, null));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[0].storeId", "Choose an active store");
        settings(bad, setting(UUID.randomUUID(), 1, null));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[0].storeId", "Choose an active store");
        Clinic other = pharmacyClinic();
        settings(bad, setting(idOf(centralStore(other)), 1, null));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[0].storeId", "Choose an active store");
        settings(bad, setting(idOf(stores[0]), 1, null), setting(idOf(stores[0]), 2, null));
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400), "storeSettings[1].storeId", "List each store once");
        // all errors together, in one answer
        settings(bad, setting(idOf(stores[0]), -5, "R".repeat(31)));
        bad.put("alias", "a".repeat(41));
        bad.put("barcode", "no good");
        JsonNode together = problem(call(HttpMethod.PUT, url, clinic.owner(), js(bad)), 400);
        List<String> fields = new ArrayList<>();
        together.get("fieldErrors").forEach(e -> fields.add(e.get("field").asText()));
        assertThat(fields).contains("storeSettings[0].reorderLevel", "storeSettings[0].rack", "alias", "barcode");
        // a global field in the body is refused (the body is closed), and so is a missing required field
        Map<String, Object> withGlobal = localBody(accepted);
        withGlobal.put("name", "Hacked name");
        assertType(problem(call(HttpMethod.PUT, url, clinic.owner(), js(withGlobal)), 400), "validation");
        for (String missing : new String[] {"storeSettings", "preferredVendorId", "alias", "barcode", "returnable", "active", "version"}) {
            Map<String, Object> partial = localBody(accepted);
            partial.remove(missing);
            JsonNode m = problem(call(HttpMethod.PUT, url, clinic.owner(), js(partial)), 400);
            assertThat(m.toString()).as("missing " + missing).contains(missing);
        }
        assertThat(getProduct(clinic, id).get("version").asText()).as("nothing was changed by any refused save").isEqualTo(version);
        assertThat(getProduct(clinic, id).get("name").asText()).isEqualTo("Sample Paracetamol 650 mg tablet");
    }

    @Test
    void aBarcodeUsedByAnotherActiveProductIs409AndAStoppedProductDoesNotHoldItsBarcode() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode a = adopt(clinic, PARACETAMOL_650);
        JsonNode b = adopt(clinic, PANTOPRAZOLE_TAB);
        Map<String, Object> bodyA = localBody(a);
        bodyA.put("barcode", "SHELF-0001");
        JsonNode savedA = ok(HttpMethod.PUT, localUrl(a.get("productId").asText()), clinic.owner(), js(bodyA), 200);
        Map<String, Object> bodyB = localBody(b);
        bodyB.put("barcode", "shelf-0001"); // same barcode, other case
        JsonNode clash = problem(call(HttpMethod.PUT, localUrl(b.get("productId").asText()), clinic.owner(), js(bodyB)), 409);
        assertType(clash, "barcode-in-use");
        assertThat(clash.get("detail").asText()).isEqualTo("That barcode is already used by Sample Paracetamol 650 mg tablet");
        assertThat(getProduct(clinic, b.get("productId").asText()).get("localSettings").get("barcode").isNull()).isTrue();
        // a catalogue barcode is not a local barcode: it does not clash
        Map<String, Object> catalogueCode = localBody(b);
        catalogueCode.put("barcode", "SAMPLE-8900000103");
        ok(HttpMethod.PUT, localUrl(b.get("productId").asText()), clinic.owner(), js(catalogueCode), 200);
        // another clinic can use the same barcode
        Clinic other = pharmacyClinic();
        JsonNode otherProduct = adopt(other, PARACETAMOL_650);
        Map<String, Object> theirs = localBody(otherProduct);
        theirs.put("barcode", "SHELF-0001");
        ok(HttpMethod.PUT, localUrl(otherProduct.get("productId").asText()), other.owner(), js(theirs), 200);
        // stop using A: its barcode is free for B
        Map<String, Object> stopA = localBody(savedA);
        stopA.put("active", false);
        JsonNode stopped = ok(HttpMethod.PUT, localUrl(a.get("productId").asText()), clinic.owner(), js(stopA), 200);
        assertThat(stopped.get("localSettings").get("active").asBoolean()).isFalse();
        assertThat(stopped.get("inUnit").asText()).isEqualTo("INACTIVE");
        Map<String, Object> bodyB2 = localBody(getProduct(clinic, b.get("productId").asText()));
        bodyB2.put("barcode", "SHELF-0001");
        JsonNode savedB = ok(HttpMethod.PUT, localUrl(b.get("productId").asText()), clinic.owner(), js(bodyB2), 200);
        // switching A on again while B holds the barcode: refused, A stays stopped
        Map<String, Object> startA = localBody(stopped);
        startA.put("active", true);
        assertType(problem(call(HttpMethod.PUT, localUrl(a.get("productId").asText()), clinic.owner(), js(startA)), 409), "barcode-in-use");
        assertThat(getProduct(clinic, a.get("productId").asText()).get("localSettings").get("active").asBoolean()).isFalse();
        assertThat(savedB.get("localSettings").get("barcode").asText()).isEqualTo("SHELF-0001");
    }

    @Test
    void stopUsingIsAuditedAdoptDoesNotSwitchItOnAgainAndAProductWithStockCannotBeStopped() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode a = adopt(clinic, PARACETAMOL_650);
        String id = a.get("productId").asText();
        Map<String, Object> stop = localBody(a);
        stop.put("active", false);
        JsonNode stopped = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(stop), 200);
        assertThat(stopped.get("inUnit").asText()).isEqualTo("INACTIVE");
        assertThat(search(clinic, "?q=g-000103").get("items").get(0).get("inUnit").asText()).isEqualTo("INACTIVE");
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_SWITCHED_OFF")).hasSize(1);
        // adopt again: 200, and an adopted product that the unit switched off is not switched on again
        JsonNode again = ok(HttpMethod.POST, adoptUrl(catalogueId(PARACETAMOL_650)), clinic.owner(), null, 200);
        assertThat(again.get("localSettings").get("active").asBoolean()).isFalse();
        // switch it on through local settings
        Map<String, Object> start = localBody(stopped);
        start.put("active", true);
        JsonNode started = ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(start), 200);
        assertThat(started.get("inUnit").asText()).isEqualTo("ADOPTED");
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_SWITCHED_ON")).hasSize(1);
        // with stock the product cannot be stopped; the refusal changes nothing
        giveStock(clinic, id);
        Map<String, Object> stopAgain = localBody(started);
        stopAgain.put("active", false);
        JsonNode refused = problem(call(HttpMethod.PUT, localUrl(id), clinic.owner(), js(stopAgain)), 409);
        assertType(refused, "product-has-stock");
        assertThat(refused.get("detail").asText()).isEqualTo("This product has stock. Clear it in Inventory first.");
        JsonNode after = getProduct(clinic, id);
        assertThat(after.get("localSettings").get("active").asBoolean()).isTrue();
        assertThat(after.get("hasStock").asBoolean()).isTrue();
        assertThat(after.get("unitsLocked").asBoolean()).as("unitsLocked equals hasStock").isTrue();
        assertThat(after.get("version").asLong()).isEqualTo(started.get("version").asLong());
        // other local settings of a product with stock are still saved
        Map<String, Object> alias = localBody(after);
        alias.put("alias", "Still editable");
        assertThat(ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(alias), 200).get("localSettings").get("alias").asText()).isEqualTo("Still editable");
        // a product that was already stopped and then got stock is not refused when saved again (not a switch-off)
        String idB = adopt(clinic, PANTOPRAZOLE_TAB).get("productId").asText();
        Map<String, Object> stopB = localBody(getProduct(clinic, idB));
        stopB.put("active", false);
        ok(HttpMethod.PUT, localUrl(idB), clinic.owner(), js(stopB), 200);
        giveStock(clinic, idB);
        ok(HttpMethod.PUT, localUrl(idB), clinic.owner(), js(withVersion(stopB, getProduct(clinic, idB))), 200);
    }

    private static Map<String, Object> withVersion(Map<String, Object> body, JsonNode current) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put("version", current.get("version").asLong());
        return copy;
    }

    @Test
    void aStaleVersionIs409SettingsChangedWithTheCurrentProductAndNothingIsOverwritten() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode a = adopt(clinic, PARACETAMOL_650);
        String id = a.get("productId").asText();
        Map<String, Object> first = localBody(a);
        first.put("alias", "First owner");
        ok(HttpMethod.PUT, localUrl(id), clinic.owner(), js(first), 200);
        Map<String, Object> stale = localBody(a); // version 0 again
        stale.put("alias", "Second owner");
        JsonNode p = problem(call(HttpMethod.PUT, localUrl(id), clinic.owner(), js(stale)), 409);
        assertType(p, "settings-changed");
        assertThat(p.get("current").get("version").asLong()).isOne();
        assertThat(p.get("current").get("localSettings").get("alias").asText()).isEqualTo("First owner");
        assertThat(getProduct(clinic, id).get("localSettings").get("alias").asText()).isEqualTo("First owner");
        // a missing or negative version is a field error, not a conflict
        Map<String, Object> negative = localBody(a);
        negative.put("version", -1);
        assertFieldMessage(problem(call(HttpMethod.PUT, localUrl(id), clinic.owner(), js(negative)), 400), "version", "Reload the page and try again.");
    }

    @Test
    void thePreferredVendorMustBeActiveAndNotBlockedWhenChangedButAnUnchangedOneStaysSavableAndShowsNeedsReview() throws Exception {
        Clinic clinic = pharmacyClinic();
        Clinic other = pharmacyClinic();
        ensureUnit(clinic);
        ensureUnit(other);
        LocalDate today = todayIst();
        UUID ok = insertVendor(clinic, "Test Vendor Plain", false, null, "ACTIVE");
        UUID soon = insertVendor(clinic, "Test Vendor Licensed", true, today.plusDays(30), "ACTIVE");
        UUID lastDay = insertVendor(clinic, "Test Vendor Last Day", true, today, "ACTIVE");
        UUID expired = insertVendor(clinic, "Test Vendor Expired", true, today.minusDays(1), "ACTIVE");
        UUID expired2 = insertVendor(clinic, "Test Vendor Expired Two", true, today.minusDays(40), "ACTIVE");
        UUID inactive = insertVendor(clinic, "Test Vendor Inactive", false, null, "INACTIVE");
        UUID foreign = insertVendor(other, "Test Vendor Foreign", false, null, "ACTIVE");
        JsonNode product = adopt(clinic, PARACETAMOL_650);
        String id = product.get("productId").asText();
        String url = localUrl(id);
        // refused when changed: blocked, inactive, another clinic's, unknown
        for (UUID bad : new UUID[] {expired, inactive, foreign, UUID.randomUUID()}) {
            Map<String, Object> body = localBody(product);
            body.put("preferredVendorId", bad.toString());
            assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(body)), 400), "preferredVendorId", "Choose an active vendor");
        }
        assertThat(getProduct(clinic, id).get("localSettings").get("preferredVendor").isNull()).isTrue();
        // accepted: active; a licence that is valid today is not yet blocked (valid-till day still counts)
        Map<String, Object> pick = localBody(product);
        pick.put("preferredVendorId", lastDay.toString());
        JsonNode picked = ok(HttpMethod.PUT, url, clinic.owner(), js(pick), 200);
        assertThat(picked.get("localSettings").get("preferredVendor").get("name").asText()).isEqualTo("Test Vendor Last Day");
        assertThat(picked.get("localSettings").get("preferredVendor").get("needsReview").asBoolean()).isFalse();
        Map<String, Object> pickSoon = localBody(picked);
        pickSoon.put("preferredVendorId", soon.toString());
        JsonNode withSoon = ok(HttpMethod.PUT, url, clinic.owner(), js(pickSoon), 200);
        // the licence then expires: needsReview, and the product still saves with the same vendor
        commitAsTenant(clinic.tenantId(), "update pharmacy.vendor set licence_valid_till = ?::date where id = ?::uuid", today.minusDays(2).toString(), soon.toString());
        JsonNode review = getProduct(clinic, id);
        assertThat(review.get("localSettings").get("preferredVendor").get("needsReview").asBoolean()).isTrue();
        Map<String, Object> keep = localBody(review);
        keep.put("alias", "Kept vendor");
        JsonNode kept = ok(HttpMethod.PUT, url, clinic.owner(), js(keep), 200);
        assertThat(kept.get("localSettings").get("alias").asText()).isEqualTo("Kept vendor");
        assertThat(kept.get("localSettings").get("preferredVendor").get("id").asText()).isEqualTo(soon.toString());
        assertThat(kept.get("localSettings").get("preferredVendor").get("needsReview").asBoolean()).isTrue();
        // changing to another blocked vendor is refused; to an active one is accepted; empty clears
        Map<String, Object> toBlocked = localBody(kept);
        toBlocked.put("preferredVendorId", expired2.toString());
        assertFieldMessage(problem(call(HttpMethod.PUT, url, clinic.owner(), js(toBlocked)), 400), "preferredVendorId", "Choose an active vendor");
        Map<String, Object> toOk = localBody(kept);
        toOk.put("preferredVendorId", ok.toString());
        JsonNode good = ok(HttpMethod.PUT, url, clinic.owner(), js(toOk), 200);
        assertThat(good.get("localSettings").get("preferredVendor").get("needsReview").asBoolean()).isFalse();
        // the vendor becomes inactive: needs review as well, and an unchanged id stays savable
        commitAsTenant(clinic.tenantId(), "update pharmacy.vendor set status = 'INACTIVE' where id = ?::uuid", ok.toString());
        assertThat(getProduct(clinic, id).get("localSettings").get("preferredVendor").get("needsReview").asBoolean()).isTrue();
        Map<String, Object> again = localBody(getProduct(clinic, id));
        again.put("returnable", false);
        ok(HttpMethod.PUT, url, clinic.owner(), js(again), 200);
        Map<String, Object> clear = localBody(getProduct(clinic, id));
        clear.put("preferredVendorId", null);
        assertThat(ok(HttpMethod.PUT, url, clinic.owner(), js(clear), 200).get("localSettings").get("preferredVendor").isNull()).isTrue();
        assertThat(withSoon.get("localSettings").get("preferredVendor").get("needsReview").asBoolean()).as("before it expired").isFalse();
    }

    // ---------------- roles, tenants, ids ----------------

    @Test
    void anotherClinicsProductIs404AndAnUnknownOrMalformedIdIsHandledInTheContractOrder() throws Exception {
        Clinic clinic = pharmacyClinic();
        Clinic other = pharmacyClinic();
        String id = adopt(clinic, PARACETAMOL_650).get("productId").asText();
        Map<String, Object> body = localBody(getProduct(clinic, id));
        {
            assertType(problem(call(HttpMethod.GET, productUrl(id), other.owner(), null), 404), "not-found");
        }
        assertType(problem(call(HttpMethod.PUT, localUrl(id), other.owner(), js(body)), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, productUrl(id), other.owner(), js(updateBody(getProduct(clinic, id)))), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, productUrl(UUID.randomUUID()), clinic.owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, localUrl(UUID.randomUUID()), clinic.owner(), js(body)), 404), "not-found");
        // malformed id: 400 bad-request after the gate, and before the body is read
        JsonNode bad = problem(call(HttpMethod.GET, PRODUCTS + "/not-a-uuid", clinic.owner(), null), 400);
        assertType(bad, "bad-request");
        assertType(problem(call(HttpMethod.PUT, localUrl("not-a-uuid"), clinic.owner(), "{broken"), 400), "bad-request");
        // non-owners: read yes for a pharmacist, change never
        Pn pharmacist = crew(clinic, "PHARMACIST");
        ok(HttpMethod.GET, productUrl(id), pharmacist.auth(), null, 200);
        assertType(problem(call(HttpMethod.PUT, localUrl(id), pharmacist.auth(), js(body)), 403), "forbidden");
        Pn nurse = crew(clinic, "NURSE");
        assertType(problem(call(HttpMethod.GET, productUrl(id), nurse.auth(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, localUrl(id), nurse.auth(), js(body)), 403), "forbidden");
        assertThat(getProduct(clinic, id).get("version").asLong()).as("nothing changed by a refused caller").isZero();
        assertThat(auditRowsOf(clinic, "PHARMACY_LOCAL_SETTINGS_CHANGED")).isEmpty();
    }

    @Test
    void theLocalSettingsOfOneClinicNeverShowInAnotherClinicsSameCatalogueProduct() throws Exception {
        Clinic a = pharmacyClinic();
        Clinic b = pharmacyClinic();
        JsonNode pa = adopt(a, PARACETAMOL_650);
        JsonNode pb = adopt(b, PARACETAMOL_650);
        Map<String, Object> body = localBody(pa);
        body.put("alias", "Clinic A alias");
        body.put("barcode", "A-ONLY-1");
        ok(HttpMethod.PUT, localUrl(pa.get("productId").asText()), a.owner(), js(body), 200);
        JsonNode theirs = getProduct(b, pb.get("productId").asText());
        assertThat(theirs.get("localSettings").get("alias").isNull()).isTrue();
        assertThat(theirs.get("localSettings").get("barcode").isNull()).isTrue();
        assertThat(search(b, "?q=a-only-1").get("total").asLong()).isZero();
    }
}
