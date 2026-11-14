package com.sahaj.opd.application.course;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code opd.treatment_course}, {@code opd.treatment_course_sitting} and the course counter (SAH-58; ADR-022
 * D12). Clinical plan data: never deleted, items and notes never logged. Row level security limits every statement to
 * the bound clinic and the tenant id is also in each statement. A sitting number is never reused.
 */
public interface TreatmentCourseRepository {

    /** {@code toString} shows ids only. {@code estimateSigned} is 0.7.0 (SAH-75-3, ADR-026 D3). */
    record CourseRow(UUID id, int courseNo, UUID patientId, String kind, String unit, String name, String note,
                     UUID doctorMembershipId, LocalDate startedOn, String status, long revision, String requestHash,
                     Instant createdAt, UUID originRecordId, boolean estimateSigned) {

        /** Without the signed-estimate flag (the 0.6.0 shape). */
        public CourseRow(UUID id, int courseNo, UUID patientId, String kind, String unit, String name, String note,
                         UUID doctorMembershipId, LocalDate startedOn, String status, long revision,
                         String requestHash, Instant createdAt, UUID originRecordId) {
            this(id, courseNo, patientId, kind, unit, name, note, doctorMembershipId, startedOn, status, revision,
                    requestHash, createdAt, originRecordId, false);
        }

        /** Without the record the course started from (the 0.4.0 shape). */
        public CourseRow(UUID id, int courseNo, UUID patientId, String kind, String unit, String name, String note,
                         UUID doctorMembershipId, LocalDate startedOn, String status, long revision,
                         String requestHash, Instant createdAt) {
            this(id, courseNo, patientId, kind, unit, name, note, doctorMembershipId, startedOn, status, revision,
                    requestHash, createdAt, null, false);
        }

        @Override
        public String toString() {
            return "CourseRow[id=" + id + ", courseNo=" + courseNo + "]";
        }
    }

    /**
     * {@code items} and {@code note} are clinical plan text: {@code toString} shows ids only. 0.7.0 (SAH-75-7, SAH-75-10,
     * ADR-026 D4, D7): {@code missedAt}, {@code removedAt}, {@code doneSource} (MARK_DONE or SIGN, null on rows from
     * before) and {@code regimenKey} (CHEMO courses only).
     */
    record SittingRow(UUID id, UUID courseId, int sittingNo, String encounterKind, String addOptionKey,
                      LocalDate plannedDate, List<String> items, long chargePaise, String note, UUID opdVisitId,
                      UUID admissionId, boolean admissionRequested, Instant doneAt, String requestHash,
                      Instant missedAt, Instant removedAt, String doneSource, String regimenKey) {

        /** The 0.6.0 shape: no missed, removed, done source or regimen. */
        public SittingRow(UUID id, UUID courseId, int sittingNo, String encounterKind, String addOptionKey,
                          LocalDate plannedDate, List<String> items, long chargePaise, String note, UUID opdVisitId,
                          UUID admissionId, boolean admissionRequested, Instant doneAt, String requestHash) {
            this(id, courseId, sittingNo, encounterKind, addOptionKey, plannedDate, items, chargePaise, note, opdVisitId,
                    admissionId, admissionRequested, doneAt, requestHash, null, null, null, null);
        }

        public boolean removed() {
            return removedAt != null;
        }

        @Override
        public String toString() {
            return "SittingRow[id=" + id + ", sittingNo=" + sittingNo + "]";
        }
    }

    record NewCourse(int courseNo, UUID patientId, String kind, String unit, String name, String note,
                     UUID doctorMembershipId, LocalDate startedOn, UUID originRecordId, Instant originRecordCreatedAt,
                     String idempotencyKey, String requestHash, boolean estimateSigned) {

        public NewCourse(int courseNo, UUID patientId, String kind, String unit, String name, String note,
                         UUID doctorMembershipId, LocalDate startedOn, UUID originRecordId,
                         Instant originRecordCreatedAt, String idempotencyKey, String requestHash) {
            this(courseNo, patientId, kind, unit, name, note, doctorMembershipId, startedOn, originRecordId,
                    originRecordCreatedAt, idempotencyKey, requestHash, false);
        }
    }

    record NewSitting(UUID courseId, int sittingNo, String encounterKind, String addOptionKey, LocalDate plannedDate,
                      List<String> items, long chargePaise, String note, String idempotencyKey, String requestHash,
                      String regimenKey) {

        public NewSitting(UUID courseId, int sittingNo, String encounterKind, String addOptionKey,
                          LocalDate plannedDate, List<String> items, long chargePaise, String note,
                          String idempotencyKey, String requestHash) {
            this(courseId, sittingNo, encounterKind, addOptionKey, plannedDate, items, chargePaise, note,
                    idempotencyKey, requestHash, null);
        }
    }

    /**
     * What a change may rewrite; {@code admissionRequested} always follows the encounter kind. A null {@code regimenKey}
     * keeps the stored regimen (0.7.0, CHEMO only).
     */
    record SittingChange(String encounterKind, LocalDate plannedDate, List<String> items, long chargePaise,
                         String note, String regimenKey) {

        public SittingChange(String encounterKind, LocalDate plannedDate, List<String> items, long chargePaise,
                             String note) {
            this(encounterKind, plannedDate, items, chargePaise, note, null);
        }
    }

    /** Serialises two requests with the same idempotency key (transaction-scoped advisory lock). */
    void lockIdempotency(UUID tenantId, String idempotencyKey);

    /** Next clinic course number by upsert-increment inside the caller's transaction (gaps allowed, no duplicates). */
    int nextCourseNo(UUID tenantId);

    UUID insertCourse(UUID tenantId, NewCourse course, UUID actorUserId);

    Optional<CourseRow> find(UUID tenantId, UUID courseId);

    /** The course row locked until the end of the transaction (adds, changes and links are serialised on it). */
    Optional<CourseRow> findForUpdate(UUID tenantId, UUID courseId);

    Optional<CourseRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** The patient's courses, newest first, at most {@code limit}. */
    List<CourseRow> forPatient(UUID tenantId, UUID patientId, int limit);

    /** The sittings of these courses that are not removed (ADR-026 D4), by course then sitting number. */
    List<SittingRow> sittingsOf(UUID tenantId, Collection<UUID> courseIds);

    /** The sitting row, a removed one too ({@link SittingRow#removed()}). */
    Optional<SittingRow> sitting(UUID tenantId, UUID sittingId);

    /** The sitting row locked until the end of the transaction, a removed one too. */
    Optional<SittingRow> sittingForUpdate(UUID tenantId, UUID sittingId);

    Optional<SittingRow> sittingByIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<SittingRow> sittingByVisit(UUID tenantId, UUID visitId);

    /** The highest sitting number of the course plus one (call under the course row lock). */
    int nextSittingNo(UUID tenantId, UUID courseId);

    UUID insertSitting(UUID tenantId, NewSitting sitting, UUID actorUserId);

    void updateSitting(UUID tenantId, UUID sittingId, SittingChange change, UUID actorUserId);

    void linkVisit(UUID tenantId, UUID sittingId, UUID visitId, UUID actorUserId);

    void linkAdmission(UUID tenantId, UUID sittingId, UUID admissionId, UUID actorUserId);

    /**
     * Sets done_at (done_source SIGN) on the sitting when it is not done yet and not removed or missed (the doctor's
     * sign, ADR-025 D1 step 9; ADR-026 D4: whichever of "Mark done" and the sign comes first, the second is a no-op).
     */
    void markDone(UUID tenantId, UUID sittingId, Instant at, UUID actorUserId);

    /** "Mark done": done_at, done_source MARK_DONE and the doctor, when not done yet. */
    void markDoneByHand(UUID tenantId, UUID sittingId, Instant at, UUID membershipId, UUID actorUserId);

    /** "Patient did not come": missed_at and the doctor. */
    void markMissed(UUID tenantId, UUID sittingId, Instant at, UUID membershipId, UUID actorUserId);

    /** "Rebook": clears the missed pair and sets the planned date. */
    void rebook(UUID tenantId, UUID sittingId, LocalDate plannedDate, UUID actorUserId);

    /** "Reschedule": the planned date only. */
    void reschedule(UUID tenantId, UUID sittingId, LocalDate plannedDate, UUID actorUserId);

    /** "Remove": removed_at and the doctor; the row is kept. */
    void remove(UUID tenantId, UUID sittingId, Instant at, UUID membershipId, UUID actorUserId);

    /** "Move to day care / IPD" or "Request admission": the encounter kind and admission_requested = true. */
    void requestAdmission(UUID tenantId, UUID sittingId, String encounterKind, UUID actorUserId);

    /** Sets the regimen of a sitting that has none (a sent chemotherapy order, ADR-026 D7). */
    void setRegimenIfEmpty(UUID tenantId, UUID sittingId, String regimenKey, UUID actorUserId);

    /**
     * Planned, unlinked sittings that ask for admission, of ACTIVE courses, newest first, at most 200 (ADR-025 D10);
     * not removed, not missed (ADR-026 D4).
     */
    List<SittingRow> openAdmissionSittings(UUID tenantId);

    /** Revision + 1 (call under the course row lock after a change to its sittings). */
    void bumpRevision(UUID tenantId, UUID courseId, UUID actorUserId);
}
