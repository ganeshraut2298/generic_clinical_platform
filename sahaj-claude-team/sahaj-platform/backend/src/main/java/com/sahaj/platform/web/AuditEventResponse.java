package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.platform.application.SetupOverviewService.AuditEventView;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code AuditEvent} in {@code docs/api/platform.yaml} (SAH-7). Values were sanitised when they were stored. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventResponse(UUID id, String actorName, String action, String entityType,
                                  Map<String, Object> before, Map<String, Object> after, Instant at) {

    public static AuditEventResponse from(AuditEventView view) {
        return new AuditEventResponse(view.id(), view.actorName(), view.action(), view.entityType(), view.before(),
                view.after(), view.at());
    }
}
