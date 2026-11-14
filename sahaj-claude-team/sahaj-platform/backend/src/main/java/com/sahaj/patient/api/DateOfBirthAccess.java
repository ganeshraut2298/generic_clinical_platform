package com.sahaj.patient.api;

import java.util.Set;

/**
 * SAH-73 (ADR-024 D3, BR73-12): who may receive a patient's date of birth. A fixed allow-list of system roles in
 * code (like the capabilities of ADR-019 D8), not a cell of the "Who can do what" grid. BILLING, PHARMACIST,
 * COUNSELLOR, any custom role and a missing role are outside it (default deny): the date is then absent from the
 * answer. {@code patient} uses it for its own answers and {@code opd} for the Paediatrics view, so there is one list.
 */
public final class DateOfBirthAccess {

    private static final Set<String> ROLES = Set.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK");

    private DateOfBirthAccess() {
    }

    /** True when the membership role key may see a patient's date of birth. */
    public static boolean mayView(String role) {
        return role != null && ROLES.contains(role);
    }
}
