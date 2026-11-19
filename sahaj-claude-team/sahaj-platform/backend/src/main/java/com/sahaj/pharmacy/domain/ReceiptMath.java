package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The arithmetic and the small rules of a goods receipt line (SAH-79-7, docs/api/pharmacy.yaml rule 23, ADR-029 P2 and P10,
 * OQ-79-6, OQ-79-9, OQ-79-10). Money is paise and rounds half up; a rate keeps four decimals. Quantities are purchase units
 * unless the name says issue units. Nothing here reads or writes data.
 */
public final class ReceiptMath {

    /** Invoice total against the computed total: more than this many paise apart is 400 invoice-total-mismatch (OQ-79-9b). */
    public static final long TOTAL_TOLERANCE_PAISE = 100L;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

    private ReceiptMath() {
    }

    /** {@code YYYY-MM} as the last day of that month (OQ-79-7); null when it is not a month. */
    public static LocalDate lastDayOf(String expiryMonth) {
        if (expiryMonth == null || !MONTH.matcher(expiryMonth).matches()) {
            return null;
        }
        try {
            return YearMonth.parse(expiryMonth).atEndOfMonth();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    /** An expiry month that is not before the current month (IST): valid through the last day of its month. */
    public static boolean notInThePast(LocalDate lastDay, LocalDate today) {
        return lastDay != null && !lastDay.isBefore(today);
    }

    /** {@code ptr x (1 - discount%)}, per purchase unit, four decimals half up (the rate the rate-rise check compares). */
    public static BigDecimal netRate(BigDecimal ptr, BigDecimal discountPercent) {
        return ptr.multiply(HUNDRED.subtract(discountPercent)).divide(HUNDRED, 4, RoundingMode.HALF_UP);
    }

    /** Billed quantity x PTR x (1 - discount%) in paise, half up: what the vendor charges before GST. */
    public static long taxablePaise(int billedQty, BigDecimal ptr, BigDecimal discountPercent) {
        return ptr.multiply(BigDecimal.valueOf(billedQty)).multiply(HUNDRED.subtract(discountPercent))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Landed cost per issue unit, without GST (OQ-79-6): {@code billed x PTR x (1 - discount%)} spread over
     * {@code (received + free) x conversion} issue units, four decimals half up.
     */
    public static BigDecimal landedRate(int billedQty, BigDecimal ptr, BigDecimal discountPercent, int receivedQty, int freeQty,
                                        int conversion) {
        long units = (long) (receivedQty + freeQty) * conversion;
        if (units <= 0) {
            return null;
        }
        BigDecimal value = ptr.multiply(BigDecimal.valueOf(billedQty)).multiply(HUNDRED.subtract(discountPercent))
                .divide(HUNDRED, 10, RoundingMode.HALF_UP);
        return value.divide(BigDecimal.valueOf(units), 4, RoundingMode.HALF_UP);
    }

    /** Issue units that enter stock: {@code (received + free) x conversion}. */
    public static int issueUnits(int receivedQty, int freeQty, int conversion) {
        return Math.multiplyExact(receivedQty + freeQty, conversion);
    }

    /** Rise of the net rate over the reference rate in percent, one decimal half up; null without a reference rate. */
    public static BigDecimal ratePercent(BigDecimal netRate, BigDecimal referenceRate) {
        if (referenceRate == null || referenceRate.signum() <= 0) {
            return null;
        }
        return netRate.subtract(referenceRate).multiply(HUNDRED).divide(referenceRate, 1, RoundingMode.HALF_UP);
    }

    /** "+6.1%" or "-2.0%": the contract's ratePercent text; null without a percentage. */
    public static String ratePercentText(BigDecimal percent) {
        if (percent == null) {
            return null;
        }
        return (percent.signum() >= 0 ? "+" : "") + percent.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Short quantity against the purchase order: what is left to receive minus what is received or billed, whichever is less. */
    public static int shortQty(int remainingQty, int receivedQty, int billedQty) {
        return Math.max(0, remainingQty - Math.min(receivedQty, billedQty));
    }

    /** Units billed but not received, purchase units (the debit-note short supply, OQ-79-9a). */
    public static int shortBilled(int billedQty, int receivedQty) {
        return Math.max(0, billedQty - receivedQty);
    }

    /** True when the PTR per issue unit is above the MRP per issue unit (a warning on the line, never an error). */
    public static boolean ptrAboveMrp(BigDecimal ptr, int conversion, BigDecimal mrp) {
        return ptr.divide(BigDecimal.valueOf(conversion), 4, RoundingMode.HALF_UP).compareTo(mrp) > 0;
    }

    /** The due date of a payable: invoice date plus credit days (OQ-79-5). */
    public static LocalDate dueDate(LocalDate invoiceDate, int creditDays) {
        return invoiceDate.plusDays(creditDays);
    }

    /** Discount and credit percentages: 0 to 100 with up to two decimals. */
    public static boolean validPercent(BigDecimal percent) {
        return percent != null && percent.signum() >= 0 && percent.compareTo(HUNDRED) <= 0
                && percent.stripTrailingZeros().scale() <= 2;
    }

    /** The credit of a return line in paise: quantity x rate x credit %, half up (OQ-79-11, G-13). */
    public static long creditPaise(int qty, BigDecimal rate, BigDecimal creditPercent) {
        return rate.multiply(BigDecimal.valueOf(qty)).multiply(creditPercent).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * The credit of short-supply units in paise (review m-4). The invoice values the goods per purchase unit, so when the units are
     * a whole number of purchase units the credit is worked in purchase units ({@code units x net rate x credit %}, half up) and
     * the per-issue-unit rate, rounded to 4 decimals, is for display only. Anything else falls back to issue units x that rate.
     */
    public static long shortSupplyCreditPaise(int issueUnits, int conversion, BigDecimal netRatePerPurchaseUnit,
                                              BigDecimal ratePerIssueUnit, BigDecimal creditPercent) {
        if (netRatePerPurchaseUnit != null && conversion > 0 && issueUnits % conversion == 0) {
            return creditPaise(issueUnits / conversion, netRatePerPurchaseUnit, creditPercent);
        }
        return creditPaise(issueUnits, ratePerIssueUnit, creditPercent);
    }

    /**
     * The credit percent a return line starts from (contract 0.3.3, ADR-029 N1, story 79-8): the damaged level for stock damaged
     * on receipt, the after-expiry level for an expired batch, the lower of the two when both apply; a level that is not set
     * counts as the standard credit before the two are compared; otherwise the standard credit. Null only when there is no
     * standard credit and no level applies (the person types the credit).
     */
    public static BigDecimal startingCredit(BigDecimal standard, BigDecimal damaged, BigDecimal expired, boolean damagedReason,
                                            boolean batchExpired) {
        BigDecimal damagedLevel = damaged != null ? damaged : standard;
        BigDecimal expiredLevel = expired != null ? expired : standard;
        if (damagedReason && batchExpired) {
            if (damagedLevel == null) {
                return expiredLevel;
            }
            return expiredLevel == null ? damagedLevel : damagedLevel.min(expiredLevel);
        }
        if (damagedReason) {
            return damagedLevel;
        }
        return batchExpired ? expiredLevel : standard;
    }
}
