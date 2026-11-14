package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * The EMR layout and library shapes of docs/api/opd.yaml 0.3.0 (ADR-020 D3, D6) as the application layer holds them.
 * The same records are the JSON of the API answers and, with {@code schemaVersion}, of the stored starter layouts and
 * department versions (a version holds the full layout with a copy of each section's definition, ADR-020 D6). They hold
 * setup text only, never patient data.
 */
public final class EmrModel {

    public static final int SCHEMA_VERSION = 1;
    public static final String PART_ASSESSMENT = "ASSESSMENT";
    public static final String PART_TREATMENT = "TREATMENT";
    public static final List<String> PARTS = List.of(PART_ASSESSMENT, PART_TREATMENT);
    /** The order the three "who can fill" roles are always stored and returned in. */
    public static final List<String> FILLER_ROLES = List.of("DOCTOR", "NURSE", "RECEPTION");

    private EmrModel() {
    }

    /**
     * One choice of a field. {@code alert} (0.7.0, SAH-75-6, ADR-026 D6) draws the chip red when chosen; false for every
     * option of a layout written before 0.7.0. {@code hint} (0.7.1, ADR-026 D13) is the second line of a tile (1 to 60
     * characters); null for every option of a layout written before 0.7.1 and for an option without a drawn second line.
     * A blank hint is read as null.
     */
    public record FieldOption(String key, String label, @JsonProperty("isNone") boolean isNone, boolean alert,
                              String hint) {

        public FieldOption {
            hint = hint == null || hint.isBlank() ? null : hint;
        }

        /** An option with no hint (every option before 0.7.1). */
        public FieldOption(String key, String label, boolean isNone, boolean alert) {
            this(key, label, isNone, alert, null);
        }

        /** An option that is not an alert and has no hint (every library option before 0.7.0). */
        public FieldOption(String key, String label, boolean isNone) {
            this(key, label, isNone, false, null);
        }
    }

    public record TableColumn(String key, String label) {
    }

    /** The shared component a COMPONENT field holds (docs: EmrFieldDefinition.component); listKind for EMR_LIST. */
    public record ComponentRef(String type, String listKind) {
    }

    public record FieldDefinition(String key, String label, String type, EmrValueKind valueKind, String meta,
                                  boolean required, String optionsSource, String master, boolean allowFreeText,
                                  List<FieldOption> options, List<TableColumn> columns, BigDecimal min,
                                  BigDecimal max, ComponentRef component, String presentation) {

        /** The two presentations of a field (0.7.0, SAH-75-4 and 75-6): the usual control, or chips. */
        public static final String PRESENTATION_DEFAULT = "DEFAULT";
        public static final String PRESENTATION_CHIPS = "CHIPS";

        public FieldDefinition {
            presentation = PRESENTATION_CHIPS.equals(presentation) ? PRESENTATION_CHIPS : PRESENTATION_DEFAULT;
        }

        /** Without a component or presentation (the shape before 0.7.0). */
        public FieldDefinition(String key, String label, String type, EmrValueKind valueKind, String meta,
                               boolean required, String optionsSource, String master, boolean allowFreeText,
                               List<FieldOption> options, List<TableColumn> columns, BigDecimal min,
                               BigDecimal max) {
            this(key, label, type, valueKind, meta, required, optionsSource, master, allowFreeText, options, columns,
                    min, max, null, PRESENTATION_DEFAULT);
        }

        /** With a component and the default presentation. */
        public FieldDefinition(String key, String label, String type, EmrValueKind valueKind, String meta,
                               boolean required, String optionsSource, String master, boolean allowFreeText,
                               List<FieldOption> options, List<TableColumn> columns, BigDecimal min,
                               BigDecimal max, ComponentRef component) {
            this(key, label, type, valueKind, meta, required, optionsSource, master, allowFreeText, options, columns,
                    min, max, component, PRESENTATION_DEFAULT);
        }

        public FieldDefinition asComponent(ComponentRef ref) {
            return new FieldDefinition(key, label, type, EmrValueKind.COMPONENT, meta, required, optionsSource,
                    master, allowFreeText, options, columns, min, max, ref, presentation);
        }

        public FieldDefinition asCovered() {
            return new FieldDefinition(key, label, type, EmrValueKind.COVERED, meta, false, optionsSource, master,
                    allowFreeText, options, columns, min, max, null, presentation);
        }

        /** The same field with the given presentation (a CHIPS or DEFAULT value; anything else reads DEFAULT). */
        public FieldDefinition withPresentation(String newPresentation) {
            return new FieldDefinition(key, label, type, valueKind, meta, required, optionsSource, master,
                    allowFreeText, options, columns, min, max, component, newPresentation);
        }

        public boolean fillable() {
            return valueKind.fillable();
        }
    }

    /** One library section with its fields (docs: EmrLibrarySection). */
    public record LibrarySection(String key, String name, String kind, String description, boolean alwaysShown,
                                 List<FieldDefinition> fields, List<String> usedBy) {
    }

    /** ADR-023 D1: the specialty editor a section has in the record's profile (record snapshots only, never stored). */
    public record SpecialtyEditor(String component, String profile) {
    }

    /**
     * One section of a layout with its settings and definition (docs: EmrLayoutSection). A clinic form (SAH-59, ADR-022
     * D11) is a section with {@code source} FORM, {@code formKey} equal to its key, no library fields, and in a doctor
     * layout or a record snapshot its {@code formVersionNo} and {@code form} definition; department layouts store
     * form sections as settings only.
     */
    public record LayoutSection(String key, String source, String name, String kind, String description, String part,
                                int position, boolean shown, boolean required, boolean print, boolean copy,
                                boolean collapsed, List<String> whoCanFill, boolean hideLocked, String hideLockReason,
                                List<FieldDefinition> fields, String formKey, Integer formVersionNo,
                                EmrFormModel.Definition form, SpecialtyEditor specialtyEditor) {

        /** Without a specialty editor (every layout read except a record snapshot of a specialty profile). */
        public LayoutSection(String key, String source, String name, String kind, String description, String part,
                             int position, boolean shown, boolean required, boolean print, boolean copy,
                             boolean collapsed, List<String> whoCanFill, boolean hideLocked, String hideLockReason,
                             List<FieldDefinition> fields, String formKey, Integer formVersionNo,
                             EmrFormModel.Definition form) {
            this(key, source, name, kind, description, part, position, shown, required, print, copy, collapsed,
                    whoCanFill, hideLocked, hideLockReason, fields, formKey, formVersionNo, form, null);
        }

        /** A library section (no form). */
        public LayoutSection(String key, String source, String name, String kind, String description, String part,
                             int position, boolean shown, boolean required, boolean print, boolean copy,
                             boolean collapsed, List<String> whoCanFill, boolean hideLocked, String hideLockReason,
                             List<FieldDefinition> fields) {
            this(key, source, name, kind, description, part, position, shown, required, print, copy, collapsed,
                    whoCanFill, hideLocked, hideLockReason, fields, null, null, null, null);
        }

        /** The same section with other field definitions (an IPD round record binds the Prescription differently). */
        public LayoutSection withFields(List<FieldDefinition> newFields) {
            return new LayoutSection(key, source, name, kind, description, part, position, shown, required, print, copy,
                    collapsed, whoCanFill, hideLocked, hideLockReason, newFields, formKey, formVersionNo, form,
                    specialtyEditor);
        }

        /** The same section with the editor of the record's specialty profile and its (COVERED) fields. */
        public LayoutSection withSpecialtyEditor(SpecialtyEditor editor, List<FieldDefinition> coveredFields) {
            return new LayoutSection(key, source, name, kind, description, part, position, shown, required, print, copy,
                    collapsed, whoCanFill, hideLocked, hideLockReason, coveredFields, formKey, formVersionNo, form,
                    editor);
        }

        public boolean formSection() {
            return "FORM".equals(source);
        }

        public SectionSettings settings() {
            return new SectionSettings(key, part, shown, required, print, copy, collapsed, whoCanFill);
        }

        public LayoutSection withSettings(SectionSettings s, int newPosition) {
            return new LayoutSection(key, source, name, kind, description, s.part(), newPosition, s.shown(),
                    s.required(), s.print(), s.copy(), s.collapsed(), s.whoCanFill(), hideLocked, hideLockReason,
                    fields, formKey, formVersionNo, form, specialtyEditor);
        }

        /** The same section with the definition of the form version it will use. */
        public LayoutSection withForm(String newName, Integer versionNo, EmrFormModel.Definition definition) {
            return new LayoutSection(key, source, newName, kind, description, part, position, shown, required, print,
                    copy, collapsed, whoCanFill, hideLocked, hideLockReason, fields, formKey, versionNo, definition,
                    specialtyEditor);
        }
    }

    /** The eight properties a clinic may change per section (docs: EmrLayoutSettingsSection). */
    public record SectionSettings(String key, String part, boolean shown, boolean required, boolean print,
                                  boolean copy, boolean collapsed, List<String> whoCanFill) {
    }
}
