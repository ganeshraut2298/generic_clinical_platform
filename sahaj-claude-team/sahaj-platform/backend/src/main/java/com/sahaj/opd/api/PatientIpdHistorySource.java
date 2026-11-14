package com.sahaj.opd.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-50 (ADR-022 D1, D10): how the patient history of {@code opd} lists a patient's IPD admissions without a module
 * cycle ({@code ipd} depends on this package, so {@code opd} must not depend on {@code ipd.api}). Declared here,
 * implemented by {@code ipd}. It runs in the caller's transaction (the clinic is already bound) and acts for the
 * signed-in user: the implementation applies the IPD patient scope exactly as an IPD read does (ADR-017), marks each
 * admission ALLOWED, OUTSIDE_SCOPE or FORBIDDEN (a visiting doctor), and writes no break-glass of its own.
 *
 * <p>Another clinic's patient or admission is simply absent. {@link AdmissionHistory#toString()} shows ids only; the
 * reason for admission is clinical text and no caller may log it.
 */
public interface PatientIpdHistorySource {

    enum Access { ALLOWED, OUTSIDE_SCOPE, FORBIDDEN }

    /** {@code status} is ADMITTED or DISCHARGED; {@code ward} and {@code bedCode} are the current or last bed. */
    record AdmissionHistory(UUID admissionId, LocalDate admittedOn, LocalDate dischargedOn, String status,
                            String reason, boolean dayCare, UUID doctorMembershipId, String ward, String bedCode,
                            Access access) {

        @Override
        public String toString() {
            return "AdmissionHistory[admissionId=" + admissionId + ", access=" + access + "]";
        }
    }

    /** The patient's admissions, newest first, at most {@code limit}. */
    List<AdmissionHistory> admissionsOf(UUID tenantId, UUID patientId, int limit);

    Optional<AdmissionHistory> admission(UUID tenantId, UUID patientId, UUID admissionId);
}
