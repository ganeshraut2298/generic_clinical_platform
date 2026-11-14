package com.sahaj.ipd.application;

import java.util.UUID;

/**
 * Request body of {@code POST /admissions} (SAH-23; SAH-28 adds the optional emergency case id; SAH-58 adds the optional
 * treatment course sitting this admission does). Boxed fields so that a missing one is a 400. Carries the
 * patient and the clinical reason: {@code toString} shows nothing.
 */
public record AdmissionInput(UUID patientId, UUID doctorMembershipId, String admissionType, UUID bedId,
                             String reason, String payer, Long depositPaise, String depositMode,
                             Boolean consentSigned, UUID emergencyCaseId, UUID treatmentCourseSittingId,
                             UUID admissionRequestId) {

    /** An admission with a course sitting but no EMR admission request (SAH-58). */
    public AdmissionInput(UUID patientId, UUID doctorMembershipId, String admissionType, UUID bedId, String reason,
                          String payer, Long depositPaise, String depositMode, Boolean consentSigned,
                          UUID emergencyCaseId, UUID treatmentCourseSittingId) {
        this(patientId, doctorMembershipId, admissionType, bedId, reason, payer, depositPaise, depositMode,
                consentSigned, emergencyCaseId, treatmentCourseSittingId, null);
    }

    /** An admission with an emergency case but no course sitting (SAH-28). */
    public AdmissionInput(UUID patientId, UUID doctorMembershipId, String admissionType, UUID bedId, String reason,
                          String payer, Long depositPaise, String depositMode, Boolean consentSigned,
                          UUID emergencyCaseId) {
        this(patientId, doctorMembershipId, admissionType, bedId, reason, payer, depositPaise, depositMode,
                consentSigned, emergencyCaseId, null, null);
    }

    /** An admission from the front desk: no emergency case and no course sitting. */
    public AdmissionInput(UUID patientId, UUID doctorMembershipId, String admissionType, UUID bedId, String reason,
                          String payer, Long depositPaise, String depositMode, Boolean consentSigned) {
        this(patientId, doctorMembershipId, admissionType, bedId, reason, payer, depositPaise, depositMode,
                consentSigned, null, null, null);
    }

    @Override
    public String toString() {
        return "AdmissionInput[redacted]";
    }
}
