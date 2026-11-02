package com.sahaj.opd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.workflow_draft} and the append-only {@code opd.workflow_version} (SAH-12). Needs {@code app.tenant_id} bound. */
public interface WorkflowRepository {

    /** The highest version_no for the tenant, if anything was ever published or lazily created. */
    Optional<VersionRow> findCurrent(UUID tenantId);

    Optional<VersionRow> findVersion(UUID tenantId, int versionNo);

    /** Newest first. */
    List<VersionRow> listVersions(UUID tenantId);

    /**
     * Inserts the version unless one with that number already exists (a concurrent first caller or
     * publisher won the race).
     *
     * @return true when this call inserted the row
     */
    boolean insertVersionIfAbsent(UUID tenantId, int versionNo, List<WorkflowStep> steps, UUID publishedBy);

    Optional<DraftRow> findDraft(UUID tenantId);

    void upsertDraft(UUID tenantId, List<WorkflowStep> steps, UUID actorUserId);

    record VersionRow(int versionNo, List<WorkflowStep> steps, Instant publishedAt, UUID publishedBy) {
    }

    record DraftRow(List<WorkflowStep> steps) {
    }
}
