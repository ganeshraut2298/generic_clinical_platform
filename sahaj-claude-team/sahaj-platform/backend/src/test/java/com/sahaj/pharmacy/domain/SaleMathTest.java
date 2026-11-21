package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.SaleMath.LineIn;
import com.sahaj.pharmacy.domain.SaleMath.Result;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-030 S4: the worked example of mockup 02 and the rules that keep every paise accounted for. */
class SaleMathTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void theWorkedExampleOfTheMockupGivesTheStatedFigures() {
        // one line at 12 %: 56,040 paise of MRP, 5 % off
        Result r = SaleMath.compute(List.of(new LineIn(1, d("560.40"), d("12"))), d("5"));

        assertThat(r.gross()).isEqualTo(56_040);
        assertThat(r.discount()).isEqualTo(2_802);
        assertThat(r.net()).isEqualTo(53_238);
        assertThat(r.taxable()).isEqualTo(47_534);
        assertThat(r.cgst()).isEqualTo(2_852);
        assertThat(r.sgst()).isEqualTo(2_852);
        assertThat(r.roundOff()).isEqualTo(-38);
        assertThat(r.netPayable()).isEqualTo(53_200);
        assertThat(r.lines().get(0).t() + r.lines().get(0).x()).isEqualTo(r.lines().get(0).n());
    }

    @Test
    void theDiscountSharesAddUpToTheDiscountAndTheLargestRemainderGetsTheOddPaise() {
        // three equal lines share 100 paise of discount: 33 + 33 + 33 and one paise to the first (tie goes to the earlier line)
        long[] share = SaleMath.spread(new long[] {1_000, 1_000, 1_000}, 3_000, 100);

        assertThat(share).containsExactly(34, 33, 33);
        assertThat(share[0] + share[1] + share[2]).isEqualTo(100);
    }

    @Test
    void everyLineSplitsIntoTaxableAndTaxAndTheBillTotalsMatchTheLines() {
        Result r = SaleMath.compute(List.of(new LineIn(3, d("17.35"), d("5")), new LineIn(2, d("99.99"), d("12")),
                new LineIn(1, d("8.00"), d("0"))), d("7.5"));

        long net = 0;
        long taxable = 0;
        long discount = 0;
        for (SaleMath.Line line : r.lines()) {
            assertThat(line.a() - line.d()).isEqualTo(line.n());
            assertThat(line.t() + line.x()).isEqualTo(line.n());
            net += line.n();
            taxable += line.t();
            discount += line.d();
        }
        assertThat(net).isEqualTo(r.net());
        assertThat(taxable).isEqualTo(r.taxable());
        assertThat(discount).isEqualTo(r.discount());
        assertThat(r.taxable() + r.cgst() + r.sgst()).isEqualTo(r.net());
        assertThat(r.byRate()).extracting(SaleMath.RateTax::rate).containsExactly(d("0"), d("5"), d("12"));
        assertThat(r.netPayable() % 100).isZero();
        assertThat(r.roundOff()).isBetween(-50L, 49L);
    }

    @Test
    void aTieOnTheRupeeGoesDownSoTheRoundOffStaysWithinTheStoredRange() {
        // net 10.50: the round off is limited to -50..49, so the tie goes down to 10.00
        Result r = SaleMath.compute(List.of(new LineIn(1, d("10.50"), d("0"))), null);

        assertThat(r.discount()).isZero();
        assertThat(r.net()).isEqualTo(1_050);
        assertThat(r.netPayable()).isEqualTo(1_000);
        assertThat(r.roundOff()).isEqualTo(-50);
    }

    @Test
    void anEmptyCartIsAllZero() {
        Result r = SaleMath.compute(List.of(), d("10"));

        assertThat(r.gross()).isZero();
        assertThat(r.netPayable()).isZero();
        assertThat(r.byRate()).isEmpty();
    }
}
