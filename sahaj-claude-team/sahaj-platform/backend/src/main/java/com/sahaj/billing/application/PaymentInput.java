package com.sahaj.billing.application;

/** {@code POST /billing/bills/{visitId}/payments} body in {@code docs/api/billing.yaml} (SAH-10). */
public record PaymentInput(String mode, Long amountPaise, String reference) {
}
