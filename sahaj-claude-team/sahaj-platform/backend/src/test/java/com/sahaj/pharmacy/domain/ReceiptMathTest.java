package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** SAH-79-7 and 79-8: the arithmetic of a receipt line and of a return line (rule 23, OQ-79-6, OQ-79-9, OQ-79-11, N1). */
class ReceiptMathTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void anExpiryMonthIsHeldAsTheLastDayOfThatMonthAndAMonthThatIsNotOneIsRefused() {
        assertThat(ReceiptMath.lastDayOf("2028-03")).isEqualTo(LocalDate.of(2028, 3, 31));
        assertThat(ReceiptMath.lastDayOf("2028-02")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ReceiptMath.lastDayOf("2027-02")).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(ReceiptMath.lastDayOf("2028-13")).isNull();
        assertThat(ReceiptMath.lastDayOf("2028-3")).isNull();
        assertThat(ReceiptMath.lastDayOf("Mar 2028")).isNull();
        assertThat(ReceiptMath.lastDayOf(null)).isNull();
    }

    @Test
    void anExpiryIsValidThroughTheLastDayOfItsMonthSoTheCurrentMonthIsNotInThePast() {
        LocalDate today = LocalDate.of(2026, 11, 19);
        assertThat(ReceiptMath.notInThePast(ReceiptMath.lastDayOf("2026-11"), today)).isTrue();
        assertThat(ReceiptMath.notInThePast(ReceiptMath.lastDayOf("2026-10"), today)).isFalse();
        assertThat(ReceiptMath.notInThePast(null, today)).isFalse();
        assertThat(ReceiptMath.notInThePast(LocalDate.of(2026, 11, 19), today)).isTrue();
    }

    @Test
    void theNetRateAndTheTaxableValueTakeTheDiscountOffThePtrAndRoundHalfUp() {
        assertThat(ReceiptMath.netRate(d("70.00"), d("5"))).isEqualByComparingTo("66.5000");
        assertThat(ReceiptMath.netRate(d("10.3333"), d("12.5"))).isEqualByComparingTo("9.0416");
        // 10 x 70.00 x 95% = 665.00 rupees
        assertThat(ReceiptMath.taxablePaise(10, d("70.00"), d("5"))).isEqualTo(66_500L);
        // 3 x 33.3333 x 87.5% = 87.499912 rupees, 8749.9912 paise
        assertThat(ReceiptMath.taxablePaise(3, d("33.3333"), d("12.5"))).isEqualTo(8_750L);
        assertThat(ReceiptMath.taxablePaise(0, d("70.00"), d("5"))).isZero();
    }

    @Test
    void landedCostSpreadsTheBilledValueOverReceivedAndFreeUnitsInIssueUnits() {
        // 10 strips billed at 70.00 less 5% = 665.00; (10 + 2 free) strips x 10 tablets = 120 tablets
        assertThat(ReceiptMath.landedRate(10, d("70.00"), d("5"), 10, 2, 10)).isEqualByComparingTo("5.5417");
        // free quantity only: nothing billed, nothing paid
        assertThat(ReceiptMath.landedRate(0, d("70.00"), d("0"), 0, 4, 10)).isEqualByComparingTo("0");
        assertThat(ReceiptMath.landedRate(10, d("70.00"), d("0"), 0, 0, 10)).isNull();
    }

    @Test
    void theRatePercentIsOneDecimalAndTheTextCarriesItsSign() {
        // 70.00 to 74.30 is +6.1% (OQ-79-10)
        BigDecimal rise = ReceiptMath.ratePercent(d("74.30"), d("70.00"));
        assertThat(rise).isEqualByComparingTo("6.1");
        assertThat(ReceiptMath.ratePercentText(rise)).isEqualTo("+6.1%");
        BigDecimal fall = ReceiptMath.ratePercent(d("68.60"), d("70.00"));
        assertThat(ReceiptMath.ratePercentText(fall)).isEqualTo("-2.0%");
        assertThat(ReceiptMath.ratePercentText(ReceiptMath.ratePercent(d("70.00"), d("70.00")))).isEqualTo("+0.0%");
        assertThat(ReceiptMath.ratePercent(d("70.00"), null)).isNull();
        assertThat(ReceiptMath.ratePercent(d("70.00"), BigDecimal.ZERO)).isNull();
        assertThat(ReceiptMath.ratePercentText(null)).isNull();
    }

    @Test
    void shortAgainstThePurchaseOrderIsWhatIsLeftToReceiveLessWhatCameOrWasBilled() {
        assertThat(ReceiptMath.shortQty(10, 6, 10)).isEqualTo(4);
        assertThat(ReceiptMath.shortQty(10, 10, 7)).isEqualTo(3);
        assertThat(ReceiptMath.shortQty(10, 10, 10)).isZero();
        assertThat(ReceiptMath.shortQty(10, 12, 12)).isZero();
        assertThat(ReceiptMath.shortBilled(10, 6)).isEqualTo(4);
        assertThat(ReceiptMath.shortBilled(6, 10)).isZero();
    }

    @Test
    void aPtrAboveTheMrpIsComparedPerIssueUnit() {
        // PTR 70.00 a strip of 10 is 7.00 a tablet
        assertThat(ReceiptMath.ptrAboveMrp(d("70.00"), 10, d("6.50"))).isTrue();
        assertThat(ReceiptMath.ptrAboveMrp(d("70.00"), 10, d("7.00"))).isFalse();
        assertThat(ReceiptMath.ptrAboveMrp(d("70.00"), 10, d("8.00"))).isFalse();
    }

    @Test
    void issueUnitsAreReceivedPlusFreeTimesTheConversionAndTheDueDateAddsTheCreditDays() {
        assertThat(ReceiptMath.issueUnits(10, 2, 10)).isEqualTo(120);
        assertThat(ReceiptMath.dueDate(LocalDate.of(2026, 11, 16), 30)).isEqualTo(LocalDate.of(2026, 12, 16));
        assertThat(ReceiptMath.dueDate(LocalDate.of(2026, 11, 16), 0)).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void aPercentIsZeroToOneHundredWithAtMostTwoDecimals() {
        assertThat(ReceiptMath.validPercent(d("0"))).isTrue();
        assertThat(ReceiptMath.validPercent(d("100"))).isTrue();
        assertThat(ReceiptMath.validPercent(d("12.55"))).isTrue();
        assertThat(ReceiptMath.validPercent(d("12.555"))).isFalse();
        assertThat(ReceiptMath.validPercent(d("100.01"))).isFalse();
        assertThat(ReceiptMath.validPercent(d("-1"))).isFalse();
        assertThat(ReceiptMath.validPercent(null)).isFalse();
    }

    @Test
    void theCreditOfAReturnLineIsQuantityTimesRateTimesPercentRoundedHalfUpToThePaisa() {
        // 7 x 5.5417 x 50% = 19.395950 rupees, 1939.595 paise
        assertThat(ReceiptMath.creditPaise(7, d("5.5417"), d("50"))).isEqualTo(1_940L);
        assertThat(ReceiptMath.creditPaise(100, d("12.00"), d("100"))).isEqualTo(120_000L);
        assertThat(ReceiptMath.creditPaise(1, d("0.0050"), d("100"))).isEqualTo(1L);
    }

    /** m-4: 15 tablets per strip, 1,000 strips short at Rs 100.00 a strip. */
    @Test
    void aShortSupplyIsCreditedInPurchaseUnitsSoTheRoundedPerTabletRateCannotDrift() {
        // per tablet: 100.00 / 15 = 6.6667 (rounded). Worked per tablet the credit is 50 paise above the invoice
        assertThat(ReceiptMath.creditPaise(15_000, d("6.6667"), d("100"))).isEqualTo(10_000_050L);
        // worked per strip it is exactly what the vendor billed: 1,000 x 100.00 = Rs 1,00,000.00
        assertThat(ReceiptMath.shortSupplyCreditPaise(15_000, 15, d("100.00"), d("6.6667"), d("100"))).isEqualTo(10_000_000L);
        // a credit level below 100 % is rounded half up once: 1,000 x 72.35 x 33.33 % = 24,113,... paise
        assertThat(ReceiptMath.shortSupplyCreditPaise(15_000, 15, d("72.35"), d("4.8233"), d("33.33")))
                .isEqualTo(ReceiptMath.creditPaise(1_000, d("72.35"), d("33.33")));
        // units that are not a whole number of strips (a person edited the draft) fall back to units x the rate
        assertThat(ReceiptMath.shortSupplyCreditPaise(20, 15, d("100.00"), d("6.6667"), d("100")))
                .isEqualTo(ReceiptMath.creditPaise(20, d("6.6667"), d("100")));
        assertThat(ReceiptMath.shortSupplyCreditPaise(20, 10, null, d("7.0000"), d("100"))).isEqualTo(14_000L);
    }

    @Test
    void theStartingCreditFollowsTheVendorLevelsAndTheLowerOfTwoWinsWhenBothApply() {
        BigDecimal standard = d("80");
        BigDecimal damaged = d("100");
        BigDecimal expired = d("50");
        assertThat(ReceiptMath.startingCredit(standard, damaged, expired, false, false)).isEqualByComparingTo("80");
        assertThat(ReceiptMath.startingCredit(standard, damaged, expired, true, false)).isEqualByComparingTo("100");
        assertThat(ReceiptMath.startingCredit(standard, damaged, expired, false, true)).isEqualByComparingTo("50");
        assertThat(ReceiptMath.startingCredit(standard, damaged, expired, true, true)).isEqualByComparingTo("50");
        // a level that is not set counts as the standard credit before the two are compared
        assertThat(ReceiptMath.startingCredit(standard, null, expired, true, true)).isEqualByComparingTo("50");
        assertThat(ReceiptMath.startingCredit(standard, damaged, null, true, true)).isEqualByComparingTo("80");
        assertThat(ReceiptMath.startingCredit(standard, null, null, true, false)).isEqualByComparingTo("80");
    }

    @Test
    void theStartingCreditIsNullOnlyWhenThereIsNoStandardCreditAndNoLevelApplies() {
        assertThat(ReceiptMath.startingCredit(null, null, null, false, false)).isNull();
        assertThat(ReceiptMath.startingCredit(null, null, null, true, true)).isNull();
        assertThat(ReceiptMath.startingCredit(null, d("100"), null, true, false)).isEqualByComparingTo("100");
        assertThat(ReceiptMath.startingCredit(null, d("100"), null, true, true)).isEqualByComparingTo("100");
        assertThat(ReceiptMath.startingCredit(null, null, d("40"), false, true)).isEqualByComparingTo("40");
        assertThat(ReceiptMath.startingCredit(null, d("100"), null, false, false)).isNull();
    }
}
