package com.sahaj.billing.application;

/** {@code PUT /billing/bills/{visitId}/discount} body in {@code docs/api/billing.yaml} (SAH-10). */
public record DiscountInput(Integer percent, String reason) {
}
