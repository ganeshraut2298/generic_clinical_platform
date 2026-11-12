package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrSetupRepository.OverrideRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.OptionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.SectionRow;
import com.sahaj.opd.application.emr.EmrViews.SectionOptions;
import com.sahaj.opd.application.emr.EmrViews.SectionView;
import com.sahaj.opd.application.emr.EmrViews.SnapshotSection;
import com.sahaj.opd.application.emr.EmrViews.TemplateOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure rules of ADR-019 D5: the effective section is the template default, then this clinic's override if there is
 * one for that key. No override means shown, and required as the template says. A hidden section is never required,
 * and a section that cannot be hidden is always shown. Overrides whose key is not in the template version are ignored.
 */
public final class EmrTemplateComposer {

    private EmrTemplateComposer() {
    }

    /** The Show and Required of one section for one clinic. */
    public record Effective(boolean shown, boolean required) {
    }

    public static Map<String, OverrideRow> byKey(List<OverrideRow> overrides) {
        Map<String, OverrideRow> map = new HashMap<>();
        for (OverrideRow row : overrides) {
            map.put(row.sectionKey(), row);
        }
        return map;
    }

    /** What the template says before any override: shown, and required by default. */
    public static Effective templateDefault(SectionRow section) {
        return new Effective(true, section.requiredDefault());
    }

    public static Effective effective(SectionRow section, OverrideRow override) {
        if (override == null) {
            return templateDefault(section);
        }
        boolean shown = override.shown() || !section.hideable();
        return new Effective(shown, shown && override.required());
    }

    /** Every section of the version, hidden ones too, with the clinic's effective Show and Required. */
    public static List<SectionView> views(List<SectionRow> sections, List<OptionRow> options,
                                          Map<String, OverrideRow> overrides, Map<EmrMaster, Long> masterCounts,
                                          int tariffCount) {
        Map<String, List<TemplateOption>> chipsBySection = new HashMap<>();
        for (OptionRow option : options) {
            chipsBySection.computeIfAbsent(option.sectionKey(), key -> new ArrayList<>())
                    .add(new TemplateOption(option.optionKey(), option.label(), option.style(), option.none()));
        }
        List<SectionView> views = new ArrayList<>();
        for (SectionRow section : sections) {
            OverrideRow override = overrides.get(section.sectionKey());
            Effective effective = effective(section, override);
            List<TemplateOption> chips = "TEMPLATE".equals(section.optionsSource())
                    ? chipsBySection.getOrDefault(section.sectionKey(), List.of()) : List.of();
            views.add(new SectionView(section.sectionKey(), section.label(), section.part(), section.position(),
                    section.editorType(), section.iconKey(), optionsOf(section),
                    quickOptionCount(section, chips, masterCounts, tariffCount), chips, section.requiredDefault(),
                    section.hideable(), effective.shown(), effective.required(), override != null));
        }
        return views;
    }

    public static int sectionsOn(List<SectionView> views) {
        return (int) views.stream().filter(SectionView::shown).count();
    }

    public static int requiredOn(List<SectionView> views) {
        return (int) views.stream().filter(view -> view.shown() && view.required()).count();
    }

    /** Only the shown sections, in template order, with the effective required flag (ADR-019 D6). */
    public static List<SnapshotSection> snapshotSections(List<SectionRow> sections, Map<String, OverrideRow> overrides) {
        List<SnapshotSection> result = new ArrayList<>();
        for (SectionRow section : sections) {
            Effective effective = effective(section, overrides.get(section.sectionKey()));
            if (effective.shown()) {
                result.add(new SnapshotSection(section.sectionKey(), section.label(), section.part(),
                        section.position(), effective.required(), section.editorType(), section.iconKey(),
                        optionsOf(section)));
            }
        }
        return result;
    }

    private static SectionOptions optionsOf(SectionRow section) {
        return new SectionOptions(section.optionsSource(),
                "MASTER".equals(section.optionsSource()) ? section.optionsMaster() : null);
    }

    private static Integer quickOptionCount(SectionRow section, List<TemplateOption> chips,
                                            Map<EmrMaster, Long> masterCounts, int tariffCount) {
        switch (section.optionsSource()) {
            case "MASTER":
                return EmrMaster.fromKey(section.optionsMaster())
                        .map(master -> masterCounts.getOrDefault(master, 0L).intValue()).orElse(0);
            case "TEMPLATE":
                return chips.size();
            case "TARIFF":
                return tariffCount;
            default:
                return null;
        }
    }
}
