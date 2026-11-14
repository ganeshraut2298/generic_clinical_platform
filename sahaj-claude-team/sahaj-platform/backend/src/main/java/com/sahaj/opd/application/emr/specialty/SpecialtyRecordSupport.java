package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.ServiceRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository.CourseRow;
import com.sahaj.opd.application.course.TreatmentCourseRepository.SittingRow;
import com.sahaj.opd.application.emr.EmrComponentValues.Note;
import com.sahaj.opd.application.emr.EmrInputs.ScreenInput;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SpecialtyEditor;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.EmrViews.StillNeededItem;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Earlier;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Hooks;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.RehabInfo;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.Tariff;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.TariffItem;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import com.sahaj.patient.api.DateOfBirthAccess;
import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What the EMR record service needs for the specialty screens (ADR-023 D1 to D5): which profile a record has, the layout
 * decoration (the editor of a section and its COVERED library fields), the value check of a section, the Done rule and
 * the footer items, the screen facts (visit mode, Oncology decision) and the server-computed {@code specialty} view.
 * Everything is read through the repositories and ports of the clinic bound to the caller's transaction. Clinical values
 * are never logged and never audited by value.
 */
@Component
public class SpecialtyRecordSupport {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int EARLIER_SCAN = 500;

    private final SpecialtyReferenceRepository reference;
    private final ServiceRepository services;
    private final TeamMemberPort teamMembers;
    private final PatientClinicalPort patients;
    private final TreatmentCourseRepository courses;
    private final ChemoOrderRepository chemoOrders;
    private final EmrRecordRepository records;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final ObjectProvider<ChemoOrderHook> chemoHook;
    /** SAH-75-7: the journey, plan and watch facts of the oncology strip; null in a plain unit test. */
    private final OncoStripSource oncoStrip;

    public SpecialtyRecordSupport(SpecialtyReferenceRepository reference, ServiceRepository services,
                                  TeamMemberPort teamMembers, PatientClinicalPort patients,
                                  TreatmentCourseRepository courses, ChemoOrderRepository chemoOrders,
                                  EmrRecordRepository records, AuditLog auditLog, ObjectMapper mapper,
                                  ObjectProvider<ChemoOrderHook> chemoHook) {
        this(reference, services, teamMembers, patients, courses, chemoOrders, records, auditLog, mapper, chemoHook,
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SpecialtyRecordSupport(SpecialtyReferenceRepository reference, ServiceRepository services,
                                  TeamMemberPort teamMembers, PatientClinicalPort patients,
                                  TreatmentCourseRepository courses, ChemoOrderRepository chemoOrders,
                                  EmrRecordRepository records, AuditLog auditLog, ObjectMapper mapper,
                                  ObjectProvider<ChemoOrderHook> chemoHook, OncoStripSource oncoStrip) {
        this.oncoStrip = oncoStrip;
        this.reference = reference;
        this.services = services;
        this.teamMembers = teamMembers;
        this.patients = patients;
        this.courses = courses;
        this.chemoOrders = chemoOrders;
        this.records = records;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.chemoHook = chemoHook;
    }

    /** Called by the record service after a draft save that may change what the chemotherapy order shows. */
    public interface ChemoOrderHook {
        void onRecordSaved(UUID tenantId, UUID actorUserId, UUID membershipId, RecordRow row, EmrRecordDocument document);
    }

    // ---- profile and layout ----

    public Optional<String> profile(RecordRow row) {
        return SpecialtyProfiles.profileOf(row.encounterType(), row.starterKey());
    }

    /** The shown sections with the editor of the profile and the COVERED library fields of a section that has one. */
    public List<LayoutSection> decorate(String profile, List<LayoutSection> shown) {
        List<LayoutSection> result = new ArrayList<>();
        for (LayoutSection section : shown) {
            String component = section.formSection() ? null : SpecialtyProfiles.component(profile, section.key());
            if (component == null) {
                result.add(section);
                continue;
            }
            List<FieldDefinition> covered = new ArrayList<>();
            for (FieldDefinition field : section.fields()) {
                covered.add(field.asCovered());
            }
            result.add(section.withSpecialtyEditor(new SpecialtyEditor(component, profile), List.copyOf(covered)));
        }
        return List.copyOf(result);
    }

    // ---- screen facts ----

    /** The stored screen facts of a record (empty object when none). */
    public JsonNode screen(RecordRow row) {
        if (row.screenState() == null || row.screenState().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(row.screenState());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored screen state could not be read", e);
        }
    }

    public String mode(RecordRow row) {
        JsonNode mode = screen(row).path("mode");
        return mode.isTextual() ? mode.asText() : null;
    }

    /**
     * Applies the {@code screen} object of a draft save to the stored screen state (ADR-023 D5). Returns the new screen
     * state as JSON text, or null when nothing changed. {@code ctx} is built on the document after this save's section
     * changes, so the suggestion is worked out from the values that will be stored.
     */
    public ScreenOutcome applyScreen(RecordRow row, String profile, ScreenInput input, SpecialtyCtx ctx,
                                     UUID membershipId, Instant now) {
        if (input == null || (input.mode() == null && input.oncDecision() == null)) {
            return new ScreenOutcome(null, List.of());
        }
        ObjectNode state = screen(row).deepCopy() instanceof ObjectNode node ? node : mapper.createObjectNode();
        state.put("schemaVersion", 1);
        state.put("profile", profile);
        List<Note> notes = new ArrayList<>();
        boolean changed = false;
        if (input.mode() != null) {
            if (!SpecialtyProfiles.modes(profile).contains(input.mode())) {
                throw new com.sahaj.platform.api.InvalidFieldsException(List.of(new FieldMessage("screen.mode",
                        "Choose a valid option")));
            }
            changed = !input.mode().equals(state.path("mode").asText(null));
            state.put("mode", input.mode());
        }
        if (input.oncDecision() != null) {
            if (!"onco".equals(profile)) {
                throw EmrProblemException.notApplicable();
            }
            String choice = input.oncDecision().choice();
            String reason = input.oncDecision().reasonKey();
            if (choice == null || !OncologyRules.DECISIONS.contains(choice)) {
                throw new com.sahaj.platform.api.InvalidFieldsException(List.of(new FieldMessage(
                        "screen.oncDecision.choice", "Choose a valid option")));
            }
            if (reason != null && !OncologyRules.DEVIATION_REASONS.contains(reason)) {
                throw new com.sahaj.platform.api.InvalidFieldsException(List.of(new FieldMessage(
                        "screen.oncDecision.reasonKey", "Choose a valid option")));
            }
            String suggestion = OncologyAssessment.compute(ctx.sibling(), null, now, null).suggestion();
            if (suggestion != null && !suggestion.equals(choice) && reason == null) {
                throw EmrProblemException.of(400, "decision-reason-required", "A reason is needed",
                        "Pick a reason (saved with the note).");
            }
            JsonNode before = state.path("oncDecision");
            boolean same = before.isObject() && choice.equals(before.path("choice").asText())
                    && java.util.Objects.equals(reason, before.path("reasonKey").asText(null));
            if (!same) {
                ObjectNode decision = state.putObject("oncDecision");
                decision.put("choice", choice);
                if (reason == null) {
                    decision.putNull("reasonKey");
                } else {
                    decision.put("reasonKey", reason);
                }
                if (suggestion == null) {
                    decision.putNull("suggestionAtDecision");
                } else {
                    decision.put("suggestionAtDecision", suggestion);
                }
                decision.put("decidedAt", now.toString());
                decision.put("decidedByMembershipId", membershipId.toString());
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("suggestion", suggestion);
                audit.put("choice", choice);
                audit.put("reasonKey", reason);
                notes.add(new Note("EMR_ONC_DECISION_RECORDED", audit));
                changed = true;
            }
        }
        return new ScreenOutcome(changed ? state.toString() : null, notes);
    }

    /** The new screen state JSON (null = unchanged) and the audit entries to write. */
    public record ScreenOutcome(String screenState, List<Note> notes) {
    }

    // ---- value check ----

    /**
     * Checks the values of an anchor section: only the reserved key {@code specialty}, holding the component the section's
     * editor names. Returns {@code {"specialty": value}} to store, or null after adding errors named by field key.
     */
    public ObjectNode cleanSection(String where, LayoutSection section, JsonNode values, JsonNode storedValues,
                                   SpecialtyCtx ctx, List<FieldMessage> errors, List<Note> notes) {
        SpecialtyEditor editor = section.specialtyEditor();
        if (values == null || !values.isObject()) {
            errors.add(new FieldMessage(where + ".values", "Send the values as an object."));
            return null;
        }
        var names = values.fieldNames();
        while (names.hasNext()) {
            if (!"specialty".equals(names.next())) {
                errors.add(new FieldMessage(where + ".values", "This section is filled with its editor. Send specialty only."));
                return null;
            }
        }
        JsonNode input = values.get("specialty");
        if (input == null || input.isNull()) {
            return mapper.createObjectNode();
        }
        if (!input.isObject() || !editor.component().equals(input.path("component").asText())) {
            errors.add(new FieldMessage(where + ".values.specialty.component", "Choose a valid option"));
            return null;
        }
        Sp.Errors problems = new Sp.Errors(where + ".values.specialty");
        JsonNode stored = storedValues == null ? null : storedValues.path("specialty");
        ObjectNode value = SpecialtyValues.normalize(editor.component(), input, stored == null || stored.isMissingNode()
                ? null : stored, ctx, problems, notes);
        if (value == null) {
            errors.addAll(problems.messages());
            return null;
        }
        ObjectNode cleaned = mapper.createObjectNode();
        cleaned.set("specialty", value);
        return cleaned;
    }

    // ---- Done and the footer ----

    /** The Done rule of an anchor section, or null for a generic section. */
    public Boolean done(LayoutSection section, EmrRecordDocument document, SpecialtyCtx ctx, String orderStatus) {
        SpecialtyEditor editor = section.specialtyEditor();
        if (editor == null) {
            return null;
        }
        SpecialtyDone.Ctx doneCtx = new SpecialtyDone.Ctx(ctx.recordDate(), ctx.sibling(), orderStatus);
        JsonNode value = document.values(section.key()).path("specialty");
        if (document.copyPending(section.key())) {
            return false;
        }
        return SpecialtyDone.isDone(editor.component(), value.isMissingNode() ? null : value, doneCtx);
    }

    /**
     * The footer items in order (ADR-023 D3): required sections not Done, with the profile's labels, and the profile's own
     * items. For the oncology profile the grades, the systemic examination, the decision and the order sheet are listed
     * whenever their section is shown, whatever its Required setting (H2).
     */
    public List<StillNeededItem> stillNeeded(String profile, List<LayoutSection> shown, Map<String, Boolean> doneByKey,
                                             EmrRecordDocument document, JsonNode screen, String orderStatus) {
        return stillNeeded(profile, shown, doneByKey, document, screen, orderStatus,
                screen.path("mode").isTextual() ? screen.path("mode").asText() : null);
    }

    /**
     * The footer items for the sections that are not hidden by the visit mode (D13.3): {@code shown} must already
     * exclude the hidden sections. {@code mode} is the effective mode.
     */
    public List<StillNeededItem> stillNeeded(String profile, List<LayoutSection> shown, Map<String, Boolean> doneByKey,
                                             EmrRecordDocument document, JsonNode screen, String orderStatus,
                                             String mode) {
        List<StillNeededItem> items = new ArrayList<>();
        boolean onco = "onco".equals(profile);
        boolean decisionAdded = false;
        for (LayoutSection section : shown) {
            boolean done = Boolean.TRUE.equals(doneByKey.get(section.key()));
            String key = section.key();
            if (onco && "onc_regimen".equals(key) && !decisionAdded) {
                decisionAdded = true;
                if (!screen.path("oncDecision").path("choice").isTextual()) {
                    items.add(new StillNeededItem("ONC_DECISION", null, "today's decision", null));
                }
            }
            if (onco && Set.of("onc_tox", "onc_bsa", "onc_regimen").contains(key)) {
                if (done) {
                    continue;
                }
                items.add(oncoItem(key, document, screen, orderStatus));
                continue;
            }
            if (section.required() && !done) {
                String label = "sx_plan".equals(key) && "surgery".equals(profile) ? "OT booking"
                        : section.name().toLowerCase(Locale.ROOT);
                items.add(new StillNeededItem(key, key, label, null));
            }
        }
        if (onco && !decisionAdded && !screen.path("oncDecision").path("choice").isTextual()
                && shown.stream().anyMatch(s -> "onc_bsa".equals(s.key()))) {
            items.add(new StillNeededItem("ONC_DECISION", null, "today's decision", null));
        }
        if ("physio".equals(profile) && "SESSION".equals(mode)
                && shown.stream().anyMatch(s -> "modalities".equals(s.key()))
                && !Boolean.TRUE.equals(doneByKey.get("modalities"))) {
            items.add(new StillNeededItem("PHYSIO_TREATMENT_TODAY", "modalities",
                    "Select at least one treatment given today.", null));
        }
        return items;
    }

    private StillNeededItem oncoItem(String key, EmrRecordDocument document, JsonNode screen, String orderStatus) {
        switch (key) {
            case "onc_tox": {
                int open = SpecialtyDone.oncToxOpen(document.values("onc_tox").path("specialty"));
                return new StillNeededItem(key, key, open + " side effect grade(s)", open);
            }
            case "onc_bsa":
                return new StillNeededItem(key, key, "systemic exam", null);
            default: {
                String choice = screen.path("oncDecision").path("choice").asText();
                boolean hold = "DELAY_1_WEEK".equals(choice) || "STOP".equals(choice);
                return new StillNeededItem(key, key, hold ? "record the hold" : "send chemo order", null);
            }
        }
    }

    // ---- the specialty view ----

    public JsonNode view(String profile, SpecialtyCtx ctx, RecordRow row) {
        return view(profile, ctx, row, List.of());
    }

    /** The view with the sections the effective mode hides (see {@link #hiddenSectionKeys}). */
    public JsonNode view(String profile, SpecialtyCtx ctx, RecordRow row, List<String> hiddenSectionKeys) {
        JsonNode screen = screen(row);
        JsonNode decision = screen.path("oncDecision");
        return SpecialtyViewBuilder.build(profile, ctx, effectiveMode(profile, ctx, row),
                decision.isObject() ? decision : null, hiddenSectionKeys);
    }

    /**
     * The effective visit mode (ADR-023 D13.3): the stored mode, else og ANC, physio SESSION when the linked REHAB
     * session is above 1 (else FIRST_VISIT); null for the other profiles.
     */
    public String effectiveMode(String profile, SpecialtyCtx ctx, RecordRow row) {
        String stored = mode(row);
        if (stored != null || !"physio".equals(profile)) {
            return SpecialtyProfiles.effectiveMode(profile, stored, null);
        }
        RehabInfo rehab = ctx.hooks().rehab().get();
        return SpecialtyProfiles.effectiveMode(profile, null, rehab == null ? null : rehab.sessionNo());
    }

    /**
     * The sections of the layout the effective mode hides, in layout order (ADR-023 D13.3, H21). Only keys that are in
     * the shown layout are listed. {@code pcpndt} stays shown while Form F is required (a scan ordered, not marked).
     */
    public List<String> hiddenSectionKeys(String profile, SpecialtyCtx ctx, RecordRow row, List<LayoutSection> shown) {
        String mode = effectiveMode(profile, ctx, row);
        boolean formFRequired = "og".equals(profile)
                && SpecialtyValues.obstetricScanOrdered(ctx.sibling().apply("ANC_CHECK"))
                && !formFSigned(ctx.sibling().apply("FORM_F"));
        List<String> hidden = SpecialtyProfiles.hiddenSections(profile, mode, formFRequired);
        List<String> result = new ArrayList<>();
        for (LayoutSection section : shown) {
            if (hidden.contains(section.key())) {
                result.add(section.key());
            }
        }
        return List.copyOf(result);
    }

    private static boolean formFSigned(JsonNode formF) {
        return formF != null && formF.path("filledAndSigned").asBoolean(false);
    }

    /** The order status of a record (NONE when no order exists). */
    public String orderStatus(UUID tenantId, RecordRow row) {
        return chemoOrders.findByRecord(tenantId, row.id()).map(o -> o.status).orElse("NONE");
    }

    /** Adds the staff name beside every {@code byMembershipId} of a specialty value (response only, never stored). */
    public void addNames(UUID tenantId, ObjectNode values) {
        Set<UUID> ids = new HashSet<>();
        collect(values, ids);
        if (ids.isEmpty()) {
            return;
        }
        Map<UUID, TeamMember> members = teamMembers.findAll(tenantId, ids);
        apply(values, members);
    }

    private static void collect(JsonNode node, Set<UUID> ids) {
        if (node.isObject()) {
            if (node.path("byMembershipId").isTextual()) {
                try {
                    ids.add(UUID.fromString(node.path("byMembershipId").asText()));
                } catch (IllegalArgumentException ignored) {
                    // not an id: nothing to look up
                }
            }
            node.fields().forEachRemaining(entry -> collect(entry.getValue(), ids));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, ids));
        }
    }

    private static void apply(JsonNode node, Map<UUID, TeamMember> members) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.path("byMembershipId").isTextual()) {
                String name = null;
                try {
                    TeamMember member = members.get(UUID.fromString(object.path("byMembershipId").asText()));
                    name = member == null ? null : member.name();
                } catch (IllegalArgumentException ignored) {
                    name = null;
                }
                if (name == null) {
                    object.putNull("byName");
                } else {
                    object.put("byName", name);
                }
            }
            List<JsonNode> children = new ArrayList<>();
            object.fields().forEachRemaining(entry -> children.add(entry.getValue()));
            children.forEach(child -> apply(child, members));
        } else if (node.isArray()) {
            node.forEach(child -> apply(child, members));
        }
    }

    // ---- hooks after a save ----

    /** Recomputes the chemotherapy order of an oncology record after a save (clears a stale confirmation). */
    public void afterSave(UUID tenantId, UUID actorUserId, UUID membershipId, RecordRow row, String profile,
                          EmrRecordDocument document) {
        if (!"onco".equals(profile)) {
            return;
        }
        ChemoOrderHook hook = chemoHook.getIfAvailable();
        if (hook != null) {
            hook.onRecordSaved(tenantId, actorUserId, membershipId, row, document);
        }
    }

    // ---- the context ----

    /**
     * Builds the context of one request for a record and its (already changed) document. Everything that needs a
     * database read is lazy and read at most once.
     */
    public SpecialtyCtx context(UUID tenantId, RecordRow row, String profile, UUID membershipId, Instant now,
                                EmrRecordDocument document) {
        return context(tenantId, row, profile, membershipId, null, now, document);
    }

    /**
     * As above, for a caller whose membership role is known. SAH-73 (ADR-024 D1, D3): the age is the effective years the
     * patient module worked out; the date of birth is passed on to the Paediatrics view only when {@code role} may see it.
     */
    public SpecialtyCtx context(UUID tenantId, RecordRow row, String profile, UUID membershipId, String role, Instant now,
                                EmrRecordDocument document) {
        return context(tenantId, row, profile, membershipId, role, now, document, null);
    }

    /**
     * As above, as of a day in the past (ADR-025 H20, the reprint of a signed record). {@code signedAt} is the moment the
     * record was signed: it is also {@code now}, so every age, vaccine state and due date reads as it did on the signing day;
     * only records signed strictly before it count as earlier records; the age comes from the date of birth as of that day
     * (the stored whole number when the patient has no date of birth). Null: the live reading.
     */
    public SpecialtyCtx context(UUID tenantId, RecordRow row, String profile, UUID membershipId, String role, Instant now,
                                EmrRecordDocument document, Instant signedAt) {
        LocalDate today = now.atZone(INDIA).toLocalDate();
        LocalDate recordDate = row.createdAt().atZone(INDIA).toLocalDate();
        PatientClinical patient = row.patientId() == null ? null : patients.find(tenantId, row.patientId()).orElse(null);
        Function<String, JsonNode> sibling = component -> componentValue(profile, document, component);
        EarlierLoader earlier = new EarlierLoader(tenantId, row.patientId(), row.id(), signedAt);
        Hooks hooks = new Hooks(key -> document.has(key) ? document.values(key) : null, earlier::of,
                () -> prescriptionRows(document), () -> hasActiveDiagnosis(document),
                lazy(() -> rehab(tenantId, row)), lazy(() -> chemo(tenantId, row, document)),
                lazy(() -> oncoStrip == null || !"onco".equals(profile) ? SpecialtyCtx.OncoExtra.none()
                        : oncoStrip.load(tenantId, row, document)));
        Set<String> vaccines = new HashSet<>();
        UUID ancCourse = null;
        if ("paeds".equals(profile)) {
            for (Earlier e : earlier.of("IMMUNIZATION")) {
                for (JsonNode given : e.value().path("givenToday")) {
                    vaccines.add(given.path("vaccineKey").asText());
                }
            }
        }
        if ("og".equals(profile) && row.patientId() != null) {
            ancCourse = activeCourse(tenantId, row.patientId(), "ANC").map(c -> c.id()).orElse(null);
        }
        Integer ageYears = patient == null ? null : signedAt == null ? effectiveYears(patient, today)
                : PatientAge.of(patient.dateOfBirth(), patient.age(), today).years();
        return new SpecialtyCtx(profile, now, today, recordDate, membershipId, patient == null ? null
                : patient.dateOfBirth(), patient == null ? null : patient.sex(), reference.load(), tariff(tenantId),
                vaccines, ancCourse, sibling, hooks, ageYears, DateOfBirthAccess.mayView(role));
    }

    /**
     * SAH-73 (ADR-024 D7): the patient's date of birth, for the age at an earlier growth point (chart position only);
     * null when the patient has none or is unknown. Personal data: the caller turns it into months and never returns it.
     */
    public LocalDate dateOfBirth(UUID tenantId, UUID patientId) {
        return patients.find(tenantId, patientId).map(PatientClinical::dateOfBirth).orElse(null);
    }

    /**
     * SAH-73 (ADR-024 D1): the patient module's own answer when it gave one; otherwise (a caller that built the
     * record by hand) the same {@link PatientAge} function over what the record holds.
     */
    static int effectiveYears(PatientClinical patient, LocalDate today) {
        return patient.ageDetail() != null ? patient.ageDetail().years()
                : PatientAge.of(patient.dateOfBirth(), patient.age(), today).years();
    }

    /** The stored specialty value of a component of a record document, or null. */
    static JsonNode componentValue(String profile, EmrRecordDocument document, String component) {
        String section = SpecialtyProfiles.sectionOf(profile, component);
        if (section == null) {
            return null;
        }
        JsonNode value = document.values(section).path("specialty");
        return value.isObject() && component.equals(value.path("component").asText()) ? value : null;
    }

    private static <T> java.util.function.Supplier<T> lazy(java.util.function.Supplier<T> supplier) {
        Object[] holder = new Object[2];
        return () -> {
            if (holder[1] == null) {
                holder[0] = supplier.get();
                holder[1] = Boolean.TRUE;
            }
            @SuppressWarnings("unchecked")
            T value = (T) holder[0];
            return value;
        };
    }

    private Tariff tariff(UUID tenantId) {
        Map<String, TariffItem>[] cache = new Map[1];
        return name -> {
            if (cache[0] == null) {
                Map<String, TariffItem> map = new HashMap<>();
                for (ServiceRepository.ServiceRow service : services.findAll(tenantId, true)) {
                    map.putIfAbsent(normalise(service.name()), new TariffItem(service.id(), service.pricePaise()));
                }
                cache[0] = map;
            }
            return Optional.ofNullable(cache[0].get(normalise(name)));
        };
    }

    static String normalise(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private Optional<CourseRow> activeCourse(UUID tenantId, UUID patientId, String kind) {
        return courses.forPatient(tenantId, patientId, 50).stream()
                .filter(c -> kind.equals(c.kind()) && "ACTIVE".equals(c.status())).findFirst();
    }

    private RehabInfo rehab(UUID tenantId, RecordRow row) {
        if (row.patientId() == null) {
            return null;
        }
        Optional<CourseRow> course = activeCourse(tenantId, row.patientId(), "REHAB");
        if (course.isEmpty()) {
            return null;
        }
        List<SittingRow> sittings = courses.sittingsOf(tenantId, List.of(course.get().id()));
        Integer number = null;
        int rank = 0;
        for (SittingRow sitting : sittings) {
            rank++;
            if (row.opdVisitId() != null && row.opdVisitId().equals(sitting.opdVisitId())) {
                number = rank;
            }
        }
        return new RehabInfo(course.get().name(), number, sittings.size());
    }

    /** The pain score of an earlier SIGNED session, by the session number shown (ADR-026 D4 displayNo). */
    public record SessionPain(int sessionNo, int score) {
    }

    /**
     * SAH-75-11 (P-3): the PAIN_SCORE of the earlier SIGNED sessions of the patient's active REHAB course, in session order,
     * at most 60. A record whose visit is not a sitting of that course is left out. Empty until SAH-51 signs records.
     */
    public List<SessionPain> painBySession(UUID tenantId, UUID patientId, UUID currentRecordId) {
        Optional<CourseRow> course = patientId == null ? Optional.empty() : activeCourse(tenantId, patientId, "REHAB");
        if (course.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> sessionOfVisit = new HashMap<>();
        int rank = 0;
        for (SittingRow sitting : courses.sittingsOf(tenantId, List.of(course.get().id()))) {
            rank++;
            if (sitting.opdVisitId() != null) {
                sessionOfVisit.put(sitting.opdVisitId(), rank);
            }
        }
        java.util.TreeMap<Integer, Integer> bySession = new java.util.TreeMap<>();
        for (Earlier earlier : new EarlierLoader(tenantId, patientId, currentRecordId, null).of("PAIN_SCORE")) {
            JsonNode score = earlier.value().path("score");
            RecordRow past = score.isInt() ? records.findById(tenantId, earlier.recordId()).orElse(null) : null;
            Integer session = past == null || past.opdVisitId() == null ? null : sessionOfVisit.get(past.opdVisitId());
            if (session != null) {
                bySession.putIfAbsent(session, score.asInt());
            }
        }
        List<SessionPain> result = new ArrayList<>();
        bySession.forEach((session, value) -> {
            if (result.size() < 60) {
                result.add(new SessionPain(session, value));
            }
        });
        return List.copyOf(result);
    }

    private ChemoInfo chemo(UUID tenantId, RecordRow row, EmrRecordDocument document) {
        if (row.patientId() == null) {
            return ChemoInfo.none();
        }
        List<ChemoOrderRow> sent = chemoOrders.sentForPatient(tenantId, row.patientId());
        Double cycle1 = null;
        double dox = 0;
        double outside = 0;
        Set<String> protocols = new HashSet<>();
        for (ChemoOrderRow order : sent) {
            if (cycle1 == null) {
                cycle1 = weightOf(order);
            }
            dox += order.doxorubicinMgM2.doubleValue();
            outside = order.priorOutsideMgM2.doubleValue();
            protocols.add(order.protocolKey);
        }
        Optional<ChemoOrderRow> own = chemoOrders.findByRecord(tenantId, row.id());
        return new ChemoInfo(cycle1, dox, outside, sent.size(), protocols, own.map(o -> o.status).orElse("NONE"),
                own.map(o -> o.cycleNo).orElse(null), own.map(o -> o.protocolKey).orElse(null),
                own.map(o -> o.levelPct).orElse(100), own.map(o -> o.priorOutsideMgM2.doubleValue()).orElse(null));
    }

    private Double weightOf(ChemoOrderRow order) {
        try {
            JsonNode weight = mapper.readTree(order.snapshot).path("weightKg");
            return weight.isNumber() ? weight.asDouble() : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static List<JsonNode> prescriptionRows(EmrRecordDocument document) {
        List<JsonNode> rows = new ArrayList<>();
        for (String key : document.sectionKeys()) {
            document.values(key).fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isObject() && "PRESCRIPTION".equals(value.path("component").asText())) {
                    value.path("rows").forEach(rows::add);
                }
            });
        }
        return rows;
    }

    static boolean hasActiveDiagnosis(EmrRecordDocument document) {
        for (String key : document.sectionKeys()) {
            var fields = document.values(key).fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                if (value.isObject() && "EMR_LIST".equals(value.path("component").asText())
                        && "DIAGNOSIS".equals(value.path("kind").asText())) {
                    for (JsonNode item : value.path("items")) {
                        if (item.path("active").asBoolean(true)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Component values of the patient's earlier SIGNED records, newest first (empty until SAH-51 signs records). */
    public List<Earlier> earlier(UUID tenantId, UUID patientId, UUID currentRecordId, String component) {
        return new EarlierLoader(tenantId, patientId, currentRecordId, null).of(component);
    }

    /** Component values of the patient's earlier SIGNED records, newest first, read once. */
    private final class EarlierLoader {
        private final UUID tenantId;
        private final UUID patientId;
        private final UUID currentRecordId;
        private final Instant signedBefore;
        private List<SignedCopy> signed;

        EarlierLoader(UUID tenantId, UUID patientId, UUID currentRecordId, Instant signedBefore) {
            this.tenantId = tenantId;
            this.patientId = patientId;
            this.currentRecordId = currentRecordId;
            this.signedBefore = signedBefore;
        }

        List<Earlier> of(String component) {
            if (patientId == null) {
                return List.of();
            }
            if (signed == null) {
                signed = records.signedForPatient(tenantId, patientId, EARLIER_SCAN);
            }
            List<Earlier> found = new ArrayList<>();
            for (SignedCopy copy : signed) {
                if (copy.recordId().equals(currentRecordId)
                        || (signedBefore != null && !copy.signedAt().isBefore(signedBefore))) {
                    continue;
                }
                EmrRecordDocument document = EmrRecordDocument.parse(mapper, copy.sections());
                for (String key : document.sectionKeys()) {
                    JsonNode value = document.values(key).path("specialty");
                    if (value.isObject() && component.equals(value.path("component").asText())) {
                        found.add(new Earlier(copy.recordId(), copy.signedAt().atZone(INDIA).toLocalDate(), value));
                        break;
                    }
                }
            }
            return found;
        }
    }
}
