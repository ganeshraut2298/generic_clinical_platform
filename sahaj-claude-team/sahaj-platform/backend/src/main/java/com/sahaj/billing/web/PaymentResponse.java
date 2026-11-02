package com.sahaj.billing.web;

import com.sahaj.billing.application.PaymentView;
import java.time.Instant;
import java.util.UUID;

/** {@code Payment} in {@code docs/api/billing.yaml} (SAH-10). */
public record PaymentResponse(UUID id, String mode, long amountPaise, String reference, String recordedByName,
                               Instant recordedAt) {

    public static PaymentResponse from(PaymentView view) {
        return new PaymentResponse(view.id(), view.mode(), view.amountPaise(), view.reference(),
                view.recordedByName(), view.recordedAt());
    }
}
