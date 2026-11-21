package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port over {@code pharmacy.stock_audit} and {@code pharmacy.stock_audit_line} (SAH-79-17, ADR-029 P12). Every audit write
 * bumps {@code version} (contract 0.3.6: the count sheet and the audit share one version). Row level security scopes every
 * statement. The book quantity of a line is never part of a counter answer; the services decide who sees it.
 */
public interface StockAuditRepository {

    record Audit(UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, String scope, String status,
                 UUID submittedBy, Instant submittedAt, UUID approvalRequestId, UUID secondSignerMembershipId,
                 Instant startedAt, UUID createdBy, long version) {

        public boolean open() {
            return !"CLOSED".equals(status);
        }
    }

    record Line(UUID id, UUID auditId, UUID productId, UUID batchId, Integer countedQty, boolean notCounted,
                String notCountedReasonKey, UUID countedBy, Instant countedAt, boolean recount, Integer bookQty,
                Instant bookOccurredAt, String varianceReasonKey, String varianceNote, Instant postedAt) {

        /** Counted, or marked not counted. */
        public boolean answered() {
            return countedQty != null || notCounted;
        }

        /** Counted minus the book quantity (a not-counted line has no difference). */
        public Integer difference() {
            return countedQty == null || bookQty == null ? null : countedQty - bookQty;
        }
    }

    record Slice(List<Audit> items, long total) {
    }

    /** A batch to count: its product, batch and the AVAILABLE quantity it holds now (never answered to a counter). */
    record Seed(UUID productId, UUID batchId, int qty) {
    }

    /** The last ledger line of a store and batch (any state). */
    record LedgerMark(UUID id, Instant occurredAt) {
    }

    Optional<Audit> find(UUID tenantId, UUID storeId, UUID auditId);

    Optional<Audit> findById(UUID tenantId, UUID auditId);

    /** The audit row locked FOR UPDATE, by id alone (the approval handler works from the subject id of the request). */
    Optional<Audit> findForUpdate(UUID tenantId, UUID auditId);

    /** The open audit of the store (COUNTING, RECOUNT, WAITING_REVIEW or WAITING_APPROVAL), if any. */
    Optional<Audit> openOf(UUID tenantId, UUID storeId);

    /** The products with at least one line on the open audit of the store; empty when there is none (rule 24). */
    Set<UUID> openAuditProducts(UUID tenantId, UUID storeId);

    Slice list(UUID tenantId, UUID storeId, String status, int limit, int offset);

    /** The AVAILABLE batches (quantity above 0) of the store, for the whole store or for the given products. */
    List<Seed> availableBatches(UUID tenantId, UUID storeId, Collection<UUID> productIdsOrNull);

    /** The ids of the given products that exist. */
    Set<UUID> knownProducts(UUID tenantId, Collection<UUID> productIds);

    void insert(UUID tenantId, UUID id, UUID unitId, long docSeq, String docNo, UUID storeId, String scope, UUID createdBy);

    void insertLines(UUID tenantId, UUID unitId, UUID auditId, List<Seed> seeds, UUID createdBy);

    /** Lines in a stable order (product name, batch expiry, id); onlyRecount keeps the lines open for recount. */
    List<Line> lines(UUID tenantId, UUID auditId, boolean onlyRecount, int limit, int offset);

    long countLines(UUID tenantId, UUID auditId, boolean onlyRecount);

    /** Every line of the audit, no paging. */
    List<Line> allLines(UUID tenantId, UUID auditId);

    /** The lines of this audit among the ids, locked FOR UPDATE. */
    List<Line> linesForUpdate(UUID tenantId, UUID auditId, Collection<UUID> ids);

    Optional<Line> findLine(UUID tenantId, UUID lineId);

    /** The saved count of one line; counted may be null with notCounted true. Clears the recount flag and the book side (submit fixes it again). */
    void saveCount(UUID tenantId, UUID lineId, Integer counted, boolean notCounted, String reasonKey, UUID countedBy);

    /** Returns lines to the counter blind: counted and book side cleared, recount flag set. */
    void requestRecount(UUID tenantId, Collection<UUID> lineIds, UUID actor);

    void fixBook(UUID tenantId, UUID lineId, int bookQty, UUID ledgerId, Instant occurredAt, UUID actor);

    void saveVariance(UUID tenantId, UUID lineId, String reasonKey, String note, UUID actor);

    void markPosted(UUID tenantId, UUID lineId, UUID actor);

    /** Version bump only. False when the version is not current. */
    boolean bump(UUID tenantId, UUID auditId, long version, UUID actor);

    /** Status change with the version bump; submittedBy (when not null) sets the submission. False when the version is not current. */
    boolean setStatus(UUID tenantId, UUID auditId, long version, String status, UUID submittedBy, UUID actor);

    void attachRequest(UUID tenantId, UUID auditId, UUID requestId, UUID signerOrNull, UUID actor);

    /** Moves a WAITING_APPROVAL audit to the given status with the version bump. False when it is not waiting. */
    boolean setStatusFromApproval(UUID tenantId, UUID auditId, String status, UUID actor);

    /**
     * Status change with the version bump for a caller that holds the audit row lock (no version check): the database guard
     * decides which moves are allowed. {@code submittedBy} (when not null) sets the submission.
     */
    void moveTo(UUID tenantId, UUID auditId, String status, UUID submittedBy, UUID actor);

    /** Takes the second signer of a Schedule X variance, once (the database refuses a second one); version bump. */
    void setSigner(UUID tenantId, UUID auditId, UUID signerMembershipId, UUID actor);

    /** Lines of the audit that are counted or marked not counted. */
    long countAnswered(UUID tenantId, UUID auditId);

    /** Lowers the book quantity of a line after a consumption entry posted instead ("Post as consumption instead"); never below 0. */
    void lowerBook(UUID tenantId, UUID lineId, int by, UUID actor);

    /** The last ledger line of the store and batch, any state. */
    Optional<LedgerMark> lastLedger(UUID tenantId, UUID storeId, UUID batchId);

    /**
     * Whether an AVAILABLE ledger line of this store and batch is later than the given time, other than a consumption entry
     * linked to the audit line (that entry is the cure, not a movement to recount).
     */
    boolean movedSince(UUID tenantId, UUID storeId, UUID batchId, Instant after, UUID auditLineId);
}
