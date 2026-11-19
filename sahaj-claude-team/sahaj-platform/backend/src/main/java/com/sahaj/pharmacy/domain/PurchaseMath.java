package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The money rules of buying (docs/api/pharmacy.yaml rule 23, ADR-028 D10, BR-9): a line amount is the quantity times the rate,
 * rounded half up to the paisa; GST is worked out per line on the taxable value, also rounded half up. Rates carry up to four
 * decimals and are per purchase unit. Nothing here reads or writes data.
 */
public final class PurchaseMath {

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final BigDecimal MAX_RATE = new BigDecimal("9999999.9999");

    private PurchaseMath() {
    }

    /** {@code qty x rate} in paise, half up. */
    public static long amountPaise(long qty, BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(qty)).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** GST on a taxable value in paise, half up; {@code gstRate} is a percentage such as 12 or 5.5. */
    public static long gstPaise(long taxablePaise, BigDecimal gstRate) {
        if (gstRate == null) {
            return 0L;
        }
        return BigDecimal.valueOf(taxablePaise).multiply(gstRate).divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** A rate above 0, at most 4 decimals and at most 7 digits before the point (contract Rate). */
    public static boolean validRate(BigDecimal rate) {
        return rate != null && rate.signum() > 0 && rate.stripTrailingZeros().scale() <= 4 && rate.compareTo(MAX_RATE) <= 0;
    }

    /** A rate as the contract shows it: a plain decimal string with at least 2 decimals, for example "70.00" or "70.1250". */
    public static String rateText(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal shown = rate.stripTrailingZeros();
        if (shown.scale() < 2) {
            shown = shown.setScale(2, RoundingMode.UNNECESSARY);
        }
        return shown.toPlainString();
    }

    /** A GST percentage as the contract shows it: plain, without trailing zeros beyond what was typed ("12", "5.5"). */
    public static String percentText(BigDecimal percent) {
        if (percent == null) {
            return null;
        }
        BigDecimal shown = percent.stripTrailingZeros();
        return (shown.scale() < 0 ? shown.setScale(0) : shown).toPlainString();
    }
}
