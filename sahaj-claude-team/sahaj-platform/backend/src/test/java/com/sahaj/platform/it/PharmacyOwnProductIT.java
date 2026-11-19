package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-6 on the real database: create and edit an own product, the duplicate check (salt, strength and form, "1 g" equals
 * "1000 mg"), "Use global product" and "Create anyway", the {@code clientKey} replay, the code {@code U-<unit>-nnnn} (also under
 * concurrent creates), the units that lock once the product has stock, and the read-only global fields of an adopted product.
 * Texts and field names are those of docs/api/pharmacy.yaml 0.1.2. Test plan: docs/test-plans/SAH-78.md (T78-6-*).
 */
@OnTestDatabase
class PharmacyOwnProductIT extends Sah78BSupport {

    private JsonNode refused(Clinic clinic, Map<String, Object> body) throws Exception {
        return problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body)), 400);
    }

    private static List<String> candidateCodes(JsonNode problemOrList) {
        List<String> out = new ArrayList<>();
        JsonNode list = problemOrList.has("candidates") ? problemOrList.get("candidates") : problemOrList;
        list.forEach(c -> out.add(c.get("code").asText()));
        return out;
    }

    // ---------------- create ----------------

    @Test
    void anOwnProductIsCreatedWithTheNextCodeOfTheUnitAndReadsBackAsOwn() throws Exception {
        Clinic clinic = pharmacyClinic();
        String unit = unitCode(clinic);
        Map<String, Object> body = ownBody("Test dressing kit (OT)", "Dressing kit", "1 kit", "kit");
        body.put("purchaseUnit", "Kit");
        body.put("issueUnit", "kit");
        body.put("conversion", 1);
        body.put("hsn", "3005");
        JsonNode created = ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body), 201);
        assertThat(created.get("code").asText()).isEqualTo("U-" + unit + "-0001");
        assertThat(created.get("code").asText()).matches("^U-[A-Z0-9]{3}-0001$");
        assertThat(created.get("source").asText()).isEqualTo("OWN");
        assertThat(created.get("catalogueProductId").isNull()).isTrue();
        assertThat(created.get("productId").isNull()).isFalse();
        assertThat(created.get("name").asText()).isEqualTo("Test dressing kit (OT)");
        assertThat(created.get("madeInHouse").asBoolean()).isTrue();
        assertThat(created.get("manufacturer").isNull()).isTrue();
        assertThat(created.get("gstRate").asText()).isEqualTo("12");
        assertThat(created.get("schedule").asText()).isEqualTo("NONE");
        assertThat(created.get("ndps").asBoolean()).as("an own product has ndps false in Phase 1").isFalse();
        assertThat(created.get("sample").asBoolean()).isFalse();
        assertThat(created.get("inUnit").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("hasStock").asBoolean()).isFalse();
        assertThat(created.get("unitsLocked").asBoolean()).isFalse();
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("barcodes")).as("no catalogue barcodes").isEmpty();
        assertThat(created.get("localSettings").get("active").asBoolean()).isTrue();
        // the second product of the unit is 0002, and a read gives the same record
        JsonNode second = createOwn(clinic, ownBody("Second kit"));
        assertThat(second.get("code").asText()).isEqualTo("U-" + unit + "-0002");
        assertThat(getProduct(clinic, created.get("productId").asText())).isEqualTo(created);
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_OWN_PRODUCT_CREATED");
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).get(3)).contains("Test dressing kit (OT)").contains("U-" + unit + "-0001");
        assertThat(audit.get(0).get(3)).as("no duplicates recorded when there were none").doesNotContain("duplicateOfCodes");
        // a manufacturer instead of made in-house
        Map<String, Object> maker = ownBody("Test gauze roll");
        maker.put("madeInHouse", false);
        maker.put("manufacturer", "Demo Gauze Works (fake)");
        JsonNode made = createOwn(clinic, maker);
        assertThat(made.get("manufacturer").asText()).isEqualTo("Demo Gauze Works (fake)");
        assertThat(made.get("madeInHouse").asBoolean()).isFalse();
        // text is trimmed
        Map<String, Object> spaced = ownBody("  Test padded name  ");
        assertThat(createOwn(clinic, spaced).get("name").asText()).isEqualTo("Test padded name");
    }

    @Test
    void theNumberingIsPerUnitAndEachClinicStartsAtOne() throws Exception {
        Clinic a = pharmacyClinic();
        Clinic b = pharmacyClinic();
        JsonNode a1 = createOwn(a, ownBody("A one"));
        JsonNode b1 = createOwn(b, ownBody("B one"));
        JsonNode a2 = createOwn(a, ownBody("A two"));
        assertThat(a1.get("code").asText()).endsWith("-0001");
        assertThat(b1.get("code").asText()).endsWith("-0001");
        assertThat(a2.get("code").asText()).endsWith("-0002");
        assertThat(a1.get("code").asText()).startsWith("U-" + unitCode(a) + "-");
    }

    @Test
    void twoOrMoreCreatesAtTheSameMomentGetDifferentCodesWithoutGapsOrErrors() throws Exception {
        Clinic clinic = pharmacyClinic();
        String unit = unitCode(clinic);
        int n = 4;
        List<RequestPostProcessor> callers = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            callers.add(clinic.owner());
            bodies.add(js(ownBody("Concurrent product " + i)));
        }
        List<String> answers = atTheSameMoment(HttpMethod.POST, PRODUCTS, callers, bodies);
        assertThat(statusCount(answers, 201)).as(answers.toString()).isEqualTo(n);
        Set<String> codes = new HashSet<>();
        for (String a : answers) {
            codes.add(bodyOf(a).get("code").asText());
        }
        Set<String> expected = new HashSet<>();
        for (int i = 1; i <= n; i++) {
            expected.add("U-" + unit + "-000" + i);
        }
        assertThat(codes).as("one number each, no gap").isEqualTo(expected);
        assertThat(pharmacyRows(clinic, "product")).isEqualTo(n);
    }

    @Test
    void aFailedCreateGivesTheNumberBackNoGapIsLeft() throws Exception {
        Clinic clinic = pharmacyClinic();
        String unit = unitCode(clinic);
        JsonNode first = createOwn(clinic, ownBody("First"));
        Map<String, Object> bad = ownBody("Bad barcode");
        bad.put("barcode", "no good!");
        refused(clinic, bad);
        Map<String, Object> clash = ownBody("Clash");
        clash.put("barcode", "UNIQ-1");
        createOwn(clinic, clash); // 0002
        Map<String, Object> clash2 = ownBody("Clash again");
        clash2.put("barcode", "uniq-1");
        assertType(problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(clash2)), 409), "barcode-in-use");
        JsonNode next = createOwn(clinic, ownBody("After the refusals"));
        assertThat(first.get("code").asText()).isEqualTo("U-" + unit + "-0001");
        assertThat(next.get("code").asText()).as("refused creates took no number").isEqualTo("U-" + unit + "-0003");
    }

    // ---------------- validation ----------------

    @Test
    void anEmptyFormIsRefusedWithEveryFieldMessageTogetherAndNothingIsSaved() throws Exception {
        Clinic clinic = pharmacyClinic();
        Map<String, Object> body = ownBody("x", "x", "x", "x");
        body.put("name", "");
        body.put("salt", " ");
        body.put("strength", "");
        body.put("form", "");
        body.put("purchaseUnit", "");
        body.put("issueUnit", "");
        body.put("conversion", 0);
        body.put("madeInHouse", false);
        body.put("manufacturer", "");
        body.put("hsn", "123");
        body.put("gstRate", "abc");
        body.put("schedule", null);
        JsonNode p = refused(clinic, body);
        assertType(p, "validation");
        assertFieldMessage(p, "name", "Enter the product name");
        assertFieldMessage(p, "salt", "Enter the salt");
        assertFieldMessage(p, "strength", "Enter the strength");
        assertFieldMessage(p, "form", "Enter the form");
        assertFieldMessage(p, "purchaseUnit", "Enter the purchase unit");
        assertFieldMessage(p, "issueUnit", "Enter the issue unit");
        assertFieldMessage(p, "conversion", "Enter a whole number, 1 or more");
        assertFieldMessage(p, "manufacturer", "Enter the manufacturer or choose Made in-house");
        assertFieldMessage(p, "hsn", "Enter a 4 to 8 digit HSN");
        assertFieldMessage(p, "gstRate", "Enter a GST % from 0 to 100");
        assertFieldMessage(p, "schedule", "Choose the schedule");
        assertThat(pharmacyRows(clinic, "product")).isZero();
        assertThat(auditRowsOf(clinic, "PHARMACY_OWN_PRODUCT_CREATED")).isEmpty();
    }

    @Test
    void theLimitsOfEachFieldAreExactAtTheBoundary() throws Exception {
        Clinic clinic = pharmacyClinic();
        // lengths: name, salt, strength 80; form 30; units 20; manufacturer 80; barcode 40
        Map<String, Object> edge = ownBody("n".repeat(80), "s".repeat(80) + "q", "5 mg", "f".repeat(30));
        edge.put("salt", "Zlimit" + "s".repeat(74));
        edge.put("strength", "t".repeat(80));
        edge.put("purchaseUnit", "p".repeat(20));
        edge.put("issueUnit", "i".repeat(20));
        edge.put("conversion", 10_000);
        edge.put("madeInHouse", false);
        edge.put("manufacturer", "m".repeat(80));
        edge.put("hsn", "12345678");
        edge.put("gstRate", "100");
        edge.put("barcode", "B".repeat(40));
        JsonNode accepted = createOwn(clinic, edge);
        assertThat(accepted.get("gstRate").asText()).isEqualTo("100");
        Map<String, Object> over = ownBody("n".repeat(81), "Zover", "5 mg", "f".repeat(31));
        over.put("salt", "s".repeat(81));
        over.put("strength", "t".repeat(81));
        over.put("purchaseUnit", "p".repeat(21));
        over.put("issueUnit", "i".repeat(21));
        over.put("madeInHouse", false);
        over.put("manufacturer", "m".repeat(81));
        over.put("barcode", "B".repeat(41));
        JsonNode p = refused(clinic, over);
        assertFieldMessage(p, "name", "Keep it to 80 characters");
        assertFieldMessage(p, "salt", "Keep it to 80 characters");
        assertFieldMessage(p, "strength", "Keep it to 80 characters");
        assertFieldMessage(p, "form", "Keep it to 30 characters");
        assertFieldMessage(p, "purchaseUnit", "Keep it to 20 characters");
        assertFieldMessage(p, "issueUnit", "Keep it to 20 characters");
        assertFieldMessage(p, "manufacturer", "Keep it to 80 characters");
        assertFieldMessage(p, "barcode", "Use up to 40 letters, digits or hyphens");
        // conversion: 1 to 10000, whole
        for (Object bad : new Object[] {0, -3, 10_001, 2.5}) {
            Map<String, Object> b = ownBody("Conv " + bad);
            b.put("conversion", bad);
            assertFieldMessage(refused(clinic, b), "conversion", "Enter a whole number, 1 or more");
        }
        Map<String, Object> one = ownBody("Conv one");
        one.put("conversion", 1);
        createOwn(clinic, one);
        // HSN: 4 to 8 digits
        for (String bad : new String[] {"123", "123456789", "12a4", "", " "}) {
            Map<String, Object> b = ownBody("Hsn " + bad.trim());
            b.put("hsn", bad);
            assertFieldMessage(refused(clinic, b), "hsn", "Enter a 4 to 8 digit HSN");
        }
        Map<String, Object> hsn4 = ownBody("Hsn four");
        hsn4.put("hsn", "1234");
        createOwn(clinic, hsn4);
        // GST: 0 to 100, at most 2 decimals
        for (String bad : new String[] {"101", "100.5", "-1", "12.345", "x", ""}) {
            Map<String, Object> b = ownBody("Gst " + bad);
            b.put("gstRate", bad);
            assertFieldMessage(refused(clinic, b), "gstRate", "Enter a GST % from 0 to 100");
        }
        for (String good : new String[] {"0", "5", "12.5", "99.99", "100.00"}) {
            Map<String, Object> b = ownBody("Gst good " + good);
            b.put("gstRate", good);
            createOwn(clinic, b);
        }
        // schedule: only the four values; null is "Choose the schedule"
        Map<String, Object> unknown = ownBody("Schedule unknown");
        unknown.put("schedule", "Y");
        assertType(problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(unknown)), 400), "validation");
        for (String s : new String[] {"NONE", "H", "H1", "X"}) {
            Map<String, Object> b = ownBody("Schedule " + s);
            b.put("schedule", s);
            assertThat(createOwn(clinic, b).get("schedule").asText()).isEqualTo(s);
        }
    }

    @Test
    void theBodyIsClosedAndNeedsEveryRequiredFieldAndAClientKey() throws Exception {
        Clinic clinic = pharmacyClinic();
        // no NDPS field in Phase 1; no tenant, unit or code from the client
        for (String extra : new String[] {"ndps", "code", "tenantId", "unitId", "hasStock", "source", "version"}) {
            Map<String, Object> b = ownBody("Extra " + extra);
            b.put(extra, extra.equals("ndps") || extra.equals("hasStock") ? (Object) true : "x");
            assertType(problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(b)), 400), "validation");
        }
        for (String missing : new String[] {"clientKey", "name", "salt", "strength", "form", "purchaseUnit", "issueUnit", "conversion",
            "manufacturer", "madeInHouse", "hsn", "gstRate", "schedule"}) {
            Map<String, Object> b = ownBody("Missing " + missing);
            b.remove(missing);
            JsonNode p = refused(clinic, b);
            assertThat(p.toString()).as("missing " + missing).contains(missing);
        }
        assertType(problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), "{\"clientKey\":"), 400), "malformed-request");
        assertThat(pharmacyRows(clinic, "product")).isZero();
    }

    // ---------------- duplicate check ----------------

    @Test
    void theSameSaltStrengthAndFormAsAGlobalProductIs409WithCandidatesAndNothingIsSavedThenUseGlobalOrCreateAnyway() throws Exception {
        Clinic clinic = pharmacyClinic();
        Map<String, Object> body = ownBody("Test ceftriaxone vial", "Ceftriaxone", "1 g", "injection");
        JsonNode dup = problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body)), 409);
        assertType(dup, "possible-duplicate");
        assertThat(dup.get("detail").asText()).isEqualTo("A product with the same salt, strength and form exists.");
        JsonNode candidate = dup.get("candidates").get(0);
        assertThat(candidate.get("source").asText()).isEqualTo("GLOBAL");
        assertThat(candidate.get("code").asText()).isEqualTo("G-000101");
        assertThat(candidate.get("name").asText()).isEqualTo("Sample Ceftriaxone 1 g injection");
        assertThat(candidate.get("manufacturer").asText()).isEqualTo("Sample Labs (fake)");
        assertThat(candidate.get("inUnit").asText()).isEqualTo("NOT_ADOPTED");
        assertThat(candidate.get("productId").isNull()).isTrue();
        assertThat(candidate.get("catalogueProductId").isNull()).isFalse();
        assertThat(dup.get("candidates")).hasSize(1);
        assertThat(pharmacyRows(clinic, "product")).as("nothing saved").isZero();
        assertThat(auditRowsOf(clinic, "PHARMACY_OWN_PRODUCT_CREATED")).isEmpty();
        // "Use global product": adopt the candidate
        JsonNode adopted = ok(HttpMethod.POST, adoptUrl(candidate.get("catalogueProductId").asText()), clinic.owner(), null, 201);
        assertThat(adopted.get("code").asText()).isEqualTo("G-000101");
        // the global product is now listed as ADOPTED in a new check
        JsonNode again = problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body)), 409);
        assertThat(again.get("candidates").get(0).get("inUnit").asText()).isEqualTo("ADOPTED");
        assertThat(again.get("candidates").get(0).get("productId").asText()).isEqualTo(adopted.get("productId").asText());
        // "Create anyway": the server saves it and records the codes it matched, recomputed by the server
        body.put("ignoreDuplicates", true);
        JsonNode created = ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body), 201);
        assertThat(created.get("source").asText()).isEqualTo("OWN");
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_OWN_PRODUCT_CREATED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(3)).contains("duplicateOfCodes").contains("G-000101");
    }

    @Test
    void oneGramAndOneThousandMilligramsAreTheSameStrengthAndCaseAndSpacesDoNotMatter() throws Exception {
        Clinic clinic = pharmacyClinic();
        for (String[] sameAsCeftriaxone1g : new String[][] {
            {"Ceftriaxone", "1000 mg", "injection"}, {"ceftriaxone", "1000mg", "Injection"}, {"  CEFTRIAXONE ", "1 G", "INJECTION"},
            {"Ceftriaxone", "1g", "injection"}, {"Ceftriaxone", "1.0 g", "injection"}, {"Ceftriaxone", "1,000 mg".replace(",", ""), "injection"}}) {
            Map<String, Object> b = ownBody("Dup " + sameAsCeftriaxone1g[1], sameAsCeftriaxone1g[0], sameAsCeftriaxone1g[1], sameAsCeftriaxone1g[2]);
            JsonNode dup = problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(b)), 409);
            assertThat(candidateCodes(dup)).as(String.join("|", sameAsCeftriaxone1g)).containsExactly("G-000101");
        }
        // 500 mg is a different strength: only the 500 mg product matches (G-000102), not the 1 g one
        Map<String, Object> half = ownBody("Dup half", "Ceftriaxone", "0.5 g", "injection");
        assertThat(candidateCodes(problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(half)), 409))).containsExactly("G-000102");
        // a different form is no duplicate
        Map<String, Object> vial = ownBody("Not dup", "Ceftriaxone", "1 g", "dry powder");
        createOwn(clinic, vial);
        // the check endpoint gives the same answer without saving
        JsonNode check = ok(HttpMethod.GET, DUPLICATES + "?salt=ceftriaxone&strength=1000%20mg&form=injection", clinic.owner(), null, 200);
        assertThat(candidateCodes(check)).containsExactly("G-000101");
        assertThat(ok(HttpMethod.GET, DUPLICATES + "?salt=ceftriaxone&strength=2%20g&form=injection", clinic.owner(), null, 200).get("candidates")).isEmpty();
        assertThat(pharmacyRows(clinic, "product")).as("the check saves nothing").isOne();
    }

    @Test
    void anOwnProductOfTheUnitIsACandidateTooAfterTheGlobalOnesAndAnotherClinicsOwnProductIsNot() throws Exception {
        Clinic clinic = pharmacyClinic();
        Clinic other = pharmacyClinic();
        Map<String, Object> first = ownBody("Own first", "Quuxine", "250 mg", "capsule");
        JsonNode created = createOwn(clinic, first);
        createOwn(other, ownBody("Other clinic", "Quuxine", "250 mg", "capsule"));
        Map<String, Object> second = ownBody("Own second", "Quuxine", "250mg", "Capsule");
        JsonNode dup = problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(second)), 409);
        assertThat(dup.get("candidates")).hasSize(1);
        JsonNode candidate = dup.get("candidates").get(0);
        assertThat(candidate.get("source").asText()).isEqualTo("OWN");
        assertThat(candidate.get("productId").asText()).isEqualTo(created.get("productId").asText());
        assertThat(candidate.get("code").asText()).isEqualTo(created.get("code").asText());
        assertThat(candidate.get("inUnit").asText()).isEqualTo("ACTIVE");
        assertThat(candidate.get("catalogueProductId").isNull()).isTrue();
        // the owner's own product listed after a global match: same key as the catalogue Ceftriaxone 1 g
        Map<String, Object> cef = ownBody("Cef own", "Ceftriaxone", "1 g", "injection");
        cef.put("ignoreDuplicates", true);
        createOwn(clinic, cef);
        JsonNode both = ok(HttpMethod.GET, DUPLICATES + "?salt=Ceftriaxone&strength=1%20g&form=injection", clinic.owner(), null, 200);
        assertThat(both.get("candidates")).hasSize(2);
        assertThat(both.get("candidates").get(0).get("source").asText()).as("global first").isEqualTo("GLOBAL");
        assertThat(both.get("candidates").get(1).get("source").asText()).isEqualTo("OWN");
        // excludeProductId leaves out the product being edited
        String cefId = both.get("candidates").get(1).get("productId").asText();
        JsonNode without = ok(HttpMethod.GET, DUPLICATES + "?salt=Ceftriaxone&strength=1%20g&form=injection&excludeProductId=" + cefId,
                clinic.owner(), null, 200);
        assertThat(candidateCodes(without)).containsExactly("G-000101");
        // a switched-off own product is not a candidate: only ACTIVE ones count
        Map<String, Object> stop = localBody(getProduct(clinic, cefId));
        stop.put("active", false);
        ok(HttpMethod.PUT, localUrl(cefId), clinic.owner(), js(stop), 200);
        assertThat(candidateCodes(ok(HttpMethod.GET, DUPLICATES + "?salt=Ceftriaxone&strength=1%20g&form=injection", clinic.owner(), null, 200)))
                .containsExactly("G-000101");
    }

    @Test
    void theDuplicateCheckEndpointNeedsTheOwnerAndAllThreeFieldsAndAGoodExcludeId() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode none = problem(call(HttpMethod.GET, DUPLICATES, clinic.owner(), null), 400);
        assertFieldMessage(none, "salt", "Enter the salt");
        assertFieldMessage(none, "strength", "Enter the strength");
        assertFieldMessage(none, "form", "Enter the form");
        assertFieldMessage(problem(call(HttpMethod.GET, DUPLICATES + "?salt=a&strength=b&form=c&excludeProductId=nope", clinic.owner(), null), 400),
                "excludeProductId", "Choose a valid option");
        assertFieldMessage(problem(call(HttpMethod.GET, DUPLICATES + "?salt=" + "s".repeat(81) + "&strength=b&form=" + "f".repeat(31), clinic.owner(), null), 400),
                "salt", "Keep it to 80 characters");
        ok(HttpMethod.GET, DUPLICATES + "?salt=a&strength=b&form=c&excludeProductId=", clinic.owner(), null, 200);
        for (String role : new String[] {"PHARMACIST", "NURSE", "DOCTOR"}) {
            assertType(problem(call(HttpMethod.GET, DUPLICATES + "?salt=a&strength=b&form=c", crew(clinic, role).auth(), null), 403), "forbidden");
            assertType(problem(call(HttpMethod.GET, DUPLICATES, crew(clinic, role).auth(), null), 403), "forbidden");
        }
    }

    // ---------------- clientKey ----------------

    @Test
    void theSameClientKeyFromTheSameCallerAnswersTheFirstProductAgainAndWritesNothing() throws Exception {
        Clinic clinic = pharmacyClinic();
        Map<String, Object> body = ownBody("Replay product");
        JsonNode first = ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body), 201);
        JsonNode replay = ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body), 200);
        assertThat(replay).isEqualTo(first);
        assertThat(pharmacyRows(clinic, "product")).isOne();
        assertThat(auditRowsOf(clinic, "PHARMACY_OWN_PRODUCT_CREATED")).hasSize(1);
        // the replay is answered before the body is checked, and before the duplicate check
        Map<String, Object> changed = ownBody("Replay product", "Ceftriaxone", "1 g", "injection");
        changed.put("clientKey", body.get("clientKey"));
        assertThat(ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(changed), 200).get("productId").asText()).isEqualTo(first.get("productId").asText());
        // the next number is not used up by the replays
        assertThat(createOwn(clinic, ownBody("Next")).get("code").asText()).endsWith("-0002");
    }

    @Test
    void theSameClientKeyFromAnotherCallerIs409ClientKeyReusedAndWritesNothing() throws Exception {
        Clinic clinic = pharmacyClinic();
        Pn second = crew(clinic, "OWNER", "Second owner");
        Map<String, Object> body = ownBody("Key product");
        ok(HttpMethod.POST, PRODUCTS, clinic.owner(), js(body), 201);
        JsonNode p = problem(call(HttpMethod.POST, PRODUCTS, second.auth(), js(body)), 409);
        assertType(p, "client-key-reused");
        assertThat(p.get("detail").asText()).isEqualTo(KEY_REUSED_TEXT);
        assertThat(pharmacyRows(clinic, "product")).isOne();
        // another clinic using the same key is a different record
        Clinic other = pharmacyClinic();
        ok(HttpMethod.POST, PRODUCTS, other.owner(), js(body), 201);
        assertThat(pharmacyRows(other, "product")).isOne();
    }

    @Test
    void onlyTheOwnerCreatesAndABarcodeUsedByAnotherActiveProductIs409() throws Exception {
        Clinic clinic = pharmacyClinic();
        for (String role : new String[] {"PHARMACIST", "NURSE", "DOCTOR", "STORE_STAFF", "FRONT_DESK"}) {
            assertType(problem(call(HttpMethod.POST, PRODUCTS, crew(clinic, role).auth(), js(ownBody("By " + role))), 403), "forbidden");
        }
        assertThat(pharmacyRows(clinic, "product")).isZero();
        Map<String, Object> a = ownBody("Barcode holder");
        a.put("barcode", "OWN-BC-1");
        createOwn(clinic, a);
        Map<String, Object> b = ownBody("Barcode taker");
        b.put("barcode", "own-bc-1");
        JsonNode p = problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(b)), 409);
        assertType(p, "barcode-in-use");
        assertThat(p.get("detail").asText()).isEqualTo("That barcode is already used by Barcode holder");
        // an adopted product with that local barcode holds it too
        JsonNode adopted = adopt(clinic, "G-000103");
        Map<String, Object> local = localBody(adopted);
        local.put("barcode", "ADOPT-BC-1");
        ok(HttpMethod.PUT, localUrl(adopted.get("productId").asText()), clinic.owner(), js(local), 200);
        Map<String, Object> c = ownBody("Wants adopted barcode");
        c.put("barcode", "adopt-bc-1");
        assertType(problem(call(HttpMethod.POST, PRODUCTS, clinic.owner(), js(c)), 409), "barcode-in-use");
        // another clinic may use it
        Clinic other = pharmacyClinic();
        Map<String, Object> d = ownBody("Other clinic barcode");
        d.put("barcode", "OWN-BC-1");
        createOwn(other, d);
    }

    // ---------------- edit ----------------

    @Test
    void anOwnProductIsEditedWithItsVersionAuditedAndItsCodeNeverChanges() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode created = createOwn(clinic, ownBody("Edit me"));
        String id = created.get("productId").asText();
        Map<String, Object> edit = updateBody(created);
        edit.put("name", "Edited name");
        edit.put("hsn", "300490");
        edit.put("gstRate", "5.5");
        edit.put("schedule", "H");
        edit.put("madeInHouse", false);
        edit.put("manufacturer", "Test Maker (fake)");
        JsonNode saved = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(edit), 200);
        assertThat(saved.get("version").asLong()).isOne();
        assertThat(saved.get("name").asText()).isEqualTo("Edited name");
        assertThat(saved.get("hsn").asText()).isEqualTo("300490");
        assertThat(saved.get("gstRate").asText()).isEqualTo("5.5");
        assertThat(saved.get("schedule").asText()).isEqualTo("H");
        assertThat(saved.get("manufacturer").asText()).isEqualTo("Test Maker (fake)");
        assertThat(saved.get("code").asText()).isEqualTo(created.get("code").asText());
        assertThat(saved.get("productId").asText()).isEqualTo(id);
        List<List<String>> audit = auditRowsOf(clinic, "PHARMACY_PRODUCT_CHANGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(2)).contains("Edit me");
        assertThat(audit.get(0).get(3)).contains("Edited name").contains("300490");
        // saving the same values again writes nothing (no new version, no second audit row)
        JsonNode same = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(updateBody(saved)), 200);
        assertThat(same.get("version").asLong()).isOne();
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_CHANGED")).hasSize(1);
        // a stale version is 409 settings-changed with the current product
        Map<String, Object> stale = updateBody(created);
        stale.put("name", "Late edit");
        JsonNode conflict = problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(stale)), 409);
        assertType(conflict, "settings-changed");
        assertThat(conflict.get("current").get("name").asText()).isEqualTo("Edited name");
        assertThat(getProduct(clinic, id).get("name").asText()).isEqualTo("Edited name");
        // the same rules as create: all errors together, and nothing changes
        Map<String, Object> bad = updateBody(saved);
        bad.put("name", "");
        bad.put("conversion", 0);
        bad.put("schedule", null);
        JsonNode p = problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(bad)), 400);
        assertFieldMessage(p, "name", "Enter the product name");
        assertFieldMessage(p, "conversion", "Enter a whole number, 1 or more");
        assertFieldMessage(p, "schedule", "Choose the schedule");
        assertThat(getProduct(clinic, id).get("version").asLong()).isOne();
        // a code or source in the body is refused (the body is closed)
        Map<String, Object> withCode = updateBody(saved);
        withCode.put("code", "U-XXX-9999");
        assertType(problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(withCode)), 400), "validation");
        assertThat(getProduct(clinic, id).get("code").asText()).isEqualTo(created.get("code").asText());
    }

    @Test
    void theDuplicateCheckOnEditRunsOnlyWhenTheNormalisedKeyChanges() throws Exception {
        Clinic clinic = pharmacyClinic();
        Map<String, Object> original = ownBody("Dup on edit", "Ceftriaxone", "1 g", "injection");
        original.put("ignoreDuplicates", true);
        JsonNode created = createOwn(clinic, original); // saved with "Create anyway"
        String id = created.get("productId").asText();
        // an edit that keeps salt, strength and form is not checked, and needs no "Create anyway" again
        Map<String, Object> rename = updateBody(created);
        rename.put("name", "Renamed duplicate");
        JsonNode renamed = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(rename), 200);
        // "1 g" changed to "1000 mg" is not a change of the key
        Map<String, Object> spelling = updateBody(renamed);
        spelling.put("strength", "1000 mg");
        JsonNode spelled = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(spelling), 200);
        assertThat(spelled.get("strength").asText()).isEqualTo("1000 mg");
        // a real change to another existing key is checked: 500 mg matches G-000102
        Map<String, Object> toHalf = updateBody(spelled);
        toHalf.put("strength", "500 mg");
        JsonNode dup = problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(toHalf)), 409);
        assertType(dup, "possible-duplicate");
        assertThat(candidateCodes(dup)).containsExactly("G-000102");
        assertThat(getProduct(clinic, id).get("strength").asText()).as("nothing saved").isEqualTo("1000 mg");
        // "Create anyway" on edit saves and records the codes
        toHalf.put("ignoreDuplicates", true);
        JsonNode forced = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(toHalf), 200);
        assertThat(forced.get("strength").asText()).isEqualTo("500 mg");
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_CHANGED").get(2).get(3)).contains("duplicateOfCodes").contains("G-000102");
        // the product itself is never its own duplicate: changing to a key that only itself holds is allowed
        Map<String, Object> unique = updateBody(forced);
        unique.put("salt", "Quuxine-edit");
        unique.put("strength", "5 mg");
        unique.put("form", "tablet");
        ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(unique), 200);
    }

    @Test
    void anAdoptedProductsGlobalFieldsCannotBeEditedAndTheAnswerIs400WithTheSentence() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode adopted = adopt(clinic, "G-000103");
        String id = adopted.get("productId").asText();
        Map<String, Object> edit = updateBody(adopted);
        edit.put("name", "My own name for paracetamol");
        JsonNode p = problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(edit)), 400);
        assertType(p, "global-fields-read-only");
        assertThat(p.get("detail").asText()).isEqualTo("Global fields cannot be changed. Use Local settings.");
        JsonNode after = getProduct(clinic, id);
        assertThat(after.get("name").asText()).isEqualTo("Sample Paracetamol 650 mg tablet");
        assertThat(after.get("version").asLong()).isZero();
        assertThat(auditRowsOf(clinic, "PHARMACY_PRODUCT_CHANGED")).isEmpty();
        assertThat(values(clinic, "select name from pharmacy.product where id = ?::uuid", id).get(0)).as("no global field is ever copied").isNull();
        // the same answer even for a body that changes nothing
        assertType(problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(updateBody(adopted))), 400), "global-fields-read-only");
        // the owner only; a malformed id is 400 bad-request; another clinic's product 404
        assertType(problem(call(HttpMethod.PUT, productUrl(id), crew(clinic, "PHARMACIST").auth(), js(edit)), 403), "forbidden");
        assertType(problem(call(HttpMethod.PUT, productUrl("not-a-uuid"), clinic.owner(), js(edit)), 400), "bad-request");
        Clinic other = pharmacyClinic();
        assertType(problem(call(HttpMethod.PUT, productUrl(id), other.owner(), js(edit)), 404), "not-found");
    }

    @Test
    void theUnitsAndConversionAreLockedOnceTheProductHasStockButOtherFieldsStillChange() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode created = createOwn(clinic, ownBody("Stock product"));
        String id = created.get("productId").asText();
        Map<String, Object> before = updateBody(created);
        before.put("purchaseUnit", "Carton");
        JsonNode freeToChange = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(before), 200);
        assertThat(freeToChange.get("unitsLocked").asBoolean()).isFalse();
        giveStock(clinic, id);
        JsonNode locked = getProduct(clinic, id);
        assertThat(locked.get("hasStock").asBoolean()).isTrue();
        assertThat(locked.get("unitsLocked").asBoolean()).as("unitsLocked equals hasStock").isTrue();
        for (String[] change : new String[][] {{"purchaseUnit", "Pack"}, {"issueUnit", "strip"}}) {
            Map<String, Object> b = updateBody(locked);
            b.put(change[0], change[1]);
            JsonNode p = problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(b)), 409);
            assertType(p, "units-locked");
            assertThat(p.get("detail").asText()).isEqualTo("Locked because this product has stock.");
        }
        Map<String, Object> conversion = updateBody(locked);
        conversion.put("conversion", 99);
        assertType(problem(call(HttpMethod.PUT, productUrl(id), clinic.owner(), js(conversion)), 409), "units-locked");
        assertThat(getProduct(clinic, id).get("purchaseUnit").asText()).isEqualTo("Carton");
        // everything else may change, and the same units sent again are fine
        Map<String, Object> other = updateBody(locked);
        other.put("name", "Renamed with stock");
        other.put("gstRate", "18");
        JsonNode saved = ok(HttpMethod.PUT, productUrl(id), clinic.owner(), js(other), 200);
        assertThat(saved.get("name").asText()).isEqualTo("Renamed with stock");
        assertThat(saved.get("purchaseUnit").asText()).isEqualTo("Carton");
        // the database refuses it too (defence in depth): a direct update of the units is a check violation
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db().inTenant(clinic.tenantId(),
                c -> {
                    DbAccess.execute(c, "update pharmacy.product set issue_unit = 'other' where id = ?::uuid", id);
                    return null;
                })).isInstanceOfSatisfying(java.sql.SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("23514");
                    assertThat(e.getMessage()).contains("locked once the product has stock");
                });
    }

    // ---------------- options ----------------

    @Test
    void theOptionsListStartsWithTheStarterValuesAddsWhatTheUnitUsedAndShowsEachOnceIgnoringCase() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode starter = ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        assertThat(texts(starter.get("units"))).containsExactly("Ampoule", "Bottle", "Box", "Capsule", "Kit", "Sachet", "Strip", "Tablet", "Tube", "Vial");
        assertThat(texts(starter.get("forms"))).containsExactly("capsule", "consumable", "cream", "device", "drops", "infusion", "inhaler",
                "injection", "kit", "ointment", "syrup", "tablet");
        Map<String, Object> body = ownBody("Pouch product");
        body.put("purchaseUnit", "Pouch");
        body.put("issueUnit", "BOX"); // the same as the starter "Box" ignoring case
        body.put("form", "Dry powder");
        createOwn(clinic, body);
        Map<String, Object> second = ownBody("Tablet product");
        second.put("form", "TABLET"); // the same as the starter "tablet"
        createOwn(clinic, second);
        JsonNode options = ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        List<String> units = texts(options.get("units"));
        assertThat(units).contains("Pouch").doesNotContain("BOX").doesNotHaveDuplicates();
        assertThat(units.stream().filter(u -> u.equalsIgnoreCase("box")).count()).isOne();
        List<String> forms = texts(options.get("forms"));
        assertThat(forms).contains("Dry powder").doesNotContain("TABLET").doesNotHaveDuplicates();
        assertThat(forms).as("sorted A to Z ignoring case").isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(units).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        // another clinic's values are not suggested here
        Clinic other = pharmacyClinic();
        assertThat(texts(ok(HttpMethod.GET, OPTIONS, other.owner(), null, 200).get("units"))).doesNotContain("Pouch");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
