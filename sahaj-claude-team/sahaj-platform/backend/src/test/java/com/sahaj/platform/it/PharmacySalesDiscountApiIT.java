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
 * SAH-80-6 (pharmacy-sales.yaml 0.3.7, WP-J, DEC-089, DEC-090): the sale discount rule (owner approves above 10 percent), the
 * discount request, withdraw and void, the approval by the owner, the owner exempt from his own rule, a held bill discarded
 * withdrawing its request, and the role read-out. Written, not run. Test plan: docs/test-plans/SAH-79.md section 11 (T80-6-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacySalesDiscountApiIT extends Sah80SalesSupport {

    private Sale ruleWorld() throws Exception {
        Sale s = saleWorld();
        putRules(s.w(), Map.of("SALE_DISCOUNT", rule("SALE_DISCOUNT", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, "10", null))));
        return s;
    }

    private static Map<String, Object> discounted(String productId, int qty, String percent) {
        return cart(noBuyer(), percent, cl(productId, qty));
    }

    private JsonNode request(Pn who, Sw w, Map<String, Object> cart, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(cart);
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("heldBillId", null);
        return post(who, url(w.pharmacyId(), "sale/discount-requests"), body, expected);
    }

    private JsonNode approve(Sw w, String requestId) throws Exception {
        JsonNode a = get(owner(w), PH + "/approvals/" + requestId, 200);
        return decide(owner(w), a, "APPROVED", null, null, 200);
    }

    @Test
    void aDiscountWithinTheLimitSavesAtOnceAndAboveItNeedsApproval() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        JsonNode within = quote(s.cashier(), w, discounted(w.productB(), 10, "5"), 200);
        assertThat(within.get("discount").get("approvalNeeded").asBoolean()).isFalse();
        assertThat(within.get("canSave").asBoolean()).isTrue();
        assertThat(within.get("summary").get("discountPaise").asLong()).isEqualTo(1000);
        JsonNode above = quote(s.cashier(), w, discounted(w.productB(), 10, "15"), 200);
        assertThat(above.get("discount").get("approvalNeeded").asBoolean()).isTrue();
        assertThat(above.get("discount").get("limitPercent").asText()).startsWith("10");
        assertThat(above.get("canSave").asBoolean()).isFalse();
        assertThat(above.get("blockers").toString()).contains("DISCOUNT_APPROVAL");
        Map<String, Object> cart = discounted(w.productB(), 10, "15");
        JsonNode needed = post(s.cashier(), url(w.pharmacyId(), "bills"), billBody(above, cart, "CASH"), 409);
        assertType(needed, "discount-approval-needed");
        assertThat(billsSaved(w)).isZero();
        assertThat(saveBill(s.cashier(), w, discounted(w.productB(), 10, "5")).get("status").asText()).isEqualTo("SAVED");
    }

    @Test
    void aRequestIsPendingReadWithdrawnAndWithdrawingTwiceChangesNothing() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        JsonNode r = request(s.cashier(), w, discounted(w.productB(), 10, "15"), 201);
        assertThat(r.get("required").asBoolean()).isTrue();
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertThat(r.get("waitingFor").asText()).isEqualTo("OWNER");
        assertThat(r.get("ruleText").asText()).contains("10%");
        String id = r.get("requestId").asText();
        assertThat(get(s.cashier(), url(w.pharmacyId(), "sale/discount-requests/" + id), 200).get("status").asText()).isEqualTo("PENDING");
        JsonNode inbox = get(owner(w), PH + "/approvals/inbox", 200);
        assertThat(inbox.toString()).contains(id).contains("SALE_DISCOUNT");
        JsonNode withdrawn = ok(HttpMethod.POST, url(w.pharmacyId(), "sale/discount-requests/" + id + "/withdraw"), s.cashier().auth(), null, 200);
        assertThat(withdrawn.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(ok(HttpMethod.POST, url(w.pharmacyId(), "sale/discount-requests/" + id + "/withdraw"), s.cashier().auth(), null, 200).get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(get(owner(w), PH + "/approvals/inbox", 200).toString()).as("it left the inbox").doesNotContain(id);
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_DISCOUNT_WITHDRAWN'")).isOne();
    }

    @Test
    void anApprovedRequestLetsTheCashierSaveAndAChangedCartVoidsIt() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        Map<String, Object> cart = discounted(w.productB(), 10, "15");
        JsonNode r = request(s.cashier(), w, cart, 201);
        String id = r.get("requestId").asText();
        approve(w, id);
        JsonNode seen = get(s.cashier(), url(w.pharmacyId(), "sale/discount-requests/" + id), 200);
        assertThat(seen.get("status").asText()).isEqualTo("APPROVED");
        assertThat(seen.get("decidedBy").get("name").asText()).isEqualTo("Owner");
        Map<String, Object> withRequest = new LinkedHashMap<>(cart);
        withRequest.put("discountRequestId", id);
        JsonNode q = quote(s.cashier(), w, withRequest, 200);
        assertThat(q.get("discount").get("approvalNeeded").asBoolean()).isFalse();
        JsonNode bill = post(s.cashier(), url(w.pharmacyId(), "bills"), billBody(q, withRequest, "CASH"), 201);
        assertThat(bill.get("discountApprovedBy").get("name").asText()).isEqualTo("Owner");
        // the same request for a bigger cart is void
        Map<String, Object> bigger = new LinkedHashMap<>(discounted(w.productB(), 12, "15"));
        bigger.put("discountRequestId", id);
        JsonNode qb = quote(s.cashier(), w, bigger, 200);
        assertThat(qb.get("discount").get("approval").get("status").asText()).isEqualTo("VOID");
        assertThat(qb.get("canSave").asBoolean()).isFalse();
        assertType(post(s.cashier(), url(w.pharmacyId(), "bills"), billBody(qb, bigger, "CASH"), 409), "discount-approval-void");
        assertLedgerEqualsBalances(w);
    }

    @Test
    void aRejectedRequestIsShownWithItsReasonAndTheRaiserCannotApproveHisOwn() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        JsonNode r = request(s.w().keeper(), w, discounted(w.productB(), 10, "20"), 201);
        String id = r.get("requestId").asText();
        JsonNode a = get(owner(w), PH + "/approvals/" + id, 200);
        decide(owner(w), a, "REJECTED", REJECT_OTHER, null, 400);
        decideWithNote(w, a);
        JsonNode seen = get(s.w().keeper(), url(w.pharmacyId(), "sale/discount-requests/" + id), 200);
        assertThat(seen.get("status").asText()).isEqualTo("REJECTED");
        assertThat(seen.get("rejectReason")).isNotNull();
        JsonNode second = request(s.w().keeper(), w, discounted(w.productB(), 10, "20"), 201);
        JsonNode own = get(s.w().keeper(), PH + "/approvals/" + second.get("requestId").asText(), 200);
        assertThat(call(HttpMethod.POST, decisionUrl(second.get("requestId").asText()), s.w().keeper().auth(),
                decisionBody(UUID.randomUUID(), own.get("levelNo").asInt(), own.get("requestVersion").asLong(), "APPROVED", null)).andReturn().getResponse().getStatus())
                .as("a pharmacist is not the approver").isIn(403, 409);
    }

    private void decideWithNote(Sw w, JsonNode a) throws Exception {
        Map<String, Object> d = decision(a.get("levelNo").asInt(), a.get("requestVersion").asLong(), "REJECTED", REJECT_OTHER, null);
        d.put("note", "Too high for this item");
        ok(HttpMethod.POST, decisionUrl(a.get("requestId").asText()), owner(w).auth(), js(d), 200);
    }

    @Test
    void theOwnerIsExemptFromTheDiscountRuleEverywhere() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        Map<String, Object> cart = discounted(w.productB(), 10, "25");
        JsonNode q = quote(owner(w), w, cart, 200);
        assertThat(q.get("discount").get("approvalNeeded").asBoolean()).isFalse();
        assertThat(q.get("canSave").asBoolean()).isTrue();
        JsonNode asked = request(owner(w), w, cart, 201);
        assertThat(asked.get("required").asBoolean()).isFalse();
        assertThat(asked.get("requestId").isNull()).isTrue();
        assertThat(asked.get("selfApprovalOnly").asBoolean()).isFalse();
        assertThat(count(w.clinic(), "select count(*) from platform.audit_log where action = 'PHARMACY_DISCOUNT_REQUESTED'")).isZero();
        Map<String, Object> withRandomId = new LinkedHashMap<>(cart);
        withRandomId.put("discountRequestId", UUID.randomUUID().toString());
        JsonNode bill = post(owner(w), url(w.pharmacyId(), "bills"), billBody(quote(owner(w), w, withRandomId, 200), withRandomId, "CASH"), 201);
        assertThat(bill.get("summary").get("discountPercent").asText()).startsWith("25");
        JsonNode approver = bill.get("discountApprovedBy");
        assertThat(approver == null || approver.isNull()).as("no approver named for an exempt owner").isTrue();
        JsonNode readout = get(owner(w), PH + "/compliance/role-readout", 200);
        JsonNode row = null;
        for (JsonNode r : readout.get("rows")) {
            if ("DISCOUNT_ABOVE_LIMIT".equals(r.get("action").asText())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        for (JsonNode cell : row.get("cells")) {
            if ("OWNER".equals(cell.get("column").asText())) {
                assertThat(cell.get("value").asText()).isEqualTo("ALLOW");
                assertThat(cell.get("ruleText").isNull()).isTrue();
            }
            if ("CASHIER".equals(cell.get("column").asText())) {
                assertThat(cell.get("value").asText()).isEqualTo("NEEDS_APPROVAL");
            }
        }
    }

    @Test
    void discardingAHeldBillWithdrawsItsPendingRequest() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        Map<String, Object> cart = discounted(w.productB(), 10, "15");
        JsonNode r = request(s.cashier(), w, cart, 201);
        String id = r.get("requestId").asText();
        Map<String, Object> hold = new LinkedHashMap<>(cart);
        hold.put("discountRequestId", id);
        hold.put("clientKey", UUID.randomUUID().toString());
        hold.put("replacesHeldBillId", null);
        JsonNode held = post(s.cashier(), url(w.pharmacyId(), "held-bills"), hold, 201);
        assertThat(held.get("discountState").asText()).isEqualTo("PENDING");
        ok(HttpMethod.POST, url(w.pharmacyId(), "held-bills/" + idOf(held) + "/discard"), s.cashier().auth(), null, 200);
        assertThat(get(s.cashier(), url(w.pharmacyId(), "sale/discount-requests/" + id), 200).get("status").asText()).isEqualTo("WITHDRAWN");
    }

    @Test
    void aNewRequestOfTheSameSellerWithdrawsTheEarlierOneAndInputIsChecked() throws Exception {
        Sale s = ruleWorld();
        Sw w = s.w();
        Map<String, Object> cart = discounted(w.productB(), 10, "15");
        String first = request(s.cashier(), w, cart, 201).get("requestId").asText();
        String second = request(s.cashier(), w, cart, 201).get("requestId").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(get(s.cashier(), url(w.pharmacyId(), "sale/discount-requests/" + first), 200).get("status").asText()).isEqualTo("WITHDRAWN");
        for (String bad : List.of("-1", "101")) {
            Map<String, Object> body = new LinkedHashMap<>(discounted(w.productB(), 1, bad));
            body.put("clientKey", UUID.randomUUID().toString());
            post(s.cashier(), url(w.pharmacyId(), "sale/discount-requests"), body, 400);
        }
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "sale/discount-requests/" + second), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Fiona Front");
        assertThat(call(HttpMethod.GET, url(w.pharmacyId(), "sale/discount-requests/" + second), frontDesk.auth(), null).andReturn().getResponse().getStatus())
                .isIn(403, 404);
    }
}
