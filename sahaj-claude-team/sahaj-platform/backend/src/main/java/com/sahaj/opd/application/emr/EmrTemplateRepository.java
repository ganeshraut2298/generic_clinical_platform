package com.sahaj.opd.application.emr;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port over the global, read-only EMR template tables and the specialty alias list (SAH-44, ADR-019 D4, D6). No
 * tenant data here: every clinic reads the same rows. Versions never change once shipped.
 */
public interface EmrTemplateRepository {

    record TemplateHeader(String templateKey, int version, String name) {
    }

    /** One section of one template version. {@code part} is ASSESSMENT or TREATMENT; {@code optionsMaster} only for MASTER. */
    record SectionRow(String sectionKey, int position, String label, String part, boolean requiredDefault,
                      boolean hideable, String editorType, String iconKey, String optionsSource,
                      String optionsMaster) {
    }

    record OptionRow(String sectionKey, String optionKey, int position, String label, String style, boolean none) {
    }

    /** The newest version of each template, in the order gp, dental, paeds, homeo. */
    List<TemplateHeader> newestTemplates();

    /** The newest version of this template key; empty when the key has no template. */
    Optional<TemplateHeader> newest(String templateKey);

    /** The sections of this version in part then position order (ASSESSMENT first). */
    List<SectionRow> sections(String templateKey, int version);

    /** The chip options of this version, by section then position. */
    List<OptionRow> options(String templateKey, int version);

    /** The template key for a normalised specialty text (lower case, trimmed, single spaces), if listed. */
    Optional<String> templateKeyForAlias(String normalisedSpecialty);

    /** The number of rows in each master, for the "quick options" count of MASTER sections. */
    Map<EmrMaster, Long> masterCounts();
}
