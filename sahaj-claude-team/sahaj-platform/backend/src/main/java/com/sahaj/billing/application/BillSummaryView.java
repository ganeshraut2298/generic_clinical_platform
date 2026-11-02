package com.sahaj.billing.application;

import java.util.UUID;

/** {@code BillSummary} in {@code docs/api/billing.yaml} (SAH-10): a counter list row. */
public record BillSummaryView(UUID visitId, int tokenNo, String patientName, String doctorName, long totalPaise,
                               long balancePaise) {
}
