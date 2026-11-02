package com.sahaj.billing.web;

import com.sahaj.billing.application.BillSummaryView;
import java.util.UUID;

/** {@code BillSummary} in {@code docs/api/billing.yaml} (SAH-10). */
public record BillSummaryResponse(UUID visitId, int tokenNo, String patientName, String doctorName,
                                   long totalPaise, long balancePaise) {

    public static BillSummaryResponse from(BillSummaryView view) {
        return new BillSummaryResponse(view.visitId(), view.tokenNo(), view.patientName(), view.doctorName(),
                view.totalPaise(), view.balancePaise());
    }
}
