package com.sahaj.billing.web;

import com.sahaj.billing.application.DiscountInput;

/** {@code PUT /billing/bills/{visitId}/discount} body in {@code docs/api/billing.yaml} (SAH-10). */
public record DiscountRequest(Integer percent, String reason) {

    public DiscountInput toInput() {
        return new DiscountInput(percent, reason);
    }
}
