package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78-10 on the real database: the reason lists (eleven after SAH-79, seeded reasons are global rows, the unit's own follow)
 * and the print template settings (invoice, issue slip, debit note). Contract docs/api/pharmacy.yaml 0.2.0; the "fixed" flag
 * and the switch-on of a vendor are 0.2.1 and live in {@link PharmacyHardeningMastersIT}. Test plan: docs/test-plans/SAH-78.md
 * section 23 (T78-10-*).
 */
@OnTestDatabase
class PharmacyReasonsTemplatesIT extends Sah78CSupport {

    private static final String LABEL_TEXT = "Enter the reason (up to 40 characters)";

    // ---------------- reason lists ----------------

    @Test
    void theTwentyListsComeInOrderWithTheSeededReasonsAndTheirLabels() throws Exception {
        Masters w = mastersClinic();
        JsonNode lists = ok(HttpMethod.GET, REASON_LISTS, w.clinic().owner(), null, 200);
        List<String> keys = new ArrayList<>();
        lists.get("lists").forEach(l -> keys.add(l.get("listKey").asText()));
        assertThat(keys).containsExactly(LIST_KEYS);
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("store-return", List.of("Excess stock", "Near expiry", "Not needed (order stopped)"));
        expected.put("sales-return", List.of("Doctor changed medicine", "Excess bought", "Wrong item given", "Adverse reaction"));
        expected.put("damage", List.of("Damaged / leaking", "Expired", "Cold-chain break"));
        expected.put("stock-adjustment", List.of("Consumption not entered", "Breakage", "Unknown", "Count mismatch", "Theft or loss"));
        expected.put("receipt-reject", List.of("Damaged in transit", "Wrong item", "Near expiry / expired", "Excess quantity", "Short / not received"));
        expected.put("cs-change", List.of("Out of stock", "Near expiry held", "Pack or size change", "Other"));
        expected.put("approval-reject", List.of("Not needed", "Quantity too high", "Wrong item", "Other"));
        expected.put("transfer", List.of("Non-moving here, needed there", "Urgent shortage", "Store closure"));
        expected.put("consumption-purpose", List.of("Ward general use", "Procedure (non-billable)", "Cleaning / infection control"));
        expected.put("dispute", List.of("Other"));
        expected.put("vendor-return", List.of("Other"));
        // SAH-80 (migration 1800): the nine lists of the sale
        expected.put("substitution", List.of("Out of stock", "Patient preference", "Cheaper equivalent", "Prescriber advice", "Other"));
        expected.put("allergy-override", List.of("Prescriber confirmed", "Patient has taken it safely before", "Allergy entry is wrong", "Other"));
        expected.put("h1-override", List.of("Prescriber confirmed", "Patient has taken it safely before", "Allergy entry is wrong", "Other"));
        expected.put("return-condition", List.of("Sealed", "Strip cut or opened", "Damaged"));
        expected.put("cancel-bill", List.of("Wrong item", "Wrong quantity", "Customer changed mind", "Other"));
        expected.put("ward-return", List.of("Medicine stopped", "Discharge", "Not used"));
        expected.put("rx-closed", List.of("Patient declined", "Out of stock", "Doctor changed the order", "Other"));
        expected.put("clearance", List.of("Returns will come later", "Nothing to return", "Other"));
        expected.put("day-close-difference", List.of("Short change given", "Entry error", "Refund not recorded", "Other"));
        int total = 0;
        for (var e : expected.entrySet()) {
            List<String> labels = new ArrayList<>();
            reasonList(lists, e.getKey()).get("reasons").forEach(r -> labels.add(r.get("label").asText()));
            assertThat(labels).as(e.getKey()).containsExactlyElementsOf(e.getValue());
            reasonList(lists, e.getKey()).get("reasons").forEach(r -> {
                assertThat(r.get("seeded").asBoolean()).isTrue();
                assertThat(r.get("active").asBoolean()).isTrue();
            });
            total += labels.size();
        }
        assertThat(total).as("70 seeded reasons (36 before SAH-80)").isEqualTo(70);
        assertThat(reasonList(lists, "receipt-reject").get("label").asText()).isEqualTo("Receipt rejection");
        assertThat(reasonList(lists, "cs-change").get("label").asText()).isEqualTo("Central Store change");
        assertThat(reasonList(lists, "approval-reject").get("label").asText()).isEqualTo("Approval rejection");
        assertThat(reasonList(lists, "transfer").get("label").asText()).isEqualTo("Stock transfer");
        assertThat(reasonList(lists, "consumption-purpose").get("label").asText()).isEqualTo("Consumption purpose");
        assertThat(reasonList(lists, "dispute").get("label").asText()).isEqualTo("Dispute");
        assertThat(reasonList(lists, "vendor-return").get("label").asText()).isEqualTo("Vendor return");
        assertThat(pharmacyRows(w.clinic(), "reason")).as("a read writes nothing").isZero();
        assertThat(pharmacyRows(w.clinic(), "reason_default_state")).isZero();
    }

    @Test
    void anOwnerAddsAReasonAtTheEndOfTheListAndItIsAuditedAndAnotherClinicDoesNotSeeIt() throws Exception {
        Masters w = mastersClinic();
        JsonNode lists = ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"  Packaging torn \"}", 201);
        JsonNode reasons = reasonList(lists, "damage").get("reasons");
        assertThat(reasons).hasSize(4);
        JsonNode added = reasons.get(3);
        assertThat(added.get("label").asText()).isEqualTo("Packaging torn");
        assertThat(added.get("seeded").asBoolean()).isFalse();
        assertThat(added.get("active").asBoolean()).isTrue();
        assertThat(added.get("reasonKey").asText()).matches("^[a-z0-9][a-z0-9.-]{0,63}$");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_REASON_ADDED")).hasSize(1);
        Masters other = mastersClinic();
        assertThat(reasonList(ok(HttpMethod.GET, REASON_LISTS, other.clinic().owner(), null, 200), "damage").get("reasons")).hasSize(3);
        // every one of the twenty lists takes a reason
        for (String key : LIST_KEYS) {
            JsonNode after = ok(HttpMethod.POST, REASON_LISTS + "/" + key + "/reasons", w.clinic().owner(), "{\"label\":\"Own reason in " + key + "\"}", 201);
            assertThat(reasonList(after, key).get("reasons").get(reasonList(after, key).get("reasons").size() - 1).get("seeded").asBoolean()).isFalse();
        }
    }

    @Test
    void aLabelIsOneToFortyCharactersAndAnEqualOneIgnoringCaseIs409ReasonExists() throws Exception {
        Masters w = mastersClinic();
        for (String bad : new String[] {"", "   ", "x".repeat(41)}) {
            JsonNode p = problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"" + bad + "\"}"), 400);
            assertType(p, "validation");
            assertFieldMessage(p, "label", LABEL_TEXT);
        }
        ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"" + "y".repeat(40) + "\"}", 201);
        ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"Z\"}", 201);
        // a seeded one, in any capitals, and an own one
        JsonNode seeded = problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"EXPIRED\"}"), 409);
        assertType(seeded, "reason-exists");
        assertThat(seeded.get("detail").asText()).isEqualTo("That reason is already in the list");
        assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"z\"}"), 409), "reason-exists");
        // the same label in another list is fine
        ok(HttpMethod.POST, REASON_LISTS + "/store-return/reasons", w.clinic().owner(), "{\"label\":\"Z\"}", 201);
        // a switched-off reason still blocks its label
        ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.expired/switch-off", w.clinic().owner(), null, 200);
        assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"expired\"}"), 409), "reason-exists");
        // an unknown field and an unknown list
        assertFieldMessage(problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", w.clinic().owner(), "{\"label\":\"Ok\",\"key\":\"x\"}"), 400),
                "body", "Send only the fields listed for this request.");
        assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/nope/reasons", w.clinic().owner(), "{\"label\":\"Ok\"}"), 400), "bad-request");
    }

    @Test
    void switchOffAndOnWorkOnSeededAndOwnReasonsAreIdempotentAndWriteOneAuditEach() throws Exception {
        Masters w = mastersClinic();
        JsonNode off = ok(HttpMethod.POST, REASON_LISTS + "/store-return/reasons/store-return.excess-stock/switch-off", w.clinic().owner(), null, 200);
        assertThat(reasonOf(off, "store-return", "store-return.excess-stock").get("active").asBoolean()).isFalse();
        assertThat(reasonOf(off, "store-return", "store-return.excess-stock").get("seeded").asBoolean()).isTrue();
        ok(HttpMethod.POST, REASON_LISTS + "/store-return/reasons/store-return.excess-stock/switch-off", w.clinic().owner(), null, 200);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_REASON_SWITCHED_OFF")).as("a repeat writes nothing").hasSize(1);
        JsonNode on = ok(HttpMethod.POST, REASON_LISTS + "/store-return/reasons/store-return.excess-stock/switch-on", w.clinic().owner(), null, 200);
        assertThat(reasonOf(on, "store-return", "store-return.excess-stock").get("active").asBoolean()).isTrue();
        ok(HttpMethod.POST, REASON_LISTS + "/store-return/reasons/store-return.excess-stock/switch-on", w.clinic().owner(), null, 200);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_REASON_SWITCHED_ON")).hasSize(1);
        // an own reason
        JsonNode added = ok(HttpMethod.POST, REASON_LISTS + "/transfer/reasons", w.clinic().owner(), "{\"label\":\"Own\"}", 201);
        String ownKey = reasonList(added, "transfer").get("reasons").get(3).get("reasonKey").asText();
        JsonNode ownOff = ok(HttpMethod.POST, REASON_LISTS + "/transfer/reasons/" + ownKey + "/switch-off", w.clinic().owner(), null, 200);
        assertThat(reasonOf(ownOff, "transfer", ownKey).get("active").asBoolean()).isFalse();
        // the switch is the clinic's own: another clinic still sees the seeded reason on
        Masters other = mastersClinic();
        assertThat(reasonOf(ok(HttpMethod.GET, REASON_LISTS, other.clinic().owner(), null, 200), "store-return", "store-return.near-expiry").get("active").asBoolean()).isTrue();
        ok(HttpMethod.POST, REASON_LISTS + "/store-return/reasons/store-return.near-expiry/switch-off", w.clinic().owner(), null, 200);
        assertThat(reasonOf(ok(HttpMethod.GET, REASON_LISTS, other.clinic().owner(), null, 200), "store-return", "store-return.near-expiry").get("active").asBoolean())
                .as("another clinic's switch-off is not mine").isTrue();
    }

    @Test
    void theLastActiveDamageReasonCannotBeSwitchedOffAndTheFixedReceiptReasonCannotEither() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.expired/switch-off", w.clinic().owner(), null, 200);
        ok(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.damaged-leaking/switch-off", w.clinic().owner(), null, 200);
        JsonNode last = problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.cold-chain-break/switch-off", w.clinic().owner(), null), 409);
        assertType(last, "last-active-reason");
        assertThat(last.get("detail").asText()).isEqualTo("Keep at least one damage reason.");
        JsonNode lists = ok(HttpMethod.GET, REASON_LISTS, w.clinic().owner(), null, 200);
        long active = 0;
        for (JsonNode r : reasonList(lists, "damage").get("reasons")) {
            active += r.get("active").asBoolean() ? 1 : 0;
        }
        assertThat(active).isOne();
        JsonNode fixed = problem(call(HttpMethod.POST, REASON_LISTS + "/receipt-reject/reasons/receipt-reject.short/switch-off", w.clinic().owner(), null), 409);
        assertType(fixed, "last-active-reason");
        assertThat(fixed.get("detail").asText()).isEqualTo("This reason is fixed.");
        assertThat(reasonOf(ok(HttpMethod.GET, REASON_LISTS, w.clinic().owner(), null, 200), "receipt-reject", "receipt-reject.short").get("active").asBoolean()).isTrue();
        assertThat(pharmacyRows(w.clinic(), "reason_default_state")).as("only the two damage switch-offs were saved").isEqualTo(2);
    }

    @Test
    void anUnknownReasonIs404AndAnUnknownListIs400AndOtherReasonsKeyOfAnotherListIs404() throws Exception {
        Masters w = mastersClinic();
        problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.nope/switch-off", w.clinic().owner(), null), 404);
        problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.nope/switch-on", w.clinic().owner(), null), 404);
        problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/store-return.near-expiry/switch-off", w.clinic().owner(), null), 404);
        assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/nope/reasons/x/switch-off", w.clinic().owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/nope/reasons/x/switch-on", w.clinic().owner(), null), 400), "bad-request");
        // a reason key of another clinic's own reason
        Masters other = mastersClinic();
        JsonNode added = ok(HttpMethod.POST, REASON_LISTS + "/transfer/reasons", other.clinic().owner(), "{\"label\":\"Theirs\"}", 201);
        String theirKey = reasonList(added, "transfer").get("reasons").get(3).get("reasonKey").asText();
        problem(call(HttpMethod.POST, REASON_LISTS + "/transfer/reasons/" + theirKey + "/switch-off", w.clinic().owner(), null), 404);
    }

    @Test
    void everyReasonWriteByANonOwnerIs403AndNothingIsWritten() throws Exception {
        Masters w = mastersClinic();
        var callers = nonOwners(w);
        for (var who : callers.entrySet()) {
            assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons", who.getValue(), "{\"label\":\"Nope\"}"), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.expired/switch-off", who.getValue(), null), 403), "forbidden");
            assertType(problem(call(HttpMethod.POST, REASON_LISTS + "/damage/reasons/damage.expired/switch-on", who.getValue(), null), 403), "forbidden");
        }
        assertThat(pharmacyRows(w.clinic(), "reason")).isZero();
        assertThat(pharmacyRows(w.clinic(), "reason_default_state")).isZero();
        for (String reader : new String[] {"PHARMACIST", "CENTRAL_STORE_USER"}) {
            assertThat(ok(HttpMethod.GET, REASON_LISTS, callers.get(reader), null, 200).get("lists")).hasSize(20);
        }
        for (String other : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            assertType(problem(call(HttpMethod.GET, REASON_LISTS, callers.get(other), null), 403), "forbidden");
        }
    }

    // ---------------- print templates ----------------

    @Test
    void theThreeTemplatesHaveTheDefaultsTheFixedFieldsAndTheMissingUnitSettingsOfTheInvoice() throws Exception {
        Masters w = mastersClinic();
        JsonNode list = ok(HttpMethod.GET, TEMPLATES, w.clinic().owner(), null, 200);
        List<String> types = new ArrayList<>();
        list.get("items").forEach(i -> types.add(i.get("docType").asText()));
        assertThat(types).containsExactly("INVOICE", "ISSUE_SLIP", "DEBIT_NOTE");
        for (JsonNode t : list.get("items")) {
            assertThat(t.get("headerLines")).isEmpty();
            assertThat(t.get("footerNote").isNull()).isTrue();
            assertThat(t.get("paperSize").asText()).isEqualTo("A4");
            assertThat(t.get("saved").asBoolean()).isFalse();
            assertThat(t.get("version").asInt()).isZero();
            assertThat(t.get("fixedFields")).isNotEmpty();
            assertThat(t.get("defaultHeaderLines").size()).isLessThanOrEqualTo(3);
        }
        JsonNode invoice = list.get("items").get(0);
        assertThat(invoice.get("printable").asBoolean()).isFalse();
        List<String> missing = new ArrayList<>();
        invoice.get("missing").forEach(m -> missing.add(m.asText()));
        assertThat(missing).containsExactly("GSTIN", "DRUG_LICENCE_NO");
        List<String> fixed = new ArrayList<>();
        invoice.get("fixedFields").forEach(f -> fixed.add(f.asText()));
        assertThat(fixed).contains("UNIT_NAME", "DOCUMENT_NO", "DATE", "GSTIN", "DRUG_LICENCE_NO", "HSN", "BATCH", "EXPIRY", "MRP", "GST_AMOUNTS");
        assertThat(list.get("items").get(1).get("printable").asBoolean()).isTrue();
        assertThat(list.get("items").get(2).get("printable").asBoolean()).isTrue();
        assertThat(pharmacyRows(w.clinic(), "print_template_setting")).isZero();
        // set the GSTIN and the licence in Unit settings: the invoice prints
        // (the version of the unit settings moved on when the stores were made: read it first)
        JsonNode unit = ok(HttpMethod.GET, UNIT, w.clinic().owner(), null, 200);
        ok(HttpMethod.PUT, UNIT, w.clinic().owner(), "{\"version\":" + unit.get("version").asLong() + ",\"gstin\":\"" + GSTIN_OK
                + "\",\"drugLicenceNo\":\"TEST-DL-9\",\"drugLicenceValidTill\":\"" + (todayIst().getYear() + 2)
                + "-03-31\",\"integrationMode\":\"INTEGRATED\"}", 200);
        JsonNode after = ok(HttpMethod.GET, TEMPLATES, w.clinic().owner(), null, 200);
        assertThat(after.get("items").get(0).get("printable").asBoolean()).isTrue();
        assertThat(after.get("items").get(0).get("missing")).isEmpty();
    }

    @Test
    void aSaveChangesTheHeaderFooterAndPaperAuditsItAndAnUnchangedSaveWritesNothing() throws Exception {
        Masters w = mastersClinic();
        JsonNode saved = ok(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(),
                js(templateBody(0, List.of(" Test Clinic Baner Pharmacy ", "Test Road", "Pune 411045"), "Goods once sold are returned only as per the policy.", "A5")), 200);
        assertThat(saved.get("headerLines").size()).isEqualTo(3);
        assertThat(saved.get("headerLines").get(0).asText()).as("trimmed").isEqualTo("Test Clinic Baner Pharmacy");
        assertThat(saved.get("footerNote").asText()).startsWith("Goods once sold");
        assertThat(saved.get("paperSize").asText()).isEqualTo("A5");
        assertThat(saved.get("saved").asBoolean()).isTrue();
        assertThat(saved.get("version").asInt()).isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_PRINT_TEMPLATE_CHANGED")).hasSize(1);
        JsonNode same = ok(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(),
                js(templateBody(1, List.of("Test Clinic Baner Pharmacy", "Test Road", "Pune 411045"), "Goods once sold are returned only as per the policy.", "A5")), 200);
        assertThat(same.get("version").asInt()).isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_PRINT_TEMPLATE_CHANGED")).hasSize(1);
        // the other two types are not touched
        JsonNode list = ok(HttpMethod.GET, TEMPLATES, w.clinic().owner(), null, 200);
        assertThat(list.get("items").get(1).get("saved").asBoolean()).isFalse();
        // an empty footer is stored as none
        JsonNode cleared = ok(HttpMethod.PUT, TEMPLATES + "/ISSUE_SLIP", w.clinic().owner(), js(templateBody(0, List.of(), "   ", "A4")), 200);
        assertThat(cleared.get("footerNote").isNull()).isTrue();
    }

    @Test
    void theHeaderFooterAndPaperLimitsAreChecked() throws Exception {
        Masters w = mastersClinic();
        JsonNode lines = problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of("a", "b", "c", "d"), null, "A4"))), 400);
        assertFieldMessage(lines, "headerLines", "Use up to 3 header lines");
        JsonNode longLine = problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of("x".repeat(81)), null, "A4"))), 400);
        assertFieldMessage(longLine, "headerLines[0]", "Keep each header line to 80 characters");
        assertFieldMessage(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of(" "), null, "A4"))), 400),
                "headerLines[0]", "Keep each header line to 80 characters");
        assertFieldMessage(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of(), "f".repeat(201), "A4"))), 400),
                "footerNote", "Keep the footer to 200 characters");
        for (String paper : new String[] {"A3", "a4", "Letter", ""}) {
            assertFieldMessage(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of(), null, paper))), 400),
                    "paperSize", "Choose A4 or A5");
        }
        // the boundaries are accepted: 80-character lines, a 200-character footer, A4
        ok(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of("x".repeat(80), "y".repeat(80), "z".repeat(80)), "f".repeat(200), "A4")), 200);
        // fixed fields cannot be sent
        Map<String, Object> extra = templateBody(1, List.of(), null, "A4");
        extra.put("fixedFields", List.of());
        assertFieldMessage(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(extra)), 400), "body", "Send only the fields listed for this request.");
        assertThat(pharmacyRows(w.clinic(), "print_template_setting")).isOne();
    }

    @Test
    void aStaleVersionIs409SettingsChangedWithTheCurrentTemplateAndAnUnknownDocTypeIs400() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.PUT, TEMPLATES + "/DEBIT_NOTE", w.clinic().owner(), js(templateBody(0, List.of("First"), null, "A4")), 200);
        JsonNode p = problem(call(HttpMethod.PUT, TEMPLATES + "/DEBIT_NOTE", w.clinic().owner(), js(templateBody(0, List.of("Second"), null, "A4"))), 409);
        assertType(p, "settings-changed");
        assertThat(p.get("current").get("headerLines").get(0).asText()).isEqualTo("First");
        for (String bad : new String[] {"BILL", "invoice", "NOPE"}) {
            assertType(problem(call(HttpMethod.PUT, TEMPLATES + "/" + bad, w.clinic().owner(), js(templateBody(0, List.of(), null, "A4"))), 400), "bad-request");
        }
        Map<String, Object> noVersion = templateBody(0, List.of(), null, "A4");
        noVersion.remove("version");
        assertFieldMessage(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(noVersion)), 400), "version", "Reload the page and try again.");
        Map<String, Object> noFooter = templateBody(0, List.of(), null, "A4");
        noFooter.remove("footerNote");
        assertFieldMessage(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(noFooter)), 400), "footerNote", "This field is required");
    }

    @Test
    void everyTemplateWriteByANonOwnerIs403AndAnotherClinicNeverSeesMySettings() throws Exception {
        Masters w = mastersClinic();
        var callers = nonOwners(w);
        for (var who : callers.entrySet()) {
            assertType(problem(call(HttpMethod.PUT, TEMPLATES + "/INVOICE", who.getValue(), js(templateBody(0, List.of("x"), null, "A4"))), 403), "forbidden");
        }
        assertThat(pharmacyRows(w.clinic(), "print_template_setting")).isZero();
        for (String reader : new String[] {"PHARMACIST", "CENTRAL_STORE_USER"}) {
            assertThat(ok(HttpMethod.GET, TEMPLATES, callers.get(reader), null, 200).get("items")).hasSize(3);
        }
        for (String other : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            assertType(problem(call(HttpMethod.GET, TEMPLATES, callers.get(other), null), 403), "forbidden");
        }
        ok(HttpMethod.PUT, TEMPLATES + "/INVOICE", w.clinic().owner(), js(templateBody(0, List.of("Mine"), "Mine footer", "A5")), 200);
        Masters theirs = mastersClinic();
        JsonNode theirInvoice = ok(HttpMethod.GET, TEMPLATES, theirs.clinic().owner(), null, 200).get("items").get(0);
        assertThat(theirInvoice.get("headerLines")).isEmpty();
        assertThat(theirInvoice.get("saved").asBoolean()).isFalse();
        assertThat(count(theirs.clinic(), "select count(*) from pharmacy.print_template_setting")).isZero();
    }
}
