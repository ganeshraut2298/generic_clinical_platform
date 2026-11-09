package com.sahaj.ipd.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.ipd.domain.card.CardCatalogue;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SAH-37 response and request shapes of {@code docs/api/ipd.yaml} (tag SAH-37: {@code CardSetupView},
 * {@code CardLive}, {@code CardDraft}, {@code CardVersion}, {@code CardSetupWrite}). A setup holds owner-typed labels
 * and no patient data; nothing here is logged.
 */
public final class CardSetupViews {

    private CardSetupViews() {
    }

    /** Body of {@code PUT /card-setup/draft} and {@code POST /card-setup/publish}. Read strictly (no extra property). */
    public record CardSetupWrite(Integer baseVersion, Long draftRevision, CardSetup setup) {

        @Override
        public String toString() {
            return "CardSetupWrite[baseVersion=" + baseVersion + ", draftRevision=" + draftRevision + "]";
        }
    }

    /** The live setup; v1 is the Sahaj standard card (no date, no person). */
    public record CardLive(int version, boolean isStandard, CardSetup setup, Instant publishedAt,
                           String publishedByName) {
    }

    /** The owner's draft. */
    public record CardDraft(long revision, int baseVersion, CardSetup setup, Instant savedAt, String savedByName,
                            List<String> changedParts) {
    }

    /** One row of "Published versions". */
    public record CardVersion(int version, String summary, boolean isStandard, List<String> changedParts,
                              Instant publishedAt, String publishedByName, boolean live) {
    }

    /**
     * Answer of every {@code /card-setup} operation. {@code draft} and {@code versions} are set for the owner only; for
     * the other roles they are absent from the JSON (Java null), and for the owner a missing draft is {@code Optional.empty()},
     * which is written as {@code null}.
     */
    public record CardSetupView(CardCatalogue.Catalogue catalogue, CardSetup standard,
                                List<CardSetupDefaults.Template> templates, CardLive live,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Optional<CardDraft> draft,
                                @JsonInclude(JsonInclude.Include.NON_NULL) List<CardVersion> versions,
                                boolean canEdit, String readOnlyReason, String state) {
    }
}
