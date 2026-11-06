package com.sahaj.ipd.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * SAH-33 what was done about an early warning escalation (ADR-007 D1). The first five are the doctor's choices, in
 * the order of the mockup's select "What was done"; {@link #CLOSED_AT_DISCHARGE} is written by the discharge only and
 * is never accepted from a request. No Spring and no I/O.
 */
public enum ResponseAction {
    BEDSIDE_REVIEW("Reviewed at the bedside"),
    TREATMENT_CHANGED("Treatment changed"),
    MOVED_ICU_HDU("Moved to ICU or HDU"),
    RAPID_RESPONSE("Rapid response team called"),
    NO_CHANGE("Reviewed, no change needed"),
    CLOSED_AT_DISCHARGE("Closed at discharge");

    private final String label;

    ResponseAction(String label) {
        this.label = label;
    }

    /** The words shown to the user and written to the clinical event log. */
    public String label() {
        return label;
    }

    /** True for the five actions a doctor may choose. */
    public boolean doctorChoice() {
        return this != CLOSED_AT_DISCHARGE;
    }

    /** The five doctor actions, in select order. */
    public static List<ResponseAction> doctorChoices() {
        return Arrays.stream(values()).filter(ResponseAction::doctorChoice).toList();
    }

    /** The doctor action with this code; empty for null, an unknown code or CLOSED_AT_DISCHARGE. */
    public static Optional<ResponseAction> doctorChoice(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return doctorChoices().stream().filter(a -> a.name().equals(code)).findFirst();
    }

    /** Any action by code, including CLOSED_AT_DISCHARGE (a stored value); empty for null or unknown. */
    public static Optional<ResponseAction> ofStored(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(a -> a.name().equals(code)).findFirst();
    }
}
