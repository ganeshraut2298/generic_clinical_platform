package com.sahaj.opd.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-46 and SAH-52 (ADR-020 D9): how the Doctor EMR of {@code opd} reads an IPD admission or an Emergency case without
 * a module cycle ({@code ipd} already depends on this package, so {@code opd} must not depend on {@code ipd.api}).
 * Declared here, implemented by {@code ipd}. It runs in the caller's transaction (the clinic is already bound) and acts
 * for the signed-in user: the implementation applies the IPD patient scope exactly as an IPD read by id does
 * (ADR-017 D1, D2), including a live break-glass grant, which {@code ipd} audits as it does today.
 *
 * <p>An id that is not the bound clinic's is simply absent (row level security). The facts hold ids and fixed words
 * only, except {@link EmergencyCaseFacts#displayName()} which is personal data: its {@code toString} shows ids only and
 * no caller may log it.
 */
public interface EmrEncounterSource {

    /** ALLOWED, OUTSIDE_SCOPE (ward scope or no grant) or FORBIDDEN (a role that has no IPD access). */
    enum Access { ALLOWED, OUTSIDE_SCOPE, FORBIDDEN }

    /** {@code status} is ADMITTED or DISCHARGED; {@code dayNumber} is the billed bed day shown as "IPD day n". */
    record AdmissionFacts(UUID admissionId, UUID patientId, String ipNumber, String status, int dayNumber,
                          UUID admittingDoctorMembershipId, Access access) {

        @Override
        public String toString() {
            return "AdmissionFacts[admissionId=" + admissionId + ", status=" + status + "]";
        }
    }

    /**
     * {@code patientId} is null until the case is admitted (ADR-005). {@code status} is WAITING, SEEN, ADMITTED or
     * CLOSED. {@code displayName}, {@code age} and {@code sex} are the case's own (the header of an unadmitted case).
     */
    record EmergencyCaseFacts(UUID caseId, UUID patientId, String status, int triageLevel, String triageLevelName,
                              String displayName, Integer age, String sex, Access access, Instant arrivedAt) {

        /** The SAH-52 shape: no arrival time (null). */
        public EmergencyCaseFacts(UUID caseId, UUID patientId, String status, int triageLevel, String triageLevelName,
                                  String displayName, Integer age, String sex, Access access) {
            this(caseId, patientId, status, triageLevel, triageLevelName, displayName, age, sex, access, null);
        }

        @Override
        public String toString() {
            return "EmergencyCaseFacts[caseId=" + caseId + ", status=" + status + "]";
        }
    }

    /** The patient's open (ADMITTED) admission: "Already admitted · IP number" (ADR-025 D10). */
    record AdmissionRef(UUID admissionId, String ipNumber) {
    }

    Optional<AdmissionFacts> admission(UUID tenantId, UUID admissionId);

    /** SAH-67-3 (ADR-025 D10): the patient's open admission; no scope check (the id and the IP number only). */
    Optional<AdmissionRef> openAdmissionOf(UUID tenantId, UUID patientId);

    Optional<EmergencyCaseFacts> emergencyCase(UUID tenantId, UUID caseId);
}
