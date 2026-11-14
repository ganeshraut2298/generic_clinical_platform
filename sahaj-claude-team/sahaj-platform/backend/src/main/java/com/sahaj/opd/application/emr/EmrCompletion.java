package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import java.util.ArrayList;
import java.util.List;

/**
 * The Done rule, the part badges and the "Still needed" footer (ADR-020 D8, OQ-45-25, OQ-45-26), computed from the
 * record's layout snapshot and its values. The server's answer wins over the client's live recomputation.
 *
 * <p>A section is Done when every required field whose value kind is fillable (not display-only or later) has a
 * non-empty value; when it has no such required field, when at least one fillable field has a value; and never while a
 * copied value waits for confirmation. Status: DONE, else REQUIRED when the layout marks the section required, else
 * OPTIONAL.
 */
public final class EmrCompletion {

    private EmrCompletion() {
    }

    public record SectionProgress(String key, String status, boolean done, boolean copyPending) {
    }

    public record PartProgress(String part, int requiredDone, int requiredTotal, boolean complete, int sectionsDone,
                               int sectionsTotal) {
    }

    public record Progress(List<SectionProgress> sections, List<PartProgress> parts, List<String> stillNeeded,
                           boolean readyToComplete) {
    }

    /** A Done rule that replaces the generic one for a section (a specialty editor); null = use the generic rule. */
    public interface DoneOverride {
        Boolean done(LayoutSection section, EmrRecordDocument document);
    }

    public static Progress compute(List<LayoutSection> shownSections, EmrRecordDocument document) {
        return compute(shownSections, document, null);
    }

    public static Progress compute(List<LayoutSection> shownSections, EmrRecordDocument document,
                                   DoneOverride override) {
        List<SectionProgress> sections = new ArrayList<>();
        List<String> stillNeeded = new ArrayList<>();
        int[] requiredDone = new int[2];
        int[] requiredTotal = new int[2];
        int[] done = new int[2];
        int[] total = new int[2];
        for (LayoutSection section : shownSections) {
            int part = EmrModel.PART_TREATMENT.equals(section.part()) ? 1 : 0;
            boolean copyPending = document.copyPending(section.key());
            Boolean special = override == null ? null : override.done(section, document);
            boolean isDone = special != null ? special : !copyPending && isDone(section, document.values(section.key()));
            String status = isDone ? "DONE" : section.required() ? "REQUIRED" : "OPTIONAL";
            sections.add(new SectionProgress(section.key(), status, isDone, copyPending));
            total[part]++;
            if (isDone) {
                done[part]++;
            }
            if (section.required()) {
                requiredTotal[part]++;
                if (isDone) {
                    requiredDone[part]++;
                } else {
                    stillNeeded.add(section.key());
                }
            }
        }
        List<PartProgress> parts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            parts.add(new PartProgress(EmrModel.PARTS.get(i), requiredDone[i], requiredTotal[i],
                    requiredDone[i] == requiredTotal[i], done[i], total[i]));
        }
        return new Progress(List.copyOf(sections), List.copyOf(parts), List.copyOf(stillNeeded),
                stillNeeded.isEmpty());
    }

    static boolean isDone(LayoutSection section, JsonNode values) {
        if (section.formSection()) {
            // SAH-59: a clinic form is Done by the form's own fields (show-if hidden fields do not count).
            return section.form() != null && EmrFormValues.isDone(section.form(), values);
        }
        boolean anyRequired = false;
        boolean anyFilled = false;
        for (FieldDefinition field : section.fields()) {
            if (!field.fillable()) {
                continue;
            }
            boolean filled = !EmrValueRules.isEmpty(values.get(field.key()));
            if (field.required()) {
                anyRequired = true;
                if (!filled) {
                    return false;
                }
            }
            anyFilled = anyFilled || filled;
        }
        return anyRequired || anyFilled;
    }
}
