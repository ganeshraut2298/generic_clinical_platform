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

    /** The most recent visit this patient had with this doctor, if any (follow-up window, AC5). */
    Optional<Instant> lastVisitAt(UUID tenantId, UUID patientId, UUID doctorMembershipId);

    void updateStage(UUID id, String stage, UUID actorUserId);

    record VisitRow(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage, int tokenNo,
                     long feePaise, Instant createdAt, long version, int workflowVersionNo) {

        /** A visit on workflow version 1 (every visit before SAH-12). */
        public VisitRow(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage,
                         int tokenNo, long feePaise, Instant createdAt, long version) {
            this(id, patientId, doctorMembershipId, visitType, stage, tokenNo, feePaise, createdAt, version, 1);
        }
    }
}
