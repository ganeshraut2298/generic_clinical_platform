package com.sahaj.billing.application;

import java.time.Instant;
import java.time.LocalDate;

/** {@code DayClosing} in {@code docs/api/billing.yaml} (SAH-10 AC9). */
public record DayClosingView(LocalDate businessDate, long countedCashPaise, long expectedCashPaise, long diffPaise,
                              String note, String closedByName, Instant closedAt) {
}
