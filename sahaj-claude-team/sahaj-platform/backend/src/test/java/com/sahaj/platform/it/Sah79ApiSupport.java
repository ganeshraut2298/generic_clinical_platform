package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-79 WP-D and WP-E API tests (docs/api/pharmacy.yaml 0.3.3): a clinic with a Central Store and a
 * Main Pharmacy, a pharmacist mapped at the pharmacy, a pharmacist mapped at the Central Store, a second owner and two
 * products (one adopted, one own). The clean three-row opening-stock file is submitted, approved by the second owner and
 * posted through the real API. Names, codes and quantities are invented. Test plan: docs/test-plans/SAH-79.md.
 */
abstract class Sah79ApiSupport extends Sah79Support {

    static final String REASON_ADJ = "stock-adjustment.breakage";

    /** The fixture. {@code keeper} is a pharmacist mapped at the pharmacy, {@code csUser} a pharmacist mapped at the Central Store. */
    protected record Sw(Clinic clinic, String centralId, String pharmacyId, Pn keeper, Pn csUser, Pn second, String productA, String codeA,
                        String productB, String codeB, String vendorId) {
    }

    protected Sw stockWorld() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(stores[1]), keeper, "STORE_USER");
        Pn csUser = crew(clinic, "PHARMACIST", "Chandra Central");
        mapStaff(clinic, idOf(stores[0]), csUser, "CENTRAL_STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        JsonNode adopted = adopt(clinic, "G-000101");
        JsonNode own = createOwn(clinic, ownBody("Test dressing kit (OT)"));
        JsonNode vendor = createVendor(clinic, vendorBody("Api Vendor (fake)"));
        return new Sw(clinic, idOf(stores[0]), idOf(stores[1]), keeper, csUser, second, adopted.get("productId").asText(), "G-000101",
                own.get("productId").asText(), own.get("code").asText(), idOf(vendor));
    }

    /** Submits, approves (second owner) and returns the id of the clean three-row import of the pharmacy store; not yet posted. */
    protected String approvedImport(Sw w, Pn uploader, String storeCode, String storeId) throws Exception {
        String id = idOf(draftOfCleanFile(uploader.auth(), storeCode, storeId, w.codeA(), w.codeB()));
        submit(uploader.auth(), storeId, id);
        ok(HttpMethod.POST, importUrl(storeId, id) + "/approve", w.second().auth(), null, 200);
        return id;
    }

    protected static String postUrl(Object storeId, Object importId) {
        return importUrl(storeId, importId) + "/post";
    }

    protected static String previewUrl(Object storeId, Object importId) {
        return importUrl(storeId, importId) + "/posting-preview";
    }

    protected static String clientKeyBody(Object key) {
        return "{\"clientKey\":\"" + key + "\"}";
    }

    protected JsonNode postImport(RequestPostProcessor auth, Object storeId, Object importId, UUID key) throws Exception {
        return ok(HttpMethod.POST, postUrl(storeId, importId), auth, clientKeyBody(key), 200);
    }

    /** An import approved and posted: 100 + 3 of product A and 40 of product B in the pharmacy store. */
    protected String postedImport(Sw w) throws Exception {
        String id = approvedImport(w, w.keeper(), "PHARM", w.pharmacyId());
        postImport(w.keeper().auth(), w.pharmacyId(), id, UUID.randomUUID());
        return id;
    }

    protected static String batchesUrl(Object storeId, String query) {
        return STORES + "/" + storeId + "/stock/batches" + (query == null ? "" : "?" + query);
    }

    /** The batch id of the batch number, as the owner reads it in the store (any state). */
    protected String batchId(Sw w, String storeId, String batchNo) throws Exception {
        JsonNode page = ok(HttpMethod.GET, batchesUrl(storeId, "size=100"), w.clinic().owner(), null, 200);
        for (JsonNode row : page.get("items")) {
            if (batchNo.equalsIgnoreCase(row.get("batch").get("batchNo").asText())) {
                return row.get("batch").get("batchId").asText();
            }
        }
        throw new AssertionError("No batch " + batchNo);
    }

    protected static String adjustmentsUrl(Object storeId) {
        return STORES + "/" + storeId + "/stock-adjustments";
    }

    protected static Map<String, Object> adjustmentBody(UUID clientKey, String batchId, int bookSeen, int physical, String reasonKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", clientKey.toString());
        m.put("batchId", batchId);
        m.put("bookQtySeen", bookSeen);
        m.put("physicalQty", physical);
        m.put("reasonKey", reasonKey);
        m.put("note", null);
        m.put("correctsLedgerId", null);
        m.put("secondSigner", null);
        return m;
    }

    protected static Map<String, Object> signer(Pn person, String pin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("membershipId", person.membershipId().toString());
        m.put("pin", pin);
        return m;
    }

    /** The version number of the saved approval rules (0 before the first save). */
    protected long rulesVersion(Sw w) throws Exception {
        return ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200).get("versionNo").asLong();
    }

    /** Saves the rules: the types of the override as given, every other type with no level; the base is the current version. */
    protected void putRules(Sw w, Map<String, Map<String, Object>> override) throws Exception {
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(rulesVersion(w), null, override)), 200);
    }

    /** Saves the approval rules with no level for any type (so a transaction posts or is approved at once), as the owner. */
    protected void noApprovalRules(Sw w) throws Exception {
        putRules(w, Map.of());
    }

    /** Saves the rules so that each named type needs the owner always (level 1); every other type has no level. */
    protected void ownerAlways(Sw w, String... types) throws Exception {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String type : types) {
            m.put(type, rule(type, null, null, always(1, "OWNER")));
        }
        putRules(w, m);
    }

    protected String decisionBody(UUID key, int level, long requestVersion, String decision, String reasonKey) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", key.toString());
        m.put("level", level);
        m.put("requestVersion", requestVersion);
        m.put("decision", decision);
        m.put("reasonKey", reasonKey);
        m.put("note", null);
        m.put("indentLines", null);
        m.put("damageRejectOutcome", null);
        return js(m);
    }

    protected static String decisionUrl(Object requestId) {
        return PH + "/approvals/" + requestId + "/decision";
    }

    /** Sets one store's reorder level of a product (committed through the local settings API). */
    protected void reorderLevel(Sw w, String productId, String storeId, int level) throws Exception {
        JsonNode product = getProduct(w.clinic(), productId);
        Map<String, Object> body = localBody(product);
        settings(body, setting(storeId, level, null));
        ok(HttpMethod.PUT, localUrl(productId), w.clinic().owner(), js(body), 200);
    }

    protected long ledgerSum(Sw w, String sql) throws Exception {
        return db().inTenant(w.clinic().tenantId(), c -> number(c, sql));
    }
}
