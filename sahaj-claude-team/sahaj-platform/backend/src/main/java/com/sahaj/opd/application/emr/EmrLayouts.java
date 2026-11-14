package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.DepartmentRow;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.VersionRow;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormRepository.LiveForm;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.LibrarySection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Finds and reads the layouts of ADR-020 D4 to D6: a starter layout, the blank layout, a department's latest version,
 * its saved draft, and the two answers built from them: what doctors see and the working layout of the configurator.
 * It joins the global starters (virtual departments) and the clinic's department rows into one {@link Department}.
 * Reads only; every call runs in the caller's transaction with the clinic bound.
 */
@Component
public class EmrLayouts {

    /** The 3 non-common sections the blank layout adds to the 14 COMMON ones (ADR-020 D4, SAH-54-2). */
    static final Set<String> BLANK_EXTRA_KEYS = Set.of("abha", "consent", "rxformat");

    /** The description of a clinic form shown as a custom section (tag "Custom"). */
    static final String FORM_DESCRIPTION = "Custom form";

    /** A department as the application sees it: a stored row, or a starter that has no row yet (status TEMPLATE). */
    public record Department(DepartmentRow row, String key, String name, String initials, String colour,
                             String starterKey, String sourceKind, String copiedFromKey, List<String> rooms,
                             int publishedVersion, long revision, String draftLayout) {

        public boolean stored() {
            return row != null;
        }

        /** TEMPLATE (no row), DRAFT (row, never published) or LIVE (at least one version). */
        public String status() {
            return row == null ? "TEMPLATE" : publishedVersion > 0 ? "LIVE" : "DRAFT";
        }

        /** Doctors see a starter-backed department always (published version or starter layout), an added one once published. */
        public boolean visibleToDoctors() {
            return EmrKeys.isStarterKey(key) || publishedVersion > 0;
        }
    }

    /** What doctors see: a published version, or the starter layout. {@code versionId} is set for a version. */
    public record Published(String layoutSource, Integer versionNo, UUID versionId, Integer starterLayoutVersion,
                            List<LayoutSection> sections) {
    }

    /** The configurator's working layout: the saved draft, else what doctors see. {@code source}: DRAFT, PUBLISHED, STARTER. */
    public record Working(String source, List<LayoutSection> sections) {
    }

    /** The layout a record points at (ADR-020 D8): every section, hidden ones too; {@code versionNo} as in the contract. */
    public record Snapshot(String layoutSource, Integer versionNo, List<LayoutSection> sections) {
    }

    private final EmrLibraryRepository library;
    private final EmrDepartmentRepository departments;
    private final EmrLayoutJson json;
    private final ObjectMapper mapper;
    private final EmrFormRepository forms;

    /** Without clinic forms (a plain unit test); Spring uses the constructor below. */
    public EmrLayouts(EmrLibraryRepository library, EmrDepartmentRepository departments, ObjectMapper mapper) {
        this(library, departments, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmrLayouts(EmrLibraryRepository library, EmrDepartmentRepository departments, ObjectMapper mapper,
                      EmrFormRepository forms) {
        this.library = library;
        this.departments = departments;
        this.json = new EmrLayoutJson(mapper);
        this.mapper = mapper;
        this.forms = forms;
    }

    public EmrLayoutJson json() {
        return json;
    }

    // ---- departments ----

    public Department fromRow(DepartmentRow row) {
        return new Department(row, row.key(), row.name(), row.initials(), row.colour(), row.starterKey(),
                row.sourceKind(), row.copiedFromKey(), row.rooms(), row.publishedVersion(), row.revision(),
                row.draftLayout());
    }

    public Department virtual(StarterRow starter) {
        return new Department(null, starter.starterKey(), starter.name(), starter.initials(), starter.colour(),
                starter.starterKey(), "STARTER", null, List.of(), 0, 0, null);
    }

    /** The clinic's department with this key: its row, or the virtual starter department. */
    public Optional<Department> find(UUID tenantId, String departmentKey) {
        Optional<DepartmentRow> row = departments.find(tenantId, departmentKey);
        if (row.isPresent()) {
            return Optional.of(fromRow(row.get()));
        }
        return library.starter(departmentKey).map(this::virtual);
    }

    // ---- layouts ----

    /**
     * Expands the immutable layout a record refers to: a department version (layoutSource PUBLISHED, versionNo the
     * department's version number) or a starter layout (layoutSource STARTER, versionNo the starter layout version).
     * Both are immutable, so a later publish or reset never changes what a record shows.
     */
    public Snapshot snapshot(UUID tenantId, EmrRecordRepository.RecordRow record) {
        if ("DEPARTMENT_VERSION".equals(record.layoutKind())) {
            VersionRow version = departments.versionById(tenantId, record.departmentVersionId())
                    .orElseThrow(() -> new IllegalStateException("A record points at a department version that is missing"));
            return new Snapshot("PUBLISHED", version.versionNo(),
                    withRecordForms(tenantId, record, json.readLayout(version.layout())));
        }
        StarterLayoutRow starter = library.starterLayout(record.starterKey(), record.starterLayoutVersion())
                .orElseThrow(() -> new IllegalStateException("A record points at a starter layout that is missing"));
        return new Snapshot("STARTER", starter.layoutVersion(),
                withRecordForms(tenantId, record, json.readLayout(starter.layout())));
    }

    // ---- clinic forms as sections (SAH-59, ADR-022 D11) ----

    /**
     * The immutable layout with the record's own forms: the form sections the department layout version holds as
     * settings are dropped and replaced by the record's {@code form_refs} (each with the definition of the version the
     * record was opened with). A record with no form references and no form sections is returned untouched.
     */
    private List<LayoutSection> withRecordForms(UUID tenantId, EmrRecordRepository.RecordRow record,
                                                List<LayoutSection> layout) {
        List<EmrFormRefs.Ref> refs = EmrFormRefs.parse(mapper, record.formRefs());
        boolean hasFormSections = layout.stream().anyMatch(LayoutSection::formSection);
        if (refs.isEmpty() && !hasFormSections) {
            return layout;
        }
        List<LayoutSection> sections = new ArrayList<>();
        for (LayoutSection section : layout) {
            if (!section.formSection()) {
                sections.add(section);
            }
        }
        for (EmrFormRefs.Ref ref : refs) {
            LiveForm form = forms == null ? null
                    : forms.formVersion(tenantId, ref.formKey(), ref.versionNo()).orElse(null);
            if (form == null) {
                throw new IllegalStateException("A record points at a form version that is missing");
            }
            sections.add(new LayoutSection(ref.formKey(), "FORM", form.name(), "SPECIALTY", FORM_DESCRIPTION,
                    ref.part(), ref.position(), true, ref.required(), ref.print(), ref.copy(), ref.collapsed(),
                    ref.whoCanFill(), false, null, List.of(), ref.formKey(), ref.versionNo(), readForm(form)));
        }
        sections.sort(java.util.Comparator.comparingInt(LayoutSection::position));
        List<LayoutSection> numbered = new ArrayList<>();
        int position = 1;
        for (LayoutSection section : sections) {
            numbered.add(section.withSettings(section.settings(), position++));
        }
        return List.copyOf(numbered);
    }

    /**
     * The live forms that use this department, placed in the layout: a form section the layout holds stays with its
     * settings (and its name is refreshed) when the form is still live for this department, otherwise it is dropped; a
     * live form the layout does not hold yet is appended at the end of its EMR part with the defaults of ADR-022 D11
     * (shown, not required, print on, copy off, expanded, the form's "who can fill"). With {@code withDefinitions} each
     * form section carries the live version number and definition (what doctors and new records use).
     */
    List<LayoutSection> withLiveForms(UUID tenantId, Department department, List<LayoutSection> layout,
                                      boolean withDefinitions) {
        if (forms == null) {
            return layout;
        }
        Map<String, LiveForm> live = new LinkedHashMap<>();
        for (LiveForm form : forms.liveFor(tenantId, department.key(), null)) {
            live.put(form.formKey(), form);
        }
        List<LayoutSection> result = new ArrayList<>();
        int highest = 0;
        for (LayoutSection section : layout) {
            highest = Math.max(highest, section.position());
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        for (LayoutSection section : layout) {
            if (!section.formSection()) {
                result.add(section);
                continue;
            }
            LiveForm form = live.get(section.key());
            if (form != null) {
                present.add(section.key());
                result.add(withDefinitions ? section.withForm(form.name(), form.versionNo(), readForm(form))
                        : section.withForm(form.name(), null, null));
            }
        }
        for (LiveForm form : live.values()) {
            if (!present.contains(form.formKey())) {
                result.add(newFormSection(form, ++highest, withDefinitions));
            }
        }
        return List.copyOf(result);
    }

    /** The live forms that use this place (EMERGENCY, IPD_WARD) and are not already in {@code sections}, with defaults. */
    List<LayoutSection> placeForms(UUID tenantId, String place, List<LayoutSection> sections) {
        if (forms == null || place == null) {
            return List.of();
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        int highest = 0;
        for (LayoutSection section : sections) {
            present.add(section.key());
            highest = Math.max(highest, section.position());
        }
        List<LayoutSection> result = new ArrayList<>();
        for (LiveForm form : forms.liveFor(tenantId, null, place)) {
            if (!present.contains(form.formKey())) {
                result.add(newFormSection(form, ++highest, true));
            }
        }
        return result;
    }

    /**
     * What a new record starts with: the sections doctors see now plus the live forms for the place of the encounter
     * (an IPD round uses IPD_WARD, an Emergency case EMERGENCY) that the department does not already hold. Its form
     * sections become the record's {@code form_refs}.
     */
    public List<LayoutSection> sectionsAtOpen(UUID tenantId, List<LayoutSection> published, String encounterType) {
        List<LayoutSection> all = new ArrayList<>(published);
        String place = "IPD_ROUND".equals(encounterType) ? "IPD_WARD"
                : "ER_ASSESSMENT".equals(encounterType) ? "EMERGENCY" : null;
        all.addAll(placeForms(tenantId, place, published));
        return List.copyOf(all);
    }

    /** The JSON of {@link #formRefsForOpen}, ready for the {@code form_refs} column. */
    public String formRefsJson(List<EmrFormRefs.Ref> refs) {
        return EmrFormRefs.write(mapper, refs);
    }

    private LayoutSection newFormSection(LiveForm form, int position, boolean withDefinition) {
        Definition definition = readForm(form);
        String part = definition.settings() != null && EmrModel.PARTS.contains(definition.settings().emrPart())
                ? definition.settings().emrPart() : EmrModel.PART_ASSESSMENT;
        List<String> roles = definition.settings() == null || definition.settings().whoCanFill() == null
                ? List.of() : EmrLayoutJson.canonicalRoles(definition.settings().whoCanFill());
        return new LayoutSection(form.formKey(), "FORM", form.name(), "SPECIALTY", FORM_DESCRIPTION, part, position,
                true, false, true, false, false, roles, false, null, List.of(), form.formKey(),
                withDefinition ? form.versionNo() : null, withDefinition ? definition : null);
    }

    private Definition readForm(LiveForm form) {
        try {
            return mapper.readValue(form.definition(), Definition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored form version could not be read", e);
        }
    }

    /** The current starter layout; a key that is not a starter is 404 starter-not-found. */
    public List<LayoutSection> starterLayout(String starterKey) {
        return json.readLayout(currentStarterRow(starterKey).layout());
    }

    public StarterLayoutRow currentStarterRow(String starterKey) {
        return library.currentStarterLayout(starterKey).orElseThrow(EmrProblemException::starterNotFound);
    }

    /**
     * The blank layout of "Start blank" (ADR-020 D4): the 14 COMMON sections and ABHA / ABDM link, Consent forms and
     * Prescription print format, with the settings of General Medicine (the COMMON defaults, part per the mockup's
     * {@code PART_T}), in the order of the General Medicine starter.
     */
    public List<LayoutSection> blankLayout() {
        List<LayoutSection> blank = new ArrayList<>();
        int position = 1;
        for (LayoutSection section : starterLayout(EmrKeys.DEFAULT_STARTER_KEY)) {
            if ("COMMON".equals(section.kind()) || BLANK_EXTRA_KEYS.contains(section.key())) {
                blank.add(section.withSettings(section.settings(), position++));
            }
        }
        return List.copyOf(blank);
    }

    /** The section definitions that fix a department's section set (its latest version, else its starter, else blank). */
    public List<LayoutSection> base(UUID tenantId, Department department) {
        return withLiveForms(tenantId, department, libraryBase(tenantId, department), false);
    }

    /**
     * The library sections only, forms as stored (ADR-026 D2, the union rule): the latest version's sections plus the
     * sections of the current starter layout that the version lacks (placed at their starter position, offered hidden and
     * not required because doctors have a published version and see nothing new until the owner shows it); a department
     * never published has the current starter layout, else the blank layout.
     */
    private List<LayoutSection> libraryBase(UUID tenantId, Department department) {
        Optional<VersionRow> latest = latestVersion(tenantId, department);
        if (latest.isPresent()) {
            List<LayoutSection> version = json.readLayout(latest.get().layout());
            return department.starterKey() == null ? version
                    : withStarterSections(version, starterLayout(department.starterKey()));
        }
        return department.starterKey() != null ? starterLayout(department.starterKey()) : blankLayout();
    }

    /** The sections with the starter's sections they lack added as hidden, not required rows at their starter position. */
    static List<LayoutSection> withStarterSections(List<LayoutSection> sections, List<LayoutSection> starter) {
        List<LayoutSection> merged = insertMissing(sections, starter, LayoutSection::key, EmrLayouts::offered);
        List<LayoutSection> numbered = new ArrayList<>();
        int position = 1;
        for (LayoutSection section : merged) {
            numbered.add(section.position() == position ? section : section.withSettings(section.settings(), position));
            position++;
        }
        return List.copyOf(numbered);
    }

    /** A starter section as a published department is offered it: the starter's settings, but hidden and optional. */
    private static LayoutSection offered(LayoutSection starter) {
        if (starter.hideLocked()) {
            return starter;
        }
        SectionSettings s = starter.settings();
        return starter.withSettings(new SectionSettings(s.key(), s.part(), false, false, s.print(), s.copy(),
                s.collapsed(), s.whoCanFill()), starter.position());
    }

    /**
     * {@code present} plus every {@code reference} section it lacks, each placed straight after the nearest earlier
     * reference section that is present (else before the nearest later one, else at the end); the missing item is made
     * by {@code make}. Order of the items already present is never changed.
     */
    static <T> List<T> insertMissing(List<T> present, List<LayoutSection> reference,
                                     java.util.function.Function<T, String> keyOf,
                                     java.util.function.Function<LayoutSection, T> make) {
        List<T> result = new ArrayList<>(present);
        Set<String> keys = new java.util.HashSet<>();
        for (T item : present) {
            keys.add(keyOf.apply(item));
        }
        for (int i = 0; i < reference.size(); i++) {
            LayoutSection missing = reference.get(i);
            if (keys.contains(missing.key())) {
                continue;
            }
            int at = -1;
            for (int j = i - 1; j >= 0 && at < 0; j--) {
                at = indexOf(result, reference.get(j).key(), keyOf);
                if (at >= 0) {
                    at = at + 1;
                }
            }
            for (int j = i + 1; j < reference.size() && at < 0; j++) {
                at = indexOf(result, reference.get(j).key(), keyOf);
            }
            result.add(at < 0 ? result.size() : at, make.apply(missing));
            keys.add(missing.key());
        }
        return result;
    }

    private static <T> int indexOf(List<T> items, String key, java.util.function.Function<T, String> keyOf) {
        for (int i = 0; i < items.size(); i++) {
            if (keyOf.apply(items.get(i)).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private Optional<VersionRow> latestVersion(UUID tenantId, Department department) {
        return department.stored() && department.publishedVersion() > 0
                ? departments.latestVersion(tenantId, department.row().id()) : Optional.empty();
    }

    /**
     * What doctors see for this department (ADR-020 D6): the latest version, else the starter layout. Empty for an added
     * department that was never published (it has no starter layout to fall back to unless it was started from one,
     * and doctors do not see it, SAH-54-4).
     */
    public Optional<Published> published(UUID tenantId, Department department) {
        Optional<VersionRow> latest = latestVersion(tenantId, department);
        if (latest.isPresent()) {
            VersionRow version = latest.get();
            return Optional.of(new Published("PUBLISHED", version.versionNo(), version.id(), null,
                    withLiveForms(tenantId, department, json.readLayout(version.layout()), true)));
        }
        if (department.starterKey() != null && EmrKeys.isStarterKey(department.key())) {
            StarterLayoutRow starter = currentStarterRow(department.starterKey());
            return Optional.of(new Published("STARTER", null, null, starter.layoutVersion(),
                    withLiveForms(tenantId, department, json.readLayout(starter.layout()), true)));
        }
        return Optional.empty();
    }

    /** The settings doctors see now, the baseline of NOTHING_NEW and NOTHING_TO_PUBLISH; empty when nothing is published. */
    public Optional<List<SectionSettings>> baseline(UUID tenantId, Department department) {
        return published(tenantId, department).map(p -> EmrLayoutRules.settings(p.sections()));
    }

    /** The saved draft, if any. */
    public Optional<List<SectionSettings>> draft(Department department) {
        return department.draftLayout() == null ? Optional.empty()
                : Optional.of(json.readSettings(department.draftLayout()));
    }

    /** The working layout: the saved draft applied to the department's definitions, else what doctors see. */
    public Working working(UUID tenantId, Department department) {
        Optional<List<SectionSettings>> draft = draft(department);
        if (draft.isPresent()) {
            List<LayoutSection> base = base(tenantId, department);
            // ADR-026 D2: a section of today's set that the draft lacks (a starter section added since) is added at
            // its position with the settings the base gives it.
            List<SectionSettings> reconciled = insertMissing(reconcileForms(draft.get(), base), base,
                    SectionSettings::key, LayoutSection::settings);
            return new Working("DRAFT", EmrLayoutRules.apply(reconciled, base));
        }
        Optional<Published> published = published(tenantId, department);
        if (published.isPresent()) {
            // The configurator lists a form as settings only; the definition is for doctors and records.
            List<LayoutSection> sections = published.get().sections().stream()
                    .map(s -> s.formSection() ? s.withForm(s.name(), null, null) : s).toList();
            if ("PUBLISHED".equals(published.get().layoutSource()) && department.starterKey() != null) {
                sections = withStarterSections(sections, starterLayout(department.starterKey()));
            }
            return new Working(published.get().layoutSource(), sections);
        }
        return new Working("STARTER", base(tenantId, department));
    }

    /**
     * A saved draft against today's section set: a form that is no longer live for the department is dropped from it, a
     * form that went live since is added at the end with the defaults (ADR-022 D11); library sections are untouched.
     */
    List<SectionSettings> reconcileForms(List<SectionSettings> draft, List<LayoutSection> base) {
        Map<String, LayoutSection> baseFormSections = new LinkedHashMap<>();
        for (LayoutSection section : base) {
            if (section.formSection()) {
                baseFormSections.put(section.key(), section);
            }
        }
        List<SectionSettings> result = new ArrayList<>();
        java.util.Set<String> present = new java.util.HashSet<>();
        for (SectionSettings s : draft) {
            boolean isForm = s.key() != null && s.key().startsWith("f_");
            if (isForm && !baseFormSections.containsKey(s.key())) {
                continue;
            }
            present.add(s.key());
            result.add(s);
        }
        for (LayoutSection section : baseFormSections.values()) {
            if (!present.contains(section.key())) {
                result.add(section.settings());
            }
        }
        return List.copyOf(result);
    }

    /** The hide-lock flags carry over to a new version: from the base layout, else the library's always-shown flag. */
    public List<LayoutSection> fromLibrary(List<SectionSettings> settings, List<LayoutSection> base,
                                           List<LibrarySection> library) {
        List<LayoutSection> result = new ArrayList<>();
        int position = 1;
        for (SectionSettings s : settings) {
            LayoutSection old = base.stream().filter(b -> b.key().equals(s.key())).findFirst().orElse(null);
            LibrarySection def = library.stream().filter(l -> l.key().equals(s.key())).findFirst().orElse(null);
            if (def == null) {
                if (old == null) {
                    throw EmrProblemException.sectionNotFound();
                }
                result.add(old.withSettings(s, position++));
                continue;
            }
            boolean locked = def.alwaysShown() || (old != null && old.hideLocked());
            String reason = !locked ? null
                    : old != null && old.hideLockReason() != null ? old.hideLockReason()
                    : EmrLayoutJson.lockReason(def.key(), def.name());
            result.add(new LayoutSection(def.key(), "LIBRARY", def.name(), def.kind(), def.description(), s.part(),
                    position++, s.shown(), s.required(), s.print(), s.copy(), s.collapsed(), s.whoCanFill(), locked,
                    reason, def.fields()));
        }
        return List.copyOf(result);
    }
}
