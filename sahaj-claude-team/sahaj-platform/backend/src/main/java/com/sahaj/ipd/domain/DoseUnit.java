package com.sahaj.ipd.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * SAH-76-1 (ADR-027 data item 16, OQ-76-8): the unit list of a dose, in select order. Demo content for sign-off, not
 * clinically reviewed (Q3). The keys are also checked by {@code ck_medicine_order_version_dose_unit}.
 */
public enum DoseUnit {

    TABLET("tablet"),
    CAPSULE("capsule"),
    MG("mg"),
    G("g"),
    MCG("mcg"),
    ML("ml"),
    IU("IU"),
    UNIT("units"),
    MMOL("mmol");

    private final String label;

    DoseUnit(String label) {
        this.label = label;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    /** "1 tablet", "650 mg", "0.5 g": the amount without trailing zeros, then the label. */
    public String display(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString() + " " + label;
    }

    /** The unit with this key, or empty for any other text (case matters). */
    public static Optional<DoseUnit> ofKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (DoseUnit u : values()) {
            if (u.name().equals(key)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }
}
