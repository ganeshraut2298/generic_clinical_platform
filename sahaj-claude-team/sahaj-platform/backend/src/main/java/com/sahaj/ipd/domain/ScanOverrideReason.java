package com.sahaj.ipd.domain;

import java.util.List;
import java.util.Optional;

/**
 * SAH-76-4 (ADR-027 D7, OQ-76-17): why a nurse cannot scan and uses the manual override, in select order. The keys are also
 * checked by {@code ck_dose_administration_override_reason}.
 */
public enum ScanOverrideReason {
    WRISTBAND_DAMAGED("Wristband damaged or missing"),
    NO_BARCODE("Medicine has no barcode"),
    SCANNER_DOWN("Scanner not working"),
    EMERGENCY("Emergency");

    private final String label;

    ScanOverrideReason(String label) {
        this.label = label;
    }

    public String key() {
        return name();
    }

    public String label() {
        return label;
    }

    /**
     * The reasons accepted on input from contract 0.4.3 (ADR-027 A9) and the list of {@code Mar.overrideReasons}.
     * {@code WRISTBAND_DAMAGED} is not one of them (the wristband is always required) but stays readable on doses
     * recorded before.
     */
    public static List<ScanOverrideReason> inputReasons() {
        return List.of(NO_BARCODE, SCANNER_DOWN, EMERGENCY);
    }

    /** The reason of this key when accepted on input; empty for {@code WRISTBAND_DAMAGED}, an unknown key or null. */
    public static Optional<ScanOverrideReason> ofInputKey(String key) {
        return ofKey(key).filter(r -> inputReasons().contains(r));
    }

    public static Optional<ScanOverrideReason> ofKey(String key) {
        for (ScanOverrideReason r : values()) {
            if (r.name().equals(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
