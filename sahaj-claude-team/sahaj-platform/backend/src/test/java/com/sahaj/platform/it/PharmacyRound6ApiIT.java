package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79 round 6 fixes (DEC-082, pharmacy.yaml 0.3.4): the approvals inbox and one request with a waiting purchase order (the
 * old 500, D-6), the capabilities of the context per role (D-4), the Standard default of a first purchase order and a first
 * stock adjustment with no saved rules (D-5; the engine writes no version row, migration 1750), M-2 (no PIN attempt for a
 * batch that is not Schedule X or for a non-pharmacist), L17 (a bad second signer is 400 before any try), M3 (the reduced
 * vendor view), L15 (two creates with one key), L18 (a mapped person without the document's read capability gets 404) and
 * the batches read with a one-character search (D-7). Test plan: docs/test-plans/SAH-79.md (T79-R6-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyRound6ApiIT extends Sah79BuySupport {

    // ---------------- D-6, D-5: the waiting purchase order and the Standard default ----------------

    private JsonNode waitingOrder(Sw w, String vendor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("vendorId", vendor);
        body.put("deliveryExpected", todayIst().plusDays(7).toString());
        body.put("creditDays", 30);
        body.put("lines", List.of(poLine(w.productA(), 10, "70.00")));
        JsonNode draft = ok(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(), js(body), 201);
        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("version", draft.get("version").asLong());
        submit.put("clientKey", UUID.randomUUID().toString());
        return ok(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(draft) + "/submit", w.csUser().auth(), js(submit), 200);
    }

    private String supplying(Sw w, String name) throws Exception {
        Map<String, Object> body = vendorBody(name);
        body.put("suppliesToStoreIds", List.of(w.centralId()));
        return idOf(createVendor(w.clinic(), body));
    }

    @Test
    void theInboxAndOneRequestWithAWaitingPurchaseOrderAnswer200AndTheStandardDefaultMakesTheOwnerApprove() throws Exception {
        Sw w = stockWorld(); // no saved rules: the served Standard default applies
        assertThat(rulesVersion(w)).isZero();
        JsonNode po = waitingOrder(w, supplying(w, "Std Vendor (fake)"));
        assertThat(po.get("status").asText()).as("the Standard preset: the owner approves a purchase order").isEqualTo("WAITING_APPROVAL");
        String request = po.get("approval").get("requestId").asText();
        JsonNode inbox = ok(HttpMethod.GET, PH + "/approvals/inbox", w.clinic().owner(), null, 200);
        assertThat(inbox.toString()).contains(request);
        JsonNode one = ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200);
        assertThat(one.get("transactionType").asText()).isEqualTo("PURCHASE_ORDER");
        assertThat(one.get("status").asText()).isEqualTo("PENDING");
        assertThat(one.get("ruleVersionNo").asInt()).as("raised before any save: version 0").isZero();
        ok(HttpMethod.GET, PH + "/approvals/" + request, w.csUser().auth(), null, 200);
        ok(HttpMethod.GET, PH + "/approvals/inbox", w.csUser().auth(), null, 200);
        // the engine wrote no version row, and the owner's rules still read version 0
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_rule_version")).as("no version row is written for the default").isZero();
        assertThat(rulesVersion(w)).isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_request where rule_version_id is null")).isOne();
        // the owner decides and the order is approved
        ok(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, one.get("version").asLong(), "APPROVED", null), 200);
        assertThat(getOrder(w, idOf(po)).get("status").asText()).isEqualTo("APPROVED");
        // the owner can still save the first version afterwards: it is version 1
        putRules(w, Map.of());
        assertThat(rulesVersion(w)).isOne();
        assertThat(ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200).get("ruleVersionNo").asInt()).as("the old request keeps 0").isZero();
    }

    @Test
    void aFirstStockAdjustmentWithNoSavedRulesWaitsForTheOwnerAndTheRaiserCannotApproveIt() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode adj = ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), js(adjustmentBody(UUID.randomUUID(), batch, 100, 98, REASON_ADJ)), 201);
        assertThat(adj.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        String request = adj.get("approval").get("requestId").asText();
        JsonNode r = ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200);
        assertThat(r.get("ruleVersionNo").asInt()).isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_rule_version")).isZero();
        assertThat(count(w.clinic(), "select coalesce(sum(b.qty), 0) from pharmacy.stock_balance b join pharmacy.batch t on t.id = b.batch_id "
                + "where t.batch_no = 'TESTB001' and b.store_id = ?::uuid and b.state = 'AVAILABLE'", w.pharmacyId())).as("nothing posted yet").isEqualTo(100);
        problem(call(HttpMethod.POST, decisionUrl(request), w.keeper().auth(), decisionBody(UUID.randomUUID(), 1, r.get("version").asLong(), "APPROVED", null)), 403);
        ok(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, r.get("version").asLong(), "APPROVED", null), 200);
        assertThat(count(w.clinic(), "select coalesce(sum(b.qty), 0) from pharmacy.stock_balance b join pharmacy.batch t on t.id = b.batch_id "
                + "where t.batch_no = 'TESTB001' and b.store_id = ?::uuid and b.state = 'AVAILABLE'", w.pharmacyId())).isEqualTo(98);
    }

    // ---------------- D-4: capabilities per role ----------------

    private List<String> capabilities(Pn who) throws Exception {
        List<String> caps = new ArrayList<>();
        contextOf(who.auth()).get("capabilities").forEach(k -> caps.add(k.asText()));
        return caps;
    }

    @Test
    void theContextCapabilitiesFollowTheRoleOwnerHodDelegatePharmacistCentralStoreUserAndStoreStaff() throws Exception {
        Sw w = stockWorld();
        List<String> owner = new ArrayList<>();
        contextOf(w.clinic().owner()).get("capabilities").forEach(k -> owner.add(k.asText()));
        assertThat(owner).contains("INVENTORY", "APPROVALS_INBOX", "PURCHASE", "STOCK_REPORTS");
        Pn hod = crew(w.clinic(), "NURSE", "Hema Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        assertThat(capabilities(hod)).contains("INVENTORY", "APPROVALS_INBOX", "STOCK_REPORTS").doesNotContain("PURCHASE");
        Pn delegate = crew(w.clinic(), "PHARMACIST", "Dev Delegate");
        Map<String, Object> d = mapBody(delegate, "DELEGATE");
        d.put("delegateFrom", todayIst().toString());
        d.put("delegateTo", todayIst().plusDays(3).toString());
        ok(HttpMethod.POST, staffUrl(w.pharmacyId()), w.clinic().owner(), js(d), 201);
        assertThat(capabilities(delegate)).contains("INVENTORY", "APPROVALS_INBOX", "STOCK_REPORTS").doesNotContain("PURCHASE");
        assertThat(capabilities(w.keeper())).as("a plain pharmacist").contains("INVENTORY", "STOCK_REPORTS").doesNotContain("APPROVALS_INBOX", "PURCHASE");
        assertThat(capabilities(w.csUser())).as("a Central Store user").contains("INVENTORY", "PURCHASE", "STOCK_REPORTS").doesNotContain("APPROVALS_INBOX");
        Pn staff = crew(w.clinic(), "STORE_STAFF", "Sita Staff");
        mapStaff(w.clinic(), w.pharmacyId(), staff, "STORE_USER");
        assertThat(capabilities(staff)).as("store staff").contains("INVENTORY", "STOCK_REPORTS").doesNotContain("APPROVALS_INBOX", "PURCHASE");
    }

    // ---------------- M-2 and L17: the second signer ----------------

    private record Sx(Sw w, String batchId) {
    }

    private Sx scheduleX() throws Exception {
        Sw w = stockWorld();
        Map<String, Object> body = ownBody("Test controlled item (fake)");
        body.put("schedule", "X");
        JsonNode x = createOwn(w.clinic(), body);
        String id = idOf(startImport(w.keeper().auth(), w.pharmacyId(), 1));
        addRows(w.keeper().auth(), w.pharmacyId(), id, rows(row(1, "PHARM", x.get("code").asText(), "XB001", farExpiry(), "10", "5", "9")));
        submit(w.keeper().auth(), w.pharmacyId(), id);
        ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200);
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        noApprovalRules(w);
        return new Sx(w, batchId(w, w.pharmacyId(), "XB001"));
    }

    private JsonNode adjust(Sw w, Pn who, String batchId, int book, int physical, Map<String, Object> signer, int expected) throws Exception {
        Map<String, Object> body = adjustmentBody(UUID.randomUUID(), batchId, book, physical, REASON_ADJ);
        body.put("secondSigner", signer);
        return expected == 201 ? ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), who.auth(), js(body), 201)
                : problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), who.auth(), js(body)), expected);
    }

    @Test
    void aSignerInTheBodyOfANonScheduleXBatchOrFromANonPharmacistMakesNoPinAttempt() throws Exception {
        Sx s = scheduleX();
        Sw w = s.w();
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        String wrong = otherPin(w.second(), pin);
        // a Schedule X batch by an owner (not a pharmacist): refused before any PIN is tried
        assertType(adjust(w, ownerPn(w.clinic()), s.batchId(), 9, 7, signer(w.second(), wrong), 403), "schedule-x-pharmacist");
        assertThat(pinRow(w.clinic(), w.second().membershipId()).failed()).as("the colleague's tries are unchanged").isZero();
        // a batch that is not Schedule X (the adopted product of the world) with a wrong PIN in the body: posted, no attempt
        Sw plain = stockWorld();
        postedImport(plain);
        noApprovalRules(plain);
        String plainPin = goodPin(plain.second());
        setPin(plain.second(), plainPin);
        JsonNode done = adjust(plain, plain.keeper(), batchId(plain, plain.pharmacyId(), "TESTB001"), 100, 98, signer(plain.second(), otherPin(plain.second(), plainPin)), 201);
        assertThat(done.get("status").asText()).isEqualTo("POSTED");
        assertThat(pinRow(plain.clinic(), plain.second().membershipId()).failed()).isZero();
        // the control: a pharmacist on a Schedule X batch with the wrong PIN does count one try
        assertType(adjust(w, w.keeper(), s.batchId(), 9, 7, signer(w.second(), wrong), 403), "wrong-pin");
        assertThat(pinRow(w.clinic(), w.second().membershipId()).failed()).isOne();
    }

    @Test
    void aMissingMembershipIdOrAPinThatIsNotFourDigitsIs400BeforeAnyTry() throws Exception {
        Sx s = scheduleX();
        Sw w = s.w();
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        Map<String, Object> noMember = new LinkedHashMap<>();
        noMember.put("membershipId", null);
        noMember.put("pin", pin);
        JsonNode missing = adjust(w, w.keeper(), s.batchId(), 9, 7, noMember, 400);
        assertFieldMessage(missing, "secondSigner", "A second signature is needed for Schedule X items.");
        for (String bad : new String[] {"123", "12345", "abcd", "12 4", "", " 1234"}) {
            Map<String, Object> m = signer(w.second(), bad);
            JsonNode p = adjust(w, w.keeper(), s.batchId(), 9, 7, m, 400);
            assertFieldMessage(p, "secondSigner.pin", "Enter a 4-digit PIN.");
        }
        Map<String, Object> noPin = new LinkedHashMap<>();
        noPin.put("membershipId", w.second().membershipId().toString());
        noPin.put("pin", null);
        assertFieldMessage(adjust(w, w.keeper(), s.batchId(), 9, 7, noPin, 400), "secondSigner.pin", "Enter a 4-digit PIN.");
        assertThat(pinRow(w.clinic(), w.second().membershipId()).failed()).as("none of these was a PIN attempt").isZero();
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isZero();
        assertThat(adjust(w, w.keeper(), s.batchId(), 9, 7, signer(w.second(), pin), 201).get("status").asText()).isEqualTo("POSTED");
    }

    // ---------------- M3: the reduced vendor view ----------------

    private static final List<String> HIDDEN = List.of("gstin", "licenceNo", "contactName", "contactPhone", "suppliesTo", "productsCount", "licenceRequired",
            "licenceState", "creditDays");

    private void assertReduced(JsonNode v) {
        for (String f : List.of("id", "name", "status", "blocked", "version")) {
            assertThat(v.get(f)).as(f).isNotNull();
            assertThat(v.get(f).isNull()).as(f + " is present").isFalse();
        }
        for (String f : HIDDEN) {
            assertThat(v.get(f) == null || v.get(f).isNull()).as(f + " is hidden").isTrue();
        }
    }

    @Test
    void aCentralStoreUserWhoIsNotAPharmacistGetsOnlyTheFiveMembersOfAVendorAndAnOwnerAndAPharmacistGetTheFullRecord() throws Exception {
        Sw w = stockWorld();
        String vendor = supplying(w, "View Vendor (fake)");
        Pn clerk = crew(w.clinic(), "STORE_STAFF", "Clara Clerk");
        mapStaff(w.clinic(), w.centralId(), clerk, "CENTRAL_STORE_USER");
        assertReduced(ok(HttpMethod.GET, VENDORS + "/" + vendor, clerk.auth(), null, 200));
        JsonNode list = ok(HttpMethod.GET, VENDORS, clerk.auth(), null, 200);
        assertThat(list.get("items")).isNotEmpty();
        list.get("items").forEach(this::assertReduced);
        assertThat(list.toString()).doesNotContain("9000000001").doesNotContain("TEST-DL-0002").doesNotContain("Test Contact").doesNotContain("27AAAAA0000A1Z5");
        // the view is chosen from the caller's role: a query value changes nothing
        assertReduced(ok(HttpMethod.GET, VENDORS + "/" + vendor + "?view=full", clerk.auth(), null, 200));
        for (RequestPostProcessorHolder who : List.of(new RequestPostProcessorHolder("owner", w.clinic().owner()), new RequestPostProcessorHolder("pharmacist", w.csUser().auth()),
                new RequestPostProcessorHolder("keeper", w.keeper().auth()))) {
            JsonNode full = ok(HttpMethod.GET, VENDORS + "/" + vendor, who.auth(), null, 200);
            assertThat(full.get("contactPhone").asText()).as(who.name()).isEqualTo("9000000001");
            assertThat(full.get("licenceNo").asText()).isEqualTo("TEST-DL-0002");
            assertThat(full.get("gstin").asText()).isEqualTo("27AAAAA0000A1Z5");
            assertThat(full.get("contactName").asText()).isEqualTo("Test Contact");
            assertThat(ok(HttpMethod.GET, VENDORS, who.auth(), null, 200).get("items").get(0).get("contactPhone").asText()).isEqualTo("9000000001");
        }
    }

    private record RequestPostProcessorHolder(String name, org.springframework.test.web.servlet.request.RequestPostProcessor auth) {
    }

    // ---------------- L15: two creates with one key ----------------

    @Test
    void twoParallelCreatesWithOneKeyMakeOneOrderAndTheSecondReplaysOrIs409ClientKeyReused() throws Exception {
        Sw w = stockWorld();
        String vendor = supplying(w, "Par Vendor (fake)");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("vendorId", vendor);
        body.put("deliveryExpected", todayIst().plusDays(7).toString());
        body.put("creditDays", 30);
        body.put("lines", List.of(poLine(w.productA(), 10, "70.00")));
        String json = js(body);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> codes = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> call(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(), json).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : results) {
                codes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(codes).as("one creates; the other replays (200) or is refused (409)").contains(201);
        assertThat(codes).allMatch(c -> c == 201 || c == 200 || c == 409);
        assertThat(pharmacyRows(w.clinic(), "purchase_order")).as("one order").isOne();
    }

    // ---------------- L18: a mapped person without the document's read capability ----------------

    @Test
    void aMappedPersonWithoutTheDocumentsReadCapabilityGets404OnItsApprovalRequestAndThePeopleWhoMayReadGet200() throws Exception {
        Sw w = stockWorld();
        JsonNode po = waitingOrder(w, supplying(w, "L18 Vendor (fake)"));
        String poRequest = po.get("approval").get("requestId").asText();
        // a store user at the Central Store has no purchase capability: 404
        Pn plain = crew(w.clinic(), "STORE_STAFF", "Pat Plain");
        mapStaff(w.clinic(), w.centralId(), plain, "STORE_USER");
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + poRequest, plain.auth(), null), 404), "not-found");
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox", plain.auth(), null, 200).get("items")).isEmpty();
        // a person not mapped at all: 404
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + poRequest, w.keeper().auth(), null), 404), "not-found");
        // the raiser, a Central Store user with the capability and the owner: 200
        ok(HttpMethod.GET, PH + "/approvals/" + poRequest, w.csUser().auth(), null, 200);
        ok(HttpMethod.GET, PH + "/approvals/" + poRequest, w.clinic().owner(), null, 200);
        // a stock adjustment request at the pharmacy: a non-pharmacist store user cannot adjust stock, so cannot read it
        postedImport(w);
        JsonNode adj = ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, REASON_ADJ)), 201);
        String adjRequest = adj.get("approval").get("requestId").asText();
        Pn staff = crew(w.clinic(), "STORE_STAFF", "Sam Staff");
        mapStaff(w.clinic(), w.pharmacyId(), staff, "STORE_USER");
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + adjRequest, staff.auth(), null), 404), "not-found");
        ok(HttpMethod.GET, PH + "/approvals/" + adjRequest, w.keeper().auth(), null, 200);
    }

    // ---------------- D-7: the batches read ----------------

    @Test
    void aSearchOfOneCharacterOnTheBatchesReadIs400AndTwoAndSixtyAreAcceptedAndSixtyOneIs400() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        problem(call(HttpMethod.GET, batchesUrl(w.pharmacyId(), "q=T"), w.keeper().auth(), null), 400);
        ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "q=TE"), w.keeper().auth(), null, 200);
        ok(HttpMethod.GET, batchesUrl(w.pharmacyId(), "q=" + "T".repeat(60)), w.keeper().auth(), null, 200);
        JsonNode tooLong = problem(call(HttpMethod.GET, batchesUrl(w.pharmacyId(), "q=" + "T".repeat(61)), w.keeper().auth(), null), 400);
        assertThat(tooLong.toString()).contains("Type at most 60 characters.");
    }
}
