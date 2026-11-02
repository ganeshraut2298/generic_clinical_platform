package com.sahaj.opd.application;

/** One step of the OPD workflow (SAH-12). {@code owner} is a role code, or null for steps with no owner selector. */
public record WorkflowStep(String key, boolean on, String owner) {
}
