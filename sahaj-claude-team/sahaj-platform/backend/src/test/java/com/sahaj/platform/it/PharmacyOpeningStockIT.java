package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SAH-78-7 on the real database: the CSV template, start an import, send rows (up to 200 a request, a row sent again replaces
 * it), submit (all or nothing; NOT_ACCEPTED stays after the 422), the owner's approval by a different owner, reject with a
 * reason, REPLACED, the store guard, and the database rules behind the service. Texts, codes and field names are those of
 * docs/api/pharmacy.yaml 0.1.2. Test plan: docs/test-plans/SAH-78.md (T78-7-*).
 */
@OnTestDatabase
class PharmacyOpeningStockIT extends Sah78BSupport {

    private static final String KEEPER_NAME = "Pia Pharmacist";

    /** A clinic with Central Store (CS) and Main Pharmacy, a pharmacist at the Central Store, a second owner and two products. */
    private record World(Clinic clinic, JsonNode central, JsonNode pharmacy, Pn keeper, Pn second, String codeA, String codeB) {

        String centralId() {
            return central.get("id").asText();
        }

        String pharmacyId() {
            return pharmacy.get("id").asText();
        }
    }

    private World world() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", KEEPER_NAME);
        mapStaff(clinic, idOf(stores[0]), keeper, "CENTRAL_STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        adopt(clinic, "G-000101");
        JsonNode own = createOwn(clinic, ownBody("Test dressing kit (OT)"));
        return new World(clinic, stores[0], stores[1], keeper, second, "G-000101", own.get("code").asText());
    }

    /** The clean three-row file of the keeper, started, sent and submitted. Returns the submitted import. */
    private JsonNode submittedByKeeper(World w) throws Exception {
        JsonNode started = draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB());
        return submit(w.keeper().auth(), w.centralId(), idOf(started));
    }

    private static List<String> issueTexts(JsonNode issues, int rowNo) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : issues) {
            if (i.get("rowNo").asInt() == rowNo) {
                out.add(i.get("field").asText() + ":" + i.get("code").asText() + ":" + i.get("severity").asText());
            }
        }
        return out;
    }

    private String ownerMembership(Clinic clinic) throws Exception {
        return contextOf(clinic.owner()).get("caller").get("membershipId").asText();
    }

    // ---------------- template ----------------

    @Test
    void theTemplateIsTheFixedCsvWithTheDocumentedHeadersForAnyoneWithAccessAndIsNotOfferedToOthers() throws Exception {
        World w = world();
        for (var who : List.of(w.clinic().owner(), w.keeper().auth(), w.second().auth())) {
            MvcResult r = mvc.perform(get(TEMPLATE).with(who).accept(MediaType.APPLICATION_JSON)).andReturn();
            assertThat(r.getResponse().getStatus()).as("also when the client asks for JSON: the CSV is served as text").isEqualTo(200);
            assertThat(r.getResponse().getContentType()).startsWith("text/csv");
            assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"sahaj-opening-stock-template.csv\"");
            assertThat(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("store code,product code,batch,expiry date,MRP,purchase rate,quantity\n#EXAMPLE,PRODUCT-CODE,TESTB001,31-12-2027,45.50,30.25,100\n"); // N-13: a placeholder, never a sample catalogue code
        }
        // a nurse mapped to a store reads it too (access to Pharmacy and Inventory is enough); an unmapped one does not
        Pn mapped = crew(w.clinic(), "NURSE");
        mapStaff(w.clinic(), w.pharmacyId(), mapped, "STORE_USER");
        assertThat(call(HttpMethod.GET, TEMPLATE, mapped.auth(), null).andReturn().getResponse().getStatus()).isEqualTo(200);
        for (String role : new String[] {"NURSE", "FRONT_DESK", "DOCTOR", "STORE_STAFF"}) {
            assertType(problem(call(HttpMethod.GET, TEMPLATE, crew(w.clinic(), role).auth(), null), 403), "forbidden");
        }
        assertThat(pharmacyRows(w.clinic(), "opening_stock_import")).as("a download writes nothing").isZero();
    }

    // ---------------- the flow: start, rows, submit ----------------

    @Test
    void startThenRowsThenSubmitMakesTheImportWaitForApprovalWithTheTotalsFixedAndNoFileTextInTheLogs() throws Exception {
        World w = world();
        UUID key = UUID.randomUUID();
        JsonNode started;
        String logs;
        try (LogCapture capture = new LogCapture()) {
            started = ok(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(key, 3)), 201);
            addRows(w.keeper().auth(), w.centralId(), idOf(started), rows(
                    row(1, "CS", w.codeA(), "SECRETBATCH77", farExpiry(), "45.50", "30.25", "100"),
                    row(2, "CS", w.codeB(), "SECRETBATCH88", farExpiry(), "20", "12.5", "40"),
                    row(3, "CS", w.codeA(), "SECRETBATCH99", "12/" + (todayIst().getYear() + 4), "150.00", "100.10", "3")));
            submit(w.keeper().auth(), w.centralId(), idOf(started));
            logs = capture.allText();
        }
        assertThat(logs).as("file text, batch numbers and quantities never reach a log").doesNotContain("SECRETBATCH").doesNotContain("30.25")
                .doesNotContain("100.10");
        // the answer of the start
        assertThat(started.get("status").asText()).isEqualTo("DRAFT");
        assertThat(started.get("rowCount").asInt()).isEqualTo(3);
        assertThat(started.get("receivedRows").asInt()).isZero();
        assertThat(started.get("storeId").asText()).isEqualTo(w.centralId());
        assertThat(started.get("storeName").asText()).isEqualTo("Central Store");
        assertThat(started.get("uploadedBy").get("membershipId").asText()).isEqualTo(w.keeper().membershipId().toString());
        assertThat(started.get("uploadedBy").get("name").asText()).isEqualTo(KEEPER_NAME);
        assertThat(started.get("canApprove").asBoolean()).isFalse();
        assertThat(started.get("approveLockReason").isNull()).as("null outside WAITING_APPROVAL").isTrue();
        assertThat(started.get("posted").asBoolean()).as("nothing is posted before SAH-79").isFalse();
        assertThat(started.get("submittedAt").isNull()).isTrue();
        assertThat(started.get("decidedBy").isNull()).isTrue();
        // the rows as stored: raw text kept, parsed values, value in paise
        JsonNode lines = ok(HttpMethod.GET, importUrl(w.centralId(), idOf(started)) + "/lines", w.keeper().auth(), null, 200);
        assertThat(lines.get("total").asInt()).isEqualTo(3);
        JsonNode first = lines.get("items").get(0);
        assertThat(first.get("rowNo").asInt()).isOne();
        assertThat(first.get("storeCode").asText()).isEqualTo("CS");
        assertThat(first.get("productCode").asText()).isEqualTo(w.codeA());
        assertThat(first.get("productName").asText()).isEqualTo("Sample Ceftriaxone 1 g injection");
        assertThat(first.get("productId").isNull()).isFalse();
        assertThat(first.get("batch").asText()).isEqualTo("SECRETBATCH77");
        assertThat(first.get("expiryDate").asText()).isEqualTo((todayIst().getYear() + 3) + "-12-31");
        assertThat(first.get("mrp").asText()).isEqualTo("45.50");
        assertThat(first.get("purchaseRate").asText()).isEqualTo("30.25");
        assertThat(first.get("quantity").asInt()).isEqualTo(100);
        assertThat(first.get("valuePaise").asLong()).isEqualTo(302_500L);
        assertThat(first.get("issues")).isEmpty();
        assertThat(lines.get("items").get(1).get("mrp").asText()).isEqualTo("20.00");
        assertThat(lines.get("items").get(1).get("purchaseRate").asText()).isEqualTo("12.50");
        assertThat(lines.get("items").get(2).get("expiryDate").asText()).as("a month form is the last day of that month")
                .isEqualTo((todayIst().getYear() + 4) + "-12-31");
        // after submit: waiting for approval, the totals fixed
        JsonNode waiting = getImport(w.keeper().auth(), w.centralId(), idOf(started));
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.get("receivedRows").asInt()).isEqualTo(3);
        assertThat(waiting.get("lineCount").asInt()).isEqualTo(3);
        assertThat(waiting.get("totalQuantity").asInt()).isEqualTo(CLEAN_FILE_QUANTITY);
        assertThat(waiting.get("totalValuePaise").asLong()).isEqualTo(CLEAN_FILE_PAISE);
        assertThat(waiting.get("errorCount").asInt()).isZero();
        assertThat(waiting.get("warningCount").asInt()).isZero();
        assertThat(waiting.get("submittedAt").isNull()).isFalse();
        assertThat(waiting.get("canApprove").asBoolean()).as("the uploader is not an owner").isFalse();
        assertThat(waiting.get("approveLockReason").asText()).isEqualTo("NOT_OWNER");
        // the owner sees the same import and may approve it
        JsonNode forOwner = getImport(w.clinic().owner(), w.centralId(), idOf(started));
        assertThat(forOwner.get("canApprove").asBoolean()).isTrue();
        assertThat(forOwner.get("approveLockReason").isNull()).isTrue();
        // it cannot change any more
        JsonNode locked = problem(call(HttpMethod.POST, importUrl(w.centralId(), idOf(started)) + "/lines", w.keeper().auth(),
                js(rows(row(1, "CS", w.codeA(), "X", farExpiry(), "1", "1", "1")))), 409);
        assertType(locked, "import-state");
        assertThat(locked.get("detail").asText()).isEqualTo("This import can no longer be changed.");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), idOf(started)) + "/submit", w.keeper().auth(), null), 409), "import-state");
        // audited with ids and totals, no row text
        List<List<String>> startedAudit = auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_STARTED");
        List<List<String>> submittedAudit = auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_SUBMITTED");
        assertThat(startedAudit).hasSize(1);
        assertThat(submittedAudit).hasSize(1);
        assertThat(submittedAudit.get(0).get(3)).contains(String.valueOf(CLEAN_FILE_PAISE)).contains(String.valueOf(CLEAN_FILE_QUANTITY));
        assertThat(startedAudit.get(0).get(3) + submittedAudit.get(0).get(3)).doesNotContain("SECRETBATCH");
        // the lists: the store's list for the pharmacist, the owner's list over every store with a status filter
        assertThat(ok(HttpMethod.GET, importsUrl(w.centralId()), w.keeper().auth(), null, 200).get("items")).hasSize(1);
        assertThat(ok(HttpMethod.GET, ALL_IMPORTS + "?status=WAITING_APPROVAL", w.clinic().owner(), null, 200).get("total").asInt()).isOne();
        assertThat(ok(HttpMethod.GET, ALL_IMPORTS + "?status=DRAFT", w.clinic().owner(), null, 200).get("items")).isEmpty();
    }

    @Test
    void upTo200RowsGoInOneRequestARowSentAgainReplacesItAndTheLimitsAreExact() throws Exception {
        World w = world();
        JsonNode started = startImport(w.keeper().auth(), w.centralId(), 250);
        String id = idOf(started);
        List<Map<String, Object>> first = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            first.add(row(i, "CS", w.codeA(), "B" + i, farExpiry(), "10", "5", "1"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", first);
        JsonNode result = addRows(w.keeper().auth(), w.centralId(), id, body);
        assertThat(result.get("receivedRows").asInt()).isEqualTo(200);
        assertThat(result.get("rowCount").asInt()).isEqualTo(250);
        assertThat(result.get("issues")).isEmpty();
        // 201 rows in one request: refused, nothing stored
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 201; i <= 250; i++) {
            tooMany.add(row(i, "CS", w.codeA(), "B" + i, farExpiry(), "10", "5", "1"));
        }
        tooMany.addAll(first.subList(0, 151));
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("rows", tooMany);
        assertThat(tooMany).hasSize(201);
        assertFieldMessage(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/lines", w.keeper().auth(), js(big)), 400), "rows",
                "Send at most 200 rows at a time");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("receivedRows").asInt()).isEqualTo(200);
        // the other 50 rows
        Map<String, Object> rest = new LinkedHashMap<>();
        rest.put("rows", tooMany.subList(0, 50));
        assertThat(addRows(w.keeper().auth(), w.centralId(), id, rest).get("receivedRows").asInt()).isEqualTo(250);
        // a row sent again replaces that row: the count does not grow, the new values are kept, the old problem is gone
        JsonNode bad = addRows(w.keeper().auth(), w.centralId(), id, rows(row(5, "CS", w.codeA(), "B5", farExpiry(), "10", "5", "x")));
        assertThat(issueTexts(bad.get("issues"), 5)).containsExactly("quantity:quantity-invalid:ERROR");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("errorCount").asInt()).isOne();
        JsonNode fixed = addRows(w.keeper().auth(), w.centralId(), id, rows(row(5, "CS", w.codeA(), "B5-NEW", farExpiry(), "10", "5", "7")));
        assertThat(fixed.get("receivedRows").asInt()).isEqualTo(250);
        assertThat(fixed.get("issues")).isEmpty();
        JsonNode now = getImport(w.keeper().auth(), w.centralId(), id);
        assertThat(now.get("errorCount").asInt()).as("the old issue was superseded").isZero();
        assertThat(now.get("lineCount").asInt()).isEqualTo(250);
        assertThat(ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?withIssues=true", w.keeper().auth(), null, 200).get("items")).isEmpty();
        JsonNode row5 = ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?page=0&size=10", w.keeper().auth(), null, 200).get("items").get(4);
        assertThat(row5.get("batch").asText()).isEqualTo("B5-NEW");
        assertThat(row5.get("quantity").asInt()).isEqualTo(7);
        // paging of the lines: size 200 is the largest
        assertThat(ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?size=200", w.keeper().auth(), null, 200).get("items")).hasSize(200);
        assertFieldMessage(problem(call(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?size=201", w.keeper().auth(), null), 400), "size",
                "Enter a whole number from 1 to 200");
        assertFieldMessage(problem(call(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?withIssues=maybe", w.keeper().auth(), null), 400),
                "withIssues", "Choose a valid option");
    }

    @Test
    void aBadRequestBodyIsRefusedWithFieldErrorsAndStoresNothing() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 5));
        String url = importUrl(w.centralId(), id) + "/lines";
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), "{\"rows\":[]}"), 400), "rows", "This field is required");
        assertType(problem(call(HttpMethod.POST, url, w.keeper().auth(), "{}"), 400), "validation");
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(rows(row(0, "CS", w.codeA(), "B", farExpiry(), "1", "1", "1")))), 400),
                "rows[0].rowNo", "Enter a row number from 1 to 5");
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(rows(row(6, "CS", w.codeA(), "B", farExpiry(), "1", "1", "1")))), 400),
                "rows[0].rowNo", "Enter a row number from 1 to 5");
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(rows(
                row(2, "CS", w.codeA(), "B", farExpiry(), "1", "1", "1"), row(2, "CS", w.codeA(), "C", farExpiry(), "1", "1", "1")))), 400),
                "rows[1].rowNo", "Send each row number once");
        Map<String, Object> nullCell = row(1, "CS", w.codeA(), "B", farExpiry(), "1", "1", "1");
        nullCell.put("batch", null);
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(rows(nullCell))), 400), "rows[0].batch", "This field is required");
        for (String[] over : new String[][] {{"storeCode", "s".repeat(41), "40"}, {"productCode", "p".repeat(41), "40"}, {"batch", "b".repeat(41), "40"},
            {"expiryDate", "e".repeat(21), "20"}, {"mrp", "1".repeat(21), "20"}, {"purchaseRate", "1".repeat(21), "20"}, {"quantity", "1".repeat(21), "20"}}) {
            Map<String, Object> r = row(1, "CS", w.codeA(), "B", farExpiry(), "1", "1", "1");
            r.put(over[0], over[1]);
            assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(rows(r))), 400), "rows[0]." + over[0], "Keep it to " + over[2] + " characters");
        }
        Map<String, Object> extra = row(1, "CS", w.codeA(), "B", farExpiry(), "1", "1", "1");
        extra.put("productId", UUID.randomUUID().toString());
        assertType(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(rows(extra))), 400), "validation");
        assertType(problem(call(HttpMethod.POST, url, w.keeper().auth(), "{\"rows\":[{\"rowNo\":\"x\"}]}"), 400), "validation");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("receivedRows").asInt()).as("nothing stored by a refused request").isZero();
        assertThat(pharmacyRows(w.clinic(), "opening_stock_line")).isZero();
    }

    // ---------------- row checks ----------------

    @Test
    void everyRowCheckAnswersItsOwnIssueWithTheDocumentedFieldAndCodeAndWarningsAreNotErrors() throws Exception {
        World w = world();
        LocalDate today = todayIst();
        int year = today.getYear() + 3;
        String good = w.codeA();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<Integer, List<String>> expected = new LinkedHashMap<>();
        // expiry formats: all five readings are the same date
        list.add(row(1, "cs", " g-000101 ", "T1", "31-12-" + year, "10", "5", "1")); // codes: case and spaces do not matter
        list.add(row(2, "CS", good, "T2", "31/12/" + year, "10", "5", "1"));
        list.add(row(3, "CS", good, "T3", year + "-12-31", "10", "5", "1"));
        list.add(row(4, "CS", good, "T4", "12/" + year, "10", "5", "1"));
        list.add(row(5, "CS", good, "T5", "12-" + year, "10", "5", "1"));
        list.add(row(6, "OTHER", good, "T6", farExpiry(), "10", "5", "1"));
        expected.put(6, List.of("storeCode:store-mismatch:ERROR"));
        list.add(row(7, "", good, "T7", farExpiry(), "10", "5", "1"));
        expected.put(7, List.of("storeCode:store-mismatch:ERROR"));
        list.add(row(8, "CS", "G-999999", "T8", farExpiry(), "10", "5", "1"));
        expected.put(8, List.of("productCode:product-unknown:ERROR"));
        list.add(row(9, "CS", good, "", farExpiry(), "10", "5", "1"));
        expected.put(9, List.of("batch:batch-invalid:ERROR"));
        list.add(row(10, "CS", good, "b".repeat(21), farExpiry(), "10", "5", "1"));
        expected.put(10, List.of("batch:batch-invalid:ERROR"));
        list.add(row(11, "CS", good, "b".repeat(20), farExpiry(), "10", "5", "1")); // 20 characters is the limit
        list.add(row(12, "CS", good, "T12", "next year", "10", "5", "1"));
        expected.put(12, List.of("expiryDate:expiry-invalid:ERROR"));
        list.add(row(13, "CS", good, "T13", "31-02-" + year, "10", "5", "1"));
        expected.put(13, List.of("expiryDate:expiry-invalid:ERROR"));
        list.add(row(14, "CS", good, "T14", ddMmYyyy(today), "10", "5", "1")); // today is not in the future
        expected.put(14, List.of("expiryDate:expiry-past:ERROR"));
        list.add(row(15, "CS", good, "T15", ddMmYyyy(today.minusDays(1)), "10", "5", "1"));
        expected.put(15, List.of("expiryDate:expiry-past:ERROR"));
        list.add(row(16, "CS", good, "T16", ddMmYyyy(today.plusDays(1)), "10", "5", "1")); // tomorrow is the first good day
        list.add(row(17, "CS", good, "T17", farExpiry(), "0", "5", "1"));
        expected.put(17, List.of("mrp:mrp-invalid:ERROR"));
        list.add(row(18, "CS", good, "T18", farExpiry(), "1.234", "5", "1"));
        expected.put(18, List.of("mrp:mrp-invalid:ERROR"));
        list.add(row(19, "CS", good, "T19", farExpiry(), "9999999.99", "5", "1")); // the largest amount
        list.add(row(20, "CS", good, "T20", farExpiry(), "10000000", "5", "1"));
        expected.put(20, List.of("mrp:mrp-invalid:ERROR"));
        list.add(row(21, "CS", good, "T21", farExpiry(), "10", "0", "1"));
        expected.put(21, List.of("purchaseRate:rate-invalid:ERROR"));
        list.add(row(22, "CS", good, "T22", farExpiry(), "10", "-5", "1"));
        expected.put(22, List.of("purchaseRate:rate-invalid:ERROR"));
        list.add(row(23, "CS", good, "T23", farExpiry(), "10", "abc", "1"));
        expected.put(23, List.of("purchaseRate:rate-invalid:ERROR"));
        list.add(row(24, "CS", good, "T24", farExpiry(), "10", "5", "0"));
        expected.put(24, List.of("quantity:quantity-invalid:ERROR"));
        list.add(row(25, "CS", good, "T25", farExpiry(), "10", "5", "1000000"));
        expected.put(25, List.of("quantity:quantity-invalid:ERROR"));
        list.add(row(26, "CS", good, "T26", farExpiry(), "10", "5", "999999")); // the largest quantity
        list.add(row(27, "CS", good, "T27", farExpiry(), "10", "5", "2.5"));
        expected.put(27, List.of("quantity:quantity-invalid:ERROR"));
        list.add(row(28, "CS", good, "T28", farExpiry(), "10", "5", ""));
        expected.put(28, List.of("quantity:quantity-invalid:ERROR"));
        list.add(row(29, "CS", good, "T29", farExpiry(), "10", "50", "1")); // rate above MRP: a warning, not an error
        expected.put(29, List.of("purchaseRate:rate-above-mrp:WARNING"));
        list.add(row(30, "CS", good, "T30", farExpiry(), "10", "10", "1")); // rate equal to MRP: nothing
        list.add(row(31, "OTHER", "G-999999", "", "x", "0", "0", "0")); // everything wrong at once
        expected.put(31, List.of("batch:batch-invalid:ERROR", "expiryDate:expiry-invalid:ERROR", "mrp:mrp-invalid:ERROR", "productCode:product-unknown:ERROR",
                "purchaseRate:rate-invalid:ERROR", "quantity:quantity-invalid:ERROR", "storeCode:store-mismatch:ERROR"));
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 31));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", list);
        JsonNode result = addRows(w.keeper().auth(), w.centralId(), id, body);
        assertThat(result.get("receivedRows").asInt()).isEqualTo(31);
        for (int rowNo = 1; rowNo <= 31; rowNo++) {
            List<String> got = issueTexts(result.get("issues"), rowNo);
            List<String> want = expected.getOrDefault(rowNo, List.of());
            assertThat(new TreeSet<>(got)).as("row " + rowNo).isEqualTo(new TreeSet<>(want));
        }
        // the sentences
        for (JsonNode i : result.get("issues")) {
            switch (i.get("code").asText()) {
                case "expiry-past" -> assertThat(i.get("message").asText()).isEqualTo("Expiry date must be in the future");
                case "rate-above-mrp" -> assertThat(i.get("message").asText()).isEqualTo("Purchase rate is higher than MRP");
                case "quantity-invalid" -> assertThat(i.get("message").asText()).isEqualTo("Enter a whole number from 1 to 9,99,999");
                default -> assertThat(i.get("message").asText()).isNotBlank();
            }
        }
        // the parsed expiry of the five formats is the same day, and the amounts at the limits are kept
        JsonNode lines = ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?size=200", w.keeper().auth(), null, 200);
        for (int i = 0; i < 5; i++) {
            assertThat(lines.get("items").get(i).get("expiryDate").asText()).as("row " + (i + 1)).isEqualTo(year + "-12-31");
        }
        assertThat(lines.get("items").get(18).get("mrp").asText()).isEqualTo("9999999.99");
        assertThat(lines.get("items").get(25).get("quantity").asInt()).isEqualTo(999_999);
        assertThat(lines.get("items").get(0).get("storeCode").asText()).as("the raw text is kept as sent").isEqualTo("cs");
        // the problem list: only rows with an error or a warning
        JsonNode problems = ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?withIssues=true&size=200", w.keeper().auth(), null, 200);
        assertThat(problems.get("total").asInt()).isEqualTo(expected.size());
        // counts while DRAFT are live: rows with an error, rows with a warning
        JsonNode draft = getImport(w.keeper().auth(), w.centralId(), id);
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("errorCount").asInt()).as("rows with an error, not issues").isEqualTo(expected.size() - 1);
        assertThat(draft.get("warningCount").asInt()).isOne();
    }

    @Test
    void aFileWithAnErrorBecomesNotAcceptedAndThatStaysAfterThe422WithRowIssuesWithoutWarnings() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 4));
        addRows(w.keeper().auth(), w.centralId(), id, rows(
                row(1, "CS", w.codeA(), "", "bad date", "10", "5", "0"), // three errors, one row
                row(2, "CS", "G-999999", "N2", farExpiry(), "10", "5", "1"), // one error
                row(3, "CS", w.codeA(), "N3", farExpiry(), "10", "50", "1"), // a warning only
                row(4, "CS", w.codeB(), "N4", farExpiry(), "10", "5", "1")));
        JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.keeper().auth(), null), 422);
        assertType(p, "opening-stock-invalid");
        assertThat(p.get("title").asText()).isEqualTo("File not accepted");
        assertThat(p.get("detail").asText()).isEqualTo("2 rows have problems. Fix them and upload the file again.");
        assertThat(p.get("errorCount").asInt()).as("rows with an error, not issues").isEqualTo(2);
        JsonNode issues = p.get("rowIssues");
        assertThat(issues).as("4 error issues, the warning is not listed").hasSize(4);
        List<String> order = new ArrayList<>();
        for (JsonNode i : issues) {
            assertThat(i.get("severity").asText()).isEqualTo("ERROR");
            order.add(i.get("rowNo").asInt() + ":" + i.get("field").asText());
        }
        assertThat(order).as("by row, then field").containsExactly("1:batch", "1:expiryDate", "1:quantity", "2:productCode");
        // the status change was kept although the request answered 422
        JsonNode after = getImport(w.keeper().auth(), w.centralId(), id);
        assertThat(after.get("status").asText()).isEqualTo("NOT_ACCEPTED");
        assertThat(after.get("errorCount").asInt()).isEqualTo(2);
        assertThat(after.get("warningCount").asInt()).isOne();
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("NOT_ACCEPTED");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_NOT_ACCEPTED")).hasSize(1);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_SUBMITTED")).isEmpty();
        // the warning is still readable with the problem list
        JsonNode list = ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?withIssues=true", w.keeper().auth(), null, 200);
        assertThat(list.get("total").asInt()).isEqualTo(3);
        assertThat(issueTexts(list.get("items").get(2).get("issues"), 3)).containsExactly("purchaseRate:rate-above-mrp:WARNING");
        // final: no more rows, no submit, no approval, no reject
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.keeper().auth(), null), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/lines", w.keeper().auth(),
                js(rows(row(1, "CS", w.codeA(), "Z", farExpiry(), "1", "1", "1")))), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", w.second().auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}"), 409), "import-state");
        assertThat(after.get("canApprove").asBoolean()).isFalse();
        assertThat(after.get("approveLockReason").isNull()).as("null outside WAITING_APPROVAL").isTrue();
        // the uploader fixes the file and starts a new import; the old one stays NOT_ACCEPTED
        JsonNode again = startImport(w.keeper().auth(), w.centralId(), 1);
        assertThat(again.get("status").asText()).isEqualTo("DRAFT");
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("NOT_ACCEPTED", "DRAFT");
        // nothing was approved, so the store has no stock
        assertThat(ok(HttpMethod.GET, STORES + "/" + w.centralId(), w.clinic().owner(), null, 200).get("hasStock").asBoolean()).isFalse();
    }

    @Test
    void oneBadRowGives1RowMessageAndOnlyTheFirst100IssuesAreListedWhileTheCountIsTheNumberOfRows() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 105));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 1; i <= 105; i++) {
            list.add(row(i, "CS", "G-999999", "M" + i, farExpiry(), "10", "5", "1"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", list);
        addRows(w.keeper().auth(), w.centralId(), id, body);
        JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.keeper().auth(), null), 422);
        assertThat(p.get("errorCount").asInt()).isEqualTo(105);
        assertThat(p.get("rowIssues")).hasSize(100);
        assertThat(p.get("rowIssues").get(99).get("rowNo").asInt()).isEqualTo(100);
        assertThat(p.get("detail").asText()).isEqualTo("105 rows have problems. Fix them and upload the file again.");
        // the rest through the lines list
        assertThat(ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines?withIssues=true&size=200", w.keeper().auth(), null, 200).get("total").asInt()).isEqualTo(105);
        // one bad row: the singular sentence
        String second = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        addRows(w.keeper().auth(), w.centralId(), second, rows(row(1, "CS", w.codeA(), "S1", farExpiry(), "10", "5", "1"),
                row(2, "CS", w.codeA(), "S2", farExpiry(), "10", "5", "0")));
        JsonNode one = problem(call(HttpMethod.POST, importUrl(w.centralId(), second) + "/submit", w.keeper().auth(), null), 422);
        assertThat(one.get("detail").asText()).isEqualTo("1 row has a problem. Fix them and upload the file again.");
    }

    @Test
    void theSameProductAndBatchTwiceIsAnErrorOnBothRowsAtSubmitIgnoringCaseAndSpacesButOtherCombinationsAreFine() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 5));
        addRows(w.keeper().auth(), w.centralId(), id, rows(
                row(1, "CS", w.codeA(), "ab1", farExpiry(), "10", "5", "1"),
                row(2, "CS", w.codeA(), "AB1", farExpiry(), "10", "5", "1"), // same product, same batch, another case
                row(3, "CS", w.codeB(), "AB1", farExpiry(), "10", "5", "1"), // another product, same batch: fine
                row(4, "CS", w.codeA(), "ab2", farExpiry(), "10", "5", "1"), // same product, another batch: fine
                row(5, "CS", " G-000101", " ab1 ", farExpiry(), "10", "5", "1"))); // codes and batch trimmed: the same as row 1
        JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.keeper().auth(), null), 422);
        assertThat(p.get("errorCount").asInt()).isEqualTo(3);
        List<String> rowsWithIssue = new ArrayList<>();
        for (JsonNode i : p.get("rowIssues")) {
            assertThat(i.get("field").asText()).isEqualTo("batch");
            assertThat(i.get("code").asText()).isEqualTo("batch-repeated");
            assertThat(i.get("message").asText()).isEqualTo("This product and batch appear more than once in the file");
            rowsWithIssue.add(i.get("rowNo").asText());
        }
        assertThat(rowsWithIssue).containsExactly("1", "2", "5");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("status").asText()).isEqualTo("NOT_ACCEPTED");
        // without the repeat the same file is accepted
        String clean = idOf(startImport(w.keeper().auth(), w.centralId(), 3));
        addRows(w.keeper().auth(), w.centralId(), clean, rows(
                row(1, "CS", w.codeA(), "ab1", farExpiry(), "10", "5", "1"),
                row(2, "CS", w.codeB(), "AB1", farExpiry(), "10", "5", "1"),
                row(3, "CS", w.codeA(), "ab2", farExpiry(), "10", "5", "1")));
        assertThat(submit(w.keeper().auth(), w.centralId(), clean).get("status").asText()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void submitBeforeEveryRowArrivedIs409ImportIncompleteAndTheImportStaysADraft() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 3));
        addRows(w.keeper().auth(), w.centralId(), id, rows(row(1, "CS", w.codeA(), "I1", farExpiry(), "10", "5", "1"),
                row(3, "CS", w.codeA(), "I3", farExpiry(), "10", "5", "1")));
        JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.keeper().auth(), null), 409);
        assertType(p, "import-incomplete");
        assertThat(p.get("detail").asText()).isEqualTo("Some rows did not arrive. Upload the file again.");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("status").asText()).isEqualTo("DRAFT");
        addRows(w.keeper().auth(), w.centralId(), id, rows(row(2, "CS", w.codeA(), "I2", farExpiry(), "10", "5", "1")));
        assertThat(submit(w.keeper().auth(), w.centralId(), id).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_NOT_ACCEPTED")).isEmpty();
    }

    // ---------------- start ----------------

    @Test
    void startNeedsTheTemplateHeaderAndOneToFiveThousandRowsAndAClientKey() throws Exception {
        World w = world();
        String url = importsUrl(w.centralId());
        // the header is compared trimmed and ignoring case, with a byte order mark tolerated on the first cell
        List<List<String>> accepted = new ArrayList<>();
        accepted.add(List.of("STORE CODE", "Product Code", "BATCH", "Expiry Date", "mrp", "PURCHASE RATE", "Quantity"));
        accepted.add(List.of(" store code ", " product code", "batch ", "expiry date", "MRP", "purchase rate", "quantity"));
        accepted.add(List.of("﻿store code", "product code", "batch", "expiry date", "MRP", "purchase rate", "quantity"));
        for (List<String> columns : accepted) {
            Map<String, Object> b = startBody(UUID.randomUUID(), 1);
            b.put("columns", columns);
            assertThat(ok(HttpMethod.POST, url, w.keeper().auth(), js(b), 201).get("status").asText()).as(columns.toString()).isEqualTo("DRAFT");
        }
        List<List<String>> refused = new ArrayList<>();
        refused.add(List.of("store code", "product code", "batch", "expiry date", "MRP", "purchase rate")); // 6 columns
        refused.add(List.of("store code", "product code", "batch", "expiry date", "MRP", "purchase rate", "quantity", "notes")); // 8
        refused.add(List.of("product code", "store code", "batch", "expiry date", "MRP", "purchase rate", "quantity")); // order
        refused.add(List.of("store code", "product code", "batch", "expiry date", "MRP", "purchase price", "quantity")); // a name
        refused.add(List.of("store code")); // not the template
        for (List<String> columns : refused) {
            Map<String, Object> b = startBody(UUID.randomUUID(), 1);
            b.put("columns", columns);
            JsonNode p = problem(call(HttpMethod.POST, url, w.keeper().auth(), js(b)), 400);
            assertFieldMessage(p, "columns", HEADER_TEXT);
        }
        Map<String, Object> noColumns = startBody(UUID.randomUUID(), 1);
        noColumns.put("columns", null);
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(noColumns)), 400), "columns", HEADER_TEXT);
        // 1 to 5,000 rows
        for (Object bad : new Object[] {0, -1, 5001, 2.5}) {
            Map<String, Object> b = startBody(UUID.randomUUID(), 1);
            b.put("rowCount", bad);
            assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(b)), 400), "rowCount", "The file must have 1 to 5,000 rows");
        }
        Map<String, Object> noRows = startBody(UUID.randomUUID(), 1);
        noRows.put("rowCount", null);
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(noRows)), 400), "rowCount", "The file must have 1 to 5,000 rows");
        assertThat(ok(HttpMethod.POST, url, w.keeper().auth(), js(startBody(UUID.randomUUID(), 5000)), 201).get("rowCount").asInt()).isEqualTo(5000);
        assertThat(ok(HttpMethod.POST, url, w.keeper().auth(), js(startBody(UUID.randomUUID(), 1)), 201).get("rowCount").asInt()).isOne();
        // a client key is needed; the body is closed
        Map<String, Object> noKey = startBody(UUID.randomUUID(), 1);
        noKey.remove("clientKey");
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(noKey)), 400), "clientKey", "Reload the page and try again.");
        Map<String, Object> extra = startBody(UUID.randomUUID(), 1);
        extra.put("storeId", w.pharmacyId());
        assertType(problem(call(HttpMethod.POST, url, w.keeper().auth(), js(extra)), 400), "validation");
    }

    @Test
    void theSameClientKeyAnswersTheSameImportAgainAndAnotherCallerOrAnotherStoreGets409ClientKeyReused() throws Exception {
        World w = world();
        UUID key = UUID.randomUUID();
        String url = importsUrl(w.centralId());
        JsonNode first = ok(HttpMethod.POST, url, w.keeper().auth(), js(startBody(key, 3)), 201);
        JsonNode replay = ok(HttpMethod.POST, url, w.keeper().auth(), js(startBody(key, 3)), 200);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(importStatuses(w.clinic(), w.centralId())).as("a replay writes nothing").containsExactly("DRAFT");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_STARTED")).hasSize(1);
        // another caller with the same key
        JsonNode other = problem(call(HttpMethod.POST, url, w.second().auth(), js(startBody(key, 3))), 409);
        assertType(other, "client-key-reused");
        assertThat(other.get("detail").asText()).isEqualTo(KEY_REUSED_TEXT);
        // the same caller, the same key, another store (the first use is a start at the Central Store)
        UUID ownerKey = UUID.randomUUID();
        ok(HttpMethod.POST, url, w.clinic().owner(), js(startBody(ownerKey, 3)), 201);
        assertType(problem(call(HttpMethod.POST, importsUrl(w.pharmacyId()), w.clinic().owner(), js(startBody(ownerKey, 3))), 409), "client-key-reused");
        // another clinic may use the same key
        World v = world();
        ok(HttpMethod.POST, importsUrl(v.centralId()), v.keeper().auth(), js(startBody(key, 3)), 201);
    }

    @Test
    void startIsRefusedWhenTheStoreAlreadyHasAnApprovedImportOrAnotherPersonsImportIsWaiting() throws Exception {
        World w = world();
        JsonNode waiting = submittedByKeeper(w);
        // another uploader (the owner may upload too): the keeper's file is waiting
        JsonNode p = problem(call(HttpMethod.POST, importsUrl(w.centralId()), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1))), 409);
        assertType(p, "import-waiting");
        assertThat(p.get("detail").asText()).isEqualTo("An import for this store is waiting for approval by " + KEEPER_NAME + ".");
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("WAITING_APPROVAL");
        // the person whose file waits may start a new one (it replaces the waiting one at its submit)
        assertThat(ok(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(UUID.randomUUID(), 1)), 201).get("status").asText()).isEqualTo("DRAFT");
        // approved: no more imports for the store
        ok(HttpMethod.POST, importUrl(w.centralId(), idOf(waiting)) + "/approve", w.clinic().owner(), null, 200);
        JsonNode done = problem(call(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(UUID.randomUUID(), 1))), 409);
        assertType(done, "opening-stock-already-approved");
        assertThat(done.get("detail").asText()).isEqualTo("Opening stock was already approved for this store.");
        assertType(problem(call(HttpMethod.POST, importsUrl(w.centralId()), w.second().auth(), js(startBody(UUID.randomUUID(), 1))), 409), "opening-stock-already-approved");
        // another store of the clinic is not affected
        assertThat(ok(HttpMethod.POST, importsUrl(w.pharmacyId()), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1)), 201).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------------- REPLACED ----------------

    @Test
    void aNewStartReplacesMyDraftAndASubmitReplacesMyWaitingImportSoOnlyOneWaitsAtATime() throws Exception {
        World w = world();
        JsonNode first = draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB());
        // start: my earlier DRAFT becomes REPLACED, pointing at the new import
        JsonNode second = draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB());
        JsonNode firstNow = getImport(w.keeper().auth(), w.centralId(), idOf(first));
        assertThat(firstNow.get("status").asText()).isEqualTo("REPLACED");
        assertThat(firstNow.get("replacedByImportId").asText()).isEqualTo(idOf(second));
        assertThat(firstNow.get("canApprove").asBoolean()).isFalse();
        assertThat(firstNow.get("approveLockReason").isNull()).isTrue();
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), idOf(first)) + "/lines", w.keeper().auth(),
                js(rows(row(1, "CS", w.codeA(), "Q", farExpiry(), "1", "1", "1")))), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), idOf(first)) + "/submit", w.keeper().auth(), null), 409), "import-state");
        String startedAudit = auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_STARTED").get(1).get(3);
        assertThat(startedAudit).contains("replacedImportId").contains(idOf(first));
        // submit the second: it waits
        submit(w.keeper().auth(), w.centralId(), idOf(second));
        // a third is started while the second waits (allowed: it is mine); the second keeps waiting until the third is submitted
        JsonNode third = draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB());
        assertThat(getImport(w.keeper().auth(), w.centralId(), idOf(second)).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        submit(w.keeper().auth(), w.centralId(), idOf(third));
        JsonNode secondNow = getImport(w.keeper().auth(), w.centralId(), idOf(second));
        assertThat(secondNow.get("status").asText()).isEqualTo("REPLACED");
        assertThat(secondNow.get("replacedByImportId").asText()).isEqualTo(idOf(third));
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("REPLACED", "REPLACED", "WAITING_APPROVAL");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_SUBMITTED").get(1).get(3)).contains("replacedImportId").contains(idOf(second));
        // a replaced import cannot be approved or rejected; the waiting one can
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), idOf(second)) + "/approve", w.clinic().owner(), null), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), idOf(second)) + "/reject", w.clinic().owner(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}"), 409), "import-state");
        assertThat(ok(HttpMethod.POST, importUrl(w.centralId(), idOf(third)) + "/approve", w.clinic().owner(), null, 200).get("status").asText()).isEqualTo("APPROVED");
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("REPLACED", "REPLACED", "APPROVED");
    }

    @Test
    void anotherPersonsDraftIsNotReplacedByMyStart() throws Exception {
        World w = world();
        Pn keeper2 = crew(w.clinic(), "PHARMACIST", "Second Pharmacist");
        mapStaff(w.clinic(), w.centralId(), keeper2, "CENTRAL_STORE_USER");
        JsonNode mine = startImport(w.keeper().auth(), w.centralId(), 2);
        JsonNode theirs = startImport(keeper2.auth(), w.centralId(), 2);
        assertThat(getImport(w.keeper().auth(), w.centralId(), idOf(mine)).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getImport(keeper2.auth(), w.centralId(), idOf(theirs)).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------------- approve ----------------

    @Test
    void anotherOwnerApprovesAndTheStoreAndTheProductsOnTheLinesGetStockAndLockTheirUnits() throws Exception {
        World w = world();
        JsonNode otherProduct = adopt(w.clinic(), "G-000103"); // not on any line
        JsonNode submitted = submittedByKeeper(w);
        String id = idOf(submitted);
        JsonNode approved = ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null, 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("decidedBy").get("membershipId").asText()).isEqualTo(w.second().membershipId().toString());
        assertThat(approved.get("decidedBy").get("name").asText()).isEqualTo("Second Owner");
        assertThat(approved.get("decidedAt").isNull()).isFalse();
        assertThat(approved.get("uploadedBy").get("membershipId").asText()).isEqualTo(w.keeper().membershipId().toString());
        assertThat(approved.get("canApprove").asBoolean()).isFalse();
        assertThat(approved.get("approveLockReason").isNull()).as("null outside WAITING_APPROVAL").isTrue();
        assertThat(approved.get("posted").asBoolean()).isFalse();
        assertThat(approved.get("totalValuePaise").asLong()).isEqualTo(CLEAN_FILE_PAISE);
        // the store has stock and its type, code and issues-from are locked
        JsonNode store = ok(HttpMethod.GET, STORES + "/" + w.centralId(), w.clinic().owner(), null, 200);
        assertThat(store.get("hasStock").asBoolean()).isTrue();
        List<String> locked = new ArrayList<>();
        store.get("lockedFields").forEach(f -> locked.add(f.asText()));
        assertThat(locked).contains("TYPE", "CODE");
        assertThat(ok(HttpMethod.GET, STORES + "/" + w.pharmacyId(), w.clinic().owner(), null, 200).get("hasStock").asBoolean()).as("another store").isFalse();
        // every product named on a line has stock and unitsLocked equals hasStock; a product that is not on a line has not
        List<String> codes = new ArrayList<>();
        JsonNode lines = ok(HttpMethod.GET, importUrl(w.centralId(), id) + "/lines", w.keeper().auth(), null, 200);
        for (JsonNode line : lines.get("items")) {
            JsonNode product = getProduct(w.clinic(), line.get("productId").asText());
            assertThat(product.get("hasStock").asBoolean()).as(product.get("code").asText()).isTrue();
            assertThat(product.get("unitsLocked").asBoolean()).isEqualTo(product.get("hasStock").asBoolean());
            codes.add(product.get("code").asText());
        }
        assertThat(codes).contains(w.codeA(), w.codeB());
        JsonNode untouched = getProduct(w.clinic(), otherProduct.get("productId").asText());
        assertThat(untouched.get("hasStock").asBoolean()).isFalse();
        assertThat(untouched.get("unitsLocked").asBoolean()).isFalse();
        // the effects: no switch-off of the store or the product, no change of the units of an own product
        assertType(problem(call(HttpMethod.POST, STORES + "/" + w.centralId() + "/switch-off", w.clinic().owner(), null), 409), "store-has-stock");
        Map<String, Object> stop = localBody(getProduct(w.clinic(), lines.get("items").get(0).get("productId").asText()));
        stop.put("active", false);
        assertType(problem(call(HttpMethod.PUT, localUrl(lines.get("items").get(0).get("productId").asText()), w.clinic().owner(), js(stop)), 409), "product-has-stock");
        String ownId = null;
        for (JsonNode line : lines.get("items")) {
            if (line.get("productCode").asText().equals(w.codeB())) {
                ownId = line.get("productId").asText();
            }
        }
        Map<String, Object> units = updateBody(getProduct(w.clinic(), ownId));
        units.put("issueUnit", "strip");
        assertType(problem(call(HttpMethod.PUT, productUrl(ownId), w.clinic().owner(), js(units)), 409), "units-locked");
        // final: approve again, reject, a new start
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", w.second().auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}"), 409), "import-state");
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_APPROVED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(3)).contains(w.keeper().membershipId().toString()).contains(w.second().membershipId().toString())
                .contains(String.valueOf(CLEAN_FILE_PAISE));
        // the keeper reads the final state
        JsonNode forKeeper = getImport(w.keeper().auth(), w.centralId(), id);
        assertThat(forKeeper.get("status").asText()).isEqualTo("APPROVED");
        assertThat(forKeeper.get("decidedBy").get("name").asText()).isEqualTo("Second Owner");
    }

    @Test
    void theUploaderCannotApproveHisOrHerOwnImportAndTheDatabaseRefusesItToo() throws Exception {
        World w = world();
        // the owner uploads (an owner may) and submits
        JsonNode started = draftOfCleanFile(w.clinic().owner(), "CS", w.centralId(), w.codeA(), w.codeB());
        String id = idOf(started);
        submit(w.clinic().owner(), w.centralId(), id);
        JsonNode mine = getImport(w.clinic().owner(), w.centralId(), id);
        assertThat(mine.get("canApprove").asBoolean()).isFalse();
        assertThat(mine.get("approveLockReason").asText()).isEqualTo("SELF_UPLOADED");
        JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.clinic().owner(), null), 403);
        assertType(p, "self-approval");
        assertThat(p.get("detail").asText()).isEqualTo("Another owner must approve this import.");
        assertThat(getImport(w.clinic().owner(), w.centralId(), id).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(ok(HttpMethod.GET, STORES + "/" + w.centralId(), w.clinic().owner(), null, 200).get("hasStock").asBoolean()).isFalse();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_APPROVED")).isEmpty();
        // a second owner sees it as approvable
        JsonNode forSecond = getImport(w.second().auth(), w.centralId(), id);
        assertThat(forSecond.get("canApprove").asBoolean()).isTrue();
        // the database rule: approved_by must differ from uploaded_by (23514), even with the service out of the way
        assertThatThrownBy(() -> db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = uploaded_by, decided_at = now() where id = ?::uuid", id);
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains("ck_opening_stock_import_approver_differs");
        });
        assertThat(ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null, 200).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void onlyAnOwnerApprovesOrRejectsWhoeverElseGets403ForbiddenAndTheImportIsUnchanged() throws Exception {
        World w = world();
        JsonNode submitted = submittedByKeeper(w);
        String id = idOf(submitted);
        Pn nurse = crew(w.clinic(), "NURSE");
        mapStaff(w.clinic(), w.centralId(), nurse, "APPROVER"); // the approver of the store is not the owner
        Pn doctor = crew(w.clinic(), "DOCTOR");
        for (var who : List.of(w.keeper().auth(), nurse.auth(), doctor.auth(), crew(w.clinic(), "FRONT_DESK").auth(), crew(w.clinic(), "PHARMACIST").auth())) {
            assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", who, null), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", who, "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}"), 403), "forbidden");
        }
        assertThat(getImport(w.clinic().owner(), w.centralId(), id).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        // the owner's list over every store is the owner's alone
        assertType(problem(call(HttpMethod.GET, ALL_IMPORTS, w.keeper().auth(), null), 403), "forbidden");
        assertType(problem(call(HttpMethod.GET, ALL_IMPORTS, nurse.auth(), null), 403), "forbidden");
        assertFieldMessage(problem(call(HttpMethod.GET, ALL_IMPORTS + "?status=BOGUS", w.clinic().owner(), null), 400), "status", "Choose a valid option");
        assertFieldMessage(problem(call(HttpMethod.GET, ALL_IMPORTS + "?size=51", w.clinic().owner(), null), 400), "size", "Enter a whole number from 1 to 50");
        assertFieldMessage(problem(call(HttpMethod.GET, ALL_IMPORTS + "?page=1001", w.clinic().owner(), null), 400), "page", "Enter a whole number from 0 to 1000");
    }

    @Test
    void anImportOnlyOneStoreHasWaitingAndOnlyOneApprovedIsAlsoEnforcedByTheDatabase() throws Exception {
        World w = world();
        JsonNode waiting = submittedByKeeper(w);
        // a second WAITING_APPROVAL import of the same store written behind the service: refused by the unique index
        UUID unit = UUID.fromString(values(w.clinic(), "select id::text from pharmacy.unit").get(0));
        UUID other = UUID.randomUUID();
        String uploader = w.second().membershipId().toString();
        assertThatThrownBy(() -> db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, "insert into pharmacy.opening_stock_import (id, tenant_id, unit_id, store_id, row_count, uploaded_by, client_key, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid, ?::uuid)", other.toString(), w.clinic().tenantId().toString(),
                    unit.toString(), w.centralId(), uploader, UUID.randomUUID().toString(), uploader);
            DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'WAITING_APPROVAL', submitted_at = now() where id = ?::uuid", other.toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(e.getMessage()).contains("uq_opening_stock_import_one_waiting");
        });
        // one APPROVED per store
        ok(HttpMethod.POST, importUrl(w.centralId(), idOf(waiting)) + "/approve", w.clinic().owner(), null, 200);
        assertThatThrownBy(() -> db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, "insert into pharmacy.opening_stock_import (id, tenant_id, unit_id, store_id, row_count, uploaded_by, client_key, created_by) "
                    + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid, ?::uuid)", other.toString(), w.clinic().tenantId().toString(),
                    unit.toString(), w.centralId(), uploader, UUID.randomUUID().toString(), uploader);
            DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'WAITING_APPROVAL', submitted_at = now() where id = ?::uuid", other.toString());
            DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = ?::uuid, decided_at = now() where id = ?::uuid",
                    w.keeper().membershipId().toString(), other.toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(e.getMessage()).contains("uq_opening_stock_import_one_approved");
        });
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("APPROVED");
    }

    // ---------------- reject ----------------

    @Test
    void anOwnerRejectsWithAReasonTheUploaderMayStartAgainAndNothingIsApproved() throws Exception {
        World w = world();
        for (String reason : new String[] {"WRONG_FILE", "WRONG_QUANTITIES", "WRONG_STORE"}) {
            String id = idOf(submittedByKeeper(w));
            JsonNode rejected = ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", w.second().auth(),
                    "{\"reasonKey\":\"" + reason + "\",\"note\":null}", 200);
            assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
            assertThat(rejected.get("rejectReasonKey").asText()).isEqualTo(reason);
            assertThat(rejected.get("rejectNote").isNull()).isTrue();
            assertThat(rejected.get("decidedBy").get("membershipId").asText()).isEqualTo(w.second().membershipId().toString());
            assertThat(rejected.get("decidedAt").isNull()).isFalse();
            assertThat(rejected.get("canApprove").asBoolean()).isFalse();
            assertThat(rejected.get("approveLockReason").isNull()).isTrue();
        }
        // a note is kept with any reason
        String noted = idOf(submittedByKeeper(w));
        JsonNode withNote = ok(HttpMethod.POST, importUrl(w.centralId(), noted) + "/reject", w.second().auth(),
                "{\"reasonKey\":\"WRONG_FILE\",\"note\":\"  Counted twice  \"}", 200);
        assertThat(withNote.get("rejectNote").asText()).isEqualTo("Counted twice");
        assertThat(importStatuses(w.clinic(), w.centralId())).containsExactly("REJECTED", "REJECTED", "REJECTED", "REJECTED");
        assertThat(ok(HttpMethod.GET, STORES + "/" + w.centralId(), w.clinic().owner(), null, 200).get("hasStock").asBoolean()).isFalse();
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_REJECTED");
        assertThat(audit).hasSize(4);
        assertThat(audit.get(0).get(3)).contains("WRONG_FILE");
        // the uploader starts a new import
        assertThat(startImport(w.keeper().auth(), w.centralId(), 1).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void theOwnerWhoUploadedMayRejectHisOwnImportButNeverApproveIt() throws Exception {
        World w = world();
        String id = idOf(draftOfCleanFile(w.clinic().owner(), "CS", w.centralId(), w.codeA(), w.codeB()));
        submit(w.clinic().owner(), w.centralId(), id);
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.clinic().owner(), null), 403), "self-approval");
        JsonNode rejected = ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", w.clinic().owner(), "{\"reasonKey\":\"WRONG_STORE\",\"note\":null}", 200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("decidedBy").get("membershipId").asText()).isEqualTo(ownerMembership(w.clinic()));
    }

    @Test
    void theReasonIsRequiredAndOtherNeedsANoteOfAtLeastFourCharactersAndAtMost300() throws Exception {
        World w = world();
        String id = idOf(submittedByKeeper(w));
        String url = importUrl(w.centralId(), id) + "/reject";
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":null,\"note\":null}"), 400), "reasonKey", "Choose a reason");
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.second().auth(), "{\"note\":null}"), 400), "reasonKey", "Choose a reason");
        assertType(problem(call(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":\"BECAUSE\",\"note\":null}"), 400), "validation");
        for (String note : new String[] {"null", "\"\"", "\"abc\"", "\"   ab  \"", "\"a b\""}) {
            JsonNode p = problem(call(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":\"OTHER\",\"note\":" + note + "}"), 400);
            assertFieldMessage(p, "note", "Write at least 4 characters");
        }
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":\"OTHER\",\"note\":\"" + "n".repeat(301) + "\"}"), 400),
                "note", "Keep the note to 300 characters");
        assertThat(getImport(w.clinic().owner(), w.centralId(), id).get("status").asText()).as("nothing changed by the refusals").isEqualTo("WAITING_APPROVAL");
        // 4 characters is enough; 300 is the limit
        JsonNode ok = ok(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":\"OTHER\",\"note\":\"abcd\"}", 200);
        assertThat(ok.get("rejectReasonKey").asText()).isEqualTo("OTHER");
        assertThat(ok.get("rejectNote").asText()).isEqualTo("abcd");
        String longId = idOf(submittedByKeeper(w));
        assertThat(ok(HttpMethod.POST, importUrl(w.centralId(), longId) + "/reject", w.second().auth(),
                "{\"reasonKey\":\"OTHER\",\"note\":\"" + "n".repeat(300) + "\"}", 200).get("rejectNote").asText()).hasSize(300);
        // a draft is not waiting: approving it is 409; since contract 0.2.1 (DEC-076) an owner may reject it, and a bad body is 400 first
        String draft = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), draft) + "/reject", w.second().auth(), "{\"reasonKey\":null,\"note\":null}"), 400), "validation");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), draft) + "/approve", w.second().auth(), null), 409), "import-state");
        JsonNode draftNow = getImport(w.keeper().auth(), w.centralId(), draft);
        assertThat(draftNow.get("canApprove").asBoolean()).isFalse();
        assertThat(draftNow.get("approveLockReason").isNull()).as("null outside WAITING_APPROVAL").isTrue();
        JsonNode rejectedDraft = ok(HttpMethod.POST, importUrl(w.centralId(), draft) + "/reject", w.second().auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}", 200);
        assertThat(rejectedDraft.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectedDraft.get("submittedAt").isNull()).as("a rejected draft has no submittedAt").isTrue();
    }

    // ---------------- the store guard ----------------

    @Test
    void anUnmappedStoreIs404AMappedStoreWithoutTheCapabilityIs403WithTheAuditRowAndAnUnknownIdIsHandledInOrder() throws Exception {
        World w = world();
        String importId = idOf(submittedByKeeper(w));
        Pn nurse = crew(w.clinic(), "NURSE");
        mapStaff(w.clinic(), w.pharmacyId(), nurse, "STORE_USER"); // mapped to the pharmacy only
        // not mapped to the Central Store: the store is not confirmed to this caller
        String c = w.centralId();
        assertType(problem(call(HttpMethod.GET, importsUrl(c), nurse.auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importsUrl(c), nurse.auth(), js(startBody(UUID.randomUUID(), 1))), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, importUrl(c, importId), nurse.auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, importUrl(c, importId) + "/lines", nurse.auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importUrl(c, importId) + "/submit", nurse.auth(), null), 404), "not-found");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_STORE_ACCESS_DENIED")).as("a 404 is not a denial").isEmpty();
        // mapped to the pharmacy but a nurse has no opening-stock upload there: 403 store-permission-denied, audited with ids only
        String p = w.pharmacyId();
        JsonNode denied = problem(call(HttpMethod.GET, importsUrl(p), nurse.auth(), null), 403);
        assertType(denied, "store-permission-denied");
        assertThat(denied.get("detail").asText()).isEqualTo("You cannot do this in this store.");
        problem(call(HttpMethod.POST, importsUrl(p), nurse.auth(), js(startBody(UUID.randomUUID(), 1))), 403);
        problem(call(HttpMethod.GET, importUrl(p, UUID.randomUUID()), nurse.auth(), null), 403);
        problem(call(HttpMethod.GET, importUrl(p, UUID.randomUUID()) + "/lines", nurse.auth(), null), 403);
        problem(call(HttpMethod.POST, importUrl(p, UUID.randomUUID()) + "/lines", nurse.auth(), js(rows(row(1, "P", "G-000101", "B", farExpiry(), "1", "1", "1")))), 403);
        problem(call(HttpMethod.POST, importUrl(p, UUID.randomUUID()) + "/submit", nurse.auth(), null), 403);
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_STORE_ACCESS_DENIED");
        assertThat(audit).hasSize(6);
        for (List<String> row : audit) {
            assertThat(row.get(1)).as("the entity is the store").isEqualTo(p);
            assertThat(row.get(3)).contains(nurse.membershipId().toString()).contains("UPLOAD_OPENING_STOCK").doesNotContain("G-000101").doesNotContain("Test nurse");
        }
        assertThat(pharmacyRows(w.clinic(), "opening_stock_import")).as("refused calls wrote nothing").isOne();
        // unknown, foreign and malformed ids
        Clinic other = pharmacyClinic();
        JsonNode foreign = centralStore(other);
        assertType(problem(call(HttpMethod.GET, importsUrl(UUID.randomUUID()), w.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, importsUrl(idOf(foreign)), w.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importsUrl(idOf(foreign)), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1))), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, importsUrl("not-a-uuid"), w.clinic().owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.GET, importUrl(c, "not-a-uuid"), w.clinic().owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.GET, importUrl(c, UUID.randomUUID()), w.clinic().owner(), null), 404), "not-found");
        // an import of the Central Store addressed through the pharmacy store: not found there
        assertType(problem(call(HttpMethod.GET, importUrl(p, importId), w.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importUrl(p, importId) + "/approve", w.second().auth(), null), 404), "not-found");
    }

    @Test
    void aSecondPharmacistOrTheOwnerWritingToSomeoneElsesImportIs403StorePermissionDeniedAndAudited() throws Exception {
        World w = world();
        Pn keeper2 = crew(w.clinic(), "PHARMACIST", "Second Pharmacist");
        mapStaff(w.clinic(), w.centralId(), keeper2, "CENTRAL_STORE_USER");
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        String lines = importUrl(w.centralId(), id) + "/lines";
        String body = js(rows(row(1, "CS", w.codeA(), "X1", farExpiry(), "1", "1", "1"), row(2, "CS", w.codeA(), "X2", farExpiry(), "1", "1", "1")));
        // the second pharmacist may read the import, not write to it
        assertThat(getImport(keeper2.auth(), w.centralId(), id).get("status").asText()).isEqualTo("DRAFT");
        ok(HttpMethod.GET, lines, keeper2.auth(), null, 200);
        JsonNode denied = problem(call(HttpMethod.POST, lines, keeper2.auth(), body), 403);
        assertType(denied, "store-permission-denied");
        assertThat(denied.get("detail").asText()).isEqualTo("You cannot do this in this store.");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", keeper2.auth(), null), 403), "store-permission-denied");
        // the owner is not the starter either
        assertType(problem(call(HttpMethod.POST, lines, w.clinic().owner(), body), 403), "store-permission-denied");
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.clinic().owner(), null), 403), "store-permission-denied");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("receivedRows").asInt()).as("nothing was written").isZero();
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_STORE_ACCESS_DENIED");
        assertThat(audit).as("one audit row per refusal, committed although the request was refused").hasSize(4);
        assertThat(audit.get(0).get(3)).contains(keeper2.membershipId().toString()).contains("UPLOAD_OPENING_STOCK");
        assertThat(audit.get(2).get(3)).contains(ownerMembership(w.clinic()));
        // the starter still writes and submits
        addRows(w.keeper().auth(), w.centralId(), id, rows(row(1, "CS", w.codeA(), "X1", farExpiry(), "1", "1", "1"), row(2, "CS", w.codeA(), "X2", farExpiry(), "1", "1", "1")));
        assertThat(submit(w.keeper().auth(), w.centralId(), id).get("status").asText()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void aPharmacistMappedAtAStoreMayUploadThereButOnlyForThatStore() throws Exception {
        World w = world();
        Pn pharmacist = crew(w.clinic(), "PHARMACIST");
        mapStaff(w.clinic(), w.pharmacyId(), pharmacist, "STORE_USER"); // a Pharmacist team role has the upload capability in its store
        assertThat(startImport(pharmacist.auth(), w.pharmacyId(), 1).get("status").asText()).isEqualTo("DRAFT");
        assertType(problem(call(HttpMethod.GET, importsUrl(w.centralId()), pharmacist.auth(), null), 404), "not-found");
        // a Central Store user of the Central Store, who is not a pharmacist, may upload there too
        Pn keeper = crew(w.clinic(), "STORE_STAFF");
        mapStaff(w.clinic(), w.centralId(), keeper, "CENTRAL_STORE_USER");
        assertThat(startImport(keeper.auth(), w.centralId(), 1).get("status").asText()).isEqualTo("DRAFT");
        // a Store user who is not a pharmacist may not
        Pn user = crew(w.clinic(), "STORE_STAFF");
        mapStaff(w.clinic(), w.pharmacyId(), user, "STORE_USER");
        assertType(problem(call(HttpMethod.POST, importsUrl(w.pharmacyId()), user.auth(), js(startBody(UUID.randomUUID(), 1))), 403), "store-permission-denied");
    }

    // ---------------- tenants ----------------

    @Test
    void anotherClinicNeverSeesOrChangesMyImportsAndTheListsHoldOnlyMyOwn() throws Exception {
        World a = world();
        World b = world();
        String idA = idOf(submittedByKeeper(a));
        String idB = idOf(submittedByKeeper(b));
        // B asking for A's import through B's own Central Store, and through A's store
        assertType(problem(call(HttpMethod.GET, importUrl(b.centralId(), idA), b.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, importUrl(a.centralId(), idA), b.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.GET, importUrl(b.centralId(), idA) + "/lines", b.clinic().owner(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importUrl(b.centralId(), idA) + "/approve", b.second().auth(), null), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importUrl(b.centralId(), idA) + "/reject", b.second().auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}"), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importUrl(a.centralId(), idA) + "/approve", b.second().auth(), null), 404), "not-found");
        assertThat(getImport(a.clinic().owner(), a.centralId(), idA).get("status").asText()).isEqualTo("WAITING_APPROVAL");
        // each owner's list holds only the clinic's own imports
        JsonNode listA = ok(HttpMethod.GET, ALL_IMPORTS, a.clinic().owner(), null, 200);
        JsonNode listB = ok(HttpMethod.GET, ALL_IMPORTS, b.clinic().owner(), null, 200);
        assertThat(listA.get("total").asInt()).isOne();
        assertThat(listA.get("items").get(0).get("id").asText()).isEqualTo(idA);
        assertThat(listB.get("total").asInt()).isOne();
        assertThat(listB.get("items").get(0).get("id").asText()).isEqualTo(idB);
        // the rows and issues of A's import are invisible to B at the database too
        assertThat(count(b.clinic(), "select count(*) from pharmacy.opening_stock_line where import_id = ?::uuid", idA)).isZero();
        assertThat(count(a.clinic(), "select count(*) from pharmacy.opening_stock_line where import_id = ?::uuid", idA)).isEqualTo(3);
        // a product code of another clinic's catalogue adoption is unknown here
        String bad = idOf(startImport(b.keeper().auth(), b.centralId(), 1));
        addRows(b.keeper().auth(), b.centralId(), bad, rows(row(1, "CS", "U-AAA-9999", "Z1", farExpiry(), "1", "1", "1")));
        assertThat(getImport(b.keeper().auth(), b.centralId(), bad).get("errorCount").asInt()).isOne();
    }

    // ---------------- the database behind the service ----------------

    @Test
    void theDatabaseRefusesEveryWriteToLinesAndIssuesOnceTheImportIsNoLongerADraftAndOtherRulesOfTheTables() throws Exception {
        World w = world();
        // a draft with one issue (product unknown) and one clean line
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        addRows(w.keeper().auth(), w.centralId(), id, rows(row(1, "CS", w.codeA(), "D1", farExpiry(), "10", "5", "1"),
                row(2, "CS", "G-999999", "D2", farExpiry(), "10", "5", "1")));
        String lineId = values(w.clinic(), "select id::text from pharmacy.opening_stock_line where import_id = ?::uuid and row_no = 2", id).get(0);
        String unit = values(w.clinic(), "select id::text from pharmacy.unit").get(0);
        String tenant = w.clinic().tenantId().toString();
        String actor = w.keeper().membershipId().toString();
        // while DRAFT: a row number beyond the import's row count and a change of an issue other than superseded are refused
        expectCheck(w.clinic(), "beyond the import row count", c -> DbAccess.execute(c, "insert into pharmacy.opening_stock_line (tenant_id, unit_id, import_id, row_no, created_by) "
                + "values (?::uuid, ?::uuid, ?::uuid, 3, ?::uuid)", tenant, unit, id, actor));
        expectCheck(w.clinic(), "can only be marked superseded", c -> DbAccess.execute(c, "update pharmacy.opening_stock_line_issue set code = 'batch-invalid' where line_id = ?::uuid", lineId));
        expectCheck(w.clinic(), "can only be marked superseded", c -> DbAccess.execute(c, "update pharmacy.opening_stock_line_issue set superseded = false where line_id = ?::uuid", lineId));
        // submit is refused for the errors, so use a clean second import for the WAITING and APPROVED states
        String clean = idOf(draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB()));
        String cleanLine = values(w.clinic(), "select id::text from pharmacy.opening_stock_line where import_id = ?::uuid and row_no = 1", clean).get(0);
        // (the first import was replaced by the second start; a REPLACED import also holds still)
        expectCheck(w.clinic(), "can change only while the import is a draft", c -> DbAccess.execute(c, "update pharmacy.opening_stock_line set qty = 9 where id = ?::uuid", lineId));
        expectCheck(w.clinic(), "can change only while the import is a draft", c -> DbAccess.execute(c, "update pharmacy.opening_stock_line set qty = 9 where import_id = ?::uuid", id));
        submit(w.keeper().auth(), w.centralId(), clean);
        for (String state : new String[] {"waiting for approval", "approved"}) {
            if (state.equals("approved")) {
                ok(HttpMethod.POST, importUrl(w.centralId(), clean) + "/approve", w.second().auth(), null, 200);
            }
            expectCheck(w.clinic(), "can change only while the import is a draft", c -> DbAccess.execute(c, "update pharmacy.opening_stock_line set qty = 9 where id = ?::uuid", cleanLine));
            expectCheck(w.clinic(), "can change only while the import is a draft", c -> DbAccess.execute(c, "update pharmacy.opening_stock_line set batch_raw = 'x' where import_id = ?::uuid", clean));
            expectCheck(w.clinic(), "can change only while the import is a draft", c -> DbAccess.execute(c, "insert into pharmacy.opening_stock_line "
                    + "(tenant_id, unit_id, import_id, row_no, created_by) values (?::uuid, ?::uuid, ?::uuid, 2, ?::uuid)", tenant, unit, clean, actor));
            expectCheck(w.clinic(), "can change only while the import is a draft", c -> DbAccess.execute(c, "insert into pharmacy.opening_stock_line_issue "
                    + "(tenant_id, unit_id, line_id, field, code, severity, created_by) values (?::uuid, ?::uuid, ?::uuid, 'batch', 'batch-invalid', 'ERROR', ?::uuid)",
                    tenant, unit, cleanLine, actor));
        }
        // an approved import is final: not even the status can change; the totals of a waiting one are fixed
        expectCheck(w.clinic(), "is final", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'REJECTED' where id = ?::uuid", clean));
        expectCheck(w.clinic(), "is final", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set total_quantity = 1 where id = ?::uuid", clean));
        // an import must start as a draft
        expectCheck(w.clinic(), "must start as a draft", c -> DbAccess.execute(c, "insert into pharmacy.opening_stock_import (tenant_id, unit_id, store_id, status, row_count, "
                + "uploaded_by, client_key, created_by) values (?::uuid, ?::uuid, ?::uuid, 'WAITING_APPROVAL', 1, ?::uuid, ?::uuid, ?::uuid)",
                tenant, unit, w.centralId(), actor, UUID.randomUUID().toString(), actor));
    }

    @Test
    void aWaitingImportKeepsItsTotalsAndOnlyMovesToApprovedRejectedOrReplacedAndNotAcceptedNeedsAnError() throws Exception {
        World w = world();
        String id = idOf(submittedByKeeper(w));
        expectCheck(w.clinic(), "totals are fixed once submitted", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = ?::uuid, decided_at = now(), total_value_paise = 1 where id = ?::uuid", w.second().membershipId().toString(), id));
        expectCheck(w.clinic(), "totals are fixed once submitted", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'REJECTED', reject_reason_key = 'WRONG_FILE', decided_by = ?::uuid, decided_at = now(), line_count = 9 where id = ?::uuid", w.second().membershipId().toString(), id));
        expectCheck(w.clinic(), "can only be approved, rejected or replaced", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'DRAFT' where id = ?::uuid", id));
        expectCheck(w.clinic(), "can only be approved, rejected or replaced", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'NOT_ACCEPTED', error_count = 1 where id = ?::uuid", id));
        expectCheck(w.clinic(), "identity cannot change", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set row_count = 99 where id = ?::uuid", id));
        expectCheck(w.clinic(), "identity cannot change", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set store_id = ?::uuid where id = ?::uuid", w.pharmacyId(), id));
        // a draft: NOT_ACCEPTED needs at least one error; approving a draft is not a transition
        String draft = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        assertThatThrownBy(() -> db().inTenant(w.clinic().tenantId(), c -> {
            DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'NOT_ACCEPTED' where id = ?::uuid", draft);
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains("ck_opening_stock_import_not_accepted");
        });
        expectCheck(w.clinic(), "cannot go from draft to APPROVED", c -> DbAccess.execute(c, "update pharmacy.opening_stock_import set status = 'APPROVED', "
                + "decided_by = ?::uuid, decided_at = now(), submitted_at = now() where id = ?::uuid", w.second().membershipId().toString(), draft));
    }

    @FunctionalInterface
    private interface Statement {
        void run(java.sql.Connection c) throws SQLException;
    }

    private void expectCheck(Clinic clinic, String messagePart, Statement statement) throws SQLException {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            statement.run(c);
            return null;
        })).as(messagePart).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo("23514");
            assertThat(e.getMessage()).contains(messagePart);
        });
    }
}
