package com.sahaj.ipd.domain.card;

import com.sahaj.ipd.domain.card.CardSetup.ActionPlacement;
import com.sahaj.ipd.domain.card.CardSetup.FieldPlacement;
import com.sahaj.ipd.domain.card.CardSetup.Section;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SAH-37 (ADR-015 D3, amendment A4) the rules of Save draft and Publish, V1 to V15 of
 * {@code docs/architecture/SAH-37-patient-card-setup.md} section 4. All errors are returned together, in the order of
 * the rule numbers, each with the JSON path of the body ({@code setup.sections[2].on}); the messages are fixed
 * plain English that never echoes what was typed. Pure: no Spring, no I/O.
 *
 * <p>This is the protection of the locked safety items (rule 9): a setup that lacks, hides, renames or misplaces one is
 * refused, whatever the browser does.
 */
public final class CardSetupValidator {

    /** One broken rule: the rule number (for the order), the path in the body and the message. */
    public record Problem(int rule, String path, String message) {
    }

    private static final String LABEL_LONG = "Keep the label to 40 characters.";
    private static final String LABEL_CONTROL = "Label cannot contain control or text-direction characters";
    private static final String NAME_FIRST = "Patient name stays first in the Card header.";
    private static final String NAME_USED = "This name is already used by another field.";

    /** V15: the comparison key of the standard name of every catalogue field (always reserved), by field key. */
    private static final Map<String, String> STANDARD_KEYS = standardKeys();

    private CardSetupValidator() {
    }

    private static Map<String, String> standardKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (CardCatalogue.FieldDef def : CardCatalogue.FIELDS) {
            keys.put(def.key(), nameKey(def.name()));
        }
        return Map.copyOf(keys);
    }

    /** The problems of {@code setup}; empty when it is valid. */
    public static List<Problem> validate(CardSetup setup, TextRules text) {
        List<Problem> errors = new ArrayList<>();
        layout(setup, errors);
        List<Section> sections = setup.sections() == null ? List.of() : setup.sections();
        sections(sections, errors);
        fields(sections, text, errors);
        actions(setup.actions() == null ? List.of() : setup.actions(), errors);
        errors.sort(Comparator.comparingInt(Problem::rule));
        return List.copyOf(errors);
    }

    private static void layout(CardSetup setup, List<Problem> errors) {
        if (setup.layout() == null || !CardCatalogue.LAYOUTS.contains(setup.layout())) {
            errors.add(new Problem(1, "setup.layout", "Choose Compact, Standard or Expanded."));
        }
        if (!CardCatalogue.CARDS_PER_ROW.contains(setup.cardsPerRow())) {
            errors.add(new Problem(2, "setup.cardsPerRow", "Choose 2, 3 or 4 cards per row."));
        }
    }

    /** V3, V4, V5. */
    private static void sections(List<Section> sections, List<Problem> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            String path = "setup.sections[" + i + "]";
            if (section == null || section.key() == null || CardCatalogue.section(section.key()).isEmpty()) {
                errors.add(new Problem(3, path + ".key", "Unknown section."));
                continue;
            }
            if (!seen.add(section.key())) {
                errors.add(new Problem(3, path + ".key", "Each section can appear only once."));
            }
        }
        for (CardCatalogue.SectionDef def : CardCatalogue.SECTIONS) {
            if (!seen.contains(def.key())) {
                errors.add(new Problem(3, "setup.sections", "The " + def.name() + " section is missing."));
            }
        }
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (section == null || section.key() == null) {
                continue;
            }
            String path = "setup.sections[" + i + "]";
            if (CardCatalogue.HEADER.equals(section.key())) {
                if (i != 0) {
                    errors.add(new Problem(4, path + ".key", headerMessage()));
                } else if (!section.on()) {
                    errors.add(new Problem(4, path + ".on", headerMessage()));
                }
            } else if (!section.on() && CardCatalogue.section(section.key()).map(
                    CardCatalogue.SectionDef::safety).orElse(false)) {
                errors.add(new Problem(5, path + ".on", "The " + CardCatalogue.sectionName(section.key())
                        + " section is a safety section and stays on."));
            }
        }
    }

    private static String headerMessage() {
        return "The Card header section is always first and always on.";
    }

    /** V6 to V12, V14 and, last, V15. */
    private static void fields(List<Section> sections, TextRules text, List<Problem> errors) {
        Set<String> seen = new HashSet<>();
        List<LabelSeen> labels = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (section == null) {
                continue;
            }
            List<FieldPlacement> placements = section.fields() == null ? List.of() : section.fields();
            for (int j = 0; j < placements.size(); j++) {
                FieldPlacement field = placements.get(j);
                String path = "setup.sections[" + i + "].fields[" + j + "]";
                if (field == null || field.key() == null || CardCatalogue.field(field.key()).isEmpty()) {
                    errors.add(new Problem(6, path + ".key", "Unknown field."));
                    continue;
                }
                if (!seen.add(field.key())) {
                    errors.add(new Problem(6, path + ".key", "Each field can appear only once."));
                    continue;
                }
                String label = field(CardCatalogue.field(field.key()).get(), field, section, i, j, path, text,
                        errors);
                labels.add(new LabelSeen(field.key(), path + ".label", label));
            }
        }
        for (CardCatalogue.FieldDef def : CardCatalogue.FIELDS) {
            if (!seen.contains(def.key())) {
                errors.add(new Problem(6, "setup.sections", def.name() + " is missing from the card setup."));
            }
        }
        sameName(labels, errors);
    }

    /**
     * The label of one placed field as the validator read it: {@code label} is the trimmed NFC text, or null when the
     * label raised V7, V10, V11, V12 or V14 (then V15 does not look at it).
     */
    private record LabelSeen(String fieldKey, String path, String label) {
    }

    /**
     * V15 (DEC-032, G-4): a label may not duplicate or impersonate another field. Checked only for a label that is not
     * empty, raised no other error and is not the field's own standard name (in any case or spacing). It fails when its
     * comparison key is the standard name of any other catalogue field, or the label of a field placed before it; the
     * later field gets the error. At most one error per label path; the message never echoes the typed text.
     */
    private static void sameName(List<LabelSeen> labels, List<Problem> errors) {
        Set<String> earlier = new HashSet<>();
        for (LabelSeen item : labels) {
            if (item.label() == null || item.label().isEmpty()) {
                continue;
            }
            String key = nameKey(item.label());
            if (key.isEmpty()) {
                continue;
            }
            boolean own = key.equals(STANDARD_KEYS.get(item.fieldKey()));
            if (!own && (STANDARD_KEYS.containsValue(key) || earlier.contains(key))) {
                errors.add(new Problem(15, item.path(), NAME_USED));
            }
            earlier.add(key);
        }
    }

    /**
     * The comparison key of V15 (design note 4.1): NFC; every run of white space or space separators becomes one
     * space; no space at either end; upper case then lower case without a locale; NFC again. The browser uses the
     * same steps.
     */
    static String nameKey(String text) {
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder spaced = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < nfc.length(); ) {
            int cp = nfc.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR) {
                gap = spaced.length() > 0;
            } else {
                if (gap) {
                    spaced.append(' ');
                    gap = false;
                }
                spaced.appendCodePoint(cp);
            }
        }
        String folded = spaced.toString().toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(folded, Normalizer.Form.NFC);
    }

    /** Returns the label to compare for V15, or null when the label raised an error of its own. */
    private static String field(CardCatalogue.FieldDef def, FieldPlacement field, Section section, int sectionIndex,
                                int fieldIndex, String path, TextRules text, List<Problem> errors) {
        boolean sectionOk = section.key() != null && CardCatalogue.section(section.key()).isPresent();
        if (def.required()) {
            boolean placed = CardCatalogue.HEADER.equals(section.key()) && sectionIndex == 0 && fieldIndex == 0;
            if (!placed) {
                errors.add(new Problem(7, path + ".key", NAME_FIRST));
            }
            if (!field.on()) {
                errors.add(new Problem(7, path + ".on", NAME_FIRST));
            }
        } else if (def.safety()) {
            if (!field.on()) {
                errors.add(new Problem(8, path + ".on", def.name() + " is a safety field and cannot be hidden."));
            }
            if (sectionOk && !section.on()) {
                errors.add(new Problem(9, path + ".key", def.name()
                        + " is a safety field. Move it to a section that is switched on."));
            }
        }
        return label(def, field, path, text, errors);
    }

    /** V7 (name), V10, V11, V12, V14 for one label; the trimmed label when none of them fired, else null. */
    private static String label(CardCatalogue.FieldDef def, FieldPlacement field, String path, TextRules text,
                                List<Problem> errors) {
        String raw = field.label() == null ? "" : field.label();
        if (raw.length() > CardCatalogue.LABEL_RAW_MAX) {
            errors.add(new Problem(14, path + ".label", LABEL_LONG));
            return null;
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (text.hasControlOrBidi(nfc)) {
            errors.add(new Problem(12, path + ".label", LABEL_CONTROL));
            return null;
        }
        String label = text.strip(nfc);
        if (label.codePointCount(0, label.length()) > CardCatalogue.LABEL_MAX) {
            errors.add(new Problem(11, path + ".label", LABEL_LONG));
            return null;
        }
        boolean changed = !label.isEmpty() && !label.equals(def.name());
        if (def.required() && changed) {
            errors.add(new Problem(7, path + ".label", NAME_FIRST));
            return null;
        } else if (def.safety() && changed) {
            errors.add(new Problem(10, path + ".label", "The label of " + def.name() + " cannot be changed."));
            return null;
        }
        return label;
    }

    /** V13. */
    private static void actions(List<ActionPlacement> actions, List<Problem> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < actions.size(); i++) {
            ActionPlacement action = actions.get(i);
            String path = "setup.actions[" + i + "]";
            if (action == null || action.key() == null || CardCatalogue.action(action.key()).isEmpty()) {
                errors.add(new Problem(13, path + ".key", "Unknown quick action."));
                continue;
            }
            if (!seen.add(action.key())) {
                errors.add(new Problem(13, path + ".key", "Each quick action can appear only once."));
            }
        }
        for (CardCatalogue.ActionDef def : CardCatalogue.ACTIONS) {
            if (!seen.contains(def.key())) {
                errors.add(new Problem(13, "setup.actions", "The " + def.name() + " quick action is missing."));
            }
        }
    }
}
