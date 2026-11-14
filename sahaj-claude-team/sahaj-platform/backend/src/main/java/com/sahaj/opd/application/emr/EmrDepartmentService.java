package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrDepartmentRepository.DepartmentRow;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.NewDepartment;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.VersionRow;
import com.sahaj.opd.application.emr.EmrInputs.DepartmentCreateInput;
import com.sahaj.opd.application.emr.EmrInputs.DepartmentSource;
import com.sahaj.opd.application.emr.EmrInputs.LayoutInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrLayouts.Working;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterRow;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import com.sahaj.opd.application.emr.EmrViews.DepartmentList;
import com.sahaj.opd.application.emr.EmrViews.DepartmentSummary;
import com.sahaj.opd.application.emr.EmrViews.DepartmentVersion;
import com.sahaj.opd.application.emr.EmrViews.DepartmentView;
import com.sahaj.opd.application.emr.EmrViews.DoctorOption;
import com.sahaj.opd.application.emr.EmrViews.DraftSaveResult;
import com.sahaj.opd.application.emr.EmrViews.PublishResult;
import com.sahaj.opd.application.emr.EmrViews.PublishedLayout;
import com.sahaj.opd.application.emr.EmrViews.ResetTarget;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinic departments, their settings draft, publishing and versions (SAH-45, SAH-53, SAH-54; ADR-020 D5, D6, D12).
 *
 * <p>The 15 starter departments exist virtually (status TEMPLATE, version 0) until the clinic's first Save draft,
 * Publish or Add department creates the row. A draft is one shared document per department with a revision; a save or
 * a publish with a stale revision is 409 emr-setup-changed. Publishing inserts the next immutable version holding the
 * full layout with a copy of each section's definition from the library at that moment. Everything is all or nothing
 * and audited by id and setup values only (no patient data exists here). Only an active owner writes; any clinic role
 * reads. Reset and Undo are screen actions: the reset target is read here, written only by the next Save draft.
 */
@Service
public class EmrDepartmentService {

    static final int MAX_ADDED_DEPARTMENTS = 50;
    static final int MAX_ROOMS = 10;
    static final int MAX_DOCTORS = 50;
    private static final Pattern INITIALS = Pattern.compile("^[A-Z0-9]{1,3}$");
    private static final Pattern COLOUR = Pattern.compile("^#[0-9A-F]{6}$");
    /** A fixed palette for an added department with no colour sent (setup text, not clinical). */
    static final List<String> PALETTE = List.of("#1D5FA8", "#2E7D32", "#B3541E", "#7B3FA0", "#00796B", "#AD1457",
            "#5D4037", "#37474F");
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final String ENTITY = "EMR_DEPARTMENT";

    private final EmrAccess access;
    private final EmrLibraryRepository library;
    private final EmrDepartmentRepository departments;
    private final EmrLayouts layouts;
    private final TeamMemberPort teamMembers;
    private final ActorDirectoryPort actors;
    private final AuditLog auditLog;
    private final SecureRandom random;

    public EmrDepartmentService(EmrAccess access, EmrLibraryRepository library, EmrDepartmentRepository departments,
                                EmrLayouts layouts, TeamMemberPort teamMembers, ActorDirectoryPort actors,
                                AuditLog auditLog, SecureRandom random) {
        this.access = access;
        this.library = library;
        this.departments = departments;
        this.layouts = layouts;
        this.teamMembers = teamMembers;
        this.actors = actors;
        this.auditLog = auditLog;
        this.random = random;
    }

    // ---- reads (any clinic role) ----

    @Transactional(readOnly = true)
    public DepartmentList list(ClinicUser user) {
        UUID tenantId = user.tenantId();
        boolean canEdit = access.canEditSetup(user);
        Map<String, DepartmentRow> rows = new LinkedHashMap<>();
        for (DepartmentRow row : departments.findAll(tenantId)) {
            rows.put(row.key(), row);
        }
        Map<UUID, VersionRow> latest = departments.latestVersions(tenantId);
        Map<UUID, Set<UUID>> doctors = departments.doctorsByDepartment(tenantId);
        Map<String, List<SectionSettings>> starterSettings = new HashMap<>();
        List<DepartmentSummary> result = new ArrayList<>();
        for (StarterRow starter : library.starters()) {
            starterSettings.put(starter.starterKey(),
                    EmrLayoutRules.settings(layouts.starterLayout(starter.starterKey())));
        }
        for (StarterRow starter : library.starters()) {
            DepartmentRow row = rows.remove(starter.starterKey());
            Department department = row == null ? layouts.virtual(starter) : layouts.fromRow(row);
            result.add(summary(department, latest, doctors, starterSettings));
        }
        for (DepartmentRow row : rows.values()) {
            result.add(summary(layouts.fromRow(row), latest, doctors, starterSettings));
        }
        return new DepartmentList(canEdit, result);
    }

    @Transactional(readOnly = true)
    public DepartmentView get(ClinicUser user, String departmentKey) {
        Department department = require(user.tenantId(), departmentKey);
        return view(user, department);
    }

    @Transactional(readOnly = true)
    public ResetTarget resetTarget(ClinicUser user, String departmentKey) {
        Department department = require(user.tenantId(), departmentKey);
        List<LayoutSection> base = layouts.base(user.tenantId(), department);
        boolean starter = department.starterKey() != null;
        List<LayoutSection> source = starter ? layouts.starterLayout(department.starterKey()) : layouts.blankLayout();
        Set<String> keys = new HashSet<>();
        for (LayoutSection section : base) {
            keys.add(section.key());
        }
        List<SectionSettings> settings = new ArrayList<>();
        for (LayoutSection section : source) {
            if (keys.contains(section.key())) {
                settings.add(section.settings());
            }
        }
        // SAH-59: a reset puts the library sections back; the forms of the department keep their settings.
        for (LayoutSection section : base) {
            if (section.formSection()) {
                settings.add(section.settings());
            }
        }
        return new ResetTarget(department.key(), starter ? "STARTER" : "BLANK", List.copyOf(settings));
    }

    /** What doctors see (SAH-53-5): the shown sections of the latest version, else of the starter layout. */
    @Transactional(readOnly = true)
    public PublishedLayout publishedLayout(ClinicUser user, String departmentKey) {
        Department department = require(user.tenantId(), departmentKey);
        if (!department.visibleToDoctors()) {
            throw EmrProblemException.departmentNotPublished();
        }
        Published published = layouts.published(user.tenantId(), department)
                .orElseThrow(EmrProblemException::departmentNotPublished);
        List<LayoutSection> shown = published.sections().stream().filter(LayoutSection::shown).toList();
        return new PublishedLayout(department.key(), department.name(), department.starterKey(),
                published.layoutSource(), published.versionNo(), shown);
    }

    @Transactional(readOnly = true)
    public DepartmentVersion version(ClinicUser user, String departmentKey, int versionNo) {
        Department department = require(user.tenantId(), departmentKey);
        if (versionNo < 1 || versionNo > 100_000) {
            throw EmrProblemException.badRequest("Choose a version number from 1 to 100000.");
        }
        if (!department.stored()) {
            throw EmrProblemException.versionNotFound();
        }
        VersionRow version = departments.version(user.tenantId(), department.row().id(), versionNo)
                .orElseThrow(EmrProblemException::versionNotFound);
        String by = version.publishedBy() == null ? "Unknown"
                : actors.displayName(user.tenantId(), version.publishedBy()).orElse("Unknown");
        return new DepartmentVersion(department.key(), version.versionNo(), version.publishedAt(), by,
                layouts.json().readLayout(version.layout()));
    }

    @Transactional(readOnly = true)
    public List<DoctorOption> doctorOptions(ClinicUser user) {
        access.requireSetupEdit(user);
        List<DoctorOption> options = new ArrayList<>();
        for (TeamMember doctor : teamMembers.findActiveDoctors(user.tenantId())) {
            String degree = teamMembers.doctorDetails(user.tenantId(), doctor.membershipId())
                    .map(TeamMemberPort.DoctorDetails::degree).orElse(null);
            options.add(new DoctorOption(doctor.membershipId(), doctor.name(), doctor.role(), degree));
        }
        options.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return options;
    }

    // ---- Save draft ----

    @Transactional
    public DraftSaveResult saveDraft(ClinicUser user, String departmentKey, LayoutInput input) {
        access.requireSetupEdit(user);
        UUID tenantId = user.tenantId();
        Department department = require(tenantId, departmentKey);
        long revision = revisionOf(input);
        boolean reset = origin(input);
        List<SectionSettings> settings = EmrLayoutRules.validate(input.sections(),
                layouts.base(tenantId, department));
        if (revision != department.revision()) {
            throw EmrProblemException.emrSetupChanged();
        }
        Optional<List<SectionSettings>> draft = layouts.draft(department);
        Optional<List<SectionSettings>> baseline = layouts.baseline(tenantId, department);
        List<SectionSettings> before = draft.orElse(baseline.orElse(List.of()));
        if (!before.isEmpty() && before.equals(settings)) {
            return new DraftSaveResult("NOTHING_NEW", view(user, department));
        }
        String json = layouts.json().writeSettings(settings);
        UUID id;
        long newRevision;
        if (department.stored()) {
            id = department.row().id();
            if (!departments.saveDraft(tenantId, id, revision, json, user.userId())) {
                throw EmrProblemException.emrSetupChanged();
            }
            newRevision = revision + 1;
        } else {
            id = insertVirtual(tenantId, department, json, 1, user.userId());
            newRevision = 1;
        }
        Map<String, Object> beforeValues = new LinkedHashMap<>();
        beforeValues.put("departmentKey", department.key());
        beforeValues.put("revision", revision);
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("departmentKey", department.key());
        afterValues.put("revision", newRevision);
        afterValues.put("changes", EmrLayoutRules.changes(before, settings));
        afterValues.put("moved", EmrLayoutRules.moved(before, settings));
        if (reset) {
            beforeValues.putAll(EmrLayoutRules.counts(before));
            afterValues.putAll(EmrLayoutRules.counts(settings));
        }
        auditLog.record(reset ? "EMR_DEPARTMENT_RESET" : "EMR_DEPARTMENT_DRAFT_SAVED", ENTITY, id, beforeValues,
                afterValues);
        return new DraftSaveResult("SAVED", view(user, require(tenantId, departmentKey)));
    }

    // ---- Publish ----

    @Transactional
    public PublishResult publish(ClinicUser user, String departmentKey, LayoutInput input) {
        access.requireSetupEdit(user);
        UUID tenantId = user.tenantId();
        requireWellFormed(departmentKey);
        Department department = departments.findForUpdate(tenantId, departmentKey).map(layouts::fromRow)
                .or(() -> library.starter(departmentKey).map(layouts::virtual))
                .orElseThrow(EmrProblemException::departmentNotFound);
        long revision = revisionOf(input);
        origin(input);
        List<LayoutSection> base = layouts.base(tenantId, department);
        List<SectionSettings> settings = EmrLayoutRules.validate(input.sections(), base);
        if (revision != department.revision()) {
            throw EmrProblemException.emrSetupChanged();
        }
        Optional<List<SectionSettings>> draft = layouts.draft(department);
        Optional<List<SectionSettings>> baseline = layouts.baseline(tenantId, department);
        if (draft.isEmpty() && baseline.isPresent() && baseline.get().equals(settings)) {
            return new PublishResult("NOTHING_TO_PUBLISH", department.publishedVersion(), view(user, department));
        }
        UUID id = department.stored() ? department.row().id()
                : insertVirtual(tenantId, department, null, 0, user.userId());
        int versionBefore = department.publishedVersion();
        int versionAfter = versionBefore + 1;
        List<LayoutSection> layout = layouts.fromLibrary(settings, base, library.sections());
        departments.insertVersion(tenantId, id, versionAfter, layouts.json().writeLayout(layout), user.userId());
        if (!departments.markPublished(tenantId, id, revision, versionAfter, user.userId())) {
            throw EmrProblemException.emrSetupChanged();
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("departmentKey", department.key());
        before.put("versionBefore", versionBefore);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("departmentKey", department.key());
        after.put("versionBefore", versionBefore);
        after.put("versionAfter", versionAfter);
        after.put("changes", EmrLayoutRules.changes(baseline.orElse(List.of()), settings));
        auditLog.record("EMR_DEPARTMENT_PUBLISHED", ENTITY, id, before, after);
        return new PublishResult("PUBLISHED", versionAfter, view(user, require(tenantId, departmentKey)));
    }

    /** The first save or publish of a starter department creates its row from the starter's name, initials, colour. */
    private UUID insertVirtual(UUID tenantId, Department virtual, String draftJson, long revision, UUID actor) {
        try {
            return departments.insert(tenantId, new NewDepartment(virtual.key(), virtual.name(), virtual.initials(),
                    virtual.colour(), virtual.starterKey(), "STARTER", null, List.of(), draftJson, revision), actor);
        } catch (DuplicateKeyException e) {
            throw EmrProblemException.emrSetupChanged();
        }
    }

    // ---- Add department ----

    @Transactional
    public DepartmentView create(ClinicUser user, DepartmentCreateInput input) {
        access.requireSetupEdit(user);
        UUID tenantId = user.tenantId();
        String name = cleanName(input);
        String initials = cleanInitials(input.initials(), name);
        String colour = cleanColour(input.colour(), name);
        List<String> rooms = cleanRooms(input.rooms());
        if (input.copyPrintFlags() == null) {
            throw invalid("copyPrintFlags", "Choose yes or no.");
        }
        List<UUID> doctorIds = cleanDoctors(tenantId, input.doctorMembershipIds());
        DepartmentSource source = input.source();
        if (source == null || source.kind() == null) {
            throw invalid("source.kind", "Choose Starter, Blank or Copy.");
        }
        SourceLayout from = sourceLayout(tenantId, source);
        if (departments.countAdded(tenantId, EmrKeys.STARTER_KEYS) >= MAX_ADDED_DEPARTMENTS) {
            throw EmrProblemException.departmentLimit();
        }
        if (nameTaken(tenantId, name)) {
            throw EmrProblemException.departmentNameTaken();
        }
        List<SectionSettings> settings = from.settings();
        if (input.sections() != null && !input.sections().isEmpty()) {
            settings = EmrLayoutRules.validate(input.sections(), from.base());
        }
        if (!input.copyPrintFlags()) {
            settings = EmrLayoutRules.clearPrint(settings);
        }
        String draft = layouts.json().writeSettings(settings);
        UUID id = null;
        String key = null;
        for (int attempt = 0; attempt < 5 && id == null; attempt++) {
            key = newKey();
            id = departments.insertIfAbsent(tenantId, new NewDepartment(key, name, initials, colour,
                    from.starterKey(), source.kind(), from.copiedFromKey(), rooms, draft, 1), user.userId())
                    .orElse(null);
            if (id == null && nameTaken(tenantId, name)) {
                throw EmrProblemException.departmentNameTaken();
            }
        }
        if (id == null) {
            throw new IllegalStateException("A department key could not be generated");
        }
        for (UUID doctor : doctorIds) {
            departments.insertDoctor(tenantId, id, doctor, user.userId());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("departmentKey", key);
        after.put("name", name);
        after.put("sourceKind", source.kind());
        after.put("starterKey", from.starterKey());
        after.put("copiedFromKey", from.copiedFromKey());
        after.put("doctorMembershipIds", doctorIds.stream().map(UUID::toString).toList());
        after.put("rooms", rooms);
        auditLog.record("EMR_DEPARTMENT_CREATED", ENTITY, id, null, after);
        return view(user, require(tenantId, key));
    }

    private record SourceLayout(String starterKey, String copiedFromKey, List<SectionSettings> settings,
                                List<LayoutSection> base) {
    }

    private SourceLayout sourceLayout(UUID tenantId, DepartmentSource source) {
        switch (source.kind()) {
            case "STARTER": {
                if (!EmrKeys.isWellFormedDepartmentKey(source.starterKey())) {
                    throw invalid("source.starterKey", "Choose a starter department.");
                }
                StarterRow starter = library.starter(source.starterKey())
                        .orElseThrow(EmrProblemException::starterNotFound);
                List<LayoutSection> base = layouts.starterLayout(starter.starterKey());
                return new SourceLayout(starter.starterKey(), null, EmrLayoutRules.settings(base), base);
            }
            case "BLANK": {
                if (source.starterKey() != null || source.copyFromDepartmentKey() != null) {
                    throw invalid("source", "A blank department has no starter or copy source.");
                }
                List<LayoutSection> base = layouts.blankLayout();
                return new SourceLayout(null, null, EmrLayoutRules.settings(base), base);
            }
            case "COPY": {
                if (!EmrKeys.isWellFormedDepartmentKey(source.copyFromDepartmentKey())) {
                    throw invalid("source.copyFromDepartmentKey", "Choose a department to copy.");
                }
                Department from = require(tenantId, source.copyFromDepartmentKey());
                if (!from.stored()) {
                    throw EmrProblemException.departmentNotFound();
                }
                Working working = layouts.working(tenantId, from);
                // SAH-59: a copy takes the library sections only; the source department's forms are chosen by each
                // form's own "Use in" settings and the new department is not in them.
                List<LayoutSection> base = layouts.base(tenantId, from).stream().filter(s -> !s.formSection()).toList();
                List<LayoutSection> libraryWorking = working.sections().stream().filter(s -> !s.formSection()).toList();
                return new SourceLayout(from.starterKey(), from.key(), EmrLayoutRules.settings(libraryWorking), base);
            }
            default:
                throw invalid("source.kind", "Choose Starter, Blank or Copy.");
        }
    }

    private boolean nameTaken(UUID tenantId, String name) {
        for (StarterRow starter : library.starters()) {
            if (starter.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return departments.nameTaken(tenantId, name);
    }

    private String cleanName(DepartmentCreateInput input) {
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty() || name.length() > 60) {
            throw invalid("name", "Enter a name of 1 to 60 characters.");
        }
        return name;
    }

    static String cleanInitials(String given, String name) {
        if (given != null) {
            if (!INITIALS.matcher(given).matches()) {
                throw invalid("initials", "Use 1 to 3 capital letters or digits.");
            }
            return given;
        }
        return deriveInitials(name);
    }

    /** First letters of up to three words, upper case; one word gives its first two letters. */
    static String deriveInitials(String name) {
        StringBuilder letters = new StringBuilder();
        for (String word : name.strip().split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty() && letters.length() < 3) {
                letters.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            }
        }
        if (letters.length() == 1) {
            String first = name.strip().replaceAll("[^\\p{L}\\p{N}]", "");
            if (first.length() > 1) {
                letters.append(first.substring(1, 2).toUpperCase(Locale.ROOT));
            }
        }
        String result = letters.toString().replaceAll("[^A-Z0-9]", "");
        return result.isEmpty() ? "D" : result;
    }

    private String cleanColour(String given, String name) {
        if (given != null) {
            if (!COLOUR.matcher(given).matches()) {
                throw invalid("colour", "Use a colour such as #1D5FA8.");
            }
            return given;
        }
        return PALETTE.get(Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), PALETTE.size()));
    }

    private List<String> cleanRooms(List<String> given) {
        if (given == null) {
            return List.of();
        }
        if (given.size() > MAX_ROOMS) {
            throw invalid("rooms", "Add at most 10 rooms.");
        }
        List<String> rooms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String room : given) {
            String label = room == null ? "" : room.strip();
            if (label.isEmpty() || label.length() > 40 || !seen.add(label.toLowerCase(Locale.ROOT))) {
                throw invalid("rooms", "Each room is 1 to 40 characters and different from the others.");
            }
            rooms.add(label);
        }
        return List.copyOf(rooms);
    }

    private List<UUID> cleanDoctors(UUID tenantId, List<UUID> given) {
        if (given == null || given.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new HashSet<>(given);
        if (given.size() > MAX_DOCTORS || unique.size() != given.size() || unique.contains(null)) {
            throw invalid("doctorMembershipIds", "Choose each doctor once, up to 50.");
        }
        Set<UUID> active = new HashSet<>();
        for (TeamMember doctor : teamMembers.findActiveDoctors(tenantId)) {
            active.add(doctor.membershipId());
        }
        if (!active.containsAll(unique)) {
            throw invalid("doctorMembershipIds", "Choose doctors from this clinic's team.");
        }
        return List.copyOf(given);
    }

    private String newKey() {
        StringBuilder key = new StringBuilder("d_");
        for (int i = 0; i < 10; i++) {
            key.append(BASE32[random.nextInt(BASE32.length)]);
        }
        return key.toString();
    }

    // ---- shared ----

    private Department require(UUID tenantId, String departmentKey) {
        requireWellFormed(departmentKey);
        return layouts.find(tenantId, departmentKey).orElseThrow(EmrProblemException::departmentNotFound);
    }

    private static void requireWellFormed(String departmentKey) {
        if (!EmrKeys.isWellFormedDepartmentKey(departmentKey)) {
            throw EmrProblemException.badRequest("Choose a valid department key.");
        }
    }

    private static long revisionOf(LayoutInput input) {
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        return input.revision();
    }

    /** True for origin RESET; EDIT is the other value; a missing or other value is a 400. */
    private static boolean origin(LayoutInput input) {
        if ("RESET".equals(input.origin())) {
            return true;
        }
        if ("EDIT".equals(input.origin())) {
            return false;
        }
        throw invalid("origin", "Choose Edit or Reset.");
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }

    private DepartmentView view(ClinicUser user, Department department) {
        UUID tenantId = user.tenantId();
        Working working = layouts.working(tenantId, department);
        List<SectionSettings> settings = EmrLayoutRules.settings(working.sections());
        int doctors = department.stored()
                ? departments.doctorsByDepartment(tenantId).getOrDefault(department.row().id(), Set.of()).size() : 0;
        boolean hasDraft = hasDraft(department, layouts.draft(department), layouts.baseline(tenantId, department));
        return new DepartmentView(department.key(), department.name(), department.initials(), department.colour(),
                department.starterKey(), department.sourceKind(), department.copiedFromKey(), department.status(),
                department.publishedVersion(), hasDraft, settings.size(), EmrLayoutRules.sectionsOn(settings),
                EmrLayoutRules.requiredOn(settings), doctors, department.rooms(), access.canEditSetup(user),
                department.revision(), working.source(), working.sections());
    }

    private static boolean hasDraft(Department department, Optional<List<SectionSettings>> draft,
                                    Optional<List<SectionSettings>> baseline) {
        return draft.isPresent() && !baseline.map(b -> b.equals(draft.get())).orElse(false);
    }

    /** One list row, with the version and starter layouts fetched once for the whole list. */
    private DepartmentSummary summary(Department department, Map<UUID, VersionRow> latest,
                                      Map<UUID, Set<UUID>> doctors, Map<String, List<SectionSettings>> starters) {
        Optional<List<SectionSettings>> draft = layouts.draft(department);
        Optional<List<SectionSettings>> baseline = Optional.empty();
        if (department.stored() && department.publishedVersion() > 0) {
            VersionRow version = latest.get(department.row().id());
            if (version != null) {
                baseline = Optional.of(EmrLayoutRules.settings(layouts.json().readLayout(version.layout())));
            }
        } else if (EmrKeys.isStarterKey(department.key())) {
            baseline = Optional.of(starters.get(department.key()));
        }
        List<SectionSettings> working = draft.orElse(baseline.orElse(List.of()));
        int doctorCount = department.stored() ? doctors.getOrDefault(department.row().id(), Set.of()).size() : 0;
        return new DepartmentSummary(department.key(), department.name(), department.initials(), department.colour(),
                department.starterKey(), department.sourceKind(), department.copiedFromKey(), department.status(),
                department.publishedVersion(), hasDraft(department, draft, baseline), working.size(),
                EmrLayoutRules.sectionsOn(working), EmrLayoutRules.requiredOn(working), doctorCount,
                department.rooms());
    }
}
