package com.sahaj.billing.web;

import com.sahaj.billing.application.BillView;
import java.util.List;
import java.util.UUID;

/** {@code Bill} in {@code docs/api/billing.yaml} (SAH-10). */
public record BillResponse(UUID visitId, int tokenNo, String patientName, int age, String sex, String doctorName,
                            String visitType, List<BillItemResponse> items, long subtotalPaise, int discountPercent,
                            String discountReason, long discountPaise, long totalPaise, long paidPaise,
                            long balancePaise, List<PaymentResponse> payments, boolean canCollect,
                            int discountLimitPercent) {

    public static BillResponse from(BillView view) {
        return new BillResponse(view.visitId(), view.tokenNo(), view.patientName(), view.age(), view.sex(),
                view.doctorName(), view.visitType(), view.items().stream().map(BillItemResponse::from).toList(),
                view.subtotalPaise(), view.discountPercent(), view.discountReason(), view.discountPaise(),
                view.totalPaise(), view.paidPaise(), view.balancePaise(),
                view.payments().stream().map(PaymentResponse::from).toList(), view.canCollect(),
                view.discountLimitPercent());
    }
}
