package com.sahaj.ipd.domain;

import java.util.Optional;

/**
 * SAH-76-5 (ADR-027 D3): why a given dose is marked "recorded in error", in chip order. The keys are also checked by
 * {@code ck_dose_correction_reason}.
 */
public enum InErrorReason {
    NOT_GIVEN_ACTUALLY("Dose was not actually given"),
    WRONG_PATIENT("Documented on the wrong patient"),
    WRONG_TIME("Wrong time recorded"),
    DUPLICATE("Duplicate entry");

    private final String label;

    InErrorReason(String label) {
        this.label = label;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    public static Optional<InErrorReason> ofKey(String key) {
        for (InErrorReason r : values()) {
            if (r.name().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
