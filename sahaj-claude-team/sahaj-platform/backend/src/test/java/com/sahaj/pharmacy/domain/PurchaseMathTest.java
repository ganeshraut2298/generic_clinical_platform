package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The money rules of buying (contract rule 23): half up to the paisa, GST per line, rates with up to four decimals. */
class PurchaseMathTest {

    @Test
    void aLineAmountIsQuantityTimesRateRoundedHalfUpToThePaisa() {
        assertThat(PurchaseMath.amountPaise(10, new BigDecimal("70.00"))).isEqualTo(70_000L);
        assertThat(PurchaseMath.amountPaise(3, new BigDecimal("0.3333"))).as("0.9999 rupees").isEqualTo(100L);
        assertThat(PurchaseMath.amountPaise(1, new BigDecimal("0.0050"))).as("half a paisa rounds up").isEqualTo(1L);
        assertThat(PurchaseMath.amountPaise(1, new BigDecimal("0.0049"))).isZero();
        assertThat(PurchaseMath.amountPaise(999_999, new BigDecimal("9999999.9999"))).isEqualTo(999_998_999_990_000L);
    }

    @Test
    void gstIsWorkedOutOnTheTaxableValueHalfUp() {
        assertThat(PurchaseMath.gstPaise(70_125L, new BigDecimal("12"))).isEqualTo(8_415L);
        assertThat(PurchaseMath.gstPaise(1_001L, new BigDecimal("5"))).as("50.05 paise").isEqualTo(50L);
        assertThat(PurchaseMath.gstPaise(1_010L, new BigDecimal("5"))).as("50.5 paise rounds up").isEqualTo(51L);
        assertThat(PurchaseMath.gstPaise(1_000L, new BigDecimal("5.5"))).isEqualTo(55L);
        assertThat(PurchaseMath.gstPaise(1_000L, null)).isZero();
    }

    @Test
    void aRateIsAboveZeroWithAtMostFourDecimalsAndSevenDigits() {
        assertThat(PurchaseMath.validRate(new BigDecimal("0.0001"))).isTrue();
        assertThat(PurchaseMath.validRate(new BigDecimal("70.1250"))).isTrue();
        assertThat(PurchaseMath.validRate(new BigDecimal("70.12500"))).as("trailing zeros are not decimals").isTrue();
        assertThat(PurchaseMath.validRate(new BigDecimal("9999999.9999"))).isTrue();
        assertThat(PurchaseMath.validRate(new BigDecimal("0"))).isFalse();
        assertThat(PurchaseMath.validRate(new BigDecimal("-1"))).isFalse();
        assertThat(PurchaseMath.validRate(new BigDecimal("1.00001"))).isFalse();
        assertThat(PurchaseMath.validRate(new BigDecimal("10000000"))).isFalse();
        assertThat(PurchaseMath.validRate(null)).isFalse();
    }

    @Test
    void ratesAndPercentagesAreShownAsPlainDecimalStrings() {
        assertThat(PurchaseMath.rateText(new BigDecimal("70"))).isEqualTo("70.00");
        assertThat(PurchaseMath.rateText(new BigDecimal("70.1000"))).isEqualTo("70.10");
        assertThat(PurchaseMath.rateText(new BigDecimal("70.1250"))).isEqualTo("70.125");
        assertThat(PurchaseMath.rateText(new BigDecimal("1E+2"))).isEqualTo("100.00");
        assertThat(PurchaseMath.percentText(new BigDecimal("12.00"))).isEqualTo("12");
        assertThat(PurchaseMath.percentText(new BigDecimal("5.50"))).isEqualTo("5.5");
        assertThat(PurchaseMath.percentText(new BigDecimal("0"))).isEqualTo("0");
        assertThat(PurchaseMath.percentText(new BigDecimal("1E+1"))).isEqualTo("10");
    }
}
