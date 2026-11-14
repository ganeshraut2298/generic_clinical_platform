package com.sahaj.opd.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-67-3 (ADR-025 D10): the doctor's admission requests from the EMR, read and closed by {@code ipd} (which already
 * depends on this package) without {@code opd} depending on {@code ipd}. Declared here, implemented by {@code opd}. Runs in
 * the caller's transaction. Lock order is ipd's (IPD lock, then these rows).
 *
 * <p>The diagnoses text and the note are clinical: on screen only, never logged ({@code toString} shows ids).
 */
public interface EmrAdmissionRequestPort {

    record OpenRequest(UUID requestId, UUID recordId, UUID patientId, UUID emergencyCaseId, String admissionType,
                       Integer plannedStayDays, UUID requestedByMembershipId, String diagnoses, String procedure,
                       String note, Instant requestedAt) {

        @Override
        public String toString() {
            return "OpenRequest[requestId=" + requestId + "]";
        }
    }

    record AdmittedFacts(UUID admissionId, UUID patientId, UUID emergencyCaseId, UUID admissionRequestId) {
    }

    /** Open requests, newest first, at most 200. */
    List<OpenRequest> listOpen(UUID tenantId);

    /** The request row locked for the caller's transaction, when it is OPEN. */
    Optional<OpenRequest> lockOpen(UUID tenantId, UUID requestId);

    /**
     * Marks the named request and every other OPEN request of the patient or the case ADMITTED with the admission, and
     * sets the patient on the case's ER record when it has none.
     */
    void onAdmitted(UUID tenantId, UUID actorUserId, AdmittedFacts facts);
}
