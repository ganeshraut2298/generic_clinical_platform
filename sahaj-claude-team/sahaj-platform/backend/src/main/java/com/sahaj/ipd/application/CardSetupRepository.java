package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.card_setup_version} and {@code ipd.card_setup_draft} (SAH-37, ADR-015 D1). Versions are append-only (2, 3,
 * ...; version 1 is the Sahaj standard setup built in code and has no row); the draft is at most one row per clinic.
 * Every read is scoped to the tenant; the database sets the clinic, the id and the publish time (the application never
 * sends them). The setup is a JSON text in the canonical form; the application parses and repairs it. No patient data.
 */
public interface CardSetupRepository {

    /** The live version: the row with the highest version, or empty when the clinic never published. */
    Optional<VersionRow> findLatestVersion(UUID tenantId);

    /** The newest {@code limit} versions first, without the setup text. */
    List<VersionHead> findVersionHeads(UUID tenantId, int limit);

    /** Appends version {@code version}; a clash on {@code (tenant, version)} is a {@code DuplicateKeyException}. */
    void insertVersion(UUID tenantId, int version, String setupJson, boolean isStandard, List<String> changedParts,
                       UUID publishedBy);

    Optional<DraftRow> findDraft(UUID tenantId);

    /** A new draft (revision 1); a clash on the clinic key is a {@code DuplicateKeyException}. */
    void insertDraft(UUID tenantId, String setupJson, int baseVersion, long revision, UUID savedBy);

    /** Replaces the draft of the clinic with the next revision and the time of the save. */
    void updateDraft(UUID tenantId, String setupJson, int baseVersion, long revision, UUID savedBy, Instant savedAt);

    /** True when a draft row was deleted. */
    boolean deleteDraft(UUID tenantId);

    /** The setup is owner-typed text: left out of {@code toString}. */
    record VersionRow(int version, String setupJson, boolean isStandard, List<String> changedParts, UUID publishedBy,
                      Instant publishedAt) {

        @Override
        public String toString() {
            return "VersionRow[version=" + version + "]";
        }
    }

    record VersionHead(int version, boolean isStandard, List<String> changedParts, UUID publishedBy,
                       Instant publishedAt) {
    }

    record DraftRow(String setupJson, int baseVersion, long revision, UUID savedBy, Instant savedAt) {

        @Override
        public String toString() {
            return "DraftRow[revision=" + revision + "]";
        }
    }
}
