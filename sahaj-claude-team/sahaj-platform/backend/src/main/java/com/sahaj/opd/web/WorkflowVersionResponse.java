package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.opd.application.WorkflowVersionView;
import java.time.Instant;
import java.util.List;

/** {@code WorkflowVersion} in {@code docs/api/opd.yaml} (SAH-12). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowVersionResponse(int versionNo, List<WorkflowStepDto> steps, Instant publishedAt,
                                       String publishedByName) {

    public static WorkflowVersionResponse from(WorkflowVersionView view) {
        return new WorkflowVersionResponse(view.versionNo(), view.steps().stream().map(WorkflowStepDto::from).toList(),
                view.publishedAt(), view.publishedByName());
    }
}
