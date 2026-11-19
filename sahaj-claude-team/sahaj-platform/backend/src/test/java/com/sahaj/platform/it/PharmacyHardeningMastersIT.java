package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-78 hardening round on the package C masters (contract docs/api/pharmacy.yaml 0.2.1, DEC-078): the locked series
 * (409 series-locked), the fixed reason, vendor switch-on, {@code limitTypes} per preset, the percentage limit of a rate rise and
 * the largest rupee limit (₹ 9,99,99,999 = 9,999,999,900 paise). Written before the code: these tests fail until the
 * java-developer lands the round. Test plan: docs/test-plans/SAH-78.md section 25 (T78-H-*).
 */
@OnTestDatabase
class PharmacyHardeningMastersIT extends Sah78CSupport {

    private static final String LOCKED_TEXT = "Numbers have already been issued this year.";
    private static final long MAX_PAISE = 9_999_999_900L;
    private static final String AMOUNT_TEXT = "Enter an amount up to ₹ 9,99,99,999";

    // ---------------- series locking (DEC-078 item 4) ----------------

    @Test
    void onceANumberOfTheTypeIsIssuedThisFinancialYearTheSeriesIs409SeriesLockedAndNothingIsWritten() throws Exception {
        Masters w = mastersClinic();
        // before any number: the prefix and the format may change, twice
        JsonNode s1 = ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "PO/", false, 4, false)), 200);
        JsonNode s2 = ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(1, "PX/", false, 5, false)), 200);
        assertThat(s2.get("prefix").asText()).isEqualTo("PX/");
        assertThat(s1.get("numbersIssued").asBoolean()).isFalse();
        assertThat(issueNumber(w.clinic(), "PURCHASE_ORDER")).isOne();
        assertThat(seriesOf(ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200), "PURCHASE_ORDER").get("numbersIssued").asBoolean()).isTrue();
        // a stale version is a stale version first (409 settings-changed), then the lock (409 series-locked)
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(0, "PY/", false, 5, false))), 409), "settings-changed");
        JsonNode locked = problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(2, "PY/", false, 6, false))), 409);
        assertType(locked, "series-locked");
        assertThat(locked.get("detail").asText()).isEqualTo(LOCKED_TEXT);
        JsonNode still = seriesOf(ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200), "PURCHASE_ORDER");
        assertThat(still.get("prefix").asText()).isEqualTo("PX/");
        assertThat(still.get("sequenceLength").asInt()).isEqualTo(5);
        assertThat(still.get("version").asInt()).isEqualTo(2);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_SERIES_CHANGED")).as("the refused change wrote no audit").hasSize(2);
        // a validation error is still a 400 before the lock is looked at
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", w.clinic().owner(), js(seriesBody(2, "BAD PREFIX", false, 6, false))), 400), "validation");
    }

    @Test
    void aTypeThatWasNeverSavedIsLockedByItsFirstIssuedNumberAndOtherTypesStayFree() throws Exception {
        Masters w = mastersClinic();
        assertThat(issueNumber(w.clinic(), "GRN")).isOne();
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/GRN", w.clinic().owner(), js(seriesBody(0, "GR-", false, 4, false))), 409), "series-locked");
        assertThat(pharmacyRows(w.clinic(), "document_series")).as("the locked save wrote no row").isZero();
        ok(HttpMethod.PUT, SERIES_URL + "/INDENT", w.clinic().owner(), js(seriesBody(0, "ID-", false, 4, false)), 200);
        // the five SAH-79 types lock the same way, the bill included
        for (String type : new String[] {"DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN", "DAMAGE_REPORT", "STOCK_ADJUSTMENT", "BILL"}) {
            issueNumber(w.clinic(), type);
            assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/" + type, w.clinic().owner(), js(seriesBody(0, "L" + type.charAt(0) + type.charAt(1) + "-", false, 4, false))), 409), "series-locked");
        }
    }

    // Not testable from outside: "numbers of the current financial year only" (an old counter does not lock). The counter guard
    // (migration 1240) refuses a counter time that goes back ("document counter can only go up"), so a test cannot make a counter old,
    // and the IST financial year cannot be changed in an integration test. Covered by the unit test of NumberingService.

    @Test
    void aLockedSeriesIsLockedForEveryoneAndAnotherClinicsSeriesIsNotAffected() throws Exception {
        Masters mine = mastersClinic();
        Masters theirs = mastersClinic();
        issueNumber(mine.clinic(), "PURCHASE_ORDER");
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", mine.clinic().owner(), js(seriesBody(0, "MP-", false, 4, false))), 409), "series-locked");
        ok(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", theirs.clinic().owner(), js(seriesBody(0, "TP-", false, 4, false)), 200);
        Pn second = crew(mine.clinic(), "OWNER", "Second Owner");
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/PURCHASE_ORDER", second.auth(), js(seriesBody(0, "MQ-", false, 4, false))), 409), "series-locked");
    }

    // ---------------- the fixed reason (DEC-078 item 6) ----------------

    @Test
    void theReasonItemCarriesFixedTrueForShortNotReceivedOnlyAndItsSwitchOffIs409ThisReasonIsFixed() throws Exception {
        Masters w = mastersClinic();
        JsonNode lists = ok(HttpMethod.GET, REASON_LISTS, w.clinic().owner(), null, 200);
        int fixedCount = 0;
        for (JsonNode list : lists.get("lists")) {
            for (JsonNode reason : list.get("reasons")) {
                boolean fixed = reason.path("fixed").asBoolean(false);
                assertThat(reason.has("fixed")).as("fixed is answered on every reason: " + reason.get("reasonKey").asText()).isTrue();
                fixedCount += fixed ? 1 : 0;
                assertThat(fixed).as(reason.get("reasonKey").asText()).isEqualTo(reason.get("reasonKey").asText().equals("receipt-reject.short"));
            }
        }
        assertThat(fixedCount).isOne();
        JsonNode p = problem(call(HttpMethod.POST, REASON_LISTS + "/receipt-reject/reasons/receipt-reject.short/switch-off", w.clinic().owner(), null), 409);
        assertType(p, "last-active-reason");
        assertThat(p.get("detail").asText()).isEqualTo("This reason is fixed.");
        assertThat(reasonOf(ok(HttpMethod.GET, REASON_LISTS, w.clinic().owner(), null, 200), "receipt-reject", "receipt-reject.short").get("active").asBoolean()).isTrue();
        assertThat(pharmacyRows(w.clinic(), "reason_default_state")).isZero();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_REASON_SWITCHED_OFF")).isEmpty();
        // switching it on is harmless and writes nothing
        ok(HttpMethod.POST, REASON_LISTS + "/receipt-reject/reasons/receipt-reject.short/switch-on", w.clinic().owner(), null, 200);
        assertThat(pharmacyRows(w.clinic(), "reason_default_state")).isZero();
        // an own reason in the same list can be switched off, and its fixed flag is false
        JsonNode added = ok(HttpMethod.POST, REASON_LISTS + "/receipt-reject/reasons", w.clinic().owner(), "{\"label\":\"Wrong batch\"}", 201);
        String ownKey = reasonList(added, "receipt-reject").get("reasons").get(5).get("reasonKey").asText();
        assertThat(reasonOf(added, "receipt-reject", ownKey).get("fixed").asBoolean()).isFalse();
        assertThat(reasonOf(ok(HttpMethod.POST, REASON_LISTS + "/receipt-reject/reasons/" + ownKey + "/switch-off", w.clinic().owner(), null, 200), "receipt-reject", ownKey)
                .get("active").asBoolean()).isFalse();
    }

    // ---------------- vendor switch-on (DEC-078 item 7) ----------------

    private static String switchOnUrl(Object id) {
        return VENDORS + "/" + id + "/switch-on";
    }

    @Test
    void switchOnMakesAnInactiveVendorActiveAgainIsIdempotentAndAuditsOnce() throws Exception {
        Masters w = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Back Again (fake)"));
        String id = v.get("id").asText();
        // already active: 200, nothing written
        JsonNode same = ok(HttpMethod.POST, switchOnUrl(id), w.clinic().owner(), null, 200);
        assertThat(same.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(same.get("version").asInt()).isEqualTo(v.get("version").asInt());
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_SWITCHED_ON")).isEmpty();
        ok(HttpMethod.POST, vendorUrl(id) + "/switch-off", w.clinic().owner(), null, 200);
        // the name is unique: re-adding is refused, so switching on is the way back
        assertType(problem(call(HttpMethod.POST, VENDORS, w.clinic().owner(), js(vendorBody("Back Again (fake)"))), 409), "vendor-name-in-use");
        JsonNode on = ok(HttpMethod.POST, switchOnUrl(id), w.clinic().owner(), null, 200);
        assertThat(on.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(on.get("blocked").asBoolean()).isFalse();
        assertThat(on.get("name").asText()).isEqualTo("Back Again (fake)");
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_VENDOR_SWITCHED_ON");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(1)).isEqualTo(id);
        assertThat(audit.get(0).get(2)).contains("INACTIVE");
        assertThat(audit.get(0).get(3)).contains("ACTIVE");
        ok(HttpMethod.POST, switchOnUrl(id), w.clinic().owner(), null, 200);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_VENDOR_SWITCHED_ON")).as("a repeat writes nothing").hasSize(1);
        // it is in the default list again
        assertThat(ok(HttpMethod.GET, VENDORS, w.clinic().owner(), null, 200).get("total").asInt()).isOne();
    }

    @Test
    void aVendorWithAnExpiredLicenceComesBackBlockedNotActiveAndKeepsItsRecords() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = vendorBody("Lapsed Licence (fake)");
        body.put("licenceValidTill", todayIst().minusDays(2).toString());
        JsonNode v = createVendor(w.clinic(), body);
        String id = v.get("id").asText();
        ok(HttpMethod.POST, vendorUrl(id) + "/switch-off", w.clinic().owner(), null, 200);
        JsonNode on = ok(HttpMethod.POST, switchOnUrl(id), w.clinic().owner(), null, 200);
        assertThat(on.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(on.get("blocked").asBoolean()).isTrue();
        assertThat(on.get("licenceState").asText()).isEqualTo("EXPIRED");
        assertThat(on.get("creditDays").asInt()).isEqualTo(30);
        assertThat(on.get("suppliesTo")).hasSize(1);
    }

    @Test
    void switchOnIsOwnerOnlyAnotherClinicsVendorIs404AndAMalformedIdIs400() throws Exception {
        Masters w = mastersClinic();
        Masters other = mastersClinic();
        JsonNode v = createVendor(w.clinic(), vendorBody("Owner Only On (fake)"));
        String id = v.get("id").asText();
        ok(HttpMethod.POST, vendorUrl(id) + "/switch-off", w.clinic().owner(), null, 200);
        for (var who : nonOwners(w).entrySet()) {
            assertType(problem(call(HttpMethod.POST, switchOnUrl(id), who.getValue(), null), 403), "forbidden");
        }
        assertThat(values(w.clinic(), "select status from pharmacy.vendor where id = ?::uuid", id).get(0)).isEqualTo("INACTIVE");
        problem(call(HttpMethod.POST, switchOnUrl(id), other.clinic().owner(), null), 404);
        problem(call(HttpMethod.POST, switchOnUrl(UUID.randomUUID()), w.clinic().owner(), null), 404);
        assertType(problem(call(HttpMethod.POST, switchOnUrl("not-a-uuid"), w.clinic().owner(), null), 400), "bad-request");
        assertThat(values(w.clinic(), "select status from pharmacy.vendor where id = ?::uuid", id).get(0)).isEqualTo("INACTIVE");
    }

    @Test
    void withTheModuleOffSwitchOnAnswers403ModuleOffEvenForTheOwner() throws Exception {
        Clinic off = newClinic();
        JsonNode p = problem(call(HttpMethod.POST, switchOnUrl(UUID.randomUUID()), off.owner(), null), 403);
        assertType(p, "module-off");
        assertThat(p.get("detail").asText()).isEqualTo(MODULE_OFF_TEXT);
    }

    // ---------------- limitTypes per preset (DEC-078 item 3) ----------------

    private static List<String> limitTypes(JsonNode rules, String preset) {
        assertThat(rules.has("presets")).as("GET /approval-rules answers presets (0.2.1)").isTrue();
        for (JsonNode p : rules.get("presets")) {
            if (preset.equals(p.get("preset").asText())) {
                List<String> out = new ArrayList<>();
                p.get("limitTypes").forEach(t -> out.add(t.asText()));
                return out;
            }
        }
        throw new AssertionError("no presets entry for " + preset);
    }

    @Test
    void theRulesAnswerOneEntryPerPresetWithTheTypesThatNeedALimitInTransactionTypeOrder() throws Exception {
        Masters w = mastersClinic();
        JsonNode rules = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        List<String> names = new ArrayList<>();
        rules.get("presets").forEach(p -> names.add(p.get("preset").asText()));
        assertThat(names).containsExactly("LEAN", "STANDARD", "STRICT");
        assertThat(limitTypes(rules, "LEAN")).containsExactly("PURCHASE_ORDER");
        assertThat(limitTypes(rules, "STANDARD")).containsExactly("PURCHASE_ORDER", "GRN_RATE_RISE", "STOCK_ADJUSTMENT", "SALE_DISCOUNT", "SALES_RETURN");
        List<String> strict = limitTypes(rules, "STRICT");
        assertThat(strict).containsAll(limitTypes(rules, "STANDARD")).doesNotHaveDuplicates();
        List<String> order = new ArrayList<>(List.of(TRANSACTION_TYPES));
        assertThat(strict).as("in TransactionType order").isSortedAccordingTo((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b)));
        // the same field is on every answer of the rules (save, preset, a version)
        JsonNode saved = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, Map.of())), 200);
        assertThat(limitTypes(saved, "LEAN")).containsExactly("PURCHASE_ORDER");
        assertThat(ok(HttpMethod.GET, VERSIONS_URL + "/1", w.clinic().owner(), null, 200).has("presets")).isTrue();
        // another clinic gets the same lists: it is not clinic data
        assertThat(limitTypes(ok(HttpMethod.GET, RULES_URL, mastersClinic().clinic().owner(), null, 200), "STANDARD")).hasSize(5);
    }

    @Test
    void eachPresetIsAppliedWithExactlyTheLimitsTheServerListsAndEveryMissingOneIs400OnItsOwnField() throws Exception {
        Masters w = mastersClinic();
        for (String preset : new String[] {"LEAN", "STANDARD", "STRICT"}) {
            JsonNode current = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
            List<String> types = limitTypes(current, preset);
            long base = current.get("versionNo").asLong();
            List<Map<String, Object>> limits = new ArrayList<>();
            for (String type : types) {
                boolean percent = type.equals("SALE_DISCOUNT") || type.equals("GRN_RATE_RISE");
                limits.add(percent ? limit(type, null, "12.5", false) : limit(type, 1_000_000, null, false));
            }
            for (String omitted : types) {
                List<Map<String, Object>> fewer = new ArrayList<>(limits);
                fewer.removeIf(l -> l.get("transactionType").equals(omitted));
                JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(base, preset, fewer))), 400);
                assertFieldMessage(p, "limits." + omitted, "Enter the limit");
            }
            JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(base, preset, limits)), 200);
            assertThat(applied.get("preset").asText()).isEqualTo(preset);
            for (String type : types) {
                JsonNode levels = ruleOf(applied, type).get("levels");
                JsonNode owner = levels.get(levels.size() - 1);
                assertThat(owner.get("approver").asText()).as(preset + " " + type).isEqualTo("OWNER");
                boolean percent = type.equals("SALE_DISCOUNT") || type.equals("GRN_RATE_RISE");
                assertThat(owner.get("condition").get("kind").asText()).as(preset + " " + type).isEqualTo(percent ? "ABOVE_PERCENT" : "ABOVE_AMOUNT");
            }
        }
        // an entry for a type that needs no limit in this preset is refused
        JsonNode current = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        long base = current.get("versionNo").asLong();
        JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(base, "LEAN", List.of(limit("PURCHASE_ORDER", 1_000_000, null, false), limit("INDENT", 1_000_000, null, false))))), 400);
        assertFieldMessage(p, "limits.INDENT", "This preset has no limit for that item");
    }

    // ---------------- a rate rise takes a percentage only (DEC-078 item 2) ----------------

    @Test
    void theRateRiseLimitIsAlwaysOrAPercentageAndAnAmountOrAnyOtherKindIs400OnThatLevelsCondition() throws Exception {
        Masters w = mastersClinic();
        // accepted: ALWAYS, and a percent from 0 to 100 with at most two decimals
        for (Map<String, Object> level : List.of(always(1, "OWNER"), level(1, "OWNER", "ABOVE_PERCENT", null, "0", null), level(1, "OWNER", "ABOVE_PERCENT", null, "100", null),
                level(1, "OWNER", "ABOVE_PERCENT", null, "7.25", null))) {
            ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(currentVersion(w), null, only(rule("GRN_RATE_RISE", null, null, level)))), 200);
        }
        // refused: an amount, and every kind that does not make sense for a rise
        for (Map<String, Object> level : List.of(level(1, "OWNER", "ABOVE_AMOUNT", 100_000, null, null), level(1, "OWNER", "ABOVE_AMOUNT", null, null, null),
                level(1, "OWNER", "STORE_TYPE", null, null, List.of("PHARMACY")), level(1, "OWNER", "HIGH_VALUE_ITEM", null, null, null),
                level(1, "OWNER", "SCHEDULE_X_ITEM", null, null, null), level(1, "OWNER", "URGENT_PRIORITY", null, null, null))) {
            JsonNode p = problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(currentVersion(w), 100_000, only(rule("GRN_RATE_RISE", null, null, level))))), 400);
            boolean onCondition = false;
            for (JsonNode e : p.get("fieldErrors")) {
                onCondition |= e.get("field").asText().startsWith("rules[1].levels[0].condition");
            }
            assertThat(onCondition).as("a field error on rules[1].levels[0].condition for " + level + ": " + p.get("fieldErrors")).isTrue();
        }
        // percent range and decimals
        for (String percent : new String[] {"100.01", "-1", "5.123", "abc"}) {
            assertFieldMessage(problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(),
                    js(rulesBody(currentVersion(w), null, only(rule("GRN_RATE_RISE", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, percent, null)))))), 400),
                    "rules[1].levels[0].condition.percent", "Enter a % from 0 to 100");
        }
    }

    private long currentVersion(Masters w) throws Exception {
        return ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200).get("versionNo").asLong();
    }

    @Test
    void aPresetLimitForARateRiseIsAPercentAndAnAmountForItIs400() throws Exception {
        Masters w = mastersClinic();
        JsonNode current = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        List<String> types = limitTypes(current, "STANDARD");
        List<Map<String, Object>> good = new ArrayList<>();
        List<Map<String, Object>> withAmountForRise = new ArrayList<>();
        for (String type : types) {
            boolean percent = type.equals("SALE_DISCOUNT") || type.equals("GRN_RATE_RISE");
            good.add(percent ? limit(type, null, "5", false) : limit(type, 500_000, null, false));
            withAmountForRise.add(type.equals("GRN_RATE_RISE") ? limit(type, 500_000, null, false) : good.get(good.size() - 1));
        }
        JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, "STANDARD", withAmountForRise))), 400);
        assertThat(p.get("fieldErrors")).as("an amount for the rate rise is refused").isNotEmpty();
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isZero();
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, "STANDARD", good)), 200);
        JsonNode condition = ruleOf(applied, "GRN_RATE_RISE").get("levels").get(0).get("condition");
        assertThat(condition.get("kind").asText()).isEqualTo("ABOVE_PERCENT");
        assertThat(condition.get("percent").asText()).isEqualTo("5");
        assertThat(condition.get("amountPaise").isNull()).isTrue();
        // no amount condition for a rise is still allowed
        List<Map<String, Object>> none = new ArrayList<>(good);
        none.set(types.indexOf("GRN_RATE_RISE"), limit("GRN_RATE_RISE", null, null, true));
        JsonNode again = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(1, "STANDARD", none)), 200);
        assertThat(ruleOf(again, "GRN_RATE_RISE").get("levels").get(0).get("condition").get("kind").asText()).isEqualTo("ALWAYS");
    }

    // ---------------- the largest rupee limit (DEC-078 item 1) ----------------

    @Test
    void theLargestAmountLimitIs9_99_99_999RupeesThatIs9999999900PaiseAndOnePaiseMoreOrTenTimesMoreIs400() throws Exception {
        Masters w = mastersClinic();
        JsonNode saved = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(),
                js(rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", MAX_PAISE, null, null))))), 200);
        assertThat(ruleOf(saved, "PURCHASE_ORDER").get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(MAX_PAISE);
        for (long over : new long[] {MAX_PAISE + 1, MAX_PAISE + 100, 99_999_999_900L, 100_000_000_000L}) {
            JsonNode p = problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(),
                    js(rulesBody(1, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", over, null, null)))))), 400);
            assertFieldMessage(p, "rules[0].levels[0].condition.amountPaise", AMOUNT_TEXT);
        }
        assertThat(currentVersion(w)).as("the refused saves wrote nothing").isOne();
        // zero is the smallest limit and is accepted
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(1, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 0, null, null))))), 200);
    }

    @Test
    void theSameLargestAmountHoldsForAPresetLimit() throws Exception {
        Masters w = mastersClinic();
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", MAX_PAISE, null, false)))), 200);
        assertThat(ruleOf(applied, "PURCHASE_ORDER").get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(MAX_PAISE);
        for (long over : new long[] {MAX_PAISE + 1, 99_999_999_900L}) {
            JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(1, "LEAN", List.of(limit("PURCHASE_ORDER", over, null, false))))), 400);
            assertThat(p.get("fieldErrors")).as("over " + over).isNotEmpty();
            assertThat(p.get("fieldErrors").get(0).get("field").asText()).startsWith("limits");
        }
        assertThat(currentVersion(w)).isOne();
    }
}
