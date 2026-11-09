package com.sahaj.ipd.domain.card;

import java.util.List;

/**
 * SAH-37 (ADR-015 D3) one card setup: the shape stored, edited and sent back whole ({@code CardSetup} of
 * {@code docs/api/ipd.yaml}). It holds no patient data. The strings are the keys of {@link CardCatalogue}; validation,
 * repair and equality are in {@link CardSetupValidator}, {@link CardSetupRepair} and {@link CardSetupCanonical}.
 *
 * @param layout      compact, standard or expanded
 * @param cardsPerRow the default cards per row on a desktop: 2, 3 or 4
 * @param sections    in card order
 * @param actions     in card order
 */
public record CardSetup(String layout, int cardsPerRow, List<Section> sections, List<ActionPlacement> actions) {

    /** One section: on or off, with its fields in card order. */
    public record Section(String key, boolean on, List<FieldPlacement> fields) {
    }

    /** One field in a section. {@code label} "" keeps the standard name. */
    public record FieldPlacement(String key, boolean on, String label) {
    }

    /** One quick action. */
    public record ActionPlacement(String key, boolean on) {
    }
}
