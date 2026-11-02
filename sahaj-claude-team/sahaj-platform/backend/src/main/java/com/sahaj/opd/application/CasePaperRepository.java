package com.sahaj.opd.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code opd.case_paper_settings}, one row per clinic created by the first save (SAH-7). Needs {@code app.tenant_id} bound. */
public interface CasePaperRepository {

    Optional<PaperRow> find(UUID tenantId);

    void upsert(UUID tenantId, PaperRow row, UUID actorUserId);

    /** {@code secondLanguage} is null (English only), {@code mr} or {@code hi}. */
    record PaperRow(String size, String secondLanguage, boolean preprinted, boolean caps,
                    Map<String, Boolean> sections) {
    }
}
