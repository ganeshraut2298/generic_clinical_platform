package com.sahaj.opd.web;

import com.sahaj.opd.application.WorkflowStep;
import com.sahaj.opd.application.WorkflowStepInput;
import java.util.List;

/** {@code WorkflowSteps} in {@code docs/api/opd.yaml} (SAH-12), for the request and the response. */
public record WorkflowStepsDto(List<WorkflowStepDto> steps) {

    public static WorkflowStepsDto from(List<WorkflowStep> steps) {
        return new WorkflowStepsDto(steps.stream().map(WorkflowStepDto::from).toList());
    }

    public List<WorkflowStepInput> toInput() {
        return steps == null ? null : steps.stream().map(step -> step == null ? null : step.toInput()).toList();
    }
}
