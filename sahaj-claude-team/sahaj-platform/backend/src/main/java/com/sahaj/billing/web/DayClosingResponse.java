package com.sahaj.billing.web;

import com.sahaj.billing.application.DayClosingView;
import java.time.Instant;
import java.time.LocalDate;

/** {@code DayClosing} in {@code docs/api/billing.yaml} (SAH-10). */
public record DayClosingResponse(LocalDate businessDate, long countedCashPaise, long expectedCashPaise,
                                  long diffPaise, String note, String closedByName, Instant closedAt) {

    public static DayClosingResponse from(DayClosingView view) {
        return new DayClosingResponse(view.businessDate(), view.countedCashPaise(), view.expectedCashPaise(),
                view.diffPaise(), view.note(), view.closedByName(), view.closedAt());
    }
}
