package com.sahaj.platform.application;

import com.sahaj.platform.domain.SetupProgress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.setup_progress} (SAH-4). Needs {@code app.tenant_id} bound. */
public interface SetupProgressRepository {

    /** The bound tenant's saved progress, or empty when the owner has not saved a step yet. */
    Optional<SetupProgress> find(UUID tenantId);

    /**
     * Saves {@code stepAnswers} under {@code stepKey} and moves {@code current_step} forward to
     * {@code step + 1} (capped at 5) if it was not already further along; never moves it back.
     */
    void saveStep(UUID tenantId, int step, String stepKey, Map<String, Object> stepAnswers);

    /** Sign-up: the first row for a new tenant (step 1, no answers). A second call changes nothing. */
    void createInitial(UUID tenantId);

    void markCompleted(UUID tenantId, Instant completedAt);

    /** True only when a progress row exists and its {@code completed_at} is set. */
    boolean isComplete(UUID tenantId);
}
