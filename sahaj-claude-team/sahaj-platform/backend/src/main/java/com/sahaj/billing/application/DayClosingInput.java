package com.sahaj.billing.application;

/** {@code POST /billing/day-closing} body in {@code docs/api/billing.yaml} (SAH-10). */
public record DayClosingInput(Long countedCashPaise, String note) {
}
