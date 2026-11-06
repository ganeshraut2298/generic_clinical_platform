package com.sahaj.ipd.domain;

import java.util.Optional;

/**
 * SAH-31 the reason a doctor gives to order past a warning (mockup SF_OVR), in select order. Demo content from the
 * mockup, not clinically validated (OQ-31-1). The keys are also checked by {@code ck_medicine_order_override_reason}.
 */
public enum OverrideReason {

    BENEFIT_MONITORED("Benefit outweighs the risk, monitoring in place"),
    SPECIALIST_ADVISED("Specialist advised this combination"),
    ALLERGY_HISTORY_CHECKED("Allergy history checked with the patient: mild rash only, no anaphylaxis"),
    CARDIOLOGY_STENT("Recent coronary stent, cardiology advised");

    private final String label;

    OverrideReason(String label) {
        this.label = label;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    public static Optional<OverrideReason> ofKey(String key) {
        for (OverrideReason r : values()) {
            if (r.name().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
