package com.sahaj.billing.web;

import com.sahaj.billing.application.RefundInput;

/** {@code POST /billing/bills/{visitId}/refund} body in {@code docs/api/billing.yaml} (SAH-10). */
public record RefundRequest(Long amountPaise, String mode, String reason) {

    public RefundInput toInput() {
        return new RefundInput(amountPaise, mode, reason);
    }
}
