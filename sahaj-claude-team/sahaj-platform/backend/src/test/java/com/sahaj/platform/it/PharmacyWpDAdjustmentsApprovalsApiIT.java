package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79-2 and 79-5 (pharmacy.yaml 0.3.3): the approvals inbox and the decision, and stock adjustments (list, create, get),
 * with the approval engine behind them: waiting for approval and posting at once, replay, changed, adjustment-waiting,
 * validation, self-approval, level 2, a stale decision, the reject reasons, a handler refusal that rolls back, who may read
 * a request, Schedule X (owner refused, the second signer and the PIN), the second-signers list and the active reasons.
 * Test plan: docs/test-plans/SAH-79.md (T79-2-*, T79-5-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyWpDAdjustmentsApprovalsApiIT extends Sah79ApiSupport {

    private static final String WAITING_TEXT = "An adjustment for this batch is already waiting.";
    private static final String SELF_TEXT = "You cannot approve your own request.";
    private static final String SECOND_LEVEL_TEXT = "Another person must approve the second level.";

    private Sw posted() throws Exception {
        Sw w = stockWorld();
        postedImport(w);
        ownerAlways(w, "STOCK_ADJUSTMENT"); // the engine applies a rule only once rules are saved (defect D-5)
        return w;
    }

    private JsonNode raise(Sw w, Pn who, String batchId, int book, int physical, int expected) throws Exception {
        return ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), who.auth(),
                js(adjustmentBody(UUID.randomUUID(), batchId, book, physical, REASON_ADJ)), expected);
    }

    private long available(Sw w, String batchNo) throws Exception {
        return count(w.clinic(), "select coalesce(sum(b.qty), 0) from pharmacy.stock_balance b join pharmacy.batch t on t.id = b.batch_id "
                + "where t.batch_no = ? and b.state = 'AVAILABLE'", batchNo);
    }

    private JsonNode decide(Sw w, Pn who, String requestId, int level, long version, String decision, String reason, int expected) throws Exception {
        return ok(HttpMethod.POST, decisionUrl(requestId), who.auth(), decisionBody(UUID.randomUUID(), level, version, decision, reason), expected);
    }

    private JsonNode requestOf(Sw w, Pn who, String requestId) throws Exception {
        return ok(HttpMethod.GET, PH + "/approvals/" + requestId, who.auth(), null, 200);
    }

    private Pn owner(Sw w) throws Exception {
        return ownerPn(w.clinic());
    }

    // ---------------- creating an adjustment ----------------

    @Test
    void anAdjustmentWithTheStandardRuleWaitsForApprovalAndMovesNoStock() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode a = raise(w, w.keeper(), batch, 100, 98, 201);
        assertThat(a.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(a.get("number").asText()).startsWith("ADJ-");
        assertThat(a.get("bookQty").asInt()).isEqualTo(100);
        assertThat(a.get("physicalQty").asInt()).isEqualTo(98);
        assertThat(a.get("difference").asInt()).isEqualTo(-2);
        assertThat(a.get("valuePaise").asLong()).as("2 x 30.25").isEqualTo(6050);
        assertThat(a.get("reason").get("reasonKey").asText()).isEqualTo(REASON_ADJ);
        assertThat(a.get("raisedBy").get("name").asText()).isEqualTo("Pia Pharmacist");
        assertThat(a.get("approval").get("status").asText()).isEqualTo("PENDING");
        assertThat(a.get("approval").get("canDecide").asBoolean()).as("the raiser cannot decide").isFalse();
        assertThat(available(w, "TESTB001")).isEqualTo(100);
        assertThat(pharmacyRows(w.clinic(), "stock_ledger")).isEqualTo(3);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_STOCK_ADJUSTMENT_RAISED")).hasSize(1);
    }

    @Test
    void whenNoRuleAppliesTheAdjustmentIsPostedAtOnceAndTheLedgerSumsStillEqualTheBalances() throws Exception {
        Sw w = posted();
        noApprovalRules(w);
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode down = raise(w, w.keeper(), batch, 100, 98, 201);
        assertThat(down.get("status").asText()).isEqualTo("POSTED");
        assertThat(down.get("approval").isNull()).isTrue();
        assertThat(available(w, "TESTB001")).isEqualTo(98);
        JsonNode up = raise(w, w.keeper(), batch, 98, 105, 201);
        assertThat(up.get("status").asText()).isEqualTo("POSTED");
        assertThat(up.get("difference").asInt()).isEqualTo(7);
        assertThat(available(w, "TESTB001")).isEqualTo(105);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STOCK_ADJUSTMENT' and state = 'AVAILABLE'")).isEqualTo(2);
        assertThat(count(w.clinic(), "select count(*) from (select b.qty, (select coalesce(sum(l.qty_in - l.qty_out), 0) from pharmacy.stock_ledger l "
                + "where l.store_id = b.store_id and l.batch_id = b.batch_id and l.state = b.state) as s from pharmacy.stock_balance b) x where x.qty <> x.s"))
                .isZero();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_STOCK_ADJUSTMENT_POSTED")).hasSize(2);
    }

    @Test
    void theSameClientKeyReplaysTheAdjustmentAndADifferentBodyWithTheSameKeyIsRefused() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        UUID key = UUID.randomUUID();
        JsonNode first = ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), js(adjustmentBody(key, batch, 100, 98, REASON_ADJ)), 201);
        JsonNode replay = ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), js(adjustmentBody(key, batch, 100, 98, REASON_ADJ)), 200);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isOne();
        assertThat(ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), js(adjustmentBody(key, batch, 100, 90, REASON_ADJ)), 200).get("id").asText())
                .as("the same caller and key: the first answer again, whatever the body").isEqualTo(idOf(first));
        assertType(problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.second().auth(), js(adjustmentBody(key, batch, 100, 98, REASON_ADJ))), 409),
                "client-key-reused");
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isOne();
    }

    @Test
    void aSecondAdjustmentOfTheSameBatchWhileOneWaitsIsRefused() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        raise(w, w.keeper(), batch, 100, 98, 201);
        JsonNode p = problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 100, 97, REASON_ADJ))), 409);
        assertType(p, "adjustment-waiting");
        assertThat(p.get("detail").asText()).isEqualTo(WAITING_TEXT);
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isOne();
        // another batch is not blocked
        raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB003"), 3, 2, 201);
    }

    @Test
    void whenTheBookQuantityChangedSinceTheScreenLoadedTheAnswerIs409ChangedWithTheCurrentQuantity() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode p = problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 99, 90, REASON_ADJ))), 409);
        assertType(p, "changed");
        assertThat(p.get("current")).as("the current state is in the body").isNotNull();
        assertThat(p.get("current").toString()).contains("100");
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isZero();
    }

    @Test
    void theAdjustmentIsValidatedAllTogetherWith400AndFieldErrors() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode same = problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 100, 100, REASON_ADJ))), 400);
        assertType(same, "validation");
        assertFieldError(same, "physicalQty");
        assertThat(same.toString()).contains("No change to post");
        JsonNode reason = problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 100, 90, "stock-adjustment.nope"))), 400);
        assertThat(reason.toString()).contains("reasonKey");
        problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 100, 1_000_000, REASON_ADJ))), 400);
        problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 100, -1, REASON_ADJ))), 400);
        problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), UUID.randomUUID().toString(), 100, 90, REASON_ADJ))), 400);
        assertType(problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), "{}"), 400), "validation");
        assertType(problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), "not json"), 400), "malformed-request");
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isZero();
        // the boundaries 0 and 999999 are accepted by the rules of the body (0 is a real adjustment)
        raise(w, w.keeper(), batch, 100, 0, 201);
    }

    @Test
    void aStaleScreenOfABatchEmptiedByAnEarlierAdjustmentIsRefusedAsChanged() throws Exception {
        Sw w = posted();
        noApprovalRules(w);
        String batch = batchId(w, w.pharmacyId(), "TESTB003");
        raise(w, w.keeper(), batch, 3, 0, 201); // all 3 gone
        assertThat(available(w, "TESTB003")).isZero();
        // a stale screen (book 3) is refused as changed; a fresh one cannot reduce below zero because the physical count is a whole number >= 0
        assertType(problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), batch, 3, 1, REASON_ADJ))), 409), "changed");
    }

    // ---------------- listing and reading ----------------

    @Test
    void theListIsNewestFirstFilteredByStatusAndPagedAndOneAdjustmentShowsItsHistory() throws Exception {
        Sw w = posted();
        String b1 = batchId(w, w.pharmacyId(), "TESTB001");
        String b3 = batchId(w, w.pharmacyId(), "TESTB003");
        JsonNode first = raise(w, w.keeper(), b1, 100, 98, 201);
        JsonNode second = raise(w, w.keeper(), b3, 3, 2, 201);
        JsonNode list = ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(), null, 200);
        assertThat(list.get("total").asInt()).isEqualTo(2);
        assertThat(list.get("items").get(0).get("id").asText()).as("newest first").isEqualTo(second.get("id").asText());
        assertThat(ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "?status=POSTED", w.keeper().auth(), null, 200).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "?status=WAITING_APPROVAL&size=1&page=1", w.keeper().auth(), null, 200).get("items")).hasSize(1);
        problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "?status=NOPE", w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "?size=51", w.keeper().auth(), null), 400);
        JsonNode one = ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/" + idOf(first), w.keeper().auth(), null, 200);
        assertThat(one.get("history")).isNotEmpty();
        assertThat(one.get("history").get(0).get("event").asText()).isEqualTo("CREATED");
        assertType(problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/" + UUID.randomUUID(), w.keeper().auth(), null), 404), "not-found");
        problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/not-a-uuid", w.keeper().auth(), null), 400);
    }

    @Test
    void anAdjustmentOfAnotherClinicIsInvisibleAndANonCapablePersonIsRefused() throws Exception {
        Sw w = posted();
        Sw other = stockWorld();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        assertType(problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/" + idOf(a), other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()), other.clinic().owner(), null), 404), "not-found");
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK");
        problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()), frontDesk.auth(), null), 403);
        problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), frontDesk.auth(),
                js(adjustmentBody(UUID.randomUUID(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 90, REASON_ADJ))), 403);
        // a pharmacist mapped only to the Central Store has no business in the pharmacy store: 404
        assertType(problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()), w.csUser().auth(), null), 404), "not-found");
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()), w.clinic().owner(), null), 403), "module-off");
    }

    // ---------------- the inbox and the decision ----------------

    @Test
    void theInboxListsTheWaitingRequestForAnOwnerWithCountsAndNotForTheRaiserOrAPlainPharmacist() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode a = raise(w, w.keeper(), batch, 100, 98, 201);
        JsonNode inbox = ok(HttpMethod.GET, PH + "/approvals/inbox", w.clinic().owner(), null, 200);
        assertThat(inbox.get("total").asInt()).isOne();
        JsonNode row = inbox.get("items").get(0);
        assertThat(row.get("requestId").asText()).isEqualTo(a.get("approval").get("requestId").asText());
        assertThat(row.get("chip").asText()).isEqualTo("STOCK_ADJUSTMENT");
        assertThat(row.get("subjectType").asText()).isEqualTo("STOCK_ADJUSTMENT");
        assertThat(row.get("subjectId").asText()).isEqualTo(idOf(a));
        assertThat(row.get("subjectStoreId").asText()).isEqualTo(w.pharmacyId());
        assertThat(row.get("raisedBy").get("name").asText()).isEqualTo("Pia Pharmacist");
        assertThat(row.get("level").asInt()).isOne();
        assertThat(row.get("actions").toString()).contains("REVIEW").contains("REJECT");
        assertThat(inbox.get("counts").get("all").asInt()).isOne();
        assertThat(inbox.get("counts").get("stock").asInt()).isOne();
        assertThat(inbox.get("signedInAs").asText()).isEqualTo("Owner");
        // the chip filter changes the items, not the counts
        JsonNode indents = ok(HttpMethod.GET, PH + "/approvals/inbox?type=INDENTS", w.clinic().owner(), null, 200);
        assertThat(indents.get("items")).isEmpty();
        assertThat(indents.get("counts").get("all").asInt()).isOne();
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox?type=STOCK", w.clinic().owner(), null, 200).get("items")).hasSize(1);
        // a pharmacist who is not an owner, an HOD or a delegate: 403 forbidden
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/inbox", w.keeper().auth(), null), 403), "forbidden");
        // an empty inbox
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox", w.second().auth(), null, 200).get("total").asInt()).as("second owner sees it too").isOne();
        problem(call(HttpMethod.GET, PH + "/approvals/inbox?type=NOPE", w.clinic().owner(), null), 400);
        problem(call(HttpMethod.GET, PH + "/approvals/inbox?size=51", w.clinic().owner(), null), 400);
        problem(call(HttpMethod.GET, PH + "/approvals/inbox?page=1001", w.clinic().owner(), null), 400);
    }

    @Test
    void theInboxOfAClinicWithNothingWaitingIsEmptyAndAnotherClinicsRequestsNeverShow() throws Exception {
        Sw w = posted();
        Sw other = posted();
        raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        JsonNode empty = ok(HttpMethod.GET, PH + "/approvals/inbox", other.clinic().owner(), null, 200);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("total").asInt()).isZero();
        assertThat(empty.get("counts").get("all").asInt()).isZero();
    }

    @Test
    void anOwnerWhoRaisedTheAdjustmentCannotApproveItButAnotherOwnerCan() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB001");
        JsonNode a = ok(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.clinic().owner(), js(adjustmentBody(UUID.randomUUID(), batch, 100, 96, REASON_ADJ)), 201);
        String request = a.get("approval").get("requestId").asText();
        long version = requestOf(w, w.second(), request).get("version").asLong();
        JsonNode self = problem(call(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null)), 403);
        assertType(self, "self-approval");
        assertThat(self.get("detail").asText()).isEqualTo(SELF_TEXT);
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox", w.clinic().owner(), null, 200).get("items")).as("never the raiser").isEmpty();
        assertThat(available(w, "TESTB001")).isEqualTo(100);
        JsonNode done = decide(w, w.second(), request, 1, version, "APPROVED", null, 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(done.get("decisions")).hasSize(1);
        assertThat(done.get("decisions").get(0).get("decidedBy").get("name").asText()).isEqualTo("Second Owner");
        assertThat(done.get("decisions").get(0).get("actingAs").asText()).isEqualTo("OWNER");
        assertThat(available(w, "TESTB001")).as("the approval posted it").isEqualTo(96);
        JsonNode adjustment = ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/" + idOf(a), w.clinic().owner(), null, 200);
        assertThat(adjustment.get("status").asText()).isEqualTo("POSTED");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_APPROVAL_DECIDED")).hasSize(1);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STOCK_ADJUSTMENT' and actor_membership_id = ?::uuid",
                w.clinic().ownerUserId().toString())).as("the ledger names a membership, not a user id").isZero();
    }

    @Test
    void aSecondLevelNeedsAnotherPersonAndTheFirstApproverCannotDecideIt() throws Exception {
        Sw w = posted();
        Map<String, Map<String, Object>> two = only(rule("STOCK_ADJUSTMENT", null, null, always(1, "OWNER"), always(2, "OWNER")));
        putRules(w, two);
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 95, 201);
        String request = a.get("approval").get("requestId").asText();
        JsonNode r = requestOf(w, w.keeper(), request);
        assertThat(r.get("levels")).hasSize(2);
        decide(w, w.second(), request, 1, r.get("version").asLong(), "APPROVED", null, 200);
        JsonNode mid = requestOf(w, w.second(), request);
        assertThat(mid.get("status").asText()).isEqualTo("PENDING");
        assertThat(mid.get("currentLevel").asInt()).isEqualTo(2);
        assertThat(available(w, "TESTB001")).as("not posted before level 2").isEqualTo(100);
        JsonNode same = problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(),
                decisionBody(UUID.randomUUID(), 2, mid.get("version").asLong(), "APPROVED", null)), 403);
        assertType(same, "self-approval");
        assertThat(same.get("detail").asText()).isEqualTo(SECOND_LEVEL_TEXT);
        JsonNode done = ok(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 2, mid.get("version").asLong(), "APPROVED", null), 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(done.get("decisions")).hasSize(2);
        assertThat(available(w, "TESTB001")).isEqualTo(95);
    }

    @Test
    void aStaleOrRepeatedDecisionAnswers409AlreadyDecidedWithTheCurrentStateAndTheSameKeyReplays() throws Exception {
        Sw w = posted();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        String request = a.get("approval").get("requestId").asText();
        long version = requestOf(w, w.keeper(), request).get("version").asLong();
        UUID key = UUID.randomUUID();
        ok(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(key, 1, version, "APPROVED", null), 200);
        // the same key: the same answer, nothing decided twice
        JsonNode replay = ok(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(key, 1, version, "APPROVED", null), 200);
        assertThat(replay.get("status").asText()).isEqualTo("APPROVED");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_APPROVAL_DECIDED")).hasSize(1);
        // another key, request already finished
        JsonNode p = problem(call(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null)), 409);
        assertType(p, "already-decided");
        assertThat(p.get("current")).as("the current state is in the body").isNotNull();
        assertThat(available(w, "TESTB001")).as("posted once").isEqualTo(98);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_decision")).isOne();
    }

    @Test
    void aWrongLevelOrAWrongRequestVersionIsAlreadyDecided() throws Exception {
        Sw w = posted();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        String request = a.get("approval").get("requestId").asText();
        long version = requestOf(w, w.keeper(), request).get("version").asLong();
        assertType(problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 2, version, "APPROVED", null)), 409),
                "already-decided");
        assertType(problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version + 5, "APPROVED", null)), 409),
                "already-decided");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_decision")).isZero();
        assertThat(available(w, "TESTB001")).isEqualTo(100);
    }

    @Test
    void aRejectionNeedsAReasonFromTheRejectListAndMovesNoStock() throws Exception {
        Sw w = posted();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        String request = a.get("approval").get("requestId").asText();
        long version = requestOf(w, w.keeper(), request).get("version").asLong();
        JsonNode none = problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version, "REJECTED", null)), 400);
        assertType(none, "validation");
        assertThat(none.toString()).contains("Choose a reason");
        problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version, "REJECTED", "approval-reject.nope")), 400);
        // a reason of another list is not accepted
        problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version, "REJECTED", REASON_ADJ)), 400);
        // indentLines only for an indent
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("level", 1);
        body.put("requestVersion", version);
        body.put("decision", "APPROVED");
        body.put("indentLines", List.of(Map.of("indentLineId", UUID.randomUUID().toString(), "approvedQty", 1)));
        problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), js(body)), 400);
        JsonNode done = decide(w, w.second(), request, 1, version, "REJECTED", "approval-reject.not-needed", 200);
        assertThat(done.get("status").asText()).isEqualTo("REJECTED");
        assertThat(done.get("decisions").get(0).get("reason").get("reasonKey").asText()).isEqualTo("approval-reject.not-needed");
        assertThat(ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/" + idOf(a), w.keeper().auth(), null, 200).get("status").asText()).isEqualTo("REJECTED");
        assertThat(available(w, "TESTB001")).isEqualTo(100);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STOCK_ADJUSTMENT'")).isZero();
        // the batch is free for a new adjustment
        raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 99, 201);
    }

    @Test
    void whenThePostingIsRefusedTheWholeDecisionIsRolledBackAndTheRequestStaysPending() throws Exception {
        Sw w = posted();
        String batch = batchId(w, w.pharmacyId(), "TESTB003");
        JsonNode a = raise(w, w.keeper(), batch, 3, 0, 201); // wants to take all 3
        String request = a.get("approval").get("requestId").asText();
        long version = requestOf(w, w.keeper(), request).get("version").asLong();
        // the stock leaves by another road before the owner decides (a committed fixture)
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.stock_balance set qty = 1 where batch_id = ?::uuid and state = 'AVAILABLE'", batch);
        JsonNode p = problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null)), 409);
        assertType(p, "insufficient-stock");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_decision")).as("no decision stays").isZero();
        assertThat(requestOf(w, w.keeper(), request).get("status").asText()).isEqualTo("PENDING");
        assertThat(ok(HttpMethod.GET, adjustmentsUrl(w.pharmacyId()) + "/" + idOf(a), w.keeper().auth(), null, 200).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STOCK_ADJUSTMENT'")).isZero();
    }

    @Test
    void whoMayReadARequestTheRaiserAnOwnerAndAnApproverAndNobodyElse() throws Exception {
        Sw w = posted();
        Sw other = stockWorld();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        String request = a.get("approval").get("requestId").asText();
        JsonNode asRaiser = requestOf(w, w.keeper(), request);
        assertThat(asRaiser.get("canDecide").asBoolean()).isFalse();
        assertThat(asRaiser.get("decideLockReason").asText()).isEqualTo("SELF_APPROVAL");
        assertThat(asRaiser.get("subjectType").asText()).isEqualTo("STOCK_ADJUSTMENT");
        assertThat(asRaiser.get("subjectNo").asText()).startsWith("ADJ-");
        assertThat(asRaiser.get("subjectStoreId").asText()).isEqualTo(w.pharmacyId());
        JsonNode asOwner = requestOf(w, w.second(), request);
        assertThat(asOwner.get("canDecide").asBoolean()).isTrue();
        assertThat(asOwner.get("decideLockReason").isNull()).isTrue();
        assertThat(asOwner.get("levels")).hasSize(1);
        // a pharmacist mapped to another store only and a person of another clinic: 404, never 403
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + request, w.csUser().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + request, other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + UUID.randomUUID(), w.second().auth(), null), 404), "not-found");
        problem(call(HttpMethod.GET, PH + "/approvals/not-a-uuid", w.second().auth(), null), 400);
        // another clinic's owner cannot decide it either
        assertType(problem(call(HttpMethod.POST, decisionUrl(request), other.clinic().owner(), decisionBody(UUID.randomUUID(), 1, 0, "APPROVED", null)), 404), "not-found");
        // a pharmacist who is not an approver: 404 on the decision
        assertType(problem(call(HttpMethod.POST, decisionUrl(request), w.csUser().auth(), decisionBody(UUID.randomUUID(), 1, 0, "APPROVED", null)), 404), "not-found");
        assertThat(available(w, "TESTB001")).isEqualTo(100);
    }

    @Test
    void theModuleSwitchedOffRefusesTheInboxAndTheDecision() throws Exception {
        Sw w = posted();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 98, 201);
        String request = a.get("approval").get("requestId").asText();
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/inbox", w.clinic().owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, decisionUrl(request), w.second().auth(), decisionBody(UUID.randomUUID(), 1, 0, "APPROVED", null)), 403), "module-off");
    }

    // ---------------- reconciliation (I11) ----------------

    @Test
    void afterAPostingAndAnAdjustmentTheLedgerEqualsTheBalancesAndEveryApprovedRequestHasADecisionByAnotherPerson() throws Exception {
        Sw w = posted();
        JsonNode a = raise(w, w.keeper(), batchId(w, w.pharmacyId(), "TESTB001"), 100, 90, 201);
        String request = a.get("approval").get("requestId").asText();
        decide(w, w.second(), request, 1, requestOf(w, w.second(), request).get("version").asLong(), "APPROVED", null, 200);
        assertThat(count(w.clinic(), "select count(*) from (select b.qty, (select coalesce(sum(l.qty_in - l.qty_out), 0) from pharmacy.stock_ledger l "
                + "where l.store_id = b.store_id and l.batch_id = b.batch_id and l.state = b.state) as s from pharmacy.stock_balance b) x where x.qty <> x.s")).isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_adjustment where status = 'POSTED' and approval_request_id is not null "
                + "and not exists (select 1 from pharmacy.approval_request r where r.id = approval_request_id and r.status = 'APPROVED')")).isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_request r where r.status = 'APPROVED' and not exists "
                + "(select 1 from pharmacy.approval_decision d where d.request_id = r.id and d.decision = 'APPROVED' and d.decided_by <> r.raised_by)")).isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_request r where r.status = 'APPROVED' and exists "
                + "(select 1 from pharmacy.approval_request_level l where l.request_id = r.id and l.applies and not exists "
                + "(select 1 from pharmacy.approval_decision d where d.request_id = r.id and d.level_no = l.level_no and d.decision = 'APPROVED'))")).isZero();
        assertThat(idOf(a)).isNotBlank();
    }

    // ---------------- Schedule X ----------------

    private record Sx(Sw w, String batchId) {
    }

    /** A Schedule X own product with 9 in the pharmacy store (posted), and no approval rule so an adjustment posts at once. */
    private Sx scheduleX(boolean noRules) throws Exception {
        Sw w = stockWorld();
        Map<String, Object> body = ownBody("Test controlled item (fake)");
        body.put("schedule", "X");
        JsonNode x = createOwn(w.clinic(), body);
        String id = idOf(startImport(w.keeper().auth(), w.pharmacyId(), 1));
        addRows(w.keeper().auth(), w.pharmacyId(), id, rows(row(1, "PHARM", x.get("code").asText(), "XB001", farExpiry(), "10", "5", "9")));
        submit(w.keeper().auth(), w.pharmacyId(), id);
        ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200);
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        if (noRules) {
            noApprovalRules(w);
        } else {
            ownerAlways(w, "STOCK_ADJUSTMENT");
        }
        return new Sx(w, batchId(w, w.pharmacyId(), "XB001"));
    }

    private JsonNode xCreate(Sx s, Pn who, Map<String, Object> signer, int expected) throws Exception {
        Map<String, Object> body = adjustmentBody(UUID.randomUUID(), s.batchId(), 9, 7, REASON_ADJ);
        body.put("secondSigner", signer);
        return expected == 201 ? ok(HttpMethod.POST, adjustmentsUrl(s.w().pharmacyId()), who.auth(), js(body), 201)
                : problem(call(HttpMethod.POST, adjustmentsUrl(s.w().pharmacyId()), who.auth(), js(body)), expected);
    }

    @Test
    void aScheduleXAdjustmentByAnOwnerIsRefusedAndAMissingSecondSignerIs400OnSecondSigner() throws Exception {
        Sx s = scheduleX(true);
        assertType(xCreate(s, owner(s.w()),
                null, 403), "schedule-x-pharmacist");
        JsonNode missing = xCreate(s, s.w().keeper(), null, 400);
        assertType(missing, "validation");
        assertFieldError(missing, "secondSigner");
        assertThat(pharmacyRows(s.w().clinic(), "stock_adjustment")).isZero();
    }

    @Test
    void theSecondSignerMustBeAnotherActivePharmacistOrOwnerWithAPinThatIsRight() throws Exception {
        Sx s = scheduleX(true);
        Sw w = s.w();
        // the caller as signer, a nurse, and a person who does not exist
        assertType(xCreate(s, w.keeper(), signer(w.keeper(), "1234"), 403), "second-signer-invalid");
        Pn nurse = crew(w.clinic(), "NURSE");
        assertType(xCreate(s, w.keeper(), signer(nurse, "1234"), 403), "second-signer-invalid");
        Map<String, Object> ghost = new java.util.LinkedHashMap<>();
        ghost.put("membershipId", UUID.randomUUID().toString());
        ghost.put("pin", "1234");
        assertType(xCreate(s, w.keeper(), ghost, 403), "second-signer-invalid");
        // a valid colleague with no PIN set
        assertType(xCreate(s, w.keeper(), signer(w.second(), "1234"), 409), "pin-not-set");
        // with a PIN: the wrong one is refused and the stock is untouched
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        assertType(xCreate(s, w.keeper(), signer(w.second(), otherPin(w.second(), pin)), 403), "wrong-pin");
        assertThat(pharmacyRows(w.clinic(), "stock_adjustment")).isZero();
        assertThat(available(w, "XB001")).isEqualTo(9);
    }

    @Test
    void aScheduleXAdjustmentWithTheRightPinIsPostedWithTheSignerOnTheLedgerAndThePinIsNeverEchoed() throws Exception {
        Sx s = scheduleX(true);
        Sw w = s.w();
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        JsonNode a = xCreate(s, w.keeper(), signer(w.second(), pin), 201);
        assertThat(a.get("status").asText()).isEqualTo("POSTED");
        assertThat(a.get("secondSigner").get("name").asText()).isEqualTo("Second Owner");
        assertThat(a.toString()).as("the PIN is never echoed").doesNotContain(pin);
        assertThat(available(w, "XB001")).isEqualTo(7);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STOCK_ADJUSTMENT' and second_signer_membership_id = ?::uuid "
                + "and actor_membership_id = ?::uuid", w.second().membershipId().toString(), w.keeper().membershipId().toString())).isOne();
        assertThat(allAuditText(w.clinic())).as("the PIN is never audited").doesNotContain("\"pin\"").doesNotContain("\"" + pin + "\"");
    }

    @Test
    void aRejectedScheduleXAdjustmentMovesNoStock() throws Exception {
        Sx s = scheduleX(false); // the Standard rule: an owner decides
        Sw w = s.w();
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        JsonNode a = xCreate(s, w.keeper(), signer(w.second(), pin), 201);
        assertThat(a.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        String request = a.get("approval").get("requestId").asText();
        decide(w, owner(w), request, 1, requestOf(w, w.second(), request).get("version").asLong(), "REJECTED", "approval-reject.not-needed", 200);
        assertThat(available(w, "XB001")).isEqualTo(9);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'STOCK_ADJUSTMENT'")).isZero();
    }

    // ---------------- second signers and reasons ----------------

    @Test
    void theSecondSignerListHasOtherPharmacistsAndOwnersNeverTheCallerAndNoPersonalFields() throws Exception {
        Sw w = posted();
        Pn another = crew(w.clinic(), "PHARMACIST", "Zed Pharmacist");
        crew(w.clinic(), "NURSE", "Nora Nurse");
        JsonNode r = ok(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/second-signers", w.keeper().auth(), null, 200);
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode item : r.get("items")) {
            ids.add(item.get("membershipId").asText());
            assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("membershipId", "name", "roleLabel");
            assertThat(item.get("roleLabel").asText()).isIn("Pharmacist", "Owner");
        }
        assertThat(ids).doesNotContain(w.keeper().membershipId().toString());
        assertThat(ids).contains(w.second().membershipId().toString(), w.csUser().membershipId().toString(), another.membershipId().toString());
        assertThat(r.toString()).doesNotContain("Nora Nurse").doesNotContain(w.second().mobile());
        // sorted by name, ignoring case
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode item : r.get("items")) {
            names.add(item.get("name").asText().toLowerCase());
        }
        assertThat(names).isSorted();
    }

    @Test
    void theSecondSignerListIsRefusedToAnOwnerAndToAPersonNotMappedAndInvisibleToAnotherClinic() throws Exception {
        Sw w = posted();
        Sw other = stockWorld();
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/second-signers", w.clinic().owner(), null), 403), "store-permission-denied");
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/second-signers", w.csUser().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/second-signers", other.clinic().owner(), null), 404), "not-found");
        problem(call(HttpMethod.GET, STORES + "/not-a-uuid/second-signers", w.keeper().auth(), null), 400);
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/second-signers", w.keeper().auth(), null), 403), "module-off");
    }

    @Test
    void theActiveReasonsOfNamedListsAreReturnedWithTheNoteFlagAndAnUnknownListIs400() throws Exception {
        Sw w = stockWorld();
        JsonNode r = ok(HttpMethod.GET, PH + "/reasons?lists=stock-adjustment,approval-reject", w.keeper().auth(), null, 200);
        assertThat(r.get("lists")).hasSize(2);
        boolean other = false;
        for (JsonNode list : r.get("lists")) {
            assertThat(list.get("reasons")).isNotEmpty();
            for (JsonNode reason : list.get("reasons")) {
                assertThat(reason.get("needsNote").asBoolean()).isEqualTo(reason.get("reasonKey").asText().endsWith(".other"));
                other |= reason.get("reasonKey").asText().endsWith(".other");
            }
        }
        assertThat(other).as("a list has an Other reason that needs a note").isTrue();
        problem(call(HttpMethod.GET, PH + "/reasons?lists=nope", w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, PH + "/reasons", w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, PH + "/reasons?lists=", w.keeper().auth(), null), 400);
        // a reason the owner switched off is no longer listed
        ok(HttpMethod.POST, REASON_LISTS + "/stock-adjustment/reasons/stock-adjustment.breakage/switch-off", w.clinic().owner(), null, 200);
        String text = ok(HttpMethod.GET, PH + "/reasons?lists=stock-adjustment", w.keeper().auth(), null, 200).toString();
        assertThat(text).doesNotContain("stock-adjustment.breakage");
        assertThat(problem(call(HttpMethod.POST, adjustmentsUrl(w.pharmacyId()), w.keeper().auth(),
                js(adjustmentBody(UUID.randomUUID(), UUID.randomUUID().toString(), 1, 0, REASON_ADJ))), 400).toString()).isNotBlank();
    }
}
