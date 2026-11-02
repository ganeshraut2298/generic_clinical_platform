package com.sahaj.billing.application;

import java.time.Instant;
import java.util.UUID;

/** {@code Payment} in {@code docs/api/billing.yaml} (SAH-10). {@code amountPaise} is negative for a refund. */
public record PaymentView(UUID id, String mode, long amountPaise, String reference, String recordedByName,
                           Instant recordedAt) {
}
