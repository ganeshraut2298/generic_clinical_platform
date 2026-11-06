package com.sahaj.hra.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code hra.leave_request} (SAH-21). No delete. Needs {@code app.tenant_id} bound. */
public interface LeaveRepository {

    /** Takes a transaction-scoped advisory lock for this person so two concurrent requests queue up. */
    void lockMember(UUID tenantId, UUID membershipId);

    /** True when a pending or approved request of this person touches any day from {@code from} to {@code to}. */
    boolean existsActiveOverlap(UUID tenantId, UUID membershipId, LocalDate from, LocalDate to);

    void insert(UUID tenantId, UUID id, UUID membershipId, String type, LocalDate from, LocalDate to, String reason,
                UUID actorUserId, Instant now);

    Optional<LeaveRow> findById(UUID tenantId, UUID id);

    /** The newest {@code limit} requests of the clinic, newest first. */
    List<LeaveRow> findRecent(UUID tenantId, int limit);

    /** How many pending requests this person has. */
    long countPendingForMember(UUID tenantId, UUID membershipId);

    /** One person's requests, newest first. */
    List<LeaveRow> findForMember(UUID tenantId, UUID membershipId);

    /** Approved requests of anyone that touch any day from {@code from} to {@code to}. */
    List<LeaveRow> findApprovedOverlapping(UUID tenantId, LocalDate from, LocalDate to);

    /** Approved requests of one person that touch any day from {@code from} to {@code to}. */
    List<LeaveRow> findApprovedOverlappingForMember(UUID tenantId, UUID membershipId, LocalDate from, LocalDate to);

    long countPending(UUID tenantId);

    /**
     * Moves a still-pending request to {@code approved} or {@code declined}, setting the decision time and
     * person together. Returns false when the request is no longer pending.
     */
    boolean decide(UUID tenantId, UUID id, String status, UUID actorUserId, Instant now);

    record LeaveRow(UUID id, UUID membershipId, String type, LocalDate from, LocalDate to, String reason,
                    String status, Instant createdAt, Instant decidedAt, UUID decidedBy) {

        /** The reason is staff health data and must never reach a log line. */
        @Override
        public String toString() {
            return "LeaveRow[id=" + id + ", type=" + type + ", from=" + from + ", to=" + to + ", status=" + status + "]";
        }
    }
}
