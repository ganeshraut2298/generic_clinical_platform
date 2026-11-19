package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Code review N-2, DEC-071 Q1 option A, contract 0.1.3 general rule 14 and ADR-028 R5-2: while an opening-stock import is open
 * (DRAFT or WAITING_APPROVAL) its store cannot be switched off or get another code, and a product on one of its lines cannot be
 * switched off or get other units or conversion. The refusal is 409 {@code open-import} with the exact sentence, and nothing is
 * written. It ends when the import is REJECTED, NOT_ACCEPTED, APPROVED (then the has-stock rules take over) or REPLACED.
 * Another clinic's import never blocks (row level security). Test plan: docs/test-plans/SAH-78.md (T78-N2-*).
 */
@OnTestDatabase
class PharmacyOpenImportIT extends Sah78BSupport {

    private static final String OPEN_IMPORT_TEXT = "Finish or reject the opening stock file of this store first.";
    private static final String REJECT = "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}";

    /** Central Store (CS), Main Pharmacy (PHARM) and Ward 3B (W3B); a pharmacist mapped at the pharmacy; a second owner; two products. */
    private record World(Clinic clinic, JsonNode central, JsonNode pharmacy, JsonNode ward, Pn keeper, Pn second,
                         String adoptedId, String ownId, String codeA, String codeB) {

        String pharmacyId() {
            return pharmacy.get("id").asText();
        }

        String wardId() {
            return ward.get("id").asText();
        }
    }

    private World world() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        JsonNode ward = createStore(clinic, "Ward 3B", "W3B", "WARD");
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(stores[1]), keeper, "STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        JsonNode adopted = adopt(clinic, "G-000101");
        JsonNode own = createOwn(clinic, ownBody("Test dressing kit (OT)"));
        return new World(clinic, stores[0], stores[1], ward, keeper, second, adopted.get("productId").asText(),
                own.get("productId").asText(), "G-000101", own.get("code").asText());
    }

    /** An open import of the pharmacy store with both products on its lines; "DRAFT" or "WAITING". Returns the import id. */
    private String open(World w, String state) throws Exception {
        String id = idOf(draftOfCleanFile(w.keeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        if (state.equals("WAITING")) {
            submit(w.keeper().auth(), w.pharmacyId(), id);
        }
        assertThat(getImport(w.keeper().auth(), w.pharmacyId(), id).get("status").asText())
                .isEqualTo(state.equals("WAITING") ? "WAITING_APPROVAL" : "DRAFT");
        return id;
    }

    private static void assertOpenImport(JsonNode p) {
        assertType(p, "open-import");
        assertThat(p.get("status").asInt()).isEqualTo(409);
        assertThat(p.get("detail").asText()).as("the exact sentence the client shows").isEqualTo(OPEN_IMPORT_TEXT);
    }

    private JsonNode getStore(World w, String storeId) throws Exception {
        return ok(HttpMethod.GET, STORES + "/" + storeId, w.clinic().owner(), null, 200);
    }

    private static String storeBody(JsonNode store, String name, String code) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", store.get("version").asLong());
        m.put("name", name);
        m.put("code", code);
        m.put("type", store.get("type").asText());
        m.put("saleAllowed", store.get("saleAllowed").asBoolean());
        m.put("consumptionAllowed", store.get("consumptionAllowed").asBoolean());
        m.put("hodMembershipId", null);
        m.put("delegate", null);
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    }

    private long audits(World w) throws Exception {
        return count(w.clinic(), "select count(*) from platform.audit_event");
    }

    private JsonNode putProductUnits(World w, String productId, String field, Object value, int expected) throws Exception {
        Map<String, Object> body = updateBody(getProduct(w.clinic(), productId));
        body.put(field, value);
        return expected == 200 ? ok(HttpMethod.PUT, productUrl(productId), w.clinic().owner(), js(body), 200)
                : problem(call(HttpMethod.PUT, productUrl(productId), w.clinic().owner(), js(body)), expected);
    }

    private JsonNode putActive(World w, String productId, boolean active, int expected) throws Exception {
        Map<String, Object> body = localBody(getProduct(w.clinic(), productId));
        body.put("active", active);
        return expected == 200 ? ok(HttpMethod.PUT, localUrl(productId), w.clinic().owner(), js(body), 200)
                : problem(call(HttpMethod.PUT, localUrl(productId), w.clinic().owner(), js(body)), expected);
    }

    // ---------------- the store ----------------

    @Test
    void aStoreWithADraftOrWaitingImportCannotBeSwitchedOffAndNothingChanges() throws Exception {
        for (String state : List.of("DRAFT", "WAITING")) {
            World w = world();
            String id = open(w, state);
            JsonNode before = getStore(w, w.pharmacyId());
            long auditBefore = audits(w);
            JsonNode p = problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null), 409);
            assertOpenImport(p);
            assertThat(getStore(w, w.pharmacyId())).as(state + ": the store is unchanged").isEqualTo(before);
            assertThat(before.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(audits(w)).as(state + ": nothing was written or audited").isEqualTo(auditBefore);
            assertThat(auditRowsOf(w.clinic(), "PHARMACY_STORE_SWITCHED_OFF")).isEmpty();
            // the file is still reachable: the uploader reads it, the owners can decide it
            assertThat(getImport(w.keeper().auth(), w.pharmacyId(), id).get("status").asText())
                    .isEqualTo(state.equals("WAITING") ? "WAITING_APPROVAL" : "DRAFT");
            // another store of the clinic is not affected by this store's file
            assertThat(ok(HttpMethod.POST, STORES + "/" + w.wardId() + "/switch-off", w.clinic().owner(), null, 200).get("status").asText())
                    .as(state + ": the Ward has no open import").isEqualTo("INACTIVE");
        }
    }

    @Test
    void aStoreCodeCannotChangeWhileTheImportIsOpenButARenameAndAnUnchangedSaveAreAllowed() throws Exception {
        for (String state : List.of("DRAFT", "WAITING")) {
            World w = world();
            open(w, state);
            JsonNode before = getStore(w, w.pharmacyId());
            long auditBefore = audits(w);
            JsonNode p = problem(call(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(),
                    storeBody(before, before.get("name").asText(), "PHARM2")), 409);
            assertOpenImport(p);
            assertThat(getStore(w, w.pharmacyId())).as(state + ": nothing changed").isEqualTo(before);
            assertThat(audits(w)).isEqualTo(auditBefore);
            // an unchanged code is fine: the same body, and a rename
            JsonNode same = ok(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(),
                    storeBody(before, before.get("name").asText(), "PHARM"), 200);
            assertThat(same.get("code").asText()).isEqualTo("PHARM");
            JsonNode renamed = ok(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(),
                    storeBody(same, "Main Pharmacy (new name)", "PHARM"), 200);
            assertThat(renamed.get("name").asText()).isEqualTo("Main Pharmacy (new name)");
            assertThat(renamed.get("code").asText()).isEqualTo("PHARM");
            // the same store, code in another case, is not a change either (codes are kept in capitals)
            ok(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(), storeBody(renamed, "Main Pharmacy (new name)", "pharm"), 200);
            assertThat(getStore(w, w.pharmacyId()).get("code").asText()).isEqualTo("PHARM");
            // another store of the clinic may change its code while this store's file is open
            JsonNode ward = getStore(w, w.wardId());
            assertThat(ok(HttpMethod.PUT, STORES + "/" + w.wardId(), w.clinic().owner(), storeBody(ward, "Ward 3B", "W3C"), 200)
                    .get("code").asText()).isEqualTo("W3C");
        }
    }

    // ---------------- the product ----------------

    @Test
    void aProductOnALineOfAnOpenImportKeepsItsUnitsAndConversionButOtherFieldsAndUnchangedUnitsAreAllowed() throws Exception {
        for (String state : List.of("DRAFT", "WAITING")) {
            World w = world();
            open(w, state);
            JsonNode before = getProduct(w.clinic(), w.ownId());
            long auditBefore = audits(w);
            assertOpenImport(putProductUnits(w, w.ownId(), "purchaseUnit", "Carton", 409));
            assertOpenImport(putProductUnits(w, w.ownId(), "issueUnit", "strip", 409));
            assertOpenImport(putProductUnits(w, w.ownId(), "conversion", 20, 409));
            assertThat(getProduct(w.clinic(), w.ownId())).as(state + ": nothing changed").isEqualTo(before);
            assertThat(audits(w)).isEqualTo(auditBefore);
            assertThat(auditRowsOf(w.clinic(), "PHARMACY_PRODUCT_CHANGED")).isEmpty();
            // the file was started at the pharmacy; the product is refused wherever the call comes from: the Central Store has no file
            assertThat(ok(HttpMethod.GET, importsUrl(idOf(w.central())), w.clinic().owner(), null, 200).get("items")).isEmpty();
            // the same units are fine, also with another name
            JsonNode renamed = putProductUnits(w, w.ownId(), "name", "Test dressing kit (OT) renamed", 200);
            assertThat(renamed.get("name").asText()).isEqualTo("Test dressing kit (OT) renamed");
            assertThat(renamed.get("purchaseUnit").asText()).isEqualTo(before.get("purchaseUnit").asText());
            assertThat(renamed.get("conversion").asInt()).isEqualTo(before.get("conversion").asInt());
            assertThat(auditRowsOf(w.clinic(), "PHARMACY_PRODUCT_CHANGED")).hasSize(1);
            // a product that is on no line is free to change its units
            JsonNode free = createOwn(w.clinic(), ownBody("Test free product"));
            Map<String, Object> change = updateBody(getProduct(w.clinic(), free.get("productId").asText()));
            change.put("issueUnit", "sachet");
            assertThat(ok(HttpMethod.PUT, productUrl(free.get("productId").asText()), w.clinic().owner(), js(change), 200)
                    .get("issueUnit").asText()).isEqualTo("sachet");
        }
    }

    @Test
    void aProductOnALineOfAnOpenImportCannotBeSwitchedOffButSwitchingOnAndOtherProductsAreAllowed() throws Exception {
        for (String state : List.of("DRAFT", "WAITING")) {
            World w = world();
            open(w, state);
            JsonNode otherAdopted = adopt(w.clinic(), "G-000103"); // on no line
            for (String productId : List.of(w.adoptedId(), w.ownId())) {
                JsonNode before = getProduct(w.clinic(), productId);
                long auditBefore = audits(w);
                assertOpenImport(putActive(w, productId, false, 409));
                assertThat(getProduct(w.clinic(), productId)).as(state + ": still in use, nothing changed").isEqualTo(before);
                assertThat(before.get("localSettings").get("active").asBoolean()).isTrue();
                assertThat(audits(w)).isEqualTo(auditBefore);
                // active:true with another local value is allowed (the product stays in use)
                Map<String, Object> keep = localBody(before);
                keep.put("alias", "alias " + state.toLowerCase());
                keep.put("active", true);
                assertThat(ok(HttpMethod.PUT, localUrl(productId), w.clinic().owner(), js(keep), 200).get("alias").asText())
                        .isEqualTo("alias " + state.toLowerCase());
            }
            assertThat(auditRowsOf(w.clinic(), "PHARMACY_PRODUCT_SWITCHED_OFF")).isEmpty();
            // a product that is on no line may be stopped
            assertThat(putActive(w, otherAdopted.get("productId").asText(), false, 200).get("localSettings").get("active").asBoolean()).isFalse();
            assertThat(auditRowsOf(w.clinic(), "PHARMACY_PRODUCT_SWITCHED_OFF")).hasSize(1);
        }
    }

    // ---------------- which error comes first ----------------

    @Test
    void unitsLockedAndHasStockAnswerBeforeOpenImportForTheProductAndTheStore() throws Exception {
        World w = world();
        open(w, "DRAFT");
        giveStock(w.clinic(), w.ownId());
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.product set has_stock = true where id = ?::uuid", w.adoptedId());
        // product: units-locked first, then product-has-stock; neither is open-import
        JsonNode locked = putProductUnits(w, w.ownId(), "issueUnit", "strip", 409);
        assertType(locked, "units-locked");
        assertThat(locked.get("detail").asText()).isEqualTo("Locked because this product has stock.");
        JsonNode hasStock = putActive(w, w.adoptedId(), false, 409);
        assertType(hasStock, "product-has-stock");
        assertThat(hasStock.get("detail").asText()).isNotEqualTo(OPEN_IMPORT_TEXT);
        // store: store-has-stock first on a switch-off and on a code change
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.store set has_stock = true where id = ?::uuid", w.pharmacyId());
        JsonNode off = problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null), 409);
        assertType(off, "store-has-stock");
        JsonNode store = getStore(w, w.pharmacyId());
        JsonNode code = problem(call(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(), storeBody(store, "Main Pharmacy", "PHARM2")), 409);
        assertType(code, "store-has-stock");
    }

    // ---------------- when the file is no longer open ----------------

    @Test
    void afterRejectTheStoreAndTheProductsMayChangeAgain() throws Exception {
        for (String state : List.of("DRAFT", "WAITING")) {
            World w = world();
            String id = open(w, state);
            assertOpenImport(putActive(w, w.ownId(), false, 409));
            // DEC-076 (contract 0.2.1): an owner may reject a DRAFT as well as a waiting import, so a draft nobody finishes frees the store
            assertThat(ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/reject", w.second().auth(), REJECT, 200).get("status").asText())
                    .isEqualTo("REJECTED");
            assertFree(w);
        }
    }

    @Test
    void afterTheFileIsNotAcceptedTheStoreAndTheProductsMayChangeAgain() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.pharmacyId(), 2));
        addRows(w.keeper().auth(), w.pharmacyId(), id, rows(
                row(1, "PHARM", w.codeB(), "NA1", farExpiry(), "10", "5", "3"),
                row(2, "PHARM", "G-999999", "NA2", farExpiry(), "10", "5", "1"))); // unknown product: an error
        // while it is a DRAFT the product on row 1 and the store are held
        assertOpenImport(putActive(w, w.ownId(), false, 409));
        assertOpenImport(problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null), 409));
        assertType(problem(call(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/submit", w.keeper().auth(), null), 422), "opening-stock-invalid");
        assertThat(getImport(w.keeper().auth(), w.pharmacyId(), id).get("status").asText()).isEqualTo("NOT_ACCEPTED");
        assertFree(w);
    }

    /** The store and the product may change: code, units, stop using, and the store may be switched off. */
    private void assertFree(World w) throws Exception {
        JsonNode store = getStore(w, w.pharmacyId());
        assertThat(ok(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(), storeBody(store, "Main Pharmacy", "PHARM2"), 200)
                .get("code").asText()).isEqualTo("PHARM2");
        assertThat(putProductUnits(w, w.ownId(), "issueUnit", "strip", 200).get("issueUnit").asText()).isEqualTo("strip");
        assertThat(putActive(w, w.ownId(), false, 200).get("localSettings").get("active").asBoolean()).isFalse();
        assertThat(putActive(w, w.adoptedId(), false, 200).get("localSettings").get("active").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null, 200).get("status").asText())
                .isEqualTo("INACTIVE");
    }

    @Test
    void aReplacedImportNoLongerHoldsItsProductsWhileTheNewDraftHoldsTheStore() throws Exception {
        World w = world();
        String first = open(w, "DRAFT"); // products A and B on its lines
        // the uploader starts again: the first draft is REPLACED, the new one has no rows yet
        String second = idOf(startImport(w.keeper().auth(), w.pharmacyId(), 1));
        assertThat(getImport(w.keeper().auth(), w.pharmacyId(), first).get("status").asText()).isEqualTo("REPLACED");
        assertThat(getImport(w.keeper().auth(), w.pharmacyId(), second).get("status").asText()).isEqualTo("DRAFT");
        // the products are on the lines of a REPLACED import only: free
        assertThat(putProductUnits(w, w.ownId(), "issueUnit", "strip", 200).get("issueUnit").asText()).isEqualTo("strip");
        assertThat(putActive(w, w.adoptedId(), false, 200).get("localSettings").get("active").asBoolean()).isFalse();
        // the store is held by the new draft
        assertOpenImport(problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null), 409));
        JsonNode store = getStore(w, w.pharmacyId());
        assertOpenImport(problem(call(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(), storeBody(store, "Main Pharmacy", "PHARM2")), 409));
    }

    @Test
    void afterApprovalTheHasStockRulesApplyAndOpenImportIsNoLongerAnswered() throws Exception {
        World w = world();
        String id = open(w, "WAITING");
        assertOpenImport(putActive(w, w.ownId(), false, 409));
        assertThat(ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200).get("status").asText())
                .isEqualTo("APPROVED");
        assertType(problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null), 409), "store-has-stock");
        assertType(putProductUnits(w, w.ownId(), "issueUnit", "strip", 409), "units-locked");
        assertType(putActive(w, w.ownId(), false, 409), "product-has-stock");
        assertType(putActive(w, w.adoptedId(), false, 409), "product-has-stock");
        JsonNode store = getStore(w, w.pharmacyId());
        assertType(problem(call(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(), storeBody(store, "Main Pharmacy", "PHARM2")), 409),
                "store-has-stock");
        // a rename still works and an unchanged code is still fine
        assertThat(ok(HttpMethod.PUT, STORES + "/" + w.pharmacyId(), w.clinic().owner(), storeBody(store, "Main Pharmacy B", "PHARM"), 200)
                .get("name").asText()).isEqualTo("Main Pharmacy B");
    }

    // ---------------- tenants ----------------

    @Test
    void anotherClinicsOpenImportNeverBlocksThisClinic() throws Exception {
        World a = world();
        World b = world(); // the same store codes (PHARM, W3B) and the same catalogue product G-000101 in both clinics
        open(b, "DRAFT");
        assertOpenImport(putActive(b, b.adoptedId(), false, 409)); // control: the same change is refused where the file is
        // clinic A has no file: nothing of clinic B holds its store or its products
        assertThat(putActive(a, a.adoptedId(), false, 200).get("localSettings").get("active").asBoolean()).isFalse();
        assertThat(putProductUnits(a, a.ownId(), "issueUnit", "strip", 200).get("issueUnit").asText()).isEqualTo("strip");
        JsonNode store = getStore(a, a.pharmacyId());
        assertThat(ok(HttpMethod.PUT, STORES + "/" + a.pharmacyId(), a.clinic().owner(), storeBody(store, "Main Pharmacy", "PHARM2"), 200)
                .get("code").asText()).isEqualTo("PHARM2");
        assertThat(ok(HttpMethod.POST, STORES + "/" + a.pharmacyId() + "/switch-off", a.clinic().owner(), null, 200).get("status").asText())
                .isEqualTo("INACTIVE");
        // and B is still held
        assertOpenImport(problem(call(HttpMethod.POST, STORES + "/" + b.pharmacyId() + "/switch-off", b.clinic().owner(), null), 409));
    }

    @Test
    void anotherClinicsStoreOrProductIsStill404EvenWhenAFileIsOpenHere() throws Exception {
        World a = world();
        World b = world();
        open(a, "DRAFT");
        assertType(problem(call(HttpMethod.POST, STORES + "/" + a.pharmacyId() + "/switch-off", b.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.PUT, localUrl(a.ownId()), b.clinic().owner(), js(localBody(getProduct(b.clinic(), b.ownId())))), 404), "not-found");
    }

    // ---------------- who may see the sentence ----------------

    @Test
    void aNonOwnerIsRefusedBeforeTheOpenImportRuleIsReached() throws Exception {
        World w = world();
        open(w, "DRAFT");
        assertType(problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.keeper().auth(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, localUrl(w.ownId()), w.keeper().auth(), js(localBody(getProduct(w.clinic(), w.ownId())))), 403), "forbidden");
    }
}
