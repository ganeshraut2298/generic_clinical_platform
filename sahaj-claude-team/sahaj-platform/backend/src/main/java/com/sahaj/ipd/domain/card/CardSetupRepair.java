package com.sahaj.ipd.domain.card;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SAH-37 (ADR-015 D3) repair of a stored setup on every read, in memory only: the stored row is never rewritten. It
 * makes a setup that was valid when it was saved safe again after the catalogue grew or a row was damaged: unknown
 * keys are dropped, a repeated key is kept once, catalogue items that are missing are added (a new field arrives off,
 * in its standard section; a locked item arrives on), locked sections and fields are forced on, the Card header goes
 * first and Patient name first in it, the label of a locked field is cleared, and a locked field that sits in a
 * switched-off section goes back to its standard section. The result is a setup that passes
 * {@link CardSetupValidator}. Pure: no Spring, no I/O.
 */
public final class CardSetupRepair {

    private CardSetupRepair() {
    }

    public static CardSetup repair(CardSetup stored) {
        CardSetup standard = CardSetupDefaults.standard();
        String layout = stored.layout() != null && CardCatalogue.LAYOUTS.contains(stored.layout())
                ? stored.layout() : standard.layout();
        int perRow = CardCatalogue.CARDS_PER_ROW.contains(stored.cardsPerRow()) ? stored.cardsPerRow()
                : standard.cardsPerRow();
        return new CardSetup(layout, perRow, sections(stored.sections() == null ? List.of() : stored.sections()),
                actions(stored.actions() == null ? List.of() : stored.actions()));
    }

    private static List<Section> sections(List<Section> stored) {
        // 1. known sections, once each, in stored order; the fields of a dropped duplicate are not lost: they are
        //    collected below with all others.
        Map<String, Boolean> on = new LinkedHashMap<>();
        Map<String, List<String>> order = new LinkedHashMap<>();
        Map<String, FieldPlacement> fields = new LinkedHashMap<>();
        for (Section section : stored) {
            if (section == null || section.key() == null || CardCatalogue.section(section.key()).isEmpty()
                    || on.containsKey(section.key())) {
                continue;
            }
            on.put(section.key(), section.on());
            List<String> keys = new ArrayList<>();
            for (FieldPlacement field : section.fields() == null ? List.<FieldPlacement>of() : section.fields()) {
                if (field == null || field.key() == null || CardCatalogue.field(field.key()).isEmpty()
                        || fields.containsKey(field.key())) {
                    continue;
                }
                fields.put(field.key(), field);
                keys.add(field.key());
            }
            order.put(section.key(), keys);
        }
        // 2. missing sections are added at their standard position (relative to the known ones)
        List<String> sectionOrder = new ArrayList<>(on.keySet());
        for (CardCatalogue.SectionDef def : CardCatalogue.SECTIONS) {
            if (!on.containsKey(def.key())) {
                int at = Math.min(CardCatalogue.sectionKeys().indexOf(def.key()), sectionOrder.size());
                sectionOrder.add(at, def.key());
                on.put(def.key(), def.locked() || def.safety());
                order.put(def.key(), new ArrayList<>());
            }
        }
        // 3. locked sections on, the Card header first
        sectionOrder.remove(CardCatalogue.HEADER);
        sectionOrder.add(0, CardCatalogue.HEADER);
        for (CardCatalogue.SectionDef def : CardCatalogue.SECTIONS) {
            if (def.locked() || def.safety()) {
                on.put(def.key(), true);
            }
        }
        // 4. fields: missing ones are added to their standard section (off, or on when locked); a locked field is
        //    on, unlabelled and in a section that is on; Patient name is first in the Card header
        Set<String> placed = new HashSet<>(fields.keySet());
        for (CardCatalogue.FieldDef def : CardCatalogue.FIELDS) {
            if (!placed.contains(def.key())) {
                fields.put(def.key(), new FieldPlacement(def.key(), def.locked(), ""));
                order.get(def.standardSection()).add(def.key());
            }
        }
        for (CardCatalogue.FieldDef def : CardCatalogue.FIELDS) {
            if (!def.locked()) {
                continue;
            }
            String home = sectionOf(order, def.key());
            if (def.required()) {
                order.get(home).remove(def.key());
                order.get(CardCatalogue.HEADER).add(0, def.key());
            } else if (!on.get(home)) {
                order.get(home).remove(def.key());
                order.get(def.standardSection()).add(def.key());
            }
        }
        List<Section> result = new ArrayList<>();
        for (String sectionKey : sectionOrder) {
            List<FieldPlacement> placements = new ArrayList<>();
            for (String fieldKey : order.get(sectionKey)) {
                placements.add(placement(fields.get(fieldKey)));
            }
            result.add(new Section(sectionKey, on.get(sectionKey), List.copyOf(placements)));
        }
        return List.copyOf(result);
    }

    private static String sectionOf(Map<String, List<String>> order, String fieldKey) {
        for (Map.Entry<String, List<String>> entry : order.entrySet()) {
            if (entry.getValue().contains(fieldKey)) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("A catalogue field was not placed: " + fieldKey);
    }

    private static FieldPlacement placement(FieldPlacement stored) {
        CardCatalogue.FieldDef def = CardCatalogue.field(stored.key()).orElseThrow();
        if (def.locked()) {
            return new FieldPlacement(def.key(), true, "");
        }
        return new FieldPlacement(def.key(), stored.on(), label(stored.label(), def.name()));
    }

    /** A stored label is NFC, trimmed, free of control characters and at most 40 code points. */
    private static String label(String stored, String standardName) {
        if (stored == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        Normalizer.normalize(stored, Normalizer.Form.NFC).codePoints()
                .filter(cp -> !Character.isISOControl(cp)).limit(CardCatalogue.LABEL_RAW_MAX)
                .forEach(clean::appendCodePoint);
        String text = clean.toString().strip();
        if (text.codePointCount(0, text.length()) > CardCatalogue.LABEL_MAX) {
            text = text.substring(0, text.offsetByCodePoints(0, CardCatalogue.LABEL_MAX)).strip();
        }
        return text.equals(standardName) ? "" : text;
    }

    private static List<ActionPlacement> actions(List<ActionPlacement> stored) {
        Map<String, Boolean> on = new LinkedHashMap<>();
        for (ActionPlacement action : stored) {
            if (action != null && action.key() != null && CardCatalogue.action(action.key()).isPresent()
                    && !on.containsKey(action.key())) {
                on.put(action.key(), action.on());
            }
        }
        for (String key : CardCatalogue.actionKeys()) {
            on.putIfAbsent(key, false);
        }
        List<ActionPlacement> result = new ArrayList<>();
        on.forEach((key, value) -> result.add(new ActionPlacement(key, value)));
        return List.copyOf(result);
    }
}
