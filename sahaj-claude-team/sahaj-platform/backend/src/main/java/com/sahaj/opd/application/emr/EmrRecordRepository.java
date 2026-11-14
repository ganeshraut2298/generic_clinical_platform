package com.sahaj.opd.application.emr;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code opd.emr_record} (ADR-020 D8): one record per encounter for an OPD visit, an IPD round and an
 * Emergency case. Clinical data: never deleted, never logged. Row level security limits every statement to the bound
 * clinic and the tenant id is also in each statement. Updates are compare-and-set on the revision.
 */
public interface EmrRecordRepository {

    /**
     * One stored record. {@code sections} is the values document as JSON text ({@link EmrRecordDocument}).
     * {@code toString} shows the id and type only.
     */
    record RecordRow(UUID id, String encounterType, UUID opdVisitId, UUID ipdAdmissionId, LocalDate roundDate,
                     UUID erCaseId, UUID patientId, UUID openedByMembershipId, String departmentKey,
                     String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                     Integer starterLayoutVersion, String choiceSource, String status, Instant signedAt,
                     String sections, String uiPart, String uiSectionKey, boolean admissionAdvised,
                     String referralLine, long revision, Instant createdAt, String printLanguage, String paperSize,
                     String formRefs, String screenState) {

        /** The phase 3 shape: no screen state (ADR-023 D2 adds it). */
        public RecordRow(UUID id, String encounterType, UUID opdVisitId, UUID ipdAdmissionId, LocalDate roundDate,
                         UUID erCaseId, UUID patientId, UUID openedByMembershipId, String departmentKey,
                         String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                         Integer starterLayoutVersion, String choiceSource, String status, Instant signedAt,
                         String sections, String uiPart, String uiSectionKey, boolean admissionAdvised,
                         String referralLine, long revision, Instant createdAt, String printLanguage,
                         String paperSize, String formRefs) {
            this(id, encounterType, opdVisitId, ipdAdmissionId, roundDate, erCaseId, patientId, openedByMembershipId,
                    departmentKey, departmentName, starterKey, layoutKind, departmentVersionId, starterLayoutVersion,
                    choiceSource, status, signedAt, sections, uiPart, uiSectionKey, admissionAdvised, referralLine,
                    revision, createdAt, printLanguage, paperSize, formRefs, null);
        }

        /** The phase 2 shape: no print preferences and no form references. */
        public RecordRow(UUID id, String encounterType, UUID opdVisitId, UUID ipdAdmissionId, LocalDate roundDate,
                         UUID erCaseId, UUID patientId, UUID openedByMembershipId, String departmentKey,
                         String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                         Integer starterLayoutVersion, String choiceSource, String status, Instant signedAt,
                         String sections, String uiPart, String uiSectionKey, boolean admissionAdvised,
                         String referralLine, long revision, Instant createdAt) {
            this(id, encounterType, opdVisitId, ipdAdmissionId, roundDate, erCaseId, patientId, openedByMembershipId,
                    departmentKey, departmentName, starterKey, layoutKind, departmentVersionId, starterLayoutVersion,
                    choiceSource, status, signedAt, sections, uiPart, uiSectionKey, admissionAdvised, referralLine,
                    revision, createdAt, null, null, "[]", null);
        }

        public UUID encounterId() {
            return switch (encounterType) {
                case "OPD_VISIT" -> opdVisitId;
                case "IPD_ROUND" -> ipdAdmissionId;
                default -> erCaseId;
            };
        }

        @Override
        public String toString() {
            return "RecordRow[id=" + id + ", encounterType=" + encounterType + "]";
        }
    }

    record NewRecord(UUID id, String encounterType, UUID opdVisitId, UUID ipdAdmissionId, LocalDate roundDate,
                     UUID erCaseId, UUID patientId, UUID openedByMembershipId, String departmentKey,
                     String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                     Integer starterLayoutVersion, String choiceSource, String sections, String formRefs) {

        /** A record with no clinic forms (form_refs is an empty list). */
        public NewRecord(UUID id, String encounterType, UUID opdVisitId, UUID ipdAdmissionId, LocalDate roundDate,
                         UUID erCaseId, UUID patientId, UUID openedByMembershipId, String departmentKey,
                         String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                         Integer starterLayoutVersion, String choiceSource, String sections) {
            this(id, encounterType, opdVisitId, ipdAdmissionId, roundDate, erCaseId, patientId, openedByMembershipId,
                    departmentKey, departmentName, starterKey, layoutKind, departmentVersionId, starterLayoutVersion,
                    choiceSource, sections, "[]");
        }
    }

    /** The values of the patient's latest SIGNED record of the same department (Copy from last visit). */
    record SignedCopy(UUID recordId, Instant signedAt, String sections) {
    }

    /** Takes a transaction-scoped advisory lock on the encounter, so that two first opens make one record. */
    void lockEncounter(UUID tenantId, UUID encounterId);

    Optional<RecordRow> findById(UUID tenantId, UUID recordId);

    Optional<RecordRow> findOpd(UUID tenantId, UUID visitId);

    Optional<RecordRow> findIpd(UUID tenantId, UUID admissionId, LocalDate roundDate);

    Optional<RecordRow> latestIpd(UUID tenantId, UUID admissionId);

    Optional<RecordRow> findEmergency(UUID tenantId, UUID caseId);

    void insert(UUID tenantId, NewRecord record, UUID actorUserId);

    /** Replaces the values document and the UI position, revision + 1. False when the revision moved. */
    boolean saveDraft(UUID tenantId, UUID recordId, long expectedRevision, String sections, String uiPart,
                      String uiSectionKey, UUID actorUserId);

    /**
     * Replaces the values document, the UI position and the screen state (ADR-023 D2), revision + 1; a null screen state
     * leaves the stored one. False when the revision moved.
     */
    boolean saveDraft(UUID tenantId, UUID recordId, long expectedRevision, String sections, String uiPart,
                      String uiSectionKey, String screenState, UUID actorUserId);

    /** Replaces the layout reference, revision + 1. False when the revision moved or the record is signed. */
    boolean switchLayout(UUID tenantId, UUID recordId, long expectedRevision, String departmentKey,
                         String departmentName, String starterKey, String layoutKind, UUID departmentVersionId,
                         Integer starterLayoutVersion, String choiceSource, UUID actorUserId);

    /** Replaces the form references (a department switch); no revision change, the caller already moved it. */
    void saveFormRefs(UUID tenantId, UUID recordId, String formRefs, UUID actorUserId);

    /** Sets admission advised and the referral line, revision + 1. False when the revision moved. */
    boolean adviseAdmission(UUID tenantId, UUID recordId, long expectedRevision, String referralLine,
                            UUID actorUserId);

    /**
     * Sets the referral line of an Emergency record that finishes as refer-out, without "admission advised" (DEC-048 H22: the
     * database allows a referral line on an ER_ASSESSMENT record), revision + 1. False when the revision moved, the record is
     * signed or it is not an Emergency record.
     */
    boolean setReferralLine(UUID tenantId, UUID recordId, long expectedRevision, String referralLine, UUID actorUserId);

    Optional<SignedCopy> latestSigned(UUID tenantId, UUID patientId, String departmentKey);

    /** The latest SIGNED IPD round of this admission (Copy from last visit for a round, ADR-025 D8). */
    Optional<SignedCopy> latestSignedIpd(UUID tenantId, UUID admissionId);

    /** The patient SIGNED records, newest first, at most {@code limit} (last prescription, history details). */
    List<SignedCopy> signedForPatient(UUID tenantId, UUID patientId, int limit);

    /**
     * The patient's records in this clinic, newest first, at most {@code limit} (history, ADR-022 D10). Clinical data:
     * callers never log it.
     */
    List<RecordRow> forPatient(UUID tenantId, UUID patientId, int limit);

    /** Sets the print language and paper size (null = clinic default). No revision change; allowed once signed. */
    boolean savePrintPreferences(UUID tenantId, UUID recordId, String language, String paperSize, UUID actorUserId);

    /** The record locked until the end of the transaction (copy actions). */
    Optional<RecordRow> findByIdForUpdate(UUID tenantId, UUID recordId);

    /** Replaces the values document without a revision check, revision + 1 (copy actions under the row lock). */
    boolean saveSectionsLocked(UUID tenantId, UUID recordId, String sections, UUID actorUserId);
}
