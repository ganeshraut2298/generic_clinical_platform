package com.sahaj.billing.web;

import com.sahaj.billing.application.PaymentInput;

/** {@code POST /billing/bills/{visitId}/payments} body in {@code docs/api/billing.yaml} (SAH-10). */
public record PaymentRequest(String mode, Long amountPaise, String reference) {

    public PaymentInput toInput() {
        return new PaymentInput(mode, amountPaise, reference);
    }
}
