package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The money and percentage rules of stock (ADR-029 P2, G-13). Money is whole paise; rates are per issue unit, without GST. */
public final class StockMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private StockMath() {
    }

    /** Quantity x rate in paise, rounded half up to the paisa; 0 when there is no rate. */
    public static long valuePaise(long quantity, BigDecimal ratePerUnit) {
        if (ratePerUnit == null) {
            return 0L;
        }
        return BigDecimal.valueOf(quantity).multiply(ratePerUnit).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** (MRP - landed) / MRP as a percentage with one decimal ("12.5"); null without a rate or without an MRP. */
    public static String marginPercent(BigDecimal mrp, BigDecimal landed) {
        if (landed == null || mrp == null || mrp.signum() <= 0) {
            return null;
        }
        return mrp.subtract(landed).multiply(HUNDRED).divide(mrp, 1, RoundingMode.HALF_UP).toPlainString();
    }

}
