package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.OpeningStockRules.Checked;
import com.sahaj.pharmacy.domain.OpeningStockRules.Raw;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The per-row checks of an opening-stock file (story 78-7, OQ-78-35). */
class OpeningStockRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private static Raw raw(String store, String product, String batch, String expiry, String mrp, String rate, String qty) {
        return new Raw(store, product, batch, expiry, mrp, rate, qty);
    }

    private static Checked check(Raw raw, boolean known) {
        return OpeningStockRules.check(raw, "CS", known, TODAY);
    }

    private static String codes(Checked checked) {
        return checked.issues().stream().map(i -> i.field() + ":" + i.code() + ":" + i.severity())
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    @Test
    void aGoodRowHasNoIssueAndEveryValueParsed() {
        Checked c = check(raw("cs", "G-000101", " TESTB001 ", "31-12-2027", "45.50", "30.25", "100"), true);
        assertThat(c.issues()).isEmpty();
        assertThat(c.hasError()).isFalse();
        assertThat(c.parsed().batchNo()).isEqualTo("TESTB001");
        assertThat(c.parsed().expiry()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(c.parsed().mrp()).isEqualByComparingTo("45.50");
        assertThat(c.parsed().purchaseRate()).isEqualByComparingTo("30.25");
        assertThat(c.parsed().quantity()).isEqualTo(100);
        assertThat(c.parsed().valuePaise()).isEqualTo(302500L);
    }

    @Test
    void theStoreCodeMustBeThisStoresCodeIgnoringCaseAndSpaces() {
        assertThat(codes(check(raw(" Cs ", "P", "B", "31-12-2027", "1", "1", "1"), true))).isEmpty();
        assertThat(codes(check(raw("OTHER", "P", "B", "31-12-2027", "1", "1", "1"), true)))
                .isEqualTo("storeCode:store-mismatch:ERROR");
        assertThat(codes(check(raw("", "P", "B", "31-12-2027", "1", "1", "1"), true)))
                .isEqualTo("storeCode:store-mismatch:ERROR");
        assertThat(codes(check(raw(null, "P", "B", "31-12-2027", "1", "1", "1"), true)))
                .isEqualTo("storeCode:store-mismatch:ERROR");
    }

    @Test
    void anUnknownProductIsAnError() {
        assertThat(codes(check(raw("CS", "NOPE", "B", "31-12-2027", "1", "1", "1"), false)))
                .isEqualTo("productCode:product-unknown:ERROR");
    }

    @Test
    void aBatchIsOneToTwentyCharactersAfterTrimming() {
        assertThat(check(raw("CS", "P", "B".repeat(20), "31-12-2027", "1", "1", "1"), true).issues()).isEmpty();
        for (String bad : new String[] {"", "   ", "B".repeat(21), null}) {
            Checked c = check(raw("CS", "P", bad, "31-12-2027", "1", "1", "1"), true);
            assertThat(codes(c)).isEqualTo("batch:batch-invalid:ERROR");
            assertThat(c.parsed().batchNo()).isNull();
        }
    }

    @Test
    void everyExpiryFormIsReadAndAMonthMeansItsLastDay() {
        assertThat(OpeningStockRules.parseExpiry("31-12-2027")).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(OpeningStockRules.parseExpiry("31/12/2027")).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(OpeningStockRules.parseExpiry("2027-12-31")).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(OpeningStockRules.parseExpiry("1-2-2028")).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(OpeningStockRules.parseExpiry("02/2028")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(OpeningStockRules.parseExpiry("2-2027")).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(OpeningStockRules.parseExpiry(" 12-2027 ")).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void anUnreadableExpiryIsNull() {
        for (String bad : new String[] {null, "", "soon", "31-02-2027", "32-01-2027", "00-01-2027", "13/2027", "00/2027",
                "2027/12/31", "31.12.2027", "12-27", "31-12-27", "2027-13-01", "12-31-2027"}) {
            assertThat(OpeningStockRules.parseExpiry(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void anExpiryOfTodayOrEarlierIsInThePastAndTomorrowIsFine() {
        assertThat(codes(check(raw("CS", "P", "B", "17-11-2026", "1", "1", "1"), true)))
                .isEqualTo("expiryDate:expiry-past:ERROR");
        assertThat(codes(check(raw("CS", "P", "B", "16-11-2026", "1", "1", "1"), true)))
                .isEqualTo("expiryDate:expiry-past:ERROR");
        assertThat(codes(check(raw("CS", "P", "B", "18-11-2026", "1", "1", "1"), true))).isEmpty();
        assertThat(codes(check(raw("CS", "P", "B", "11/2026", "1", "1", "1"), true))).isEmpty();
        assertThat(codes(check(raw("CS", "P", "B", "10/2026", "1", "1", "1"), true)))
                .isEqualTo("expiryDate:expiry-past:ERROR");
        assertThat(codes(check(raw("CS", "P", "B", "nope", "1", "1", "1"), true)))
                .isEqualTo("expiryDate:expiry-invalid:ERROR");
    }

    @Test
    void anAmountIsAboveZeroWithAtMostTwoDecimalsAndNotOver9999999Point99() {
        assertThat(OpeningStockRules.parseRate("0.01")).isEqualByComparingTo("0.01");
        assertThat(OpeningStockRules.parseRate(" 45 ")).isEqualByComparingTo("45");
        assertThat(OpeningStockRules.parseRate("9999999.99")).isEqualByComparingTo("9999999.99");
        for (String bad : new String[] {null, "", "0", "0.00", "-1", "1.234", "10000000", "9999999.991", "1,250", "12.", ".5",
                "Rs 5", "1e3", "abc"}) {
            assertThat(OpeningStockRules.parseRate(bad)).as(String.valueOf(bad)).isNull();
        }
        assertThat(codes(check(raw("CS", "P", "B", "31-12-2027", "x", "0", "1"), true)))
                .isEqualTo("mrp:mrp-invalid:ERROR,purchaseRate:rate-invalid:ERROR");
    }

    @Test
    void aQuantityIsAWholeNumberFromOneTo999999() {
        assertThat(OpeningStockRules.parseQuantity("1")).isEqualTo(1);
        assertThat(OpeningStockRules.parseQuantity("999999")).isEqualTo(999999);
        assertThat(OpeningStockRules.parseQuantity(" 25 ")).isEqualTo(25);
        for (String bad : new String[] {null, "", "0", "-5", "1.0", "1.5", "1000000", "12345678", "ten", "1 000"}) {
            assertThat(OpeningStockRules.parseQuantity(bad)).as(String.valueOf(bad)).isNull();
        }
        assertThat(codes(check(raw("CS", "P", "B", "31-12-2027", "1", "1", "0"), true)))
                .isEqualTo("quantity:quantity-invalid:ERROR");
    }

    @Test
    void aRateAboveTheMrpIsAWarningAndNotAnError() {
        Checked c = check(raw("CS", "P", "B", "31-12-2027", "10", "10.01", "1"), true);
        assertThat(codes(c)).isEqualTo("purchaseRate:rate-above-mrp:WARNING");
        assertThat(c.hasError()).isFalse();
        assertThat(check(raw("CS", "P", "B", "31-12-2027", "10", "10", "1"), true).issues()).isEmpty();
        // with the mrp unreadable there is nothing to compare
        assertThat(codes(check(raw("CS", "P", "B", "31-12-2027", "x", "10", "1"), true))).isEqualTo("mrp:mrp-invalid:ERROR");
    }

    @Test
    void theValueIsRateTimesQuantityInPaiseAndHalfUpToThePaisa() {
        assertThat(OpeningStockRules.valuePaise(new BigDecimal("30.25"), 100)).isEqualTo(302500L);
        assertThat(OpeningStockRules.valuePaise(new BigDecimal("0.01"), 1)).isEqualTo(1L);
        // the largest value fits a bigint with room to spare: 9999999.99 rupees times 999999 units
        assertThat(OpeningStockRules.valuePaise(new BigDecimal("9999999.99"), 999999)).isEqualTo(999998999000001L);
        // a rate with a third decimal (never accepted today) would round half up
        assertThat(OpeningStockRules.valuePaise(new BigDecimal("0.005"), 1)).isEqualTo(1L);
        assertThat(OpeningStockRules.valuePaise(new BigDecimal("0.0049"), 1)).isZero();
    }

    @Test
    void aValueIsOnlyWorkedOutWhenRateAndQuantityAreBothValid() {
        assertThat(check(raw("CS", "P", "B", "31-12-2027", "1", "x", "5"), true).parsed().valuePaise()).isNull();
        assertThat(check(raw("CS", "P", "B", "31-12-2027", "1", "2", "x"), true).parsed().valuePaise()).isNull();
        assertThat(check(raw("CS", "P", "B", "31-12-2027", "1", "2", "5"), true).parsed().valuePaise()).isEqualTo(1000L);
    }

    @Test
    void everyIssueCodeOfTheContractHasAPlainSentence() {
        for (String code : new String[] {"store-mismatch", "product-unknown", "batch-invalid", "batch-repeated",
                "expiry-invalid", "expiry-past", "mrp-invalid", "rate-invalid", "quantity-invalid", "rate-above-mrp"}) {
            assertThat(OpeningStockRules.message(code)).isNotBlank().isNotEqualTo("Check this value");
        }
    }

    @Test
    void severalProblemsOnOneRowAreAllReportedInColumnOrder() {
        Checked c = check(raw("X", "P", "", "soon", "0", "0", "0"), false);
        assertThat(c.issues()).extracting(i -> i.code()).containsExactly("store-mismatch", "product-unknown", "batch-invalid",
                "expiry-invalid", "mrp-invalid", "rate-invalid", "quantity-invalid");
        assertThat(c.hasError()).isTrue();
    }
}
