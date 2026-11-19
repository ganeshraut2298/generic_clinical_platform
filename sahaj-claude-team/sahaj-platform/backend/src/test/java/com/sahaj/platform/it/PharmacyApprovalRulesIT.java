package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78-9 on the real database: the approval rules (docs/api/pharmacy.yaml 0.2.0), their presets and the immutable versions.
 * Sahaj ships no limit: before the first save the rules are STANDARD with no amounts and "review needed". The 0.2.1 items (the
 * rupee limit, a percentage for a rate rise, {@code limitTypes}) are in {@link PharmacyHardeningMastersIT}. Test plan:
 * docs/test-plans/SAH-78.md section 22 (T78-9-*).
 */
@OnTestDatabase
class PharmacyApprovalRulesIT extends Sah78CSupport {

    // ---------------- the defaults ----------------

    @Test
    void beforeTheFirstSaveTheRulesAreStandardWithNoAmountsVersionZeroAndReviewNeededAndTheReadWritesNothing() throws Exception {
        Masters w = mastersClinic();
        JsonNode r = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        assertThat(r.get("versionNo").asInt()).isZero();
        assertThat(r.get("preset").asText()).isEqualTo("STANDARD");
        assertThat(r.get("reviewNeeded").asBoolean()).isTrue();
        assertThat(r.get("savedAt").isNull()).isTrue();
        assertThat(r.get("savedBy").isNull()).isTrue();
        assertThat(r.get("highValueThresholdPaise").isNull()).isTrue();
        List<String> order = new ArrayList<>();
        r.get("rules").forEach(x -> order.add(x.get("transactionType").asText()));
        assertThat(order).containsExactly(TRANSACTION_TYPES);
        // STANDARD: the store approver always for indent, transfer, write-off and vendor return; the owner for the rest, no limit
        for (String type : new String[] {"INDENT", "STOCK_TRANSFER", "WRITE_OFF", "VENDOR_RETURN"}) {
            JsonNode levels = ruleOf(r, type).get("levels");
            assertThat(levels).as(type).hasSize(1);
            assertThat(levels.get(0).get("approver").asText()).isEqualTo("STORE_APPROVER");
            assertThat(levels.get(0).get("condition").get("kind").asText()).isEqualTo("ALWAYS");
        }
        for (String type : new String[] {"PURCHASE_ORDER", "GRN_RATE_RISE", "STOCK_ADJUSTMENT", "SALE_DISCOUNT", "SALES_RETURN"}) {
            JsonNode levels = ruleOf(r, type).get("levels");
            assertThat(levels).as(type).hasSize(1);
            assertThat(levels.get(0).get("approver").asText()).isEqualTo("OWNER");
            assertThat(levels.get(0).get("condition").get("kind").asText()).as("no limit is shipped").isEqualTo("ALWAYS");
            assertThat(levels.get(0).get("condition").get("amountPaise").isNull()).isTrue();
        }
        assertThat(ruleOf(r, "INDENT").get("escalateAfterHours").asInt()).isEqualTo(2);
        assertThat(ruleOf(r, "INDENT").get("urgentEscalateAfterMinutes").asInt()).isEqualTo(30);
        assertThat(ruleOf(r, "STOCK_TRANSFER").get("escalateAfterHours").asInt()).isEqualTo(24);
        List<String> fixed = new ArrayList<>();
        r.get("fixedRules").forEach(f -> fixed.add(f.get("key").asText() + "|" + f.get("text").asText()));
        assertThat(fixed).containsExactly("NO_SELF_APPROVAL|Nobody approves their own request.",
                "SCHEDULE_X_TWO_SIGNATURES|Schedule X needs a pharmacist and a second signature.",
                "LEDGER_APPEND_ONLY|The stock ledger cannot be edited; corrections are reversing lines.");
        assertThat(r.get("currentDelegates")).isEmpty();
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isZero();
        assertThat(pharmacyRows(w.clinic(), "approval_rule_set")).isZero();
        assertThat(ok(HttpMethod.GET, VERSIONS_URL, w.clinic().owner(), null, 200).get("items")).isEmpty();
    }

    @Test
    void storesWithNoApproverAreListedWhileARuleUsesTheStoreApproverAndTheCurrentDelegateIsShown() throws Exception {
        Masters w = mastersClinic();
        JsonNode r = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        List<String> without = new ArrayList<>();
        r.get("storesWithoutApprover").forEach(s -> without.add(s.get("name").asText()));
        assertThat(without).containsExactlyInAnyOrder("Central Store", "Main Pharmacy");
        Pn hod = crew(w.clinic(), "NURSE", "Nina Nurse");
        mapStaff(w.clinic(), w.centralId(), hod, "APPROVER");
        Pn delegate = crew(w.clinic(), "DOCTOR", "Dev Doctor");
        Map<String, Object> d = mapBody(delegate, "DELEGATE");
        d.put("delegateFrom", todayIst().minusDays(1).toString());
        d.put("delegateTo", todayIst().plusDays(5).toString());
        ok(HttpMethod.POST, staffUrl(w.pharmacyId()), w.clinic().owner(), js(d), 201);
        r = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        without.clear();
        r.get("storesWithoutApprover").forEach(s -> without.add(s.get("name").asText()));
        assertThat(without).containsExactly("Main Pharmacy");
        assertThat(r.get("currentDelegates")).hasSize(1);
        JsonNode cd = r.get("currentDelegates").get(0);
        assertThat(cd.get("storeId").asText()).isEqualTo(w.pharmacyId());
        assertThat(cd.get("storeName").asText()).isEqualTo("Main Pharmacy");
        assertThat(cd.get("name").asText()).isEqualTo("Dev Doctor");
        assertThat(cd.get("to").asText()).isEqualTo(todayIst().plusDays(5).toString());
        // a rule set with no store approver level lists no store without approver
        Map<String, Object> body = rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, always(1, "OWNER"))));
        JsonNode saved = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(body), 200);
        assertThat(saved.get("storesWithoutApprover")).isEmpty();
    }

    // ---------------- save ----------------

    @Test
    void aSaveOfAllNineRowsMakesVersionOneWithPresetCustomAuditsItAndTheSavedByIsThePerson() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> body = rulesBody(0, 500_000, only(rule("PURCHASE_ORDER", null, null,
                level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null))));
        Map<String, Map<String, Object>> more = new LinkedHashMap<>(only(rule("PURCHASE_ORDER", null, null,
                level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null))));
        more.put("INDENT", rule("INDENT", 2, 30, always(1, "STORE_APPROVER")));
        more.put("SALE_DISCOUNT", rule("SALE_DISCOUNT", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, "10", null)));
        body = rulesBody(0, 500_000, more);
        JsonNode saved = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(body), 200);
        assertThat(saved.get("versionNo").asInt()).isOne();
        assertThat(saved.get("preset").asText()).isEqualTo("CUSTOM");
        assertThat(saved.get("reviewNeeded").asBoolean()).isFalse();
        assertThat(saved.get("savedAt").isNull()).isFalse();
        assertThat(saved.get("savedBy").get("membershipId").asText()).isNotBlank();
        assertThat(saved.get("highValueThresholdPaise").asLong()).isEqualTo(500_000);
        assertThat(ruleOf(saved, "PURCHASE_ORDER").get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(5_000_000);
        assertThat(ruleOf(saved, "SALE_DISCOUNT").get("levels").get(0).get("condition").get("percent").asText()).isEqualTo("10");
        assertThat(ruleOf(saved, "WRITE_OFF").get("levels")).as("no level means no approval").isEmpty();
        assertThat(ruleOf(saved, "INDENT").get("escalateAfterHours").asInt()).isEqualTo(2);
        assertThat(ruleOf(saved, "INDENT").get("urgentEscalateAfterMinutes").asInt()).isEqualTo(30);
        // read back
        JsonNode again = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        assertThat(again.get("versionNo").asInt()).isOne();
        assertThat(again.get("rules")).isEqualTo(saved.get("rules"));
        List<List<String>> audit = auditRowsOf(w.clinic(), "PHARMACY_APPROVAL_RULES_SAVED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get(2)).contains("versionNo");
        assertThat(audit.get(0).get(3)).contains("CUSTOM");
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isOne();
        assertThat(pharmacyRows(w.clinic(), "approval_rule")).isEqualTo(9);
    }

    @Test
    void aSaveIdenticalToTheCurrentVersionWritesNothingAndAChangeMakesTheNextVersionWhileTheOldOneStaysAsItWas() throws Exception {
        Masters w = mastersClinic();
        Map<String, Map<String, Object>> first = only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null)));
        JsonNode v1 = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, first)), 200);
        JsonNode same = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(1, null, first)), 200);
        assertThat(same.get("versionNo").asInt()).as("nothing changed: still version 1").isOne();
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isOne();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_APPROVAL_RULES_SAVED")).hasSize(1);
        Map<String, Map<String, Object>> second = only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 7_500_000, null, null)));
        JsonNode v2 = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(1, null, second)), 200);
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        JsonNode old = ok(HttpMethod.GET, VERSIONS_URL + "/1", w.clinic().owner(), null, 200);
        assertThat(old.get("versionNo").asInt()).isOne();
        assertThat(ruleOf(old, "PURCHASE_ORDER").get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(5_000_000);
        assertThat(old.get("rules")).isEqualTo(v1.get("rules"));
        JsonNode list = ok(HttpMethod.GET, VERSIONS_URL, w.clinic().owner(), null, 200);
        assertThat(list.get("total").asInt()).isEqualTo(2);
        assertThat(list.get("items").get(0).get("versionNo").asInt()).as("newest first").isEqualTo(2);
        assertThat(list.get("items").get(1).get("versionNo").asInt()).isOne();
        assertThat(list.get("items").get(0).get("preset").asText()).isEqualTo("CUSTOM");
        assertThat(list.get("items").get(0).get("savedBy").get("name").asText()).isNotBlank();
        assertThat(list.get("size").asInt()).isEqualTo(20);
    }

    @Test
    void aStaleBaseVersionIs409RulesChangedWithTheCurrentRulesAndNothingIsWritten() throws Exception {
        Masters w = mastersClinic();
        Map<String, Map<String, Object>> a = only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null)));
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, a)), 200);
        Map<String, Map<String, Object>> b = only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 9_000_000, null, null)));
        JsonNode p = problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, b))), 409);
        assertType(p, "rules-changed");
        assertThat(p.get("current").get("versionNo").asInt()).isOne();
        assertThat(ruleOf(p.get("current"), "PURCHASE_ORDER").get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(5_000_000);
        JsonNode preset = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", 1_000_000, null, false))))), 409);
        assertType(preset, "rules-changed");
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isOne();
    }

    @Test
    void twoOwnersSavingAtTheSameMomentOneWinsAndTheOtherGets409RulesChanged() throws Exception {
        Masters w = mastersClinic();
        Pn second = crew(w.clinic(), "OWNER", "Second Owner");
        List<RequestPostProcessor> callers = List.of(w.clinic().owner(), second.auth());
        List<String> bodies = List.of(
                js(rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 1_000_000, null, null))))),
                js(rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 2_000_000, null, null))))));
        List<String> answers = atTheSameMoment(HttpMethod.PUT, RULES_URL, callers, bodies);
        assertThat(statusCount(answers, 200)).as(answers.toString()).isOne();
        assertThat(statusCount(answers, 409)).isOne();
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isOne();
    }

    // ---------------- validation ----------------

    private JsonNode bad(Masters w, Map<String, Object> body) throws Exception {
        JsonNode p = problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(body)), 400);
        assertType(p, "validation");
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).as("a refused save writes nothing").isZero();
        return p;
    }

    @Test
    void aMissingOrRepeatedTransactionTypeOrAWrongNumberOfRowsIs400() throws Exception {
        Masters w = mastersClinic();
        Map<String, Object> missing = rulesBody(0, null, Map.of());
        ((List<?>) missing.get("rules")).remove(0);
        assertFieldMessage(bad(w, missing), "rules", "Send all nine transaction types once");
        Map<String, Object> repeated = rulesBody(0, null, Map.of());
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) repeated.get("rules");
        list.set(1, rule("PURCHASE_ORDER", null, null));
        JsonNode p = bad(w, repeated);
        assertFieldMessage(p, "rules[1].transactionType", "Each transaction type can appear once");
        Map<String, Object> noRules = new LinkedHashMap<>();
        noRules.put("baseVersionNo", 0);
        noRules.put("highValueThresholdPaise", null);
        assertFieldMessage(bad(w, noRules), "rules", "This field is required");
    }

    @Test
    void levelsMustBeAtMostTwoAndLevelTwoNeedsLevelOneAndEveryConditionKindFollowsItsRule() throws Exception {
        Masters w = mastersClinic();
        // three levels
        JsonNode three = bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, always(1, "STORE_APPROVER"), always(2, "OWNER"), always(2, "OWNER")))));
        assertFieldMessage(three, "rules[0].levels", "A rule has at most 2 levels");
        // level 2 without level 1
        JsonNode noOne = bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, always(2, "OWNER")))));
        assertFieldMessage(noOne, "rules[0].levels[0]", "Fill level 1 first");
        // ABOVE_AMOUNT without an amount; a negative amount; an amount above the largest
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", null, null, null))))),
                "rules[0].levels[0].condition.amountPaise", "Enter the limit");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_AMOUNT", -1, null, null))))),
                "rules[0].levels[0].condition.amountPaise", "Enter an amount up to ₹ 9,99,99,999");
        // ABOVE_PERCENT is only for the discount (and the rate rise); the discount limit is never an amount
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, "5", null))))),
                "rules[0].levels[0].condition.kind", "A % limit is only for discounts and rate rises");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("SALE_DISCOUNT", null, null, level(1, "OWNER", "ABOVE_AMOUNT", 100, null, null))))),
                "rules[7].levels[0].condition.kind", "The discount limit is a percentage");
        // percent range
        for (String percent : new String[] {"100.01", "-1", "abc", "5.123"}) {
            assertFieldMessage(bad(w, rulesBody(0, null, only(rule("SALE_DISCOUNT", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, percent, null))))),
                    "rules[7].levels[0].condition.percent", "Enter a % from 0 to 100");
        }
        // HIGH_VALUE_ITEM needs the high-value amount
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "HIGH_VALUE_ITEM", null, null, null))))),
                "highValueThresholdPaise", "Enter the high-value amount");
        // STORE_TYPE needs store types
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "STORE_TYPE", null, null, null))))),
                "rules[0].levels[0].condition.storeTypes", "Choose at least one store type");
        // URGENT_PRIORITY only for indent and transfer
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null, level(1, "OWNER", "URGENT_PRIORITY", null, null, null))))),
                "rules[0].levels[0].condition.kind", "Urgent priority is only for indents and transfers");
    }

    @Test
    void theKindsThatAreAllowedAreAccepted() throws Exception {
        Masters w = mastersClinic();
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        rules.put("PURCHASE_ORDER", rule("PURCHASE_ORDER", null, null, always(1, "STORE_APPROVER"),
                level(2, "OWNER", "HIGH_VALUE_ITEM", null, null, null)));
        rules.put("INDENT", rule("INDENT", 168, 1440, level(1, "STORE_APPROVER", "URGENT_PRIORITY", null, null, null)));
        rules.put("STOCK_TRANSFER", rule("STOCK_TRANSFER", 1, null, level(1, "STORE_APPROVER", "URGENT_PRIORITY", null, null, null)));
        rules.put("WRITE_OFF", rule("WRITE_OFF", null, null, level(1, "OWNER", "STORE_TYPE", null, null, List.of("PHARMACY", "WARD"))));
        rules.put("VENDOR_RETURN", rule("VENDOR_RETURN", null, null, level(1, "OWNER", "SCHEDULE_X_ITEM", null, null, null)));
        rules.put("SALE_DISCOUNT", rule("SALE_DISCOUNT", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, "100", null)));
        rules.put("GRN_RATE_RISE", rule("GRN_RATE_RISE", null, null, level(1, "OWNER", "ABOVE_PERCENT", null, "7.5", null)));
        JsonNode saved = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, 100_000, rules)), 200);
        assertThat(ruleOf(saved, "WRITE_OFF").get("levels").get(0).get("condition").get("storeTypes")).hasSize(2);
        assertThat(ruleOf(saved, "INDENT").get("escalateAfterHours").asInt()).isEqualTo(168);
        assertThat(ruleOf(saved, "INDENT").get("urgentEscalateAfterMinutes").asInt()).isEqualTo(1440);
        assertThat(ruleOf(saved, "GRN_RATE_RISE").get("levels").get(0).get("condition").get("percent").asText()).isEqualTo("7.5");
    }

    @Test
    void escalationOutsideItsRangeOrAnUrgentTimeOnAnotherTypeIs400() throws Exception {
        Masters w = mastersClinic();
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("INDENT", 0, null, always(1, "STORE_APPROVER"))))),
                "rules[3].escalateAfterHours", "Enter 1 to 168 hours");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("INDENT", 169, null, always(1, "STORE_APPROVER"))))),
                "rules[3].escalateAfterHours", "Enter 1 to 168 hours");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("INDENT", null, 4, always(1, "STORE_APPROVER"))))),
                "rules[3].urgentEscalateAfterMinutes", "Enter 5 to 1440 minutes");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("INDENT", null, 1441, always(1, "STORE_APPROVER"))))),
                "rules[3].urgentEscalateAfterMinutes", "Enter 5 to 1440 minutes");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("STOCK_TRANSFER", null, 30, always(1, "STORE_APPROVER"))))),
                "rules[4].urgentEscalateAfterMinutes", "Only the indent has an urgent time");
        assertFieldMessage(bad(w, rulesBody(0, null, only(rule("INDENT", 1.5, null, always(1, "STORE_APPROVER"))))),
                "rules[3].escalateAfterHours", "Enter 1 to 168 hours");
    }

    @Test
    void theHighValueAmountMustBeInRangeAndAFixedRuleOrAnUnknownFieldIsRefused() throws Exception {
        Masters w = mastersClinic();
        assertFieldMessage(bad(w, rulesBody(0, -1, Map.of())), "highValueThresholdPaise", "Enter an amount up to ₹ 9,99,99,999");
        Map<String, Object> extra = rulesBody(0, null, Map.of());
        extra.put("fixedRules", List.of());
        JsonNode p = problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(extra)), 400);
        assertFieldMessage(p, "body", "Send only the fields listed for this request.");
        Map<String, Object> noHigh = rulesBody(0, null, Map.of());
        noHigh.remove("highValueThresholdPaise");
        assertFieldMessage(bad(w, noHigh), "highValueThresholdPaise", "This field is required");
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isZero();
        assertType(problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), "{\"rules\":"), 400), "malformed-request");
    }

    // ---------------- presets ----------------

    private static List<String> limitTypesOf(JsonNode rules, String preset) {
        List<String> out = new ArrayList<>();
        JsonNode presets = rules.get("presets");
        if (presets != null) {
            for (JsonNode p : presets) {
                if (preset.equals(p.get("preset").asText())) {
                    p.get("limitTypes").forEach(t -> out.add(t.asText()));
                    return out;
                }
            }
        }
        // contract 0.2.0 (no presets field yet): the owner step of each preset
        switch (preset) {
            case "LEAN":
                return List.of("PURCHASE_ORDER");
            case "STANDARD":
                return List.of("PURCHASE_ORDER", "GRN_RATE_RISE", "STOCK_ADJUSTMENT", "SALE_DISCOUNT", "SALES_RETURN");
            default:
                return List.of(TRANSACTION_TYPES);
        }
    }

    private static List<Map<String, Object>> limitsFor(List<String> types) {
        List<Map<String, Object>> limits = new ArrayList<>();
        for (String type : types) {
            boolean percent = type.equals("SALE_DISCOUNT") || type.equals("GRN_RATE_RISE");
            limits.add(percent ? limit(type, null, "10", false) : limit(type, 5_000_000, null, false));
        }
        return limits;
    }

    @Test
    void leanTakesTheOwnerLimitForThePurchaseOrderOnlyAndAnEntryForAnotherTypeIs400() throws Exception {
        Masters w = mastersClinic();
        JsonNode current = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        assertThat(limitTypesOf(current, "LEAN")).containsExactly("PURCHASE_ORDER");
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", 5_000_000, null, false)))), 200);
        assertThat(applied.get("preset").asText()).isEqualTo("LEAN");
        assertThat(applied.get("versionNo").asInt()).isOne();
        assertThat(applied.get("reviewNeeded").asBoolean()).isFalse();
        JsonNode po = ruleOf(applied, "PURCHASE_ORDER");
        assertThat(po.get("levels")).hasSize(1);
        assertThat(po.get("levels").get(0).get("approver").asText()).isEqualTo("OWNER");
        assertThat(po.get("levels").get(0).get("condition").get("kind").asText()).isEqualTo("ABOVE_AMOUNT");
        assertThat(po.get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(5_000_000);
        for (String type : new String[] {"INDENT", "STOCK_TRANSFER", "WRITE_OFF", "STOCK_ADJUSTMENT", "SALE_DISCOUNT", "SALES_RETURN", "GRN_RATE_RISE", "VENDOR_RETURN"}) {
            assertThat(ruleOf(applied, type).get("levels")).as("LEAN: no level for " + type).isEmpty();
        }
        // the escalation defaults come with every preset
        assertThat(ruleOf(applied, "INDENT").get("escalateAfterHours").asInt()).isEqualTo(2);
        assertThat(ruleOf(applied, "INDENT").get("urgentEscalateAfterMinutes").asInt()).isEqualTo(30);
        assertThat(ruleOf(applied, "STOCK_TRANSFER").get("escalateAfterHours").asInt()).isEqualTo(24);
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_APPROVAL_PRESET_APPLIED")).hasSize(1);
        // an entry for a type that needs none
        JsonNode extra = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(1, "LEAN",
                List.of(limit("PURCHASE_ORDER", 1_000_000, null, false), limit("INDENT", 1_000_000, null, false))))), 400);
        assertFieldMessage(extra, "limits.INDENT", "This preset has no limit for that item");
    }

    @Test
    void aMissingLimitIs400OnTheFieldOfThatTypeAndNoAmountConditionMakesTheOwnerStepAlways() throws Exception {
        Masters w = mastersClinic();
        JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, "LEAN", List.of()))), 400);
        assertType(p, "validation");
        assertFieldMessage(p, "limits.PURCHASE_ORDER", "Enter the limit");
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isZero();
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", null, null, true)))), 200);
        JsonNode condition = ruleOf(applied, "PURCHASE_ORDER").get("levels").get(0).get("condition");
        assertThat(condition.get("kind").asText()).isEqualTo("ALWAYS");
        // both a limit and no amount condition is refused
        JsonNode both = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(1, "LEAN", List.of(limit("PURCHASE_ORDER", 100, null, true))))), 400);
        assertThat(both.get("fieldErrors")).isNotEmpty();
    }

    @Test
    void standardPutsTheStoreApproverFirstForFourTypesAndTheOwnerAboveTheLimitForTheRest() throws Exception {
        Masters w = mastersClinic();
        JsonNode current = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        List<String> types = limitTypesOf(current, "STANDARD");
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, "STANDARD", limitsFor(types))), 200);
        assertThat(applied.get("preset").asText()).isEqualTo("STANDARD");
        for (String type : new String[] {"INDENT", "STOCK_TRANSFER", "WRITE_OFF", "VENDOR_RETURN"}) {
            JsonNode levels = ruleOf(applied, type).get("levels");
            assertThat(levels).as(type).hasSize(1);
            assertThat(levels.get(0).get("approver").asText()).isEqualTo("STORE_APPROVER");
        }
        assertThat(ruleOf(applied, "SALE_DISCOUNT").get("levels").get(0).get("condition").get("kind").asText()).isEqualTo("ABOVE_PERCENT");
        assertThat(ruleOf(applied, "SALE_DISCOUNT").get("levels").get(0).get("condition").get("percent").asText()).isEqualTo("10");
        assertThat(ruleOf(applied, "SALES_RETURN").get("levels").get(0).get("condition").get("amountPaise").asLong()).isEqualTo(5_000_000);
    }

    @Test
    void strictPutsTheStoreApproverThenTheOwnerAboveTheLimitOnEveryTypeThatNeedsOne() throws Exception {
        Masters w = mastersClinic();
        JsonNode current = ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200);
        List<String> types = limitTypesOf(current, "STRICT");
        assertThat(types).isNotEmpty();
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, "STRICT", limitsFor(types))), 200);
        assertThat(applied.get("preset").asText()).isEqualTo("STRICT");
        for (String type : TRANSACTION_TYPES) {
            JsonNode levels = ruleOf(applied, type).get("levels");
            assertThat(levels).as(type).hasSize(2);
            assertThat(levels.get(0).get("level").asInt()).isOne();
            assertThat(levels.get(0).get("approver").asText()).isEqualTo("STORE_APPROVER");
            assertThat(levels.get(0).get("condition").get("kind").asText()).isEqualTo("ALWAYS");
            assertThat(levels.get(1).get("level").asInt()).isEqualTo(2);
            assertThat(levels.get(1).get("approver").asText()).isEqualTo("OWNER");
        }
        // omit one limit: 400 on that field, nothing written
        List<String> missingOne = new ArrayList<>(types);
        String dropped = missingOne.remove(0);
        JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(1, "STRICT", limitsFor(missingOne)))), 400);
        assertFieldMessage(p, "limits." + dropped, "Enter the limit");
        assertThat(ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200).get("versionNo").asInt()).isOne();
    }

    @Test
    void aPresetKeepsTheHighValueAmountAndApplyingTheSamePresetAgainWritesNoNewVersion() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, 750_000, Map.of())), 200);
        JsonNode applied = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(1, "LEAN", List.of(limit("PURCHASE_ORDER", 5_000_000, null, false)))), 200);
        assertThat(applied.get("versionNo").asInt()).isEqualTo(2);
        assertThat(applied.get("highValueThresholdPaise").asLong()).isEqualTo(750_000);
        JsonNode again = ok(HttpMethod.POST, PRESET_URL, w.clinic().owner(),
                js(presetBody(2, "LEAN", List.of(limit("PURCHASE_ORDER", 5_000_000, null, false)))), 200);
        assertThat(again.get("versionNo").asInt()).isEqualTo(2);
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isEqualTo(2);
    }

    @Test
    void anUnknownPresetOrCustomOrMissingLimitsOrAStaleOrNegativeBaseVersionIs400() throws Exception {
        Masters w = mastersClinic();
        for (String preset : new String[] {"CUSTOM", "WILD", null}) {
            JsonNode p = problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(presetBody(0, preset, List.of()))), 400);
            assertThat(p.get("fieldErrors")).as("preset " + preset).isNotEmpty();
        }
        Map<String, Object> noLimits = presetBody(0, "LEAN", List.of());
        noLimits.remove("limits");
        assertFieldMessage(problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(noLimits)), 400), "limits", "This field is required");
        Map<String, Object> negative = presetBody(-1, "LEAN", List.of(limit("PURCHASE_ORDER", 1, null, false)));
        assertThat(problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(negative)), 400).get("fieldErrors")).isNotEmpty();
        Map<String, Object> repeated = presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", 1, null, false), limit("PURCHASE_ORDER", 2, null, false)));
        assertFieldMessage(problem(call(HttpMethod.POST, PRESET_URL, w.clinic().owner(), js(repeated)), 400), "limits[1].transactionType",
                "Each transaction type can appear once");
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isZero();
    }

    // ---------------- versions ----------------

    @Test
    void theVersionEndpointsAnswer404ForAnUnsavedVersionAnd400ForANumberThatIsNotWholeAndPagingIsChecked() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, Map.of())), 200);
        problem(call(HttpMethod.GET, VERSIONS_URL + "/2", w.clinic().owner(), null), 404);
        problem(call(HttpMethod.GET, VERSIONS_URL + "/0", w.clinic().owner(), null), 404);
        assertType(problem(call(HttpMethod.GET, VERSIONS_URL + "/abc", w.clinic().owner(), null), 400), "bad-request");
        assertType(problem(call(HttpMethod.GET, VERSIONS_URL + "/1.5", w.clinic().owner(), null), 400), "bad-request");
        for (String query : new String[] {"?page=-1", "?page=1001", "?size=0", "?size=51", "?size=x"}) {
            problem(call(HttpMethod.GET, VERSIONS_URL + query, w.clinic().owner(), null), 400);
        }
        ok(HttpMethod.GET, VERSIONS_URL + "?page=1000&size=50", w.clinic().owner(), null, 200);
        assertThat(ok(HttpMethod.GET, VERSIONS_URL + "/1", w.clinic().owner(), null, 200).get("versionNo").asInt()).isOne();
    }

    // ---------------- who may ----------------

    @Test
    void everyWriteByANonOwnerIs403AndNothingIsWrittenWhilePharmacistAndCentralStoreUserMayRead() throws Exception {
        Masters w = mastersClinic();
        Map<String, RequestPostProcessor> callers = nonOwners(w);
        for (var who : callers.entrySet()) {
            JsonNode put = problem(call(HttpMethod.PUT, RULES_URL, who.getValue(), js(rulesBody(0, null, Map.of()))), 403);
            assertType(put, "forbidden");
            JsonNode preset = problem(call(HttpMethod.POST, PRESET_URL, who.getValue(),
                    js(presetBody(0, "LEAN", List.of(limit("PURCHASE_ORDER", 1, null, false))))), 403);
            assertType(preset, "forbidden");
        }
        assertThat(pharmacyRows(w.clinic(), "approval_rule_version")).isZero();
        assertThat(count(w.clinic(), "select count(*) from platform.audit_event where action like 'PHARMACY\\_APPROVAL%'")).isZero();
        for (String reader : new String[] {"PHARMACIST", "CENTRAL_STORE_USER"}) {
            ok(HttpMethod.GET, RULES_URL, callers.get(reader), null, 200);
            ok(HttpMethod.GET, VERSIONS_URL, callers.get(reader), null, 200);
        }
        for (String other : new String[] {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING"}) {
            assertType(problem(call(HttpMethod.GET, RULES_URL, callers.get(other), null), 403), "forbidden");
            assertType(problem(call(HttpMethod.GET, VERSIONS_URL, callers.get(other), null), 403), "forbidden");
        }
    }

    // ---------------- the clinic wall ----------------

    @Test
    void anotherClinicHasItsOwnVersionsAndItsOwnDefaultsAndNeverSeesMine() throws Exception {
        Masters mine = mastersClinic();
        Masters theirs = mastersClinic();
        ok(HttpMethod.PUT, RULES_URL, mine.clinic().owner(), js(rulesBody(0, 123_400, only(rule("PURCHASE_ORDER", null, null,
                level(1, "OWNER", "ABOVE_AMOUNT", 5_000_000, null, null))))), 200);
        JsonNode theirRules = ok(HttpMethod.GET, RULES_URL, theirs.clinic().owner(), null, 200);
        assertThat(theirRules.get("versionNo").asInt()).isZero();
        assertThat(theirRules.get("highValueThresholdPaise").isNull()).isTrue();
        assertThat(ok(HttpMethod.GET, VERSIONS_URL, theirs.clinic().owner(), null, 200).get("items")).isEmpty();
        problem(call(HttpMethod.GET, VERSIONS_URL + "/1", theirs.clinic().owner(), null), 404);
        // their first save is version 1 of their own clinic
        JsonNode theirs1 = ok(HttpMethod.PUT, RULES_URL, theirs.clinic().owner(), js(rulesBody(0, null, Map.of())), 200);
        assertThat(theirs1.get("versionNo").asInt()).isOne();
        assertThat(count(theirs.clinic(), "select count(*) from pharmacy.approval_rule_version")).isOne();
        assertThat(count(mine.clinic(), "select count(*) from pharmacy.approval_rule_version")).isOne();
    }
}
