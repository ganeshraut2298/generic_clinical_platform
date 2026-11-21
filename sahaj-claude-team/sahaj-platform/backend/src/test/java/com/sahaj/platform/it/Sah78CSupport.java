package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-78 package C integration tests (78-8 vendors, 78-9 approval rules, 78-10 numbering, reasons and print
 * templates) and of the hardening round (DEC-076, DEC-077, DEC-078). Urls, field names and texts are copied from
 * docs/api/pharmacy.yaml 0.2.0 and 0.2.1, never read from the application. All names, GSTINs and licence numbers are invented.
 */
abstract class Sah78CSupport extends Sah78BSupport {

    static final String VENDORS = PH + "/vendors";
    static final String RULES_URL = PH + "/approval-rules";
    static final String PRESET_URL = RULES_URL + "/preset";
    static final String VERSIONS_URL = RULES_URL + "/versions";
    static final String SERIES_URL = PH + "/document-series";
    static final String REASON_LISTS = PH + "/reason-lists";
    static final String TEMPLATES = PH + "/print-templates";

    static final String[] TRANSACTION_TYPES = {"PURCHASE_ORDER", "GRN_RATE_RISE", "VENDOR_RETURN", "INDENT", "STOCK_TRANSFER",
        "WRITE_OFF", "STOCK_ADJUSTMENT", "SALE_DISCOUNT", "SALES_RETURN"};

    static final String[] LIST_KEYS = {"store-return", "sales-return", "damage", "stock-adjustment", "receipt-reject", "cs-change",
        "approval-reject", "transfer", "consumption-purpose", "dispute", "vendor-return",
        "substitution", "allergy-override", "h1-override", "return-condition", "cancel-bill", "ward-return", "rx-closed", "clearance",
        "day-close-difference"};

    static final String[] DOC_TYPES = {"PURCHASE_ORDER", "GRN", "INDENT", "TRANSFER", "BILL", "DEBIT_NOTE", "STOCK_AUDIT",
        "STORE_RETURN", "DAMAGE_REPORT", "STOCK_ADJUSTMENT"};

    // ---------------- vendors ----------------

    /** A complete VendorInput of the contract (a made-up vendor with a valid licence, supplying the Central Store). */
    protected static Map<String, Object> vendorBody(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("name", name);
        m.put("gstin", "27AAAAA0000A1Z5");
        m.put("licenceRequired", true);
        m.put("licenceNo", "TEST-DL-0002");
        m.put("licenceValidTill", (todayIst().getYear() + 2) + "-03-31");
        m.put("contactName", "Test Contact");
        m.put("contactPhone", "9000000001");
        m.put("creditDays", 30);
        m.put("returnDaysBeforeExpiry", 90);
        m.put("returnCreditPercent", "100");
        m.put("suppliesToStoreIds", new ArrayList<String>());
        return m;
    }

    /** The PUT body built from what GET answered (the editable fields and the version). */
    protected static Map<String, Object> vendorUpdate(JsonNode v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.get("version").asLong());
        m.put("name", v.get("name").asText());
        m.put("gstin", text(v, "gstin"));
        m.put("licenceRequired", v.get("licenceRequired").asBoolean());
        m.put("licenceNo", text(v, "licenceNo"));
        m.put("licenceValidTill", text(v, "licenceValidTill"));
        m.put("contactName", text(v, "contactName"));
        m.put("contactPhone", text(v, "contactPhone"));
        m.put("creditDays", v.get("creditDays").asInt());
        m.put("returnDaysBeforeExpiry", v.get("returnDaysBeforeExpiry").isNull() ? null : v.get("returnDaysBeforeExpiry").asInt());
        m.put("returnCreditPercent", text(v, "returnCreditPercent"));
        List<String> stores = new ArrayList<>();
        v.get("suppliesTo").forEach(s -> stores.add(s.get("storeId").asText()));
        m.put("suppliesToStoreIds", stores);
        return m;
    }

    protected static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    protected JsonNode createVendor(Clinic clinic, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, VENDORS, clinic.owner(), js(body), 201);
    }

    protected static String vendorUrl(Object id) {
        return VENDORS + "/" + id;
    }

    /** A clinic with the module on, a Central Store and a Pharmacy store. */
    protected record Masters(Clinic clinic, String centralId, String pharmacyId) {
    }

    protected Masters mastersClinic() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        return new Masters(clinic, idOf(stores[0]), idOf(stores[1]));
    }

    // ---------------- approval rules ----------------

    /** One rule of the PUT body: levels as {level, approver, kind, amountPaise, percent, storeTypes}. */
    @SafeVarargs
    protected static Map<String, Object> rule(String type, Object escalateHours, Object urgentMinutes, Map<String, Object>... levels) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionType", type);
        m.put("levels", List.of(levels));
        m.put("escalateAfterHours", escalateHours);
        m.put("urgentEscalateAfterMinutes", urgentMinutes);
        return m;
    }

    protected static Map<String, Object> level(int no, String approver, String kind, Object amountPaise, Object percent, Object storeTypes) {
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("kind", kind);
        cond.put("amountPaise", amountPaise);
        cond.put("percent", percent);
        cond.put("storeTypes", storeTypes);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", no);
        m.put("approver", approver);
        m.put("condition", cond);
        return m;
    }

    protected static Map<String, Object> always(int no, String approver) {
        return level(no, approver, "ALWAYS", null, null, null);
    }

    /** The nine rows of the PUT body, one rule per type; a type with no entry in {@code override} has no level. */
    protected static Map<String, Object> rulesBody(long base, Object highValue, Map<String, Map<String, Object>> override) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String type : TRANSACTION_TYPES) {
            rules.add(override.containsKey(type) ? override.get(type) : rule(type, null, null));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseVersionNo", base);
        m.put("highValueThresholdPaise", highValue);
        m.put("rules", rules);
        return m;
    }

    protected static Map<String, Map<String, Object>> only(Map<String, Object> rule) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put((String) rule.get("transactionType"), rule);
        return m;
    }

    protected static Map<String, Object> limit(String type, Object amountPaise, Object percent, boolean noAmount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionType", type);
        m.put("amountPaise", amountPaise);
        m.put("percent", percent);
        m.put("noAmountCondition", noAmount);
        return m;
    }

    protected static Map<String, Object> presetBody(long base, String preset, List<Map<String, Object>> limits) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseVersionNo", base);
        m.put("preset", preset);
        m.put("limits", limits);
        return m;
    }

    protected static JsonNode ruleOf(JsonNode rules, String type) {
        for (JsonNode r : rules.get("rules")) {
            if (type.equals(r.get("transactionType").asText())) {
                return r;
            }
        }
        return null;
    }

    // ---------------- numbering, reasons, templates ----------------

    protected static Map<String, Object> seriesBody(long version, String prefix, boolean fy, int length, boolean restarts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("prefix", prefix);
        m.put("financialYearPart", fy);
        m.put("sequenceLength", length);
        m.put("restartsEachFinancialYear", restarts);
        return m;
    }

    protected static JsonNode seriesOf(JsonNode list, String docType) {
        for (JsonNode s : list.get("items")) {
            if (docType.equals(s.get("docType").asText())) {
                return s;
            }
        }
        return null;
    }

    protected static JsonNode reasonList(JsonNode lists, String listKey) {
        for (JsonNode l : lists.get("lists")) {
            if (listKey.equals(l.get("listKey").asText())) {
                return l;
            }
        }
        return null;
    }

    protected static JsonNode reasonOf(JsonNode lists, String listKey, String reasonKey) {
        for (JsonNode r : reasonList(lists, listKey).get("reasons")) {
            if (reasonKey.equals(r.get("reasonKey").asText())) {
                return r;
            }
        }
        return null;
    }

    protected static Map<String, Object> templateBody(long version, List<String> lines, String footer, String paper) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("headerLines", lines);
        m.put("footerNote", footer);
        m.put("paperSize", paper);
        return m;
    }

    /**
     * Takes one document number of the type the way the posting code of SAH-79 will: as sahaj_app with the tenant bound, through
     * pharmacy.next_document_no(), committed. The unit row must exist.
     */
    protected long issueNumber(Clinic clinic, String docType) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            long n = DbAccess.count(c, "select pharmacy.next_document_no(?)", docType);
            c.commit();
            return n;
        }
    }

    // ---------------- a whole-day callers table ----------------

    /** Owner, pharmacist, store staff mapped to a store, nurse, front desk, billing: the non-owner callers of a setup write. */
    protected Map<String, RequestPostProcessor> nonOwners(Masters w) throws Exception {
        Map<String, RequestPostProcessor> callers = new LinkedHashMap<>();
        Pn pharmacist = crew(w.clinic(), "PHARMACIST");
        callers.put("PHARMACIST", pharmacist.auth());
        Pn storeStaff = crew(w.clinic(), "STORE_STAFF");
        mapStaff(w.clinic(), w.centralId(), storeStaff, "CENTRAL_STORE_USER");
        callers.put("CENTRAL_STORE_USER", storeStaff.auth());
        callers.put("DOCTOR", crew(w.clinic(), "DOCTOR").auth());
        callers.put("NURSE", crew(w.clinic(), "NURSE").auth());
        callers.put("FRONT_DESK", crew(w.clinic(), "FRONT_DESK").auth());
        callers.put("BILLING", crew(w.clinic(), "BILLING").auth());
        return callers;
    }
}
