package com.sahaj.opd.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The single {@code opd.opd_settings} column the workflow needs: whether the clinic chose, at setup
 * (SAH-4 {@code vitals_on}), to have a nurse record vitals before the consultation. SAH-12 uses it
 * only to seed a clinic's first workflow version.
 */
public interface VisitWorkflowSettingsRepository {

    /** Empty when the clinic has no settings row yet. */
    Optional<Boolean> vitalsOn(UUID tenantId);
}
