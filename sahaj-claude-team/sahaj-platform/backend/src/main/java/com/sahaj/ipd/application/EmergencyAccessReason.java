package com.sahaj.ipd.application;

import java.util.Optional;

/**
 * SAH-34 (D-3a, OQ-34-12): the three fixed reasons of an emergency access, in the order of the select "Reason" (mockup
 * {@code sfbg}). The keys are held by a database check; the labels live here and are the same text in the audit-trail
 * entry and in the owner's panel. There is no free text.
 */
enum EmergencyAccessReason {
    DETERIORATING("Patient deteriorating, rapid response"),
    COVERING_BREAK("Covering for a colleague on break"),
    DUTY_DOCTOR_ASKED("Asked to help by the duty doctor");

    private final String label;

    EmergencyAccessReason(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** The reason of a stored or sent key; empty for anything else (the key is matched exactly). */
    static Optional<EmergencyAccessReason> ofKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (EmergencyAccessReason reason : values()) {
            if (reason.name().equals(key)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }

    /** The label of a stored key; a key outside the list (impossible with the database check) is shown as it is. */
    static String labelOf(String key) {
        return ofKey(key).map(EmergencyAccessReason::label).orElse(key);
    }
}
