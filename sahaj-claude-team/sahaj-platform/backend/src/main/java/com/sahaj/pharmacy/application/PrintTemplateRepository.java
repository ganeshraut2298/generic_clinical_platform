package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.PrintDocType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code pharmacy.print_template_setting}: one row per printed document type once saved. */
public interface PrintTemplateRepository {

    /** Header lines are the non-empty ones from the top (line 2 only with line 1). */
    record Row(UUID id, PrintDocType docType, List<String> headerLines, String footerNote, String paperSize, long version) {
    }

    List<Row> list(UUID tenantId);

    Optional<Row> find(UUID tenantId, PrintDocType docType);

    UUID insert(UUID tenantId, UUID unitId, PrintDocType docType, List<String> headerLines, String footerNote,
                String paperSize, UUID actorUserId);

    /** Saves the template when its version is still {@code version}; false when it is not. */
    boolean update(UUID tenantId, UUID id, long version, List<String> headerLines, String footerNote, String paperSize,
                   UUID actorUserId);
}
