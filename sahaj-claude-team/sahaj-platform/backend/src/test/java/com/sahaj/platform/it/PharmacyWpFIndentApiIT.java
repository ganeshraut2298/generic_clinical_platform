package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79-9 and SAH-79-10 (pharmacy.yaml 0.3.4, WP-F indents): raise, edit, discard, submit at level 0 and with a rule, the
 * approver's decision with changed quantities (a reduced line needs a remark; every quantity 0 is 400 on indentLines), reject,
 * cancel (refused once anything is dispatched), another person's draft 404, the Central Store 409 doc-state, replay by
 * clientKey, the lists and two clinics. Product A has the Central Store 103 units after the opening stock is posted. Test
 * plan: docs/test-plans/SAH-79.md (T79-9-*, T79-10-*).
 */
@OnTestDatabase
class PharmacyWpFIndentApiIT extends Sah79ApiSupport {

    private static final String CENTRAL_BUYS = "The Central Store buys by purchase order.";
    private static final String REDUCE_REASON = "approval-reject.quantity-too-high";

    private static String ind(Object storeId) {
        return STORES + "/" + storeId + "/indents";
    }

    private Map<String, Object> line(String productId, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("requestedQty", qty);
        return m;
    }

    private Map<String, Object> createBody(UUID key, String priority, List<Map<String, Object>> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("priority", priority);
        m.put("lines", lines);
        return m;
    }

    private JsonNode raise(Sw w, Pn who, String priority, int qtyA, int qtyB) throws Exception {
        return ok(HttpMethod.POST, ind(w.pharmacyId()), who.auth(),
                js(createBody(UUID.randomUUID(), priority, List.of(line(w.productA(), qtyA), line(w.productB(), qtyB)))), 201);
    }

    private JsonNode submit(Sw w, Pn who, JsonNode indent, int expected) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", indent.get("version").asLong());
        m.put("clientKey", UUID.randomUUID().toString());
        String url = ind(w.pharmacyId()) + "/" + idOf(indent) + "/submit";
        return expected == 200 ? ok(HttpMethod.POST, url, who.auth(), js(m), 200) : problem(call(HttpMethod.POST, url, who.auth(), js(m)), expected);
    }

    private JsonNode get(Sw w, Pn who, String id) throws Exception {
        return ok(HttpMethod.GET, ind(w.pharmacyId()) + "/" + id, who.auth(), null, 200);
    }

    private JsonNode lineOf(JsonNode indent, String productId) {
        for (JsonNode l : indent.get("lines")) {
            if (productId.equals(l.get("product").get("productId").asText())) {
                return l;
            }
        }
        throw new AssertionError("No line for " + productId);
    }

    private String decision(UUID key, long requestVersion, String decision, String reason, List<Map<String, Object>> indentLines) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("level", 1);
        m.put("requestVersion", requestVersion);
        m.put("decision", decision);
        m.put("reasonKey", reason);
        m.put("note", null);
        m.put("indentLines", indentLines);
        m.put("damageRejectOutcome", null);
        return js(m);
    }

    private Map<String, Object> approved(JsonNode line, int qty, String remark) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indentLineId", line.get("id").asText());
        m.put("approvedQty", qty);
        m.put("remarkReasonKey", remark);
        m.put("remarkNote", null);
        return m;
    }

    private long requestVersion(Sw w, Pn who, JsonNode indent) throws Exception {
        return ok(HttpMethod.GET, PH + "/approvals/" + indent.get("approval").get("requestId").asText(), who.auth(), null, 200).get("version").asLong();
    }

    private Map<String, Object> reasonBody(JsonNode indent, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", indent.get("version").asLong());
        m.put("reasonKey", reason);
        m.put("note", null);
        return m;
    }

    private Pn hod(Sw w) throws Exception {
        Pn hod = crew(w.clinic(), "NURSE", "Hema Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        return hod;
    }

    // ---------------- raise, edit, discard ----------------

    @Test
    void aDraftIsRaisedForTheRequestingStoreToItsIssuingStoreWithNoNumberAndTheSameKeyReplays() throws Exception {
        Sw w = stockWorld();
        UUID key = UUID.randomUUID();
        Map<String, Object> body = createBody(key, "URGENT", List.of(line(w.productA(), 20)));
        JsonNode draft = ok(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(), js(body), 201);
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("number").isNull()).isTrue();
        assertThat(draft.get("priority").asText()).isEqualTo("URGENT");
        assertThat(draft.get("fromStore").get("storeId").asText()).isEqualTo(w.pharmacyId());
        assertThat(draft.get("toStore").get("storeId").asText()).as("Issues-from is the Central Store").isEqualTo(w.centralId());
        assertThat(draft.get("lines")).hasSize(1);
        assertThat(draft.get("lines").get(0).get("requestedQty").asInt()).isEqualTo(20);
        assertThat(draft.get("raisedBy").get("name").asText()).isEqualTo("Pia Pharmacist");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_INDENT_CREATED")).hasSize(1);
        JsonNode replay = ok(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(), js(body), 200);
        assertThat(idOf(replay)).isEqualTo(idOf(draft));
        assertThat(pharmacyRows(w.clinic(), "indent")).isOne();
        // the same key by another person: 409 client-key-reused
        Pn other = crew(w.clinic(), "PHARMACIST", "Omar Other");
        mapStaff(w.clinic(), w.pharmacyId(), other, "STORE_USER");
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()), other.auth(), js(body)), 409), "client-key-reused");
        assertThat(pharmacyRows(w.clinic(), "indent")).isOne();
    }

    @Test
    void theCreateRulesAreCheckedTogetherAndAnyBadBodyIs400WithAProblemBody() throws Exception {
        Sw w = stockWorld();
        String prod = w.productA();
        List<Object[]> bad = new ArrayList<>();
        bad.add(new Object[] {"no lines", createBody(UUID.randomUUID(), "ROUTINE", List.of())});
        bad.add(new Object[] {"qty 0", createBody(UUID.randomUUID(), "ROUTINE", List.of(line(prod, 0)))});
        bad.add(new Object[] {"qty 1000000", createBody(UUID.randomUUID(), "ROUTINE", List.of(line(prod, 1_000_000)))});
        bad.add(new Object[] {"priority", createBody(UUID.randomUUID(), "SOON", List.of(line(prod, 1)))});
        bad.add(new Object[] {"same product twice", createBody(UUID.randomUUID(), "ROUTINE", List.of(line(prod, 1), line(prod, 2)))});
        bad.add(new Object[] {"unknown product", createBody(UUID.randomUUID(), "ROUTINE", List.of(line(UUID.randomUUID().toString(), 1)))});
        List<String> notRefused = new ArrayList<>();
        for (Object[] b : bad) {
            @SuppressWarnings("unchecked")
            int status = call(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(), js((Map<String, Object>) b[1])).andReturn().getResponse().getStatus();
            if (status != 400) {
                notRefused.add(b[0] + " answered " + status);
            }
        }
        assertThat(notRefused).as("every bad body is 400").isEmpty();
        JsonNode twice = problem(call(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(),
                js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(prod, 1), line(prod, 2))))), 400);
        assertType(twice, "validation");
        assertFieldMessage(twice, "lines[1].productId", "Already on this indent");
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(), "not json"), 400), "malformed-request");
        assertThat(pharmacyRows(w.clinic(), "indent")).isZero();
        // the boundaries pass: quantity 1 and 999999
        ok(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(), js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(prod, 1), line(w.productB(), 999_999)))), 201);
    }

    @Test
    void theCentralStoreRefusesAnIndentWith409DocStateAndAPersonWithoutTheCapabilityIsRefused() throws Exception {
        Sw w = stockWorld();
        JsonNode central = problem(call(HttpMethod.POST, ind(w.centralId()), w.clinic().owner(),
                js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(w.productA(), 5))))), 409);
        assertType(central, "doc-state");
        assertThat(central.get("detail").asText()).isEqualTo(CENTRAL_BUYS);
        // a Central Store user is not mapped at the pharmacy: 404; a front desk person has no pharmacy access: 403
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(w.productA(), 5))))), 404), "not-found");
        problem(call(HttpMethod.POST, ind(w.pharmacyId()), crew(w.clinic(), "FRONT_DESK").auth(),
                js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(w.productA(), 5))))), 403);
        // the Central Store user cannot raise at his own store (the Central Store buys by purchase order)
        problem(call(HttpMethod.POST, ind(w.centralId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(w.productA(), 5))))), 403);
        assertThat(pharmacyRows(w.clinic(), "indent")).isZero();
    }

    @Test
    void theRaiserEditsTheDraftAndAStaleVersionIs409ChangedAndASubmittedIndentCannotBeEdited() throws Exception {
        Sw w = stockWorld();
        noApprovalRules(w);
        JsonNode draft = raise(w, w.keeper(), "ROUTINE", 20, 10);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("version", draft.get("version").asLong());
        edit.put("priority", "URGENT");
        edit.put("lines", List.of(line(w.productA(), 25)));
        JsonNode saved = ok(HttpMethod.PUT, ind(w.pharmacyId()) + "/" + idOf(draft), w.keeper().auth(), js(edit), 200);
        assertThat(saved.get("priority").asText()).isEqualTo("URGENT");
        assertThat(saved.get("lines")).hasSize(1);
        assertThat(saved.get("lines").get(0).get("requestedQty").asInt()).isEqualTo(25);
        assertThat(saved.get("version").asLong()).isGreaterThan(draft.get("version").asLong());
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_INDENT_CHANGED")).hasSize(1);
        // the old version
        assertType(problem(call(HttpMethod.PUT, ind(w.pharmacyId()) + "/" + idOf(draft), w.keeper().auth(), js(edit)), 409), "changed");
        // bad edit: no lines
        edit.put("version", saved.get("version").asLong());
        edit.put("lines", List.of());
        problem(call(HttpMethod.PUT, ind(w.pharmacyId()) + "/" + idOf(draft), w.keeper().auth(), js(edit)), 400);
        // submitted: cannot be edited
        JsonNode sent = submit(w, w.keeper(), saved, 200);
        edit.put("version", sent.get("version").asLong());
        edit.put("lines", List.of(line(w.productA(), 5)));
        assertType(problem(call(HttpMethod.PUT, ind(w.pharmacyId()) + "/" + idOf(draft), w.keeper().auth(), js(edit)), 409), "doc-state");
    }

    @Test
    void aDraftCanBeDiscardedOnlyByItsRaiserAndADiscardedIndentIsNeverListed() throws Exception {
        Sw w = stockWorld();
        JsonNode draft = raise(w, w.keeper(), "ROUTINE", 20, 10);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", draft.get("version").asLong());
        JsonNode gone = ok(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(draft) + "/discard", w.keeper().auth(), js(v), 200);
        assertThat(gone.get("status").asText()).isEqualTo("DISCARDED");
        assertThat(ok(HttpMethod.GET, ind(w.pharmacyId()), w.keeper().auth(), null, 200).get("items")).isEmpty();
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(draft) + "/discard", w.keeper().auth(), js(v)), 409), "doc-state");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_INDENT_DISCARDED")).hasSize(1);
    }

    @Test
    void anotherPersonsDraftIs404ForReadEditDiscardAndSubmitAndIsNotListed() throws Exception {
        Sw w = stockWorld();
        JsonNode draft = raise(w, w.keeper(), "ROUTINE", 20, 10);
        Pn other = crew(w.clinic(), "PHARMACIST", "Omar Other");
        mapStaff(w.clinic(), w.pharmacyId(), other, "STORE_USER");
        String url = ind(w.pharmacyId()) + "/" + idOf(draft);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", draft.get("version").asLong());
        assertType(problem(call(HttpMethod.GET, url, other.auth(), null), 404), "not-found");
        Map<String, Object> edit = new LinkedHashMap<>(v);
        edit.put("priority", "ROUTINE");
        edit.put("lines", List.of(line(w.productA(), 3)));
        assertType(problem(call(HttpMethod.PUT, url, other.auth(), js(edit)), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, url + "/discard", other.auth(), js(v)), 404), "not-found");
        Map<String, Object> sub = new LinkedHashMap<>(v);
        sub.put("clientKey", UUID.randomUUID().toString());
        assertType(problem(call(HttpMethod.POST, url + "/submit", other.auth(), js(sub)), 404), "not-found");
        assertThat(ok(HttpMethod.GET, ind(w.pharmacyId()), other.auth(), null, 200).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, ind(w.pharmacyId()), w.keeper().auth(), null, 200).get("items")).hasSize(1);
        assertThat(get(w, w.keeper(), idOf(draft)).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------------- submit ----------------

    @Test
    void submitAtLevelZeroTakesTheNumberAndIsApprovedAtOnceWithTheRequestedQuantities() throws Exception {
        Sw w = stockWorld();
        noApprovalRules(w);
        JsonNode draft = raise(w, w.keeper(), "ROUTINE", 20, 10);
        JsonNode sent = submit(w, w.keeper(), draft, 200);
        assertThat(sent.get("status").asText()).isEqualTo("APPROVED");
        assertThat(sent.get("number").asText()).startsWith("IND");
        assertThat(sent.get("approval").isNull()).as("no rule applied").isTrue();
        assertThat(lineOf(sent, w.productA()).get("approvedQty").asInt()).isEqualTo(20);
        assertThat(lineOf(sent, w.productB()).get("approvedQty").asInt()).isEqualTo(10);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_INDENT_SUBMITTED")).hasSize(1);
        // the second submit: doc-state
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(draft) + "/submit", w.keeper().auth(),
                js(Map.of("version", sent.get("version").asLong(), "clientKey", UUID.randomUUID().toString()))), 409), "doc-state");
        // the Central Store sees it to dispatch
        JsonNode todo = ok(HttpMethod.GET, ind(w.centralId()) + "?view=TO_DISPATCH", w.csUser().auth(), null, 200);
        assertThat(todo.get("items")).hasSize(1);
        assertThat(todo.get("items").get(0).get("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void submitWithTheStandardDefaultWaitsForTheHodAndTheHodSeesItInTheInbox() throws Exception {
        Sw w = stockWorld();
        Pn hod = hod(w);
        JsonNode draft = raise(w, w.keeper(), "ROUTINE", 20, 10);
        JsonNode sent = submit(w, w.keeper(), draft, 200);
        assertThat(sent.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(sent.get("number").asText()).startsWith("IND");
        assertThat(sent.get("approval").get("waitingFor").asText()).isEqualTo("HOD");
        assertThat(lineOf(sent, w.productA()).get("approvedQty").isNull()).as("not decided yet").isTrue();
        JsonNode inbox = ok(HttpMethod.GET, PH + "/approvals/inbox", hod.auth(), null, 200);
        assertThat(inbox.toString()).contains(sent.get("approval").get("requestId").asText());
        // the raiser never decides his own; a plain store user is not an approver
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox", w.keeper().auth(), null, 200).get("items")).isEmpty();
        assertThat(ok(HttpMethod.GET, ind(w.centralId()) + "?view=TO_DISPATCH", w.csUser().auth(), null, 200).get("items")).as("not yet with the Central Store").isEmpty();
    }

    // ---------------- the approver's decision (79-10) ----------------

    @Test
    void theHodApprovesWithAReducedLineAndARaisedLineAndTheChangesAreSavedWithRemarkAndHistory() throws Exception {
        Sw w = stockWorld();
        Pn hod = hod(w);
        JsonNode sent = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        String requestId = sent.get("approval").get("requestId").asText();
        long version = requestVersion(w, hod, sent);
        JsonNode a = lineOf(sent, w.productA());
        JsonNode b = lineOf(sent, w.productB());
        JsonNode done = ok(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 15, REDUCE_REASON), approved(b, 14, null))), 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        JsonNode after = get(w, w.keeper(), idOf(sent));
        assertThat(after.get("status").asText()).isEqualTo("APPROVED");
        assertThat(lineOf(after, w.productA()).get("approvedQty").asInt()).isEqualTo(15);
        assertThat(lineOf(after, w.productA()).get("hodChange").asInt()).isEqualTo(-5);
        assertThat(lineOf(after, w.productA()).get("hodRemark").get("key").asText()).isEqualTo(REDUCE_REASON);
        assertThat(lineOf(after, w.productB()).get("approvedQty").asInt()).isEqualTo(14);
        assertThat(lineOf(after, w.productB()).get("hodChange").asInt()).as("+4 by HOD").isEqualTo(4);
        assertThat(after.get("history").toString()).contains("LINE_CHANGED");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.document_event where event = 'LINE_CHANGED'")).isEqualTo(2);
        assertThat(ok(HttpMethod.GET, ind(w.centralId()) + "?view=TO_DISPATCH", w.csUser().auth(), null, 200).get("items")).hasSize(1);
    }

    @Test
    void aReducedLineWithoutARemarkEveryQuantityZeroAndAMissingLineAreRefusedAndNothingChanges() throws Exception {
        Sw w = stockWorld();
        Pn hod = hod(w);
        JsonNode sent = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        String requestId = sent.get("approval").get("requestId").asText();
        long version = requestVersion(w, hod, sent);
        JsonNode a = lineOf(sent, w.productA());
        JsonNode b = lineOf(sent, w.productB());
        JsonNode noRemark = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 15, null), approved(b, 10, null)))), 400);
        assertFieldMessage(noRemark, "indentLines[0].remarkReasonKey", "Choose a reason");
        JsonNode otherNoNote = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 15, "approval-reject.other"), approved(b, 10, null)))), 400);
        assertFieldMessage(otherNoNote, "indentLines[0].remarkNote", "Write at least 4 characters");
        JsonNode zero = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 0, REDUCE_REASON), approved(b, 0, REDUCE_REASON)))), 400);
        assertType(zero, "validation");
        assertThat(zero.get("fieldErrors").toString()).contains("indentLines").contains("Reject the indent with a reason instead");
        JsonNode oneOnly = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 20, null)))), 400);
        assertFieldMessage(oneOnly, "indentLines", "List every line once");
        JsonNode unknownLine = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(Map.of("indentLineId", UUID.randomUUID().toString(), "approvedQty", 5), approved(b, 10, null)))), 400);
        assertThat(unknownLine.get("fieldErrors").toString()).contains("indentLines[0].indentLineId");
        JsonNode huge = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 1_000_000, null), approved(b, 10, null)))), 400);
        assertThat(huge.get("fieldErrors").toString()).contains("indentLines[0].approvedQty");
        assertThat(get(w, w.keeper(), idOf(sent)).get("status").asText()).as("nothing was decided").isEqualTo("WAITING_APPROVAL");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_decision")).isZero();
        // a quantity 0 on one line with the other kept is the line rejected, and is accepted with a remark
        JsonNode done = ok(HttpMethod.POST, decisionUrl(requestId), hod.auth(),
                decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 0, REDUCE_REASON), approved(b, 10, null))), 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(lineOf(get(w, w.keeper(), idOf(sent)), w.productA()).get("approvedQty").asInt()).isZero();
    }

    @Test
    void anIndentIsRejectedWithAReasonAndNoStockMovesAndTheRaiserSeesIt() throws Exception {
        Sw w = stockWorld();
        Pn hod = hod(w);
        JsonNode sent = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        String requestId = sent.get("approval").get("requestId").asText();
        long version = requestVersion(w, hod, sent);
        problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(), decision(UUID.randomUUID(), version, "REJECTED", null, null)), 400);
        JsonNode done = ok(HttpMethod.POST, decisionUrl(requestId), hod.auth(), decision(UUID.randomUUID(), version, "REJECTED", "approval-reject.not-needed", null), 200);
        assertThat(done.get("status").asText()).isEqualTo("REJECTED");
        assertThat(get(w, w.keeper(), idOf(sent)).get("status").asText()).isEqualTo("REJECTED");
        assertThat(ok(HttpMethod.GET, ind(w.centralId()) + "?view=TO_DISPATCH", w.csUser().auth(), null, 200).get("items")).isEmpty();
        assertThat(ledgerSum(w, "select count(*) from pharmacy.stock_ledger where txn_type like 'INDENT%'")).isZero();
    }

    @Test
    void whenTheStoreHasNoHodTheOwnerDecidesAndAPersonWhoIsNotAnApproverIsRefused() throws Exception {
        Sw w = stockWorld();
        JsonNode sent = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        String requestId = sent.get("approval").get("requestId").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + requestId, w.clinic().owner(), null, 200).get("version").asLong();
        JsonNode a = lineOf(sent, w.productA());
        JsonNode b = lineOf(sent, w.productB());
        String body = decision(UUID.randomUUID(), version, "APPROVED", null, List.of(approved(a, 20, null), approved(b, 10, null)));
        problem(call(HttpMethod.POST, decisionUrl(requestId), w.keeper().auth(), body), 403);
        problem(call(HttpMethod.POST, decisionUrl(requestId), w.csUser().auth(), body), 403);
        JsonNode done = ok(HttpMethod.POST, decisionUrl(requestId), w.clinic().owner(), body, 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(lineOf(get(w, w.keeper(), idOf(sent)), w.productA()).get("approvedQty").asInt()).isEqualTo(20);
    }

    // ---------------- cancel ----------------

    @Test
    void aWaitingIndentIsCancelledByItsRaiserWithAReasonAndTheRequestIsWithdrawn() throws Exception {
        Sw w = stockWorld();
        JsonNode sent = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        Map<String, Object> body = reasonBody(sent, "approval-reject.not-needed");
        body.put("reasonKey", null);
        problem(call(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(sent) + "/cancel", w.keeper().auth(), js(body)), 400);
        body.put("reasonKey", "approval-reject.not-needed");
        JsonNode cancelled = ok(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(sent) + "/cancel", w.keeper().auth(), js(body), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("number").asText()).as("keeps its number").isEqualTo(sent.get("number").asText());
        assertThat(count(w.clinic(), "select count(*) from pharmacy.approval_request where status = 'WITHDRAWN'")).isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_INDENT_CANCELLED")).hasSize(1);
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(sent) + "/cancel", w.keeper().auth(),
                js(reasonBody(cancelled, "approval-reject.not-needed"))), 409), "doc-state");
    }

    @Test
    void anApprovedIndentCancelsWhileNothingIsDispatchedAndAfterTheFirstDispatchCancelIs409DocState() throws Exception {
        Sw w = stockWorld();
        postImport(w.csUser().auth(), w.centralId(), approvedImport(w, w.csUser(), "CS", w.centralId()), UUID.randomUUID());
        noApprovalRules(w);
        JsonNode first = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        JsonNode cancelled = ok(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(first) + "/cancel", w.keeper().auth(),
                js(reasonBody(first, "approval-reject.not-needed")), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        // a second one is dispatched in part: cancel is refused
        JsonNode second = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("clientKey", UUID.randomUUID().toString());
        dispatch.put("indentVersion", second.get("version").asLong());
        Map<String, Object> dl = new LinkedHashMap<>();
        dl.put("indentLineId", lineOf(second, w.productA()).get("id").asText());
        dl.put("dispatchProductId", w.productA());
        dl.put("quantity", 5);
        dl.put("balanceAction", "BACK_ORDER");
        dispatch.put("lines", List.of(dl));
        ok(HttpMethod.POST, ind(w.centralId()) + "/" + idOf(second) + "/dispatches", w.csUser().auth(), js(dispatch), 201);
        JsonNode now = get(w, w.keeper(), idOf(second));
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(second) + "/cancel", w.keeper().auth(),
                js(reasonBody(now, "approval-reject.not-needed"))), 409), "doc-state");
        assertThat(get(w, w.keeper(), idOf(second)).get("status").asText()).isEqualTo("PARTLY_DISPATCHED");
    }

    // ---------------- lists ----------------

    @Test
    void theRaisedListFiltersByStatusNewestFirstAndTheDispatchListHasUrgentFirst() throws Exception {
        Sw w = stockWorld();
        noApprovalRules(w);
        JsonNode routine = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 5, 5), 200);
        JsonNode urgent = submit(w, w.keeper(), raise(w, w.keeper(), "URGENT", 6, 6), 200);
        raise(w, w.keeper(), "ROUTINE", 7, 7);
        JsonNode all = ok(HttpMethod.GET, ind(w.pharmacyId()), w.keeper().auth(), null, 200);
        assertThat(all.get("items")).hasSize(3);
        assertThat(all.get("items").get(0).get("status").asText()).as("newest first").isEqualTo("DRAFT");
        assertThat(ok(HttpMethod.GET, ind(w.pharmacyId()) + "?status=APPROVED", w.keeper().auth(), null, 200).get("items")).hasSize(2);
        assertThat(ok(HttpMethod.GET, ind(w.pharmacyId()) + "?status=DRAFT", w.keeper().auth(), null, 200).get("items")).hasSize(1);
        JsonNode todo = ok(HttpMethod.GET, ind(w.centralId()) + "?view=TO_DISPATCH", w.csUser().auth(), null, 200);
        assertThat(todo.get("items")).hasSize(2);
        assertThat(todo.get("items").get(0).get("id").asText()).as("urgent first").isEqualTo(idOf(urgent));
        assertThat(todo.get("items").get(1).get("id").asText()).isEqualTo(idOf(routine));
        problem(call(HttpMethod.GET, ind(w.pharmacyId()) + "?size=51", w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, ind(w.pharmacyId()) + "?view=NOPE", w.keeper().auth(), null), 400);
        // the dispatch list belongs to the Central Store
        problem(call(HttpMethod.GET, ind(w.pharmacyId()) + "?view=TO_DISPATCH", w.keeper().auth(), null), 403);
    }

    // ---------------- two clinics ----------------

    @Test
    void anotherClinicSeesNothingOfTheIndentNeitherInTheListNorByIdAndCannotDecideOrCancel() throws Exception {
        Sw w = stockWorld();
        Pn hod = hod(w);
        JsonNode sent = submit(w, w.keeper(), raise(w, w.keeper(), "ROUTINE", 20, 10), 200);
        Sw other = stockWorld();
        String url = ind(w.pharmacyId()) + "/" + idOf(sent);
        assertType(problem(call(HttpMethod.GET, url, other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, ind(other.pharmacyId()) + "/" + idOf(sent), other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, url + "/cancel", other.clinic().owner(), js(reasonBody(sent, "approval-reject.not-needed"))), 404), "not-found");
        assertThat(ok(HttpMethod.GET, ind(other.pharmacyId()), other.keeper().auth(), null, 200).get("items")).isEmpty();
        assertType(problem(call(HttpMethod.GET, PH + "/approvals/" + sent.get("approval").get("requestId").asText(), other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, decisionUrl(sent.get("approval").get("requestId").asText()), other.clinic().owner(),
                decision(UUID.randomUUID(), 0, "REJECTED", "approval-reject.not-needed", null)), 404), "not-found");
        assertThat(get(w, hod, idOf(sent)).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(pharmacyRows(other.clinic(), "indent")).isZero();
    }

    @Test
    void theModuleSwitchedOffAnswers403ModuleOffOnTheIndentOperations() throws Exception {
        Sw w = stockWorld();
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, ind(w.pharmacyId()), w.clinic().owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, ind(w.pharmacyId()), w.clinic().owner(),
                js(createBody(UUID.randomUUID(), "ROUTINE", List.of(line(w.productA(), 5))))), 403), "module-off");
    }
}
