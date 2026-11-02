package com.sahaj.opd.application;

/** A step as submitted by the owner (SAH-12); every field is checked by {@code WorkflowRules.validate}. */
public record WorkflowStepInput(String key, Boolean on, String owner) {
}
