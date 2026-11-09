package com.sahaj.ipd.domain.card;

import java.util.List;

/**
 * SAH-37 (ADR-015 D3) one card setup: the shape stored, edited and sent back whole ({@code CardSetup} of
 * {@code docs/api/ipd.yaml}). It holds no patient data. The strings are the keys of {@link CardCatalogue}; validation,
 * repair and equality are in {@link CardSetupValidator}, {@link CardSetupRepair} and {@link CardSetupCanonical}.
 *
 * <p>The labels are text the owner typed: the {@code toString} of {@code CardSetup}, {@link Section} and
 * {@link FieldPlacement} prints keys and switches only, never a label (security review SAH-37, finding 8).
 *
 * @param layout      compact, standard or expanded
 * @param cardsPerRow the default cards per row on a desktop: 2, 3 or 4
 * @param sections    in card order
 * @param actions     in card order
 */
public record CardSetup(String layout, int cardsPerRow, List<Section> sections, List<ActionPlacement> actions) {

    @Override
    public String toString() {
        return "CardSetup[layout=" + layout + ", cardsPerRow=" + cardsPerRow + ", sections="
                + (sections == null ? "null" : sections.size()) + ", actions="
                + (actions == null ? "null" : actions.size()) + "]";
    }

    /** One section: on or off, with its fields in card order. */
    public record Section(String key, boolean on, List<FieldPlacement> fields) {

        @Override
        public String toString() {
            return "Section[key=" + key + ", on=" + on + ", fields=" + (fields == null ? "null" : fields.size()) + "]";
        }
    }

    /** One field in a section. {@code label} "" keeps the standard name. */
    public record FieldPlacement(String key, boolean on, String label) {

        @Override
        public String toString() {
            return "FieldPlacement[key=" + key + ", on=" + on + "]";
        }
    }

    /** One quick action. */
    public record ActionPlacement(String key, boolean on) {
    }
}
