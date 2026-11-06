package com.sahaj.ipd.application;

import java.util.UUID;

/**
 * Request body of {@code POST /admissions} (SAH-23; SAH-28 adds the optional emergency case id). Boxed fields so that a missing one is a 400. Carries the
 * patient and the clinical reason: {@code toString} shows nothing.
 */
public record AdmissionInput(UUID patientId, UUID doctorMembershipId, String admissionType, UUID bedId,
                             String reason, String payer, Long depositPaise, String depositMode,
                             Boolean consentSigned, UUID emergencyCaseId) {

    /** An admission from the front desk: no emergency case (SAH-28 adds the last field). */
    public AdmissionInput(UUID patientId, UUID doctorMembershipId, String admissionType, UUID bedId, String reason,
                          String payer, Long depositPaise, String depositMode, Boolean consentSigned) {
        this(patientId, doctorMembershipId, admissionType, bedId, reason, payer, depositPaise, depositMode,
                consentSigned, null);
    }

    @Override
    public String toString() {
        return "AdmissionInput[redacted]";
    }
}
