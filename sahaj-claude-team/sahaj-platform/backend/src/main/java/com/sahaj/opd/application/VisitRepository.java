package com.sahaj.opd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.visit} (SAH-9). Needs {@code app.tenant_id} bound. */
public interface VisitRepository {

    /** Allocates the next token number for the bound tenant and today, via {@code opd.next_token_no()}. */
    int nextTokenNo();

    void insert(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage, int tokenNo,
                long feePaise, int workflowVersionNo, UUID actorUserId);

    /** Another clinic's id, or any id at all, is simply absent. */
    Optional<VisitRow> findById(UUID tenantId, UUID id);

    /** Every visit created at or after {@code since} (today, server-side), newest token first within the day. */
    List<VisitRow> findSince(UUID tenantId, Instant since);

    /** Every visit created at or after {@code from} and before {@code to}, oldest first (SAH-16 revenue). */
    List<VisitRow> findBetween(UUID tenantId, Instant from, Instant to);

    /** The visits with these ids that belong to the tenant (SAH-16 revenue); unknown ids are simply absent. */
    List<VisitRow> findByIds(UUID tenantId, java.util.Collection<UUID> ids);

    /** The most recent visit this patient had with this doctor, if any (follow-up window, AC5). */
    Optional<Instant> lastVisitAt(UUID tenantId, UUID patientId, UUID doctorMembershipId);

    void updateStage(UUID id, String stage, UUID actorUserId);

    /** SAH-46-2: moves the stage only while it is still {@code from}; false when someone moved it first. */
    boolean moveStageIfCurrent(UUID id, String from, String to, UUID actorUserId);

    /** SAH-52-2: sets the visit type (NEW, FOLLOW_UP, REVISIT) and bumps the version. */
    void updateVisitType(UUID id, String visitType, UUID actorUserId);

    /**
     * SAH-67-4, SAH-68-2: sets the visit type with the triage level and scale of an Emergency visit (both null for another
     * type) and bumps the version.
     */
    void updateVisitTypeAndTriage(UUID tenantId, UUID id, String visitType, Integer triageLevel, Integer triageScale,
                                  UUID actorUserId);

    /** SAH-68-2: sets the fee (the visit-type change while the bill has no payment) and bumps the version. */
    void updateFee(UUID tenantId, UUID id, long feePaise, UUID actorUserId);

    /**
     * Locks the visit row ({@code for update}) for the rest of the transaction. The sign and the visit-type change take it
     * right after the record lock (ADR-025 D1 step 5), so a card move, a sign and a fee change meet in one order. False when
     * the visit is absent.
     */
    boolean lockById(UUID tenantId, UUID id);

    /** SAH-68-2: the creation time of the patient's latest visit with this doctor before {@code before}, if any. */
    Optional<Instant> lastVisitBefore(UUID tenantId, UUID patientId, UUID doctorMembershipId, Instant before);

    /** SAH-47-1: the creation time of the patient's latest visit before {@code before}, if any. */
    Optional<Instant> previousVisitAt(UUID tenantId, UUID patientId, Instant before);

    /** SAH-47-1: how many visits the patient has had up to and including {@code upTo} ("3rd visit"). */
    int countVisitsUpTo(UUID tenantId, UUID patientId, Instant upTo);

    /**
     * {@code emrRecordId} and {@code emrStatus} (NONE, DRAFT, SIGNED) are the visit's EMR record, if opened (SAH-46,
     * docs/api/opd.yaml 0.3.0); null and NONE before the first "Open EMR".
     */
    record VisitRow(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage, int tokenNo,
                     long feePaise, Instant createdAt, long version, int workflowVersionNo, UUID emrRecordId,
                     String emrStatus, Integer triageLevel, Integer triageScale) {

        /** A visit that is not an Emergency visit (no triage level). */
        public VisitRow(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage, int tokenNo,
                         long feePaise, Instant createdAt, long version, int workflowVersionNo, UUID emrRecordId,
                         String emrStatus) {
            this(id, patientId, doctorMembershipId, visitType, stage, tokenNo, feePaise, createdAt, version,
                    workflowVersionNo, emrRecordId, emrStatus, null, null);
        }

        /** A visit with no EMR record yet. */
        public VisitRow(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage,
                         int tokenNo, long feePaise, Instant createdAt, long version, int workflowVersionNo) {
            this(id, patientId, doctorMembershipId, visitType, stage, tokenNo, feePaise, createdAt, version,
                    workflowVersionNo, null, "NONE");
        }

        /** A visit on workflow version 1 (every visit before SAH-12). */
        public VisitRow(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage,
                         int tokenNo, long feePaise, Instant createdAt, long version) {
            this(id, patientId, doctorMembershipId, visitType, stage, tokenNo, feePaise, createdAt, version, 1);
        }
    }
}
