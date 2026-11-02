package com.sahaj.billing.web;

import com.sahaj.billing.application.DayClosingInput;

/** {@code POST /billing/day-closing} body in {@code docs/api/billing.yaml} (SAH-10). */
public record DayClosingRequest(Long countedCashPaise, String note) {

    public DayClosingInput toInput() {
        return new DayClosingInput(countedCashPaise, note);
    }
}
