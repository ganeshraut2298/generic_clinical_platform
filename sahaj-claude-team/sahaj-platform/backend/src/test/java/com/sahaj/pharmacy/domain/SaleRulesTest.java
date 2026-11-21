package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-80 pure rules: masks, the discount limit, the allergy keyword match, the 60-day ageing and the quote digest. */
class SaleRulesTest {

    private static ApprovalRule discountRule(ApprovalLevel... levels) {
        return new ApprovalRule(TransactionType.SALE_DISCOUNT, List.of(levels), null, null);
    }

    @Test
    void aMobileShowsTheFirstTwoAndTheLastThreeDigitsOnly() {
        assertThat(SaleRules.maskMobile("9876543214")).isEqualTo("+91 98XXX XX214");
        assertThat(SaleRules.maskMobile("+91 98765 43214")).isEqualTo("+91 98XXX XX214");
        assertThat(SaleRules.maskMobile("12")).isEqualTo("+91 XXXXX XXXXX");
        assertThat(SaleRules.maskMobile(null)).isNull();
    }

    @Test
    void aNameInAListShowsTheFirstLetterOfEachWord() {
        assertThat(SaleRules.maskName("Ravi  Kumar Sharma")).isEqualTo("R*** K*** S***");
        assertThat(SaleRules.maskName(" ")).isEmpty();
    }

    @Test
    void aPhoneIsTenDigits() {
        assertThat(SaleRules.validPhone("9000000101")).isTrue();
        assertThat(SaleRules.validPhone("900000010")).isFalse();
        assertThat(SaleRules.validPhone("90000001o1")).isFalse();
        assertThat(SaleRules.validPhone(null)).isFalse();
    }

    @Test
    void noDiscountRuleOrALevelFreeRuleIsLeanAndNeverNeedsApproval() {
        assertThat(SaleRules.discount(List.of(), new BigDecimal("50")).lean()).isTrue();
        SaleRules.Discount lean = SaleRules.discount(List.of(discountRule()), new BigDecimal("50"));
        assertThat(lean.lean()).isTrue();
        assertThat(lean.approvalNeeded()).isFalse();
    }

    @Test
    void aPercentLimitNeedsApprovalOnlyAboveIt() {
        ApprovalRule rule = discountRule(new ApprovalLevel(1, ApproverKind.OWNER,
                ApprovalCondition.abovePercent(new BigDecimal("10"))));

        SaleRules.Discount above = SaleRules.discount(List.of(rule), new BigDecimal("12.5"));
        assertThat(above.approvalNeeded()).isTrue();
        assertThat(above.limitPercent()).isEqualByComparingTo("10");
        assertThat(above.limitNotSet()).isFalse();
        assertThat(above.lean()).isFalse();
        assertThat(SaleRules.discount(List.of(rule), new BigDecimal("10")).approvalNeeded()).isFalse();
        assertThat(SaleRules.discount(List.of(rule), BigDecimal.ZERO).approvalNeeded()).isFalse();
    }

    @Test
    void aLevelWithNoLimitMeansEveryDiscountAboveZeroWaitsForTheOwner() {
        ApprovalRule rule = discountRule(new ApprovalLevel(1, ApproverKind.OWNER, ApprovalCondition.always()));

        SaleRules.Discount five = SaleRules.discount(List.of(rule), new BigDecimal("5"));
        assertThat(five.approvalNeeded()).isTrue();
        assertThat(five.limitNotSet()).isTrue();
        assertThat(five.limitPercent()).isNull();
        assertThat(SaleRules.discount(List.of(rule), null).approvalNeeded()).isFalse();
    }

    @Test
    void theStandardPresetOfANewUnitHasNoLimitSet() {
        SaleRules.Discount d = SaleRules.discount(ApprovalPresets.defaults(), new BigDecimal("2"));
        assertThat(d.limitNotSet()).isTrue();
        assertThat(d.approvalNeeded()).isTrue();
    }

    @Test
    void anAllergyTermThatIsAWholeWordOfTheSaltOrNameBlocks() {
        assertThat(SaleRules.allergyMatches("Penicillin", "Penicillin V", "Pen-V")).isTrue();
        assertThat(SaleRules.allergyMatches("sulfa, aspirin", "Aspirin", "Disprin")).isTrue();
        assertThat(SaleRules.allergyMatches("peanuts and dust; Crocin", "Paracetamol", "Crocin 500")).isTrue();
    }

    @Test
    void theFirstWordOfTheSaltFoundInTheTextBlocks() {
        assertThat(SaleRules.allergyMatches("Allergic to paracetamol (rash)", "Paracetamol 500 mg", "Calpol")).isTrue();
    }

    @Test
    void drugClassesAreNotCheckedAndPartsOfAWordDoNotMatch() {
        assertThat(SaleRules.allergyMatches("Penicillin", "Amoxicillin", "Amoxil")).isFalse();
        assertThat(SaleRules.allergyMatches("pen", "Penicillin", "Penvee")).isFalse();
        assertThat(SaleRules.allergyMatches("ab", "Ab", "Ab")).isFalse();
    }

    @Test
    void theNoneWordsAndEmptyTextNeverMatch() {
        assertThat(SaleRules.allergyMatches("NKDA", "Nkda", "Nkda")).isFalse();
        assertThat(SaleRules.allergyMatches("No known allergy", "known", "allergy")).isFalse();
        assertThat(SaleRules.allergyMatches(null, "Aspirin", "Aspirin")).isFalse();
        assertThat(SaleRules.allergyMatches("   ", "Aspirin", "Aspirin")).isFalse();
    }

    @Test
    void theDueOlderThanSixtyDaysIsWhatPaymentsHaveNotCoveredFifo() {
        assertThat(SaleRules.over60(500_00, 200_00)).isEqualTo(300_00);
        assertThat(SaleRules.over60(500_00, 800_00)).isZero();
        assertThat(SaleRules.over60(0, 0)).isZero();
    }

    @Test
    void moneyAndRatesReadAsTheContractWritesThem() {
        assertThat(SaleRules.rupeesText(53_200)).isEqualTo("₹ 532");
        assertThat(SaleRules.rupeesText(12_500_000)).isEqualTo("₹ 1,25,000");
        assertThat(SaleRules.rate(new BigDecimal("46.5"))).isEqualTo("46.50");
        assertThat(SaleRules.rate(new BigDecimal("0.0500"))).isEqualTo("0.05");
        assertThat(SaleRules.rate(new BigDecimal("100"))).isEqualTo("100.00");
        assertThat(SaleRules.percent(new BigDecimal("12.00"))).isEqualTo("12");
        assertThat(SaleRules.percent(new BigDecimal("5.50"))).isEqualTo("5.5");
    }

    @Test
    void theQuoteDigestIsStableForOneCartAndChangesWithAnyLineOrThePercent() {
        List<String> lines = List.of("p1|b1|2|46.50", "p2|b2|1|10.00");
        String a = SaleRules.quoteVersion(lines, new BigDecimal("5"));

        assertThat(SaleRules.quoteVersion(lines, new BigDecimal("5.0"))).isEqualTo(a);
        assertThat(SaleRules.quoteVersion(List.of("p1|b1|3|46.50", "p2|b2|1|10.00"), new BigDecimal("5"))).isNotEqualTo(a);
        assertThat(SaleRules.quoteVersion(lines, new BigDecimal("6"))).isNotEqualTo(a);
        assertThat(a).startsWith("q-").hasSizeLessThanOrEqualTo(64);
    }
}
