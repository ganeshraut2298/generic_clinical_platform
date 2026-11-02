package com.sahaj.platform.domain;

import java.time.Instant;
import java.util.Map;

/**
 * The owner's saved place in the setup wizard (SAH-4, {@code platform.setup_progress}).
 * {@code answers} is keyed by step name ({@code clinic}, {@code specialty}, {@code team},
 * {@code opd}, {@code look}); a step not yet saved is simply missing from the map.
 */
public record SetupProgress(int currentStep, Map<String, Object> answers, Instant completedAt) {

    public boolean isComplete() {
        return completedAt != null;
    }
}
