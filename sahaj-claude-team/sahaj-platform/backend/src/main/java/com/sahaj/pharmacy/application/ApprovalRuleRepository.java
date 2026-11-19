package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.TransactionType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over the approval rule tables (SAH-78-9, ADR-028 D7). The version, rule and level tables are immutable: rows are
 * inserted and never changed or deleted. Only {@code approval_rule_set}, the pointer to the current version, is updated.
 * {@code createdBy} is the saver's membership id (so the saved-by name can be shown).
 */
public interface ApprovalRuleRepository {

    record Version(UUID id, int versionNo, RulePreset preset, Long highValueThresholdPaise, Instant createdAt,
                   UUID createdBy) {
    }

    record VersionSlice(List<Version> items, long total) {
    }

    /** The version the unit's rule set points at; empty before the first save. */
    Optional<Version> current(UUID tenantId);

    Optional<Version> version(UUID tenantId, int versionNo);

    /** Newest first. */
    VersionSlice versions(UUID tenantId, int limit, int offset);

    /** The nine rules of a version in screen order; a type with no row has no rule (empty list when none were saved). */
    List<ApprovalRule> rules(UUID tenantId, UUID versionId);

    UUID insertVersion(UUID tenantId, UUID unitId, int versionNo, RulePreset preset, Long highValueThresholdPaise,
                       UUID actorMembershipId);

    UUID insertRule(UUID tenantId, UUID unitId, UUID versionId, TransactionType type, Integer escalateAfterHours,
                    Integer urgentEscalateAfterMinutes, UUID actorMembershipId);

    void insertLevel(UUID tenantId, UUID unitId, UUID ruleId, ApprovalLevel level, UUID actorMembershipId);

    /** First save: the pointer to version 1. */
    void insertSet(UUID tenantId, UUID unitId, UUID versionId, UUID actorMembershipId);

    /** Later saves: the pointer moves to the new version. */
    void updateSet(UUID tenantId, UUID unitId, UUID versionId, UUID actorMembershipId);
}
