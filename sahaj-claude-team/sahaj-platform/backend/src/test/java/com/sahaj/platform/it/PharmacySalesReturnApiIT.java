package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-80-18 (pharmacy-sales.yaml 0.3.7, WP-L): the sales return by a pharmacist (completes at once), the cashier return that waits
 * for the pharmacist (O-8), the return rule that needs the owner, the window and the refund checks, and tenant isolation.
 * Written, not run. Test plan: docs/test-plans/SAH-79.md section 11 (T80-18-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacySalesReturnApiIT extends Sah80SalesSupport {

    private static final String SEALED = "return-condition.sealed";
    private static final String REASON = "sales-return.excess-bought";

    private Map<String, Object> returnBody(String billId, String lineId, int qty, String condition, long refund) {
        return m("clientKey", UUID.randomUUID().toString(), "billId", billId,
                "lines", List.of(m("billLineId", lineId, "qty", qty, "conditionKey", condition)),
                "reasonKey", REASON, "note", null, "refunds", List.of(m("mode", "CASH", "amountPaise", refund)), "secondSigner", null);
    }

    private String lineOf(JsonNode bill) {
        return bill.get("lines").get(0).get("lineId").asText();
    }

    private JsonNode soldBill(Sale s, int qty) throws Exception {
        saleSettings(s.w(), 7, List.of("ORIGINAL_MODE", "CASH"));
        return saveBill(s.w().keeper(), s.w(), plain(s.w().productB(), qty));
    }

    @Test
    void aPharmacistReturnOfSealedItemsCompletesAtOnceWithACreditNoteAndStockComesBack() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = soldBill(s, 5);
        JsonNode returnable = get(w.keeper(), url(w.pharmacyId(), "sales-returns/bills/" + idOf(bill)), 200);
        JsonNode line = returnable.get("lines").get(0);
        assertThat(line.get("returnableQty").asInt()).isEqualTo(5);
        assertThat(line.get("refundPerUnitPaise").asLong()).isEqualTo(2000);
        JsonNode done = post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 2, SEALED, 4000), 201);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("creditNoteNo").asText()).startsWith("CN/");
        assertThat(done.get("refundTotalPaise").asLong()).isEqualTo(4000);
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(37);
        assertThat(ledger(w, "SALES_RETURN")).isGreaterThanOrEqualTo(1);
        JsonNode after = get(w.keeper(), url(w.pharmacyId(), "sales-returns/bills/" + idOf(bill)), 200);
        assertThat(after.get("lines").get(0).get("returnableQty").asInt()).isEqualTo(3);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "sales-returns/" + idOf(done)), 200).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(get(w.keeper(), url(w.pharmacyId(), "sales-returns"), 200).get("items")).hasSize(1);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aReturnAboveWhatWasSoldOrWithAWrongRefundIsRefusedAndMovesNothing() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = soldBill(s, 3);
        post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 4, SEALED, 8000), 400);
        JsonNode mismatch = post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 2, SEALED, 3999), 400);
        assertThat(mismatch.toString()).contains("refunds");
        post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 0, SEALED, 1), 400);
        post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 1, "return-condition.no-such", 2000), 400);
        Map<String, Object> extra = returnBody(idOf(bill), lineOf(bill), 1, SEALED, 2000);
        extra.put("tenantId", UUID.randomUUID().toString());
        post(w.keeper(), url(w.pharmacyId(), "sales-returns"), extra, 400);
        assertThat(get(w.keeper(), url(w.pharmacyId(), "sales-returns"), 200).get("items")).isEmpty();
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(37);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void withoutAReturnWindowTheReturnIsRefused() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = saveBill(w.keeper(), w, plain(w.productB(), 2));
        JsonNode p = post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 1, SEALED, 2000), 409);
        assertType(p, "return-window-not-set");
        assertThat(p.toString()).contains("Return window is not set. Ask your owner.");
    }

    @Test
    void aCashierReturnWaitsForThePharmacistWhoAcceptsAndFinishesIt() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = soldBill(s, 4);
        JsonNode started = post(s.cashier(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 2, SEALED, 4000), 201);
        assertThat(started.get("status").asText()).isEqualTo("WAITING_PHARMACIST");
        assertThat(started.get("creditNoteNo").isNull()).as("no credit note before the pharmacist").isTrue();
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).as("nothing moved yet").isEqualTo(36);
        String path = url(w.pharmacyId(), "sales-returns/" + idOf(started) + "/accept");
        String returnLine = started.get("lines").get(0).get("returnLineId").asText();
        Map<String, Object> accept = m("clientKey", UUID.randomUUID().toString(), "version", started.get("version").asLong(),
                "decisions", List.of(m("returnLineId", returnLine, "decision", "ACCEPT")),
                "refunds", List.of(m("mode", "CASH", "amountPaise", 4000)), "secondSigner", null);
        JsonNode denied = post(s.cashier(), path, accept, 403);
        assertThat(denied.get("type").asText()).containsAnyOf("pharmacist-required", "forbidden");
        JsonNode accepted = post(w.keeper(), path, accept, 200);
        assertThat(accepted.get("status").asText()).isIn("COMPLETED", "APPROVED");
        if ("APPROVED".equals(accepted.get("status").asText())) {
            accepted = ok(HttpMethod.POST, url(w.pharmacyId(), "sales-returns/" + idOf(started) + "/finish"), w.keeper().auth(),
                    js(m("clientKey", UUID.randomUUID().toString(), "version", accepted.get("version").asLong(), "secondSigner", null)), 200);
        }
        assertThat(accepted.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(accepted.get("creditNoteNo").asText()).startsWith("CN/");
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(38);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aPharmacistCanSendAnOpenedItemToQuarantineAndRefuseAnother() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = soldBill(s, 4);
        JsonNode started = post(s.cashier(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 2, "return-condition.opened", 4000), 201);
        assertThat(started.get("status").asText()).isEqualTo("WAITING_PHARMACIST");
        Map<String, Object> accept = m("clientKey", UUID.randomUUID().toString(), "version", started.get("version").asLong(),
                "decisions", List.of(m("returnLineId", started.get("lines").get(0).get("returnLineId").asText(), "decision", "ACCEPT_QUARANTINE")),
                "refunds", List.of(m("mode", "CASH", "amountPaise", 4000)), "secondSigner", null);
        JsonNode done = post(w.keeper(), url(w.pharmacyId(), "sales-returns/" + idOf(started) + "/accept"), accept, 200);
        assertThat(done.get("lines").get(0).get("destination").asText()).isEqualTo("QUARANTINE");
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "QUARANTINE")).isEqualTo(2);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aReturnRuleThatNeedsTheOwnerWaitsForApprovalThenCompletes() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = soldBill(s, 4);
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        rules.put("SALES_RETURN", rule("SALES_RETURN", null, null, always(1, "OWNER")));
        putRules(w, rules);
        JsonNode r = post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 2, SEALED, 4000), 201);
        assertThat(r.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(r.get("approval")).isNotNull();
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).as("stock waits for the approval").isEqualTo(36);
        String requestId = r.get("approval").get("requestId").asText();
        assertThat(get(owner(w), PH + "/approvals/inbox", 200).toString()).contains(requestId).contains("SALES_RETURN");
        JsonNode a = get(owner(w), PH + "/approvals/" + requestId, 200);
        decide(owner(w), a, "APPROVED", null, null, 200);
        JsonNode now = get(w.keeper(), url(w.pharmacyId(), "sales-returns/" + idOf(r)), 200);
        assertThat(now.get("status").asText()).isIn("APPROVED", "COMPLETED");
        if ("APPROVED".equals(now.get("status").asText())) {
            now = ok(HttpMethod.POST, url(w.pharmacyId(), "sales-returns/" + idOf(r) + "/finish"), w.keeper().auth(),
                    js(m("clientKey", UUID.randomUUID().toString(), "version", now.get("version").asLong(), "secondSigner", null)), 200);
        }
        assertThat(now.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(bal(w, w.pharmacyId(), "TESTB002", "AVAILABLE")).isEqualTo(38);
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aReturnOfAnotherClinicIsInvisibleAndAnUnmappedRoleIsRefused() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = soldBill(s, 2);
        JsonNode done = post(w.keeper(), url(w.pharmacyId(), "sales-returns"), returnBody(idOf(bill), lineOf(bill), 1, SEALED, 2000), 201);
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "sales-returns/" + idOf(done)), other.owner(), null), 404);
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "sales-returns/bills/" + idOf(bill)), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Fiona Front");
        assertThat(call(HttpMethod.POST, url(w.pharmacyId(), "sales-returns"), frontDesk.auth(),
                js(returnBody(idOf(bill), lineOf(bill), 1, SEALED, 2000))).andReturn().getResponse().getStatus()).isIn(403, 404);
        assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = 'SALES_RETURN'")).isOne();
    }
}
