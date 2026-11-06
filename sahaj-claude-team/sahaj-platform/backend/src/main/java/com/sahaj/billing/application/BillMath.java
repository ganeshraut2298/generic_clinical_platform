package com.sahaj.billing.application;

/**
 * The one place a bill's totals are worked out (SAH-10), shared by the counter and the revenue report (SAH-16) so
 * both always show the same numbers. Subtotal is the consultation fee plus the extra lines; the discount is a whole
 * percent of the subtotal, rounded down to the paisa; the balance is what is still unpaid after net payments.
 */
public record BillMath(long subtotalPaise, long discountPaise, long totalPaise, long paidPaise, long balancePaise) {

    public static BillMath of(long feePaise, long itemsTotalPaise, int discountPercent, long paidPaise) {
        long subtotal = feePaise + itemsTotalPaise;
        long discount = subtotal * discountPercent / 100;
        long total = subtotal - discount;
        return new BillMath(subtotal, discount, total, paidPaise, total - paidPaise);
    }
}
