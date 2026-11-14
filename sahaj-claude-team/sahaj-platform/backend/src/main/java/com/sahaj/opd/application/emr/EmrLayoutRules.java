package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrInputs.SectionSettingsInput;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure rules for a department layout (ADR-020 D6). The same checks run for Save draft, Publish and the optional
 * sections of the Add department wizard, and a request is accepted or refused as a whole. Messages are fixed plain
 * English that never echo what was sent.
 */
public final class EmrLayoutRules {

    public static final int MAX_SECTIONS = 120;

    private EmrLayoutRules() {
    }

    /**
     * Validates a body against the department's section set and returns the settings in body order with the
     * "who can fill" roles in their fixed order.
     *
     * <p>Order of refusals: a body that is not well formed (400 validation with field errors); an unknown section key
     * (404 section-not-found); a missing or repeated key (400 section-set-mismatch); then, section by section in body
     * order, hidden and required (400 hidden-section-required) and hiding a locked section (400 section-not-hideable).
     */
    public static List<SectionSettings> validate(List<SectionSettingsInput> input, List<LayoutSection> base) {
        List<FieldMessage> errors = new ArrayList<>();
        if (input == null || input.isEmpty() || input.size() > MAX_SECTIONS) {
            throw new InvalidFieldsException(
                    List.of(new FieldMessage("sections", "Send the sections of the department.")));
        }
        List<SectionSettings> settings = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            SectionSettingsInput s = input.get(i);
            if (s == null) {
                errors.add(new FieldMessage("sections[" + i + "]", "Send every section."));
                continue;
            }
            int before = errors.size();
            if (!EmrKeys.isWellFormedSectionKey(s.key())) {
                errors.add(new FieldMessage("sections[" + i + "].key", "Choose a valid section."));
            }
            if (s.part() == null || !EmrModel.PARTS.contains(s.part())) {
                errors.add(new FieldMessage("sections[" + i + "].part", "Choose Assessment or Treatment."));
            }
            requireBoolean(errors, i, "shown", s.shown());
            requireBoolean(errors, i, "required", s.required());
            requireBoolean(errors, i, "print", s.print());
            requireBoolean(errors, i, "copy", s.copy());
            requireBoolean(errors, i, "collapsed", s.collapsed());
            List<String> roles = s.whoCanFill();
            if (roles == null || roles.size() > EmrModel.FILLER_ROLES.size()
                    || new HashSet<>(roles).size() != roles.size() || !EmrModel.FILLER_ROLES.containsAll(roles)) {
                errors.add(new FieldMessage("sections[" + i + "].whoCanFill",
                        "Choose from Doctor, Nurse and Reception."));
            }
            if (errors.size() == before) {
                settings.add(new SectionSettings(s.key(), s.part(), s.shown(), s.required(), s.print(), s.copy(),
                        s.collapsed(), EmrLayoutJson.canonicalRoles(roles)));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        Map<String, LayoutSection> baseByKey = new HashMap<>();
        for (LayoutSection section : base) {
            baseByKey.put(section.key(), section);
        }
        for (SectionSettings s : settings) {
            if (!baseByKey.containsKey(s.key())) {
                throw EmrProblemException.sectionNotFound();
            }
        }
        Set<String> seen = new HashSet<>();
        for (SectionSettings s : settings) {
            if (!seen.add(s.key())) {
                throw EmrProblemException.sectionSetMismatch();
            }
        }
        if (seen.size() != baseByKey.size()) {
            throw EmrProblemException.sectionSetMismatch();
        }
        for (SectionSettings s : settings) {
            if (!s.shown() && s.required()) {
                throw EmrProblemException.hiddenSectionRequired();
            }
            LayoutSection definition = baseByKey.get(s.key());
            if (!s.shown() && definition.hideLocked()) {
                throw EmrProblemException.sectionNotHideable(definition.name());
            }
        }
        return List.copyOf(settings);
    }

    private static void requireBoolean(List<FieldMessage> errors, int index, String name, Boolean value) {
        if (value == null) {
            errors.add(new FieldMessage("sections[" + index + "]." + name, "Choose on or off."));
        }
    }

    /** The settings of a layout, in layout order. */
    public static List<SectionSettings> settings(List<LayoutSection> layout) {
        List<SectionSettings> result = new ArrayList<>();
        for (LayoutSection section : layout) {
            result.add(section.settings());
        }
        return List.copyOf(result);
    }

    /** The definitions in the order and with the settings of {@code settings}; positions are 1-based array order. */
    public static List<LayoutSection> apply(List<SectionSettings> settings, List<LayoutSection> definitions) {
        Map<String, LayoutSection> byKey = new HashMap<>();
        for (LayoutSection definition : definitions) {
            byKey.put(definition.key(), definition);
        }
        List<LayoutSection> result = new ArrayList<>();
        int position = 1;
        for (SectionSettings s : settings) {
            LayoutSection definition = byKey.get(s.key());
            if (definition == null) {
                throw EmrProblemException.sectionNotFound();
            }
            result.add(definition.withSettings(s, position++));
        }
        return List.copyOf(result);
    }

    public static int sectionsOn(List<SectionSettings> settings) {
        int count = 0;
        for (SectionSettings s : settings) {
            if (s.shown()) {
                count++;
            }
        }
        return count;
    }

    /** Required sections that are shown (BR45-10: the counts are of shown sections only). */
    public static int requiredOn(List<SectionSettings> settings) {
        int count = 0;
        for (SectionSettings s : settings) {
            if (s.shown() && s.required()) {
                count++;
            }
        }
        return count;
    }

    /** "Also create a matching print template" unticked: every Print flag cleared. */
    public static List<SectionSettings> clearPrint(List<SectionSettings> settings) {
        List<SectionSettings> result = new ArrayList<>();
        for (SectionSettings s : settings) {
            result.add(new SectionSettings(s.key(), s.part(), s.shown(), s.required(), false, s.copy(),
                    s.collapsed(), s.whoCanFill()));
        }
        return List.copyOf(result);
    }

    /**
     * The audit change list (ADR-020 D12): one entry per section and property that differs, with the old and new
     * value. Setup values only. A section missing on the old side lists every property.
     */
    public static List<Map<String, Object>> changes(List<SectionSettings> before, List<SectionSettings> after) {
        Map<String, SectionSettings> old = new LinkedHashMap<>();
        for (SectionSettings s : before) {
            old.put(s.key(), s);
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        for (SectionSettings now : after) {
            SectionSettings was = old.get(now.key());
            addChange(changes, now.key(), "part", was == null ? null : was.part(), now.part());
            addChange(changes, now.key(), "shown", was == null ? null : was.shown(), now.shown());
            addChange(changes, now.key(), "required", was == null ? null : was.required(), now.required());
            addChange(changes, now.key(), "print", was == null ? null : was.print(), now.print());
            addChange(changes, now.key(), "copy", was == null ? null : was.copy(), now.copy());
            addChange(changes, now.key(), "collapsed", was == null ? null : was.collapsed(), now.collapsed());
            addChange(changes, now.key(), "whoCanFill", was == null ? null : was.whoCanFill(), now.whoCanFill());
        }
        return changes;
    }

    private static void addChange(List<Map<String, Object>> changes, String key, String property, Object before,
                                  Object after) {
        if (before == null || !before.equals(after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("sectionKey", key);
            change.put("property", property);
            change.put("before", before);
            change.put("after", after);
            changes.add(change);
        }
    }

    /** Keys whose position in the order differs between the two layouts. */
    public static List<String> moved(List<SectionSettings> before, List<SectionSettings> after) {
        Map<String, Integer> oldPosition = new HashMap<>();
        for (int i = 0; i < before.size(); i++) {
            oldPosition.put(before.get(i).key(), i);
        }
        List<String> moved = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) {
            Integer was = oldPosition.get(after.get(i).key());
            if (was != null && was != i) {
                moved.add(after.get(i).key());
            }
        }
        return moved;
    }

    /** "Sections on" and "required on", for the reset audit entry. */
    public static Map<String, Object> counts(List<SectionSettings> settings) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("sectionsOn", sectionsOn(settings));
        counts.put("requiredOn", requiredOn(settings));
        return counts;
    }
}
