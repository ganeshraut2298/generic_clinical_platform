package com.sahaj.opd.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.alert_rule} and {@code opd.alert_rule_state} (SAH-18). Needs {@code app.tenant_id} bound. */
public interface AlertRuleRepository {

    /** The clinic's rules, oldest first. */
    List<AlertRuleRow> list(UUID tenantId);

    /** True when the starter rules were already saved for this clinic. */
    boolean isSeeded(UUID tenantId);

    /** Records the starter marker; returns true only for the call that created it. */
    boolean markSeeded(UUID tenantId);

    /** Inserts a rule; empty when the same when, cond and then already exist. */
    Optional<UUID> insert(UUID tenantId, String when, String cond, String then, boolean enabled, UUID actorUserId);

    Optional<AlertRuleRow> find(UUID tenantId, UUID id);

    /** Returns the number of rows changed (0 when the rule does not exist for this clinic). */
    int setEnabled(UUID tenantId, UUID id, boolean enabled, UUID actorUserId);

    /** Returns the number of rows removed. */
    int delete(UUID tenantId, UUID id);

    record AlertRuleRow(UUID id, String when, String cond, String then, boolean enabled) {
    }
}
