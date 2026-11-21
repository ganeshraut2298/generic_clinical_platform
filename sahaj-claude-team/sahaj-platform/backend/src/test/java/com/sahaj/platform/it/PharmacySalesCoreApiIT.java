package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-80-2 to SAH-80-5 (pharmacy-sales.yaml 0.3.7, WP-I): customers, product search, the quote with the FEFO split, saving a bill
 * (stock, ledger, gapless numbers, the double tap), held bills, cancelling a bill into Quarantine and the refund. Every flow ends
 * with the ledger equal to the balances. Written, not run. Test plan: docs/test-plans/SAH-79.md section 11 (T80-2-* to T80-5-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacySalesCoreApiIT extends Sah80SalesSupport {

    private static Map<String, Object> customer(String name, String phone, Object limit) {
        return m("clientKey", UUID.randomUUID().toString(), "patientId", null, "name", name, "phone", phone, "allergyNote", null,
                "creditLimitPaise", limit, "confirmSamePhone", false);
    }

    // ---------------- 80-4 customers ----------------

    @Test
    void aWalkInCustomerIsCreatedReplayedAndTheSamePhoneNeedsConfirmation() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        String path = url(w.pharmacyId(), "customers");
        Map<String, Object> body = customer("Test Customer One", "9000000123", 500000);
        JsonNode c = post(w.keeper(), path, body, 201);
        assertThat(c.get("kind").asText()).isEqualTo("WALK_IN");
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get("credit").get("creditLimitPaise").asLong()).isEqualTo(500000);
        assertThat(c.get("credit").get("duePaise").asLong()).isZero();
        assertThat(idOf(post(w.keeper(), path, body, 200))).as("same client key replays").isEqualTo(idOf(c));
        assertThat(count(w.clinic(), "select count(*) from pharmacy.customer")).isOne();
        JsonNode dup = post(w.keeper(), path, customer("Test Customer Two", "9000000123", null), 409);
        assertType(dup, "customer-phone-exists");
        assertThat(dup.get("customerCandidates")).hasSize(1);
        assertThat(dup.get("customerCandidates").get(0).get("maskedPhone").asText()).doesNotContain("9000000123");
        Map<String, Object> confirmed = customer("Test Customer Two", "9000000123", null);
        confirmed.put("confirmSamePhone", true);
        post(w.keeper(), path, confirmed, 201);
        JsonNode none = get(w.keeper(), url(w.pharmacyId(), "customers/" + UUID.randomUUID()), 404);
        assertType(none, "not-found");
    }

    @Test
    void aCustomerPhoneIsMaskedForACashierAndInputIsChecked() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        String path = url(w.pharmacyId(), "customers");
        JsonNode c = post(w.keeper(), path, customer("Test Customer Three", "9000000456", null), 201);
        JsonNode asKeeper = get(w.keeper(), url(w.pharmacyId(), "customers/" + idOf(c)), 200);
        JsonNode asCashier = get(s.cashier(), url(w.pharmacyId(), "customers/" + idOf(c)), 200);
        assertThat(asKeeper.get("phoneMasked").asBoolean()).isFalse();
        assertThat(asCashier.get("phoneMasked").asBoolean()).isTrue();
        assertThat(asCashier.toString()).doesNotContain("9000000456");
        assertFieldMessage(post(w.keeper(), path, customer("Test Customer Four", "12345", null), 400), "phone", "Enter a 10-digit phone number");
        post(w.keeper(), path, customer("", "9000000789", null), 400);
        post(w.keeper(), path, customer("Test Customer Five", "9000000780", -1), 400);
        Map<String, Object> extra = customer("Test Customer Six", "9000000781", null);
        extra.put("tenantId", UUID.randomUUID().toString());
        post(w.keeper(), path, extra, 400);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.customer")).isOne();
    }

    @Test
    void customersOfAnotherClinicAreInvisibleAndARoleWithoutSellingIsRefused() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode c = post(w.keeper(), url(w.pharmacyId(), "customers"), customer("Test Customer Seven", "9000000555", null), 201);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "customers/" + idOf(c)), other.owner(), null), 404);
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "customers?q=Seven"), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Fiona Front");
        assertThat(call(HttpMethod.GET, url(w.pharmacyId(), "customers"), frontDesk.auth(), null).andReturn().getResponse().getStatus()).isIn(403, 404);
        assertThat(call(HttpMethod.POST, url(w.pharmacyId(), "customers"), frontDesk.auth(),
                js(customer("Test Customer Eight", "9000000556", null))).andReturn().getResponse().getStatus()).isIn(403, 404);
    }

    // ---------------- 80-3 product search and quote ----------------

    @Test
    void theProductSearchFindsAnActiveProductWithItsStockAndRefusesBadInput() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode rows = get(w.keeper(), url(w.pharmacyId(), "sale/products?q=dressing"), 200).get("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("availableQty").asInt()).isEqualTo(40);
        assertThat(rows.get(0).get("outOfStock").asBoolean()).isFalse();
        assertThat(rows.get(0).toString()).as("no purchase rate or margin in the search").doesNotContain("purchaseRate").doesNotContain("margin");
        assertThat(get(w.keeper(), url(w.pharmacyId(), "sale/products?barcode=NOSUCH1"), 200).get("items")).isEmpty();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "sale/products"), w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "sale/products?q=dressing&barcode=ABC"), w.keeper().auth(), null), 400);
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "sale/products?q=d"), w.keeper().auth(), null), 400);
    }

    @Test
    void aQuoteSplitsALineByFefoAndWorksTheTotalsAndAShortLineCannotBeSaved() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode q = quote(w.keeper(), w, plain(w.productA(), 102), 200);
        JsonNode lines = q.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("batch").get("batchNo").asText()).as("the earlier expiry first").isEqualTo("TESTB001");
        assertThat(lines.get(0).get("qty").asInt()).isEqualTo(100);
        assertThat(lines.get(1).get("batch").get("batchNo").asText()).isEqualTo("TESTB003");
        assertThat(lines.get(1).get("qty").asInt()).isEqualTo(2);
        JsonNode sum = q.get("summary");
        assertThat(sum.get("unitCount").asInt()).isEqualTo(102);
        assertThat(sum.get("mrpTotalPaise").asLong()).isEqualTo(100 * 4550L + 2 * 15000L);
        assertThat(sum.get("netPayablePaise").asLong() % 100).as("net is a whole number of rupees").isZero();
        assertThat(sum.get("netPayablePaise").asLong()).isEqualTo(sum.get("mrpTotalPaise").asLong() - sum.get("discountPaise").asLong() + sum.get("roundOffPaise").asLong());
        assertThat(q.get("canSave").asBoolean()).isTrue();
        JsonNode oneUnit = quote(w.keeper(), w, plain(w.productB(), 2), 200);
        assertThat(oneUnit.get("summary").get("netPayablePaise").asLong()).isEqualTo(4000);
        JsonNode taxed = oneUnit.get("lines").get(0);
        assertThat(taxed.get("taxablePaise").asLong() + taxed.get("taxPaise").asLong()).isEqualTo(taxed.get("netPaise").asLong());
        JsonNode shortQuote = quote(w.keeper(), w, plain(w.productA(), 104), 200);
        assertThat(shortQuote.get("canSave").asBoolean()).isFalse();
        assertThat(shortQuote.get("blockers").toString()).contains("SHORT");
        assertThat(billsSaved(w)).isZero();
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'COUNTER_SALE'")).as("a quote writes no ledger line").isZero();
    }

    @Test
    void aQuoteRefusesAMalformedCartWithAProblemBody() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        post(w.keeper(), url(w.pharmacyId(), "sale/quote"), plain(w.productA(), 0), 400);
        post(w.keeper(), url(w.pharmacyId(), "sale/quote"), plain(w.productA(), 1000000), 400);
        post(w.keeper(), url(w.pharmacyId(), "sale/quote"), cart(noBuyer(), null), 400);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(cl(w.productB(), 1));
        }
        JsonNode p = post(w.keeper(), url(w.pharmacyId(), "sale/quote"), m("buyer", noBuyer(), "lines", many, "discountPercent", null), 400);
        assertThat(p.toString()).contains("A bill can have at most 100 items.");
        post(w.keeper(), url(w.pharmacyId(), "sale/quote"), plain(UUID.randomUUID().toString(), 1), 404);
        assertType(post(w.keeper(), url(s.wardId(), "sale/quote"), plain(w.productB(), 1), 409), "sale-not-allowed");
    }

    // ---------------- 80-3 save a bill ----------------

    @Test
    void aSavedBillMovesStockWritesTheLedgerAndTakesTheNextNumberWithNoGap() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode first = saveBill(w.keeper(), w, plain(w.productB(), 2));
        assertThat(first.get("status").asText()).isEqualTo("SAVED");
        assertThat(first.get("billNo").asText()).startsWith("SB/");
        assertThat(first.get("summary").get("netPayablePaise").asLong()).isEqualTo(4000);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(38);
        assertThat(ledger(w, "COUNTER_SALE")).isGreaterThanOrEqualTo(1);
        // a failed save (the payments do not add up) takes no number
        JsonNode q = quote(w.keeper(), w, plain(w.productB(), 1), 200);
        Map<String, Object> bad = billBody(q, plain(w.productB(), 1), "CASH");
        bad.put("payments", List.of(m("mode", "CASH", "amountPaise", 1500)));
        JsonNode mismatch = post(w.keeper(), url(w.pharmacyId(), "bills"), bad, 400);
        assertThat(mismatch.toString()).contains("payments");
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).as("a refused save moves nothing").isEqualTo(38);
        JsonNode second = saveBill(w.keeper(), w, plain(w.productB(), 1));
        assertThat(serial(second.get("billNo").asText())).isEqualTo(serial(first.get("billNo").asText()) + 1);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(37);
        JsonNode again = get(w.keeper(), url(w.pharmacyId(), "bills/" + idOf(first)), 200);
        assertThat(again.get("billNo").asText()).isEqualTo(first.get("billNo").asText());
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aSplitBillTakesBothBatchesAndTheDoubleTapSavesOnce() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        Map<String, Object> cart = plain(w.productA(), 102);
        JsonNode q = quote(w.keeper(), w, cart, 200);
        Map<String, Object> body = billBody(q, cart, "UPI");
        JsonNode bill = post(w.keeper(), url(w.pharmacyId(), "bills"), body, 201);
        assertThat(bill.get("lines")).hasSize(2);
        assertThat(bal(w, w.pharmacyId(), "TESTB001", "AVAILABLE")).isZero();
        assertThat(bal(w, w.pharmacyId(), "TESTB003", "AVAILABLE")).isEqualTo(1);
        JsonNode replay = post(w.keeper(), url(w.pharmacyId(), "bills"), body, 200);
        assertThat(idOf(replay)).isEqualTo(idOf(bill));
        assertThat(billsSaved(w)).isOne();
        assertThat(bal(w, w.pharmacyId(), "TESTB003", "AVAILABLE")).as("stock taken once").isEqualTo(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aChangedCartIsRefusedWithTheNewQuoteAndInputIsChecked() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        Map<String, Object> cart = plain(w.productB(), 2);
        JsonNode q = quote(w.keeper(), w, cart, 200);
        Map<String, Object> stale = billBody(q, cart, "CASH");
        stale.put("quoteVersion", "stale-version");
        JsonNode changed = post(w.keeper(), url(w.pharmacyId(), "bills"), stale, 409);
        assertType(changed, "sale-changed");
        assertThat(changed.get("quote")).as("the cart priced now").isNotNull();
        Map<String, Object> wrongNet = billBody(q, cart, "CASH");
        wrongNet.put("expectedNetPaise", 3900);
        assertType(post(w.keeper(), url(w.pharmacyId(), "bills"), wrongNet, 409), "sale-changed");
        Map<String, Object> noKey = billBody(q, cart, "CASH");
        noKey.remove("clientKey");
        post(w.keeper(), url(w.pharmacyId(), "bills"), noKey, 400);
        Map<String, Object> twice = billBody(q, cart, "CASH");
        twice.put("payments", List.of(m("mode", "CASH", "amountPaise", 2000), m("mode", "CASH", "amountPaise", 2000)));
        post(w.keeper(), url(w.pharmacyId(), "bills"), twice, 400);
        Map<String, Object> udharNoCustomer = billBody(q, cart, "UDHAR");
        assertThat(call(HttpMethod.POST, url(w.pharmacyId(), "bills"), w.keeper().auth(), js(udharNoCustomer)).andReturn().getResponse().getStatus())
                .as("udhar needs a customer").isBetween(400, 409);
        assertThat(billsSaved(w)).isZero();
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(40);
    }

    @Test
    void aBillOfAnotherClinicIsInvisibleAndTheListShowsOnlyThisStore() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = saveBill(w.keeper(), w, plain(w.productB(), 1));
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "bills/" + idOf(bill)), other.owner(), null), 404);
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "bills"), other.owner(), null), 404);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "bills"), 200).get("items")).hasSize(1);
        assertThat(get(s.cashier(), url(w.pharmacyId(), "bills"), 200).toString()).as("a cashier sees the bill list without a patient mobile").doesNotContain("9000");
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Fiona Front");
        assertThat(call(HttpMethod.POST, url(w.pharmacyId(), "bills"), frontDesk.auth(), js(plain(w.productB(), 1))).andReturn().getResponse().getStatus()).isIn(403, 404);
    }

    // ---------------- 80-5 hold ----------------

    @Test
    void aHeldBillIsListedReadAndDiscardedAndTheTwentyFirstIsRefused() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        Map<String, Object> hold = new java.util.LinkedHashMap<>(plain(w.productB(), 3));
        hold.put("clientKey", UUID.randomUUID().toString());
        hold.put("replacesHeldBillId", null);
        JsonNode held = post(w.keeper(), url(w.pharmacyId(), "held-bills"), hold, 201);
        assertThat(held.get("status").asText()).isEqualTo("OPEN");
        assertThat(held.get("products")).hasSize(1);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).as("a held bill takes no stock and no number").isEqualTo(40);
        assertThat(billsSaved(w)).isZero();
        assertThat(get(w.keeper(), url(w.pharmacyId(), "held-bills"), 200).get("items")).hasSize(1);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "held-bills/" + idOf(held)), 200).get("cart").get("lines")).hasSize(1);
        ok(HttpMethod.POST, url(w.pharmacyId(), "held-bills/" + idOf(held) + "/discard"), w.keeper().auth(), null, 200);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "held-bills"), 200).get("items")).isEmpty();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> next = new java.util.LinkedHashMap<>(plain(w.productB(), 1));
            next.put("clientKey", UUID.randomUUID().toString());
            next.put("replacesHeldBillId", null);
            post(w.keeper(), url(w.pharmacyId(), "held-bills"), next, 201);
        }
        Map<String, Object> over = new java.util.LinkedHashMap<>(plain(w.productB(), 1));
        over.put("clientKey", UUID.randomUUID().toString());
        over.put("replacesHeldBillId", null);
        assertType(post(w.keeper(), url(w.pharmacyId(), "held-bills"), over, 409), "held-limit");
    }

    // ---------------- 80-5 cancel and refund ----------------

    @Test
    void aCancelledBillGoesToQuarantineKeepsItsNumberAndCannotBeCancelledTwice() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = saveBill(w.keeper(), w, plain(w.productB(), 2));
        Map<String, Object> body = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-item", "note", null, "backOnShelf", false, "secondSigner", null);
        String path = url(w.pharmacyId(), "bills/" + idOf(bill) + "/cancel");
        JsonNode denied = post(s.cashier(), path, body, 403);
        assertThat(denied.get("type").asText()).as("a cashier may not cancel").containsAnyOf("cancel-not-allowed", "forbidden", "pharmacist-required");
        assertThat(get(w.keeper(), url(w.pharmacyId(), "bills/" + idOf(bill)), 200).get("status").asText()).isEqualTo("SAVED");
        JsonNode cancelled = post(w.keeper(), path, body, 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("billNo").asText()).isEqualTo(bill.get("billNo").asText());
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "QUARANTINE")).as("not back on the shelf").isEqualTo(2);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(38);
        assertThat(ledger(w, "COUNTER_SALE_CANCELLED")).isGreaterThanOrEqualTo(1);
        Map<String, Object> second = m("clientKey", UUID.randomUUID().toString(), "version", cancelled.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-item", "note", null, "backOnShelf", true, "secondSigner", null);
        assertType(post(w.keeper(), path, second, 409), "already-cancelled");
        JsonNode next = saveBill(w.keeper(), w, plain(w.productB(), 1));
        assertThat(serial(next.get("billNo").asText())).as("the cancelled number is never reused").isEqualTo(serial(bill.get("billNo").asText()) + 1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aBillCancelledBackOnTheShelfReturnsToAvailableAndAReasonOtherNeedsANote() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = saveBill(w.keeper(), w, plain(w.productB(), 3));
        String path = url(w.pharmacyId(), "bills/" + idOf(bill) + "/cancel");
        Map<String, Object> noNote = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.other", "note", null, "backOnShelf", true, "secondSigner", null);
        post(w.keeper(), path, noNote, 400);
        Map<String, Object> badReason = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.no-such", "note", null, "backOnShelf", true, "secondSigner", null);
        post(w.keeper(), path, badReason, 400);
        Map<String, Object> ok = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-quantity", "note", null, "backOnShelf", true, "secondSigner", null);
        post(w.keeper(), path, ok, 200);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(40);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "QUARANTINE")).isZero();
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aRefundOfACancelledBillWaitsForTheOwnersRefundModesAndIsRecordedOnce() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = saveBill(w.keeper(), w, plain(w.productB(), 2));
        Map<String, Object> cancel = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-item", "note", null, "backOnShelf", true, "secondSigner", null);
        JsonNode cancelled = post(w.keeper(), url(w.pharmacyId(), "bills/" + idOf(bill) + "/cancel"), cancel, 200);
        String refundPath = url(w.pharmacyId(), "bills/" + idOf(bill) + "/refund");
        Map<String, Object> refund = m("clientKey", UUID.randomUUID().toString(), "version", cancelled.get("version").asLong(),
                "refunds", List.of(m("mode", "CASH", "amountPaise", 4000)));
        JsonNode noModes = post(w.keeper(), refundPath, refund, 400);
        assertThat(noModes.toString()).contains("This refund mode is not allowed. Ask your owner.");
        saleSettings(w, 7, List.of("ORIGINAL_MODE", "CASH"));
        JsonNode done = post(w.keeper(), refundPath, refund, 200);
        assertThat(done.get("refundDuePaise").asLong()).isZero();
        Map<String, Object> again = m("clientKey", UUID.randomUUID().toString(), "version", done.get("version").asLong(),
                "refunds", List.of(m("mode", "CASH", "amountPaise", 4000)));
        assertType(post(w.keeper(), refundPath, again, 409), "refund-already-recorded");
        JsonNode live = saveBill(w.keeper(), w, plain(w.productB(), 1));
        problem(call(HttpMethod.POST, url(w.pharmacyId(), "bills/" + idOf(live) + "/refund"), w.keeper().auth(),
                js(m("clientKey", UUID.randomUUID().toString(), "version", live.get("version").asLong(),
                        "refunds", List.of(m("mode", "CASH", "amountPaise", 2000))))), 409);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void theSaleOfAnotherClinicsStoreIsNotFoundAndTheWardStoreRefusesASale() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.POST, url(w.pharmacyId(), "sale/quote"), other.owner(), js(plain(w.productB(), 1))), 404);
        problem(call(HttpMethod.POST, url(w.pharmacyId(), "bills"), other.owner(), js(plain(w.productB(), 1))), 404);
        JsonNode p = post(w.keeper(), url(s.wardId(), "bills"), plain(w.productB(), 1), 409);
        assertType(p, "sale-not-allowed");
        assertThat(billsSaved(w)).isZero();
    }
}
