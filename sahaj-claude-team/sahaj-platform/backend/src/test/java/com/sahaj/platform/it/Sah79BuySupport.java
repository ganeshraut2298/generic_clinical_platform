package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the SAH-79 WP-E receipt, debit-note and round 6 tests (pharmacy.yaml 0.3.4). The world is the stock world
 * (a Central Store, a Main Pharmacy, a pharmacist at each, a second owner, product A (box of 10, GST 12 percent) and product
 * B (own, box of 10, GST 12 percent)) with no approval rule, a vendor with the three credit levels that supplies the Central
 * Store, and helpers that build a purchase order up to SENT, a receipt with its invoice total, and the post. Names, codes and
 * amounts are invented. Test plan: docs/test-plans/SAH-79.md (T79-7-*, T79-8-*).
 */
abstract class Sah79BuySupport extends Sah79ApiSupport {

    protected static String grns(Object storeId) {
        return STORES + "/" + storeId + "/goods-receipts";
    }

    protected static String dns(Object storeId) {
        return STORES + "/" + storeId + "/debit-notes";
    }

    protected static String pos(Object storeId) {
        return STORES + "/" + storeId + "/purchase-orders";
    }

    /** The expiry month of a batch that is near expiry (this month, so a vendor return of it is allowed) as YYYY-MM. */
    protected static String thisMonth() {
        return todayIst().toString().substring(0, 7);
    }

    protected static String farMonth() {
        return todayIst().plusYears(3).toString().substring(0, 7);
    }

    /** A vendor that supplies the Central Store with credit levels 80 (standard), 60 (damaged) and 70 (expired). */
    protected String buyVendor(Sw w, String name) throws Exception {
        Map<String, Object> body = vendorBody(name);
        body.put("suppliesToStoreIds", List.of(w.centralId()));
        body.put("returnCreditPercent", "80");
        body.put("damagedCreditPercent", "60");
        body.put("expiredCreditPercent", "70");
        return idOf(createVendor(w.clinic(), body));
    }

    protected Sw buyWorld() throws Exception {
        Sw w = stockWorld();
        noApprovalRules(w);
        return w;
    }

    // ---------------- purchase order up to SENT ----------------

    protected Map<String, Object> poLine(String productId, int qty, String rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("qty", qty);
        m.put("rate", rate);
        return m;
    }

    /** A purchase order of the Central Store, submitted (no rule: APPROVED) and marked sent; returns the SENT order. */
    protected JsonNode sentOrder(Sw w, String vendor, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientKey", UUID.randomUUID().toString());
        body.put("vendorId", vendor);
        body.put("deliveryExpected", todayIst().plusDays(7).toString());
        body.put("creditDays", 30);
        body.put("lines", lines);
        JsonNode draft = ok(HttpMethod.POST, pos(w.centralId()), w.csUser().auth(), js(body), 201);
        Map<String, Object> submit = new LinkedHashMap<>();
        submit.put("version", draft.get("version").asLong());
        submit.put("clientKey", UUID.randomUUID().toString());
        JsonNode approved = ok(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(draft) + "/submit", w.csUser().auth(), js(submit), 200);
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("version", approved.get("version").asLong());
        sent.put("sentOn", todayIst().toString());
        return ok(HttpMethod.POST, pos(w.centralId()) + "/" + idOf(approved) + "/mark-sent", w.csUser().auth(), js(sent), 200);
    }

    protected JsonNode getOrder(Sw w, String poId) throws Exception {
        return ok(HttpMethod.GET, pos(w.centralId()) + "/" + poId, w.csUser().auth(), null, 200);
    }

    // ---------------- receipts ----------------

    protected Map<String, Object> gline(String productId, String batchNo, String expiryMonth, int qty, int billed, int free, String ptr, String mrp,
                                        String poLineId, String shortAction, boolean claim) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purchaseOrderLineId", poLineId);
        m.put("productId", productId);
        m.put("batchNo", batchNo);
        m.put("expiryMonth", expiryMonth);
        m.put("qty", qty);
        m.put("billedQty", billed);
        m.put("freeQty", free);
        m.put("ptr", ptr);
        m.put("mrp", mrp);
        m.put("discountPercent", "0");
        m.put("claimRateDifference", claim);
        m.put("shortAction", shortAction);
        return m;
    }

    /** A plain line: received equals billed, no free quantity, no PO line. */
    protected Map<String, Object> gline(String productId, String batchNo, String expiryMonth, int qty, String ptr, String mrp) {
        return gline(productId, batchNo, expiryMonth, qty, qty, 0, ptr, mrp, null, null, false);
    }

    protected Map<String, Object> createBody(UUID key, String poId, String vendor, String invoiceNo, String invoiceDate, int creditDays, long total,
                                             List<Map<String, Object>> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("purchaseOrderId", poId);
        m.put("vendorId", vendor);
        m.put("invoiceNo", invoiceNo);
        m.put("invoiceDate", invoiceDate);
        m.put("creditDays", creditDays);
        m.put("invoiceTotalPaise", total);
        m.put("lines", lines);
        return m;
    }

    protected Map<String, Object> updateBody(JsonNode grn, long total, List<Map<String, Object>> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", grn.get("version").asLong());
        m.put("vendorId", grn.get("vendor").get("vendorId").asText());
        m.put("invoiceNo", grn.get("invoiceNo").asText());
        m.put("invoiceDate", grn.get("invoiceDate").asText());
        m.put("creditDays", grn.get("creditDays").asInt());
        m.put("invoiceTotalPaise", total);
        m.put("lines", lines);
        return m;
    }

    /** A saved draft whose invoice total equals the computed total of its lines (so the post's total check passes). */
    protected JsonNode draftReceipt(Sw w, String poId, String vendor, String invoiceNo, String invoiceDate, int creditDays, List<Map<String, Object>> lines)
            throws Exception {
        JsonNode first = ok(HttpMethod.POST, grns(w.centralId()), w.csUser().auth(),
                js(createBody(UUID.randomUUID(), poId, vendor, invoiceNo, invoiceDate, creditDays, 0, lines)), 201);
        long computed = first.get("computedTotalPaise").asLong();
        return ok(HttpMethod.PUT, grns(w.centralId()) + "/" + idOf(first), w.csUser().auth(), js(updateBody(first, computed, lines)), 200);
    }

    protected JsonNode draftReceipt(Sw w, String vendor, String invoiceNo, List<Map<String, Object>> lines) throws Exception {
        return draftReceipt(w, null, vendor, invoiceNo, todayIst().toString(), 30, lines);
    }

    protected Map<String, Object> postBody(JsonNode grn, Map<String, Object> signer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("version", grn.get("version").asLong());
        m.put("secondSigner", signer);
        return m;
    }

    protected JsonNode post(Sw w, Pn who, JsonNode grn, Map<String, Object> signer, int expected) throws Exception {
        String url = grns(w.centralId()) + "/" + idOf(grn) + "/post";
        return expected == 200 ? ok(HttpMethod.POST, url, who.auth(), js(postBody(grn, signer)), 200)
                : problem(call(HttpMethod.POST, url, who.auth(), js(postBody(grn, signer))), expected);
    }

    protected JsonNode getGrn(Sw w, Pn who, String id) throws Exception {
        return ok(HttpMethod.GET, grns(w.centralId()) + "/" + id, who.auth(), null, 200);
    }

    /** A receipt of the lines, drafted and posted by the Central Store pharmacist. */
    protected JsonNode postedReceipt(Sw w, String vendor, String invoiceNo, String invoiceDate, int creditDays, List<Map<String, Object>> lines)
            throws Exception {
        JsonNode draft = draftReceipt(w, null, vendor, invoiceNo, invoiceDate, creditDays, lines);
        return post(w, w.csUser(), draft, null, 200);
    }

    // ---------------- stock reads (SQL, tenant bound) ----------------

    protected long stockOf(Sw w, String batchNo, String state) throws Exception {
        return count(w.clinic(), "select coalesce(sum(b.qty), 0) from pharmacy.stock_balance b join pharmacy.batch t on t.id = b.batch_id "
                + "where t.batch_no = ? and b.state = ?", batchNo, state);
    }

    protected long ledgerQty(Sw w, String type) throws SQLException {
        return count(w.clinic(), "select coalesce(sum(qty_in), 0) from pharmacy.stock_ledger where txn_type = ?", type);
    }

    protected long ledgerOut(Sw w, String type) throws SQLException {
        return count(w.clinic(), "select coalesce(sum(qty_out), 0) from pharmacy.stock_ledger where txn_type = ?", type);
    }

    /** Every unit of the clinic in the balances equals every unit in the ledger (in less out): nothing moved without a line. */
    protected void assertLedgerEqualsBalances(Sw w) throws Exception {
        long balances = count(w.clinic(), "select coalesce(sum(qty), 0) from pharmacy.stock_balance");
        long ledger = count(w.clinic(), "select coalesce(sum(qty_in - qty_out), 0) from pharmacy.stock_ledger");
        org.assertj.core.api.Assertions.assertThat(balances).as("balances equal the ledger").isEqualTo(ledger);
        org.assertj.core.api.Assertions.assertThat(count(w.clinic(), "select count(*) from pharmacy.stock_balance where qty < 0")).isZero();
    }

    protected long payableOpen(Sw w) throws Exception {
        return count(w.clinic(), "select coalesce(sum(amount_paise - credited_paise), 0) from pharmacy.vendor_payable");
    }

    /** The payables equal their entries: the invoice entries less the credit note entries. */
    protected void assertPayablesEqualEntries(Sw w) throws Exception {
        long entries = count(w.clinic(), "select coalesce(sum(amount_paise), 0) from pharmacy.vendor_payable_entry");
        org.assertj.core.api.Assertions.assertThat(payableOpen(w)).as("open payable equals the entries").isEqualTo(entries);
    }
}
