package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79-3 and 79-4, the stock reads (pharmacy.yaml 0.3.3): overview, batches, one product's stock, low stock, FEFO pick,
 * a product in all stores and in my store. The fixture posts the clean three-row file: product A (a Schedule H catalogue
 * product) has TESTB001 (100) and TESTB003 (3, later expiry), product B has TESTB002 (40), all in the Main Pharmacy.
 * Test plan: docs/test-plans/SAH-79.md (T79-3-*, T79-4-*).
 */
@OnTestDatabase
class PharmacyWpDStockReadsApiIT extends Sah79ApiSupport {

    private static String store(Object storeId, String path) {
        return STORES + "/" + storeId + "/stock/" + path;
    }

    // ---------------- overview ----------------

    @Test
    void theOverviewOfAStoreWithNoStockIsEmptyAndAfterPostingShowsTheCountsAndTheValue() throws Exception {
        Sw w = stockWorld();
        JsonNode none = ok(HttpMethod.GET, store(w.pharmacyId(), "overview"), w.keeper().auth(), null, 200);
        assertThat(none.get("empty").asBoolean()).isTrue();
        assertThat(none.get("skuCount").asInt()).isZero();
        assertThat(none.get("batchCount").asInt()).isZero();
        postedImport(w);
        JsonNode o = ok(HttpMethod.GET, store(w.pharmacyId(), "overview"), w.keeper().auth(), null, 200);
        assertThat(o.get("empty").asBoolean()).isFalse();
        assertThat(o.get("skuCount").asInt()).isEqualTo(2);
        assertThat(o.get("batchCount").asInt()).isEqualTo(3);
        assertThat(o.get("stockValuePaise").asLong()).isEqualTo(CLEAN_FILE_PAISE);
        assertThat(o.get("pills").get("scheduleProducts").asInt()).isEqualTo(1);
        assertThat(o.get("pills").get("nearExpiry90").asInt()).isZero();
        assertThat(o.get("pills").get("expiredQuarantined").asInt()).isZero();
        assertThat(o.get("pills").get("lowStock").asInt()).isZero();
        assertThat(o.get("store").get("storeId").asText()).isEqualTo(w.pharmacyId());
    }

    @Test
    void theOverviewOfTheOtherStoreIsStillEmptyBecauseStockBelongsToOneStore() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        JsonNode central = ok(HttpMethod.GET, store(w.centralId(), "overview"), w.clinic().owner(), null, 200);
        assertThat(central.get("empty").asBoolean()).isTrue();
        assertThat(central.get("batchCount").asInt()).isZero();
    }

    // ---------------- batches ----------------

    @Test
    void theBatchListShowsOneRowPerBatchAndStateOrderedByProductNameThenFefo() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        JsonNode page = ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), null), w.keeper().auth(), null, 200);
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("items")).hasSize(3);
        for (JsonNode row : page.get("items")) {
            assertThat(row.get("state").asText()).isEqualTo("AVAILABLE");
            assertThat(row.get("qty").asInt()).isPositive();
        }
        // product A: the earlier expiry first (TESTB001), then TESTB003; each with its FEFO rank
        JsonNode first = null;
        JsonNode second = null;
        for (JsonNode row : page.get("items")) {
            if (row.get("product").get("productId").asText().equals(w.productA())) {
                if (first == null) {
                    first = row;
                } else {
                    second = row;
                }
            }
        }
        assertThat(first.get("batch").get("batchNo").asText()).isEqualTo("TESTB001");
        assertThat(first.get("fefoRank").asInt()).isEqualTo(1);
        assertThat(first.get("qty").asInt()).isEqualTo(100);
        assertThat(first.get("mrp").asText()).startsWith("45.5");
        assertThat(second.get("batch").get("batchNo").asText()).isEqualTo("TESTB003");
        assertThat(second.get("fefoRank").asInt()).isEqualTo(2);
        assertThat(first.get("supplier").isNull()).as("opening stock has no vendor").isTrue();
    }

    @Test
    void theBatchViewsFilterAndTheSearchFindsByNameCaseInsensitively() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        JsonNode schedule = ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "view=SCHEDULE"), w.keeper().auth(), null, 200);
        assertThat(schedule.get("items")).hasSize(2);
        assertThat(ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "view=NEAR_EXPIRY&withinDays=30"), w.keeper().auth(), null, 200).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "view=EXPIRED"), w.keeper().auth(), null, 200).get("items")).isEmpty();
        JsonNode found = ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "q=CEFTRIAX"), w.keeper().auth(), null, 200);
        assertThat(found.get("items")).hasSize(2);
        assertThat(ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "q=zzzzzz"), w.keeper().auth(), null, 200).get("items")).isEmpty();
        JsonNode paged = ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "size=1&page=2"), w.keeper().auth(), null, 200);
        assertThat(paged.get("items")).hasSize(1);
        assertThat(paged.get("total").asInt()).isEqualTo(3);
    }

    @Test
    void theBatchListRefusesBadInputWith400AndAProblemBody() throws Exception {
        Sw w = stockWorld();
        java.util.List<String> notRefused = new java.util.ArrayList<>();
        for (String bad : new String[] {"view=SOON", "withinDays=45", "q=a", "size=0", "size=101", "page=-1", "page=1001"}) {
            int status = call(HttpMethod.GET, batchesUrl(w.pharmacyId(), bad), w.keeper().auth(), null).andReturn().getResponse().getStatus();
            if (status != 400) {
                notRefused.add(bad + " answered " + status);
            }
        }
        assertThat(notRefused).as("every bad parameter is 400").isEmpty();
    }

    // ---------------- one product ----------------

    @Test
    void oneProductShowsItsBatchesInFefoOrderWithTheStoreSettings() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        reorderLevel(w, w.productA(), w.pharmacyId(), 20);
        JsonNode p = ok(HttpMethod.GET, store(w.pharmacyId(), "products/" + w.productA()), w.keeper().auth(), null, 200);
        assertThat(p.get("product").get("productId").asText()).isEqualTo(w.productA());
        assertThat(p.get("reorderLevel").asInt()).isEqualTo(20);
        assertThat(p.get("batches")).hasSize(2);
        assertThat(p.get("batches").get(0).get("batch").get("batchNo").asText()).isEqualTo("TESTB001");
        assertThat(p.get("batches").get(0).get("fefoRank").asInt()).isEqualTo(1);
        assertThat(p.get("batches").get(1).get("fefoRank").asInt()).isEqualTo(2);
    }

    @Test
    void aProductThatIsNotInTheUnitAnswers404AndABadIdAnswers400() throws Exception {
        Sw w = stockWorld();
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "products/" + UUID.randomUUID()), w.keeper().auth(), null), 404), "not-found");
        problem(call(HttpMethod.GET, store(w.pharmacyId(), "products/not-a-uuid"), w.keeper().auth(), null), 400);
    }

    // ---------------- low stock ----------------

    @Test
    void lowStockListsAProductAtOrBelowTheReorderLevelAndNotAboveIt() throws Exception {
        Sw w = stockWorld();
        postedImport(w); // product A has 103 available
        reorderLevel(w, w.productA(), w.pharmacyId(), 102);
        assertThat(ok(HttpMethod.GET, store(w.pharmacyId(), "low-stock"), w.keeper().auth(), null, 200).get("items")).as("above the level").isEmpty();
        reorderLevel(w, w.productA(), w.pharmacyId(), 103);
        JsonNode atLevel = ok(HttpMethod.GET, store(w.pharmacyId(), "low-stock"), w.keeper().auth(), null, 200);
        assertThat(atLevel.get("items")).as("at the level counts as low").hasSize(1);
        assertThat(atLevel.get("items").get(0).get("available").asInt()).isEqualTo(103);
        assertThat(atLevel.get("items").get(0).get("reorderLevel").asInt()).isEqualTo(103);
        assertThat(atLevel.get("items").get(0).has("action")).isTrue();
        assertThat(ok(HttpMethod.GET, store(w.pharmacyId(), "overview"), w.keeper().auth(), null, 200).get("pills").get("lowStock").asInt()).isEqualTo(1);
        reorderLevel(w, w.productA(), w.pharmacyId(), 200);
        assertThat(ok(HttpMethod.GET, store(w.pharmacyId(), "low-stock"), w.keeper().auth(), null, 200).get("items")).hasSize(1);
    }

    @Test
    void aProductWithNoReorderLevelIsNeverLowStock() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        assertThat(ok(HttpMethod.GET, store(w.pharmacyId(), "low-stock"), w.keeper().auth(), null, 200).get("items")).isEmpty();
    }

    // ---------------- FEFO pick ----------------

    @Test
    void theFefoPickTakesTheEarliestExpiryFirstAndNamesTheShortage() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        JsonNode pick = ok(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + w.productA() + "&quantity=101"), w.keeper().auth(), null, 200);
        assertThat(pick.get("availableQty").asInt()).isEqualTo(103);
        assertThat(pick.get("shortBy").asInt()).isZero();
        assertThat(pick.get("batches")).hasSize(2);
        assertThat(pick.get("batches").get(0).get("batch").get("batchNo").asText()).isEqualTo("TESTB001");
        assertThat(pick.get("batches").get(0).get("quantity").asInt()).isEqualTo(100);
        assertThat(pick.get("batches").get(1).get("quantity").asInt()).isEqualTo(1);
        JsonNode short1 = ok(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + w.productA() + "&quantity=200"), w.keeper().auth(), null, 200);
        assertThat(short1.get("shortBy").asInt()).isEqualTo(97);
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).as("a pick is a preview").isEqualTo(3);
    }

    @Test
    void theFefoPickRefusesAMissingProductAQuantityOutsideTheRangeAndAnUnknownProduct() throws Exception {
        Sw w = stockWorld();
        problem(call(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?quantity=1"), w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + w.productA()), w.keeper().auth(), null), 400);
        for (String q : new String[] {"0", "1000000", "-1", "x"}) {
            problem(call(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + w.productA() + "&quantity=" + q), w.keeper().auth(), null), 400);
        }
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + UUID.randomUUID() + "&quantity=1"),
                w.keeper().auth(), null), 404), "not-found");
        // the boundaries are allowed
        ok(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + w.productA() + "&quantity=1"), w.keeper().auth(), null, 200);
        ok(HttpMethod.GET, store(w.pharmacyId(), "fefo-pick?productId=" + w.productA() + "&quantity=999999"), w.keeper().auth(), null, 200);
    }

    // ---------------- all stores and my store ----------------

    @Test
    void theOwnerSeesEveryActiveStoreAndTheUnitTotalAndAPharmacistOnlyTheMappedStore() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        String url = PH + "/stock/products/" + w.productA() + "/stores";
        JsonNode owner = ok(HttpMethod.GET, url, w.clinic().owner(), null, 200);
        assertThat(owner.get("rows")).hasSize(2);
        assertThat(owner.get("total").get("available").asInt()).isEqualTo(103);
        assertThat(owner.get("total").get("store").isNull()).isTrue();
        assertThat(owner.get("empty").asBoolean()).isFalse();
        JsonNode keeper = ok(HttpMethod.GET, url, w.keeper().auth(), null, 200);
        assertThat(keeper.get("rows")).hasSize(1);
        assertThat(keeper.get("rows").get(0).get("store").get("storeId").asText()).isEqualTo(w.pharmacyId());
        assertThat(keeper.get("rows").get(0).get("available").asInt()).isEqualTo(103);
        assertThat(keeper.get("rows").get(0).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void aPharmacistMappedToNoStoreGets200WithNoRowsAndNeverAnError() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        Pn unmapped = crew(w.clinic(), "PHARMACIST", "Una Unmapped");
        JsonNode r = ok(HttpMethod.GET, PH + "/stock/products/" + w.productA() + "/stores", unmapped.auth(), null, 200);
        assertThat(r.get("rows")).isEmpty();
        assertThat(r.get("empty").asBoolean()).isTrue();
        assertThat(r.get("total").get("available").asInt()).isZero();
    }

    @Test
    void theStatusIsBelowReorderAtOrBelowTheLevelInAllStoresAndInMyStore() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        reorderLevel(w, w.productA(), w.pharmacyId(), 103);
        JsonNode all = ok(HttpMethod.GET, PH + "/stock/products/" + w.productA() + "/stores", w.keeper().auth(), null, 200);
        assertThat(all.get("rows").get(0).get("status").asText()).isEqualTo("BELOW_REORDER");
        JsonNode mine = ok(HttpMethod.GET, store(w.pharmacyId(), "products/" + w.productA() + "/states"), w.keeper().auth(), null, 200);
        assertThat(mine.get("rows")).hasSize(1);
        assertThat(mine.get("rows").get(0).get("status").asText()).isEqualTo("BELOW_REORDER");
        reorderLevel(w, w.productA(), w.pharmacyId(), 102);
        assertThat(ok(HttpMethod.GET, store(w.pharmacyId(), "products/" + w.productA() + "/states"), w.keeper().auth(), null, 200)
                .get("rows").get(0).get("status").asText()).isEqualTo("OK");
    }

    @Test
    void myStoreForAStoreThePersonIsNotMappedToAnswers404AndAnUnknownProductAnswers404() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "products/" + w.productA() + "/states"), w.csUser().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "products/" + UUID.randomUUID() + "/states"), w.keeper().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, PH + "/stock/products/" + UUID.randomUUID() + "/stores", w.keeper().auth(), null), 404), "not-found");
    }

    // ---------------- guards, tenants, module ----------------

    @Test
    void everyStockReadAnswers404ForAnUnmappedPharmacistAnd403ForARoleWithoutPharmacyAccess() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        String[] reads = {"overview", "batches", "low-stock", "products/" + w.productA(), "fefo-pick?productId=" + w.productA() + "&quantity=1"};
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK");
        for (String read : reads) {
            assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), read), w.csUser().auth(), null), 404), "not-found");
            problem(call(HttpMethod.GET, store(w.pharmacyId(), read), frontDesk.auth(), null), 403);
            ok(HttpMethod.GET, store(w.pharmacyId(), read), w.clinic().owner(), null, 200);
        }
    }

    @Test
    void anotherClinicsStoreAndStockAreInvisible() throws Exception {
        Sw w = stockWorld();
        Sw other = stockWorld();
        postedImport(w);
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "overview"), other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "products/" + w.productA()), other.clinic().owner(), null), 404), "not-found");
        // the other clinic's all-stores read of its own product shows zeros, never this clinic's 103
        JsonNode mine = ok(HttpMethod.GET, PH + "/stock/products/" + other.productA() + "/stores", other.clinic().owner(), null, 200);
        assertThat(mine.get("total").get("available").asInt()).isZero();
        assertThat(mine.get("empty").asBoolean()).isTrue();
        assertType(problem(call(HttpMethod.GET, PH + "/stock/products/" + w.productA() + "/stores", other.clinic().owner(), null), 404), "not-found");
    }

    @Test
    void theModuleSwitchedOffAnswers403ModuleOffOnTheStockReads() throws Exception {
        Sw w = stockWorld();
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, store(w.pharmacyId(), "overview"), w.clinic().owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, PH + "/stock/products/" + w.productA() + "/stores", w.clinic().owner(), null), 403), "module-off");
    }

    @Test
    void theContextListsTheNewCapabilitiesAndTheInventoryMenuForAPharmacist() throws Exception {
        Sw w = stockWorld();
        JsonNode ctx = contextOf(w.keeper().auth());
        JsonNode mine = null;
        for (JsonNode store : ctx.get("stores")) {
            if (store.get("storeId") != null && store.get("storeId").asText().equals(w.pharmacyId())) {
                mine = store;
            } else if (store.get("id") != null && store.get("id").asText().equals(w.pharmacyId())) {
                mine = store;
            }
        }
        assertThat(mine).as("the mapped store is in the context").isNotNull();
        JsonNode access = mine.get("access");
        assertThat(access.get("readStock").asBoolean()).isTrue();
        assertThat(access.get("postOpeningStock").asBoolean()).isTrue();
        assertThat(access.get("adjustStock").asBoolean()).isTrue();
        // java round 6 (D-4): a plain pharmacist reads stock (INVENTORY, STOCK_REPORTS); the approvals inbox is the owner's, a HOD's or a delegate's
        java.util.List<String> caps = new java.util.ArrayList<>();
        ctx.get("capabilities").forEach(k -> caps.add(k.asText()));
        assertThat(caps).contains("INVENTORY", "STOCK_REPORTS").doesNotContain("APPROVALS_INBOX", "PURCHASE");
        assertThat(java.util.List.of(w.pharmacyId())).isNotEmpty();
        java.util.List<String> ownerCaps = new java.util.ArrayList<>();
        contextOf(w.clinic().owner()).get("capabilities").forEach(k -> ownerCaps.add(k.asText()));
        assertThat(ownerCaps).contains("INVENTORY", "APPROVALS_INBOX", "PURCHASE", "STOCK_REPORTS");
        Pn hod = crew(w.clinic(), "PHARMACIST", "Hari Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        java.util.List<String> hodCaps = new java.util.ArrayList<>();
        contextOf(hod.auth()).get("capabilities").forEach(k -> hodCaps.add(k.asText()));
        assertThat(hodCaps).contains("INVENTORY", "APPROVALS_INBOX", "STOCK_REPORTS").doesNotContain("PURCHASE");
        // the Central Store user is not mapped at the pharmacy: that store is not in the list, or has no capability
        JsonNode cs = contextOf(w.csUser().auth());
        assertThat(cs.toString()).doesNotContain(w.pharmacyId());
        java.util.List<String> csCaps = new java.util.ArrayList<>();
        cs.get("capabilities").forEach(k -> csCaps.add(k.asText()));
        assertThat(csCaps).contains("PURCHASE");
    }
}
