package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.ConditionIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.LevelIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.LimitIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.PresetApply;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.RuleIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.RulesSave;
import com.sahaj.pharmacy.application.PharmacyMasterViews.ApprovalRules;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreStaff;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** SAH-78-9: approval rules, presets and versions. Today in the fixture is 17 Nov 2026. */
class ApprovalRulesServiceTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;

    private final MasterFixture m = new MasterFixture();
    private final PharmacyFixture f = m.f;

    // ---- builders ----

    private static ConditionIn always() {
        return new ConditionIn(ConditionKind.ALWAYS, null, null, null);
    }

    private static ConditionIn amount(long paise) {
        return new ConditionIn(ConditionKind.ABOVE_AMOUNT, BigDecimal.valueOf(paise), null, null);
    }

    private static ConditionIn percent(String percent) {
        return new ConditionIn(ConditionKind.ABOVE_PERCENT, null, percent, null);
    }

    private static LevelIn level(int n, ApproverKind who, ConditionIn condition) {
        return new LevelIn(BigDecimal.valueOf(n), who, condition);
    }

    private static RuleIn rule(TransactionType type, LevelIn... levels) {
        return new RuleIn(type, List.of(levels), Sent.of(null), Sent.of(null));
    }

    /** All nine rows with no level; {@code change} replaces the rows it wants. */
    private static RulesSave save(long base, Long high, Function<TransactionType, RuleIn> change) {
        List<RuleIn> rows = new ArrayList<>();
        for (TransactionType type : TransactionType.values()) {
            RuleIn changed = change.apply(type);
            rows.add(changed != null ? changed : rule(type));
        }
        return new RulesSave(base, Sent.of(high == null ? null : BigDecimal.valueOf(high)), rows);
    }

    private ApprovalRules put(RulesSave in) {
        return m.rulesService.save(TENANT, f.owner.userId(), in);
    }

    private ApprovalRules get() {
        return m.rulesService.get(TENANT, f.owner.userId());
    }

    private List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            return e.errors();
        }
        throw new AssertionError("expected a validation error");
    }

    private PharmacyMasterViews.Rule row(ApprovalRules rules, TransactionType type) {
        return rules.rules().stream().filter(r -> r.transactionType().equals(type.name())).findFirst().orElseThrow();
    }

    // ---- defaults ----

    @Test
    void beforeTheFirstSaveTheStandardPresetWithNoAmountsIsServedAndNothingIsWritten() {
        ApprovalRules rules = get();

        assertThat(rules.versionNo()).isZero();
        assertThat(rules.reviewNeeded()).isTrue();
        assertThat(rules.preset()).isEqualTo("STANDARD");
        assertThat(rules.savedAt()).isNull();
        assertThat(rules.savedBy()).isNull();
        assertThat(rules.highValueThresholdPaise()).isNull();
        assertThat(rules.rules()).extracting(PharmacyMasterViews.Rule::transactionType).containsExactly(
                "PURCHASE_ORDER", "GRN_RATE_RISE", "VENDOR_RETURN", "INDENT", "STOCK_TRANSFER", "WRITE_OFF",
                "STOCK_ADJUSTMENT", "SALE_DISCOUNT", "SALES_RETURN");
        PharmacyMasterViews.Rule po = row(rules, TransactionType.PURCHASE_ORDER);
        assertThat(po.levels()).hasSize(1);
        assertThat(po.levels().get(0).approver()).isEqualTo("OWNER");
        assertThat(po.levels().get(0).condition().kind()).isEqualTo("ALWAYS");
        assertThat(row(rules, TransactionType.INDENT).levels().get(0).approver()).isEqualTo("STORE_APPROVER");
        assertThat(row(rules, TransactionType.INDENT).escalateAfterHours()).isEqualTo(2);
        assertThat(row(rules, TransactionType.INDENT).urgentEscalateAfterMinutes()).isEqualTo(30);
        assertThat(row(rules, TransactionType.STOCK_TRANSFER).escalateAfterHours()).isEqualTo(24);
        assertThat(rules.fixedRules()).extracting(PharmacyMasterViews.FixedRule::key)
                .containsExactly("NO_SELF_APPROVAL", "SCHEDULE_X_TWO_SIGNATURES", "LEDGER_APPEND_ONLY");
        assertThat(m.writes).isEmpty();
    }

    @Test
    void aStoreWithoutAnHodIsListedWhileARuleNamesTheStoreApproverAndTheCurrentDelegateIsShown() {
        PharmacyViews.Store central = f.createCentral();
        PharmacyViews.Store ward = f.createStore("Test Ward Store", "WS1", StoreType.WARD);
        f.map(central, f.pharmacist, StoreRole.APPROVER);
        LocalDate today = PharmacyFixture.TODAY;
        f.staffService.map(PharmacyFixture.TENANT, f.owner.userId(), central.id(),
                new PharmacyInputs.MapStaff(f.nurse.id(), StoreRole.DELEGATE, today.minusDays(1), today.plusDays(5)));

        ApprovalRules rules = get();

        assertThat(rules.storesWithoutApprover()).extracting(PharmacyMasterViews.StoreWithoutApprover::storeId)
                .containsExactly(ward.id());
        assertThat(rules.currentDelegates()).hasSize(1);
        assertThat(rules.currentDelegates().get(0).storeId()).isEqualTo(central.id());
        assertThat(rules.currentDelegates().get(0).to()).isEqualTo(today.plusDays(5));
        assertThat(rules.currentDelegates().get(0).name()).isEqualTo("Test Nurse One");
    }

    @Test
    void noStoreIsListedWhenNoRuleNamesTheStoreApprover() {
        f.createCentral();
        put(save(0, null, t -> null));

        assertThat(get().storesWithoutApprover()).isEmpty();
    }

    // ---- save by hand ----

    @Test
    void aSaveWritesVersionOneAsCustomWithTheCallersMembershipAndAnAudit() {
        ApprovalRules saved = put(save(0, 500000L, t -> t == TransactionType.PURCHASE_ORDER
                ? rule(t, level(1, ApproverKind.OWNER, amount(5000000))) : t == TransactionType.SALE_DISCOUNT
                ? rule(t, level(1, ApproverKind.OWNER, percent("10"))) : null));

        assertThat(saved.versionNo()).isEqualTo(1);
        assertThat(saved.preset()).isEqualTo("CUSTOM");
        assertThat(saved.reviewNeeded()).isFalse();
        assertThat(saved.savedBy().membershipId()).isEqualTo(f.owner.id());
        assertThat(saved.savedBy().name()).isEqualTo("Test Owner One");
        assertThat(saved.savedAt()).isEqualTo(PharmacyFixture.NOW);
        assertThat(saved.highValueThresholdPaise()).isEqualTo(500000L);
        PharmacyMasterViews.Condition po = row(saved, TransactionType.PURCHASE_ORDER).levels().get(0).condition();
        assertThat(po.kind()).isEqualTo("ABOVE_AMOUNT");
        assertThat(po.amountPaise()).isEqualTo(5000000L);
        assertThat(po.percent()).isNull();
        assertThat(row(saved, TransactionType.SALE_DISCOUNT).levels().get(0).condition().percent()).isEqualTo("10");
        assertThat(row(saved, TransactionType.INDENT).levels()).isEmpty();
        assertThat(m.writes).containsExactly("rules.version", "rules.setInsert");
        assertThat(f.auditActions()).contains("PHARMACY_APPROVAL_RULES_SAVED");
        assertThat(get().versionNo()).isEqualTo(1);
    }

    @Test
    void savingTheSameRulesAgainWritesNothing() {
        RulesSave in = save(0, null, t -> t == TransactionType.WRITE_OFF
                ? rule(t, level(1, ApproverKind.STORE_APPROVER, always())) : null);
        put(in);
        m.writes.clear();
        f.audit.clear();

        ApprovalRules again = put(new RulesSave(1L, in.highValueThresholdPaise(), in.rules()));

        assertThat(again.versionNo()).isEqualTo(1);
        assertThat(m.writes).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void aChangeMakesVersionTwoAndMovesThePointerWhileVersionOneStaysReadable() {
        put(save(0, null, t -> null));

        ApprovalRules two = put(save(1, null, t -> t == TransactionType.INDENT
                ? new RuleIn(t, List.of(level(1, ApproverKind.STORE_APPROVER, always())), Sent.of(BigDecimal.valueOf(3)),
                        Sent.of(BigDecimal.valueOf(45))) : null));

        assertThat(two.versionNo()).isEqualTo(2);
        assertThat(row(two, TransactionType.INDENT).escalateAfterHours()).isEqualTo(3);
        assertThat(row(two, TransactionType.INDENT).urgentEscalateAfterMinutes()).isEqualTo(45);
        assertThat(m.writes).contains("rules.setUpdate");
        ApprovalRules one = m.rulesService.version(TENANT, f.owner.userId(), "1");
        assertThat(row(one, TransactionType.INDENT).levels()).isEmpty();
        PharmacyMasterViews.RuleVersionPage page = m.rulesService.versions(TENANT, f.pharmacist.userId(), null, null);
        assertThat(page.items()).extracting(PharmacyMasterViews.RuleVersion::versionNo).containsExactly(2, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.items().get(0).savedBy().name()).isEqualTo("Test Owner One");
    }

    @Test
    void aStaleBaseVersionIsRulesChangedWithTheCurrentRules() {
        put(save(0, null, t -> null));

        assertThatThrownBy(() -> put(save(0, null, t -> t == TransactionType.WRITE_OFF
                ? rule(t, level(1, ApproverKind.OWNER, always())) : null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("rules-changed");
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(((ApprovalRules) e.extensions().get("current")).versionNo()).isEqualTo(1);
                });
    }

    @Test
    void theContractChecksAreReportedTogetherWithFieldPaths() {
        List<FieldMessage> errors = errorsOf(() -> put(save(0, null, t -> {
            switch (t) {
                case PURCHASE_ORDER:
                    return rule(t, level(2, ApproverKind.OWNER, always()));
                case GRN_RATE_RISE:
                    return rule(t, level(1, ApproverKind.OWNER, new ConditionIn(ConditionKind.ABOVE_AMOUNT, null, null, null)));
                case VENDOR_RETURN:
                    return rule(t, level(1, ApproverKind.OWNER, always()), level(2, ApproverKind.OWNER, always()),
                            level(1, ApproverKind.OWNER, always()));
                case INDENT:
                    return rule(t, level(1, ApproverKind.OWNER, new ConditionIn(ConditionKind.HIGH_VALUE_ITEM, null, null, null)));
                case STOCK_TRANSFER:
                    return rule(t, level(1, ApproverKind.OWNER, new ConditionIn(ConditionKind.STORE_TYPE, null, null, null)));
                case WRITE_OFF:
                    return rule(t, level(1, ApproverKind.OWNER, new ConditionIn(ConditionKind.URGENT_PRIORITY, null, null, null)));
                case STOCK_ADJUSTMENT:
                    return new RuleIn(t, List.of(), Sent.of(BigDecimal.valueOf(169)), Sent.of(BigDecimal.valueOf(30)));
                case SALE_DISCOUNT:
                    return rule(t, level(1, ApproverKind.OWNER, amount(100)));
                default:
                    return rule(t, level(1, ApproverKind.OWNER, amount(9_999_999_901L)));
            }
        })));

        assertThat(errors).contains(
                new FieldMessage("rules[0].levels[0]", "Fill level 1 first"),
                new FieldMessage("rules[1].levels[0].condition.kind", "The rate-rise limit is a percentage"),
                new FieldMessage("rules[2].levels", "A rule has at most 2 levels"),
                new FieldMessage("highValueThresholdPaise", "Enter the high-value amount"),
                new FieldMessage("rules[4].levels[0].condition.storeTypes", "Choose at least one store type"),
                new FieldMessage("rules[5].levels[0].condition.kind", "Urgent priority is only for indents and transfers"),
                new FieldMessage("rules[6].escalateAfterHours", "Enter 1 to 168 hours"),
                new FieldMessage("rules[6].urgentEscalateAfterMinutes", "Only the indent has an urgent time"),
                new FieldMessage("rules[7].levels[0].condition.kind", "The discount limit is a percentage"),
                new FieldMessage("rules[8].levels[0].condition.amountPaise", ApprovalRuleChecks.AMOUNT_RANGE));
        assertThat(m.versionRows).isEmpty();
    }

    @Test
    void aRateRiseMayUseAPercentAndOtherRowsMayNot() {
        ApprovalRules saved = put(save(0, null, t -> t == TransactionType.GRN_RATE_RISE
                ? rule(t, level(1, ApproverKind.OWNER, percent("5.5"))) : null));

        PharmacyMasterViews.Condition c = row(saved, TransactionType.GRN_RATE_RISE).levels().get(0).condition();
        assertThat(c.kind()).isEqualTo("ABOVE_PERCENT");
        assertThat(c.percent()).isEqualTo("5.5");
        assertThat(errorsOf(() -> put(save(1, null, t -> t == TransactionType.PURCHASE_ORDER
                ? rule(t, level(1, ApproverKind.OWNER, percent("5"))) : null))))
                .contains(new FieldMessage("rules[0].levels[0].condition.kind", "A % limit is only for discounts and rate rises"));
    }

    @Test
    void theAmountLimitIsAtMost9999999900PaiseAndARateRiseTakesOnlyAlwaysOrAPercentage() {
        // exactly ₹ 9,99,99,999 is accepted
        ApprovalRules saved = put(save(0, null, t -> t == TransactionType.PURCHASE_ORDER
                ? rule(t, level(1, ApproverKind.OWNER, amount(9_999_999_900L))) : null));
        assertThat(row(saved, TransactionType.PURCHASE_ORDER).levels().get(0).condition().amountPaise())
                .isEqualTo(9_999_999_900L);
        // one paise over, and ten times the limit (the old maximum), are refused
        for (long over : new long[] {9_999_999_901L, 99_999_999_900L}) {
            assertThat(errorsOf(() -> put(save(1, null, t -> t == TransactionType.PURCHASE_ORDER
                    ? rule(t, level(1, ApproverKind.OWNER, amount(over))) : null))))
                    .contains(new FieldMessage("rules[0].levels[0].condition.amountPaise", ApprovalRuleChecks.AMOUNT_RANGE));
        }
        // a rate rise: ALWAYS and ABOVE_PERCENT only
        ApprovalRules always = put(save(1, null, t -> t == TransactionType.GRN_RATE_RISE
                ? rule(t, level(1, ApproverKind.OWNER, always())) : null));
        assertThat(row(always, TransactionType.GRN_RATE_RISE).levels().get(0).condition().kind()).isEqualTo("ALWAYS");
        for (ConditionKind other : new ConditionKind[] {ConditionKind.ABOVE_AMOUNT, ConditionKind.STORE_TYPE,
                ConditionKind.HIGH_VALUE_ITEM, ConditionKind.URGENT_PRIORITY, ConditionKind.SCHEDULE_X_ITEM}) {
            assertThat(errorsOf(() -> put(save(2, 100L, t -> t == TransactionType.GRN_RATE_RISE
                    ? rule(t, level(1, ApproverKind.OWNER, new ConditionIn(other, BigDecimal.TEN, null, List.of(StoreType.WARD))))
                    : null)))).as(other.name())
                    .contains(new FieldMessage("rules[1].levels[0].condition.kind", "The rate-rise limit is a percentage"));
        }
    }

    @Test
    void theHighValueAmountHasTheSameMaximumAsTheAmountLimit() {
        assertThat(put(save(0, 9_999_999_900L, t -> null)).highValueThresholdPaise()).isEqualTo(9_999_999_900L);
        for (long over : new long[] {9_999_999_901L, 99_999_999_900L}) {
            assertThat(errorsOf(() -> put(save(1, over, t -> null))))
                    .contains(new FieldMessage("highValueThresholdPaise", "Enter an amount up to ₹ 9,99,99,999"));
        }
    }

    @Test
    void theApprovalRulesAnswerListsTheLimitTypesOfEveryPreset() {
        List<PharmacyMasterViews.PresetInfo> presets = get().presets();

        assertThat(presets).extracting(PharmacyMasterViews.PresetInfo::preset).containsExactly("LEAN", "STANDARD", "STRICT");
        assertThat(presets.get(0).limitTypes()).containsExactly("PURCHASE_ORDER");
        assertThat(presets.get(1).limitTypes()).containsExactly("PURCHASE_ORDER", "GRN_RATE_RISE", "STOCK_ADJUSTMENT",
                "SALE_DISCOUNT", "SALES_RETURN");
        assertThat(presets.get(2).limitTypes()).hasSize(9);
        // what POST /approval-rules/preset needs is exactly what is listed
        for (PharmacyMasterViews.PresetInfo info : presets) {
            List<LimitIn> limits = new ArrayList<>();
            for (String type : info.limitTypes()) {
                limits.add(limit(TransactionType.valueOf(type), null, null, true));
            }
            long base = m.rulesService.get(TENANT, f.owner.userId()).versionNo();
            assertThat(m.rulesService.applyPreset(TENANT, f.owner.userId(),
                    new PresetApply(base, RulePreset.valueOf(info.preset()), limits)).preset()).isEqualTo(info.preset());
        }
    }

    @Test
    void aRateRiseLimitInAPresetIsAPercentageOnly() {
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L,
                RulePreset.STANDARD, List.of(limit(TransactionType.GRN_RATE_RISE, 100L, null, false)))))).contains(
                new FieldMessage("limits[0].amountPaise", "The rate-rise limit is a percentage"));
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L,
                RulePreset.STANDARD, List.of(limit(TransactionType.GRN_RATE_RISE, null, null, false)))))).contains(
                new FieldMessage("limits[0].percent", "Enter the limit"));
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L,
                RulePreset.STANDARD, List.of(limit(TransactionType.PURCHASE_ORDER, 9_999_999_901L, null, false)))))).contains(
                new FieldMessage("limits[0].amountPaise", ApprovalRuleChecks.AMOUNT_RANGE));
    }

    @Test
    void aTypeMissingOrRepeatedAndAnUnknownFieldCountAreRefused() {
        List<RuleIn> eight = new ArrayList<>(save(0, null, t -> null).rules());
        eight.remove(0);
        assertThat(errorsOf(() -> put(new RulesSave(0L, Sent.of(null), eight))))
                .contains(new FieldMessage("rules", "Send all nine transaction types once"));

        List<RuleIn> repeated = new ArrayList<>(save(0, null, t -> null).rules());
        repeated.set(1, repeated.get(0));
        assertThat(errorsOf(() -> put(new RulesSave(0L, Sent.of(null), repeated)))).contains(
                new FieldMessage("rules[1].transactionType", "Each transaction type can appear once"),
                new FieldMessage("rules", "Send all nine transaction types once"));

        assertThat(errorsOf(() -> put(new RulesSave(null, Sent.absent(), null)))).extracting(FieldMessage::field)
                .contains("baseVersionNo", "highValueThresholdPaise", "rules");
    }

    @Test
    void aStoreTypeConditionAndAHighValueConditionSaveWhenTheirValuesAreGiven() {
        ApprovalRules saved = put(save(0, 100000L, t -> t == TransactionType.INDENT
                ? rule(t, level(1, ApproverKind.STORE_APPROVER, new ConditionIn(ConditionKind.STORE_TYPE, null, null,
                        List.of(StoreType.WARD, StoreType.CENTRAL, StoreType.WARD))),
                        level(2, ApproverKind.OWNER, new ConditionIn(ConditionKind.HIGH_VALUE_ITEM, null, null, null)))
                : null));

        List<PharmacyMasterViews.Level> levels = row(saved, TransactionType.INDENT).levels();
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0).condition().storeTypes()).containsExactly("CENTRAL", "WARD");
        assertThat(levels.get(1).condition().kind()).isEqualTo("HIGH_VALUE_ITEM");
    }

    // ---- presets ----

    private static LimitIn limit(TransactionType type, Long amount, String percent, boolean none) {
        return new LimitIn(type, amount == null ? null : BigDecimal.valueOf(amount), percent, none);
    }

    @Test
    void leanNeedsTheOwnersPurchaseOrderLimitAndHasNoOtherLevel() {
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(),
                new PresetApply(0L, RulePreset.LEAN, List.of())))).containsExactly(
                new FieldMessage("limits.PURCHASE_ORDER", "Enter the limit"));
        assertThat(m.versionRows).isEmpty();

        ApprovalRules lean = m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L, RulePreset.LEAN,
                List.of(limit(TransactionType.PURCHASE_ORDER, 5000000L, null, false))));

        assertThat(lean.versionNo()).isEqualTo(1);
        assertThat(lean.preset()).isEqualTo("LEAN");
        assertThat(row(lean, TransactionType.PURCHASE_ORDER).levels()).hasSize(1);
        assertThat(row(lean, TransactionType.PURCHASE_ORDER).levels().get(0).condition().amountPaise()).isEqualTo(5000000L);
        assertThat(lean.rules().stream().filter(r -> !r.transactionType().equals("PURCHASE_ORDER")))
                .allMatch(r -> r.levels().isEmpty());
        assertThat(f.auditActions()).contains("PHARMACY_APPROVAL_PRESET_APPLIED");
    }

    @Test
    void leanTakesNoLimitForATypeThatNeedsNone() {
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L, RulePreset.LEAN,
                List.of(limit(TransactionType.PURCHASE_ORDER, 100L, null, false),
                        limit(TransactionType.INDENT, 100L, null, false)))))).contains(
                new FieldMessage("limits.INDENT", "This preset has no limit for that item"));
    }

    @Test
    void standardGivesTheStoreApproverAlwaysAndTheOwnerAboveTheLimitWithAPercentForDiscount() {
        List<LimitIn> limits = List.of(limit(TransactionType.PURCHASE_ORDER, 5000000L, null, false),
                limit(TransactionType.GRN_RATE_RISE, null, "5", false),
                limit(TransactionType.STOCK_ADJUSTMENT, 100000L, null, false),
                limit(TransactionType.SALE_DISCOUNT, null, "10", false),
                limit(TransactionType.SALES_RETURN, null, null, true));

        ApprovalRules std = m.rulesService.applyPreset(TENANT, f.owner.userId(),
                new PresetApply(0L, RulePreset.STANDARD, limits));

        assertThat(row(std, TransactionType.INDENT).levels()).hasSize(1);
        assertThat(row(std, TransactionType.INDENT).levels().get(0).approver()).isEqualTo("STORE_APPROVER");
        assertThat(row(std, TransactionType.VENDOR_RETURN).levels().get(0).approver()).isEqualTo("STORE_APPROVER");
        assertThat(row(std, TransactionType.SALE_DISCOUNT).levels().get(0).condition().percent()).isEqualTo("10");
        assertThat(row(std, TransactionType.GRN_RATE_RISE).levels().get(0).condition().kind()).isEqualTo("ABOVE_PERCENT");
        assertThat(row(std, TransactionType.SALES_RETURN).levels().get(0).condition().kind()).isEqualTo("ALWAYS");
        assertThat(row(std, TransactionType.INDENT).escalateAfterHours()).isEqualTo(2);
    }

    @Test
    void strictGivesTheStoreApproverThenTheOwnerForEveryType() {
        List<LimitIn> none = new ArrayList<>();
        for (TransactionType type : TransactionType.values()) {
            none.add(limit(type, null, null, true));
        }

        ApprovalRules strict = m.rulesService.applyPreset(TENANT, f.owner.userId(),
                new PresetApply(0L, RulePreset.STRICT, none));

        assertThat(strict.rules()).allSatisfy(r -> {
            assertThat(r.levels()).hasSize(2);
            assertThat(r.levels().get(0).approver()).isEqualTo("STORE_APPROVER");
            assertThat(r.levels().get(1).approver()).isEqualTo("OWNER");
        });
    }

    @Test
    void aPresetNeedsAPresetNotCustomAndADiscountLimitIsAPercentage() {
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(),
                new PresetApply(0L, RulePreset.CUSTOM, List.of())))).contains(new FieldMessage("preset", "Choose a preset"));
        assertThat(errorsOf(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L,
                RulePreset.STANDARD, List.of(limit(TransactionType.SALE_DISCOUNT, 100L, null, false)))))).contains(
                new FieldMessage("limits[0].amountPaise", "The discount limit is a percentage"));
    }

    @Test
    void aStalePresetBaseVersionIsRulesChanged() {
        put(save(0, null, t -> null));

        assertThatThrownBy(() -> m.rulesService.applyPreset(TENANT, f.owner.userId(), new PresetApply(0L,
                RulePreset.LEAN, List.of(limit(TransactionType.PURCHASE_ORDER, null, null, true)))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("rules-changed"));
    }

    // ---- reads, roles ----

    @Test
    void aVersionThatWasNeverSavedIs404AndOneThatIsNotANumberIs400() {
        assertThatThrownBy(() -> m.rulesService.version(TENANT, f.owner.userId(), "7")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> m.rulesService.version(TENANT, f.owner.userId(), "1.5"))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("bad-request"));
        assertThatThrownBy(() -> m.rulesService.version(TENANT, f.owner.userId(), "abc"))
                .isInstanceOf(PharmacyProblemException.class);
    }

    @Test
    void aPharmacistReadsButCannotSaveAndTheBodyIsNotReadForThem() {
        PharmacyInputs.Body<RulesSave> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the gate");
        };

        assertThat(m.rulesService.get(TENANT, f.pharmacist.userId()).versionNo()).isZero();
        assertThatThrownBy(() -> m.rulesService.save(TENANT, f.pharmacist.userId(), unreadable))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.rulesService.get(TENANT, f.nurse.userId())).isInstanceOf(ForbiddenException.class);
    }
}
