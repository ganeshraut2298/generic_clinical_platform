package com.sahaj.patient.web;

import com.sahaj.patient.api.DateOfBirthAccess;

/**
 * Role expressions for the patient endpoints (SAH-8 AC8, DEC-049 answer 2, patient.yaml 0.2.1): OWNER, FRONT_DESK, NURSE,
 * DOCTOR, VISITING_DOCTOR, BILLING and PHARMACIST read (BILLING and PHARMACIST need the name on bills and dispensing);
 * COUNSELLOR does not (403). Only OWNER and FRONT_DESK write. Reading is not the same as seeing the date of birth:
 * {@link DateOfBirthAccess} stays the separate allow-list, so BILLING and PHARMACIST get {@code age} and
 * {@code ageDetail} only.
 */
final class PatientRoles {

    static final String READ =
            "hasAnyRole('OWNER','FRONT_DESK','NURSE','DOCTOR','VISITING_DOCTOR','BILLING','PHARMACIST')";
    static final String WRITE = "hasAnyRole('OWNER','FRONT_DESK')";

    private PatientRoles() {
    }

    /** True when the caller's role may receive {@code dateOfBirth} on a patient answer. */
    static boolean mayViewDateOfBirth(String role) {
        return DateOfBirthAccess.mayView(role);
    }
}
