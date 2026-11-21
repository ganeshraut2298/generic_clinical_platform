package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The GST maths of a counter sale (ADR-030 S4, DEC-074 OQ-80-6; the accountant's check is go-live G-22). All amounts are whole
 * paise. A line is a quantity of one batch at its MRP in rupees, GST included; the price is the batch MRP, never changed at the
 * counter. Steps: line amount, bill discount spread by the largest remainder so the parts add up to the whole, line net, taxable
 * value and tax per line, CGST and SGST per GST rate, then the round off of the net to whole rupees. Pure and side-effect free.
 */
public final class SaleMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SaleMath() {
    }

    /** One bill line: units of one batch at the batch MRP (rupees, GST included) and the product's GST percentage. */
    public record LineIn(int qty, BigDecimal mrp, BigDecimal gstRate) {
    }

    /** One bill line in paise: a amount, d discount share, n net (a - d), t taxable value, x tax (t + x = n). */
    public record Line(long a, long d, long n, long t, long x) {
    }

    /** CGST and SGST of one GST rate (counter sales are intra-state, so there is no IGST). */
    public record RateTax(BigDecimal rate, long taxable, long tax, long cgst, long sgst) {
    }

    /** The whole bill: gross A, discount D, net before round off N, taxable, CGST, SGST, round off and the net payable. */
    public record Result(List<Line> lines, long gross, long discount, long net, long taxable, long cgst, long sgst,
                         long roundOff, long netPayable, List<RateTax> byRate) {
    }

    /** {@code discountPercent} is 0 to 100 (null is none). */
    public static Result compute(List<LineIn> lines, BigDecimal discountPercent) {
        long[] amount = new long[lines.size()];
        long gross = 0;
        for (int i = 0; i < lines.size(); i++) {
            LineIn in = lines.get(i);
            amount[i] = BigDecimal.valueOf(in.qty()).multiply(in.mrp()).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            gross += amount[i];
        }
        BigDecimal percent = discountPercent == null ? BigDecimal.ZERO : discountPercent;
        long discount = BigDecimal.valueOf(gross).multiply(percent).divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
        long[] share = spread(amount, gross, discount);

        List<Line> out = new ArrayList<>();
        Map<BigDecimal, long[]> perRate = new TreeMap<>(Comparator.naturalOrder());
        long net = 0;
        long taxableTotal = 0;
        for (int i = 0; i < lines.size(); i++) {
            long n = amount[i] - share[i];
            BigDecimal rate = lines.get(i).gstRate() == null ? BigDecimal.ZERO : lines.get(i).gstRate().stripTrailingZeros();
            long taxable = BigDecimal.valueOf(n).multiply(HUNDRED).divide(HUNDRED.add(rate), 0, RoundingMode.HALF_UP).longValueExact();
            long tax = n - taxable;
            out.add(new Line(amount[i], share[i], n, taxable, tax));
            long[] sums = perRate.computeIfAbsent(rate, r -> new long[2]);
            sums[0] += taxable;
            sums[1] += tax;
            net += n;
            taxableTotal += taxable;
        }
        List<RateTax> byRate = new ArrayList<>();
        long cgstTotal = 0;
        long sgstTotal = 0;
        for (Map.Entry<BigDecimal, long[]> entry : perRate.entrySet()) {
            long tax = entry.getValue()[1];
            long cgst = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP).longValueExact();
            long sgst = tax - cgst;
            byRate.add(new RateTax(entry.getKey(), entry.getValue()[0], tax, cgst, sgst));
            cgstTotal += cgst;
            sgstTotal += sgst;
        }
        // ties go down: the stored round off is limited to -50..49 paise (ck on pharmacy.bill), and ADR-030 S4 names the same range
        long payable = BigDecimal.valueOf(net).divide(HUNDRED, 0, RoundingMode.HALF_DOWN).longValueExact() * 100;
        return new Result(out, gross, discount, net, taxableTotal, cgstTotal, sgstTotal, payable - net, payable, byRate);
    }

    /**
     * Spreads {@code discount} over the lines in proportion to their amounts so that the shares add up to exactly the discount:
     * each line gets the whole part of its exact share, then the leftover paise go one each to the lines with the largest
     * fractional part (the earlier line wins a tie).
     */
    static long[] spread(long[] amount, long gross, long discount) {
        long[] share = new long[amount.length];
        if (gross <= 0 || discount <= 0) {
            return share;
        }
        BigInteger[] remainder = new BigInteger[amount.length];
        BigInteger total = BigInteger.valueOf(gross);
        long given = 0;
        for (int i = 0; i < amount.length; i++) {
            BigInteger product = BigInteger.valueOf(discount).multiply(BigInteger.valueOf(amount[i]));
            BigInteger[] parts = product.divideAndRemainder(total);
            share[i] = parts[0].longValueExact();
            remainder[i] = parts[1];
            given += share[i];
        }
        long left = discount - given;
        Integer[] order = new Integer[amount.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> {
            int byRemainder = remainder[y].compareTo(remainder[x]);
            return byRemainder != 0 ? byRemainder : Integer.compare(x, y);
        });
        for (int k = 0; k < left; k++) {
            share[order[k]]++;
        }
        return share;
    }
}
