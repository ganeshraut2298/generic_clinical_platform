package com.sahaj.ipd.domain;

import java.util.Optional;

/**
 * SAH-32 why a dose is not given (mockup sfGive select {@code sf-why}), in select order. {@code PATIENT_REFUSED} is
 * stored as outcome REFUSED, every other reason as HELD (AC8). The keys are also checked by
 * {@code ck_dose_administration_reason}.
 */
public enum NotGivenReason {

    PATIENT_REFUSED("Patient refused", DoseOutcome.REFUSED),
    HELD_DOCTOR("Held on doctor's advice", DoseOutcome.HELD),
    NIL_BY_MOUTH("Nil by mouth", DoseOutcome.HELD),
    NOT_AVAILABLE("Medicine not available", DoseOutcome.HELD),
    AWAY_FROM_WARD("Patient away from the ward", DoseOutcome.HELD);

    private final String label;
    private final DoseOutcome outcome;

    NotGivenReason(String label, DoseOutcome outcome) {
        this.label = label;
        this.outcome = outcome;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    /** The outcome stored for this reason: REFUSED for the patient's refusal, otherwise HELD. */
    public DoseOutcome outcome() {
        return outcome;
    }

    public static Optional<NotGivenReason> ofKey(String key) {
        for (NotGivenReason r : values()) {
            if (r.name().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
