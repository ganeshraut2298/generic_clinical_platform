package com.sahaj.opd.application.emr;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port over this clinic's template overrides and the per-template revision (SAH-44, ADR-019 D5). Tenant data: needs
 * {@code app.tenant_id} bound, and every statement also carries the tenant id.
 */
public interface EmrSetupRepository {

    record OverrideRow(String sectionKey, boolean shown, boolean required) {
    }

    /** 0 before the first change of this template. */
    long revision(UUID tenantId, String templateKey);

    /** Every override of this clinic, by template key. */
    Map<String, List<OverrideRow>> allOverrides(UUID tenantId);

    List<OverrideRow> overrides(UUID tenantId, String templateKey);

    /**
     * Moves the revision from {@code expected} to {@code expected + 1} (creating the row when {@code expected} is 0).
     * Returns false when another change got there first, which the caller answers with 409. This is the point where
     * two owners are told apart: the loser's statement waits for the winner's commit and then matches no row.
     */
    boolean bumpRevision(UUID tenantId, String templateKey, long expected, UUID actorUserId);

    void saveOverride(UUID tenantId, String templateKey, String sectionKey, boolean shown, boolean required,
                      UUID actorUserId);

    void deleteOverride(UUID tenantId, String templateKey, String sectionKey);

    /** Deletes every override of this template for this clinic and returns how many there were. */
    int deleteOverrides(UUID tenantId, String templateKey);
}
