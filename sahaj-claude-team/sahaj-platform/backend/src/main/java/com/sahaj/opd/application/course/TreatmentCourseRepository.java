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

    /** {@code toString} shows ids only. */
    record CourseRow(UUID id, int courseNo, UUID patientId, String kind, String unit, String name, String note,
                     UUID doctorMembershipId, LocalDate startedOn, String status, long revision, String requestHash,
                     Instant createdAt, UUID originRecordId) {

        /** Without the record the course started from (the 0.4.0 shape). */
        public CourseRow(UUID id, int courseNo, UUID patientId, String kind, String unit, String name, String note,
                         UUID doctorMembershipId, LocalDate startedOn, String status, long revision,
                         String requestHash, Instant createdAt) {
            this(id, courseNo, patientId, kind, unit, name, note, doctorMembershipId, startedOn, status, revision,
                    requestHash, createdAt, null);
        }

        @Override
        public String toString() {
            return "CourseRow[id=" + id + ", courseNo=" + courseNo + "]";
        }
    }

    /** {@code items} and {@code note} are clinical plan text: {@code toString} shows ids only. */
    record SittingRow(UUID id, UUID courseId, int sittingNo, String encounterKind, String addOptionKey,
                      LocalDate plannedDate, List<String> items, long chargePaise, String note, UUID opdVisitId,
                      UUID admissionId, boolean admissionRequested, Instant doneAt, String requestHash) {

        @Override
        public String toString() {
            return "SittingRow[id=" + id + ", sittingNo=" + sittingNo + "]";
        }
    }

    record NewCourse(int courseNo, UUID patientId, String kind, String unit, String name, String note,
                     UUID doctorMembershipId, LocalDate startedOn, UUID originRecordId, Instant originRecordCreatedAt,
                     String idempotencyKey, String requestHash) {
    }

    record NewSitting(UUID courseId, int sittingNo, String encounterKind, String addOptionKey, LocalDate plannedDate,
                      List<String> items, long chargePaise, String note, String idempotencyKey, String requestHash) {
    }

    /** What a change may rewrite; {@code admissionRequested} always follows the encounter kind. */
    record SittingChange(String encounterKind, LocalDate plannedDate, List<String> items, long chargePaise,
                         String note) {
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

    /** All sittings of these courses, by course then sitting number. */
    List<SittingRow> sittingsOf(UUID tenantId, Collection<UUID> courseIds);

    Optional<SittingRow> sitting(UUID tenantId, UUID sittingId);

    /** The sitting row locked until the end of the transaction. */
    Optional<SittingRow> sittingForUpdate(UUID tenantId, UUID sittingId);

    Optional<SittingRow> sittingByIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<SittingRow> sittingByVisit(UUID tenantId, UUID visitId);

    /** The highest sitting number of the course plus one (call under the course row lock). */
    int nextSittingNo(UUID tenantId, UUID courseId);

    UUID insertSitting(UUID tenantId, NewSitting sitting, UUID actorUserId);

    void updateSitting(UUID tenantId, UUID sittingId, SittingChange change, UUID actorUserId);

    void linkVisit(UUID tenantId, UUID sittingId, UUID visitId, UUID actorUserId);

    void linkAdmission(UUID tenantId, UUID sittingId, UUID admissionId, UUID actorUserId);

    /** Sets done_at on the sitting when it is not done yet (the doctor's sign, ADR-025 D1 step 9). */
    void markDone(UUID tenantId, UUID sittingId, Instant at, UUID actorUserId);

    /** Planned, unlinked sittings that ask for admission, of ACTIVE courses, newest first, at most 200 (ADR-025 D10). */
    List<SittingRow> openAdmissionSittings(UUID tenantId);

    /** Revision + 1 (call under the course row lock after a change to its sittings). */
    void bumpRevision(UUID tenantId, UUID courseId, UUID actorUserId);
}
