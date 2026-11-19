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
 * SAH-79-7 (pharmacy.yaml 0.3.4, WP-E receiving stock): posting a receipt against a purchase order (batches, balances, ledger
 * lines, the payable and its INVOICE entry, the order's received quantities, the debit-note draft for short and rate-difference
 * lines), the rate-rise approval, batch-mrp-mismatch, duplicate invoice, a blocked vendor, Schedule X (a pharmacist and a
 * second signer), two posts of one receipt, a switch-off against a post, discard and reopen, two clinics. Product A is a box of
 * 10 vials (GST 12 percent); product B is an own box of 10 pieces (GST 12 percent). Test plan: docs/test-plans/SAH-79.md
 * (T79-7-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacyWpEReceiptApiIT extends Sah79BuySupport {

    // ---------------- a receipt against a purchase order ----------------

    @Test
    void aPostedReceiptAgainstAnOrderBooksBatchesBalancesLedgerThePayableAndTheOrderAndPreparesTheDebitNoteForTheShortLine() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Rcpt Vendor (fake)");
        JsonNode po = sentOrder(w, vendor, List.of(poLine(w.productA(), 10, "70.00"), poLine(w.productB(), 5, "20.00")));
        String lineA = po.get("lines").get(0).get("id").asText();
        String lineB = po.get("lines").get(1).get("id").asText();
        String today = todayIst().toString();
        JsonNode draft = draftReceipt(w, idOf(po), vendor, "INV-1001", today, 30, List.of(
                gline(w.productA(), "GA1", farMonth(), 8, 10, 2, "70.00", "12.00", lineA, "BALANCE_TO_COME", false),
                gline(w.productB(), "GB1", farMonth(), 5, 5, 0, "20.00", "3.00", lineB, null, false)));
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("number").isNull()).isTrue();
        assertThat(stockOf(w, "GA1", "AVAILABLE")).as("a draft moves no stock").isZero();
        JsonNode posted = post(w, w.csUser(), draft, null, 200);
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(posted.get("outcome").asText()).isEqualTo("POSTED");
        assertThat(posted.get("number").asText()).isNotBlank();
        // stock: (received + free) x conversion issue units
        assertThat(stockOf(w, "GA1", "AVAILABLE")).as("(8 + 2) x 10").isEqualTo(100);
        assertThat(stockOf(w, "GB1", "AVAILABLE")).isEqualTo(50);
        assertThat(ledgerQty(w, "GRN")).isEqualTo(150);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'GRN' and doc_no = ?", posted.get("number").asText()))
                .as("the ledger reference is the receipt number").isEqualTo(2);
        // the payable and its INVOICE entry
        JsonNode payable = posted.get("payable");
        assertThat(payable.get("amountPaise").asLong()).isEqualTo(posted.get("invoiceTotalPaise").asLong());
        assertThat(payable.get("dueDate").asText()).as("invoice date + 30 days").isEqualTo(todayIst().plusDays(30).toString());
        assertThat(payable.get("status").asText()).isEqualTo("OPEN");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.vendor_payable_entry where entry_type = 'INVOICE'")).isOne();
        assertPayablesEqualEntries(w);
        // the order: received quantities and status (A is short by 2 with a balance to come)
        JsonNode order = getOrder(w, idOf(po));
        assertThat(order.get("status").asText()).isEqualTo("PARTLY_RECEIVED");
        assertThat(order.get("lines").get(0).get("receivedQty").asInt()).isEqualTo(10);
        assertThat(order.get("lines").get(1).get("receivedQty").asInt()).isEqualTo(5);
        // the prepared debit note for the line billed above what was received (here billed 10, received 8)
        assertThat(posted.get("debitNoteDraft").isNull()).isFalse();
        assertThat(posted.get("debitNoteDraft").get("lineCount").asInt()).isOne();
        assertThat(posted.get("debitNoteDraft").get("amountPaise").asLong()).as("2 boxes x 10 x 7.00").isEqualTo(14_000);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_GRN_POSTED")).hasSize(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aReceiptOfTheWholeOrderClosesItAndAShortLineWithoutAShortActionIs400() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Whole Vendor (fake)");
        JsonNode po = sentOrder(w, vendor, List.of(poLine(w.productA(), 10, "70.00")));
        String line = po.get("lines").get(0).get("id").asText();
        JsonNode shortDraft = draftReceipt(w, idOf(po), vendor, "INV-2001", todayIst().toString(), 30,
                List.of(gline(w.productA(), "WS1", farMonth(), 6, 6, 0, "70.00", "12.00", line, null, false)));
        problem(call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(shortDraft) + "/post", w.csUser().auth(), js(postBody(shortDraft, null))), 400);
        assertThat(stockOf(w, "WS1", "AVAILABLE")).isZero();
        JsonNode whole = draftReceipt(w, idOf(po), vendor, "INV-2002", todayIst().toString(), 30,
                List.of(gline(w.productA(), "WS2", farMonth(), 10, 10, 0, "70.00", "12.00", line, null, false)));
        JsonNode posted = post(w, w.csUser(), whole, null, 200);
        assertThat(posted.get("debitNoteDraft").isNull()).as("nothing short").isTrue();
        assertThat(getOrder(w, idOf(po)).get("status").asText()).isEqualTo("RECEIVED");
    }

    @Test
    void theShortSupplyCreditIsWorkedInPurchaseUnits1000StripsOf15AtOneHundredRupeesIsTenMillionPaise() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Strip Vendor (fake)");
        Map<String, Object> strip = ownBody("Test strip product (fake)");
        strip.put("purchaseUnit", "Strip");
        strip.put("issueUnit", "tablet");
        strip.put("conversion", 15);
        String product = idOf(createOwn(w.clinic(), strip));
        // billed 1001 strips, received 1: 1,000 strips short
        JsonNode draft = draftReceipt(w, null, vendor, "INV-3001", todayIst().toString(), 30,
                List.of(gline(product, "ST1", farMonth(), 1, 1001, 0, "100.00", "8.00", null, null, false)));
        JsonNode posted = post(w, w.csUser(), draft, null, 200);
        assertThat(posted.get("debitNoteDraft").get("amountPaise").asLong()).as("1,000 x 100.00, not 15,000 x 6.6667").isEqualTo(10_000_000L);
    }

    @Test
    void aTickedRateDifferenceIsClaimedOnTheDebitNoteDraftAtTheRiseTimesTheUnits() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Claim Vendor (fake)");
        JsonNode po = sentOrder(w, vendor, List.of(poLine(w.productA(), 10, "70.00")));
        String line = po.get("lines").get(0).get("id").asText();
        JsonNode draft = draftReceipt(w, idOf(po), vendor, "INV-4001", todayIst().toString(), 30,
                List.of(gline(w.productA(), "CL1", farMonth(), 10, 10, 0, "74.30", "12.00", line, null, true)));
        JsonNode posted = post(w, w.csUser(), draft, null, 200);
        assertThat(posted.get("debitNoteDraft").get("lineCount").asInt()).isOne();
        assertThat(posted.get("debitNoteDraft").get("amountPaise").asLong()).as("100 units x 0.43").isEqualTo(4_300);
    }

    // ---------------- the rate-rise approval ----------------

    @Test
    void aRateRiseWaitsForTheOwnerAndTheCentralStoreUserPostsAfterApprovalAndOnlyOnce() throws Exception {
        Sw w = buyWorld();
        ownerAlways(w, "GRN_RATE_RISE");
        String vendor = buyVendor(w, "Rise Vendor (fake)");
        JsonNode po = sentOrder(w, vendor, List.of(poLine(w.productA(), 10, "70.00")));
        String line = po.get("lines").get(0).get("id").asText();
        JsonNode draft = draftReceipt(w, idOf(po), vendor, "INV-5001", todayIst().toString(), 30,
                List.of(gline(w.productA(), "RR1", farMonth(), 10, 10, 0, "74.30", "12.00", line, null, false)));
        assertThat(getGrn(w, w.csUser(), idOf(draft)).get("needsApproval").asBoolean()).isTrue();
        assertThat(draft.get("lines").get(0).get("ratePercent").asText()).isEqualTo("+6.1%");
        JsonNode waiting = post(w, w.csUser(), draft, null, 200);
        assertThat(waiting.get("outcome").asText()).isEqualTo("SENT_FOR_APPROVAL");
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.get("number").isNull()).as("numbered at posting").isTrue();
        assertThat(stockOf(w, "RR1", "AVAILABLE")).isZero();
        assertThat(ledgerQty(w, "GRN")).isZero();
        String request = waiting.get("approval").get("requestId").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200).get("version").asLong();
        JsonNode inbox = ok(HttpMethod.GET, PH + "/approvals/inbox", w.clinic().owner(), null, 200);
        assertThat(inbox.toString()).contains(request);
        JsonNode done = ok(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null), 200);
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        JsonNode approved = getGrn(w, w.csUser(), idOf(draft));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(stockOf(w, "RR1", "AVAILABLE")).as("approval alone posts nothing").isZero();
        JsonNode posted = post(w, w.csUser(), approved, null, 200);
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(stockOf(w, "RR1", "AVAILABLE")).isEqualTo(100);
        // the same receipt again: doc-state, stock unchanged
        assertType(post(w, w.csUser(), posted, null, 409), "doc-state");
        assertThat(stockOf(w, "RR1", "AVAILABLE")).isEqualTo(100);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.vendor_payable")).isOne();
    }

    @Test
    void anApprovedReceiptCanBeDiscardedDirectlyAndTheInvoiceNumberIsFreeAgain() throws Exception {
        Sw w = buyWorld();
        ownerAlways(w, "GRN_RATE_RISE");
        String vendor = buyVendor(w, "Disc Vendor (fake)");
        JsonNode po = sentOrder(w, vendor, List.of(poLine(w.productA(), 10, "70.00")));
        String line = po.get("lines").get(0).get("id").asText();
        List<Map<String, Object>> lines = List.of(gline(w.productA(), "DS1", farMonth(), 10, 10, 0, "74.30", "12.00", line, null, false));
        JsonNode draft = draftReceipt(w, idOf(po), vendor, "INV-6001", todayIst().toString(), 30, lines);
        JsonNode waiting = post(w, w.csUser(), draft, null, 200);
        String request = waiting.get("approval").get("requestId").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200).get("version").asLong();
        ok(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null), 200);
        JsonNode approved = getGrn(w, w.csUser(), idOf(draft));
        // a waiting receipt cannot be discarded; an approved one can (contract: DRAFT or APPROVED)
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", approved.get("version").asLong());
        JsonNode gone = ok(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(approved) + "/discard", w.csUser().auth(), js(v), 200);
        assertThat(gone.get("status").asText()).isEqualTo("DISCARDED");
        ok(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(), js(createBody(UUID.randomUUID(), idOf(po), vendor, "INV-6001", todayIst().toString(), 30, 0, lines)), 201);
    }

    @Test
    void aWaitingReceiptCannotBeDiscardedAndAnApprovedOneReopensToDraft() throws Exception {
        Sw w = buyWorld();
        ownerAlways(w, "GRN_RATE_RISE");
        String vendor = buyVendor(w, "Reopen Vendor (fake)");
        JsonNode po = sentOrder(w, vendor, List.of(poLine(w.productA(), 10, "70.00")));
        String line = po.get("lines").get(0).get("id").asText();
        JsonNode draft = draftReceipt(w, idOf(po), vendor, "INV-6101", todayIst().toString(), 30,
                List.of(gline(w.productA(), "RO1", farMonth(), 10, 10, 0, "74.30", "12.00", line, null, false)));
        JsonNode waiting = post(w, w.csUser(), draft, null, 200);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("version", waiting.get("version").asLong());
        assertType(problem(call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(waiting) + "/discard", w.csUser().auth(), js(v)), 409), "doc-state");
        assertType(problem(call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(waiting) + "/reopen", w.csUser().auth(), js(v)), 409), "doc-state");
        String request = waiting.get("approval").get("requestId").asText();
        long version = ok(HttpMethod.GET, PH + "/approvals/" + request, w.clinic().owner(), null, 200).get("version").asLong();
        ok(HttpMethod.POST, decisionUrl(request), w.clinic().owner(), decisionBody(UUID.randomUUID(), 1, version, "APPROVED", null), 200);
        JsonNode approved = getGrn(w, w.csUser(), idOf(draft));
        v.put("version", approved.get("version").asLong());
        JsonNode reopened = ok(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(approved) + "/reopen", w.csUser().auth(), js(v), 200);
        assertThat(reopened.get("status").asText()).isEqualTo("DRAFT");
        assertThat(reopened.get("needsApproval").asBoolean()).as("the spent approval no longer covers it").isTrue();
    }

    // ---------------- checks at post ----------------

    @Test
    void theSameBatchWithADifferentMrpIs409BatchMrpMismatchAndNothingIsPosted() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Mrp Vendor (fake)");
        post(w, w.csUser(), draftReceipt(w, vendor, "INV-7001", List.of(gline(w.productA(), "MM1", farMonth(), 5, "70.00", "12.00"))), null, 200);
        JsonNode second = draftReceipt(w, vendor, "INV-7002", List.of(gline(w.productA(), "MM1", farMonth(), 5, "70.00", "13.00")));
        JsonNode p = post(w, w.csUser(), second, null, 409);
        assertType(p, "batch-mrp-mismatch");
        assertThat(p.get("detail").asText()).isEqualTo("Same batch with a different MRP. Check the batch number.");
        assertThat(stockOf(w, "MM1", "AVAILABLE")).isEqualTo(50);
        assertThat(getGrn(w, w.csUser(), idOf(second)).get("status").asText()).isEqualTo("DRAFT");
        assertThat(count(w.clinic(), "select count(*) from pharmacy.vendor_payable")).isOne();
        // the same batch with the same MRP tops up
        JsonNode topUp = draftReceipt(w, vendor, "INV-7003", List.of(gline(w.productA(), "MM1", farMonth(), 3, "70.00", "12.00")));
        post(w, w.csUser(), topUp, null, 200);
        assertThat(stockOf(w, "MM1", "AVAILABLE")).isEqualTo(80);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.batch where batch_no = 'MM1'")).as("one batch row").isOne();
    }

    @Test
    void aDuplicateInvoiceOfTheSameVendorIs409IgnoringCaseAndSpacesButAnotherVendorMayUseIt() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Dup Vendor (fake)");
        String other = buyVendor(w, "Dup Other (fake)");
        List<Map<String, Object>> lines = List.of(gline(w.productA(), "DU1", farMonth(), 2, "70.00", "12.00"));
        draftReceipt(w, vendor, "INV-8001", lines);
        JsonNode p = problem(call(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), null, vendor, "  inv-8001 ", todayIst().toString(), 30, 0, lines))), 409);
        assertType(p, "duplicate-invoice");
        ok(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(), js(createBody(UUID.randomUUID(), null, other, "INV-8001", todayIst().toString(), 30, 0, lines)), 201);
        assertThat(pharmacyRows(w.clinic(), "goods_receipt")).isEqualTo(2);
    }

    @Test
    void aDirectReceiptFromABlockedVendorIs409VendorBlockedAndNothingIsPosted() throws Exception {
        Sw w = buyWorld();
        Map<String, Object> blocked = vendorBody("Blocked Vendor (fake)");
        blocked.put("suppliesToStoreIds", List.of(w.centralId()));
        blocked.put("licenceValidTill", todayIst().minusDays(3).toString());
        String vendor = idOf(createVendor(w.clinic(), blocked));
        List<Map<String, Object>> lines = List.of(gline(w.productA(), "BL1", farMonth(), 2, "70.00", "12.00"));
        JsonNode p = problem(call(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), null, vendor, "INV-9001", todayIst().toString(), 30, 0, lines))), 409);
        assertType(p, "vendor-blocked");
        assertThat(pharmacyRows(w.clinic(), "goods_receipt")).isZero();
        assertThat(stockOf(w, "BL1", "AVAILABLE")).isZero();
    }

    @Test
    void theInvoiceTotalMustMatchTheLinesWithin100PaiseElseTheCodeIsInvoiceTotalMismatch() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Total Vendor (fake)");
        List<Map<String, Object>> lines = List.of(gline(w.productA(), "TM1", farMonth(), 10, "70.00", "12.00"));
        JsonNode first = ok(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), null, vendor, "INV-9101", todayIst().toString(), 30, 0, lines)), 201);
        long computed = first.get("computedTotalPaise").asLong();
        JsonNode far = ok(HttpMethod.PUT, grns(w.centralId()) + "/" + idOf(first), w.csUser().auth(), js(updateBody(first, computed + 101, lines)), 200);
        JsonNode mismatch = problem(call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(far) + "/post", w.csUser().auth(), js(postBody(far, null))), 400);
        assertType(mismatch, "invoice-total-mismatch");
        assertThat(mismatch.get("detail").asText()).isEqualTo("Invoice total does not match the lines.");
        JsonNode near = ok(HttpMethod.PUT, grns(w.centralId()) + "/" + idOf(far), w.csUser().auth(), js(updateBody(far, computed + 100, lines)), 200);
        assertThat(post(w, w.csUser(), near, null, 200).get("status").asText()).as("100 paise off passes").isEqualTo("POSTED");
    }

    // ---------------- Schedule X ----------------

    private String scheduleXProduct(Sw w) throws Exception {
        Map<String, Object> body = ownBody("Test controlled receipt item (fake)");
        body.put("schedule", "X");
        return idOf(createOwn(w.clinic(), body));
    }

    @Test
    void aScheduleXLineNeedsAPharmacistAndASecondSignerAndANonPharmacistIs403() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Sx Vendor (fake)");
        String x = scheduleXProduct(w);
        JsonNode draft = draftReceipt(w, vendor, "INV-1201", List.of(gline(x, "SX1", farMonth(), 4, "50.00", "6.00")));
        assertThat(draft.get("lines").get(0).get("scheduleX").asBoolean()).isTrue();
        // an owner is not a pharmacist
        assertType(post(w, ownerPn(w.clinic()), draft, null, 403), "schedule-x-pharmacist");
        // a missing signer is 400 on secondSigner
        JsonNode missing = problem(call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(draft) + "/post", w.csUser().auth(), js(postBody(draft, null))), 400);
        assertFieldError(missing, "secondSigner");
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        // the wrong PIN is refused and nothing is posted
        assertType(post(w, w.csUser(), draft, signer(w.second(), otherPin(w.second(), pin)), 403), "wrong-pin");
        assertThat(stockOf(w, "SX1", "AVAILABLE")).isZero();
        JsonNode posted = post(w, w.csUser(), draft, signer(w.second(), pin), 200);
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(posted.toString()).as("the PIN is never echoed").doesNotContain("\"" + pin + "\"");
        assertThat(stockOf(w, "SX1", "AVAILABLE")).isEqualTo(40);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'GRN' and second_signer_membership_id = ?::uuid",
                w.second().membershipId().toString())).isOne();
    }

    @Test
    void aReceiptWithoutScheduleXLinesNeedsNoSignerAndAnExtraSignerMakesNoPinAttempt() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "NoSx Vendor (fake)");
        String pin = goodPin(w.second());
        setPin(w.second(), pin);
        JsonNode draft = draftReceipt(w, vendor, "INV-1301", List.of(gline(w.productA(), "NS1", farMonth(), 4, "70.00", "12.00")));
        // a wrong PIN in the body of a receipt with no Schedule X line: the colleague's tries stay the same (M-2)
        JsonNode posted = post(w, w.csUser(), draft, signer(w.second(), otherPin(w.second(), pin)), 200);
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(pinRow(w.clinic(), w.second().membershipId()).failed()).isZero();
    }

    // ---------------- guards, roles, isolation ----------------

    @Test
    void thePostIsRefusedToAPharmacyStoreUserAndToAPersonNotMappedAndAnotherClinicSeesNothing() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Role Vendor (fake)");
        JsonNode draft = draftReceipt(w, vendor, "INV-1401", List.of(gline(w.productA(), "RL1", farMonth(), 4, "70.00", "12.00")));
        String url = grns(w.centralId()) + "/" + idOf(draft);
        // the pharmacy store user is not mapped at the Central Store
        assertType(problem(call(HttpMethod.GET, url, w.keeper().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, url + "/post", w.keeper().auth(), js(postBody(draft, null))), 404), "not-found");
        problem(call(HttpMethod.GET, url, crew(w.clinic(), "FRONT_DESK").auth(), null), 403);
        // a receipt is made at the Central Store only
        assertType(problem(call(HttpMethod.GET, grns(w.pharmacyId()), w.clinic().owner(), null), 409), "doc-state");
        Sw other = buyWorld();
        assertType(problem(call(HttpMethod.GET, url, other.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, grns(other.centralId()) + "/" + idOf(draft), other.csUser().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, url + "/post", other.clinic().owner(), js(postBody(draft, null))), 404), "not-found");
        assertThat(stockOf(w, "RL1", "AVAILABLE")).isZero();
    }

    @Test
    void theCreateRulesAreChecked400WithAProblemBodyAndTheModuleSwitchedOffAnswers403() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Bad Vendor (fake)");
        Map<String, Object> goodLine = gline(w.productA(), "BD1", farMonth(), 2, "70.00", "12.00");
        List<Object[]> bad = new ArrayList<>();
        bad.add(new Object[] {"invoice date in the future", createBody(UUID.randomUUID(), null, vendor, "INV-B1", todayIst().plusDays(1).toString(), 30, 0, List.of(goodLine))});
        bad.add(new Object[] {"credit days 366", createBody(UUID.randomUUID(), null, vendor, "INV-B2", todayIst().toString(), 366, 0, List.of(goodLine))});
        bad.add(new Object[] {"no vendor on a direct receipt", createBody(UUID.randomUUID(), null, null, "INV-B3", todayIst().toString(), 30, 0, List.of(goodLine))});
        bad.add(new Object[] {"invoice number of 31 characters", createBody(UUID.randomUUID(), null, vendor, "X".repeat(31), todayIst().toString(), 30, 0, List.of(goodLine))});
        bad.add(new Object[] {"expiry in the past", createBody(UUID.randomUUID(), null, vendor, "INV-B5", todayIst().toString(), 30, 0,
                List.of(gline(w.productA(), "BD2", todayIst().minusMonths(2).toString().substring(0, 7), 2, "70.00", "12.00")))});
        bad.add(new Object[] {"batch of 21 characters", createBody(UUID.randomUUID(), null, vendor, "INV-B6", todayIst().toString(), 30, 0,
                List.of(gline(w.productA(), "B".repeat(21), farMonth(), 2, "70.00", "12.00")))});
        bad.add(new Object[] {"qty 0 and free 0", createBody(UUID.randomUUID(), null, vendor, "INV-B7", todayIst().toString(), 30, 0,
                List.of(gline(w.productA(), "BD3", farMonth(), 0, 0, 0, "70.00", "12.00", null, null, false)))});
        bad.add(new Object[] {"mrp 0", createBody(UUID.randomUUID(), null, vendor, "INV-B8", todayIst().toString(), 30, 0,
                List.of(gline(w.productA(), "BD4", farMonth(), 2, "70.00", "0")))});
        bad.add(new Object[] {"unknown product", createBody(UUID.randomUUID(), null, vendor, "INV-B9", todayIst().toString(), 30, 0,
                List.of(gline(UUID.randomUUID().toString(), "BD5", farMonth(), 2, "70.00", "12.00")))});
        List<String> notRefused = new ArrayList<>();
        for (Object[] b : bad) {
            @SuppressWarnings("unchecked")
            int status = call(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(), js((Map<String, Object>) b[1])).andReturn().getResponse().getStatus();
            if (status != 400) {
                notRefused.add(b[0] + " answered " + status);
            }
        }
        assertThat(notRefused).as("every bad body is 400").isEmpty();
        assertType(problem(call(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(), "{}"), 400), "validation");
        assertType(problem(call(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(), "not json"), 400), "malformed-request");
        assertThat(pharmacyRows(w.clinic(), "goods_receipt")).isZero();
        pharmacyOn(w.clinic(), false);
        assertType(problem(call(HttpMethod.GET, grns(w.centralId()), w.clinic().owner(), null), 403), "module-off");
    }

    @Test
    void aSwitchOffAgainstAPostEndsWithOneOutcomeAndTheLedgerEqualsTheBalances() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Race Vendor (fake)");
        JsonNode draft = draftReceipt(w, vendor, "INV-1501", List.of(gline(w.productA(), "RC1", farMonth(), 4, "70.00", "12.00")));
        // the pharmacy store is switched off at the same time as the Central Store posts (the receipt is the Central Store's)
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> posting = pool.submit(() -> call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(draft) + "/post", w.csUser().auth(),
                    js(postBody(draft, null))).andReturn().getResponse().getStatus());
            java.util.concurrent.Future<Integer> off = pool.submit(() -> call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null)
                    .andReturn().getResponse().getStatus());
            assertThat(posting.get()).as("the post is unaffected by an unrelated store").isEqualTo(200);
            assertThat(off.get()).isIn(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stockOf(w, "RC1", "AVAILABLE")).isEqualTo(40);
        assertLedgerEqualsBalances(w);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.store where status = 'INACTIVE' and id in (select store_id from pharmacy.stock_balance where qty > 0)"))
                .as("no inactive store holds stock").isZero();
    }

    @Test
    void twoPostsOfOneReceiptAtTheSameTimePostOnceAndTheOtherIsDocStateOrAReplay() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Twin Vendor (fake)");
        JsonNode draft = draftReceipt(w, vendor, "INV-1601", List.of(gline(w.productA(), "TW1", farMonth(), 4, "70.00", "12.00")));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> call(HttpMethod.POST, grns(w.centralId()) + "/" + idOf(draft) + "/post", w.csUser().auth(),
                        js(postBody(draft, null))).andReturn().getResponse().getStatus()));
            }
            List<Integer> codes = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : results) {
                codes.add(f.get());
            }
            assertThat(codes).as("one posts, the other is refused").containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stockOf(w, "TW1", "AVAILABLE")).as("posted once").isEqualTo(40);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.vendor_payable")).isOne();
        assertThat(ledgerQty(w, "GRN")).isEqualTo(40);
        assertLedgerEqualsBalances(w);
    }

    // ---------------- the write limit (I21) ----------------

    @Test
    void theReceiptAndOrderWritePathsAcceptAbout66KbAndRefuse130KbAs413() throws Exception {
        Sw w = buyWorld();
        String vendor = buyVendor(w, "Big Vendor (fake)");
        // a 66 KB body: valid JSON whose note-like padding sits in a long invoice number (refused 400 by the rules, not 413)
        String pad66 = "X".repeat(66 * 1024);
        String pad130 = "X".repeat(130 * 1024);
        for (String url : new String[] {grns(w.centralId()), pos(w.centralId())}) {
            Map<String, Object> small = new LinkedHashMap<>();
            small.put("clientKey", UUID.randomUUID().toString());
            small.put("vendorId", vendor);
            small.put("invoiceNo", pad66);
            small.put("lines", List.of());
            int accepted = call(HttpMethod.POST, url, w.csUser().auth(), js(small)).andReturn().getResponse().getStatus();
            assertThat(accepted).as("66 KB is read (rules answer 400, never 413) on " + url).isEqualTo(400);
            small.put("invoiceNo", pad130);
            int refused = call(HttpMethod.POST, url, w.csUser().auth(), js(small)).andReturn().getResponse().getStatus();
            assertThat(refused).as("130 KB is refused on " + url).isEqualTo(413);
        }
    }
}
