package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SAH-10 / SAH-16: the shared bill arithmetic. */
class BillMathTest {

    @Test
    void discountIsAWholePercentOfTheSubtotalRoundedDown() {
        BillMath math = BillMath.of(50000, 12345, 10, 0);

        assertThat(math.subtotalPaise()).isEqualTo(62345);
        assertThat(math.discountPaise()).isEqualTo(6234);
        assertThat(math.totalPaise()).isEqualTo(56111);
        assertThat(math.balancePaise()).isEqualTo(56111);
    }

    @Test
    void balanceIsTotalMinusNetPayments() {
        BillMath math = BillMath.of(50000, 0, 0, 30000);

        assertThat(math.paidPaise()).isEqualTo(30000);
        assertThat(math.balancePaise()).isEqualTo(20000);
    }
}
