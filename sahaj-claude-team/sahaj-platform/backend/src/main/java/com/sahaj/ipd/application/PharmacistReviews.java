package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import java.util.UUID;

/**
 * SAH-76 (ADR-027 D6): the review-after-dose port. Called by the dose and correction transactions (WP3); implemented
 * by WP1. Both calls run inside the caller's transaction and under the admission row lock.
 */
public interface PharmacistReviews {

    /** A GIVEN dose with {@code before_verification}: opens one review item for the dose (idempotent per dose). */
    void openForDose(UUID tenantId, UUID admissionId, OrderRef order, UUID doseId);

    /** Closes the dose's open review item with the outcome (DOSE_UNDONE when the dose is undone); no item = nothing. */
    void closeForDose(UUID tenantId, UUID doseId, ReviewOutcome outcome, UUID actor);
}
