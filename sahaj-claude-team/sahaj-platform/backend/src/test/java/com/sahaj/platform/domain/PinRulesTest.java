package com.sahaj.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SAH-31, ADR-010 D4: the 4-digit rule and the weak-PIN rules, rule by rule. */
class PinRulesTest {

    private static final String MOBILE = "9822077410";

    @Test
    void onlyFourAsciiDigitsAreAPinFormat() {
        assertThat(PinRules.isFourDigits("4821")).isTrue();
        assertThat(PinRules.isFourDigits("0000")).isTrue();
        for (String bad : new String[] {null, "", "123", "12345", "12a4", " 123", "123 ", "-123", "1.23",
                "١٢٣٤" /* Arabic-Indic digits */, "１２３４" /* full-width */}) {
            assertThat(PinRules.isFourDigits(bad)).as("[%s]", bad).isFalse();
        }
    }

    @Test
    void theCodeIsExactlySixAsciiDigits() {
        assertThat(PinRules.isSixDigitCode("493017")).isTrue();
        assertThat(PinRules.isSixDigitCode("000000")).isTrue();
        for (String bad : new String[] {null, "", "12345", "1234567", "12345a", " 49301", "١٢٣٤٥٦"}) {
            assertThat(PinRules.isSixDigitCode(bad)).as("[%s]", bad).isFalse();
        }
    }

    @Test
    void rule2AllFourDigitsEqual() {
        for (int d = 0; d <= 9; d++) {
            assertThat(PinRules.isWeak("" + d + d + d + d, null)).as("" + d + d + d + d).isTrue();
        }
    }

    @Test
    void rule3StraightRunsUpAndDownWithNoWrap() {
        for (String weak : new String[] {"0123", "1234", "2345", "3456", "4567", "5678", "6789",
                "9876", "8765", "7654", "6543", "5432", "4321", "3210"}) {
            assertThat(PinRules.isWeak(weak, null)).as(weak).isTrue();
        }
        for (String fine : new String[] {"7890", "8901", "9012", "0987", "1359", "1243", "4831"}) {
            assertThat(PinRules.isWeak(fine, null)).as(fine).isFalse();
        }
    }

    @Test
    void rule4TwoRepeatedPairsAbabAndAabb() {
        for (String weak : new String[] {"3434", "5757", "1212", "7878", "3344", "5577", "2233", "4488"}) {
            assertThat(PinRules.isWeak(weak, null)).as(weak).isTrue();
        }
        for (String fine : new String[] {"3443", "5753", "3435", "3454", "4833"}) {
            assertThat(PinRules.isWeak(fine, null)).as(fine).isFalse();
        }
    }

    @Test
    void rule5YearsFrom1900To2099() {
        for (String weak : new String[] {"1900", "1987", "1999", "2000", "2026", "2099"}) {
            assertThat(PinRules.isWeak(weak, null)).as(weak).isTrue();
        }
        for (String fine : new String[] {"1899", "2100", "1800", "2101", "1090", "0199"}) {
            assertThat(PinRules.isWeak(fine, null)).as(fine).isFalse();
        }
    }

    @Test
    void rule6TheKeypadColumns() {
        assertThat(PinRules.isWeak("2580", null)).isTrue();
        assertThat(PinRules.isWeak("0852", null)).isTrue();
        assertThat(PinRules.isWeak("1470", null)).isFalse();
        assertThat(PinRules.isWeak("3690", null)).isFalse();
    }

    @Test
    void rule7AnyFourConsecutiveDigitsOfTheOwnMobile() {
        for (String weak : new String[] {"9822", "8220", "2207", "2077", "0774", "7741", "7410"}) {
            assertThat(PinRules.isWeak(weak, MOBILE)).as(weak).isTrue();
        }
        assertThat(PinRules.isWeak("9822", null)).as("no mobile known: rule skipped").isFalse();
        assertThat(PinRules.isWeak("4831", MOBILE)).isFalse();
        assertThat(PinRules.isWeak("9877", MOBILE)).as("not consecutive in the number").isFalse();
        assertThat(PinRules.isWeak("9822", "98 22-0")).as("non-digits in the mobile are ignored").isTrue();
    }

    @Test
    void aPlainPinIsAccepted() {
        for (String fine : new String[] {"4821", "7305", "6190", "0471", "9158"}) {
            assertThat(PinRules.isWeak(fine, MOBILE)).as(fine).isFalse();
        }
    }

    @Test
    void aboutFourHundredOfTheTenThousandPinsAreRefused() {
        int refused = 0;
        for (int i = 0; i < 10_000; i++) {
            if (PinRules.isWeak(String.format("%04d", i), null)) {
                refused++;
            }
        }
        assertThat(refused).isBetween(350, 450);
    }

    @Test
    void isWeakRefusesAMalformedPinAsAProgrammingError() {
        assertThatThrownBy(() -> PinRules.isWeak("12a4", MOBILE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PinRules.isWeak(null, MOBILE)).isInstanceOf(IllegalArgumentException.class);
    }
}
