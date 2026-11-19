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
 * SAH-79-6, the purchase orders and the reorder list (pharmacy.yaml 0.3.3, the 12 operations of WP-E): the store guard (the
 * Central Store only), the reorder suggestions, create (manual and from suggestions), edit, discard, submit, mark sent,
 * cancel, close short, copy, the list and its tiles, the approver reading the order, and two clinics. Product A is a
 * catalogue product with 12 percent GST, purchase unit box, issue unit vial, conversion 10; the Central Store holds 103 of it.
 * Test plan: docs/test-plans/SAH-79.md (T79-6-*).
 */
@OnTestDatabase
class PharmacyWpEPurchaseOrderApiIT extends Sah79ApiSupport {

    private static final String CAN_NOT_EDIT_TEXT = "A submitted purchase order cannot be edited. Cancel it and raise a new one.";
    private static final String CENTRAL_ONLY_TEXT = "Purchase orders are raised at the Central Store.";

    private record Pw(Sw w, String vendor, String vendor2) {
    }

    /** The world with 103 of product A in the Central Store (posted by the Central Store pharmacist) and two vendors that supply it. */
    private Pw world(boolean noRules) throws Exception {
        Sw w = stockWorld();
        String id = approvedImport(w, w.csUser(), "CS", w.centralId());
        postImport(w.csUser().auth(), w.centralId(), id, UUID.randomUUID());
        if (noRules) {
            noApprovalRules(w);
        } else {
            ownerAlways(w, "PURCHASE_ORDER"); // the engine applies a rule only once rules are saved (defect D-5)
        }
        return new Pw(w, supplying(w, "Po Vendor One (fake)"), supplying(w, "Po Vendor Two (fake)"));
    }

    private String supplying(Sw w, String name) throws Exception {
        Map<String, Object> body = vendorBody(name);
        body.put("suppliesToStoreIds", List.of(w.centralId()));
        return idOf(createVendor(w.clinic(), body));
    }

    private static String pos(Object storeId) {
        return STORES + "/" + storeId + "/purchase-orders";
    }

    private static String delivery() {
        return todayIst().plusDays(7).toString();
    }

    private Map<String, Object> line(String productId, int qty, String rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("qty", qty);
        m.put("rate", rate);
        return m;
    }

    private Map<String, Object> createBody(UUID key, String vendor, Object deliveryExpected, int creditDays, List<Map<String, Object>> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("vendorId", vendor);
        m.put("deliveryExpected", deliveryExpected);
        m.put("creditDays", creditDays);
        m.put("lines", lines);
        return m;
    }

    private JsonNode draft(Pw p, Pn who) throws Exception {
        return ok(HttpMethod.POST, pos(p.w().centralId()), who.auth(),
                js(createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(p.w().productA(), 10, "70.00")))), 201);
    }

    private Map<String, Object> versionBody(JsonNode po) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", po.get("version").asLong());
        return m;
    }

    private JsonNode submitPo(Pw p, Pn who, JsonNode po, int expected) throws Exception {
        Map<String, Object> m = versionBody(po);
        m.put("clientKey", UUID.randomUUID().toString());
        String url = pos(p.w().centralId()) + "/" + idOf(po) + "/submit";
        return expected == 200 ? ok(HttpMethod.POST, url, who.auth(), js(m), 200) : problem(call(HttpMethod.POST, url, who.auth(), js(m)), expected);
    }

    private JsonNode action(Pw p, Pn who, JsonNode po, String name, Map<String, Object> body, int expected) throws Exception {
        String url = pos(p.w().centralId()) + "/" + idOf(po) + "/" + name;
        return expected == 200 || expected == 201 ? ok(HttpMethod.POST, url, who.auth(), js(body), expected)
                : problem(call(HttpMethod.POST, url, who.auth(), js(body)), expected);
    }

    private JsonNode getPo(Pw p, Pn who, String id) throws Exception {
        return ok(HttpMethod.GET, pos(p.w().centralId()) + "/" + id, who.auth(), null, 200);
    }

    // ---------------- the store guard ----------------

    @Test
    void onlyTheCentralStoreUserAndTheOwnerPassTheGuardAndAnotherStoreIs409DocStateForTheOwner() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        ok(HttpMethod.GET, pos(w.centralId()), w.csUser().auth(), null, 200);
        ok(HttpMethod.GET, pos(w.centralId()), w.clinic().owner(), null, 200);
        ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/reorder-suggestions", w.csUser().auth(), null, 200);
        // the owner at a non-central store: 409 doc-state with the sentence
        JsonNode state = problem(call(HttpMethod.GET, pos(w.pharmacyId()), w.clinic().owner(), null), 409);
        assertType(state, "doc-state");
        assertThat(state.get("detail").asText()).isEqualTo(CENTRAL_ONLY_TEXT);
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/reorder-suggestions", w.clinic().owner(), null), 409), "doc-state");
        // a mapped pharmacist of another store: 403 where he is mapped (no purchase capability), 404 where he is not
        assertType(problem(call(HttpMethod.GET, pos(w.pharmacyId()), w.keeper().auth(), null), 403), "store-permission-denied");
        assertType(problem(call(HttpMethod.GET, pos(w.centralId()), w.keeper().auth(), null), 404), "not-found");
        problem(call(HttpMethod.GET, pos(w.centralId()), crew(w.clinic(), "FRONT_DESK").auth(), null), 403);
        problem(call(HttpMethod.POST, pos(w.centralId()), w.keeper().auth(), js(createBody(UUID.randomUUID(), p.vendor(), delivery(), 30,
                List.of(line(w.productA(), 1, "10"))))), 404);
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).isZero();
    }

    @Test
    void theModuleSwitchedOffAnswers403ModuleOff() throws Exception {
        Pw p = world(true);
        pharmacyOn(p.w().clinic(), false);
        assertType(problem(call(HttpMethod.GET, pos(p.w().centralId()), p.w().clinic().owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, STORES + "/" + p.w().centralId() + "/reorder-suggestions", p.w().clinic().owner(), null), 403), "module-off");
    }

    // ---------------- reorder suggestions ----------------

    @Test
    void theReorderListHasAProductAtOrBelowItsLevelWithTheSuggestedQuantityInWholePurchaseUnits() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        String url = STORES + "/" + w.centralId() + "/reorder-suggestions";
        assertThat(ok(HttpMethod.GET, url, w.csUser().auth(), null, 200).get("items")).as("no level set").isEmpty();
        reorderLevel(w, w.productA(), w.centralId(), 102);
        assertThat(ok(HttpMethod.GET, url, w.csUser().auth(), null, 200).get("items")).as("103 is above 102").isEmpty();
        reorderLevel(w, w.productA(), w.centralId(), 150);
        JsonNode row = ok(HttpMethod.GET, url, w.csUser().auth(), null, 200).get("items").get(0);
        assertThat(row.get("product").get("productId").asText()).isEqualTo(w.productA());
        assertThat(row.get("csStock").asInt()).isEqualTo(103);
        assertThat(row.get("reorderLevel").asInt()).isEqualTo(150);
        assertThat(row.get("suggestedQty").asInt()).as("ceil(47 / 10)").isEqualTo(5);
        assertThat(row.get("openPoQty").asInt()).isZero();
        assertThat(row.get("openPoNo").isNull()).isTrue();
        assertThat(row.get("allStoresStock").isNull()).as("owner only").isTrue();
        assertThat(ok(HttpMethod.GET, url, w.clinic().owner(), null, 200).get("items").get(0).get("allStoresStock").asInt()).isEqualTo(103);
        reorderLevel(w, w.productA(), w.centralId(), 104);
        assertThat(ok(HttpMethod.GET, url, w.csUser().auth(), null, 200).get("items").get(0).get("suggestedQty").asInt()).as("need 1 gives at least 1").isOne();
        reorderLevel(w, w.productA(), w.centralId(), 103);
        assertThat(ok(HttpMethod.GET, url, w.csUser().auth(), null, 200).get("items")).as("at the level and no open order: need 0").hasSize(1);
        problem(call(HttpMethod.GET, url + "?size=101", w.csUser().auth(), null), 400);
    }

    @Test
    void anOpenOrderCountsTowardTheLevelAndAnOrderThatCoversTheLevelExactlyGivesSuggestedZeroWithItsNumber() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        reorderLevel(w, w.productA(), w.centralId(), 143);
        JsonNode po = ok(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(w.productA(), 4, "70.00")))), 201);
        JsonNode submitted = submitPo(p, w.csUser(), po, 200);
        assertThat(submitted.get("status").asText()).as("no rule applies").isEqualTo("APPROVED");
        JsonNode row = ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/reorder-suggestions", w.csUser().auth(), null, 200).get("items").get(0);
        assertThat(row.get("openPoQty").asInt()).isEqualTo(4);
        assertThat(row.get("openPoNo").asText()).isEqualTo(submitted.get("number").asText());
        assertThat(row.get("suggestedQty").asInt()).isZero();
        reorderLevel(w, w.productA(), w.centralId(), 142);
        assertThat(ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/reorder-suggestions", w.csUser().auth(), null, 200).get("items")).isEmpty();
    }

    // ---------------- create ----------------

    @Test
    void aManualPoIsADraftWithNoNumberAndTheAmountsAreWorkedPerLineWithGst() throws Exception {
        Pw p = world(true);
        JsonNode po = draft(p, p.w().csUser());
        assertThat(po.get("status").asText()).isEqualTo("DRAFT");
        assertThat(po.get("number").isNull()).isTrue();
        assertThat(po.get("version").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(po.get("creditDays").asInt()).isEqualTo(30);
        assertThat(po.get("deliveryExpected").asText()).isEqualTo(delivery());
        assertThat(po.get("lines")).hasSize(1);
        assertThat(po.get("lines").get(0).get("amountPaise").asLong()).as("10 x 70.00").isEqualTo(70_000);
        assertThat(po.get("lines").get(0).get("gstPaise").asLong()).as("12 percent").isEqualTo(8_400);
        assertThat(po.get("subtotalPaise").asLong()).isEqualTo(70_000);
        assertThat(po.get("gstPaise").asLong()).isEqualTo(8_400);
        assertThat(po.get("totalPaise").asLong()).isEqualTo(78_400);
        assertThat(po.get("vendor").get("vendorId").asText()).isEqualTo(p.vendor());
        assertThat(po.get("raisedBy").get("name").asText()).isEqualTo("Chandra Central");
        assertThat(po.get("print")).isNotNull();
        assertThat(auditRowsOf(p.w().clinic(), "PHARMACY_PO_CREATED")).hasSize(1);
    }

    @Test
    void theSameClientKeyReplaysAndTheSameKeyWithAnotherBodyIsRefused() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        UUID key = UUID.randomUUID();
        Map<String, Object> body = createBody(key, p.vendor(), delivery(), 30, List.of(line(w.productA(), 10, "70.00")));
        JsonNode first = ok(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(), js(body), 201);
        JsonNode replay = ok(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(), js(body), 200);
        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).isOne();
        body.put("creditDays", 31);
        assertThat(idOf(ok(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(), js(body), 200))).as("the same caller and key: the first answer again").isEqualTo(idOf(first));
        assertType(problem(call(HttpMethod.POST, pos(w.centralId()), w.clinic().owner(), js(body)), 409), "client-key-reused");
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).isOne();
    }

    @Test
    void theCreateRulesAreCheckedTogetherAnd400WithAProblemBody() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        String url = pos(w.centralId());
        Pn who = w.csUser();
        String prod = w.productA();
        Object[][] bad = {
            {"no lines", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of())},
            {"qty 0", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(prod, 0, "10")))},
            {"qty 1000000", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(prod, 1_000_000, "10")))},
            {"rate 0", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(prod, 1, "0")))},
            {"rate 5 decimals", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(prod, 1, "1.00001")))},
            {"credit 366", createBody(UUID.randomUUID(), p.vendor(), delivery(), 366, List.of(line(prod, 1, "10")))},
            {"credit -1", createBody(UUID.randomUUID(), p.vendor(), delivery(), -1, List.of(line(prod, 1, "10")))},
            {"delivery in the past", createBody(UUID.randomUUID(), p.vendor(), todayIst().minusDays(1).toString(), 30, List.of(line(prod, 1, "10")))},
            {"the same product twice", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(prod, 1, "10"), line(prod, 2, "10")))},
            {"unknown product", createBody(UUID.randomUUID(), p.vendor(), delivery(), 30, List.of(line(UUID.randomUUID().toString(), 1, "10")))},
            {"unknown vendor", createBody(UUID.randomUUID(), UUID.randomUUID().toString(), delivery(), 30, List.of(line(prod, 1, "10")))},
        };
        List<String> notRefused = new ArrayList<>();
        for (Object[] b : bad) {
            int status = call(HttpMethod.POST, url, who.auth(), js((Map<String, Object>) b[1])).andReturn().getResponse().getStatus();
            if (status != 400) {
                notRefused.add(b[0] + " answered " + status);
            }
        }
        assertThat(notRefused).as("every bad body is 400").isEmpty();
        // a vendor that does not supply the Central Store
        Map<String, Object> pharmacyOnly = vendorBody("Pharmacy only vendor (fake)");
        pharmacyOnly.put("suppliesToStoreIds", List.of(w.pharmacyId()));
        String notCentral = idOf(createVendor(w.clinic(), pharmacyOnly));
        problem(call(HttpMethod.POST, url, who.auth(), js(createBody(UUID.randomUUID(), notCentral, delivery(), 30, List.of(line(prod, 1, "10"))))), 400);
        assertType(problem(call(HttpMethod.POST, url, who.auth(), "{}"), 400), "validation");
        assertType(problem(call(HttpMethod.POST, url, who.auth(), "not json"), 400), "malformed-request");
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).isZero();
        // the boundaries: qty 1 and 999999, rate with 4 decimals, credit 0 and 365, delivery today
        ok(HttpMethod.POST, url, who.auth(), js(createBody(UUID.randomUUID(), p.vendor(), todayIst().toString(), 0, List.of(line(prod, 1, "0.0001")))), 201);
        ok(HttpMethod.POST, url, who.auth(), js(createBody(UUID.randomUUID(), p.vendor(), delivery(), 365, List.of(line(prod, 999_999, "70.0000")))), 201);
    }

    @Test
    void aDraftIsCreatedPerVendorFromTheSelectedSuggestionsAndTheSameKeyReplays() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        UUID key = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientKey", key.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(suggestion(w.productA(), p.vendor(), 5, "70.00"));
        rows.add(suggestion(w.productB(), p.vendor2(), 3, "20.00"));
        body.put("rows", rows);
        JsonNode created = ok(HttpMethod.POST, pos(w.centralId()) + "/from-suggestions", w.csUser().auth(), js(body), 201);
        assertThat(created.get("items")).as("one draft per vendor").hasSize(2);
        for (JsonNode po : created.get("items")) {
            assertThat(po.get("status").asText()).isEqualTo("DRAFT");
            assertThat(po.get("number").isNull()).isTrue();
        }
        JsonNode replay = ok(HttpMethod.POST, pos(w.centralId()) + "/from-suggestions", w.csUser().auth(), js(body), 200);
        assertThat(replay.get("items")).hasSize(2);
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).isEqualTo(2);
        // the same vendor for both rows: one draft with two lines
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("clientKey", UUID.randomUUID().toString());
        one.put("rows", List.of(suggestion(w.productA(), p.vendor(), 1, "70.00"), suggestion(w.productB(), p.vendor(), 1, "20.00")));
        JsonNode merged = ok(HttpMethod.POST, pos(w.centralId()) + "/from-suggestions", w.csUser().auth(), js(one), 201);
        assertThat(merged.get("items")).hasSize(1);
        assertThat(merged.get("items").get(0).get("lines")).hasSize(2);
    }

    private Map<String, Object> suggestion(String productId, String vendorId, int qty, String rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("vendorId", vendorId);
        m.put("qty", qty);
        m.put("rate", rate);
        return m;
    }

    @Test
    void theSuggestionRowsAreValidatedARowWithoutAVendorTheSameProductTwiceAndNoRows() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        String url = pos(w.centralId()) + "/from-suggestions";
        Map<String, Object> noVendor = new LinkedHashMap<>();
        noVendor.put("clientKey", UUID.randomUUID().toString());
        noVendor.put("rows", List.of(suggestion(w.productA(), null, 5, "70.00")));
        problem(call(HttpMethod.POST, url, w.csUser().auth(), js(noVendor)), 400);
        Map<String, Object> twice = new LinkedHashMap<>();
        twice.put("clientKey", UUID.randomUUID().toString());
        twice.put("rows", List.of(suggestion(w.productA(), p.vendor(), 5, "70.00"), suggestion(w.productA(), p.vendor(), 2, "70.00")));
        problem(call(HttpMethod.POST, url, w.csUser().auth(), js(twice)), 400);
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("clientKey", UUID.randomUUID().toString());
        empty.put("rows", List.of());
        problem(call(HttpMethod.POST, url, w.csUser().auth(), js(empty)), 400);
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("clientKey", UUID.randomUUID().toString());
        zero.put("rows", List.of(suggestion(w.productA(), p.vendor(), 0, "70.00")));
        problem(call(HttpMethod.POST, url, w.csUser().auth(), js(zero)), 400);
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).as("nothing is half created").isZero();
    }

    // ---------------- edit and discard ----------------

    @Test
    void aDraftCanBeEditedWithTheCurrentVersionAndAStaleVersionAnswers409ChangedWithTheCurrent() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        JsonNode po = draft(p, w.csUser());
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("version", po.get("version").asLong());
        update.put("vendorId", p.vendor2());
        update.put("deliveryExpected", todayIst().plusDays(9).toString());
        update.put("creditDays", 45);
        update.put("lines", List.of(line(w.productA(), 20, "70.00"), line(w.productB(), 5, "20.00")));
        JsonNode saved = ok(HttpMethod.PUT, pos(w.centralId()) + "/" + idOf(po), w.csUser().auth(), js(update), 200);
        assertThat(saved.get("version").asLong()).isEqualTo(po.get("version").asLong() + 1);
        assertThat(saved.get("vendor").get("vendorId").asText()).isEqualTo(p.vendor2());
        assertThat(saved.get("lines")).hasSize(2);
        assertThat(saved.get("creditDays").asInt()).isEqualTo(45);
        JsonNode stale = problem(call(HttpMethod.PUT, pos(w.centralId()) + "/" + idOf(po), w.csUser().auth(), js(update)), 409);
        assertType(stale, "changed");
        assertThat(stale.get("current")).isNotNull();
        assertThat(getPo(p, w.csUser(), idOf(po)).get("lines")).as("the stale save wrote nothing").hasSize(2);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_PO_CHANGED")).hasSize(1);
    }

    @Test
    void aSubmittedOrderCannotBeEditedOrDiscardedAndADraftCanBeDiscardedAndDisappearsFromTheList() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        JsonNode d1 = draft(p, w.csUser());
        JsonNode d2 = draft(p, w.csUser());
        JsonNode submitted = submitPo(p, w.csUser(), d1, 200);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("version", submitted.get("version").asLong());
        update.put("vendorId", p.vendor());
        update.put("deliveryExpected", delivery());
        update.put("creditDays", 30);
        update.put("lines", List.of(line(w.productA(), 1, "10")));
        JsonNode refused = problem(call(HttpMethod.PUT, pos(w.centralId()) + "/" + idOf(d1), w.csUser().auth(), js(update)), 409);
        assertType(refused, "doc-state");
        assertThat(refused.get("detail").asText()).isEqualTo(CAN_NOT_EDIT_TEXT);
        assertType(action(p, w.csUser(), submitted, "discard", versionBody(submitted), 409), "doc-state");
        // a stale version on discard, then a good one
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("version", 7);
        assertType(action(p, w.csUser(), d2, "discard", stale, 409), "changed");
        JsonNode gone = action(p, w.csUser(), d2, "discard", versionBody(d2), 200);
        assertThat(gone.get("status").asText()).isEqualTo("DISCARDED");
        JsonNode list = ok(HttpMethod.GET, pos(w.centralId()), w.csUser().auth(), null, 200);
        assertThat(list.get("total").asInt()).as("a discarded order is never listed").isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_PO_DISCARDED")).hasSize(1);
    }

    // ---------------- submit ----------------

    @Test
    void submitTakesTheNumberAndWithNoRuleTheOrderIsApprovedAtOnce() throws Exception {
        Pw p = world(true);
        JsonNode one = submitPo(p, p.w().csUser(), draft(p, p.w().csUser()), 200);
        JsonNode two = submitPo(p, p.w().csUser(), draft(p, p.w().csUser()), 200);
        assertThat(one.get("status").asText()).isEqualTo("APPROVED");
        assertThat(one.get("approval").isNull()).isTrue();
        assertThat(one.get("number").asText()).isNotBlank();
        assertThat(two.get("number").asText()).isNotEqualTo(one.get("number").asText());
        assertThat(auditRowsOf(p.w().clinic(), "PHARMACY_PO_SUBMITTED")).hasSize(2);
        assertThat(count(p.w().clinic(), "select count(*) from pharmacy.approval_request where transaction_type = 'PURCHASE_ORDER'")).isZero();
    }

    @Test
    void withTheStandardRuleTheOrderWaitsInTheOwnersInboxAndAnotherOwnersApprovalMakesItApproved() throws Exception {
        Pw p = world(false);
        Sw w = p.w();
        JsonNode waiting = submitPo(p, w.csUser(), draft(p, w.csUser()), 200);
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.get("number").asText()).as("the number is taken at submit").isNotBlank();
        assertThat(waiting.get("approval").get("status").asText()).isEqualTo("PENDING");
        String request = waiting.get("approval").get("requestId").asText();
        JsonNode inbox = ok(HttpMethod.GET, PH + "/approvals/inbox", w.second().auth(), null, 200);
        assertThat(inbox.get("items")).hasSize(1);
        assertThat(inbox.get("items").get(0).get("chip").asText()).isEqualTo("PURCHASE_ORDER");
        assertThat(inbox.get("counts").get("purchase").asInt()).isOne();
        // the approver may read the order and the Central Store pharmacist (raiser) too
        assertThat(getPo(p, w.second(), idOf(waiting)).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        long version = ok(HttpMethod.GET, PH + "/approvals/" + request, w.second().auth(), null, 200).get("version").asLong();
        ok(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null), 200);
        JsonNode approved = getPo(p, w.csUser(), idOf(waiting));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("history").toString()).contains("SUBMITTED").contains("DECIDED");
    }

    @Test
    void aRejectedOrderCanBeCopiedIntoANewDraftAndOnlyARejectedOneCan() throws Exception {
        Pw p = world(false);
        Sw w = p.w();
        JsonNode waiting = submitPo(p, w.csUser(), draft(p, w.csUser()), 200);
        String request = waiting.get("approval").get("requestId").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + request, w.second().auth(), null, 200).get("version").asLong();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("clientKey", UUID.randomUUID().toString());
        assertType(action(p, w.csUser(), waiting, "copy", key, 409), "doc-state");
        ok(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version, "REJECTED", "approval-reject.not-needed"), 200);
        assertThat(getPo(p, w.csUser(), idOf(waiting)).get("status").asText()).isEqualTo("REJECTED");
        JsonNode copy = action(p, w.csUser(), waiting, "copy", key, 201);
        assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.get("number").isNull()).isTrue();
        assertThat(copy.get("deliveryExpected").isNull()).isTrue();
        assertThat(copy.get("creditDays").asInt()).isEqualTo(30);
        assertThat(copy.get("vendor").get("vendorId").asText()).isEqualTo(p.vendor());
        assertThat(copy.get("lines")).hasSize(1);
        assertThat(idOf(copy)).isNotEqualTo(idOf(waiting));
        assertThat(action(p, w.csUser(), waiting, "copy", key, 200).get("id").asText()).as("replay").isEqualTo(idOf(copy));
        // a submit without a delivery date is refused
        assertThat(submitPo(p, w.csUser(), copy, 400).get("status").asInt()).isEqualTo(400);
    }

    @Test
    void submitOfAnOrderThatIsNotADraftOrWithAStaleVersionIsRefusedAndTheSameKeyDoesNotTakeASecondNumber() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        JsonNode d = draft(p, w.csUser());
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("version", 9);
        stale.put("clientKey", UUID.randomUUID().toString());
        assertType(problem(call(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(d) + "/submit", w.csUser().auth(), js(stale)), 409), "changed");
        JsonNode s = submitPo(p, w.csUser(), d, 200);
        assertType(submitPo(p, w.csUser(), s, 409), "doc-state");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.purchase_order where doc_no is not null")).isOne();
    }

    // ---------------- mark sent, cancel, close short ----------------

    @Test
    void markSentNeedsAnApprovedOrderAndADateThatIsNotInTheFutureOrBeforeTheApproval() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        JsonNode d = draft(p, w.csUser());
        Map<String, Object> today = new LinkedHashMap<>();
        today.put("version", d.get("version").asLong());
        today.put("sentOn", todayIst().toString());
        assertType(action(p, w.csUser(), d, "mark-sent", today, 409), "doc-state");
        JsonNode approved = submitPo(p, w.csUser(), d, 200);
        for (LocalDate bad : List.of(todayIst().plusDays(1), todayIst().minusDays(1))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", approved.get("version").asLong());
            m.put("sentOn", bad.toString());
            problem(call(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(approved) + "/mark-sent", w.csUser().auth(), js(m)), 400);
        }
        today.put("version", approved.get("version").asLong());
        JsonNode sent = action(p, w.csUser(), approved, "mark-sent", today, 200);
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("sentOn").asText()).isEqualTo(todayIst().toString());
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_PO_SENT")).hasSize(1);
    }

    @Test
    void cancellingAWaitingOrderWithdrawsItsApprovalRequestAndKeepsTheNumber() throws Exception {
        Pw p = world(false);
        Sw w = p.w();
        JsonNode waiting = submitPo(p, w.csUser(), draft(p, w.csUser()), 200);
        String request = waiting.get("approval").get("requestId").asText();
        Map<String, Object> noReason = versionBody(waiting);
        problem(call(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(waiting) + "/cancel", w.csUser().auth(), js(noReason)), 400);
        Map<String, Object> badReason = versionBody(waiting);
        badReason.put("reasonKey", "approval-reject.nope");
        problem(call(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(waiting) + "/cancel", w.csUser().auth(), js(badReason)), 400);
        Map<String, Object> body = versionBody(waiting);
        body.put("reasonKey", "approval-reject.not-needed");
        body.put("note", null);
        JsonNode cancelled = action(p, w.csUser(), waiting, "cancel", body, 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("number").asText()).isEqualTo(waiting.get("number").asText());
        assertThat(ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200).get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox", w.second().auth(), null, 200).get("items")).as("gone from the inbox").isEmpty();
        // a cancelled order cannot be cancelled again
        body.put("version", cancelled.get("version").asLong());
        assertType(action(p, w.csUser(), cancelled, "cancel", body, 409), "doc-state");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_PO_CANCELLED")).hasSize(1);
    }

    @Test
    void aDraftCannotBeCancelledAndAnOrderThatIsNotPartlyReceivedCannotBeClosedShort() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        JsonNode d = draft(p, w.csUser());
        Map<String, Object> body = versionBody(d);
        body.put("reasonKey", "approval-reject.not-needed");
        assertType(action(p, w.csUser(), d, "cancel", body, 409), "doc-state");
        assertType(action(p, w.csUser(), d, "close-short", body, 409), "doc-state");
        JsonNode approved = submitPo(p, w.csUser(), d, 200);
        body.put("version", approved.get("version").asLong());
        assertType(action(p, w.csUser(), approved, "close-short", body, 409), "doc-state");
        JsonNode cancelled = action(p, w.csUser(), approved, "cancel", body, 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    }

    // ---------------- list, tiles, read, tenants ----------------

    @Test
    void theListIsNewestFirstWithTilesAndFiltersAndAnEmptyListHasZeroTiles() throws Exception {
        Pw p = world(true);
        Sw w = p.w();
        JsonNode empty = ok(HttpMethod.GET, pos(w.centralId()), w.csUser().auth(), null, 200);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("tiles").get("draft").asInt()).isZero();
        JsonNode d = draft(p, w.csUser());
        JsonNode second = draft(p, w.csUser());
        JsonNode approved = submitPo(p, w.csUser(), draft(p, w.csUser()), 200);
        Map<String, Object> sentBody = versionBody(approved);
        sentBody.put("sentOn", todayIst().toString());
        action(p, w.csUser(), approved, "mark-sent", sentBody, 200);
        JsonNode list = ok(HttpMethod.GET, pos(w.centralId()), w.csUser().auth(), null, 200);
        assertThat(list.get("total").asInt()).isEqualTo(3);
        assertThat(list.get("tiles").get("draft").asInt()).isEqualTo(2);
        assertThat(list.get("tiles").get("sent").asInt()).isOne();
        assertThat(list.get("tiles").get("waitingApproval").asInt()).isZero();
        assertThat(list.get("tiles").get("overdue").asInt()).isZero();
        JsonNode drafts = ok(HttpMethod.GET, pos(w.centralId()) + "?status=DRAFT", w.csUser().auth(), null, 200);
        assertThat(drafts.get("items")).hasSize(2);
        assertThat(drafts.get("tiles").get("sent").asInt()).as("tiles ignore the filter").isOne();
        assertThat(ok(HttpMethod.GET, pos(w.centralId()) + "?status=DRAFT,SENT", w.csUser().auth(), null, 200).get("items")).hasSize(3);
        assertThat(ok(HttpMethod.GET, pos(w.centralId()) + "?overdue=true", w.csUser().auth(), null, 200).get("items")).isEmpty();
        problem(call(HttpMethod.GET, pos(w.centralId()) + "?status=NOPE", w.csUser().auth(), null), 400);
        problem(call(HttpMethod.GET, pos(w.centralId()) + "?size=51", w.csUser().auth(), null), 400);
        assertThat(idOf(d)).isNotEqualTo(idOf(second));
        assertThat(ok(HttpMethod.GET, pos(w.centralId()) + "?size=1&page=1", w.csUser().auth(), null, 200).get("items")).hasSize(1);
    }

    @Test
    void anOrderOfAnotherClinicIsInvisibleOnEveryOperationAndAnUnknownIdIs404() throws Exception {
        Pw p = world(true);
        Pw other = world(true);
        Sw w = p.w();
        JsonNode po = draft(p, w.csUser());
        String id = idOf(po);
        String theirs = pos(other.w().centralId());
        String mine = pos(w.centralId());
        // the other clinic's owner on its own store with this clinic's order id, and on this clinic's store
        assertType(problem(call(HttpMethod.GET, theirs + "/" + id, other.w().clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, mine + "/" + id, other.w().clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, theirs + "/" + id + "/discard", other.w().clinic().owner(), js(versionBody(po))), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, mine + "/" + id + "/submit", other.w().clinic().owner(), "{\"version\":0,\"clientKey\":\"" + UUID.randomUUID() + "\"}"), 404), "not-found");
        assertThat(ok(HttpMethod.GET, theirs, other.w().clinic().owner(), null, 200).get("items")).isEmpty();
        assertType(problem(call(HttpMethod.GET, mine + "/" + UUID.randomUUID(), w.csUser().auth(), null), 404), "not-found");
        problem(call(HttpMethod.GET, mine + "/not-a-uuid", w.csUser().auth(), null), 400);
        assertThat(getPo(p, w.csUser(), id).get("status").asText()).isEqualTo("DRAFT");
        // a vendor of the other clinic cannot be used
        problem(call(HttpMethod.POST, mine, w.csUser().auth(), js(createBody(UUID.randomUUID(), other.vendor(), delivery(), 30,
                List.of(line(w.productA(), 1, "10"))))), 400);
    }

    @Test
    void thePrintDataAndTheRuleNoteAreOnTheOrder() throws Exception {
        Pw p = world(false);
        Sw w = p.w();
        putRules(w, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null))));
        JsonNode po = draft(p, w.csUser());
        JsonNode got = getPo(p, w.csUser(), idOf(po));
        assertThat(got.get("ruleNote").asText()).contains("50,000");
        assertThat(got.get("print").get("templateDocType").asText()).isEqualTo("DEBIT_NOTE");
        assertThat(got.get("history")).isNotEmpty();
        // below the limit: approved at once
        assertThat(submitPo(p, w.csUser(), po, 200).get("status").asText()).isEqualTo("APPROVED");
    }
}
