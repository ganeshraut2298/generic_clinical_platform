package com.sahaj.opd.application;

import com.sahaj.opd.application.WorkflowRepository.DraftRow;
import com.sahaj.opd.application.WorkflowRepository.VersionRow;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-12: the clinic's OPD workflow. The owner edits a draft and publishes it as version N+1; a
 * published version is permanent history. Version 1 is created lazily from the fixed defaults the
 * first time anything needs "the current version" (a concurrent first caller is guarded by
 * {@code on conflict do nothing} plus a re-select). A visit stays on the version it was issued under.
 */
@Service
public class WorkflowService {

    static final String ENTITY_TYPE = "WORKFLOW";

    private final WorkflowRepository repository;
    private final VisitWorkflowSettingsRepository settings;
    private final OwnerGuard ownerGuard;
    private final ActorDirectoryPort actorDirectory;
    private final AuditLog auditLog;

    public WorkflowService(WorkflowRepository repository, VisitWorkflowSettingsRepository settings,
                            OwnerGuard ownerGuard, ActorDirectoryPort actorDirectory, AuditLog auditLog) {
        this.repository = repository;
        this.settings = settings;
        this.ownerGuard = ownerGuard;
        this.actorDirectory = actorDirectory;
        this.auditLog = auditLog;
    }

    /** The current published version, lazily creating version 1 when the clinic has none. */
    @Transactional
    public WorkflowVersionView current(UUID tenantId, UUID actorUserId) {
        return toView(tenantId, ensureCurrent(tenantId, actorUserId));
    }

    /** Newest first; version 1 is created lazily when the clinic has none. */
    @Transactional
    public List<WorkflowVersionView> versions(UUID tenantId, UUID actorUserId) {
        ensureCurrent(tenantId, actorUserId);
        return repository.listVersions(tenantId).stream().map(row -> toView(tenantId, row)).toList();
    }

    /** The saved draft, or a copy of the current published version's steps if none was ever saved. */
    @Transactional
    public List<WorkflowStep> draft(UUID tenantId, UUID actorUserId) {
        return repository.findDraft(tenantId).map(DraftRow::steps)
                .orElseGet(() -> ensureCurrent(tenantId, actorUserId).steps());
    }

    @Transactional
    public List<WorkflowStep> saveDraft(UUID tenantId, UUID actorUserId, List<WorkflowStepInput> input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        List<WorkflowStep> steps = WorkflowRules.validate(input);
        repository.upsertDraft(tenantId, steps, actorUserId);
        return steps;
    }

    @Transactional
    public WorkflowVersionView publish(UUID tenantId, UUID actorUserId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        VersionRow current = ensureCurrent(tenantId, actorUserId);
        DraftRow draft = repository.findDraft(tenantId).orElseThrow(WorkflowService::nothingToPublish);
        List<WorkflowStep> steps = WorkflowRules.validate(WorkflowRules.toInput(draft.steps()));
        if (steps.equals(current.steps())) {
            throw nothingToPublish();
        }
        int next = current.versionNo() + 1;
        if (!repository.insertVersionIfAbsent(tenantId, next, steps, actorUserId)) {
            throw new ConflictException("workflow-conflict", "Workflow changed",
                    "The workflow was published by someone else. Reload and try again.");
        }
        VersionRow published = repository.findVersion(tenantId, next).orElseThrow();
        auditLog.record("WORKFLOW_PUBLISHED", ENTITY_TYPE, tenantId, snapshot(current.versionNo(), current.steps()),
                snapshot(next, steps));
        return toView(tenantId, published);
    }

    /**
     * 409 {@code workflow-conflict} per {@code docs/api/opd.yaml}: no draft, or the draft equals the live version
     * (which is also what the loser of a concurrent publish finds once the winner has committed).
     */
    private static ConflictException nothingToPublish() {
        return new ConflictException("workflow-conflict", "Nothing to publish",
                "There is nothing new to publish. Reload to see the current workflow.");
    }

    /**
     * Read-only: the current version's steps without creating anything. A clinic with no version yet
     * gets exactly what lazy creation would write. For the "my queue" filter, which has no actor to credit.
     */
    @Transactional(readOnly = true)
    public List<WorkflowStep> currentSteps(UUID tenantId) {
        return repository.findCurrent(tenantId).map(VersionRow::steps).orElseGet(() -> seedSteps(tenantId));
    }

    /** The steps of one version, for a visit that was issued under it; defaults for a version that was never written. */
    @Transactional(readOnly = true)
    public List<WorkflowStep> stepsForVersion(UUID tenantId, int versionNo) {
        return repository.findVersion(tenantId, versionNo).map(VersionRow::steps)
                .orElseGet(() -> seedSteps(tenantId));
    }

    private VersionRow ensureCurrent(UUID tenantId, UUID actorUserId) {
        return repository.findCurrent(tenantId).orElseGet(() -> {
            repository.insertVersionIfAbsent(tenantId, 1, seedSteps(tenantId), actorUserId);
            return repository.findCurrent(tenantId)
                    .orElseThrow(() -> new IllegalStateException("Workflow version 1 could not be created"));
        });
    }

    /** The fixed defaults, except Vitals follows the clinic's earlier setup choice (SAH-4) when it made one. */
    private List<WorkflowStep> seedSteps(UUID tenantId) {
        return WorkflowRules.defaults(settings.vitalsOn(tenantId).orElse(true));
    }

    private WorkflowVersionView toView(UUID tenantId, VersionRow row) {
        String name = actorDirectory.displayName(tenantId, row.publishedBy()).orElse(null);
        return new WorkflowVersionView(row.versionNo(), row.steps(), row.publishedAt(), name);
    }

    private static Map<String, Object> snapshot(int versionNo, List<WorkflowStep> steps) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (WorkflowStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", step.key());
            item.put("on", step.on());
            item.put("owner", step.owner());
            list.add(item);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("versionNo", versionNo);
        values.put("steps", list);
        return values;
    }
}
