package com.sahaj.ipd.domain;

import java.util.Optional;

/**
 * SAH-31 why a pharmacist rejects an order (mockup sfrej), in select order; the first is preselected. The keys are
 * also checked by {@code ck_medicine_order_reject_reason}.
 */
public enum RejectReason {

    ALLERGY_CONFLICT("Allergy conflict"),
    INTERACTION_RISK("Interaction risk too high"),
    WRONG_DOSE("Wrong dose or frequency"),
    DUPLICATE_THERAPY("Duplicate therapy");

    private final String label;

    RejectReason(String label) {
        this.label = label;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    public static Optional<RejectReason> ofKey(String key) {
        for (RejectReason r : values()) {
            if (r.name().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
