package com.sahaj.opd.application.emr;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The EMR answers of SAH-43 and SAH-44 as the application layer hands them to the web layer (docs/api/opd.yaml 0.2.0). */
public final class EmrViews {

    private EmrViews() {
    }

    /** {@code source} is MASTER, TEMPLATE, TARIFF or NONE; {@code master} is set only for MASTER. */
    public record SectionOptions(String source, String master) {
    }

    public record TemplateOption(String key, String label, String style, boolean isNone) {
    }

    public record SectionView(String key, String label, String part, int position, String editorType, String iconKey,
                              SectionOptions options, Integer quickOptionCount, List<TemplateOption> templateOptions,
                              boolean templateRequired, boolean hideable, boolean shown, boolean required,
                              boolean overridden) {
    }

    public record TemplateSummary(String templateKey, String name, int version, boolean clinicSpecialty,
                                  int sectionCount, int sectionsOn, int requiredOn, boolean hasOverrides) {
    }

    public record TemplateView(String templateKey, String name, int version, boolean clinicSpecialty, long revision,
                               boolean canEdit, int sectionsOn, int requiredOn, List<SectionView> sections) {
    }

    /** BR15: DOCTOR_SPECIALTY, CLINIC_SPECIALTY, DEFAULT, or SWITCHED (a visit snapshot only). */
    public record TemplateChoice(String templateKey, int templateVersion, String templateName, String choiceSource) {
    }

    public record DoctorContext(UUID membershipId, String name, String role, String specialty, String degree,
                                String registrationNo, List<String> missing, TemplateChoice template) {
    }

    public record MasterItem(UUID id, String name, String specialtyHint) {
    }

    public record MasterPage(List<MasterItem> items, long total, int page, int pageSize) {
    }

    public record QuickOption(String itemKey, String name, String icd10Code, String form, Boolean favourite,
                              int useCount, Instant lastUsedAt) {
    }

    public record QuickOptionList(String master, List<QuickOption> items) {
    }

    public record Settings(String sectionPanel, String rightPanelFirst, String defaultPart, long version,
                           boolean canEdit) {
    }

    public record SnapshotSection(String key, String label, String part, int position, boolean required,
                                  String editorType, String iconKey, SectionOptions options) {
    }

    /** ADR-019 D6. Informational until SAH-46 stores it with the EMR visit. */
    public record TemplateSnapshot(int schemaVersion, String templateKey, int templateVersion, String choiceSource,
                                   Instant takenAt, List<SnapshotSection> sections) {
    }
}
