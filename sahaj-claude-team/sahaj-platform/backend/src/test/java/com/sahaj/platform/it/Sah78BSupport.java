package com.sahaj.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-78 WP-B integration tests (78-5 products, 78-6 own products, 78-7 opening stock) on the real
 * {@code sahaj_test} database. Urls, field names and texts are copied from docs/api/pharmacy.yaml 0.1.2, never read from the
 * application. Vendors have no API in WP-B (WP-C), so a vendor is a committed row written as {@code sahaj_app} with the tenant
 * bound, the way the design note describes the table. All names, codes and batch numbers are invented.
 */
abstract class Sah78BSupport extends Sah78Support {

    static final String PRODUCTS = PH + "/products";
    static final String DUPLICATES = PRODUCTS + "/duplicates";
    static final String OPTIONS = PH + "/product-options";
    static final String TEMPLATE = PH + "/opening-stock/template";
    static final String ALL_IMPORTS = PH + "/opening-stock-imports";

    static final String KEY_REUSED_TEXT = "This request was already sent by someone else. Reload the page and try again.";
    static final String HEADER_TEXT = "Use the Sahaj template. Save the file as CSV.";
    static final List<String> HEADER = List.of("store code", "product code", "batch", "expiry date", "MRP", "purchase rate", "quantity");

    /** A date that is always in the future (IST), written the way the template shows it. */
    protected static String farExpiry() {
        return "31-12-" + (todayIst().getYear() + 3);
    }

    protected static String ddMmYyyy(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }

    // ---------------- catalogue and products ----------------

    protected UUID catalogueId(String code) throws SQLException {
        return db().asApp(c -> UUID.fromString(DbAccess.text(c, "select id::text from pharmacy.catalogue_product where code = ?", code)));
    }

    protected static String adoptUrl(Object catalogueProductId) {
        return PH + "/catalogue-products/" + catalogueProductId + "/adopt";
    }

    protected static String productUrl(Object productId) {
        return PRODUCTS + "/" + productId;
    }

    protected static String localUrl(Object productId) {
        return PRODUCTS + "/" + productId + "/local-settings";
    }

    /** Adopts a sample catalogue product as the owner (201) and returns the unit product. */
    protected JsonNode adopt(Clinic clinic, String catalogueCode) throws Exception {
        return ok(HttpMethod.POST, adoptUrl(catalogueId(catalogueCode)), clinic.owner(), null, 201);
    }

    /** A complete own-product body of the contract (OwnProductInput), a made-up product of an unusual salt (no catalogue match). */
    protected static Map<String, Object> ownBody(String name, String salt, String strength, String form) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", UUID.randomUUID().toString());
        m.put("name", name);
        m.put("salt", salt);
        m.put("strength", strength);
        m.put("form", form);
        m.put("purchaseUnit", "Box");
        m.put("issueUnit", "piece");
        m.put("conversion", 10);
        m.put("manufacturer", null);
        m.put("madeInHouse", true);
        m.put("hsn", "3005");
        m.put("gstRate", "12");
        m.put("schedule", "NONE");
        m.put("barcode", null);
        m.put("ignoreDuplicates", false);
        return m;
    }

    /** Own product with a salt that is unique to the call, so no catalogue row or other product matches it. */
    protected static Map<String, Object> ownBody(String name) {
        String salt = "Zq" + UUID.randomUUID().toString().replaceAll("[^a-f]", "").toLowerCase();
        return ownBody(name, salt.isEmpty() ? "Zqx" : salt + "x", "5 mg", "tablet");
    }

    protected JsonNode createOwn(Clinic clinic, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body), 201);
    }

    /** The PUT body of an own product built from what GET answered (version and the editable fields). */
    protected static Map<String, Object> updateBody(JsonNode product) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", product.get("version").asLong());
        m.put("name", product.get("name").asText());
        m.put("salt", product.get("salt").asText());
        m.put("strength", product.get("strength").asText());
        m.put("form", product.get("form").asText());
        m.put("purchaseUnit", product.get("purchaseUnit").asText());
        m.put("issueUnit", product.get("issueUnit").asText());
        m.put("conversion", product.get("conversion").asInt());
        m.put("manufacturer", product.get("manufacturer").isNull() ? null : product.get("manufacturer").asText());
        m.put("madeInHouse", product.get("madeInHouse").asBoolean());
        m.put("hsn", product.get("hsn").asText());
        m.put("gstRate", product.get("gstRate").asText());
        m.put("schedule", product.get("schedule").asText());
        JsonNode barcode = product.get("localSettings").get("barcode");
        m.put("barcode", barcode.isNull() ? null : barcode.asText());
        m.put("ignoreDuplicates", false);
        return m;
    }

    /** The PUT body of the local settings built from what GET answered: nothing changes until the test edits the map. */
    protected static Map<String, Object> localBody(JsonNode product) {
        JsonNode local = product.get("localSettings");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", product.get("version").asLong());
        List<Map<String, Object>> settings = new ArrayList<>();
        m.put("storeSettings", settings);
        m.put("preferredVendorId", local.get("preferredVendor").isNull() ? null : local.get("preferredVendor").get("id").asText());
        m.put("alias", local.get("alias").isNull() ? null : local.get("alias").asText());
        m.put("barcode", local.get("barcode").isNull() ? null : local.get("barcode").asText());
        m.put("returnable", local.get("returnable").asBoolean());
        m.put("active", local.get("active").asBoolean());
        return m;
    }

    protected static Map<String, Object> setting(Object storeId, Object reorderLevel, Object rack) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("storeId", storeId.toString());
        m.put("reorderLevel", reorderLevel);
        m.put("rack", rack);
        return m;
    }

    @SuppressWarnings("unchecked")
    protected static void settings(Map<String, Object> body, Map<String, Object>... entries) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("storeSettings");
        list.clear();
        for (Map<String, Object> e : entries) {
            list.add(e);
        }
    }

    protected JsonNode getProduct(Clinic clinic, Object productId) throws Exception {
        return ok(HttpMethod.GET, productUrl(productId), clinic.owner(), null, 200);
    }

    protected JsonNode settingOf(JsonNode product, Object storeId) {
        for (JsonNode s : product.get("localSettings").get("storeSettings")) {
            if (storeId.toString().equals(s.get("storeId").asText())) {
                return s;
            }
        }
        return null;
    }

    /** A vendor row (WP-C has the API), committed as sahaj_app with the tenant bound. */
    protected UUID insertVendor(Clinic clinic, String name, boolean licenceRequired, LocalDate licenceValidTill, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        UUID unit = UUID.fromString(values(clinic, "select id::text from pharmacy.unit").get(0));
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, clinic.tenantId().toString());
                bind.execute();
            }
            DbAccess.execute(c, "insert into pharmacy.vendor (id, tenant_id, unit_id, name, licence_required, licence_no, licence_valid_till, "
                    + "status, created_by) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::date, ?, ?::uuid)",
                    id.toString(), clinic.tenantId().toString(), unit.toString(), name, licenceRequired,
                    licenceRequired ? "TEST-LIC-" + id.toString().substring(0, 6) : null,
                    licenceValidTill == null ? null : licenceValidTill.toString(), status, clinic.ownerUserId().toString());
            c.commit();
        }
        return id;
    }

    /** The unit row must exist before a vendor is inserted: reading the unit settings creates nothing, saving does. */
    protected void ensureUnit(Clinic clinic) throws Exception {
        if (pharmacyRows(clinic, "unit") == 0) {
            ok(HttpMethod.PUT, UNIT, clinic.owner(),
                    "{\"version\":0,\"gstin\":null,\"drugLicenceNo\":null,\"drugLicenceValidTill\":null,\"integrationMode\":\"INTEGRATED\"}", 200);
        }
    }

    protected String unitCode(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, UNIT, clinic.owner(), null, 200).get("unitCode").asText();
    }

    /** Sets has_stock on a product directly (what approving an opening-stock import does), committed with the tenant bound. */
    protected void giveStock(Clinic clinic, Object productId) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update pharmacy.product set has_stock = true where id = ?::uuid", productId.toString());
    }

    // ---------------- opening stock ----------------

    protected static String importsUrl(Object storeId) {
        return STORES + "/" + storeId + "/opening-stock-imports";
    }

    protected static String importUrl(Object storeId, Object importId) {
        return importsUrl(storeId) + "/" + importId;
    }

    protected static Map<String, Object> startBody(UUID clientKey, int rowCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientKey", clientKey.toString());
        m.put("columns", HEADER);
        m.put("rowCount", rowCount);
        return m;
    }

    protected JsonNode startImport(RequestPostProcessor auth, Object storeId, int rowCount) throws Exception {
        return ok(HttpMethod.POST, importsUrl(storeId), auth, js(startBody(UUID.randomUUID(), rowCount)), 201);
    }

    protected static Map<String, Object> row(int rowNo, String storeCode, String productCode, String batch, String expiry, String mrp,
                                             String purchaseRate, String quantity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowNo", rowNo);
        m.put("storeCode", storeCode);
        m.put("productCode", productCode);
        m.put("batch", batch);
        m.put("expiryDate", expiry);
        m.put("mrp", mrp);
        m.put("purchaseRate", purchaseRate);
        m.put("quantity", quantity);
        return m;
    }

    protected static Map<String, Object> rows(Map<String, Object>... rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", List.of(rows));
        return m;
    }

    protected JsonNode addRows(RequestPostProcessor auth, Object storeId, Object importId, Map<String, Object> body) throws Exception {
        return ok(HttpMethod.POST, importUrl(storeId, importId) + "/lines", auth, js(body), 200);
    }

    protected JsonNode submit(RequestPostProcessor auth, Object storeId, Object importId) throws Exception {
        return ok(HttpMethod.POST, importUrl(storeId, importId) + "/submit", auth, null, 200);
    }

    protected JsonNode getImport(RequestPostProcessor auth, Object storeId, Object importId) throws Exception {
        return ok(HttpMethod.GET, importUrl(storeId, importId), auth, null, 200);
    }

    /** One import of the clean three-row file used by most flows (batches TESTB001 to 3), started, sent and NOT yet submitted. */
    protected JsonNode draftOfCleanFile(RequestPostProcessor auth, String storeCode, Object storeId, String codeA, String codeB)
            throws Exception {
        JsonNode started = startImport(auth, storeId, 3);
        Object id = idOf(started);
        addRows(auth, storeId, id, rows(
                row(1, storeCode, codeA, "TESTB001", farExpiry(), "45.50", "30.25", "100"),
                row(2, storeCode, codeB, "TESTB002", farExpiry(), "20", "12.5", "40"),
                row(3, storeCode, codeA, "TESTB003", "12/" + (todayIst().getYear() + 4), "150.00", "100.10", "3")));
        return started;
    }

    /** Total of the clean file in paise: 100 x 30.25 + 40 x 12.50 + 3 x 100.10 = 3,025.00 + 500.00 + 300.30 rupees. */
    protected static final long CLEAN_FILE_PAISE = 302_500L + 50_000L + 30_030L;
    protected static final int CLEAN_FILE_QUANTITY = 143;

    protected List<String> importStatuses(Clinic clinic, Object storeId) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select status from pharmacy.opening_stock_import where store_id = ?::uuid order by created_at, id")) {
                ps.setString(1, storeId.toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }
}
