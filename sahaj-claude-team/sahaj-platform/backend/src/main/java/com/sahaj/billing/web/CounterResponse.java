package com.sahaj.billing.web;

import com.sahaj.billing.application.CounterView;
import java.util.List;

/** {@code GET /billing/counter}'s response in {@code docs/api/billing.yaml} (SAH-10). */
public record CounterResponse(List<BillSummaryResponse> awaiting, List<BillSummaryResponse> dues,
                               List<BillSummaryResponse> paidToday) {

    public static CounterResponse from(CounterView view) {
        return new CounterResponse(view.awaiting().stream().map(BillSummaryResponse::from).toList(),
                view.dues().stream().map(BillSummaryResponse::from).toList(),
                view.paidToday().stream().map(BillSummaryResponse::from).toList());
    }
}
