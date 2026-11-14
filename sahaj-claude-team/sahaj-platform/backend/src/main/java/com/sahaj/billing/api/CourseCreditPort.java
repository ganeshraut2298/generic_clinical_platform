package com.sahaj.billing.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SAH-58 (ADR-022 D1, D12): how {@code ipd} carries the credit a treatment course holds for an admission into the
 * admission's deposits (the course "Advance / IPD deposit", DEC-042 H1). Declared here, implemented by
 * {@code billing}. {@code ipd} depends on {@code billing.api}; {@code billing} must never depend on {@code ipd} (a rule
 * checked by {@code ModularityTests}). It runs in the caller's transaction (the clinic is already bound), so the
 * transfer commits or rolls back with the admission.
 */
public interface CourseCreditPort {

    /** The amount moved (paise, whole rupees) and the id of the stored transfer the IPD deposit refers to. */
    record Transfer(UUID transferId, long amountPaise) {
    }

    /**
     * Moves the IPD deposit payments earmarked to {@code sittingId}, capped at the course's current credit and at
     * ₹10,00,000 (the IPD deposit limit), to the admission. One transfer per admission and per sitting. Empty when there
     * is nothing to carry (no earmarked deposit, no credit, or already carried).
     */
    Optional<Transfer> carryToAdmission(UUID tenantId, UUID actorUserId, UUID courseId, UUID sittingId,
                                        UUID admissionId);
}
