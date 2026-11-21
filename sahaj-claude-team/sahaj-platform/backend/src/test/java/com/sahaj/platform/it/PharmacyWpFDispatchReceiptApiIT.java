package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-79-11 to SAH-79-13 (pharmacy.yaml 0.3.5, WP-F): the Central Store dispatch (FEFO, partial with back-order, closed
 * balance, replacement, Schedule X), the receipt at the requesting store (accepted, rejected, short, the second receipt of the
 * lines a non-pharmacist skipped), the return items (accept, dispute, reply), the shortages (found, transit loss), the indent
 * chips and status, and the reconciliation of ledger and balances after each flow. The last four tests are the queued decisions
 * of DEC-083 (M4, L20, I23, L21): they are expected to FAIL until the java-developer builds the fixes. The Central Store holds
 * 100 units of product A in batch TESTB001 (expiry 31 Dec, three years out), 3 in TESTB003 (four years out) and 40 of product B
 * in TESTB002, so FEFO takes TESTB001 first. Test plan: docs/test-plans/SAH-79.md (T79-11-*, T79-12-*, T79-13-*, T79-Q-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyWpFDispatchReceiptApiIT extends Sah79BuySupport {

    private static final String DAMAGED = "receipt-reject.damaged-in-transit";
    private static final String SHORT = "receipt-reject.short";
    private static final String NEAR_EXPIRY = "receipt-reject.near-expiry";
    private static final String NOTHING = "There is nothing to receive.";

    // ---------------- helpers ----------------

    /** The PIN set for the second signer by scheduleXInCentral. */
    private String xPin;

    private static String ind(Object storeId) {
        return STORES + "/" + storeId + "/indents";
    }

    private static String dis(Object storeId) {
        return STORES + "/" + storeId + "/dispatches";
    }

    private Sw csWorld() throws Exception {
        Sw w = stockWorld();
        noApprovalRules(w);
        postImport(w.csUser().auth(), w.centralId(), approvedImport(w, w.csUser(), "CS", w.centralId()), UUID.randomUUID());
        return w;
    }

    private Map<String, Object> iline(String productId, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("requestedQty", qty);
        return m;
    }

    private JsonNode approvedIndent(Sw w, Pn who, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("priority", "ROUTINE");
        m.put("lines", lines);
        JsonNode draft = ok(HttpMethod.POST, ind(w.pharmacyId()), who.auth(), js(m), 201);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", draft.get("version").asLong());
        s.put("clientKey", UUID.randomUUID().toString());
        JsonNode sent = ok(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(draft) + "/submit", who.auth(), js(s), 200);
        assertThat(sent.get("status").asText()).isEqualTo("APPROVED");
        return sent;
    }

    private JsonNode approvedIndent(Sw w, int qtyA, int qtyB) throws Exception {
        return approvedIndent(w, w.keeper(), List.of(iline(w.productA(), qtyA), iline(w.productB(), qtyB)));
    }

    private JsonNode indentNow(Sw w, JsonNode indent) throws Exception {
        return ok(HttpMethod.GET, ind(w.pharmacyId()) + "/" + idOf(indent), w.keeper().auth(), null, 200);
    }

    private JsonNode lineOf(JsonNode indent, String productId) {
        for (JsonNode l : indent.get("lines")) {
            if (productId.equals(l.get("product").get("productId").asText())) {
                return l;
            }
        }
        throw new AssertionError("No line for " + productId);
    }

    private Map<String, Object> dline(JsonNode indentLine, String productId, int qty, String action, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indentLineId", indentLine.get("id").asText());
        m.put("dispatchProductId", productId);
        m.put("quantity", qty);
        m.put("balanceAction", action);
        m.put("changeReasonKey", reason);
        m.put("changeNote", null);
        return m;
    }

    private String dispatchBody(UUID key, long version, Map<String, Object> signer, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("indentVersion", version);
        m.put("lines", lines);
        m.put("secondSigner", signer);
        return js(m);
    }

    private JsonNode dispatch(Sw w, Pn who, JsonNode indent, List<Map<String, Object>> lines, int expected) throws Exception {
        String url = ind(w.centralId()) + "/" + idOf(indent) + "/dispatches";
        String body = dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), null, lines);
        return expected == 201 ? ok(HttpMethod.POST, url, who.auth(), body, 201) : problem(call(HttpMethod.POST, url, who.auth(), body), expected);
    }

    private JsonNode dispatchAll(Sw w, JsonNode indent, int qtyA, int qtyB) throws Exception {
        return dispatch(w, w.csUser(), indent, List.of(dline(lineOf(indent, w.productA()), w.productA(), qtyA, null, null),
                dline(lineOf(indent, w.productB()), w.productB(), qtyB, null, null)), 201);
    }

    private JsonNode dispatchLineOf(JsonNode dispatch, String productId, int nth) {
        int seen = 0;
        for (JsonNode l : dispatch.get("lines")) {
            if (productId.equals(l.get("product").get("productId").asText()) && seen++ == nth) {
                return l;
            }
        }
        throw new AssertionError("No dispatch line " + nth + " for " + productId);
    }

    private Map<String, Object> rline(JsonNode line, int accepted, int rejected, String reason, boolean skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispatchLineId", line.get("id").asText());
        m.put("accepted", accepted);
        m.put("rejected", rejected);
        m.put("rejectReasonKey", reason);
        m.put("note", null);
        m.put("skipped", skipped);
        return m;
    }

    private Map<String, Object> acceptAll(JsonNode line) {
        return rline(line, line.get("dispatched").asInt(), 0, null, false);
    }

    private String receiptBody(JsonNode dispatch, Map<String, Object> signer, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("dispatchVersion", dispatch.get("version").asLong());
        m.put("lines", lines);
        m.put("secondSigner", signer);
        return js(m);
    }

    private JsonNode receive(Sw w, Pn who, JsonNode dispatch, Map<String, Object> signer, List<Map<String, Object>> lines, int expected) throws Exception {
        String url = dis(w.pharmacyId()) + "/" + idOf(dispatch) + "/receipt";
        String body = receiptBody(dispatch, signer, lines);
        return expected == 200 ? ok(HttpMethod.POST, url, who.auth(), body, 200) : problem(call(HttpMethod.POST, url, who.auth(), body), expected);
    }

    private JsonNode dispatchNow(Sw w, JsonNode dispatch) throws Exception {
        return ok(HttpMethod.GET, dis(w.pharmacyId()) + "/" + idOf(dispatch), w.keeper().auth(), null, 200);
    }

    private long qty(Sw w, String storeId, String productId, String state) throws Exception {
        return count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance where store_id = ?::uuid and product_id = ?::uuid and state = ?",
                storeId, productId, state);
    }

    private Pn nurse(Sw w) throws Exception {
        Pn nurse = crew(w.clinic(), "NURSE", "Nina Nurse");
        mapStaff(w.clinic(), w.pharmacyId(), nurse, "STORE_USER");
        return nurse;
    }

    /** Sets the expiry of the Central Store batch to yesterday (committed through a tenant-bound autocommit connection). */
    private void expire(Sw w, String batchNo) throws Exception {
        try (Connection c = db().migrator()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("create policy tmp_qa_expire on pharmacy.batch for update to sahaj_migrator using (true) with check (true)");
                st.execute("create policy tmp_qa_expire_sel on pharmacy.batch for select to sahaj_migrator using (true)");
                st.execute("alter table pharmacy.batch disable trigger user");
                try (PreparedStatement ps = c.prepareStatement("update pharmacy.batch set expiry_date = ?::date where batch_no = ? and tenant_id = ?::uuid")) {
                    ps.setString(1, todayIst().minusDays(1).toString());
                    ps.setString(2, batchNo);
                    ps.setString(3, w.clinic().tenantId().toString());
                    ps.executeUpdate();
                }
                st.execute("alter table pharmacy.batch enable trigger user");
                st.execute("drop policy tmp_qa_expire on pharmacy.batch");
                st.execute("drop policy tmp_qa_expire_sel on pharmacy.batch");
            }
            c.commit();
        }
    }

    private String scheduleXProduct(Sw w) throws Exception {
        Map<String, Object> body = ownBody("Test controlled dispatch item (fake)");
        body.put("schedule", "X");
        return createOwn(w.clinic(), body).get("productId").asText();
    }

    /** Puts the Schedule X product (8 units, batch SXD1) into the Central Store through a signed receipt. */
    private String scheduleXInCentral(Sw w) throws Exception {
        String x = scheduleXProduct(w);
        String vendor = buyVendor(w, "Sx Dispatch Vendor (fake)");
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        xPin = pin;
        JsonNode draft = draftReceipt(w, vendor, "INV-SXD", List.of(gline(x, "SXD1", farMonth(), 8, "50.00", "6.00")));
        post(w, w.csUser(), draft, signer(w.second(), pin), 200);
        return x;
    }

    private void assertReconciled(Sw w) throws Exception {
        assertLedgerEqualsBalances(w);
        assertPayablesEqualEntries(w);
    }

    // ---------------- dispatch (79-11) ----------------

    @Test
    void aFullDispatchTakesFefoBatchesMovesCentralToInTransitAndTheLedgerEqualsTheBalances() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 20, 10);
        long payable = payableOpen(w);
        JsonNode d = dispatchAll(w, indent, 20, 10);
        assertThat(d.get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(d.get("reference").asText()).startsWith(indent.get("number").asText()).endsWith("/ 1");
        assertThat(d.get("lines")).hasSize(2);
        assertThat(dispatchLineOf(d, w.productA(), 0).get("batch").get("batchNo").asText()).as("FEFO: the earlier expiry first").isEqualTo("TESTB001");
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).isEqualTo(83);
        assertThat(qty(w, w.centralId(), w.productB(), "AVAILABLE")).isEqualTo(30);
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isEqualTo(20);
        assertThat(qty(w, w.pharmacyId(), w.productB(), "IN_TRANSIT")).isEqualTo(10);
        assertThat(qty(w, w.pharmacyId(), w.productA(), "AVAILABLE")).as("nothing is saleable before the receipt").isZero();
        assertThat(ledgerOut(w, "INDENT_DISPATCH")).isEqualTo(30);
        assertThat(ledgerQty(w, "INDENT_DISPATCH")).isEqualTo(30);
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("DISPATCHED");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_INDENT_DISPATCHED")).hasSize(1);
        assertThat(payableOpen(w)).as("a dispatch never touches a payable").isEqualTo(payable);
        assertReconciled(w);
    }

    @Test
    void aDispatchOfMoreThanOneBatchSplitsIntoOneLinePerBatchInFefoOrder() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 102, 5);
        JsonNode d = dispatchAll(w, indent, 102, 5);
        int b1 = 0;
        int b3 = 0;
        for (JsonNode l : d.get("lines")) {
            if (w.productA().equals(l.get("product").get("productId").asText())) {
                if ("TESTB001".equals(l.get("batch").get("batchNo").asText())) {
                    b1 += l.get("dispatched").asInt();
                } else if ("TESTB003".equals(l.get("batch").get("batchNo").asText())) {
                    b3 += l.get("dispatched").asInt();
                }
            }
        }
        assertThat(b1).as("the earlier expiry is taken whole").isEqualTo(100);
        assertThat(b3).isEqualTo(2);
        assertThat(d.get("lines")).hasSize(3);
        assertReconciled(w);
    }

    @Test
    void aPartialDispatchNeedsABalanceActionBackOrderKeepsTheIndentOpenAndASecondDispatchCompletesIt() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 30, 10);
        JsonNode a = lineOf(indent, w.productA());
        JsonNode b = lineOf(indent, w.productB());
        // below the remaining quantity with no balance action: 400, all together
        JsonNode noAction = dispatch(w, w.csUser(), indent, List.of(dline(a, w.productA(), 10, null, null), dline(b, w.productB(), 10, null, null)), 400);
        assertThat(noAction.get("type").asText()).endsWith("validation");
        assertThat(noAction.toString()).contains("lines");
        // CLOSE needs a reason
        assertThat(dispatch(w, w.csUser(), indent, List.of(dline(a, w.productA(), 10, "CLOSE", null), dline(b, w.productB(), 10, null, null)), 400)
                .toString()).contains("lines");
        // every quantity 0
        assertThat(dispatch(w, w.csUser(), indent, List.of(dline(a, w.productA(), 0, "BACK_ORDER", null), dline(b, w.productB(), 0, "BACK_ORDER", null)), 400)
                .toString()).contains("Nothing to dispatch");
        // above the remaining approved quantity
        dispatch(w, w.csUser(), indent, List.of(dline(a, w.productA(), 31, null, null), dline(b, w.productB(), 10, null, null)), 400);
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).as("nothing moved by the refusals").isEqualTo(103);
        JsonNode first = dispatch(w, w.csUser(), indent, List.of(dline(a, w.productA(), 10, "BACK_ORDER", null), dline(b, w.productB(), 10, null, null)), 201);
        assertThat(dispatchLineOf(first, w.productA(), 0).get("backOrdered").asInt()).isEqualTo(20);
        JsonNode mid = indentNow(w, indent);
        assertThat(mid.get("status").asText()).isEqualTo("PARTLY_DISPATCHED");
        assertThat(mid.get("chips").get("backOrderOpen").asInt()).isPositive();
        // the old version is stale: 409 changed, nothing dispatched twice
        assertType(problem(call(HttpMethod.POST, ind(w.centralId()) + "/" + idOf(indent) + "/dispatches", w.csUser().auth(),
                dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), null, List.of(dline(a, w.productA(), 20, null, null)))), 409), "changed");
        JsonNode second = dispatch(w, w.csUser(), mid, List.of(dline(a, w.productA(), 20, null, null)), 201);
        assertThat(second.get("reference").asText()).endsWith("/ 2");
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("DISPATCHED");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isEqualTo(30);
        assertReconciled(w);
    }

    @Test
    void aClosedBalanceNeedsAReasonStopsCountingAndTheSameKeyReplaysWithoutADoubleDispatch() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 30, 10);
        JsonNode a = lineOf(indent, w.productA());
        JsonNode b = lineOf(indent, w.productB());
        UUID key = UUID.randomUUID();
        String body = dispatchBody(key, indent.get("version").asLong(), null,
                List.of(dline(a, w.productA(), 10, "CLOSE", "cs-change.out-of-stock"), dline(b, w.productB(), 10, null, null)));
        String url = ind(w.centralId()) + "/" + idOf(indent) + "/dispatches";
        JsonNode first = ok(HttpMethod.POST, url, w.csUser().auth(), body, 201);
        assertThat(dispatchLineOf(first, w.productA(), 0).get("backOrdered").asInt()).as("closed, so nothing is back-ordered").isZero();
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("DISPATCHED");
        JsonNode replay = ok(HttpMethod.POST, url, w.csUser().auth(), body, 200);
        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(ledgerOut(w, "INDENT_DISPATCH")).isEqualTo(20);
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).isEqualTo(93);
    }

    @Test
    void aDispatchAboveTheCentralStoreStockIs409InsufficientStockAndNothingMoves() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 150, 10);
        JsonNode problem = dispatch(w, w.csUser(), indent, List.of(dline(lineOf(indent, w.productA()), w.productA(), 150, null, null),
                dline(lineOf(indent, w.productB()), w.productB(), 10, null, null)), 409);
        assertType(problem, "insufficient-stock");
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).isEqualTo(103);
        assertThat(ledgerOut(w, "INDENT_DISPATCH")).isZero();
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void aReplacementOfTheSameSaltNeedsAReasonAndShowsAsReplacedByCentralStore() throws Exception {
        Sw w = csWorld();
        String salt = getProduct(w.clinic(), w.productB()).get("salt").asText();
        String r = createOwn(w.clinic(), ownBody("Replacement item (fake)", salt, "20 mg", "tablet")).get("productId").asText();
        postedReceipt(w, buyVendor(w, "Repl Vendor (fake)"), "INV-RP", todayIst().toString(), 30, List.of(gline(r, "RP1", farMonth(), 30, "20.00", "3.00")));
        JsonNode indent = approvedIndent(w, 5, 10);
        JsonNode b = lineOf(indent, w.productB());
        String options = ok(HttpMethod.GET, ind(w.centralId()) + "/" + idOf(indent) + "/lines/" + b.get("id").asText() + "/replacement-options",
                w.csUser().auth(), null, 200).toString();
        assertThat(options).contains(r);
        assertThat(dispatch(w, w.csUser(), indent, List.of(dline(lineOf(indent, w.productA()), w.productA(), 5, null, null),
                dline(b, r, 10, null, null)), 400).toString()).as("a replacement needs a reason").contains("lines");
        JsonNode d = dispatch(w, w.csUser(), indent, List.of(dline(lineOf(indent, w.productA()), w.productA(), 5, null, null),
                dline(b, r, 10, null, "cs-change.pack-or-size-change")), 201);
        JsonNode line = dispatchLineOf(d, r, 0);
        assertThat(line.get("replacedByCs").asBoolean()).isTrue();
        assertThat(line.get("changeReason").get("reasonKey").asText()).isEqualTo("cs-change.pack-or-size-change");
        assertThat(qty(w, w.pharmacyId(), r, "IN_TRANSIT")).isEqualTo(10);
        assertThat(qty(w, w.pharmacyId(), w.productB(), "IN_TRANSIT")).isZero();
        assertReconciled(w);
    }

    @Test
    void aScheduleXDispatchNeedsAPharmacistAndASecondSignerAndAnOwnerWhoIsNotAPharmacistIs403() throws Exception {
        Sw w = csWorld();
        String x = scheduleXInCentral(w);
        JsonNode indent = approvedIndent(w, w.keeper(), List.of(iline(x, 4)));
        JsonNode xl = lineOf(indent, x);
        String url = ind(w.centralId()) + "/" + idOf(indent) + "/dispatches";
        List<Map<String, Object>> lines = List.of(dline(xl, x, 4, null, null));
        assertType(problem(call(HttpMethod.POST, url, ownerPn(w.clinic()).auth(), dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), null, lines)), 403),
                "schedule-x-pharmacist");
        JsonNode missing = problem(call(HttpMethod.POST, url, w.csUser().auth(), dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), null, lines)), 400);
        assertFieldError(missing, "secondSigner");
        String pin = xPin;
        assertType(problem(call(HttpMethod.POST, url, w.csUser().auth(),
                dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), signer(w.second(), otherPin(w.second(), pin)), lines)), 403), "wrong-pin");
        assertThat(qty(w, w.pharmacyId(), x, "IN_TRANSIT")).isZero();
        JsonNode d = ok(HttpMethod.POST, url, w.csUser().auth(), dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), signer(w.second(), pin), lines), 201);
        assertThat(dispatchLineOf(d, x, 0).get("scheduleX").asBoolean()).isTrue();
        assertThat(d.toString()).doesNotContain("\"" + pin + "\"");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'INDENT_DISPATCH' and second_signer_membership_id = ?::uuid",
                w.second().membershipId().toString())).isPositive();
        assertReconciled(w);
    }

    @Test
    void dispatchPathsAreRefusedToAStoreUserAndAnotherClinicSeesNothing() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 5, 5);
        String url = ind(w.centralId()) + "/" + idOf(indent) + "/dispatches";
        String body = dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), null, List.of(dline(lineOf(indent, w.productA()), w.productA(), 5, null, null)));
        assertThat(call(HttpMethod.POST, url, w.keeper().auth(), body).andReturn().getResponse().getStatus()).isIn(403, 404);
        problem(call(HttpMethod.POST, url, crew(w.clinic(), "FRONT_DESK").auth(), body), 403);
        Sw other = csWorld();
        assertType(problem(call(HttpMethod.POST, ind(other.centralId()) + "/" + idOf(indent) + "/dispatches", other.csUser().auth(), body), 404), "not-found");
        problem(call(HttpMethod.POST, url, w.csUser().auth(), "{\"clientKey\":\"" + UUID.randomUUID() + "\",\"indentVersion\":1,\"lines\":[],\"surprise\":1}"), 400);
        assertThat(ledgerOut(w, "INDENT_DISPATCH")).isZero();
    }

    // ---------------- receipt (79-12) ----------------

    @Test
    void aReceiptOfAcceptedRejectedAndShortLinesMovesEachToItsStateAndOpensTheReturnItemAndTheShortage() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 20, 10);
        JsonNode d = dispatchAll(w, indent, 20, 10);
        JsonNode a = dispatchLineOf(d, w.productA(), 0);
        JsonNode b = dispatchLineOf(d, w.productB(), 0);
        // a rejected line with no reason, and quantities that do not add up
        assertThat(receive(w, w.keeper(), d, null, List.of(rline(a, 16, 4, null, false), acceptAll(b)), 400).toString()).contains("Choose a reason for the rejected quantity");
        assertThat(receive(w, w.keeper(), d, null, List.of(rline(a, 16, 3, DAMAGED, false), acceptAll(b)), 400).toString())
                .contains("Accepted + Rejected must equal Dispatched");
        // a line missing
        assertThat(receive(w, w.keeper(), d, null, List.of(acceptAll(a)), 400).toString()).contains("Send every line not yet received, once.");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).as("nothing moved by the refusals").isEqualTo(20);
        JsonNode done = receive(w, w.keeper(), d, null, List.of(rline(a, 16, 4, DAMAGED, false), rline(b, 6, 4, SHORT, false)), 200);
        assertThat(done.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(done.get("notes").get("rejectedNote").asText()).contains("4 rejected units do not enter");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "AVAILABLE")).isEqualTo(16);
        assertThat(qty(w, w.pharmacyId(), w.productA(), "PENDING_RETURN")).isEqualTo(4);
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isZero();
        assertThat(qty(w, w.pharmacyId(), w.productB(), "AVAILABLE")).isEqualTo(6);
        assertThat(qty(w, w.pharmacyId(), w.productB(), "IN_TRANSIT")).as("a short quantity stays in transit").isEqualTo(4);
        assertThat(qty(w, w.pharmacyId(), w.productB(), "PENDING_RETURN")).as("a short quantity is never a physical return").isZero();
        JsonNode chips = indentNow(w, indent).get("chips");
        assertThat(chips.get("rejectedWaitingCs").asInt()).isEqualTo(4);
        assertThat(chips.get("shortInvestigating").asInt()).isEqualTo(4);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_DISPATCH_RECEIVED")).hasSize(1);
        // a second receipt of a RECEIVED dispatch is 409 changed
        assertType(receive(w, w.keeper(), d, null, List.of(acceptAll(a), acceptAll(b)), 409), "changed");
        assertReconciled(w);
    }

    @Test
    void anExpiredBatchCannotBeAcceptedAndMayOnlyBeRejectedAsNearExpiry() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 10, 5);
        JsonNode d = dispatchAll(w, indent, 10, 5);
        expire(w, "TESTB001");
        JsonNode a = dispatchLineOf(d, w.productA(), 0);
        JsonNode b = dispatchLineOf(d, w.productB(), 0);
        receive(w, w.keeper(), d, null, List.of(acceptAll(a), acceptAll(b)), 400);
        JsonNode other = receive(w, w.keeper(), d, null, List.of(rline(a, 0, 10, DAMAGED, false), acceptAll(b)), 400);
        assertThat(other.toString()).contains("rejectReasonKey");
        JsonNode done = receive(w, w.keeper(), d, null, List.of(rline(a, 0, 10, NEAR_EXPIRY, false), acceptAll(b)), 200);
        assertThat(done.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "PENDING_RETURN")).isEqualTo(10);
        assertReconciled(w);
    }

    @Test
    void aNonPharmacistSkipsTheScheduleXLineAThenAPharmacistReceivesItAndTheIndentNeverTakesPartlyReceived() throws Exception {
        Sw w = csWorld();
        String x = scheduleXInCentral(w);
        JsonNode indent = approvedIndent(w, w.keeper(), List.of(iline(w.productA(), 10), iline(x, 4)));
        JsonNode xl = lineOf(indent, x);
        String pin = xPin;
        JsonNode d = ok(HttpMethod.POST, ind(w.centralId()) + "/" + idOf(indent) + "/dispatches", w.csUser().auth(),
                dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), signer(w.second(), pin),
                        List.of(dline(lineOf(indent, w.productA()), w.productA(), 10, null, null), dline(xl, x, 4, null, null))), 201);
        JsonNode a = dispatchLineOf(d, w.productA(), 0);
        JsonNode xd = dispatchLineOf(d, x, 0);
        Pn nurse = nurse(w);
        // a non-pharmacist with skipped false on the Schedule X line is 403
        assertType(receive(w, nurse, d, null, List.of(acceptAll(a), acceptAll(xd)), 403), "schedule-x-pharmacist");
        // every line skipped: 400 "There is nothing to receive."
        assertThat(receive(w, nurse, d, null, List.of(rline(xd, 0, 0, null, true)), 400).toString()).as("a missing line first").contains("lines");
        // skipped true on a line that is not Schedule X is 400
        receive(w, nurse, d, null, List.of(rline(a, 0, 0, null, true), rline(xd, 0, 0, null, true)), 400);
        JsonNode partly = receive(w, nurse, d, null, List.of(acceptAll(a), rline(xd, 0, 0, null, true)), 200);
        assertThat(partly.get("status").asText()).isEqualTo("PARTLY_RECEIVED");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "AVAILABLE")).isEqualTo(10);
        assertThat(qty(w, w.pharmacyId(), x, "IN_TRANSIT")).isEqualTo(4);
        JsonNode mid = indentNow(w, indent);
        assertThat(mid.get("status").asText()).isEqualTo("PARTLY_DISPATCHED");
        assertThat(mid.get("chips").get("scheduleXWaiting").asInt()).isPositive();
        // a re-send by the non-pharmacist of only skipped lines: 400 nothing to receive
        assertThat(receive(w, nurse, partly, null, List.of(rline(xd, 0, 0, null, true)), 400).toString()).contains(NOTHING);
        // the pharmacist receives only the line not yet received (no signer: 400 secondSigner)
        assertFieldError(receive(w, w.keeper(), partly, null, List.of(acceptAll(xd)), 400), "secondSigner");
        // the dispatch version is current
        assertType(receive(w, w.keeper(), d, signer(w.second(), pin), List.of(acceptAll(xd)), 409), "changed");
        JsonNode done = receive(w, w.keeper(), partly, signer(w.second(), pin), List.of(acceptAll(xd)), 200);
        assertThat(done.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(qty(w, w.pharmacyId(), x, "AVAILABLE")).isEqualTo(4);
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("RECEIVED");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.indent where status = 'PARTLY_RECEIVED'")).isZero();
        assertReconciled(w);
    }

    @Test
    void anIndentWithABackOrderStaysPartlyDispatchedAfterTheReceiptAndNeverMovesBackwards() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 30, 10);
        JsonNode a = lineOf(indent, w.productA());
        JsonNode first = dispatch(w, w.csUser(), indent, List.of(dline(a, w.productA(), 10, "BACK_ORDER", null),
                dline(lineOf(indent, w.productB()), w.productB(), 10, null, null)), 201);
        receive(w, w.keeper(), first, null, List.of(acceptAll(dispatchLineOf(first, w.productA(), 0)), acceptAll(dispatchLineOf(first, w.productB(), 0))), 200);
        JsonNode mid = indentNow(w, indent);
        assertThat(mid.get("status").asText()).as("a back-order is open").isEqualTo("PARTLY_DISPATCHED");
        JsonNode second = dispatch(w, w.csUser(), mid, List.of(dline(a, w.productA(), 20, null, null)), 201);
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("DISPATCHED");
        receive(w, w.keeper(), second, null, List.of(acceptAll(dispatchLineOf(second, w.productA(), 0))), 200);
        assertThat(indentNow(w, indent).get("status").asText()).isEqualTo("RECEIVED");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "AVAILABLE")).isEqualTo(30);
        assertReconciled(w);
    }

    @Test
    void theReceiptPathsAreRefusedToAPersonNotMappedAtTheReceivingStoreAndAnotherClinicSeesNothing() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 10, 5);
        JsonNode d = dispatchAll(w, indent, 10, 5);
        List<Map<String, Object>> lines = List.of(acceptAll(dispatchLineOf(d, w.productA(), 0)), acceptAll(dispatchLineOf(d, w.productB(), 0)));
        assertThat(call(HttpMethod.POST, dis(w.pharmacyId()) + "/" + idOf(d) + "/receipt", w.csUser().auth(), receiptBody(d, null, lines))
                .andReturn().getResponse().getStatus()).isIn(403, 404);
        problem(call(HttpMethod.POST, dis(w.pharmacyId()) + "/" + idOf(d) + "/receipt", crew(w.clinic(), "FRONT_DESK").auth(), receiptBody(d, null, lines)), 403);
        // the path store must be the receiving store
        assertType(problem(call(HttpMethod.POST, dis(w.centralId()) + "/" + idOf(d) + "/receipt", w.csUser().auth(), receiptBody(d, null, lines)), 404), "not-found");
        Sw other = csWorld();
        assertType(problem(call(HttpMethod.GET, dis(other.pharmacyId()) + "/" + idOf(d), other.keeper().auth(), null), 404), "not-found");
        // 401 lines is over the cap of the receipt body
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 401; i++) {
            many.add(rline(dispatchLineOf(d, w.productA(), 0), 1, 0, null, false));
        }
        assertThat(problem(call(HttpMethod.POST, dis(w.pharmacyId()) + "/" + idOf(d) + "/receipt", w.keeper().auth(), receiptBody(d, null, many)), 400).toString())
                .contains("lines");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "AVAILABLE")).isZero();
        // the incoming list shows it in transit, oldest first, then received
        assertThat(ok(HttpMethod.GET, dis(w.pharmacyId()), w.keeper().auth(), null, 200).get("items")).hasSize(1);
        problem(call(HttpMethod.GET, dis(w.pharmacyId()) + "?status=NOPE", w.keeper().auth(), null), 400);
    }

    // ---------------- return items and shortages (79-13) ----------------

    private JsonNode rejectedItem(Sw w, JsonNode d, JsonNode line, int accepted, int rejected, String reason, JsonNode other) throws Exception {
        receive(w, w.keeper(), d, null, other == null ? List.of(rline(line, accepted, rejected, reason, false))
                : List.of(rline(line, accepted, rejected, reason, false), acceptAll(other)), 200);
        JsonNode list = ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/waiting-for-action?side=ACCEPTING", w.csUser().auth(), null, 200);
        assertThat(list.get("items")).isNotEmpty();
        return list.get("items").get(0);
    }

    @Test
    void theCentralStoreAcceptsARejectedItemBackIntoSaleableStockOrQuarantineAndTheIndentCanBecomeReceived() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 20, 10);
        JsonNode d = dispatchAll(w, indent, 20, 10);
        JsonNode a = dispatchLineOf(d, w.productA(), 0);
        JsonNode item = rejectedItem(w, d, a, 16, 4, DAMAGED, dispatchLineOf(d, w.productB(), 0));
        assertThat(item.get("status").asText()).isEqualTo("WAITING");
        String url = STORES + "/" + w.centralId() + "/return-items/" + idOf(item);
        JsonNode detail = ok(HttpMethod.GET, url, w.csUser().auth(), null, 200);
        assertThat(detail.get("defaultPutBackInto").asText()).isEqualTo("QUARANTINE");
        assertThat(detail.get("type").asText()).isEqualTo("INDENT_REJECTION");
        // until accepted nothing changes at the Central Store (BR-25)
        assertThat(qty(w, w.centralId(), w.productA(), "QUARANTINE")).isZero();
        // the store that holds the units cannot accept them
        assertThat(call(HttpMethod.POST, url + "/accept", w.keeper().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", detail.get("version").asLong(), "putBackInto", "SALEABLE"))).andReturn().getResponse().getStatus())
                .isIn(403, 404);
        JsonNode accepted = ok(HttpMethod.POST, url + "/accept", w.csUser().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", detail.get("version").asLong(), "putBackInto", "QUARANTINE")), 200);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED_QUARANTINE");
        assertThat(accepted.get("resultNote").asText()).startsWith("Return accepted. 4 units added to Central Store");
        assertThat(qty(w, w.centralId(), w.productA(), "QUARANTINE")).isEqualTo(4);
        assertThat(qty(w, w.pharmacyId(), w.productA(), "PENDING_RETURN")).isZero();
        // accepted again: 409 changed
        assertType(problem(call(HttpMethod.POST, url + "/accept", w.csUser().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", detail.get("version").asLong(), "putBackInto", "SALEABLE"))), 409), "changed");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_RETURN_ACCEPTED")).hasSize(1);
        assertThat(indentNow(w, indent).get("chips").get("rejectedWaitingCs").asInt()).isZero();
        assertReconciled(w);
    }

    @Test
    void aDisputedItemShowsTheReasonTheHoldingStoreMayReplyAndTheCentralStoreMayStillAcceptItSaleable() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 20, 10);
        JsonNode d = dispatchAll(w, indent, 20, 10);
        JsonNode item = rejectedItem(w, d, dispatchLineOf(d, w.productA(), 0), 15, 5, "receipt-reject.wrong-item", dispatchLineOf(d, w.productB(), 0));
        String url = STORES + "/" + w.centralId() + "/return-items/" + idOf(item);
        JsonNode detail = ok(HttpMethod.GET, url, w.csUser().auth(), null, 200);
        assertThat(detail.get("defaultPutBackInto").asText()).isEqualTo("SALEABLE");
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("version", detail.get("version").asLong());
        dispute.put("reasonKey", "dispute.other");
        dispute.put("note", "Please check the pack");
        JsonNode disputed = ok(HttpMethod.POST, url + "/dispute", w.csUser().auth(), js(dispute), 200);
        assertThat(disputed.get("status").asText()).isEqualTo("DISPUTED");
        assertThat(disputed.get("dispute").get("reason").get("reasonKey").asText()).isEqualTo("dispute.other");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "PENDING_RETURN")).as("the units stay with the holding store").isEqualTo(5);
        assertType(problem(call(HttpMethod.POST, url + "/dispute", w.csUser().auth(), js(dispute)), 409), "changed");
        // the holding store reads and replies (note of 4 to 300 characters)
        String holding = STORES + "/" + w.pharmacyId() + "/return-items/" + idOf(item);
        ok(HttpMethod.GET, holding, w.keeper().auth(), null, 200);
        problem(call(HttpMethod.POST, holding + "/notes", w.keeper().auth(), js(Map.of("clientKey", UUID.randomUUID().toString(), "note", "abc"))), 400);
        JsonNode replied = ok(HttpMethod.POST, holding + "/notes", w.keeper().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "note", "The pack is the one ordered")), 201);
        assertThat(replied.get("notes")).as("the dispute note and the reply").hasSize(2);
        JsonNode accepted = ok(HttpMethod.POST, url + "/accept", w.csUser().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", replied.get("version").asLong(), "putBackInto", "SALEABLE")), 200);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED_SALEABLE");
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).isEqualTo(88);
        assertReconciled(w);
    }

    @Test
    void anExpiredItemCannotBeAcceptedAsSaleable() throws Exception {
        Sw w = csWorld();
        JsonNode indent = approvedIndent(w, 10, 5);
        JsonNode d = dispatchAll(w, indent, 10, 5);
        expire(w, "TESTB001");
        JsonNode item = rejectedItem(w, d, dispatchLineOf(d, w.productA(), 0), 0, 10, NEAR_EXPIRY, dispatchLineOf(d, w.productB(), 0));
        String url = STORES + "/" + w.centralId() + "/return-items/" + idOf(item);
        JsonNode detail = ok(HttpMethod.GET, url, w.csUser().auth(), null, 200);
        assertThat(detail.get("expired").asBoolean()).isTrue();
        assertThat(detail.get("defaultPutBackInto").asText()).isEqualTo("QUARANTINE");
        assertType(problem(call(HttpMethod.POST, url + "/accept", w.csUser().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", detail.get("version").asLong(), "putBackInto", "SALEABLE"))), 409), "batch-expired");
    }

    private JsonNode openShortage(Sw w, int dispatched, int received) throws Exception {
        JsonNode indent = approvedIndent(w, dispatched, 5);
        JsonNode d = dispatchAll(w, indent, dispatched, 5);
        JsonNode a = dispatchLineOf(d, w.productA(), 0);
        receive(w, w.keeper(), d, null, List.of(rline(a, received, dispatched - received, SHORT, false), acceptAll(dispatchLineOf(d, w.productB(), 0))), 200);
        JsonNode list = ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/shortages", w.csUser().auth(), null, 200);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("status").asText()).isEqualTo("OPEN");
        return list.get("items").get(0);
    }

    private JsonNode settle(Sw w, JsonNode shortage, String outcome, int expected) throws Exception {
        String url = STORES + "/" + w.centralId() + "/shortages/" + idOf(shortage) + "/settle";
        String body = js(Map.of("clientKey", UUID.randomUUID().toString(), "version", shortage.get("version").asLong(), "outcome", outcome));
        return expected == 200 ? ok(HttpMethod.POST, url, w.csUser().auth(), body, 200) : problem(call(HttpMethod.POST, url, w.csUser().auth(), body), expected);
    }

    @Test
    void aShortageFoundInTheCentralStoreReturnsTheUnitsToItsAvailableStock() throws Exception {
        Sw w = csWorld();
        JsonNode shortage = openShortage(w, 20, 15);
        assertThat(shortage.get("qty").asInt()).isEqualTo(5);
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isEqualTo(5);
        JsonNode found = settle(w, shortage, "FOUND", 200);
        assertThat(found.get("status").asText()).isEqualTo("FOUND");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isZero();
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).as("83 left plus 5 found").isEqualTo(88);
        assertType(settle(w, found, "FOUND", 409), "changed");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_SHORTAGE_SETTLED")).hasSize(1);
        assertReconciled(w);
    }

    @Test
    void aCarrierClaimKeepsTheUnitsInTransitAndATransitLossAtLevelZeroWritesThemOffNow() throws Exception {
        Sw w = csWorld();
        JsonNode shortage = openShortage(w, 20, 15);
        JsonNode claim = settle(w, shortage, "CARRIER_CLAIM", 200);
        assertThat(claim.get("status").asText()).isEqualTo("CLAIM_OPEN");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isEqualTo(5);
        JsonNode lost = settle(w, claim, "TRANSIT_LOSS", 200);
        assertThat(lost.get("status").asText()).isEqualTo("WRITTEN_OFF");
        assertThat(lost.get("resultNote").asText()).contains("5 units written off as transit loss");
        assertThat(qty(w, w.pharmacyId(), w.productA(), "IN_TRANSIT")).isZero();
        assertThat(ledgerOut(w, "SHORTAGE_WRITE_OFF")).isEqualTo(5);
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).as("no stock added back").isEqualTo(83);
        assertReconciled(w);
    }

    @Test
    void theShortageListIsRefusedToAStoreUserAndAnotherClinicSeesNothing() throws Exception {
        Sw w = csWorld();
        JsonNode shortage = openShortage(w, 20, 15);
        assertThat(call(HttpMethod.GET, STORES + "/" + w.centralId() + "/shortages", w.keeper().auth(), null).andReturn().getResponse().getStatus()).isIn(403, 404);
        Sw other = csWorld();
        assertThat(ok(HttpMethod.GET, STORES + "/" + other.centralId() + "/shortages", other.csUser().auth(), null, 200).get("items")).isEmpty();
        assertType(problem(call(HttpMethod.POST, STORES + "/" + other.centralId() + "/shortages/" + idOf(shortage) + "/settle", other.csUser().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", 0, "outcome", "FOUND"))), 404), "not-found");
        problem(call(HttpMethod.POST, STORES + "/" + w.centralId() + "/shortages/" + idOf(shortage) + "/settle", w.csUser().auth(),
                js(Map.of("clientKey", UUID.randomUUID().toString(), "version", shortage.get("version").asLong(), "outcome", "GIVE_UP"))), 400);
    }

    // ---------------- queued decisions of DEC-083: EXPECTED TO FAIL until the java-developer builds them ----------------

    @Test
    void m4aDispatchThatPicksMoreThan400DispatchLinesIsRefusedWith400LinesAndNothingMoves() throws Exception {
        Sw w = stockWorld();
        noApprovalRules(w);
        String importId = idOf(startImport(w.csUser().auth(), w.centralId(), 401));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 401; i++) {
            rows.add(row(i, "CS", w.codeA(), String.format("MB%04d", i), farExpiry(), "45.50", "30.25", "1"));
        }
        for (int from = 0; from < rows.size(); from += 200) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", rows.subList(from, Math.min(rows.size(), from + 200)));
            addRows(w.csUser().auth(), w.centralId(), importId, body);
        }
        submit(w.csUser().auth(), w.centralId(), importId);
        ok(HttpMethod.POST, importUrl(w.centralId(), importId) + "/approve", w.second().auth(), null, 200);
        postImport(w.csUser().auth(), w.centralId(), importId, UUID.randomUUID());
        JsonNode indent = approvedIndent(w, w.keeper(), List.of(iline(w.productA(), 401)));
        JsonNode problem = dispatch(w, w.csUser(), indent, List.of(dline(lineOf(indent, w.productA()), w.productA(), 401, null, null)), 400);
        assertThat(problem.get("type").asText()).endsWith("validation");
        assertThat(problem.toString()).contains("lines").contains("Split this dispatch into smaller ones.");
        assertThat(ledgerOut(w, "INDENT_DISPATCH")).isZero();
        assertThat(qty(w, w.centralId(), w.productA(), "AVAILABLE")).isEqualTo(401);
        // 400 lines are still allowed: dispatch part of it and the rest later
        JsonNode ok400 = dispatch(w, w.csUser(), indent, List.of(dline(lineOf(indent, w.productA()), w.productA(), 400, "BACK_ORDER", null)), 201);
        assertThat(ok400.get("lines")).hasSize(400);
    }

    @Test
    void l20AnApproverBelowTheLastLevelCannotRaiseAQuantityAboveTheRequestedOneButTheLastLevelCan() throws Exception {
        Sw w = csWorld();
        putRules(w, only(rule("INDENT", null, null, always(1, "STORE_APPROVER"), always(2, "OWNER"))));
        Pn hod = crew(w.clinic(), "NURSE", "Hema Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("priority", "ROUTINE");
        m.put("lines", List.of(iline(w.productA(), 10)));
        JsonNode draft = ok(HttpMethod.POST, ind(w.pharmacyId()), w.keeper().auth(), js(m), 201);
        JsonNode sent = ok(HttpMethod.POST, ind(w.pharmacyId()) + "/" + idOf(draft) + "/submit", w.keeper().auth(),
                js(Map.of("version", draft.get("version").asLong(), "clientKey", UUID.randomUUID().toString())), 200);
        String requestId = sent.get("approval").get("requestId").asText();
        String lineId = lineOf(sent, w.productA()).get("id").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + requestId, hod.auth(), null, 200).get("version").asLong();
        JsonNode refused = problem(call(HttpMethod.POST, decisionUrl(requestId), hod.auth(), raiseDecision(1, version, lineId, 999)), 400);
        assertThat(refused.toString()).contains("indentLines").contains("This increase needs the next approver.");
        ok(HttpMethod.POST, decisionUrl(requestId), hod.auth(), raiseDecision(1, version, lineId, 10), 200);
        long v2 = ok(HttpMethod.GET, PH + "/approvals/" + requestId, w.clinic().owner(), null, 200).get("version").asLong();
        ok(HttpMethod.POST, decisionUrl(requestId), w.clinic().owner(), raiseDecision(2, v2, lineId, 999), 200);
        assertThat(lineOf(indentNow(w, sent), w.productA()).get("approvedQty").asInt()).isEqualTo(999);
    }

    private String raiseDecision(int level, long version, String lineId, int approvedQty) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("indentLineId", lineId);
        line.put("approvedQty", approvedQty);
        line.put("remarkReasonKey", null);
        line.put("remarkNote", null);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("clientKey", UUID.randomUUID().toString());
        d.put("level", level);
        d.put("requestVersion", version);
        d.put("decision", "APPROVED");
        d.put("reasonKey", null);
        d.put("note", null);
        d.put("indentLines", List.of(line));
        d.put("damageRejectOutcome", null);
        return js(d);
    }

    @Test
    void i23ANonPharmacistCannotWriteOffScheduleXUnitsAsATransitLossAtLevelZero() throws Exception {
        Sw w = csWorld();
        String x = scheduleXInCentral(w);
        JsonNode indent = approvedIndent(w, w.keeper(), List.of(iline(x, 4)));
        String pin = xPin;
        JsonNode d = ok(HttpMethod.POST, ind(w.centralId()) + "/" + idOf(indent) + "/dispatches", w.csUser().auth(),
                dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), signer(w.second(), pin), List.of(dline(lineOf(indent, x), x, 4, null, null))), 201);
        JsonNode xd = dispatchLineOf(d, x, 0);
        receive(w, w.keeper(), d, signer(w.second(), pin), List.of(rline(xd, 1, 3, SHORT, false)), 200);
        JsonNode shortage = ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/shortages", w.csUser().auth(), null, 200).get("items").get(0);
        // the owner is not a pharmacist; the Central Store user is
        String url = STORES + "/" + w.centralId() + "/shortages/" + idOf(shortage) + "/settle";
        String body = js(Map.of("clientKey", UUID.randomUUID().toString(), "version", shortage.get("version").asLong(), "outcome", "TRANSIT_LOSS"));
        assertType(problem(call(HttpMethod.POST, url, ownerPn(w.clinic()).auth(), body), 403), "schedule-x-pharmacist");
        assertThat(qty(w, w.pharmacyId(), x, "IN_TRANSIT")).as("nothing written off").isEqualTo(3);
        assertThat(ledgerOut(w, "SHORTAGE_WRITE_OFF")).isZero();
        JsonNode lost = ok(HttpMethod.POST, url, w.csUser().auth(), body, 200);
        assertThat(lost.get("status").asText()).isEqualTo("WRITTEN_OFF");
        assertReconciled(w);
    }

    @Test
    void l21AMissingSignerMembershipIsTheFieldSecondSignerAndABadPinIsSecondSignerPin() throws Exception {
        Sw w = csWorld();
        String x = scheduleXInCentral(w);
        JsonNode indent = approvedIndent(w, w.keeper(), List.of(iline(x, 4)));
        String url = ind(w.centralId()) + "/" + idOf(indent) + "/dispatches";
        List<Map<String, Object>> lines = List.of(dline(lineOf(indent, x), x, 4, null, null));
        Map<String, Object> noMembership = new LinkedHashMap<>();
        noMembership.put("membershipId", null);
        noMembership.put("pin", "1234");
        JsonNode missing = problem(call(HttpMethod.POST, url, w.csUser().auth(), dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), noMembership, lines)), 400);
        assertFieldError(missing, "secondSigner");
        assertThat(missing.toString()).contains("A second signature is needed for Schedule X items.");
        Map<String, Object> badPin = new LinkedHashMap<>();
        badPin.put("membershipId", w.second().membershipId().toString());
        badPin.put("pin", "12");
        JsonNode bad = problem(call(HttpMethod.POST, url, w.csUser().auth(), dispatchBody(UUID.randomUUID(), indent.get("version").asLong(), badPin, lines)), 400);
        assertFieldError(bad, "secondSigner.pin");
    }
}
