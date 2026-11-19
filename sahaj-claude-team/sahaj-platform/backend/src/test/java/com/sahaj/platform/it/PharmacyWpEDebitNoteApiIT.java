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
 * SAH-79-8 (pharmacy.yaml 0.3.4, WP-E vendor return and debit note): the eligible rows and the credit level the server works,
 * create (credit and GST reversal per line), submit at level 0 (the units are held) and with a rule (they are held after
 * approval), picked, hand-over (Schedule X: a pharmacist and a signer), the credit note applied to the payables oldest due
 * first, cancel releases the hold, the short-supply-only note (CREATED and PICKED to CREDIT_NOTE_RECEIVED, migration 1740),
 * and the ledger, balance and payable reconciliation after each. A batch of product A received this month is near expiry, so
 * the vendor's return days (90) allow it: 10 boxes of 10 at ₹ 70.00 a box are 100 vials at ₹ 7.00. The vendor's standard credit
 * is 80 percent. Test plan: docs/test-plans/SAH-79.md (T79-8-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyWpEDebitNoteApiIT extends Sah79BuySupport {

    private record Dw(Sw w, String vendor, JsonNode grn) {
    }

    /** The world with 100 vials of product A, batch DN1, near expiry, received from the vendor (invoice dated 40 days ago, due 10 days ago). */
    private Dw world() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Return Vendor (fake)");
        JsonNode grn = postedReceipt(w, vendor, "INV-DN1", todayIst().minusDays(40).toString(), 30,
                List.of(gline(w.productA(), "DN1", thisMonth(), 10, "70.00", "12.00")));
        return new Dw(w, vendor, grn);
    }

    private JsonNode eligible(Sw w, String vendor) throws Exception {
        return ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/vendor-returns/eligible?vendorId=" + vendor, w.csUser().auth(), null, 200);
    }

    private JsonNode rowOf(JsonNode eligible, String batchNo) {
        for (JsonNode row : eligible.get("items")) {
            if (!row.get("batch").isNull() && batchNo.equals(row.get("batch").get("batchNo").asText())) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Object> stockLine(JsonNode row, int qty, String percent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "STOCK_RETURN");
        m.put("batchId", row.get("batch").get("batchId").asText());
        m.put("sourceState", row.get("sourceState").asText());
        m.put("goodsReceiptLineId", null);
        m.put("qty", qty);
        m.put("creditPercent", percent);
        return m;
    }

    private Map<String, Object> shortLine(String receiptLineId, int qty, String percent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "SHORT_SUPPLY");
        m.put("batchId", null);
        m.put("sourceState", null);
        m.put("goodsReceiptLineId", receiptLineId);
        m.put("qty", qty);
        m.put("creditPercent", percent);
        return m;
    }

    private Map<String, Object> noteBody(UUID key, String vendor, List<Map<String, Object>> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("vendorId", vendor);
        m.put("reasonKey", null);
        m.put("note", null);
        m.put("lines", lines);
        return m;
    }

    private JsonNode create(Sw w, Pn who, String vendor, List<Map<String, Object>> lines) throws Exception {
        return ok(HttpMethod.POST, dns(w.centralId()), who.auth(), js(noteBody(UUID.randomUUID(), vendor, lines)), 201);
    }

    private JsonNode step(Sw w, Pn who, JsonNode dn, String action, Map<String, Object> extra, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", dn.get("version").asLong());
        if (!action.equals("picked") && !action.equals("cancel")) {
            body.put("clientKey", UUID.randomUUID().toString());
        }
        body.putAll(extra);
        String url = dns(w.centralId()) + "/" + idOf(dn) + "/" + action;
        return expected == 200 ? ok(HttpMethod.POST, url, who.auth(), js(body), 200) : problem(call(HttpMethod.POST, url, who.auth(), js(body)), expected);
    }

    private JsonNode submit(Sw w, JsonNode dn) throws Exception {
        return step(w, w.csUser(), dn, "submit", Map.of(), 200);
    }

    private Map<String, Object> handOver(String name, Map<String, Object> signer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("representativeName", name);
        m.put("secondSigner", signer);
        return m;
    }

    private Map<String, Object> creditNote(long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("creditNoteNo", "CN-" + UUID.randomUUID().toString().substring(0, 6));
        m.put("creditNoteDate", todayIst().toString());
        m.put("amountPaise", amount);
        return m;
    }

    private JsonNode getDn(Sw w, String id) throws Exception {
        return ok(HttpMethod.GET, dns(w.centralId()) + "/" + id, w.csUser().auth(), null, 200);
    }

    // ---------------- eligible rows ----------------

    @Test
    void theEligibleRowsListOnlyThisVendorsNearExpiryBatchWithTheVendorsCreditAndNeverOpeningStockOrAnotherVendorsBatch() throws Exception {
        Dw d = world();
        Sw w = d.w();
        String other = buyVendor(w, "Other Return Vendor (fake)");
        postedReceipt(w, other, "INV-OTH", todayIst().toString(), 30, List.of(gline(w.productB(), "OT1", thisMonth(), 3, "20.00", "3.00")));
        postedReceipt(w, d.vendor(), "INV-FAR", todayIst().toString(), 30, List.of(gline(w.productB(), "FAR1", farMonth(), 3, "20.00", "3.00")));
        JsonNode e = eligible(w, d.vendor());
        assertThat(e.get("items")).as("only DN1: not the far batch, not the other vendor's, not the opening stock").hasSize(1);
        JsonNode row = rowOf(e, "DN1");
        assertThat(row.get("source").asText()).isEqualTo("NEAR_EXPIRY");
        assertThat(row.get("sourceState").asText()).isEqualTo("AVAILABLE");
        assertThat(row.get("availableQty").asInt()).isEqualTo(100);
        assertThat(row.get("rate").asDouble()).as("70.00 a box of 10").isEqualTo(7.0);
        assertThat(row.get("defaultCreditPercent").asDouble()).as("the vendor's standard level, no damaged or expired reason applies").isEqualTo(80.0);
        assertThat(row.get("daysToExpiry").asInt()).isBetween(0, 31);
        assertThat(e.get("policy").get("returnDaysBeforeExpiry").asInt()).isEqualTo(90);
        assertThat(rowOf(eligible(w, other), "OT1")).isNotNull();
        // a vendor with no standard credit and no levels: the default is null (the user types it)
        Map<String, Object> bare = vendorBody("Bare Vendor (fake)");
        bare.put("suppliesToStoreIds", List.of(w.centralId()));
        bare.put("returnCreditPercent", null);
        String bareVendor = idOf(createVendor(w.clinic(), bare));
        postedReceipt(w, bareVendor, "INV-BARE", todayIst().toString(), 30, List.of(gline(w.productB(), "BA1", thisMonth(), 2, "20.00", "3.00")));
        assertThat(rowOf(eligible(w, bareVendor), "BA1").get("defaultCreditPercent").isNull()).isTrue();
    }

    @Test
    void theEligibleReadIsRefusedAtAStoreThatIsNotCentralToAPersonWithoutPurchaseAndForAnotherClinic() throws Exception {
        Dw d = world();
        Sw w = d.w();
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/vendor-returns/eligible?vendorId=" + d.vendor(), w.clinic().owner(), null), 409), "doc-state");
        assertType(problem(call(HttpMethod.GET, STORES + "/" + w.pharmacyId() + "/vendor-returns/eligible?vendorId=" + d.vendor(), w.keeper().auth(), null), 403), "store-permission-denied");
        problem(call(HttpMethod.GET, STORES + "/" + w.centralId() + "/vendor-returns/eligible", w.csUser().auth(), null), 400);
        Sw other = stockWorld();
        assertType(problem(call(HttpMethod.GET, STORES + "/" + other.centralId() + "/vendor-returns/eligible?vendorId=" + d.vendor(), other.csUser().auth(), null), 404), "not-found");
    }

    // ---------------- create, submit, hand over, credit ----------------

    @Test
    void aNoteIsCreatedWithCreditAndGstReversalPerLineSubmittedAtLevelZeroHoldsTheUnitsAndTheReturnGoesToTheVendor() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode draft = create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 40, "80")));
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("number").isNull()).isTrue();
        assertThat(draft.get("lines").get(0).get("creditPaise").asLong()).as("40 x 7.00 x 80%").isEqualTo(22_400);
        assertThat(draft.get("lines").get(0).get("creditPercentChanged").asBoolean()).isFalse();
        assertThat(draft.get("creditTotalPaise").asLong()).isEqualTo(22_400);
        assertThat(draft.get("gstReversalPaise").asLong()).as("12 percent of the credit").isEqualTo(2_688);
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).as("a draft holds nothing").isZero();
        // a changed credit is marked
        JsonNode changed = create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 10, "75")));
        assertThat(changed.get("lines").get(0).get("creditPercentChanged").asBoolean()).isTrue();
        // submit at level 0: the number, status CREATED and the units held (ledger VENDOR_RETURN_HOLD)
        JsonNode created = submit(w, draft);
        assertThat(created.get("status").asText()).isEqualTo("CREATED");
        assertThat(created.get("number").asText()).isNotBlank();
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).isEqualTo(40);
        assertThat(stockOf(w, "DN1", "AVAILABLE")).isEqualTo(60);
        assertThat(ledgerQty(w, "VENDOR_RETURN_HOLD")).isEqualTo(40);
        assertThat(created.get("pickList")).isNotEmpty();
        assertLedgerEqualsBalances(w);
        // the held units are no longer eligible
        assertThat(rowOf(eligible(w, d.vendor()), "DN1").get("availableQty").asInt()).isEqualTo(60 - 0);
        // picked, then handed over: the held units leave
        JsonNode picked = step(w, w.csUser(), created, "picked", Map.of(), 200);
        assertThat(picked.get("status").asText()).isEqualTo("PICKED");
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).as("picking moves no stock").isEqualTo(40);
        JsonNode handed = step(w, w.csUser(), picked, "hand-over", handOver("Ravi Rep (fake)", null), 200);
        assertThat(handed.get("status").asText()).isEqualTo("HANDED_OVER");
        assertThat(handed.get("representativeName").asText()).isEqualTo("Ravi Rep (fake)");
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).isZero();
        assertThat(ledgerOut(w, "VENDOR_RETURN")).isEqualTo(40);
        assertThat(stockOf(w, "DN1", "AVAILABLE")).isEqualTo(60);
        assertLedgerEqualsBalances(w);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_DEBIT_NOTE_HANDED_OVER")).hasSize(1);
    }

    @Test
    void theCreditNoteIsAppliedToThePayablesOldestDueFirstAndAnyRemainderStaysUnapplied() throws Exception {
        Dw d = world(); // payable 1: invoice dated 40 days ago, due 10 days ago
        Sw w = d.w();
        JsonNode second = postedReceipt(w, d.vendor(), "INV-DN2", todayIst().toString(), 30, List.of(gline(w.productA(), "DN2", farMonth(), 10, "70.00", "12.00")));
        long first = d.grn().get("payable").get("amountPaise").asLong();
        long later = second.get("payable").get("amountPaise").asLong();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode handed = step(w, w.csUser(), step(w, w.csUser(), submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 40, "80")))), "picked", Map.of(), 200),
                "hand-over", handOver("Ravi Rep (fake)", null), 200);
        // more than the debit note asked: the credit goes to the oldest payable first, then the next
        JsonNode done = step(w, w.csUser(), handed, "credit-note", creditNote(first + 1_000), 200);
        assertThat(done.get("status").asText()).isEqualTo("CREDIT_NOTE_RECEIVED");
        assertThat(done.get("payableEffect").get("appliedPaise").asLong()).isEqualTo(first + 1_000);
        assertThat(done.get("payableEffect").get("unappliedCreditPaise").asLong()).isZero();
        assertThat(done.get("creditNote").get("differencePaise").asLong()).isNotZero();
        assertThat(count(w.clinic(), "select credited_paise from pharmacy.vendor_payable where invoice_no = 'INV-DN1'")).isEqualTo(first);
        assertThat(values(w.clinic(), "select status from pharmacy.vendor_payable where invoice_no = 'INV-DN1'")).containsExactly("SETTLED_BY_CREDIT");
        assertThat(count(w.clinic(), "select credited_paise from pharmacy.vendor_payable where invoice_no = 'INV-DN2'")).isEqualTo(1_000);
        assertThat(values(w.clinic(), "select status from pharmacy.vendor_payable where invoice_no = 'INV-DN2'")).containsExactly("OPEN");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.vendor_payable_entry where entry_type = 'CREDIT_NOTE'")).as("one entry per payable touched").isEqualTo(2);
        assertPayablesEqualEntries(w);
        // a second note: credit above everything still open leaves the rest unapplied (no negative payable)
        JsonNode row2 = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode handed2 = step(w, w.csUser(), step(w, w.csUser(), submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(row2, 60, "80")))), "picked", Map.of(), 200),
                "hand-over", handOver("Ravi Rep (fake)", null), 200);
        long open = payableOpen(w);
        JsonNode over = step(w, w.csUser(), handed2, "credit-note", creditNote(open + 5_000), 200);
        assertThat(over.get("payableEffect").get("appliedPaise").asLong()).isEqualTo(open);
        assertThat(over.get("payableEffect").get("unappliedCreditPaise").asLong()).isEqualTo(5_000);
        assertThat(payableOpen(w)).as("never negative").isZero();
        assertPayablesEqualEntries(w);
        assertLedgerEqualsBalances(w);
        assertThat(later).isPositive();
    }

    @Test
    void aCreditNoteBeforeHandOverForANoteWithGoodsOrAFutureDateOrAZeroAmountIsRefusedAndNothingChanges() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode created = submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 10, "80"))));
        assertType(step(w, w.csUser(), created, "credit-note", creditNote(1_000), 409), "doc-state");
        JsonNode handed = step(w, w.csUser(), step(w, w.csUser(), created, "picked", Map.of(), 200), "hand-over", handOver("Ravi Rep (fake)", null), 200);
        Map<String, Object> future = creditNote(1_000);
        future.put("creditNoteDate", todayIst().plusDays(1).toString());
        step(w, w.csUser(), handed, "credit-note", future, 400);
        step(w, w.csUser(), handed, "credit-note", creditNote(0), 400);
        Map<String, Object> noNumber = creditNote(1_000);
        noNumber.put("creditNoteNo", "");
        step(w, w.csUser(), handed, "credit-note", noNumber, 400);
        assertThat(getDn(w, idOf(handed)).get("status").asText()).isEqualTo("HANDED_OVER");
        JsonNode done = step(w, w.csUser(), handed, "credit-note", creditNote(1_000), 200);
        // the note is final: a second credit note is doc-state
        assertType(step(w, w.csUser(), done, "credit-note", creditNote(1_000), 409), "doc-state");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.debit_note_credit")).isOne();
    }

    // ---------------- cancel ----------------

    @Test
    void cancelReleasesTheHeldUnitsToTheStateTheyCameFromAndAHandedOverNoteCannotBeCancelled() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode created = submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 30, "80"))));
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).isEqualTo(30);
        assertThat(stockOf(w, "DN1", "AVAILABLE")).isEqualTo(70);
        Map<String, Object> noReason = new LinkedHashMap<>();
        noReason.put("reasonKey", null);
        step(w, w.csUser(), created, "cancel", noReason, 400);
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("reasonKey", "approval-reject.not-needed");
        JsonNode cancelled = step(w, w.csUser(), created, "cancel", reason, 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("number").asText()).as("keeps its number").isEqualTo(created.get("number").asText());
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).isZero();
        assertThat(stockOf(w, "DN1", "AVAILABLE")).as("back where it came from (not expired)").isEqualTo(100);
        assertThat(ledgerQty(w, "VENDOR_RETURN_RELEASE")).isEqualTo(30);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_DEBIT_NOTE_CANCELLED")).hasSize(1);
        assertLedgerEqualsBalances(w);
        assertType(step(w, w.csUser(), cancelled, "cancel", reason, 409), "doc-state");
        // handed over: cannot be cancelled
        JsonNode again = submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(rowOf(eligible(w, d.vendor()), "DN1"), 10, "80"))));
        JsonNode handed = step(w, w.csUser(), step(w, w.csUser(), again, "picked", Map.of(), 200), "hand-over", handOver("Ravi Rep (fake)", null), 200);
        assertType(step(w, w.csUser(), handed, "cancel", reason, 409), "doc-state");
        assertLedgerEqualsBalances(w);
    }

    // ---------------- approval ----------------

    @Test
    void withARuleTheUnitsAreHeldOnlyAfterTheOwnerApprovesAndARejectionHoldsNothing() throws Exception {
        Dw d = world();
        Sw w = d.w();
        ownerAlways(w, "VENDOR_RETURN");
        // VENDOR_RETURN has the STORE_APPROVER step in the Standard preset; ownerAlways makes it the owner's
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode waiting = submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 20, "80"))));
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.get("number").asText()).isNotBlank();
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).as("not held before approval").isZero();
        String request = waiting.get("approval").get("requestId").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200).get("version").asLong();
        assertThat(ok(HttpMethod.GET, PH + "/approvals/inbox", w.csUser().auth(), null, 200).get("items")).as("the raiser never sees it to decide").isEmpty();
        ok(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null), 200);
        assertThat(getDn(w, idOf(waiting)).get("status").asText()).isEqualTo("CREATED");
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).isEqualTo(20);
        // a second note rejected: nothing held
        JsonNode second = submit(w, create(w, w.csUser(), d.vendor(), List.of(stockLine(rowOf(eligible(w, d.vendor()), "DN1"), 10, "80"))));
        String request2 = second.get("approval").get("requestId").asText();
        long version2 = ok(HttpMethod.GET, PH + "/approvals/" + request2, w.clinic().owner(), null, 200).get("version").asLong();
        ok(HttpMethod.POST, decisionUrl(request2), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version2, "REJECTED", "approval-reject.not-needed"), 200);
        assertThat(getDn(w, idOf(second)).get("status").asText()).isEqualTo("REJECTED");
        assertThat(stockOf(w, "DN1", "PENDING_RETURN")).isEqualTo(20);
        assertLedgerEqualsBalances(w);
    }

    // ---------------- the short-supply-only note (migration 1740) ----------------

    private JsonNode shortNote(Dw d, String batch, int receivedBoxes, int billedBoxes) throws Exception {
        Sw w = d.w();
        JsonNode po = sentOrder(w, d.vendor(), List.of(poLine(w.productB(), billedBoxes, "20.00")));
        String line = po.get("lines").get(0).get("id").asText();
        JsonNode draft = draftReceipt(w, idOf(po), d.vendor(), "INV-" + batch, todayIst().toString(), 30,
                List.of(gline(w.productB(), batch, farMonth(), receivedBoxes, billedBoxes, 0, "20.00", "3.00", line, "BALANCE_TO_COME", false)));
        JsonNode posted = post(w, w.csUser(), draft, null, 200);
        assertThat(posted.get("debitNoteDraft").isNull()).isFalse();
        return getDn(w, posted.get("debitNoteDraft").get("dnId").asText());
    }

    @Test
    void aNoteWithShortSupplyLinesOnlyMovesFromCreatedToCreditNoteReceivedWithoutAHandOverAndHoldsNothing() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode draft = shortNote(d, "SH1", 6, 10);
        assertThat(draft.get("lines").get(0).get("type").asText()).isEqualTo("SHORT_SUPPLY");
        assertThat(draft.get("creditTotalPaise").asLong()).as("4 boxes x 10 x 2.00").isEqualTo(8_000);
        JsonNode created = submit(w, draft);
        assertThat(created.get("status").asText()).isEqualTo("CREATED");
        assertThat(stockOf(w, "SH1", "PENDING_RETURN")).as("a short supply has no stock line").isZero();
        assertThat(ledgerQty(w, "VENDOR_RETURN_HOLD")).isZero();
        JsonNode done = step(w, w.csUser(), created, "credit-note", creditNote(8_000), 200);
        assertThat(done.get("status").asText()).isEqualTo("CREDIT_NOTE_RECEIVED");
        assertThat(done.get("representativeName").isNull()).as("no hand-over data is invented").isTrue();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.debit_note where status = 'CREDIT_NOTE_RECEIVED' and handed_over_at is null")).isOne();
        assertPayablesEqualEntries(w);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aShortSupplyOnlyNoteMovesFromPickedToCreditNoteReceivedToo() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode picked = step(w, w.csUser(), submit(w, shortNote(d, "SH2", 6, 10)), "picked", Map.of(), 200);
        assertThat(picked.get("status").asText()).isEqualTo("PICKED");
        assertThat(step(w, w.csUser(), picked, "credit-note", creditNote(8_000), 200).get("status").asText()).isEqualTo("CREDIT_NOTE_RECEIVED");
    }

    @Test
    void aNoteWithGoodsAndAShortSupplyLineStillNeedsTheHandOverFirst() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode shortDraft = shortNote(d, "SH3", 6, 10);
        String receiptLine = shortDraft.get("lines").get(0).get("goodsReceiptLineId").asText();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode mixed = create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 5, "80"), shortLine(receiptLine, 1, "100")));
        JsonNode created = submit(w, mixed);
        assertType(step(w, w.csUser(), created, "credit-note", creditNote(5_000), 409), "doc-state");
    }

    // ---------------- validation, roles, isolation ----------------

    @Test
    void theCreateRulesAreCheckedAndEveryBadBodyIs400() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        List<Object[]> bad = new ArrayList<>();
        bad.add(new Object[] {"no lines", noteBody(UUID.randomUUID(), d.vendor(), List.of())});
        bad.add(new Object[] {"more than available", noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 101, "80")))});
        bad.add(new Object[] {"qty 0", noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 0, "80")))});
        bad.add(new Object[] {"credit 101", noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 1, "101")))});
        bad.add(new Object[] {"credit -1", noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 1, "-1")))});
        bad.add(new Object[] {"same batch and state twice", noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 1, "80"), stockLine(row, 2, "80")))});
        bad.add(new Object[] {"unknown vendor", noteBody(UUID.randomUUID(), UUID.randomUUID().toString(), List.of(stockLine(row, 1, "80")))});
        bad.add(new Object[] {"a batch that is not eligible", noteBody(UUID.randomUUID(), d.vendor(), List.of(notEligible(w, row)))});
        List<String> notRefused = new ArrayList<>();
        for (Object[] b : bad) {
            @SuppressWarnings("unchecked")
            int status = call(HttpMethod.POST, dns(w.centralId()), w.csUser().auth(), js((Map<String, Object>) b[1])).andReturn().getResponse().getStatus();
            if (status != 400) {
                notRefused.add(b[0] + " answered " + status);
            }
        }
        assertThat(notRefused).as("every bad body is 400").isEmpty();
        JsonNode over = problem(call(HttpMethod.POST, dns(w.centralId()), w.csUser().auth(), js(noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 101, "80"))))), 400);
        assertType(over, "validation");
        assertThat(over.get("fieldErrors").toString()).contains("Enter 1 to 100");
        assertType(problem(call(HttpMethod.POST, dns(w.centralId()), w.csUser().auth(), "not json"), 400), "malformed-request");
        assertThat(pharmacyRows(w.clinic(), "debit_note")).isZero();
        // the boundary: all 100 units at 100 percent
        ok(HttpMethod.POST, dns(w.centralId()), w.csUser().auth(), js(noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 100, "100")))), 201);
    }

    /** A line for a batch of the clinic's opening stock, which is never offered to a vendor return. */
    private Map<String, Object> notEligible(Sw w, JsonNode row) throws Exception {
        Map<String, Object> m = stockLine(row, 1, "80");
        JsonNode page = ok(HttpMethod.GET, STORES + "/" + w.centralId() + "/stock/batches?size=100", w.clinic().owner(), null, 200);
        for (JsonNode item : page.get("items")) {
            if ("TESTB001".equalsIgnoreCase(item.get("batch").get("batchNo").asText())) {
                m.put("batchId", item.get("batch").get("batchId").asText());
            }
        }
        return m;
    }

    @Test
    void theSameClientKeyReplaysAndAnotherPersonWithTheKeyIs409AndTheNoteIsNotMadeTwice() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        Map<String, Object> body = noteBody(UUID.randomUUID(), d.vendor(), List.of(stockLine(row, 5, "80")));
        JsonNode first = ok(HttpMethod.POST, dns(w.centralId()), w.csUser().auth(), js(body), 201);
        JsonNode replay = ok(HttpMethod.POST, dns(w.centralId()), w.csUser().auth(), js(body), 200);
        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertType(problem(call(HttpMethod.POST, dns(w.centralId()), w.clinic().owner(), js(body)), 409), "client-key-reused");
        assertThat(pharmacyRows(w.clinic(), "debit_note")).isOne();
    }

    @Test
    void theNotePathsAreRefusedToAPharmacyStoreUserAndToAnOwnerAtAnotherStoreAndAnotherClinicSeesNothing() throws Exception {
        Dw d = world();
        Sw w = d.w();
        JsonNode row = rowOf(eligible(w, d.vendor()), "DN1");
        JsonNode draft = create(w, w.csUser(), d.vendor(), List.of(stockLine(row, 5, "80")));
        String url = dns(w.centralId()) + "/" + idOf(draft);
        assertType(problem(call(HttpMethod.GET, url, w.keeper().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, dns(w.pharmacyId()), w.clinic().owner(), null), 409), "doc-state");
        assertType(problem(call(HttpMethod.GET, dns(w.pharmacyId()), w.keeper().auth(), null), 403), "store-permission-denied");
        Sw other = stockWorld();
        assertType(problem(call(HttpMethod.GET, url, other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, dns(other.centralId()) + "/" + idOf(draft), other.csUser().auth(), null), 404), "not-found");
        assertThat(ok(HttpMethod.GET, dns(other.centralId()), other.csUser().auth(), null, 200).get("items")).isEmpty();
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, dns(w.centralId()), w.clinic().owner(), null), 403), "module-off");
    }

    // ---------------- Schedule X ----------------

    @Test
    void aScheduleXHandOverNeedsAPharmacistAndASecondSignerAndANonPharmacistIs403() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "SxRet Vendor (fake)");
        Map<String, Object> body = ownBody("Test controlled return item (fake)");
        body.put("schedule", "X");
        String x = idOf(createOwn(w.clinic(), body));
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        JsonNode draft = draftReceipt(w, vendor, "INV-SXR", List.of(gline(x, "SXR1", thisMonth(), 5, "50.00", "6.00")));
        post(w, w.csUser(), draft, signer(w.second(), pin), 200);
        JsonNode row = rowOf(eligible(w, vendor), "SXR1");
        JsonNode created = submit(w, create(w, w.csUser(), vendor, List.of(stockLine(row, 20, "80"))));
        JsonNode picked = step(w, w.csUser(), created, "picked", Map.of(), 200);
        assertThat(picked.get("lines").get(0).get("scheduleX").asBoolean()).isTrue();
        // a non-pharmacist Central Store user
        Pn clerk = crew(w.clinic(), "STORE_STAFF", "Clara Clerk");
        mapStaff(w.clinic(), w.centralId(), clerk, "CENTRAL_STORE_USER");
        assertType(step(w, clerk, picked, "hand-over", handOver("Ravi Rep (fake)", signer(w.second(), pin)), 403), "schedule-x-pharmacist");
        // a pharmacist without a signer: 400 on secondSigner; with a wrong PIN: 403 wrong-pin
        assertFieldError(step(w, w.csUser(), picked, "hand-over", handOver("Ravi Rep (fake)", null), 400), "secondSigner");
        assertType(step(w, w.csUser(), picked, "hand-over", handOver("Ravi Rep (fake)", signer(w.second(), otherPin(w.second(), pin))), 403), "wrong-pin");
        assertThat(getDn(w, idOf(picked)).get("status").asText()).isEqualTo("PICKED");
        assertThat(stockOf(w, "SXR1", "PENDING_RETURN")).as("nothing left the store").isEqualTo(20 * 0 + 20);
        JsonNode handed = step(w, w.csUser(), picked, "hand-over", handOver("Ravi Rep (fake)", signer(w.second(), pin)), 200);
        assertThat(handed.get("status").asText()).isEqualTo("HANDED_OVER");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'VENDOR_RETURN' and second_signer_membership_id = ?::uuid",
                w.second().membershipId().toString())).isOne();
        assertLedgerEqualsBalances(w);
    }
}
