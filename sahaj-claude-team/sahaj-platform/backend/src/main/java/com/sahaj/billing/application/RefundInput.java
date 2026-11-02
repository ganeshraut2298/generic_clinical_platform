package com.sahaj.billing.application;

/** {@code POST /billing/bills/{visitId}/refund} body in {@code docs/api/billing.yaml} (SAH-10). */
public record RefundInput(Long amountPaise, String mode, String reason) {
}
