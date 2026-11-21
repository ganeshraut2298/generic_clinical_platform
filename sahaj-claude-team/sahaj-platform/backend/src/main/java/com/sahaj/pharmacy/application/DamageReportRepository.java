package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.damage_report} (SAH-79-14, ADR-029 P12). A report is inserted WAITING_APPROVAL (then pointed at its
 * approval request) or, when no approval level applies, already decided. The table allows exactly one status move, from
 * WAITING_APPROVAL to a final status, with {@code decided_at} set in the same update. Notes are never logged here.
 */
public interface DamageReportRepository {

    record Row(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, UUID productId, UUID batchId, String fromState,
               int quantity, String reasonKey, String note, String afterApproval, String status, String rejectOutcome,
               UUID approvalRequestId, UUID secondSignerMembershipId, Instant decidedAt, Instant createdAt, UUID createdBy,
               long version) {
    }

    /** {@code status} is WAITING_APPROVAL, or (level 0) WRITTEN_OFF or SENT_TO_CS with {@code decided} set. */
    record NewRow(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, UUID productId, UUID batchId, String fromState,
                  int quantity, String reasonKey, String note, String afterApproval, String status,
                  UUID secondSignerMembershipId, boolean decided, UUID createdBy) {
    }

    Optional<Row> find(UUID tenantId, UUID storeId, UUID id);

    Optional<Row> findById(UUID tenantId, UUID id);

    /** By id, locked {@code FOR UPDATE} (lock order step 3; the approval handler works from the request's subject id). */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    /**
     * The units that WAITING_APPROVAL reports of this batch at this store hold in QUARANTINE (a report moves its units there when it
     * is raised, and the approval takes them out again); another report must leave them alone.
     */
    int waitingQuantity(UUID tenantId, UUID storeId, UUID batchId);

    void insert(UUID tenantId, NewRow row);

    /** Points a WAITING_APPROVAL report at its approval request. */
    void attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actorMembershipId);

    /** WAITING_APPROVAL to a final status ({@code rejectOutcome} only with RELEASED or KEPT_IN_QUARANTINE). */
    boolean decide(UUID tenantId, UUID id, String status, String rejectOutcome, UUID actorMembershipId);
}
