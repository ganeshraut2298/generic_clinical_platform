package com.sahaj.billing.application;

import java.util.List;
import java.util.UUID;

/** {@code Bill} in {@code docs/api/billing.yaml} (SAH-10). */
public record BillView(UUID visitId, int tokenNo, String patientName, int age, String sex, String doctorName,
                        String visitType, List<BillItemView> items, long subtotalPaise, int discountPercent,
                        String discountReason, long discountPaise, long totalPaise, long paidPaise,
                        long balancePaise, List<PaymentView> payments, boolean canCollect, int discountLimitPercent) {
}
