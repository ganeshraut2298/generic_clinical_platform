package com.sahaj.opd.application;

import java.time.Instant;
import java.util.List;

/** A published workflow version as the owner sees it (SAH-12). {@code publishedByName} may be null. */
public record WorkflowVersionView(int versionNo, List<WorkflowStep> steps, Instant publishedAt,
                                   String publishedByName) {
}
