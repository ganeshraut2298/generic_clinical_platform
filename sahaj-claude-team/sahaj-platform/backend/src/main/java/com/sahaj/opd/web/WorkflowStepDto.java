package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.opd.application.WorkflowStep;
import com.sahaj.opd.application.WorkflowStepInput;

/** {@code WorkflowStep} in {@code docs/api/opd.yaml} (SAH-12); {@code owner} is omitted for steps with none. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowStepDto(String key, Boolean on, String owner) {

    public static WorkflowStepDto from(WorkflowStep step) {
        return new WorkflowStepDto(step.key(), step.on(), step.owner());
    }

    public WorkflowStepInput toInput() {
        return new WorkflowStepInput(key, on, owner);
    }
}
