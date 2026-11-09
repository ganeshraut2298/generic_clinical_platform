package com.sahaj.ipd.domain.card;

import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * SAH-37 (ADR-015 D3) the canonical form of a setup and the comparison of two setups, decided on the server. The
 * canonical form of a valid setup is: sections in the given order, fields in order within each, actions in order,
 * every label trimmed and Unicode NFC, and an empty label ("") when the label is the standard name (empty means the
 * standard name). Two setups are equal when their canonical forms are equal ({@link #equal}): never by comparing the
 * stored text, because the database reorders and reformats {@code jsonb}.
 *
 * <p>{@link #changedParts} is the mockup's {@code pcChanges} split: layout (layout or cards per row), sections (order
 * or on/off), fields (any field's section, order, on/off or label) and quickActions (order or on/off).
 */
public final class CardSetupCanonical {

    public static final String LAYOUT = "layout";
    public static final String SECTIONS = "sections";
    public static final String FIELDS = "fields";
    public static final String QUICK_ACTIONS = "quickActions";

    private CardSetupCanonical() {
    }

    /** The canonical form of a setup that passed {@link CardSetupValidator}. */
    public static CardSetup canonical(CardSetup setup, TextRules text) {
        List<Section> sections = new ArrayList<>();
        for (Section section : setup.sections()) {
            List<FieldPlacement> fields = new ArrayList<>();
            for (FieldPlacement field : section.fields()) {
                fields.add(new FieldPlacement(field.key(), field.on(), canonicalLabel(field.key(), field.label(), text)));
            }
            sections.add(new Section(section.key(), section.on(), List.copyOf(fields)));
        }
        return new CardSetup(setup.layout(), setup.cardsPerRow(), List.copyOf(sections),
                List.copyOf(setup.actions()));
    }

    /** Trimmed, NFC, and "" when it equals the standard name of the field. */
    static String canonicalLabel(String fieldKey, String raw, TextRules text) {
        if (raw == null) {
            return "";
        }
        String label = text.strip(Normalizer.normalize(raw, Normalizer.Form.NFC));
        return label.equals(CardCatalogue.fieldName(fieldKey)) ? "" : label;
    }

    /** True when the two setups have the same canonical form (both are already canonical). */
    public static boolean equal(CardSetup a, CardSetup b) {
        return Objects.equals(a, b);
    }

    /** The parts of {@code next} that differ from {@code live}, in the order layout, sections, fields, quickActions. */
    public static List<String> changedParts(CardSetup live, CardSetup next) {
        List<String> parts = new ArrayList<>();
        if (!live.layout().equals(next.layout()) || live.cardsPerRow() != next.cardsPerRow()) {
            parts.add(LAYOUT);
        }
        if (!sectionState(live).equals(sectionState(next))) {
            parts.add(SECTIONS);
        }
        if (!fieldPlacement(live).equals(fieldPlacement(next)) || !fieldState(live).equals(fieldState(next))) {
            parts.add(FIELDS);
        }
        if (!live.actions().equals(next.actions())) {
            parts.add(QUICK_ACTIONS);
        }
        return List.copyOf(parts);
    }

    /** Section order and on/off. */
    private static List<String> sectionState(CardSetup setup) {
        List<String> state = new ArrayList<>();
        for (Section section : setup.sections()) {
            state.add(section.key() + ":" + section.on());
        }
        return state;
    }

    /** Which fields sit in which section, in order (independent of the order of the sections). */
    private static Map<String, List<String>> fieldPlacement(CardSetup setup) {
        Map<String, List<String>> placement = new LinkedHashMap<>();
        for (Section section : setup.sections()) {
            List<String> keys = new ArrayList<>();
            for (FieldPlacement field : section.fields()) {
                keys.add(field.key());
            }
            placement.put(section.key(), keys);
        }
        return new TreeMap<>(placement);
    }

    /** On/off and label of every field. */
    private static Map<String, FieldPlacement> fieldState(CardSetup setup) {
        Map<String, FieldPlacement> state = new TreeMap<>();
        for (Section section : setup.sections()) {
            for (FieldPlacement field : section.fields()) {
                state.put(field.key(), field);
            }
        }
        return state;
    }
}
