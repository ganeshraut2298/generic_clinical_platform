package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.approval_request}, {@code approval_request_level} and {@code approval_decision} (SAH-79-2, ADR-029
 * P6). The request is the only row that changes (its level and status); the level copies and the decisions are inserted and
 * never changed (BR-12). Row level security scopes every statement to the bound clinic.
 */
public interface ApprovalRepository {

    record Request(UUID id, UUID unitId, TransactionType type, String subjectType, UUID subjectId, String subjectNo,
                   UUID ruleStoreId, UUID raisedBy, Instant raisedAt, /* null: the served default */ UUID ruleVersionId, long amountPaise,
                   boolean hasScheduleX, boolean hasHighValueItem, boolean urgent, BigDecimal maxRateRisePercent,
                   Integer escalateAfterMinutes, Integer currentLevel, Instant levelEnteredAt, String status, long version) {

        public boolean pending() {
            return "PENDING".equals(status);
        }
    }

    record Level(int levelNo, ApproverKind approver, ConditionKind condition, boolean applies, String ruleText) {
    }

    record Decision(int levelNo, String decision, UUID decidedBy, String actingAs, String reasonKey, String note,
                    Instant decidedAt) {
    }

    /** The request to write at submit; {@code currentLevel} is the first level that applies. */
    record NewRequest(UUID unitId, TransactionType type, String subjectType, UUID subjectId, String subjectNo,
                      UUID ruleStoreId, UUID raisedBy, UUID ruleVersionId, long amountPaise, boolean hasScheduleX,
                      boolean hasHighValueItem, boolean urgent, BigDecimal maxRateRisePercent,
                      Integer escalateAfterMinutes, int currentLevel) {
    }

    /** The level copy to write at submit. */
    record NewLevel(int levelNo, ApproverKind approver, ConditionKind condition, Long amountPaise, BigDecimal percent,
                    List<String> storeTypes, boolean applies, String ruleText) {
    }

    Optional<Request> find(UUID tenantId, UUID requestId);

    /** The request row locked {@code FOR UPDATE} (lock order step 2). */
    Optional<Request> findForUpdate(UUID tenantId, UUID requestId);

    /** The newest request of a document, whatever its status. */
    Optional<Request> findBySubject(UUID tenantId, String subjectType, UUID subjectId);

    /** Every PENDING request of the clinic, oldest first. */
    List<Request> pending(UUID tenantId);

    /**
     * Pending requests the caller could decide (L19): {@code storeIds} are the stores where the caller is an approver or a
     * delegate, or null for an owner (every store); sales requests are left out; oldest first, at most {@code limit} rows.
     */
    List<Request> pendingFor(UUID tenantId, java.util.Collection<UUID> storeIds, int limit);

    List<Level> levels(UUID tenantId, UUID requestId);

    /** The levels of many requests in one statement (m-8); a request with none is absent from the map. */
    java.util.Map<UUID, List<Level>> levelsOf(UUID tenantId, java.util.Collection<UUID> requestIds);

    /** The decisions of many requests in one statement (m-8). */
    java.util.Map<UUID, List<Decision>> decisionsOf(UUID tenantId, java.util.Collection<UUID> requestIds);

    List<Decision> decisions(UUID tenantId, UUID requestId);

    /** The rule version number a request used; 0 when the id is null (the served Standard default, D-5). */
    int versionNo(UUID tenantId, UUID ruleVersionId);

    UUID insertRequest(UUID tenantId, NewRequest request, UUID actorMembershipId);

    void insertLevel(UUID tenantId, UUID unitId, UUID requestId, NewLevel level, UUID actorMembershipId);

    void insertDecision(UUID tenantId, UUID unitId, UUID requestId, int levelNo, String decision, UUID decidedBy,
                        UUID raisedBy, String actingAs, String reasonKey, String note);

    /** Moves a PENDING request to level 2 and restarts the escalation clock. False when the version is not current. */
    boolean advanceToLevel(UUID tenantId, UUID requestId, long version, int level, UUID actorMembershipId);

    /** Ends a PENDING request as APPROVED or REJECTED. False when the version is not current. */
    boolean finish(UUID tenantId, UUID requestId, long version, String status, UUID actorMembershipId);
}
