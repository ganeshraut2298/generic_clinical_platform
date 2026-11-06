package com.sahaj.billing.application;

/** {@code GET /billing/today-summary} in {@code docs/api/billing.yaml} (SAH-15): money figures for the Today screen. */
public record BillingTodaySummary(long collectedPaise, int billsPending) {
}
