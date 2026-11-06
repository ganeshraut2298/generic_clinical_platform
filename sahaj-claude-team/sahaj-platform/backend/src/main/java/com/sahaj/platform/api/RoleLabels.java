package com.sahaj.platform.api;

import java.util.Map;

/** The plain-English label of a membership role name (for example {@code FRONT_DESK} is "Front desk"), owned by {@code platform}. */
public final class RoleLabels {

    private static final Map<String, String> LABELS = Map.of(
            "OWNER", "Owner",
            "DOCTOR", "Doctor",
            "VISITING_DOCTOR", "Visiting doctor",
            "NURSE", "Nurse",
            "FRONT_DESK", "Front desk",
            "BILLING", "Billing",
            "PHARMACIST", "Pharmacist",
            "COUNSELLOR", "Counsellor");

    private RoleLabels() {
    }

    /** The label, or the role name itself when it is not a known role. */
    public static String of(String role) {
        return LABELS.getOrDefault(role, role);
    }
}
