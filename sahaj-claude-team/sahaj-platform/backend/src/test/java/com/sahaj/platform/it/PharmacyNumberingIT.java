package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-10 on the real database: the numbering series (docs/api/pharmacy.yaml 0.2.0: ten rows). Defaults until saved, the rules
 * of the prefix and the length, the one-prefix-per-type rule, the stale version, owner only, the clinic wall. The locking of a
 * series once a number is issued (0.2.1) is in {@link PharmacyHardeningMastersIT}. Test plan: docs/test-plans/SAH-78.md
 * section 23 (T78-10-*).
 */
@OnTestDatabase
class PharmacyNumberingIT extends Sah78CSupport {

    private static final String PREFIX_TEXT = "Use 1 to 6 letters, digits, - or /";
    private static final String TOO_LONG_TEXT = "The number must be 16 characters or fewer";
    private static final String RESTART_TEXT = "Show the financial year when numbers restart every 1 April";
    private static final String PREFIX_IN_USE_TEXT = "Two document types cannot use the same prefix";

    /** "26-27" for 19 Nov 2026 (April to March, IST). */
    private static String financialYear() {
        LocalDate today = todayIst();
        int start = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }

    @Test
    void theListHasTenRowsWithTheDocumentedDefaultsAndWritesNothing() throws Exception {
        Masters w = mastersClinic();
        JsonNode list = ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200);
        List<String> types = new ArrayList<>();
        list.get("items").forEach(i -> types.add(i.get("docType").asText()));
        assertThat(types).containsExactly(DOC_TYPES);
        String[][] expected = {{"PURCHASE_ORDER", "PO-", "false", "4", "false", "PO-0001"}, {"GRN", "GRN-", "false", "4", "false", "GRN-0001"},
            {"INDENT", "IND-", "false", "4", "false", "IND-0001"}, {"TRANSFER", "TRF-", "false", "4", "false", "TRF-0001"},
            {"BILL", "SB/", "true", "6", "true", "SB/" + financialYear() + "/000001"},
            {"DEBIT_NOTE", "DN-", "false", "4", "false", "DN-0001"}, {"STOCK_AUDIT", "AUD-", "false", "4", "false", "AUD-0001"},
            {"STORE_RETURN", "RET-", "false", "4", "false", "RET-0001"}, {"DAMAGE_REPORT", "DMG-", "false", "4", "false", "DMG-0001"},
            {"STOCK_ADJUSTMENT", "ADJ-", "false", "4", "false", "ADJ-0001"},
            {"CREDIT_NOTE", "CN/", "true", "6", "true", "CN/" + financialYear() + "/000001"},
            {"IPD_ISSUE_SLIP", "IS-", "false", "4", "false", "IS-0001"}};
        for (String[] e : expected) {
            JsonNode s = seriesOf(list, e[0]);
            assertThat(s.get("prefix").asText()).as(e[0]).isEqualTo(e[1]);
            assertThat(s.get("financialYearPart").asText()).as(e[0]).isEqualTo(e[2]);
            assertThat(s.get("sequenceLength").asInt()).as(e[0]).isEqualTo(Integer.parseInt(e[3]));
            assertThat(s.get("restartsEachFinancialYear").asText()).as(e[0]).isEqualTo(e[4]);
            assertThat(s.get("example").asText()).as(e[0]).isEqualTo(e[5]);
            assertThat(s.get("renderedLength").asInt()).as(e[0]).isEqualTo(e[5].length());
            assertThat(s.get("numbersIssued").asBoolean()).as(e[0]).isFalse();
            assertThat(s.get("saved").asBoolean()).as(e[0]).isFalse();
            assertThat(s.get("version").asInt()).as(e[0]).isZero();
        }
        assertThat(pharmacyRows(w.clinic(), "document_series")).isZero();
        assertThat(pharmacyRows(w.clinic(), "document_counter")).isZero();
    }

    @Test
    void anOwnerChangesTheSeriesAndTheExampleAndTheLengthFollowAndTheChangeIsAudited() throws Exception {
        Masters w = mastersClinic();
        JsonNode s = ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "PO/", true, 5, true)), 200);
        assertThat(s.get("prefix").asText()).isEqualTo("PO/");
        assertThat(s.get("financialYearPart").asBoolean()).isTrue();
        assertThat(s.get("sequenceLength").asInt()).isEqualTo(5);
        assertThat(s.get("restartsEachFinancialYear").asBoolean()).isTrue();
        assertThat(s.get("example").asText()).isEqualTo("PO/" + financialYear() + "/00001");
        assertThat(s.get("renderedLength").asInt()).isEqualTo(("PO/" + financialYear() + "/00001").length());
        assertThat(s.get("saved").asBoolean()).isTrue();
        assertThat(s.get("version").asInt()).isOne();
        JsonNode list = ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200);
        assertThat(seriesOf(list, "PURCHASE_ORDER").get("prefix").asText()).isEqualTo("PO/");
        assertThat(seriesOf(list, "GRN").get("saved").asBoolean()).as("the others are still defaults").isFalse();
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_SERIES_CHANGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(2)).contains("PO-");
        assertThat(audit.get(0).get(3)).contains("PO/");
        // a second change bumps the version
        JsonNode again = ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(1, "PO/", true, 6, true)), 200);
        assertThat(again.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void aSaveThatChangesNothingOnASavedSeriesWritesNothingAndKeepsTheVersion() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.PUT, SERIES_URL + "/GRN", w.clinic().owner(), js(seriesBody(0, "GR-", false, 4, false)), 200);
        JsonNode same = ok(HttpMethod.PUT, SERIES_URL + "/GRN", w.clinic().owner(), js(seriesBody(1, "GR-", false, 4, false)), 200);
        assertThat(same.get("version").asInt()).isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_SERIES_CHANGED")).hasSize(1);
        assertThat(pharmacyRows(w.clinic(), "document_series")).isOne();
    }

    @Test
    void thePrefixAndTheLengthAreChecked() throws Exception {
        Masters w = mastersClinic();
        for (String prefix : new String[] {"", "TOOLONG", "PO#", "A B", "PÖ", "@"}) {
            JsonNode p = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, prefix, false, 4, false))), 400);
            assertType(p, "validation");
            assertFieldMessage(p, "prefix", PREFIX_TEXT);
        }
        for (int length : new int[] {3, 9, 0, -4}) {
            JsonNode p = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "PO-", false, length, false))), 400);
            assertFieldMessage(p, "sequenceLength", "Enter a length from 4 to 8");
        }
        // the longest accepted prefix and the shortest, with the longest length that keeps 16 characters
        ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "ABCDEF", false, 8, false)), 200);
        ok(HttpMethod.PUT, SERIES_URL + "/GRN", w.clinic().owner(), js(seriesBody(0, "G", false, 4, false)), 200);
        assertThat(pharmacyRows(w.clinic(), "document_series")).isEqualTo(2);
    }

    @Test
    void aRenderedNumberOverSixteenCharactersIsRefusedAndExactlySixteenIsAccepted() throws Exception {
        Masters w = mastersClinic();
        // prefix 6 + financial year "26-27/" 6 + length 8 = 20: too long; length 4 = 16: accepted
        JsonNode p = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "ABCDEF", true, 8, false))), 400);
        assertFieldMessage(p, "sequenceLength", TOO_LONG_TEXT);
        problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "ABCDEF", true, 5, false))), 400);
        JsonNode ok = ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "ABCDEF", true, 4, false)), 200);
        assertThat(ok.get("renderedLength").asInt()).isEqualTo(16);
        assertThat(ok.get("example").asText()).hasSize(16);
    }

    @Test
    void restartingEachFinancialYearNeedsTheFinancialYearInTheNumber() throws Exception {
        Masters w = mastersClinic();
        JsonNode p = problem(call(HttpMethod.PUT, SERIES_URL + "/INDENT", w.clinic().owner(), js(seriesBody(0, "IND-", false, 4, true))), 400);
        assertFieldMessage(p, "restartsEachFinancialYear", RESTART_TEXT);
        ok(HttpMethod.PUT, SERIES_URL + "/INDENT", w.clinic().owner(), js(seriesBody(0, "IND-", true, 4, true)), 200);
        ok(HttpMethod.PUT, SERIES_URL + "/TRANSFER", w.clinic().owner(), js(seriesBody(0, "TRF-", true, 4, false)), 200);
    }

    @Test
    void twoDocumentTypesCannotUseTheSamePrefixIgnoringCaseAndAnIssuedDefaultPrefixCountsToo() throws Exception {
        Masters w = mastersClinic();
        // GRN still has its default "GRN-" and nothing is saved for it: the purchase order cannot take it
        JsonNode p = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "grn-", false, 4, false))), 409);
        assertType(p, "prefix-in-use");
        assertThat(p.get("detail").asText()).isEqualTo(PREFIX_IN_USE_TEXT);
        // a saved prefix of another type
        ok(HttpMethod.PUT, SERIES_URL + "/INDENT", w.clinic().owner(), js(seriesBody(0, "ORD", false, 4, false)), 200);
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/TRANSFER", w.clinic().owner(), js(seriesBody(0, "ord", false, 4, false))), 409), "prefix-in-use");
        // a type may keep its own prefix and change its capitals
        ok(HttpMethod.PUT, SERIES_URL + "/INDENT", w.clinic().owner(), js(seriesBody(1, "ord", false, 4, false)), 200);
        // the five SAH-79 defaults count as well
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/TRANSFER", w.clinic().owner(), js(seriesBody(0, "DN-", false, 4, false))), 409), "prefix-in-use");
        assertThat(pharmacyRows(w.clinic(), "document_series")).isEqualTo(1);
    }

    @Test
    void aStaleVersionIs409SettingsChangedWithTheCurrentSeriesAndAnUnknownDocumentTypeIs400() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "PO/", false, 4, false)), 200);
        JsonNode p = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "PX-", false, 4, false))), 409);
        assertType(p, "settings-changed");
        assertThat(p.get("current").get("prefix").asText()).isEqualTo("PO/");
        assertThat(p.get("current").get("version").asInt()).isOne();
        for (String bad : new String[] {"FOO", "purchase_order", "NOPE"}) {
            assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/" + bad, w.clinic().owner(), js(seriesBody(0, "PX-", false, 4, false))), 400), "bad-request");
        }
        JsonNode missing = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(),
                "{\"version\":1,\"prefix\":\"PX-\"}"), 400);
        assertFieldMessage(missing, "sequenceLength", "Enter a length from 4 to 8");
        assertThat(pharmacyRows(w.clinic(), "document_series")).isOne();
    }

    @Test
    void aNumberTakenByTheCounterShowsAsIssuedAndTheNumbersAreOneTwoThreeForEachNewType() throws Exception {
        Masters w = mastersClinic();
        for (String type : new String[] {"DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN", "DAMAGE_REPORT", "STOCK_ADJUSTMENT"}) {
            assertThat(issueNumber(w.clinic(), type)).as(type).isEqualTo(1);
            assertThat(issueNumber(w.clinic(), type)).as(type).isEqualTo(2);
        }
        JsonNode list = ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200);
        assertThat(seriesOf(list, "DEBIT_NOTE").get("numbersIssued").asBoolean()).isTrue();
        assertThat(seriesOf(list, "STOCK_ADJUSTMENT").get("numbersIssued").asBoolean()).isTrue();
        assertThat(seriesOf(list, "PURCHASE_ORDER").get("numbersIssued").asBoolean()).isFalse();
    }

    @Test
    void everyWriteByANonOwnerIs403AndTheReadersMayReadTheList() throws Exception {
        Masters w = mastersClinic();
        var callers = nonOwners(w);
        for (var who : callers.entrySet()) {
            RequestPostProcessor auth = who.getValue();
            assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", auth, js(seriesBody(0, "PX-", false, 4, false))), 403), "forbidden");
        }
        assertThat(pharmacyRows(w.clinic(), "document_series")).isZero();
        for (String reader : new String[] {"PHARMACIST", "CENTRAL_STORE_USER"}) {
            assertThat(ok(HttpMethod.GET, SERIES_URL, callers.get(reader), null, 200).get("items")).hasSize(12);
        }
        for (String other : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            assertType(problem(call(HttpMethod.GET, SERIES_URL, callers.get(other), null), 403), "forbidden");
        }
    }

    @Test
    void aSeriesOfAnotherClinicIsNeverSeenAndThePrefixRuleIsPerClinic() throws Exception {
        Masters mine = mastersClinic();
        Masters theirs = mastersClinic();
        ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", mine.clinic().owner(), js(seriesBody(0, "ZZ-", false, 4, false)), 200);
        JsonNode theirList = ok(HttpMethod.GET, SERIES_URL, theirs.clinic().owner(), null, 200);
        assertThat(seriesOf(theirList, "PURCHASE_ORDER").get("prefix").asText()).isEqualTo("PO-");
        assertThat(seriesOf(theirList, "PURCHASE_ORDER").get("saved").asBoolean()).isFalse();
        // the same prefix in the other clinic is fine
        ok(HttpMethod.PUT, SERIES_URL + "/GRN", theirs.clinic().owner(), js(seriesBody(0, "ZZ-", false, 4, false)), 200);
        assertThat(count(theirs.clinic(), "select count(*) from pharmacy.document_series")).isOne();
        assertThat(count(mine.clinic(), "select count(*) from pharmacy.document_series")).isOne();
    }
}
