package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;

/**
 * Shared set-up of the SAH-79 WP-G and WP-H API tests (store use, transfers, consumption, stock audit, reports;
 * pharmacy.yaml 0.3.6). The world is the buy world (a Central Store, a Main Pharmacy, a pharmacist at each, a second owner,
 * product A and product B, no approval rule) with the clean opening-stock file posted into the Main Pharmacy (product A:
 * 100 in TESTB001 and 3 in TESTB003; product B: 40 in TESTB002) and into a Ward 3B store (the same rows), the pharmacist
 * mapped at the ward too. Names, codes and amounts are invented. Test plan: docs/test-plans/SAH-79.md section 9.
 */
abstract class Sah79StoreUseSupport extends Sah79BuySupport {

    protected static final String REASON_RETURN = "store-return.excess-stock";
    protected static final String REASON_DAMAGE = "damage.damaged-leaking";
    protected static final String REASON_TRANSFER = "transfer.urgent-shortage";
    protected static final String PURPOSE = "consumption-purpose.ward-general-use";
    protected static final String REJECT_OTHER = "approval-reject.other";

    protected record Uw(Sw w, String wardId) {
    }

    /** The buy world, stock in the pharmacy and the ward, the keeper mapped at both. */
    protected Uw useWorld() throws Exception {
        Sw w = buyWorld();
        postImport(w.keeper().auth(), w.pharmacyId(), approvedImport(w, w.keeper(), "PHARM", w.pharmacyId()), UUID.randomUUID());
        String ward = idOf(createStore(w.clinic(), "Ward 3B", "W3B", "WARD"));
        mapStaff(w.clinic(), ward, w.keeper(), "STORE_USER");
        postImport(w.keeper().auth(), ward, approvedImport(w, w.keeper(), "W3B", ward), UUID.randomUUID());
        return new Uw(w, ward);
    }

    protected static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    protected static Map<String, Object> pl(String productId, int qty) {
        return m("productId", productId, "quantity", qty);
    }

    /** Units of a batch of a store in a state. */
    protected long bal(Sw w, String storeId, String batchNo, String state) throws Exception {
        return count(w.clinic(), "select coalesce(sum(b.qty), 0) from pharmacy.stock_balance b join pharmacy.batch t on t.id = b.batch_id "
                + "where b.store_id = ?::uuid and t.batch_no = ? and b.state = ?", storeId, batchNo, state);
    }

    protected long ledger(Sw w, String type) throws Exception {
        return count(w.clinic(), "select count(*) from pharmacy.stock_ledger where txn_type = ?", type);
    }

    protected static String url(Object storeId, String tail) {
        return STORES + "/" + storeId + "/" + tail;
    }

    protected JsonNode post(Pn who, String path, Map<String, Object> body, int expected) throws Exception {
        return expected < 300 ? ok(HttpMethod.POST, path, who.auth(), js(body), expected)
                : problem(call(HttpMethod.POST, path, who.auth(), js(body)), expected);
    }

    protected JsonNode get(Pn who, String path, int expected) throws Exception {
        return expected < 300 ? ok(HttpMethod.GET, path, who.auth(), null, expected)
                : problem(call(HttpMethod.GET, path, who.auth(), null), expected);
    }

    /** The first field error message of a problem body, or "" (never a null). */
    protected static String firstMessage(JsonNode problem) {
        JsonNode errors = problem.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            JsonNode e = errors.get(0);
            for (String k : List.of("message", "detail")) {
                if (e.get(k) != null) {
                    return e.get(k).asText();
                }
            }
        }
        return problem.get("detail") == null ? "" : problem.get("detail").asText();
    }

    protected static boolean mentions(JsonNode problem, String text) {
        return problem.toString().contains(text);
    }

    // ---------------- documents ----------------

    protected JsonNode storeReturn(Uw u, Pn who, String batchNo, int qty, int expected) throws Exception {
        String batch = batchId(u.w(), u.w().pharmacyId(), batchNo);
        return post(who, url(u.w().pharmacyId(), "store-returns"),
                m("clientKey", UUID.randomUUID().toString(), "batchId", batch, "quantity", qty, "reasonKey", REASON_RETURN, "note", null), expected);
    }

    protected Map<String, Object> damageBody(String batchId, String fromState, int qty, String after) {
        return m("clientKey", UUID.randomUUID().toString(), "batchId", batchId, "fromState", fromState, "quantity", qty,
                "reasonKey", REASON_DAMAGE, "note", null, "afterApproval", after);
    }

    protected JsonNode damage(Uw u, Pn who, String batchNo, int qty, String after, int expected) throws Exception {
        String batch = batchId(u.w(), u.w().pharmacyId(), batchNo);
        return post(who, url(u.w().pharmacyId(), "damage-reports"), damageBody(batch, "AVAILABLE", qty, after), expected);
    }

    protected JsonNode transfer(Uw u, Pn who, String productId, int qty, int expected) throws Exception {
        return post(who, url(u.w().pharmacyId(), "transfers"), m("clientKey", UUID.randomUUID().toString(), "fromStoreId", u.w().pharmacyId(),
                "toStoreId", u.wardId(), "reasonKey", REASON_TRANSFER, "note", null, "lines", List.of(pl(productId, qty))), expected);
    }

    protected Map<String, Object> consumeBody(String purpose, List<Map<String, Object>> lines) {
        return m("clientKey", UUID.randomUUID().toString(), "type", "GENERAL", "purposeKey", purpose, "note", null, "admissionId", null,
                "orderedBy", null, "stockAuditLineId", null, "lines", lines, "secondSigner", null);
    }

    protected Map<String, Object> decision(int level, long version, String decision, String reason, String damageOutcome) {
        return m("clientKey", UUID.randomUUID().toString(), "level", level, "requestVersion", version, "decision", decision,
                "reasonKey", reason, "note", null, "indentLines", null, "damageRejectOutcome", damageOutcome);
    }

    protected JsonNode decide(Pn who, JsonNode approval, String decision, String reason, String damageOutcome, int expected) throws Exception {
        return post(who, decisionUrl(approval.get("requestId").asText()),
                decision(approval.get("levelNo").asInt(), approval.get("requestVersion").asLong(), decision, reason, damageOutcome), expected);
    }
}
