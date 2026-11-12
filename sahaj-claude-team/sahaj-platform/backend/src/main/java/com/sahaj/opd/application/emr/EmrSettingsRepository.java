package com.sahaj.opd.application.emr;

import java.util.Optional;
import java.util.UUID;

/** Port over this clinic's one EMR settings row (SAH-44, ADR-019 D11). Created on the first save. */
public interface EmrSettingsRepository {

    record SettingsRow(String sectionPanel, String rightPanelFirst, long version) {
    }

    Optional<SettingsRow> find(UUID tenantId);

    /** The first save. False when another owner created the row first (the caller answers 409). */
    boolean insert(UUID tenantId, String sectionPanel, String rightPanelFirst, UUID actorUserId);

    /** Updates only when the stored version equals {@code expectedVersion}; false otherwise (409). */
    boolean update(UUID tenantId, String sectionPanel, String rightPanelFirst, long expectedVersion,
                   UUID actorUserId);
}
