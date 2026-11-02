package com.sahaj.billing.application;

import java.util.List;

/** {@code GET /billing/counter}'s response shape in {@code docs/api/billing.yaml} (SAH-10). */
public record CounterView(List<BillSummaryView> awaiting, List<BillSummaryView> dues,
                           List<BillSummaryView> paidToday) {
}
