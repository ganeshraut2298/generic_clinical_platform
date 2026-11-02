package com.sahaj.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port over {@code platform.audit_event} reads (SAH-7 change history). Needs {@code app.tenant_id} bound. */
public interface AuditEventRepository {

    /** The bound clinic's newest events first. {@code actorName} is the person's name in this clinic. */
    List<AuditRow> findLatest(UUID tenantId, int limit);

    /** {@code beforeJson} and {@code afterJson} are the stored, already sanitised JSON text (or null). */
    record AuditRow(UUID id, String actorName, String action, String entityType, String beforeJson,
                    String afterJson, Instant at) {
    }
}
