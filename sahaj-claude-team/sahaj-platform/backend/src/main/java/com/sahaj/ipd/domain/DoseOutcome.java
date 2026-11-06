package com.sahaj.ipd.domain;

import java.util.Optional;

/** SAH-32 the stored outcome of a dose record (ADR-011 D1; {@code ck_dose_administration_outcome}). */
public enum DoseOutcome {
    GIVEN, HELD, REFUSED;

    public static Optional<DoseOutcome> ofKey(String key) {
        for (DoseOutcome o : values()) {
            if (o.name().equals(key)) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }
}
