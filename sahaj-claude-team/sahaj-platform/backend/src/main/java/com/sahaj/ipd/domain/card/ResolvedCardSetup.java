package com.sahaj.ipd.domain.card;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SAH-37 (ADR-015 D4, D8) the published setup ready to draw, as {@code AdmissionList.cardSetup}: repaired, only the
 * sections and fields that are on, labels resolved (the owner's label, or the standard name), the data group and the
 * safety flag per field, and the quick actions that are on, in order. A field is drawn only when its group is in
 * {@code card.visibleGroups} and an action only when its ability is true: the setup never grants anything. Holds no
 * patient data.
 */
public record ResolvedCardSetup(int version, boolean isStandard, boolean fallback, String layout, int cardsPerRow,
                                List<ResolvedSection> sections, List<String> actions) {

    public record ResolvedSection(String key, String name, List<ResolvedField> fields) {
    }

    public record ResolvedField(String key, String label, String group, boolean safety) {
    }

    /** Resolves a repaired setup. {@code isStandard} is the equality of the canonical form with the standard setup. */
    public static ResolvedCardSetup of(int version, boolean isStandard, boolean fallback, CardSetup setup) {
        List<ResolvedSection> sections = new ArrayList<>();
        for (Section section : setup.sections()) {
            if (!section.on()) {
                continue;
            }
            List<ResolvedField> fields = new ArrayList<>();
            for (FieldPlacement placement : section.fields()) {
                if (!placement.on()) {
                    continue;
                }
                CardCatalogue.FieldDef def = CardCatalogue.field(placement.key()).orElseThrow();
                String label = placement.label() == null || placement.label().isEmpty() || def.locked()
                        ? def.name() : placement.label();
                fields.add(new ResolvedField(def.key(), label, def.group(), def.safety()));
            }
            sections.add(new ResolvedSection(section.key(), CardCatalogue.sectionName(section.key()),
                    List.copyOf(fields)));
        }
        List<String> actions = new ArrayList<>();
        for (ActionPlacement action : setup.actions()) {
            if (action.on()) {
                actions.add(action.key());
            }
        }
        return new ResolvedCardSetup(version, isStandard, fallback, setup.layout(), setup.cardsPerRow(),
                List.copyOf(sections), List.copyOf(actions));
    }

    /** The keys of the fields that are on in a section that is on. */
    public Set<String> shownFields() {
        Set<String> keys = new HashSet<>();
        for (ResolvedSection section : sections) {
            for (ResolvedField field : section.fields()) {
                keys.add(field.key());
            }
        }
        return keys;
    }
}
