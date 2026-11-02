package com.sahaj.platform.api;

import java.util.UUID;

/**
 * What the setup overview (SAH-7 AC6, AC12) needs to know about the clinic's OPD masters, without
 * reading {@code opd}'s tables. Implemented by {@code opd}; runs in the caller's transaction.
 */
public interface OpdSetupStatus {

    /** The clinic's own answers, as plain facts. */
    record Status(boolean hasActiveService, boolean hasFeeSettings, boolean hasCasePaperSettings) {
    }

    Status statusFor(UUID tenantId);
}
