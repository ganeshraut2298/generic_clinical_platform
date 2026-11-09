package com.sahaj.ipd.application;

import com.sahaj.ipd.application.CardSetupRepository.DraftRow;
import com.sahaj.ipd.application.CardSetupRepository.VersionRow;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.ipd.domain.card.CardSetupCanonical;
import com.sahaj.ipd.domain.card.CardSetupDefaults;
import com.sahaj.ipd.domain.card.CardSetupRepair;
import com.sahaj.ipd.domain.card.ResolvedCardSetup;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-37 (ADR-015 D1, D3) reads the live setup of a clinic: the stored version with the highest number, parsed and
 * repaired in memory, or the Sahaj standard setup (v1, nothing stored) when the clinic never published. Reading never
 * creates a row. A stored row that cannot be parsed at all answers the standard setup with {@code fallback} true (the
 * Admitted page then shows "Could not load the card setup. Showing the standard card."). Shared by the setup service and
 * the Admitted list, which reads it in the same transaction as the rows (one snapshot, D4).
 */
@Component
class CardSetups {

    /** The live setup; {@code publishedBy} and {@code publishedAt} are null for v1 and for a fallback. */
    record Live(int version, boolean isStandard, boolean fallback, CardSetup setup, UUID publishedBy,
                Instant publishedAt) {

        ResolvedCardSetup resolved() {
            return ResolvedCardSetup.of(version, isStandard, fallback, setup);
        }
    }

    private final CardSetupRepository repository;

    CardSetups(CardSetupRepository repository) {
        this.repository = repository;
    }

    /** R7: one index probe (latest version) per call. */
    Live live(UUID tenantId) {
        Optional<VersionRow> row = repository.findLatestVersion(tenantId);
        return row.map(CardSetups::fromRow).orElseGet(CardSetups::standardV1);
    }

    static Live standardV1() {
        return new Live(1, true, false, CardSetupDefaults.standard(), null, null);
    }

    private static Live fromRow(VersionRow row) {
        Optional<CardSetup> parsed = CardSetupJson.read(row.setupJson());
        if (parsed.isEmpty()) {
            return new Live(row.version(), true, true, CardSetupDefaults.standard(), row.publishedBy(),
                    row.publishedAt());
        }
        CardSetup repaired = CardSetupRepair.repair(parsed.get());
        return new Live(row.version(), CardSetupCanonical.equal(repaired, CardSetupDefaults.standard()), false,
                repaired, row.publishedBy(), row.publishedAt());
    }

    /** A stored draft parsed and repaired; a draft that cannot be read is treated as the standard setup. */
    static CardSetup draftSetup(DraftRow draft) {
        return CardSetupJson.read(draft.setupJson()).map(CardSetupRepair::repair)
                .orElseGet(CardSetupDefaults::standard);
    }
}
