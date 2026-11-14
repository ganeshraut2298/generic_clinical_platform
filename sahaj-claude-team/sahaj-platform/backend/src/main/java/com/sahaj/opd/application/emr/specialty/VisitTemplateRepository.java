package com.sahaj.opd.application.emr.specialty;

import java.util.List;
import java.util.UUID;

/**
 * Port over {@code opd.emr_visit_template} (SAH-60-4, ADR-023 D10, H12): the clinic's visit templates, shared by its
 * doctors. Row level security limits every statement to the bound clinic. Templates are never deleted (a switch-off is the
 * {@code active} flag, a later story). The items are clinical text and master keys only, never patient data.
 */
public interface VisitTemplateRepository {

    /** {@code items} is the json text including {@code schemaVersion}. */
    record Row(UUID id, String name, String subLine, String items) {
    }

    /** Active templates by name, ignoring case. */
    List<Row> active(UUID tenantId);

    long countActive(UUID tenantId);

    /** True when an active or switched-off template of the clinic has this name ignoring case. */
    boolean nameExists(UUID tenantId, String name);

    void insert(UUID tenantId, Row row, UUID membershipId, UUID actorUserId);
}
