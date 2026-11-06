package com.sahaj.opd.application;

import com.sahaj.platform.api.InvalidInputException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SAH-12: the fixed defaults and the validation of a submitted step list (mirrors the mockup's critical checks). */
final class WorkflowRules {

    static final List<String> KEYS = List.of("token", "queue", "vitals", "consult", "counsel", "casepaper",
            "billing", "followup");
    static final Set<String> ALWAYS_ON = Set.of("token", "queue", "consult", "billing");
    static final Set<String> OWNER_ROLES = Set.of("FRONT_DESK", "NURSE", "DOCTOR", "OWNER");

    private WorkflowRules() {
    }

    /** token/queue/consult/billing on; vitals on (NURSE) unless {@code vitalsOn} says otherwise; counsel off (DOCTOR); casepaper, followup on. */
    static List<WorkflowStep> defaults(boolean vitalsOn) {
        return List.of(
                new WorkflowStep("token", true, null),
                new WorkflowStep("queue", true, null),
                new WorkflowStep("vitals", vitalsOn, "NURSE"),
                new WorkflowStep("consult", true, null),
                new WorkflowStep("counsel", false, "DOCTOR"),
                new WorkflowStep("casepaper", true, null),
                new WorkflowStep("billing", true, null),
                new WorkflowStep("followup", true, null));
    }

    /**
     * The submitted list must be exactly the eight steps in the fixed {@link #KEYS} order (which also
     * keeps the token first and the case paper, counselling, billing and follow-up after the consultation).
     * Only vitals and counselling take an owner; an owner on any other step is refused, not dropped.
     */
    static List<WorkflowStep> validate(List<WorkflowStepInput> input) {
        if (input == null || input.size() != KEYS.size()) {
            throw new InvalidInputException("steps", "Send all 8 workflow steps");
        }
        List<WorkflowStep> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            WorkflowStepInput item = input.get(i);
            if (item == null || item.key() == null || !KEYS.contains(item.key())) {
                throw new InvalidInputException("steps", "Each step needs a valid key");
            }
            if (!seen.add(item.key())) {
                throw new InvalidInputException("steps", "Each step can appear only once");
            }
            if (!KEYS.get(i).equals(item.key())) {
                throw new InvalidInputException("steps", "Steps must be in the standard order");
            }
            if (item.on() == null) {
                throw new InvalidInputException("steps", "Choose on or off for every step");
            }
            if (ALWAYS_ON.contains(item.key()) && !item.on()) {
                throw new InvalidInputException("steps",
                        "Token, queue, consultation and billing cannot be switched off");
            }
            steps.add(new WorkflowStep(item.key(), item.on(), ownerFor(item)));
        }
        return steps;
    }

    private static String ownerFor(WorkflowStepInput item) {
        boolean hasOwnerSelector = "vitals".equals(item.key()) || "counsel".equals(item.key());
        if (!hasOwnerSelector) {
            if (item.owner() != null) {
                throw new InvalidInputException("steps", "Only the vitals and counselling steps have an owner");
            }
            return null;
        }
        if (item.owner() == null) {
            return "vitals".equals(item.key()) ? "NURSE" : "DOCTOR";
        }
        if (!OWNER_ROLES.contains(item.owner())) {
            throw new InvalidInputException("steps", "Owner must be Front desk, Nurse, Doctor or Owner");
        }
        return item.owner();
    }

    static List<WorkflowStepInput> toInput(List<WorkflowStep> steps) {
        return steps.stream().map(step -> new WorkflowStepInput(step.key(), step.on(), step.owner())).toList();
    }
}
