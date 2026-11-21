package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-78-8, 78-9 and 78-10 domain rules: licence state, approval presets, document numbers. No database, no Spring. */
class MasterRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private static Vendor vendor(boolean required, LocalDate till, boolean active) {
        return new Vendor(UUID.randomUUID(), "Test Vendor (fake)", null, required, required ? "TEST-DL-1" : null, till, null,
                null, 30, null, null, null, null, active, 0L);
    }

    // ---- licence state ----

    @Test
    void aLicenceIsValidThroughItsDateAndExpiredFromTheNextDay() {
        assertThat(LicenceState.of(true, TODAY, TODAY)).isEqualTo(LicenceState.VALID);
        assertThat(LicenceState.of(true, TODAY.minusDays(1), TODAY)).isEqualTo(LicenceState.EXPIRED);
        assertThat(LicenceState.of(true, TODAY.plusDays(1), TODAY)).isEqualTo(LicenceState.VALID);
    }

    @Test
    void aVendorThatNeedsNoLicenceIsNeverBlocked() {
        Vendor v = vendor(false, LocalDate.of(2000, 1, 1), true);

        assertThat(v.licenceState(TODAY)).isEqualTo(LicenceState.NOT_REQUIRED);
        assertThat(v.blocked(TODAY)).isFalse();
        assertThat(v.status(TODAY)).isEqualTo("ACTIVE");
    }

    @Test
    void theStatusIsActiveBlockedOrInactive() {
        assertThat(vendor(true, TODAY, true).status(TODAY)).isEqualTo("ACTIVE");
        assertThat(vendor(true, TODAY.minusDays(1), true).status(TODAY)).isEqualTo("BLOCKED");
        assertThat(vendor(true, TODAY.minusDays(1), false).status(TODAY)).isEqualTo("INACTIVE");
        assertThat(vendor(true, TODAY.minusDays(1), false).blocked(TODAY)).isFalse();
    }

    // ---- approval presets ----

    @Test
    void theDefaultsAreStandardWithOwnerStepsOnAlwaysAndNoAmount() {
        List<ApprovalRule> rules = ApprovalPresets.defaults();

        assertThat(rules).extracting(ApprovalRule::type).containsExactly(TransactionType.values());
        for (ApprovalRule rule : rules) {
            rule.levels().forEach(level -> assertThat(level.condition().kind()).isEqualTo(ConditionKind.ALWAYS));
        }
        assertThat(rules.get(3).levels()).hasSize(1);
        assertThat(rules.get(3).levels().get(0).approver()).isEqualTo(ApproverKind.STORE_APPROVER);
    }

    @Test
    void theTypesThatNeedALimitFollowThePreset() {
        assertThat(ApprovalPresets.limitTypes(RulePreset.LEAN)).containsExactly(TransactionType.PURCHASE_ORDER);
        assertThat(ApprovalPresets.limitTypes(RulePreset.STANDARD)).containsExactlyInAnyOrder(
                TransactionType.PURCHASE_ORDER, TransactionType.GRN_RATE_RISE, TransactionType.STOCK_ADJUSTMENT,
                TransactionType.SALE_DISCOUNT, TransactionType.SALES_RETURN);
        assertThat(ApprovalPresets.limitTypes(RulePreset.STRICT)).hasSize(9);
        assertThat(ApprovalPresets.limitTypes(RulePreset.CUSTOM)).isEmpty();
    }

    @Test
    void anOwnerStepWithALimitIsAboveItAndWithoutOneIsAlways() {
        List<ApprovalRule> lean = ApprovalPresets.build(RulePreset.LEAN,
                Map.of(TransactionType.PURCHASE_ORDER, new ApprovalPresets.Limit(5_000_000L, null)));
        List<ApprovalRule> leanNone = ApprovalPresets.build(RulePreset.LEAN, Map.of());

        assertThat(lean.get(0).levels().get(0).condition()).isEqualTo(ApprovalCondition.aboveAmount(5_000_000L));
        assertThat(leanNone.get(0).levels().get(0).condition()).isEqualTo(ApprovalCondition.always());
        assertThat(lean.get(3).levels()).isEmpty();
    }

    @Test
    void equalConditionsAreEqualWhateverTheScaleOfThePercentOrTheOrderOfStoreTypes() {
        assertThat(ApprovalCondition.abovePercent(new BigDecimal("10")))
                .isEqualTo(ApprovalCondition.abovePercent(new BigDecimal("10.00")));
        assertThat(new ApprovalCondition(ConditionKind.STORE_TYPE, null, null, List.of(StoreType.WARD, StoreType.CENTRAL)))
                .isEqualTo(new ApprovalCondition(ConditionKind.STORE_TYPE, 5L, BigDecimal.ONE,
                        List.of(StoreType.CENTRAL, StoreType.WARD, StoreType.CENTRAL)));
    }

    @Test
    void theAuditTextHoldsOnlyFixedWords() {
        ApprovalRule rule = new ApprovalRule(TransactionType.INDENT,
                List.of(new ApprovalLevel(1, ApproverKind.STORE_APPROVER, ApprovalCondition.always()),
                        new ApprovalLevel(2, ApproverKind.OWNER, ApprovalCondition.aboveAmount(100))), 2, 30);

        assertThat(rule.describe()).isEqualTo(
                "STORE_APPROVER ALWAYS; then OWNER ABOVE_AMOUNT 100 | escalate 2h | urgent 30m");
        assertThat(new ApprovalRule(TransactionType.WRITE_OFF, List.of(), null, null).describe()).isEqualTo("NO_APPROVAL");
    }

    // ---- document numbers ----

    @Test
    void theFinancialYearRunsFromAprilToMarch() {
        assertThat(DocumentNumbers.financialYearStart(LocalDate.of(2026, 3, 31))).isEqualTo(2025);
        assertThat(DocumentNumbers.financialYearStart(LocalDate.of(2026, 4, 1))).isEqualTo(2026);
        assertThat(DocumentNumbers.financialYearStart(LocalDate.of(2027, 1, 15))).isEqualTo(2026);
        assertThat(DocumentNumbers.financialYearPart(2026)).isEqualTo("26-27/");
        assertThat(DocumentNumbers.financialYearPart(2099)).isEqualTo("99-00/");
    }

    @Test
    void aNumberIsPrefixYearAndPaddedSequence() {
        assertThat(DocumentNumbers.render("SB/", true, 6, 1, TODAY)).isEqualTo("SB/26-27/000001");
        assertThat(DocumentNumbers.render("SB/", true, 6, 18422, LocalDate.of(2027, 2, 1))).isEqualTo("SB/26-27/018422");
        assertThat(DocumentNumbers.render("PO-", false, 4, 12, TODAY)).isEqualTo("PO-0012");
        assertThat(DocumentNumbers.renderedLength("SB/", true, 6)).isEqualTo(15);
        assertThat(DocumentNumbers.renderedLength("ABCDEF", true, 4)).isEqualTo(DocumentNumbers.MAX_LENGTH);
    }

    @Test
    void thePrefixAllowsLettersDigitsHyphenAndSlashUpToSixCharacters() {
        assertThat(DocumentNumbers.PREFIX.matcher("PO/A-1").matches()).isTrue();
        assertThat(DocumentNumbers.PREFIX.matcher("").matches()).isFalse();
        assertThat(DocumentNumbers.PREFIX.matcher("PO 1").matches()).isFalse();
        assertThat(DocumentNumbers.PREFIX.matcher("TOOLONG").matches()).isFalse();
    }

    @Test
    void everyDocTypeDefaultIsLegalAndOnlyTheBillRestarts() {
        for (DocType type : DocType.values()) {
            assertThat(DocumentNumbers.PREFIX.matcher(type.defaultPrefix()).matches()).as(type.name()).isTrue();
            assertThat(DocumentNumbers.renderedLength(type.defaultPrefix(), type.defaultFinancialYearPart(),
                    type.defaultSequenceLength())).isLessThanOrEqualTo(DocumentNumbers.MAX_LENGTH);
            assertThat(type.defaultRestarts()).isEqualTo(type == DocType.BILL || type == DocType.CREDIT_NOTE);
        }
        assertThat(DocType.values()).hasSize(12);
    }

    @Test
    void theElevenReasonListsAreFoundByTheirKey() {
        assertThat(ReasonList.values()).hasSize(20);
        assertThat(ReasonList.fromKey("cs-change")).contains(ReasonList.CS_CHANGE);
        assertThat(ReasonList.fromKey("Damage")).isEmpty();
        assertThat(ReasonList.fromKey(null)).isEmpty();
    }
}
