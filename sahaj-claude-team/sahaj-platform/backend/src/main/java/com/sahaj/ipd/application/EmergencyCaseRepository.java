package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.emergency_case} (SAH-28). Every method is scoped to the tenant. The database sets the id, the clinic,
 * the status (always WAITING at insert), the arrival time (clock_timestamp()) and the version; the application never
 * sends them. The case holds the name, age, mobile and complaint as typed at the desk: they are shown on screen only,
 * so every {@code toString} here leaves them out. The mobile number is selected only by {@link #findForHandoff} and is
 * cleared in the same update that closes the case (security review M1(a)).
 */
public interface EmergencyCaseRepository {

    /** The case registered with this client key in the clinic (any status), if there is one. No mobile number. */
    Optional<CaseRow> findByClientKey(UUID tenantId, UUID clientKey);

    /**
     * Inserts a WAITING case and returns its id and the arrival time the database stamped (clock_timestamp(), read
     * when the row is written, which is after the clinic lock; ADR-005 D6).
     */
    Created insert(UUID tenantId, NewCase newCase);

    /** The database clock now ({@code clock_timestamp()}): the one source of every emergency time (ADR-005 D6). */
    Instant databaseNow();

    /** The case without its mobile number (a plain read for the answers of the actions). */
    Optional<CaseRow> find(UUID tenantId, UUID caseId);

    /** The case with its mobile number: only for the Admit hand-off, which shows it once (security review M1). */
    Optional<CaseRow> findForHandoff(UUID tenantId, UUID caseId);

    /** The case row locked ({@code select ... for update}) until the end of the transaction; no mobile number. */
    Optional<CaseRow> findForUpdate(UUID tenantId, UUID caseId);

    /** Cases with status WAITING or SEEN, in no particular order; no mobile number (the queue never shows it). */
    List<CaseRow> findOpen(UUID tenantId);

    /** WAITING to SEEN with the time and the user, one update; bumps the version. False when it was not WAITING. */
    boolean markSeen(UUID tenantId, UUID caseId, Instant at, UUID by);

    /** Changes the level of an open case; bumps the version. False when the case is not open. */
    boolean updateLevel(UUID tenantId, UUID caseId, int level);

    /**
     * WAITING or SEEN to RELEASED or REFERRED, one update that also clears the mobile number (security review M1(a));
     * bumps the version. False when the case was not open.
     */
    boolean close(UUID tenantId, UUID caseId, String status, Instant at, UUID by);

    /**
     * WAITING or SEEN to ADMITTED, one update that sets the status, the closure and the admission and patient links
     * together (the table checks need them together) and clears the mobile number in the same update; bumps the
     * version. False when the case was not open. This is the only update that sets {@code patient_id}.
     */
    boolean markAdmitted(UUID tenantId, UUID caseId, UUID admissionId, UUID patientId, Instant at, UUID by);

    /**
     * SAH-67-2 (ADR-025 D9): WAITING or SEEN to SENT_TO_OPD with the patient and the OPD visit it was sent to, the closure
     * and the cleared mobile number in one update; bumps the version. False when the case was not open.
     */
    boolean closeSentToOpd(UUID tenantId, UUID caseId, UUID patientId, UUID opdVisitId, Instant at, UUID by);

    /** What an insert gives back: the new id and the arrival time of the database clock. */
    record Created(UUID id, Instant arrivedAt) {
    }

    /** What a registration inserts. */
    record NewCase(UUID clientKey, String displayName, Integer age, String sex, String mobile, String complaint,
                   boolean identityPending, int level, String bay, UUID registeredBy) {

        @Override
        public String toString() {
            return "NewCase[level=" + level + ", bay=" + bay + "]";
        }
    }

    /**
     * One stored case. Personal and clinical text: {@code toString} shows the id and status only. {@code mobile} is
     * null except in the answer of {@link #findForHandoff}.
     */
    record CaseRow(UUID id, UUID patientId, String displayName, Integer age, String sex, String mobile,
                   String complaint, boolean identityPending, int level, String bay, String status, Instant arrivedAt,
                   UUID registeredBy, Instant seenAt, UUID seenBy, Instant closedAt, UUID closedBy, UUID admissionId,
                   UUID clientKey, int version) {

        /** WAITING or SEEN: the case is in the queue. */
        public boolean open() {
            return "WAITING".equals(status) || "SEEN".equals(status);
        }

        @Override
        public String toString() {
            return "CaseRow[id=" + id + ", status=" + status + "]";
        }
    }
}
