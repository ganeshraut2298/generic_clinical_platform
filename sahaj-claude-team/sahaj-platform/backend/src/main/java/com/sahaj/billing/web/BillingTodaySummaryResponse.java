package com.sahaj.billing.web;

import com.sahaj.billing.application.BillingTodaySummary;

/** Body of {@code GET /billing/today-summary} (SAH-15). */
public record BillingTodaySummaryResponse(long collectedPaise, int billsPending) {

    static BillingTodaySummaryResponse from(BillingTodaySummary summary) {
        return new BillingTodaySummaryResponse(summary.collectedPaise(), summary.billsPending());
    }
}
