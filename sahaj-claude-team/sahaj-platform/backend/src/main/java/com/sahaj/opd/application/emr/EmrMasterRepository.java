package com.sahaj.opd.application.emr;

import java.util.List;
import java.util.UUID;

/**
 * Port over the global, read-only complaint, investigation and advice masters and the usage-ranked quick options of
 * all five masters (SAH-43, ADR-019 D3, D10). Only the ranking needs {@code app.tenant_id} bound.
 */
public interface EmrMasterRepository {

    /** {@code nameMr} and {@code nameHi} are set for the advice master only (ADR-020 D13). */
    record MasterItemRow(UUID id, String name, String specialtyHint, String nameMr, String nameHi) {
    }

    record MasterPageRow(List<MasterItemRow> items, long total) {
    }

    /** One ranked item. {@code icd10Code}, {@code form} and {@code favourite} are set for their own master only. */
    record QuickOptionRow(String itemKey, String name, String icd10Code, String form, Boolean favourite,
                          int useCount, java.time.Instant lastUsedAt, String nameMr, String nameHi) {
    }

    /**
     * Items whose name starts with {@code pattern} (a LIKE pattern already escaped by
     * {@link com.sahaj.opd.application.SearchPrefix}), the {@code specialty} hint first when given, then sort order,
     * then name. {@code master} must be a searchable one.
     */
    MasterPageRow search(EmrMaster master, String pattern, String specialty, int limit, int offset);

    /**
     * At most {@code limit} items of {@code master} for this doctor in this clinic: used items by use count then last
     * used, then never-used items in the master's own order ({@code specialty} hint first when given; for medicines
     * the clinic's favourites first).
     */
    List<QuickOptionRow> ranked(UUID tenantId, UUID doctorMembershipId, EmrMaster master, String specialty, int limit);
}
