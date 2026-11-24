package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-80-13, SAH-80-15, SAH-80-19, SAH-80-20, SAH-80-21 (pharmacy-sales.yaml 0.3.7, WP-K, WP-L, WP-M, WP-N): the command centre cards
 * per store and per role, the activity feed, the role read-out, the OPD and IPD queues that answer "not connected", the Sales and
 * compliance reports, the inbox rows of a discount, and the blind count rule 24 on the sale reads. Written, not run. Test plan:
 * docs/test-plans/SAH-79.md section 11 (T80-13-* to T80-21-*).
 */
@OnTestDatabase
@WithPinPepper
class PharmacySalesCommandApiIT extends Sah80SalesSupport {

    private JsonNode cancelBill(Sw w, Pn who, JsonNode bill) throws Exception {
        Map<String, Object> body = m("clientKey", UUID.randomUUID().toString(), "version", bill.get("version").asLong(),
                "reasonKey", "cancel-bill.wrong-item", "note", null, "backOnShelf", true, "secondSigner", null);
        return post(who, url(w.pharmacyId(), "bills/" + idOf(bill) + "/cancel"), body, 200);
    }

    // ---------------- 80-20 command centre ----------------

    @Test
    void theCardsOfOneStoreNeverCountTheBillsOfAnotherStoreAndACashierGetsTodaysSalesOnly() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        String annex = idOf(createStore(w.clinic(), "Annex Pharmacy", "ANX", "PHARMACY"));
        Pn annexKeeper = crew(w.clinic(), "PHARMACIST", "Ola Other");
        mapStaff(w.clinic(), annex, annexKeeper, "STORE_USER");
        saveBill(w.keeper(), w, plain(w.productB(), 2));
        saveBill(w.keeper(), w, plain(w.productB(), 3));
        JsonNode here = get(w.keeper(), PH + "/command-centre?storeId=" + w.pharmacyId(), 200);
        assertThat(here.get("todaysSales").get("billCount").asInt()).isEqualTo(2);
        assertThat(here.get("todaysSales").get("netPaise").asLong()).isEqualTo(10000);
        assertThat(here.get("todaysSales").get("averagePaise").asLong()).isEqualTo(5000);
        JsonNode there = get(annexKeeper, PH + "/command-centre?storeId=" + annex, 200);
        assertThat(there.get("todaysSales").get("billCount").asInt()).as("the other store's bills are not counted").isZero();
        assertThat(there.get("todaysSales").get("state").asText()).isEqualTo("EMPTY");
        assertThat(there.get("nearExpiry90") == null || there.get("nearExpiry90").isNull()
                || there.get("nearExpiry90").get("batchCount").asInt() == 0).isTrue();
        JsonNode all = get(owner(w), PH + "/command-centre", 200);
        assertThat(all.get("store").isNull()).isTrue();
        assertThat(all.get("todaysSales").get("billCount").asInt()).isEqualTo(2);
        JsonNode cashier = get(s.cashier(), PH + "/command-centre?storeId=" + w.pharmacyId(), 200);
        assertThat(cashier.get("todaysSales")).isNotNull();
        for (String card : new String[] {"grossMargin", "nearExpiry90", "reorder", "rxWaiting", "udhar", "expiryRisk", "livePrescriptions", "compliance"}) {
            assertThat(cashier.get(card) == null || cashier.get(card).isNull()).as("a cashier does not get " + card).isTrue();
        }
        assertThat(here.get("grossMargin") == null || here.get("grossMargin").isNull()).as("gross margin is not for go-live").isTrue();
    }

    @Test
    void aCancelledBillIsNotCountedAndTheStoreAndRoleRulesApply() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode bill = saveBill(w.keeper(), w, plain(w.productB(), 2));
        saveBill(w.keeper(), w, plain(w.productB(), 1));
        cancelBill(w, w.keeper(), bill);
        assertThat(get(w.keeper(), PH + "/command-centre?storeId=" + w.pharmacyId(), 200).get("todaysSales").get("billCount").asInt()).isOne();
        problem(call(HttpMethod.GET, PH + "/command-centre", w.keeper().auth(), null), 400);
        assertThat(call(HttpMethod.GET, PH + "/command-centre?storeId=" + s.wardId(), s.cashier().auth(), null).andReturn().getResponse().getStatus()).isIn(403, 404);
        Clinic other = pharmacyClinic();
        centralAndPharmacy(other); // a clinic with stores of its own: a foreign store id is then 404 (a clinic with no store reaches nothing, 403)
        problem(call(HttpMethod.GET, PH + "/command-centre?storeId=" + w.pharmacyId(), other.owner(), null), 404);
        Pn frontDesk = crew(w.clinic(), "FRONT_DESK", "Fiona Front");
        assertType(problem(call(HttpMethod.GET, PH + "/command-centre?storeId=" + w.pharmacyId(), frontDesk.auth(), null), 403), "forbidden");
    }

    // ---------------- 80-19 role read-out and activity ----------------

    @Test
    void theRoleReadoutAndTheActivityFeedAreForTheOwnerAndAPharmacistAndBuiltFromIdsOnly() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode readout = get(w.keeper(), PH + "/compliance/role-readout", 200);
        assertThat(readout.get("rows")).hasSize(6);
        assertThat(readout.get("columns").toString()).contains("OWNER").contains("PHARMACIST").contains("CASHIER");
        for (JsonNode row : readout.get("rows")) {
            if ("CANCEL_SAVED_BILL".equals(row.get("action").asText())) {
                for (JsonNode cell : row.get("cells")) {
                    if ("CASHIER".equals(cell.get("column").asText())) {
                        assertThat(cell.get("value").asText()).isEqualTo("DENY");
                    }
                }
            }
        }
        assertType(problem(call(HttpMethod.GET, PH + "/compliance/role-readout", s.cashier().auth(), null), 403), "forbidden");
        JsonNode bill = saveBill(w.keeper(), w, cart(walkIn("Test Patient"), null, cl(w.productB(), 1)));
        cancelBill(w, w.keeper(), bill);
        JsonNode feed = get(w.keeper(), PH + "/activity?type=BILL_CANCELLED&storeId=" + w.pharmacyId(), 200);
        assertThat(feed.get("items")).hasSize(1);
        assertThat(feed.get("items").get(0).get("type").asText()).isEqualTo("BILL_CANCELLED");
        assertThat(feed.get("items").get(0).get("text").asText()).as("no patient name in the text").doesNotContain("Test Patient");
        assertThat(feed.get("items").get(0).get("by").get("name").asText()).isEqualTo("Pia Pharmacist");
        assertThat(get(w.keeper(), PH + "/activity?type=ALLERGY_OVERRIDE", 200).get("items")).isEmpty();
        problem(call(HttpMethod.GET, PH + "/activity?type=NO_SUCH", w.keeper().auth(), null), 400);
        assertType(problem(call(HttpMethod.GET, PH + "/activity", s.cashier().auth(), null), 403), "forbidden");
        Clinic other = pharmacyClinic();
        assertThat(get(new Pn(other, null, null, null, "OWNER", other.owner()), PH + "/activity", 200).get("items")).as("another clinic's feed is empty").isEmpty();
    }

    // ---------------- 80-11, 80-13, 80-15 not connected ----------------

    @Test
    void theOpdAndIpdQueuesAnswerNotConnectedAndTheirWritesAnswerNotConnected() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        JsonNode opd = get(w.keeper(), url(w.pharmacyId(), "opd-queue"), 200);
        assertThat(opd.get("connected").asBoolean()).isFalse();
        assertThat(opd.get("items")).isEmpty();
        JsonNode ipd = get(w.keeper(), url(w.pharmacyId(), "ipd-queue"), 200);
        assertThat(ipd.get("connected").asBoolean()).isFalse();
        assertThat(ipd.get("items")).isEmpty();
        assertType(problem(call(HttpMethod.POST, url(w.pharmacyId(), "opd-queue/" + UUID.randomUUID() + "/claim"), w.keeper().auth(), null), 409), "not-connected");
        assertThat(call(HttpMethod.GET, url(w.pharmacyId(), "opd-queue/" + UUID.randomUUID()), w.keeper().auth(), null).andReturn().getResponse().getStatus()).as("port not connected is 409 not-connected before any 404 (pharmacy-sales.yaml pharmacyGetOpdPrescription)").isEqualTo(409);
        JsonNode waiting = get(w.keeper(), url(w.pharmacyId(), "returns-waiting"), 200);
        assertThat(waiting.get("items")).isEmpty();
        Clinic other = pharmacyClinic();
        problem(call(HttpMethod.GET, url(w.pharmacyId(), "opd-queue"), other.owner(), null), 404);
        assertThat(call(HttpMethod.GET, url(w.pharmacyId(), "opd-queue"), crew(w.clinic(), "FRONT_DESK", "Fiona Front").auth(), null).andReturn().getResponse().getStatus())
                .isIn(403, 404);
    }

    // ---------------- 80-21 reports ----------------

    @Test
    void theSalesAndComplianceGroupIsInTheCatalogueAndEachReportRunsWithItsColumns() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        saveBill(w.keeper(), w, plain(w.productB(), 2));
        String catalogue = get(w.keeper(), PH + "/reports", 200).toString();
        assertThat(catalogue).contains("SALES_COMPLIANCE").contains("Sales & compliance");
        for (String key : new String[] {"sales-margin", "sales-returns", "gst-sales-register", "h1-register", "schedule-x-register", "activity-log"}) {
            JsonNode page = get(w.keeper(), PH + "/reports/" + key + "?storeId=" + w.pharmacyId() + "&from=" + todayIst().minusDays(1) + "&to=" + todayIst(), 200);
            assertThat(page.get("reportKey").asText()).isEqualTo(key);
            assertThat(page.get("columns")).isNotEmpty();
        }
        JsonNode gst = get(w.keeper(), PH + "/reports/gst-sales-register?storeId=" + w.pharmacyId() + "&from=" + todayIst().minusDays(1) + "&to=" + todayIst(), 200);
        assertThat(gst.get("rows")).hasSize(1);
        assertThat(gst.get("rows").toString()).as("no patient name outside the H1 report").doesNotContain("Test Patient");
        assertThat(call(HttpMethod.GET, PH + "/reports/sales-margin?storeId=" + w.pharmacyId() + "&from=" + todayIst().minusMonths(13) + "&to=" + todayIst(),
                w.keeper().auth(), null).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, PH + "/reports/no-such-report", w.keeper().auth(), null).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, PH + "/reports/sales-margin?storeId=" + w.pharmacyId(), s.cashier().auth(), null).andReturn().getResponse().getStatus()).isEqualTo(403);
        Clinic other = pharmacyClinic();
        assertThat(call(HttpMethod.GET, PH + "/reports/gst-sales-register?storeId=" + w.pharmacyId(), other.owner(), null).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theMarginCellsAreEmptyForAHeadOfDepartmentWhoIsNotAPharmacist() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        saveBill(w.keeper(), w, plain(w.productB(), 2));
        Pn hod = crew(w.clinic(), "NURSE", "Hema Head");
        mapStaff(w.clinic(), w.pharmacyId(), hod, "APPROVER");
        JsonNode asKeeper = get(w.keeper(), PH + "/reports/sales-margin?storeId=" + w.pharmacyId() + "&from=" + todayIst().minusDays(1) + "&to=" + todayIst(), 200);
        JsonNode asHod = get(hod, PH + "/reports/sales-margin?storeId=" + w.pharmacyId() + "&from=" + todayIst().minusDays(1) + "&to=" + todayIst(), 200);
        assertThat(asHod.get("columns")).as("the column stays").hasSameSizeAs(asKeeper.get("columns"));
        int cost = -1;
        for (int i = 0; i < asHod.get("columns").size(); i++) {
            String key = asHod.get("columns").get(i).toString().toLowerCase();
            if (key.contains("cost") || key.contains("margin")) {
                cost = i;
                for (JsonNode row : asHod.get("rows")) {
                    assertThat(row.get(i).isNull()).as("cell " + key).isTrue();
                }
            }
        }
        assertThat(cost).as("a cost or margin column exists").isNotNegative();
    }

    // ---------------- inbox rows of sales ----------------

    @Test
    void aDiscountRequestIsAnInboxRowWithTheApproveAction() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        putRules(w, Map.of("SALE_DISCOUNT", rule("SALE_DISCOUNT", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, "10", null))));
        Map<String, Object> body = new java.util.LinkedHashMap<>(cart(noBuyer(), "15", cl(w.productB(), 10)));
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("heldBillId", null);
        String id = post(s.cashier(), url(w.pharmacyId(), "sale/discount-requests"), body, 201).get("requestId").asText();
        JsonNode row = null;
        for (JsonNode r : get(owner(w), PH + "/approvals/inbox", 200).get("items")) {
            if (id.equals(r.get("subjectId").asText())) { // the inbox row names the discount request as its subject (DEC-095 item 4)
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("chip").asText()).isEqualTo("SALES");
        assertThat(row.get("subjectType").asText()).isEqualTo("SALE_DISCOUNT");
        assertThat(row.get("actions").toString()).contains("APPROVE").contains("REJECT");
        assertThat(get(s.cashier(), PH + "/approvals/inbox", 403).get("type").asText()).contains("forbidden");
    }

    // ---------------- rule 24 in the sale fields ----------------

    @Test
    void whileAProductIsOnAnOpenAuditABlindSellerSeesNoQuantityAndAnOwnerStillDoes() throws Exception {
        Sale s = saleWorld();
        Sw w = s.w();
        post(w.keeper(), url(w.pharmacyId(), "stock-audits"),
                m("clientKey", UUID.randomUUID().toString(), "scope", "PRODUCTS", "productIds", java.util.List.of(w.productB())), 201);
        JsonNode blindRow = get(w.keeper(), url(w.pharmacyId(), "sale/products?q=dressing"), 200).get("items").get(0);
        assertThat(blindRow.get("availableQty").isNull()).as("blind: no quantity").isTrue();
        assertThat(blindRow.get("outOfStock").asBoolean()).as("the chip stays").isFalse();
        JsonNode blindQuote = quote(w.keeper(), w, plain(w.productB(), 2), 200);
        assertThat(blindQuote.get("lines").get(0).get("qty").asInt()).as("the caller's own cart stays").isEqualTo(2);
        assertThat(blindQuote.get("lines").get(0).get("availableQty").isNull()).isTrue();
        assertThat(blindQuote.get("lines").get(0).get("shortBy").isNull()).isTrue();
        JsonNode ownerRow = get(owner(w), url(w.pharmacyId(), "sale/products?q=dressing"), 200).get("items").get(0);
        assertThat(ownerRow.get("availableQty").asInt()).isEqualTo(40);
        JsonNode ownerQuote = quote(owner(w), w, plain(w.productB(), 2), 200);
        assertThat(ownerQuote.get("lines").get(0).get("availableQty").asInt()).isEqualTo(40);
        JsonNode otherProduct = get(w.keeper(), url(w.pharmacyId(), "sale/products?q=" + "G-000101"), 200);
        assertThat(otherProduct.toString()).as("a product not on the audit keeps its quantity").doesNotContain("\"availableQty\":null");
    }
}
