package com.sahaj.opd.application.emr.finish;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The phase 5 columns and tables of the Doctor EMR (ADR-025 D1 to D4, D10): the sign state and edit hold of
 * {@code opd.emr_record}, the append-only {@code opd.emr_record_addendum} and {@code opd.emr_admission_request}. Clinical
 * data: never deleted, never logged. Row level security limits every statement to the bound clinic and the tenant id is in
 * each statement. A signed record is also frozen by a database trigger; these updates never touch a signed row except
 * the ER patient link.
 */
public interface EmrFinishRepository {

    /**
     * The sign state and the hold of one record, with the database clock now. {@code holdActive} is true while the hold is
     * not expired. {@code signResult} is the stored outcome JSON (ids and counts only).
     */
    record RecordState(UUID recordId, String status, UUID signedByMembershipId, UUID signedByUserId, String signKey,
                       String signHash, String signResult, UUID holdMembershipId, UUID holdUserId,
                       Instant holdExpiresAt, boolean holdActive, String factsFingerprint) {

        @Override
        public String toString() {
            return "RecordState[recordId=" + recordId + ", status=" + status + "]";
        }
    }

    Optional<RecordState> state(UUID tenantId, UUID recordId);

    /** The same read with the record row locked ({@code for update}): the edit-hold take reads the previous holder under it. */
    Optional<RecordState> stateForUpdate(UUID tenantId, UUID recordId);

    /**
     * Takes or renews the hold until now + {@code seconds} (database clock) when the hold is free, expired, the caller's, or
     * {@code takeOver} is true. Only for a DRAFT record. False when another doctor's hold is active (nothing changed).
     */
    boolean takeHold(UUID tenantId, UUID recordId, UUID membershipId, UUID userId, int seconds, boolean takeOver);

    /** Releases the hold when it is the caller's. */
    void releaseHold(UUID tenantId, UUID recordId, UUID membershipId);

    /** Stores the patient facts fingerprint read at open (only while none is stored). */
    void setFingerprint(UUID tenantId, UUID recordId, String fingerprint);

    /**
     * DRAFT to SIGNED in one update: signer, signature time (the transaction time), idempotency key, request hash and the
     * stored outcome; the hold is cleared and the revision moves by one. False when the record was not a draft.
     */
    boolean markSigned(UUID tenantId, UUID recordId, UUID userId, UUID membershipId, String key, String hash,
                       String resultJson);

    /** Sets "admission advised" without a revision change (the request of the Admit to IPD sheet); only for a DRAFT. */
    void markAdvised(UUID tenantId, UUID recordId, UUID actorUserId);

    /** Sets the patient of an Emergency record (before it is signed, or once after it, when the case is admitted). */
    boolean linkPatient(UUID tenantId, UUID recordId, UUID patientId);

    // ---- addenda ----

    record AddendumRow(UUID id, UUID recordId, String reason, String text, Instant createdAt,
                       UUID createdByMembershipId, String idempotencyKey, String requestHash) {

        @Override
        public String toString() {
            return "AddendumRow[id=" + id + "]";
        }
    }

    /** Oldest first (newest last on screen). */
    List<AddendumRow> addenda(UUID tenantId, UUID recordId);

    int countAddenda(UUID tenantId, UUID recordId);

    Optional<AddendumRow> addendumByKey(UUID tenantId, String idempotencyKey);

    AddendumRow insertAddendum(UUID tenantId, UUID recordId, String reason, String text, UUID membershipId, UUID userId,
                               String idempotencyKey, String requestHash);

    // ---- admission requests ----

    record RequestRow(UUID id, UUID recordId, String encounterType, UUID opdVisitId, UUID erCaseId, UUID patientId,
                      UUID requestedByMembershipId, String admissionType, Integer plannedStayDays, String reasonText,
                      String procedureText, String note, String status, UUID admissionId, Instant createdAt,
                      String idempotencyKey, String requestHash) {

        @Override
        public String toString() {
            return "RequestRow[id=" + id + ", status=" + status + "]";
        }
    }

    record NewRequest(UUID recordId, Instant recordCreatedAt, String encounterType, UUID opdVisitId, UUID erCaseId,
                      UUID patientId, UUID requestedByMembershipId, String admissionType, Integer plannedStayDays,
                      String reasonText, String procedureText, String note, String idempotencyKey,
                      String requestHash) {
    }

    Optional<RequestRow> openRequestOfRecord(UUID tenantId, UUID recordId);

    /** The newest request of the record in any state (OPEN or ADMITTED), for the button of the Admit to IPD sheet. */
    Optional<RequestRow> latestRequestOfRecord(UUID tenantId, UUID recordId);

    Optional<RequestRow> requestByKey(UUID tenantId, String idempotencyKey);

    Optional<RequestRow> requestById(UUID tenantId, UUID requestId);

    /** The OPEN request locked for the caller's transaction. */
    Optional<RequestRow> lockOpenRequest(UUID tenantId, UUID requestId);

    RequestRow insertRequest(UUID tenantId, NewRequest request, UUID actorUserId);

    /** Newest first, at most 200. */
    List<RequestRow> listOpenRequests(UUID tenantId);

    /** The OPEN requests of this patient or case (either argument may be null). */
    List<RequestRow> openRequestsOf(UUID tenantId, UUID patientId, UUID erCaseId);

    void markRequestAdmitted(UUID tenantId, UUID requestId, UUID admissionId, UUID actorUserId);

    /** The creation time of a record row (the composite key of the partitioned table). */
    Optional<Instant> recordCreatedAt(UUID tenantId, UUID recordId);

    /** The Emergency record of the case, if it has one. */
    Optional<UUID> erRecordOfCase(UUID tenantId, UUID caseId);
}
