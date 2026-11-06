package com.sahaj.platform.application;

import com.sahaj.platform.application.ModuleEntitlementRepository.EntitlementRow;
import java.time.Instant;

/** Defaults and the read-time rule for an expired trial (SAH-19). Nothing is stored for expiry. */
final class ModuleEntitlements {

    static final EntitlementRow DEFAULTS = new EntitlementRow("off", null, false, 25, null);

    private ModuleEntitlements() {
    }

    /** A trial whose end date has passed reads as {@code readonly}. */
    static String effectiveIpdState(EntitlementRow row, Instant now) {
        if ("trial".equals(row.ipdState()) && row.ipdTrialEndsAt() != null && !row.ipdTrialEndsAt().isAfter(now)) {
            return "readonly";
        }
        return row.ipdState();
    }
}
