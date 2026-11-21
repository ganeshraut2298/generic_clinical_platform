package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the SAH-80 sales API tests (docs/api/pharmacy-sales.yaml 0.3.7). The world is the stock world (a Central
 * Store, a Main Pharmacy, a pharmacist mapped at the pharmacy, a second owner) with no approval rule, a cashier mapped at the
 * pharmacy, a Ward 3B store (sale not allowed) the pharmacist is mapped at, and an opening-stock file posted into the pharmacy:
 * product A 100 in TESTB001 (MRP 45.50, expires first) and 3 in TESTB003 (MRP 150.00); product B 40 in TESTB002 (MRP 20.00);
 * an own Schedule H1 product 50 in H1B001 (MRP 60.00) and an own Schedule X product 20 in XB001 (MRP 10.00). Names, codes and
 * amounts are invented. Written, not run (the database needs recreating: Flyway checksum of V2026_12_17_1800). Test plan:
 * docs/test-plans/SAH-79.md section 11 (T80-*).
 */
abstract class Sah80SalesSupport extends Sah79StoreUseSupport {

    protected record Sale(Sw w, Pn cashier, String wardId, String h1Id, String xId) {
    }

    protected Sale saleWorld() throws Exception {
        Sw w = buyWorld();
        Map<String, Object> hb = ownBody("Test H1 tablet (fake)");
        hb.put("schedule", "H1");
        JsonNode h1 = createOwn(w.clinic(), hb);
        Map<String, Object> xb = ownBody("Test controlled tablet (fake)");
        xb.put("schedule", "X");
        JsonNode x = createOwn(w.clinic(), xb);
        String id = idOf(startImport(w.keeper().auth(), w.pharmacyId(), 5));
        addRows(w.keeper().auth(), w.pharmacyId(), id, rows(
                row(1, "PHARM", w.codeA(), "TESTB001", farExpiry(), "45.50", "30.25", "100"),
                row(2, "PHARM", w.codeB(), "TESTB002", farExpiry(), "20", "12.5", "40"),
                row(3, "PHARM", w.codeA(), "TESTB003", "12/" + (todayIst().getYear() + 4), "150.00", "100.10", "3"),
                row(4, "PHARM", h1.get("code").asText(), "H1B001", farExpiry(), "60", "40", "50"),
                row(5, "PHARM", x.get("code").asText(), "XB001", farExpiry(), "10", "5", "20")));
        submit(w.keeper().auth(), w.pharmacyId(), id);
        ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/approve", w.second().auth(), null, 200);
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        Pn cashier = crew(w.clinic(), "BILLING", "Carl Cashier");
        mapStaff(w.clinic(), w.pharmacyId(), cashier, "CASHIER");
        String ward = idOf(createStore(w.clinic(), "Ward 3B", "W3B", "WARD"));
        mapStaff(w.clinic(), ward, w.keeper(), "STORE_USER");
        return new Sale(w, cashier, ward, h1.get("productId").asText(), x.get("productId").asText());
    }

    /** Return window and refund modes of the unit (the owner's setup step). */
    protected void saleSettings(Sw w, Integer windowDays, List<String> refundModes) throws Exception {
        JsonNode u = ok(HttpMethod.GET, UNIT, w.clinic().owner(), null, 200);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", u.get("version").asLong());
        for (String k : List.of("gstin", "drugLicenceNo", "drugLicenceValidTill", "integrationMode")) {
            body.put(k, u.get(k) == null || u.get(k).isNull() ? null : u.get(k).asText());
        }
        body.put("returnWindowDays", windowDays);
        body.put("refundModes", refundModes);
        ok(HttpMethod.PUT, UNIT, w.clinic().owner(), js(body), 200);
    }

    // ---------------- carts ----------------

    protected static Map<String, Object> noBuyer() {
        return m("kind", "NONE");
    }

    protected static Map<String, Object> walkIn(String name) {
        return m("kind", "WALK_IN", "walkInName", name, "walkInPhone", null, "saveToCustomers", false);
    }

    protected static Map<String, Object> cl(String productId, int qty) {
        return m("productId", productId, "qty", qty);
    }

    @SafeVarargs
    protected static Map<String, Object> cart(Map<String, Object> buyer, String discount, Map<String, Object>... lines) {
        return m("buyer", buyer, "lines", new ArrayList<>(List.of(lines)), "discountPercent", discount);
    }

    protected static Map<String, Object> plain(String productId, int qty) {
        return cart(noBuyer(), null, cl(productId, qty));
    }

    protected static Map<String, Object> typedRx(String patientName) {
        return m("source", "TYPED", "prescriberName", "Dr Test Prescriber", "prescriberRegNo", "MH-12345",
                "prescriptionDate", todayIst().toString(), "patientName", patientName, "patientAge", 40, "patientAddress", "12 Test Road, Testville");
    }

    protected JsonNode quote(Pn who, Sw w, Map<String, Object> cart, int expected) throws Exception {
        return post(who, url(w.pharmacyId(), "sale/quote"), cart, expected);
    }

    protected Map<String, Object> billBody(JsonNode quote, Map<String, Object> cart, String mode) {
        Map<String, Object> body = new LinkedHashMap<>(cart);
        long net = quote.get("summary").get("netPayablePaise").asLong();
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("quoteVersion", quote.get("quoteVersion").asText());
        body.put("expectedNetPaise", net);
        body.put("payments", List.of(m("mode", mode, "amountPaise", net)));
        body.put("heldBillId", null);
        body.put("secondSigner", null);
        return body;
    }

    /** Quotes the cart and saves it as one payment in the mode; expects 201. */
    protected JsonNode saveBill(Pn who, Sw w, Map<String, Object> cart, String mode) throws Exception {
        JsonNode q = quote(who, w, cart, 200);
        return post(who, url(w.pharmacyId(), "bills"), billBody(q, cart, mode), 201);
    }

    protected JsonNode saveBill(Pn who, Sw w, Map<String, Object> cart) throws Exception {
        return saveBill(who, w, cart, "CASH");
    }

    protected static long serial(String billNo) {
        return Long.parseLong(billNo.substring(billNo.lastIndexOf('/') + 1));
    }

    protected Pn owner(Sw w) throws Exception {
        return ownerPn(w.clinic());
    }

    protected long billsSaved(Sw w) throws Exception {
        return count(w.clinic(), "select count(*) from pharmacy.bill");
    }
}
