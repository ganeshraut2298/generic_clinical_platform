package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-80-18 and 80-6: the refund of some units of a line, and the digest a discount request keeps. */
class SaleRulesReturnTest {

    @Test
    void theRefundOfAWholeLineIsItsNetAmountAndTheTaxableAndTaxAddUpToIt() {
        SaleRules.ReturnShare all = SaleRules.returnShare(56040, 50036, 2, 0, 2);

        assertThat(all.refundPaise()).isEqualTo(56040);
        assertThat(all.taxablePaise()).isEqualTo(50036);
        assertThat(all.taxPaise()).isEqualTo(6004);
    }

    @Test
    void theSharesOfAnyOrderOfReturnsAddUpToTheLineExactly() {
        long net = 10_001;
        long taxable = 8_930;
        long sum = 0;
        long taxableSum = 0;
        int returned = 0;
        for (int qty : new int[] {1, 2, 1, 3}) {
            SaleRules.ReturnShare s = SaleRules.returnShare(net, taxable, 7, returned, qty);
            assertThat(s.taxablePaise() + s.taxPaise()).isEqualTo(s.refundPaise());
            sum += s.refundPaise();
            taxableSum += s.taxablePaise();
            returned += qty;
        }
        assertThat(sum).isEqualTo(net);
        assertThat(taxableSum).isEqualTo(taxable);
    }

    @Test
    void aShareRoundsHalfUpAndNeverGoesNegative() {
        assertThat(SaleRules.returnShare(1, 1, 2, 0, 1).refundPaise()).isEqualTo(1); // 0.5 rounds up
        assertThat(SaleRules.returnShare(1, 1, 2, 1, 1).refundPaise()).isEqualTo(0);
    }

    @Test
    void theCartDigestIsStableForTheSameLinesAndDiffersForAChangedQuantity() {
        String a = SaleRules.cartDigest(List.of("p1|2", "p2|1"));

        assertThat(a).hasSize(32).matches("[0-9a-f]+");
        assertThat(SaleRules.cartDigest(List.of("p1|2", "p2|1"))).isEqualTo(a);
        assertThat(SaleRules.cartDigest(List.of("p1|3", "p2|1"))).isNotEqualTo(a);
        assertThat(SaleRules.cartDigest(List.of("p2|1", "p1|2"))).isNotEqualTo(a);
    }
}
