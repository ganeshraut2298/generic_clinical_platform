package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.api.EmrEncounterSource;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrEncounterSource.EmergencyCaseFacts;
import com.sahaj.opd.api.EmrIpdOrderPort;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.EmrVitalsSource;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.VisitService;
import com.sahaj.opd.application.emr.EmrCompletion.Progress;
import com.sahaj.opd.application.emr.EmrCompletion.SectionProgress;
import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrInputs.AdmissionAdviceInput;
import com.sahaj.opd.application.emr.EmrInputs.CopyItemsInput;
import com.sahaj.opd.application.emr.EmrInputs.PrintPreferencesInput;
import com.sahaj.opd.application.emr.EmrViews.CopyItemsResult;
import com.sahaj.opd.application.emr.EmrInputs.OpenInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordDraftInput;
import com.sahaj.opd.application.emr.EmrInputs.RecordSectionInput;
import com.sahaj.opd.application.emr.EmrInputs.SwitchDepartmentInput;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Snapshot;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrRecordRepository.NewRecord;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordRepository.SignedCopy;
import com.sahaj.opd.application.emr.EmrViews.AdmissionOnRecord;
import com.sahaj.opd.application.emr.EmrViews.AgeDetail;
import com.sahaj.opd.application.emr.EmrViews.AllergyAlert;
import com.sahaj.opd.application.emr.EmrViews.LastSignedPrescription;
import com.sahaj.opd.application.emr.EmrViews.PrintPreferences;
import com.sahaj.opd.application.emr.EmrViews.CopiedFrom;
import com.sahaj.opd.application.emr.EmrViews.DepartmentRef;
import com.sahaj.opd.application.emr.EmrViews.DoctorOnRecord;
import com.sahaj.opd.application.emr.EmrViews.EmergencyOnRecord;
import com.sahaj.opd.application.emr.EmrViews.IpdState;
import com.sahaj.opd.application.emr.EmrViews.OpenResult;
import com.sahaj.opd.application.emr.EmrViews.PartProgress;
import com.sahaj.opd.application.emr.EmrViews.PrefilledFrom;
import com.sahaj.opd.application.emr.EmrViews.RecordHeader;
import com.sahaj.opd.application.emr.EmrViews.RecordSection;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.EmrViews.StillNeededItem;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository;
import com.sahaj.opd.application.emr.finish.FinishModels;
import com.sahaj.opd.application.emr.specialty.SpecialtyCopy;
import com.sahaj.opd.application.emr.specialty.SpecialtyCtx;
import com.sahaj.opd.application.emr.specialty.SpecialtyProfiles;
import com.sahaj.opd.application.emr.specialty.SpecialtyRecordSupport;
import com.sahaj.opd.application.emr.specialty.SpecialtyValues;
import com.sahaj.opd.application.emr.EmrViews.UiState;
import com.sahaj.patient.api.PatientAge;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The EMR record of one encounter: open from the Queue card, read the frame, Save draft, switch department, advise
 * admission (SAH-46, SAH-47, SAH-52; ADR-020 D8 to D12). One model for an OPD visit, an IPD round and an Emergency case.
 *
 * <p>Access: only an active DOCTOR or VISITING_DOCTOR, or an OWNER with doctor details ({@link EmrAccess}), read fresh.
 * An OPD record is opened by its visit's own doctor (an owner may open any); an IPD or Emergency record follows the IPD
 * rules through {@link EmrEncounterSource} (ward scope, break-glass, visiting doctors refused). A refusal writes
 * nothing. The record stores a reference to an immutable layout, never a copy; its values are one JSON document. Open
 * is idempotent and audits every call; Save draft carries a revision and answers 409 when the record moved.
 *
 * <p>No patient name, mobile, ABHA number, field value or referral text is logged or audited here: audit values are ids,
 * section keys and fixed words. This class never logs.
 */
@Service
public class EmrRecordService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String ENTITY = "EMR_RECORD";
    private static final Set<String> OPEN_CASE_STATUSES = Set.of("WAITING", "SEEN");
    private static final Set<String> NO_INFORMATION = Set.of("none known", "not recorded", "unknown", "not known", "?",
            "none", "nil");
    private static final int MAX_REFERRAL = 300;

    private final EmrAccess access;
    private final EmrRecordRepository records;
    private final EmrDepartmentResolver resolver;
    private final EmrLayouts layouts;
    private final VisitRepository visits;
    private final VisitService visitService;
    private final PatientClinicalPort patients;
    private final TeamMemberPort teamMembers;
    private final EmrEncounterSource encounters;
    private final ModuleEntitlementPort entitlements;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final EmrComponentValues components;
    private final EmrMedicineRepository medicines;
    private final ServiceLookupPort services;
    private final ObjectProvider<EmrVisitChargePort> charges;
    /** The specialty screens (ADR-023). */
    private final SpecialtyRecordSupport specialty;
    /** The finish and sign tables and the edit hold (ADR-025). */
    private final EmrFinishRepository finish;
    private final ObjectProvider<EmrVitalsSource> vitalsSource;
    private final ObjectProvider<EmrIpdOrderPort> ipdOrders;

    public EmrRecordService(EmrAccess access, EmrRecordRepository records, EmrDepartmentResolver resolver,
                            EmrLayouts layouts, VisitRepository visits, VisitService visitService,
                            PatientClinicalPort patients, TeamMemberPort teamMembers, EmrEncounterSource encounters,
                            ModuleEntitlementPort entitlements, AuditLog auditLog, ObjectMapper mapper, Clock clock,
                            EmrComponentValues components, EmrMedicineRepository medicines,
                            ServiceLookupPort services, ObjectProvider<EmrVisitChargePort> charges,
                            SpecialtyRecordSupport specialty, EmrFinishRepository finish,
                            ObjectProvider<EmrVitalsSource> vitalsSource, ObjectProvider<EmrIpdOrderPort> ipdOrders) {
        this.finish = finish;
        this.vitalsSource = vitalsSource;
        this.ipdOrders = ipdOrders;
        this.specialty = specialty;
        this.access = access;
        this.records = records;
        this.resolver = resolver;
        this.layouts = layouts;
        this.visits = visits;
        this.visitService = visitService;
        this.patients = patients;
        this.teamMembers = teamMembers;
        this.encounters = encounters;
        this.entitlements = entitlements;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.clock = clock;
        this.components = components;
        this.medicines = medicines;
        this.services = services;
        this.charges = charges;
    }

    /** What was read about the encounter, once per request. */
    public record Encounter(String type, UUID id, VisitRow visit, AdmissionFacts admission,
                            EmergencyCaseFacts emergency, boolean closed, String ipdState) {

        public UUID patientId() {
            return switch (type) {
                case "OPD_VISIT" -> visit.patientId();
                case "IPD_ROUND" -> admission.patientId();
                default -> emergency.patientId();
            };
        }

        public boolean ipdBased() {
            return !"OPD_VISIT".equals(type);
        }
    }

    // ---- open ----

    @Transactional
    public OpenResult open(ClinicUser user, OpenInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        String type = encounterType(input);
        if (input.encounterId() == null) {
            throw invalid("encounterId", "Choose a visit, admission or case.");
        }
        UUID tenantId = user.tenantId();
        Encounter encounter = loadEncounter(tenantId, member, type, input.encounterId());
        records.lockEncounter(tenantId, encounter.id());
        Optional<RecordRow> existing = findExisting(tenantId, encounter);
        if (existing.isPresent()) {
            RecordRow row = existing.get();
            String outcome = stageOutcome(user, encounter);
            audit(tenantId, row, encounter, false);
            takeHoldIfWritable(user, member, encounter, row);
            return new OpenResult(false, outcome, view(user, member, encounter, row));
        }
        if (encounter.closed()) {
            throw EmrProblemException.encounterClosed();
        }
        if (encounter.ipdBased() && "readonly".equals(encounter.ipdState())) {
            throw EmrProblemException.ipdReadOnly();
        }
        Resolution resolution = resolver.choose(tenantId, member.membershipId());
        Instant now = clock.instant();
        EmrRecordDocument document = EmrRecordDocument.empty(mapper);
        copyFromLastVisit(tenantId, encounter, resolution, document, member.membershipId(), now);
        // SAH-59: the clinic forms live for this department (or this place) are part of the record from now on.
        List<LayoutSection> atOpen = layouts.sectionsAtOpen(tenantId, resolution.published().sections(), type);
        copyFormsFromLastVisit(tenantId, encounter, atOpen, document, member.membershipId(), now);
        String formRefs = layouts.formRefsJson(EmrFormRefs.fromSections(atOpen));
        UUID id = UUID.randomUUID();
        LocalDate round = "IPD_ROUND".equals(type) ? today() : null;
        boolean version = "PUBLISHED".equals(resolution.published().layoutSource());
        records.insert(tenantId, new NewRecord(id, type,
                "OPD_VISIT".equals(type) ? encounter.id() : null, "IPD_ROUND".equals(type) ? encounter.id() : null,
                round, "ER_ASSESSMENT".equals(type) ? encounter.id() : null, encounter.patientId(),
                member.membershipId(), resolution.department().key(), resolution.department().name(),
                resolution.department().starterKey(), version ? "DEPARTMENT_VERSION" : "STARTER_LAYOUT",
                version ? resolution.published().versionId() : null,
                version ? null : resolution.published().starterLayoutVersion(), resolution.choiceSource(),
                document.toJson(), formRefs), user.userId());
        RecordRow row = records.findById(tenantId, id).orElseThrow();
        String outcome = stageOutcome(user, encounter);
        audit(tenantId, row, encounter, true);
        storeFingerprint(tenantId, encounter, row);
        takeHoldIfWritable(user, member, encounter, row);
        return new OpenResult(true, outcome, view(user, member, encounter, row));
    }

    private String stageOutcome(ClinicUser user, Encounter encounter) {
        if (!"OPD_VISIT".equals(encounter.type())) {
            return "NOT_APPLICABLE";
        }
        return visitService.startConsultation(user.tenantId(), user.userId(), encounter.id());
    }

    private Optional<RecordRow> findExisting(UUID tenantId, Encounter encounter) {
        return switch (encounter.type()) {
            case "OPD_VISIT" -> records.findOpd(tenantId, encounter.id());
            case "IPD_ROUND" -> encounter.closed() ? records.latestIpd(tenantId, encounter.id())
                    : records.findIpd(tenantId, encounter.id(), today());
            default -> records.findEmergency(tenantId, encounter.id());
        };
    }

    /**
     * Copy from last visit (ADR-020 D8): at the first open only, for the sections whose Copy flag is on, from the
     * patient's latest SIGNED record of the same department. Copied values wait for the doctor's confirmation.
     */
    private void copyFromLastVisit(UUID tenantId, Encounter encounter, Resolution resolution,
                                   EmrRecordDocument document, UUID membershipId, Instant now) {
        UUID patientId = encounter.patientId();
        if (patientId == null) {
            return;
        }
        // ADR-025 D8: an IPD round copies from the previous signed round of the same admission, not the department rule
        Optional<SignedCopy> last = "IPD_ROUND".equals(encounter.type())
                ? records.latestSignedIpd(tenantId, encounter.id())
                : records.latestSigned(tenantId, patientId, resolution.department().key());
        if (last.isEmpty()) {
            return;
        }
        EmrRecordDocument previous = EmrRecordDocument.parse(mapper, last.get().sections());
        LocalDate signedOn = last.get().signedAt().atZone(INDIA).toLocalDate();
        for (LayoutSection section : resolution.published().sections()) {
            if (section.formSection()) {
                continue;
            }
            if (section.shown() && section.copy() && previous.has(section.key())
                    && !EmrValueRules.isEmpty(previous.values(section.key()))) {
                JsonNode values = previous.values(section.key());
                String component = SpecialtyProfiles.profileOf(encounter.type(),
                        resolution.department().starterKey()).map(p -> SpecialtyProfiles.component(p, section.key()))
                        .orElse(null);
                if (component != null) {
                    // ADR-023 D3: a specialty component copies only what its rule says (or nothing)
                    ObjectNode copied = SpecialtyCopy.copy(component, values.path("specialty"), signedOn);
                    if (copied == null) {
                        continue;
                    }
                    ObjectNode wrapper = mapper.createObjectNode();
                    wrapper.set("specialty", copied);
                    values = wrapper;
                }
                document.putCopied(section.key(), values, last.get().recordId(), signedOn, now, membershipId);
            }
        }
    }

    /**
     * Copy from last visit for clinic forms (SAH-59-5): a field whose "Copy from last visit" is on is pre-filled from the
     * patient's latest SIGNED record that holds the same form, and waits for the doctor's confirmation like any copy
     * (OQ-45-26). Only plain answers are copied; lists, medicines and body maps are never copied. A field hidden by its
     * rule in the old entry is not copied.
     */
    private void copyFormsFromLastVisit(UUID tenantId, Encounter encounter, List<LayoutSection> sections,
                                        EmrRecordDocument document, UUID membershipId, Instant now) {
        UUID patientId = encounter.patientId();
        if (patientId == null) {
            return;
        }
        List<LayoutSection> forms = sections.stream().filter(s -> s.formSection() && s.shown() && s.form() != null
                && s.form().fields() != null && s.form().fields().stream()
                        .anyMatch(f -> Boolean.TRUE.equals(f.copyFromLast()))).toList();
        if (forms.isEmpty()) {
            return;
        }
        List<SignedCopy> signed = records.signedForPatient(tenantId, patientId, 20);
        for (LayoutSection section : forms) {
            for (SignedCopy copy : signed) {
                EmrRecordDocument previous = EmrRecordDocument.parse(mapper, copy.sections());
                if (!previous.has(section.key())) {
                    continue;
                }
                JsonNode old = previous.values(section.key());
                java.util.Set<String> hidden = EmrFormValues.hiddenKeys(section.form(), old);
                ObjectNode subset = mapper.createObjectNode();
                for (EmrFormModel.Field field : section.form().fields()) {
                    JsonNode value = old.get(field.key());
                    if (Boolean.TRUE.equals(field.copyFromLast()) && EmrFormValues.fillable(field)
                            && !EmrFormValues.isComponent(field) && !hidden.contains(field.key())
                            && !EmrValueRules.isEmpty(value)) {
                        subset.set(field.key(), value);
                    }
                }
                if (!subset.isEmpty()) {
                    document.putCopied(section.key(), subset, copy.recordId(),
                            copy.signedAt().atZone(INDIA).toLocalDate(), now, membershipId);
                    break;
                }
            }
        }
    }

    private void audit(UUID tenantId, RecordRow row, Encounter encounter, boolean created) {
        Snapshot snapshot = layouts.snapshot(tenantId, row);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("encounterType", row.encounterType());
        after.put("encounterId", encounter.id());
        after.put("departmentKey", row.departmentKey());
        after.put("layoutKind", row.layoutKind());
        after.put("versionNo", snapshot.versionNo());
        after.put("created", created);
        auditLog.record("EMR_RECORD_OPENED", ENTITY, row.id(), null, after);
    }

    // ---- read ----

    /**
     * What the history and case paper reads need once access is proven: the member, the record, its patient.
     * {@code mayTakeOverHold} is true for an owner and for the admitting doctor of an IPD round (DEC-048 H35).
     */
    public record Authorized(TeamMember member, RecordRow row, UUID patientId, boolean mayTakeOverHold) {

        public Authorized(TeamMember member, RecordRow row, UUID patientId) {
            this(member, row, patientId, member != null && "OWNER".equals(member.role()));
        }
    }

    /**
     * The same access as reading the record (own patient for OPD, IPD scope for IPD and Emergency, doctors only): the
     * caller's member, the record row and the record's patient (null for an unadmitted Emergency case).
     */
    @Transactional(readOnly = true)
    public Authorized authorize(ClinicUser user, UUID recordId) {
        TeamMember member = access.requireConsultationWrite(user);
        RecordRow row = records.findById(user.tenantId(), recordId).orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(user.tenantId(), member, row.encounterType(), row.encounterId());
        return new Authorized(member, row, encounter.patientId(), mayTakeOverHold(member, encounter));
    }

    @Transactional(readOnly = true)
    public RecordView get(ClinicUser user, UUID recordId) {
        TeamMember member = access.requireConsultationWrite(user);
        RecordRow row = records.findById(user.tenantId(), recordId).orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(user.tenantId(), member, row.encounterType(), row.encounterId());
        return view(user, member, encounter, row);
    }

    // ---- Save draft ----

    @Transactional
    public RecordView saveDraft(ClinicUser user, UUID recordId, RecordDraftInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        RecordRow row = records.findById(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(tenantId, member, row.encounterType(), row.encounterId());
        if ("SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        if (encounter.ipdBased()) {
            requireWritable(row, encounter);
        }
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        if (!input.validSaveKind()) {
            throw invalid("saveKind", "Choose Manual or Auto.");
        }
        requireHold(user, member, row);
        Snapshot snapshot = layouts.snapshot(tenantId, row);
        String profile = profileOf(row);
        Map<String, LayoutSection> shown = shownByKey(snapshot, profile, row.encounterType());
        List<FieldMessage> errors = new ArrayList<>();
        String part = row.uiPart();
        String sectionKey = row.uiSectionKey();
        if (input.uiState() == null) {
            errors.add(new FieldMessage("uiState", "Send the position on the page."));
        } else {
            part = input.uiState().part();
            sectionKey = input.uiState().sectionKey();
            if (part == null || !EmrModel.PARTS.contains(part)) {
                errors.add(new FieldMessage("uiState.part", "Choose Assessment or Treatment."));
            }
            if (sectionKey != null && !shown.containsKey(sectionKey)) {
                errors.add(new FieldMessage("uiState.sectionKey", "Choose a section of this EMR."));
            }
        }
        List<RecordSectionInput> sections = input.sections() == null ? List.of() : input.sections();
        if (sections.size() > EmrLayoutRules.MAX_SECTIONS) {
            errors.add(new FieldMessage("sections", "Send at most 120 sections."));
        }
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        Set<String> knownClasses = allergyClassKeys();
        Set<String> classesBefore = EmrAllergyCheck.visitClasses(document, knownClasses);
        Instant now = clock.instant();
        EmrComponentValues.Context context = new EmrComponentValues.Context(now, member.membershipId(),
                adviceLanguage(row), lookups(tenantId));
        List<EmrComponentValues.Note> notes = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        SpecialtyCtx specialtyCtx = profile == null ? null
                : specialty.context(tenantId, row, profile, member.membershipId(), member.role(), now, document);
        for (int i = 0; i < sections.size() && errors.size() < 200; i++) {
            RecordSectionInput section = sections.get(i);
            String where = "sections[" + i + "]";
            if (section == null || section.key() == null || !shown.containsKey(section.key())
                    || !seen.add(section.key())) {
                errors.add(new FieldMessage(where + ".key", "Choose a section of this EMR."));
                continue;
            }
            ObjectNode cleaned = cleanValues(where, shown.get(section.key()), section.values(), errors,
                    document.values(section.key()), context, notes, specialtyCtx);
            if (cleaned != null && section.prefill() != null) {
                stampPrefill(tenantId, row, shown.get(section.key()), section, cleaned);
            }
            if (cleaned != null) {
                document.put(section.key(), cleaned, Boolean.TRUE.equals(section.confirmCopy()), now,
                        member.membershipId());
                keys.add(section.key());
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        String screenState = null;
        if (profile != null) {
            SpecialtyRecordSupport.ScreenOutcome outcome = specialty.applyScreen(row, profile, input.screen(),
                    specialtyCtx, member.membershipId(), now);
            screenState = outcome.screenState();
            notes.addAll(outcome.notes());
        } else if (input.screen() != null && (input.screen().mode() != null || input.screen().oncDecision() != null)) {
            throw EmrProblemException.notApplicable();
        }
        if (input.revision() != row.revision()) {
            throw EmrProblemException.emrRecordChanged();
        }
        if (!EmrAllergyCheck.visitClasses(document, knownClasses).equals(classesBefore)) {
            for (String key : document.sectionKeys()) {
                EmrAllergyCheck.clearReviews(document.mutableValues(key));
            }
            // DEC-051 Q2: a review sent in this same save was cleared just now, so it writes no audit entry
            notes.removeIf(note -> "ALLERGY_REVIEWED".equals(note.kind()));
        }
        if (keys.contains("procedures")) {
            syncServiceLines(user, encounter, shown, document);
        }
        boolean stored = profile == null
                ? records.saveDraft(tenantId, recordId, input.revision(), document.toJson(), part, sectionKey,
                        user.userId())
                : records.saveDraft(tenantId, recordId, input.revision(), document.toJson(), part, sectionKey,
                        screenState, user.userId());
        if (!stored) {
            throw EmrProblemException.emrRecordChanged();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("revision", input.revision() + 1);
        after.put("sectionKeys", keys);
        if (!input.auto()) {
            // ADR-025 D5: the 30-second autosave writes no draft audit entry; safety audits below are written either way
            auditLog.record("EMR_RECORD_DRAFT_SAVED", ENTITY, recordId, null, after);
        }
        auditNotes(recordId, notes);
        RecordRow saved = records.findById(tenantId, recordId).orElseThrow();
        if (profile != null) {
            specialty.afterSave(tenantId, user.userId(), member.membershipId(), saved, profile, document);
        }
        return view(user, member, encounter, saved);
    }

    /**
     * Validates the values of one section against its field definitions (ADR-020 D3). An empty value clears the field.
     * Returns the values to store, or null when something was wrong (the errors are added, named by field key). The
     * error text never repeats a value.
     */
    ObjectNode cleanValues(String where, LayoutSection section, JsonNode values, List<FieldMessage> errors,
                           JsonNode storedValues, EmrComponentValues.Context context,
                           List<EmrComponentValues.Note> notes) {
        return cleanValues(where, section, values, errors, storedValues, context, notes, null);
    }

    ObjectNode cleanValues(String where, LayoutSection section, JsonNode values, List<FieldMessage> errors,
                           JsonNode storedValues, EmrComponentValues.Context context,
                           List<EmrComponentValues.Note> notes, SpecialtyCtx specialtyCtx) {
        if (section.specialtyEditor() != null && specialtyCtx != null) {
            // ADR-023 D2: a section with a specialty editor takes {"specialty": component value} only.
            ObjectNode cleaned = specialty.cleanSection(where, section, values, storedValues, specialtyCtx, errors,
                    notes);
            return cleaned != null && tooLarge(where, cleaned, errors) ? null : cleaned;
        }
        if (section.formSection()) {
            // SAH-59 (ADR-022 D11): the values are checked against the form version the record was opened with.
            if (section.form() == null) {
                errors.add(new FieldMessage(where + ".key", "This form is not available in this EMR."));
                return null;
            }
            return EmrFormValues.clean(mapper, section.form(), where, values, storedValues, components, context,
                    errors, notes);
        }
        if (values == null || !values.isObject()) {
            errors.add(new FieldMessage(where + ".values", "Send the values as an object."));
            return null;
        }
        if (values.size() > 40) {
            errors.add(new FieldMessage(where + ".values", "Send at most 40 fields."));
            return null;
        }
        Map<String, FieldDefinition> fields = new HashMap<>();
        for (FieldDefinition field : section.fields()) {
            fields.put(field.key(), field);
        }
        ObjectNode cleaned = mapper.createObjectNode();
        int before = errors.size();
        Iterator<Map.Entry<String, JsonNode>> entries = values.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            FieldDefinition field = fields.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (field == null) {
                errors.add(new FieldMessage(where + ".values", "This section has a field that is not in the layout."));
                continue;
            }
            if (value == null || value.isNull()) {
                continue;
            }
            String name = where + ".values." + field.key();
            if (field.valueKind() == EmrValueKind.COMPONENT) {
                try {
                    EmrComponentValues.Result result = components.normalize(field, value,
                            storedValues == null ? null : storedValues.get(field.key()), context);
                    notes.addAll(result.notes());
                    cleaned.set(field.key(), result.value());
                } catch (EmrValueRules.InvalidValue e) {
                    errors.add(new FieldMessage(name, e.getMessage()));
                }
                continue;
            }
            try {
                EmrValueRules.check(field, value);
            } catch (EmrValueRules.InvalidValue e) {
                errors.add(new FieldMessage(name, e.getMessage()));
                continue;
            }
            if (!EmrValueRules.isEmpty(value)) {
                cleaned.set(field.key(), value);
            }
        }
        if (errors.size() > before) {
            return null;
        }
        try {
            if (mapper.writeValueAsBytes(cleaned).length > EmrValueRules.MAX_SECTION_BYTES) {
                errors.add(new FieldMessage(where + ".values", "This section is too large (32 KB at most)."));
                return null;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            errors.add(new FieldMessage(where + ".values", "These values could not be read."));
            return null;
        }
        return cleaned;
    }

    private boolean tooLarge(String where, ObjectNode cleaned, List<FieldMessage> errors) {
        try {
            if (mapper.writeValueAsBytes(cleaned).length > EmrValueRules.MAX_SECTION_BYTES) {
                errors.add(new FieldMessage(where + ".values", "This section is too large (32 KB at most)."));
                return true;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            errors.add(new FieldMessage(where + ".values", "These values could not be read."));
            return true;
        }
        return false;
    }

    private String profileOf(RecordRow row) {
        return specialty.profile(row).orElse(null);
    }

    private Set<String> allergyClassKeys() {
        Set<String> keys = new java.util.HashSet<>();
        for (EmrMedicineRepository.AllergyClassRow row : medicines.allergyClasses()) {
            keys.add(row.classKey());
        }
        return keys;
    }

    /** New advice items start in the visit print language when one is chosen (else the client sends the default). */
    private static String adviceLanguage(RecordRow row) {
        return row.printLanguage();
    }

    private EmrComponentValues.Lookups lookups(UUID tenantId) {
        return new EmrComponentValues.Lookups() {
            @Override
            public Optional<EmrComponentValues.MedicineRef> medicine(UUID medicineId) {
                return medicines.find(tenantId, medicineId).map(m -> new EmrComponentValues.MedicineRef(m.id(),
                        m.name(), m.form(), m.allergyClass(), m.defaultFrequency(), m.defaultDays(),
                        m.defaultWhen()));
            }

            @Override
            public boolean activeService(UUID serviceId) {
                return services.find(tenantId, serviceId).map(ServiceLookupPort.ServiceSummary::active).orElse(false);
            }

            private EmrIpdOrderPort.OrderOptions options;

            @Override
            public boolean ipdOrderKeys(String drugKey, String frequencyKey) {
                EmrIpdOrderPort port = ipdOrders.getIfAvailable();
                if (port == null) {
                    return true;
                }
                if (options == null) {
                    options = port.options(tenantId);
                }
                return options.drugs().stream().anyMatch(d -> d.key().equals(drugKey))
                        && options.frequencies().stream().anyMatch(f -> f.key().equals(frequencyKey));
            }
        };
    }

    /**
     * The visit bill EMR lines equal the active "Bill today" services of the procedures list (SAH-48-4, ADR-022 D7).
     * OPD visits only. A refusal (paid bill, bad service) rolls the whole save back.
     */
    private void syncServiceLines(ClinicUser user, Encounter encounter, Map<String, LayoutSection> shown,
                                  EmrRecordDocument document) {
        EmrVisitChargePort port = charges.getIfAvailable();
        LayoutSection section = shown.get("procedures");
        if (port == null || section == null || !"OPD_VISIT".equals(encounter.type())) {
            return;
        }
        List<EmrVisitChargePort.Line> wanted = new ArrayList<>();
        JsonNode values = document.values("procedures");
        for (FieldDefinition field : section.fields()) {
            if (field.component() == null || !"SERVICES".equals(field.component().listKind())
                    || !"procedure".equals(field.key())) {
                continue;
            }
            for (JsonNode item : values.path(field.key()).path("items")) {
                if (item.path("active").asBoolean(true) && "BILL_TODAY".equals(item.path("attr2").asText())) {
                    wanted.add(new EmrVisitChargePort.Line(item.path("id").asText(),
                            UUID.fromString(item.path("itemKey").asText()),
                            Integer.parseInt(item.path("attr1").asText())));
                }
            }
        }
        EmrVisitChargePort.Outcome outcome = port.syncEmrLines(user.tenantId(), user.userId(), encounter.id(), wanted);
        if (outcome == EmrVisitChargePort.Outcome.ALREADY_BILLED) {
            throw EmrProblemException.alreadyBilled();
        }
        if (outcome == EmrVisitChargePort.Outcome.INVALID_SERVICE) {
            throw invalid("sections", "Choose services from the clinic price list.");
        }
    }

    private void auditNotes(UUID recordId, List<EmrComponentValues.Note> notes) {
        for (EmrComponentValues.Note note : notes) {
            String action = note.kind().startsWith("EMR_") ? note.kind()
                    : "MLC_CHANGED".equals(note.kind()) ? "EMR_MLC_FLAG_CHANGED" : "EMR_ALLERGY_ALERT_REVIEWED";
            auditLog.record(action, ENTITY, recordId, null, note.values());
        }
    }

    /**
     * A signed earlier record of the same patient, read-only (SAH-50-4). Whoever wrote it, an OPD record opens; an IPD
     * round also needs the IPD scope of its admission.
     */
    @Transactional(readOnly = true)
    public RecordView openPast(ClinicUser user, RecordRow past) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        Encounter encounter;
        if ("OPD_VISIT".equals(past.encounterType())) {
            VisitRow visit = visits.findById(tenantId, past.encounterId())
                    .orElseThrow(EmrProblemException::pastVisitNotFound);
            encounter = new Encounter("OPD_VISIT", past.encounterId(), visit, null, null, false,
                    entitlements.forTenant(tenantId).ipdState());
        } else {
            encounter = loadEncounter(tenantId, member, past.encounterType(), past.encounterId());
        }
        return view(user, member, encounter, past).asHistory();
    }

    // ---- copy actions (SAH-48-1, SAH-50-4; ADR-022 D6) ----

    @Transactional
    public CopyItemsResult copyItems(ClinicUser user, UUID recordId, CopyItemsInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        if (input == null || input.source() == null
                || !Set.of("LAST_SIGNED_PRESCRIPTION", "PAST_RECORD").contains(input.source())) {
            throw invalid("source", "Choose the last prescription or a past visit.");
        }
        if (input.what() == null || !Set.of("MEDICINES", "TESTS").contains(input.what())) {
            throw invalid("what", "Choose medicines or tests.");
        }
        boolean last = "LAST_SIGNED_PRESCRIPTION".equals(input.source());
        if (last && (input.sourceRecordId() != null || !"MEDICINES".equals(input.what()))) {
            throw invalid("source", "The last prescription copies medicines only.");
        }
        if (!last && input.sourceRecordId() == null) {
            throw invalid("sourceRecordId", "Choose a past visit.");
        }
        RecordRow row = records.findByIdForUpdate(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(tenantId, member, row.encounterType(), row.encounterId());
        if ("SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        if ("IPD_ROUND".equals(row.encounterType()) && "MEDICINES".equals(input.what())) {
            // the round's medicines are ordered through ipd's own catalogue (ADR-025 D8): nothing to copy into
            throw EmrProblemException.notApplicable();
        }
        if (encounter.ipdBased()) {
            requireWritable(row, encounter);
        }
        requireHold(user, member, row);
        UUID patientId = encounter.patientId();
        SourceDoc found = last ? lastSignedWithMedicines(tenantId, patientId)
                : signedOfPatient(tenantId, patientId, input.sourceRecordId());
        EmrRecordDocument source = found.document();
        UUID sourceId = found.recordId();
        Snapshot snapshot = layouts.snapshot(tenantId, row);
        boolean medicinesWanted = "MEDICINES".equals(input.what());
        LayoutSection targetSection = null;
        FieldDefinition targetField = null;
        for (LayoutSection section : shownByKey(snapshot).values()) {
            for (FieldDefinition field : section.fields()) {
                if (field.component() != null && (medicinesWanted
                        ? EmrComponentBinding.PRESCRIPTION.equals(field.component().type())
                        : "INVESTIGATIONS".equals(field.component().listKind())) && targetField == null) {
                    targetSection = section;
                    targetField = field;
                }
            }
        }
        if (targetField == null) {
            throw EmrProblemException.sectionNotInLayout();
        }
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        Instant now = clock.instant();
        ObjectNode values = document.values(targetSection.key()).isObject()
                ? ((ObjectNode) document.values(targetSection.key()).deepCopy()) : mapper.createObjectNode();
        JsonNode existing = components.upgrade(targetField, values.get(targetField.key()));
        int[] counts = new int[2];
        ObjectNode merged = medicinesWanted
                ? mergeMedicines(existing, source, last ? "REPEAT" : "COPY", now, member.membershipId(), counts)
                : mergeTests(existing, source, now, member.membershipId(), counts);
        values.set(targetField.key(), merged);
        document.put(targetSection.key(), values, false, now, member.membershipId());
        if (counts[0] > 0 && !records.saveSectionsLocked(tenantId, recordId, document.toJson(), user.userId())) {
            throw EmrProblemException.emrRecordChanged();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sourceRecordId", sourceId);
        after.put("what", input.what());
        after.put("added", counts[0]);
        after.put("skipped", counts[1]);
        auditLog.record("EMR_ITEMS_COPIED", ENTITY, recordId, null, after);
        RecordRow saved = records.findById(tenantId, recordId).orElseThrow();
        return new CopyItemsResult(counts[0], counts[1], view(user, member, encounter, saved));
    }

    private record SourceDoc(UUID recordId, EmrRecordDocument document) {
    }

    private SourceDoc lastSignedWithMedicines(UUID tenantId, UUID patientId) {
        if (patientId != null) {
            for (EmrRecordRepository.SignedCopy signed : records.signedForPatient(tenantId, patientId, 20)) {
                EmrRecordDocument document = EmrRecordDocument.parse(mapper, signed.sections());
                if (hasPrescription(document)) {
                    return new SourceDoc(signed.recordId(), document);
                }
            }
        }
        throw EmrProblemException.pastVisitNotFound();
    }

    private SourceDoc signedOfPatient(UUID tenantId, UUID patientId, UUID sourceId) {
        RecordRow source = records.findById(tenantId, sourceId).orElseThrow(EmrProblemException::pastVisitNotFound);
        if (!"SIGNED".equals(source.status()) || patientId == null || !patientId.equals(source.patientId())) {
            throw EmrProblemException.pastVisitNotFound();
        }
        return new SourceDoc(sourceId, EmrRecordDocument.parse(mapper, source.sections()));
    }

    private ObjectNode mergeMedicines(JsonNode existing, EmrRecordDocument source, String how, Instant now,
                                      UUID membershipId, int[] counts) {
        ObjectNode result = existing != null && existing.isObject() ? (ObjectNode) existing.deepCopy()
                : mapper.createObjectNode();
        result.put("component", EmrComponentBinding.PRESCRIPTION);
        result.put("schemaVersion", 1);
        com.fasterxml.jackson.databind.node.ArrayNode rows = result.has("rows") && result.get("rows").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) result.get("rows") : result.putArray("rows");
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode row : rows) {
            names.add(row.path("name").asText().toLowerCase(Locale.ROOT));
        }
        for (String key : source.sectionKeys()) {
            for (JsonNode value : source.values(key)) {
                if (!EmrComponentBinding.PRESCRIPTION.equals(value.path("component").asText())) {
                    continue;
                }
                for (JsonNode old : value.path("rows")) {
                    String name = old.path("name").asText();
                    if (!names.add(name.toLowerCase(Locale.ROOT)) || rows.size() >= EmrComponentValues.MAX_RX_ROWS) {
                        counts[1]++;
                        continue;
                    }
                    ObjectNode copy = rows.addObject();
                    copy.put("id", UUID.randomUUID().toString());
                    copy.set("medicineId", old.path("medicineId"));
                    copy.put("name", name);
                    copy.set("form", old.path("form"));
                    copy.set("frequency", old.path("frequency"));
                    copy.set("days", old.path("days"));
                    copy.set("when", old.path("when"));
                    copy.put("source", old.path("medicineId").isTextual() ? how : "TYPED");
                    copy.putNull("allergyReview");
                    copy.put("addedAt", now.toString());
                    copy.put("addedByMembershipId", membershipId.toString());
                    counts[0]++;
                }
            }
        }
        return result;
    }

    private ObjectNode mergeTests(JsonNode existing, EmrRecordDocument source, Instant now, UUID membershipId,
                                  int[] counts) {
        ObjectNode result = existing != null && existing.isObject() ? (ObjectNode) existing.deepCopy()
                : mapper.createObjectNode();
        result.put("component", EmrComponentBinding.EMR_LIST);
        result.put("kind", "INVESTIGATIONS");
        result.put("schemaVersion", 1);
        com.fasterxml.jackson.databind.node.ArrayNode items = result.has("items") && result.get("items").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) result.get("items") : result.putArray("items");
        Set<String> texts = new java.util.HashSet<>();
        for (JsonNode item : items) {
            texts.add(item.path("text").asText().toLowerCase(Locale.ROOT));
        }
        for (String key : source.sectionKeys()) {
            for (JsonNode value : source.values(key)) {
                if (!"INVESTIGATIONS".equals(value.path("kind").asText())) {
                    continue;
                }
                for (JsonNode old : value.path("items")) {
                    String text = old.path("text").asText();
                    if (!old.path("active").asBoolean(true)) {
                        continue;
                    }
                    if (!texts.add(text.toLowerCase(Locale.ROOT)) || items.size() >= EmrComponentValues.MAX_LIST_ITEMS) {
                        counts[1]++;
                        continue;
                    }
                    ObjectNode copy = items.addObject();
                    copy.put("id", UUID.randomUUID().toString());
                    copy.set("itemKey", old.path("itemKey"));
                    copy.put("text", text);
                    copy.putNull("code");
                    copy.set("newToList", old.path("newToList"));
                    copy.put("attr1", "ROUTINE");
                    copy.put("attr2", "CLINIC_LAB");
                    copy.put("active", true);
                    copy.put("addedAt", now.toString());
                    copy.put("addedByMembershipId", membershipId.toString());
                    counts[0]++;
                }
            }
        }
        return result;
    }

    // ---- print preferences (SAH-48-3, SAH-55-2; ADR-022 D9) ----

    @Transactional
    public PrintPreferences setPrintPreferences(ClinicUser user, UUID recordId, PrintPreferencesInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        RecordRow row = records.findById(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        loadEncounter(tenantId, member, row.encounterType(), row.encounterId());
        if (input == null || (input.language() != null && !Set.of("EN", "MR", "HI").contains(input.language()))) {
            throw invalid("language", "Choose English, Marathi or Hindi.");
        }
        if (input.paperSize() != null && !Set.of("A4", "A5").contains(input.paperSize())) {
            throw invalid("paperSize", "Choose A4 or A5.");
        }
        records.savePrintPreferences(tenantId, recordId, input.language(), input.paperSize(), user.userId());
        return new PrintPreferences(input.language(), input.paperSize());
    }

    // ---- switch department ----

    @Transactional
    public RecordView switchDepartment(ClinicUser user, UUID recordId, SwitchDepartmentInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        RecordRow row = records.findById(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(tenantId, member, row.encounterType(), row.encounterId());
        if (input == null || input.departmentKey() == null || input.revision() == null || input.revision() < 0) {
            throw invalid(input == null || input.departmentKey() == null ? "departmentKey" : "revision",
                    "Choose a department and reload if needed.");
        }
        requireWritable(row, encounter);
        if (row.departmentKey().equals(input.departmentKey())) {
            return view(user, member, encounter, row);
        }
        Resolution target = resolver.switchTo(tenantId, input.departmentKey());
        if (input.revision() != row.revision()) {
            throw EmrProblemException.emrRecordChanged();
        }
        Snapshot old = layouts.snapshot(tenantId, row);
        boolean version = "PUBLISHED".equals(target.published().layoutSource());
        if (!records.switchLayout(tenantId, recordId, input.revision(), target.department().key(),
                target.department().name(), target.department().starterKey(),
                version ? "DEPARTMENT_VERSION" : "STARTER_LAYOUT", version ? target.published().versionId() : null,
                version ? null : target.published().starterLayoutVersion(), EmrDepartmentResolver.SWITCHED,
                user.userId())) {
            throw EmrProblemException.emrRecordChanged();
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("departmentKey", row.departmentKey());
        before.put("versionNo", old.versionNo());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("departmentKey", target.department().key());
        after.put("versionNo", version ? target.published().versionNo() : target.published().starterLayoutVersion());
        // SAH-59: the forms of the new department replace the old ones (their stored values stay in the record).
        records.saveFormRefs(tenantId, recordId, layouts.formRefsJson(EmrFormRefs.fromSections(
                layouts.sectionsAtOpen(tenantId, target.published().sections(), row.encounterType()))),
                user.userId());
        auditLog.record("EMR_RECORD_DEPARTMENT_SWITCHED", ENTITY, recordId, before, after);
        return view(user, member, encounter, records.findById(tenantId, recordId).orElseThrow());
    }

    // ---- admission advice ----

    @Transactional
    public RecordView adviseAdmission(ClinicUser user, UUID recordId, AdmissionAdviceInput input) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        RecordRow row = records.findById(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(tenantId, member, row.encounterType(), row.encounterId());
        if ("IPD_ROUND".equals(row.encounterType())) {
            throw EmrProblemException.notApplicable();
        }
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw invalid("revision", "Reload the page and try again.");
        }
        String referral = null;
        if (input.referralLine() != null) {
            referral = input.referralLine().strip();
            if (referral.isEmpty() || referral.length() > MAX_REFERRAL) {
                throw invalid("referralLine", "Enter 1 to 300 characters, or leave it out.");
            }
        }
        requireWritable(row, encounter);
        String effective = referral != null ? referral : row.referralLine();
        if (row.admissionAdvised() && Objects.equals(effective, row.referralLine())) {
            return view(user, member, encounter, row);
        }
        if (input.revision() != row.revision()) {
            throw EmrProblemException.emrRecordChanged();
        }
        if (!records.adviseAdmission(tenantId, recordId, input.revision(), effective, user.userId())) {
            throw EmrProblemException.emrRecordChanged();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("withReferral", effective != null);
        auditLog.record("EMR_ADMISSION_ADVISED", ENTITY, recordId, null, after);
        return view(user, member, encounter, records.findById(tenantId, recordId).orElseThrow());
    }

    private void requireWritable(RecordRow row, Encounter encounter) {
        if ("SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        if (encounter.closed()) {
            throw EmrProblemException.encounterClosed();
        }
        if (encounter.ipdBased() && "readonly".equals(encounter.ipdState())) {
            throw EmrProblemException.ipdReadOnly();
        }
    }

    // ---- encounter access ----

    private String encounterType(OpenInput input) {
        if (input == null || input.encounterType() == null || !Set.of("OPD_VISIT", "IPD_ROUND", "ER_ASSESSMENT")
                .contains(input.encounterType())) {
            throw invalid("encounterType", "Choose a visit, an admission or an emergency case.");
        }
        return input.encounterType();
    }

    private Encounter loadEncounter(UUID tenantId, TeamMember member, String type, UUID encounterId) {
        String ipdState = entitlements.forTenant(tenantId).ipdState();
        switch (type) {
            case "OPD_VISIT": {
                VisitRow visit = visits.findById(tenantId, encounterId)
                        .orElseThrow(EmrProblemException::encounterNotFound);
                if (!"OWNER".equals(member.role()) && !member.membershipId().equals(visit.doctorMembershipId())) {
                    throw EmrProblemException.notOwnPatient();
                }
                return new Encounter(type, encounterId, visit, null, null, false, ipdState);
            }
            case "IPD_ROUND": {
                requireIpdOn(ipdState);
                AdmissionFacts facts = encounters.admission(tenantId, encounterId)
                        .orElseThrow(EmrProblemException::encounterNotFound);
                requireAccess(facts.access());
                return new Encounter(type, encounterId, null, facts, null, !"ADMITTED".equals(facts.status()),
                        ipdState);
            }
            default: {
                requireIpdOn(ipdState);
                EmergencyCaseFacts facts = encounters.emergencyCase(tenantId, encounterId)
                        .orElseThrow(EmrProblemException::encounterNotFound);
                requireAccess(facts.access());
                return new Encounter(type, encounterId, null, null, facts, !OPEN_CASE_STATUSES.contains(facts.status()),
                        ipdState);
            }
        }
    }

    private static void requireIpdOn(String ipdState) {
        if (!"trial".equals(ipdState) && !"on".equals(ipdState) && !"readonly".equals(ipdState)) {
            throw new ModuleOffException("In-patients (IPD) is not switched on for your clinic.");
        }
    }

    private static void requireAccess(Access access) {
        if (access == Access.OUTSIDE_SCOPE) {
            throw EmrProblemException.outsideScope();
        }
        if (access == Access.FORBIDDEN) {
            throw new AccessDeniedException("You cannot open this patient's EMR.");
        }
    }

    // ---- the view ----

    /** The shown sections; for a specialty profile with the editor of each anchor section (ADR-023 D1). */
    private Map<String, LayoutSection> shownByKey(Snapshot snapshot, String profile, String encounterType) {
        Map<String, LayoutSection> shown = shownByKey(snapshot, profile);
        if (!"IPD_ROUND".equals(encounterType)) {
            return shown;
        }
        Map<String, LayoutSection> bound = new LinkedHashMap<>();
        for (LayoutSection section : shown.values()) {
            bound.put(section.key(), section.formSection() ? section : section.withFields(
                    EmrComponentBinding.forIpdRound(section.fields())));
        }
        return bound;
    }

    private Map<String, LayoutSection> shownByKey(Snapshot snapshot, String profile) {
        Map<String, LayoutSection> shown = shownByKey(snapshot);
        if (profile == null) {
            return shown;
        }
        Map<String, LayoutSection> decorated = new LinkedHashMap<>();
        for (LayoutSection section : specialty.decorate(profile, new ArrayList<>(shown.values()))) {
            decorated.put(section.key(), section);
        }
        return decorated;
    }

    private Map<String, LayoutSection> shownByKey(Snapshot snapshot) {
        Map<String, LayoutSection> shown = new LinkedHashMap<>();
        for (LayoutSection section : snapshot.sections()) {
            if (section.shown()) {
                shown.put(section.key(), section);
            }
        }
        return shown;
    }

    private RecordView view(ClinicUser user, TeamMember member, Encounter encounter, RecordRow row) {
        UUID tenantId = user.tenantId();
        Snapshot snapshot = layouts.snapshot(tenantId, row);
        String profile = profileOf(row);
        List<LayoutSection> shown = new ArrayList<>(shownByKey(snapshot, profile, row.encounterType()).values());
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        SpecialtyCtx specialtyCtx = profile == null ? null
                : specialty.context(tenantId, row, profile, member.membershipId(), member.role(), clock.instant(),
                        document);
        String orderStatus = profile == null ? "NONE" : specialty.orderStatus(tenantId, row);
        // ADR-023 D13.3: a section the visit mode hides is not rendered, not counted and never blocks completion
        List<String> hiddenKeys = profile == null ? List.of()
                : specialty.hiddenSectionKeys(profile, specialtyCtx, row, shown);
        List<LayoutSection> visible = hiddenKeys.isEmpty() ? shown
                : shown.stream().filter(s -> !hiddenKeys.contains(s.key())).toList();
        EmrCompletion.DoneOverride override = profile == null ? null
                : (section, doc) -> specialty.done(section, doc, specialtyCtx, orderStatus);
        Progress allProgress = EmrCompletion.compute(shown, document, override);
        Progress progress = hiddenKeys.isEmpty() ? allProgress : EmrCompletion.compute(visible, document, override);
        Map<UUID, String> classOf = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (EmrMedicineRepository.AllergyClassRow cls : medicines.allergyClasses()) {
            labels.put(cls.classKey(), cls.label());
        }
        List<EmrAllergyCheck.Alert> found = EmrAllergyCheck.alerts(shown, document,
                EmrAllergyCheck.visitClasses(document, labels.keySet()),
                id -> classOf.computeIfAbsent(id, key -> medicines.find(tenantId, key)
                        .map(EmrMedicineRepository.MedicineRow::allergyClass).orElse("")));
        boolean blocked = EmrAllergyCheck.blocked(found);
        Map<UUID, TeamMember> reviewers = teamMembers.findAll(tenantId, found.stream()
                .map(EmrAllergyCheck.Alert::reviewedByMembershipId).filter(Objects::nonNull).toList());
        List<AllergyAlert> alerts = found.stream().map(a -> new AllergyAlert(a.sectionKey(), a.fieldKey(), a.rowId(),
                a.medicineKey(), a.medicineName(), a.allergyClass(), labels.getOrDefault(a.allergyClass(),
                        a.allergyClass()), a.reviewed(), a.reviewedAt(),
                a.reviewedByMembershipId() == null || !reviewers.containsKey(a.reviewedByMembershipId()) ? null
                        : reviewers.get(a.reviewedByMembershipId()).name())).toList();
        Map<String, SectionProgress> byKey = new HashMap<>();
        for (SectionProgress p : allProgress.sections()) {
            byKey.put(p.key(), p);
        }
        Decor decor = new Decor(tenantId, encounter, shown, document);
        List<RecordSection> sections = new ArrayList<>();
        for (LayoutSection section : shown) {
            SectionProgress p = byKey.get(section.key());
            UUID from = document.copiedFromRecord(section.key());
            JsonNode legacy = null;
            JsonNode values;
            if (section.specialtyEditor() != null) {
                // ADR-023 D2: the editor's value; phase 2 and 3 generic values stay readable as legacyValues.
                ObjectNode special = mapper.createObjectNode();
                JsonNode stored = document.values(section.key());
                if (stored.path("specialty").isObject()) {
                    ObjectNode copy = stored.path("specialty").deepCopy();
                    SpecialtyValues.readable(copy);
                    specialty.addNames(tenantId, copy);
                    special.set("specialty", copy);
                } else if (stored.size() > 0) {
                    legacy = stored;
                }
                values = special;
            } else {
                values = valuesForView(section, document, decor);
            }
            PrefilledFrom prefilled = null;
            if (values instanceof ObjectNode object) {
                prefilled = takePrefill(tenantId, object);
                if ("OPD_VISIT".equals(row.encounterType()) && "followup".equals(section.key())) {
                    legacy = takeLegacyFollowUp(document.values(section.key()), object);
                }
            }
            sections.add(new RecordSection(section.key(), p.status(), values,
                    from == null ? null : new CopiedFrom(from, document.copiedFromDate(section.key())),
                    p.copyPending(), document.updatedAt(section.key()), legacy, prefilled));
        }
        List<PartProgress> parts = progress.parts().stream().map(p -> new PartProgress(p.part(), p.requiredDone(),
                p.requiredTotal(), p.complete(), p.sectionsDone(), p.sectionsTotal())).toList();
        boolean mlc = hasMlc(shown, document);
        String readOnlyReason = "SIGNED".equals(row.status()) ? "SIGNED" : encounter.closed() ? "ENCOUNTER_CLOSED"
                : encounter.ipdBased() && "readonly".equals(encounter.ipdState()) ? "IPD_READ_ONLY" : null;
        List<DepartmentRef> options = readOnlyReason == null ? resolver.switchOptions(tenantId) : List.of();
        List<StillNeededItem> items = new ArrayList<>();
        List<String> stillNeededKeys = progress.stillNeeded();
        boolean ready = progress.readyToComplete() && !blocked;
        JsonNode specialtyView = null;
        if (profile != null) {
            Map<String, Boolean> doneByKey = new HashMap<>();
            for (SectionProgress p : progress.sections()) {
                doneByKey.put(p.key(), p.done());
            }
            items = specialty.stillNeeded(profile, visible, doneByKey, document, specialty.screen(row), orderStatus,
                    specialty.effectiveMode(profile, specialtyCtx, row));
            stillNeededKeys = items.stream().map(StillNeededItem::key).toList();
            ready = items.isEmpty() && !blocked;
            specialtyView = specialty.view(profile, specialtyCtx, row, hiddenKeys);
        } else {
            Map<String, String> names = new HashMap<>();
            for (LayoutSection section : shown) {
                names.put(section.key(), section.name());
            }
            for (String key : progress.stillNeeded()) {
                items.add(new StillNeededItem(key, key, names.getOrDefault(key, key).toLowerCase(Locale.ROOT), null));
            }
        }
        return decorateFinish(user, member, encounter, row, new RecordView(row.id(), row.encounterType(), encounter.id(),
                row.roundDate(), row.status(),
                readOnlyReason != null, readOnlyReason, row.revision(), header(tenantId, encounter, row, mlc),
                new EmrViews.Snapshot(row.departmentKey(), row.departmentName(), row.starterKey(),
                        snapshot.layoutSource(), snapshot.versionNo(), row.choiceSource(), row.createdAt(), shown),
                List.copyOf(sections), parts, stillNeededKeys, ready,
                new UiState(row.uiPart() == null ? EmrModel.PART_ASSESSMENT : row.uiPart(), row.uiSectionKey()),
                row.admissionAdvised(), row.referralLine(), options, ipdState(member, encounter.ipdState()), alerts,
                blocked ? List.of("ALLERGY_ALERT") : List.of(), lastSignedPrescription(tenantId, encounter.patientId()),
                new PrintPreferences(row.printLanguage(), row.paperSize()), false, List.copyOf(items), specialtyView));
    }

    private static final int HOLD_SECONDS = 120;

    /** The facts of the finish and sign that the record view carries (ADR-025 D3, D4, D10). */
    private RecordView decorateFinish(ClinicUser user, TeamMember member, Encounter encounter, RecordRow row,
                                      RecordView base) {
        boolean order = "IPD_ROUND".equals(row.encounterType());
        UUID tenantId = user.tenantId();
        Optional<EmrFinishRepository.RecordState> found = finish.state(tenantId, row.id());
        boolean signed = "SIGNED".equals(row.status());
        FinishModels.EditHold hold = null;
        String reason = base.readOnlyReason();
        boolean readOnly = base.readOnly();
        if (!signed && found.isPresent() && found.get().holdActive() && found.get().holdMembershipId() != null) {
            UUID holder = found.get().holdMembershipId();
            boolean mine = holder.equals(member.membershipId());
            String name = teamMembers.find(tenantId, holder).map(TeamMember::name).orElse("Doctor");
            hold = new FinishModels.EditHold(holder, name, found.get().holdExpiresAt(), mine,
                    !mine && mayTakeOverHold(member, encounter));
            if (!mine && reason == null) {
                reason = "HELD_BY_OTHER";
                readOnly = true;
            }
        }
        String signedBy = null;
        List<FinishModels.Addendum> addenda = List.of();
        if (signed) {
            if (found.isPresent() && found.get().signedByMembershipId() != null) {
                signedBy = teamMembers.find(tenantId, found.get().signedByMembershipId()).map(TeamMember::name)
                        .orElse("Doctor");
            }
            addenda = addendaOf(tenantId, row.id());
        }
        UUID signer = found.map(EmrFinishRepository.RecordState::signedByMembershipId).orElse(null);
        boolean canAdd = signed && addenda.size() < 50 && mayAddAddendum(member, encounter, signer);
        FinishModels.AdmissionState admission = "IPD_ROUND".equals(row.encounterType()) ? FinishModels.AdmissionState.none()
                : admissionState(tenantId, encounter, row);
        return base.withFinish(readOnly, reason, signed ? row.signedAt() : null, signedBy, hold, addenda, admission,
                canAdd, order);
    }

    /** True for the admitting doctor of the admission an IPD round belongs to (ADR-025 H8, H35). */
    static boolean isAdmittingDoctor(TeamMember member, Encounter encounter) {
        return "IPD_ROUND".equals(encounter.type()) && encounter.admission() != null
                && member.membershipId().equals(encounter.admission().admittingDoctorMembershipId());
    }

    /**
     * Who may add an addendum to a signed record (DEC-048 H29): an OPD visit's own doctor or an owner; for an IPD round or an
     * Emergency record only the doctor who signed it, the admitting doctor (IPD) or an owner, not any doctor with IPD scope.
     */
    public static boolean mayAddAddendum(TeamMember member, Encounter encounter, UUID signerMembershipId) {
        if ("OWNER".equals(member.role())) {
            return true;
        }
        if (encounter.ipdBased()) {
            return member.membershipId().equals(signerMembershipId) || isAdmittingDoctor(member, encounter);
        }
        return encounter.visit() != null && member.membershipId().equals(encounter.visit().doctorMembershipId());
    }

    /** Who may take over another doctor's edit hold (DEC-048 H35): an owner; also the admitting doctor of an IPD round. */
    public static boolean mayTakeOverHold(TeamMember member, Encounter encounter) {
        return "OWNER".equals(member.role()) || isAdmittingDoctor(member, encounter);
    }

    /** The addenda of a signed record, oldest first, with the doctor names read through platform.api. */
    public List<FinishModels.Addendum> addendaOf(UUID tenantId, UUID recordId) {
        List<EmrFinishRepository.AddendumRow> rows = finish.addenda(tenantId, recordId);
        Map<UUID, TeamMember> names = rows.isEmpty() ? Map.of() : teamMembers.findAll(tenantId,
                rows.stream().map(EmrFinishRepository.AddendumRow::createdByMembershipId).toList());
        List<FinishModels.Addendum> out = new ArrayList<>();
        for (EmrFinishRepository.AddendumRow a : rows) {
            TeamMember by = names.get(a.createdByMembershipId());
            out.add(new FinishModels.Addendum(a.id(), a.reason(), a.text(), a.createdAt(),
                    by == null ? "Doctor" : by.name()));
        }
        return List.copyOf(out);
    }

    private FinishModels.AdmissionState admissionState(UUID tenantId, Encounter encounter, RecordRow row) {
        Optional<EmrFinishRepository.RequestRow> request = finish.latestRequestOfRecord(tenantId, row.id());
        if (request.isPresent() && "OPEN".equals(request.get().status())) {
            return new FinishModels.AdmissionState("REQUESTED", request.get().id(), null, null);
        }
        if (request.isPresent() && request.get().admissionId() != null) {
            String ip = encounters.openAdmissionOf(tenantId, encounter.patientId() == null ? request.get().patientId()
                    : encounter.patientId()).map(EmrEncounterSource.AdmissionRef::ipNumber).orElse(null);
            return new FinishModels.AdmissionState("ADMITTED", request.get().id(), request.get().admissionId(), ip);
        }
        UUID patientId = encounter.patientId();
        if (patientId != null) {
            Optional<EmrEncounterSource.AdmissionRef> open = encounters.openAdmissionOf(tenantId, patientId);
            if (open.isPresent()) {
                return new FinishModels.AdmissionState("ALREADY_ADMITTED", null, open.get().admissionId(),
                        open.get().ipNumber());
            }
        }
        return new FinishModels.AdmissionState(row.admissionAdvised() ? "ADVISED" : "NONE", null, null, null);
    }

    // ---- the edit hold, fingerprint and nurse vitals stamp (ADR-025 D4, D12, D13) ----

    private void takeHoldIfWritable(ClinicUser user, TeamMember member, Encounter encounter, RecordRow row) {
        if (!"DRAFT".equals(row.status()) || encounter.closed()
                || (encounter.ipdBased() && "readonly".equals(encounter.ipdState()))) {
            return;
        }
        finish.takeHold(user.tenantId(), row.id(), member.membershipId(), user.userId(), HOLD_SECONDS, false);
    }

    /**
     * A save or a copy by another doctor while the hold is active is refused (409 emr-record-held); otherwise the hold is
     * taken or renewed. The take changes nothing on a record that is no longer a draft, so a refusal is read again: a record
     * signed meanwhile is 409 emr-record-signed, not "another doctor is editing".
     */
    private void requireHold(ClinicUser user, TeamMember member, RecordRow row) {
        if (!finish.takeHold(user.tenantId(), row.id(), member.membershipId(), user.userId(), HOLD_SECONDS, false)) {
            Optional<EmrFinishRepository.RecordState> state = finish.state(user.tenantId(), row.id());
            if (state.isPresent() && "SIGNED".equals(state.get().status())) {
                throw EmrProblemException.emrRecordSigned();
            }
            String holder = state.map(EmrFinishRepository.RecordState::holdMembershipId)
                    .flatMap(id -> teamMembers.find(user.tenantId(), id)).map(TeamMember::name).orElse("another doctor");
            throw EmrProblemException.emrRecordHeld("Dr. " + holder);
        }
    }

    private void storeFingerprint(UUID tenantId, Encounter encounter, RecordRow row) {
        UUID patientId = encounter.patientId();
        if (patientId == null) {
            return;
        }
        patients.find(tenantId, patientId).ifPresent(p -> finish.setFingerprint(tenantId, row.id(), p.factsFingerprint()));
    }

    /**
     * The vitals the doctor accepted from a nurse's entry (SAH-68-1): when the values still equal the entry, the section
     * records where they came from; when they differ, nothing is stamped and the doctor is the recorder.
     */
    private void stampPrefill(UUID tenantId, RecordRow row, LayoutSection layout, RecordSectionInput section,
                              ObjectNode cleaned) {
        EmrVitalsSource source = vitalsSource.getIfAvailable();
        if (source == null || !"IPD_ROUND".equals(row.encounterType()) || row.ipdAdmissionId() == null
                || !"IPD_NURSING_CHART".equals(section.prefill().source()) || row.roundDate() == null) {
            return;
        }
        source.latestNursing(tenantId, row.ipdAdmissionId(), row.roundDate())
                .filter(r -> r.entryId().equals(section.prefill().sourceEntryId()))
                .filter(r -> sameVitals(cleaned, layout, r))
                .ifPresent(r -> {
                    ObjectNode from = cleaned.putObject("prefilledFrom");
                    from.put("source", "IPD_NURSING_CHART");
                    from.put("entryId", r.entryId().toString());
                    // the recorder is stored as a membership (ADR-025 D12); the name is added on every read
                    UUID recorder = r.recordedByUserId() == null ? null : teamMembers
                            .findActiveByUserId(tenantId, r.recordedByUserId()).map(TeamMember::membershipId).orElse(null);
                    from.put("recordedByMembershipId", recorder == null ? null : recorder.toString());
                    from.put("recordedAt", r.recordedAt().toString());
                });
    }

    /**
     * True when the section still holds exactly what the nurse recorded: blood pressure, pulse, SpO2, temperature (the field
     * is in °F, the reading in °C, so the client's conversion to one decimal) and, when the layout has a respiratory-rate
     * field, the respiratory rate. A value the doctor cleared or changed is a difference.
     */
    static boolean sameVitals(ObjectNode cleaned, LayoutSection layout, EmrVitalsSource.VitalsReading r) {
        if (!(sameNumber(cleaned.get("bp_systolic"), r.bpSystolic()) && sameNumber(cleaned.get("bp_diastolic"),
                r.bpDiastolic()) && sameNumber(cleaned.get("pulse"), r.pulse()) && sameNumber(cleaned.get("spo2"),
                r.spo2()))) {
            return false;
        }
        JsonNode temperature = cleaned.get("temperature");
        boolean hasTemperatureField = layout == null || layout.fields() == null
                || layout.fields().stream().anyMatch(f -> "temperature".equals(f.key()));
        if (!hasTemperatureField) {
            // the section cannot hold a temperature: nothing to compare
        } else if (r.temperatureC() == null) {
            if (temperature != null && !temperature.isNull()) {
                return false;
            }
        } else {
            double fahrenheit = Math.round((r.temperatureC().doubleValue() * 9 / 5 + 32) * 10.0) / 10.0;
            if (temperature == null || !temperature.isNumber() || Math.abs(temperature.asDouble() - fahrenheit) > 0.05) {
                return false;
            }
        }
        if (layout != null && layout.fields() != null) {
            for (FieldDefinition field : layout.fields()) {
                if (field.key().matches("(^|.*_)(rr|resp|respiratory)(_.*|$)")
                        && !sameNumber(cleaned.get(field.key()), r.respiratoryRate())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameNumber(JsonNode value, int expected) {
        return value != null && value.isNumber() && value.asInt(Integer.MIN_VALUE) == expected;
    }

    // ---- what the sign and the finish check need (ADR-025 D1, D2) ----

    /** The record, its encounter and the full view, proven accessible to the caller (own patient, IPD scope). */
    public record SignContext(TeamMember member, RecordRow row, Encounter encounter, RecordView view,
                              EmrRecordDocument document) {
    }

    /**
     * Reads the record for the finish check and the sign with the same access as reading it. {@code lock} takes the record
     * row lock first (the sign), so the status and revision read are the ones the sign then updates.
     */
    @Transactional
    public SignContext signContext(ClinicUser user, UUID recordId, boolean lock) {
        TeamMember member = access.requireConsultationWrite(user);
        UUID tenantId = user.tenantId();
        RecordRow row = (lock ? records.findByIdForUpdate(tenantId, recordId) : records.findById(tenantId, recordId))
                .orElseThrow(EmrProblemException::recordNotFound);
        Encounter encounter = loadEncounter(tenantId, member, row.encounterType(), row.encounterId());
        return new SignContext(member, row, encounter, view(user, member, encounter, row),
                EmrRecordDocument.parse(mapper, row.sections()));
    }

    /**
     * A signed OPD record for the reprint (ADR-025 D15): the access rules of the reprint (roles of the visit) were applied by
     * the caller, so no consultation-write check is made here. Reads only.
     */
    @Transactional(readOnly = true)
    public RecordView viewForReprint(ClinicUser user, TeamMember actor, UUID visitId) {
        UUID tenantId = user.tenantId();
        RecordRow row = records.findOpd(tenantId, visitId).orElseThrow(EmrProblemException::recordNotFound);
        VisitRow visit = visits.findById(tenantId, visitId).orElseThrow(EmrProblemException::encounterNotFound);
        Encounter encounter = new Encounter("OPD_VISIT", visitId, visit, null, null, false,
                entitlements.forTenant(tenantId).ipdState());
        return view(user, actor, encounter, row);
    }

    /** The record as the client reads it now (after the sign, an addendum or an admission request). */
    @Transactional(readOnly = true)
    public RecordView viewOf(ClinicUser user, UUID recordId) {
        return get(user, recordId);
    }

    /** What the sign counts for the bill: the lines of the record's active Bill today services, with the price list now. */
    public record BillSummary(int lines, long totalPaise, List<FinishModels.FinishBillLine> items) {
    }

    /** What the sign would do to the bill, with each line's state (the dialog's "Bill" rows). Reads only. */
    public BillSummary billPreview(ClinicUser user, Encounter encounter, RecordRow row) {
        Snapshot snapshot = layouts.snapshot(user.tenantId(), row);
        Map<String, LayoutSection> shown = shownByKey(snapshot, profileOf(row), row.encounterType());
        return billSummary(user.tenantId(), encounter, shown, EmrRecordDocument.parse(mapper, row.sections()));
    }

    /** Makes the visit bill's EMR lines equal the ticked services once more at the sign (ADR-025 D6). */
    public BillSummary syncBillLines(ClinicUser user, Encounter encounter, RecordRow row) {
        Snapshot snapshot = layouts.snapshot(user.tenantId(), row);
        Map<String, LayoutSection> shown = shownByKey(snapshot, profileOf(row), row.encounterType());
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        syncServiceLines(user, encounter, shown, document);
        return billSummary(user.tenantId(), encounter, shown, document);
    }

    private BillSummary billSummary(UUID tenantId, Encounter encounter, Map<String, LayoutSection> shown,
                                    EmrRecordDocument document) {
        LayoutSection section = shown.get("procedures");
        if (section == null || !"OPD_VISIT".equals(encounter.type())) {
            return new BillSummary(0, 0, List.of());
        }
        int lines = 0;
        long total = 0;
        List<FinishModels.FinishBillLine> items = new ArrayList<>();
        EmrVisitChargePort chargePort = charges.getIfAvailable();
        Map<String, String> billed = chargePort == null ? Map.of() : chargePort.billedState(tenantId, encounter.id());
        JsonNode values = document.values("procedures");
        for (FieldDefinition field : section.fields()) {
            if (field.component() == null || !"SERVICES".equals(field.component().listKind())
                    || !"procedure".equals(field.key())) {
                continue;
            }
            for (JsonNode item : values.path(field.key()).path("items")) {
                if (item.path("active").asBoolean(true) && "BILL_TODAY".equals(item.path("attr2").asText())) {
                    int qty = Integer.parseInt(item.path("attr1").asText());
                    long price = 0;
                    String name = item.path("text").asText("Service");
                    try {
                        Optional<ServiceLookupPort.ServiceSummary> found = services.find(tenantId,
                                UUID.fromString(item.path("itemKey").asText()));
                        price = found.map(ServiceLookupPort.ServiceSummary::pricePaise).orElse(0L);
                        name = found.map(ServiceLookupPort.ServiceSummary::name).orElse(name);
                    } catch (IllegalArgumentException e) {
                        price = 0;
                    }
                    String state = billed.get(item.path("id").asText());
                    items.add(new FinishModels.FinishBillLine(name, qty, price, price * qty,
                            state == null ? "NEW" : "ON_PAID_BILL".equals(state) ? "ALREADY_BILLED" : "ON_BILL",
                            "SERVICE"));
                    lines++;
                    total += price * qty;
                }
            }
        }
        return new BillSummary(lines, total, List.copyOf(items));
    }

    /**
     * The response-only parts of component values (docs/api/opd.yaml 0.4.0, ADR-022 D3, D8): "Added by" on list items,
     * the tariff price and the bill state on service items, "recorded by" on body map pins. They are read when the
     * record is built, through {@code platform.api} and the ports, and are never stored (the server ignores them on save).
     */
    private final class Decor {
        private final UUID tenantId;
        private final Encounter encounter;
        private final Map<UUID, TeamMember> actors;
        private final Map<UUID, Long> prices = new HashMap<>();
        private Map<String, String> billed;

        Decor(UUID tenantId, Encounter encounter, List<LayoutSection> shown, EmrRecordDocument document) {
            this.tenantId = tenantId;
            this.encounter = encounter;
            Set<UUID> ids = new java.util.HashSet<>();
            for (LayoutSection section : shown) {
                collectActors(document.values(section.key()), ids);
            }
            this.actors = ids.isEmpty() ? Map.of() : teamMembers.findAll(tenantId, ids);
        }

        private void collectActors(JsonNode node, Set<UUID> ids) {
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    if (("addedByMembershipId".equals(entry.getKey()) || "recordedByMembershipId".equals(entry.getKey()))
                            && entry.getValue().isTextual()) {
                        try {
                            ids.add(UUID.fromString(entry.getValue().textValue()));
                        } catch (IllegalArgumentException ignored) {
                            // not an id: nothing to look up
                        }
                    } else {
                        collectActors(entry.getValue(), ids);
                    }
                });
            } else if (node.isArray()) {
                node.forEach(child -> collectActors(child, ids));
            }
        }

        /** A copy of the section values with the response-only parts filled in. */
        ObjectNode apply(ObjectNode values) {
            List<String> keys = new ArrayList<>();
            values.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = values.get(key);
                if (value.isObject() && value.has("component")) {
                    values.set(key, decorate((ObjectNode) value.deepCopy()));
                }
            }
            return values;
        }

        private ObjectNode decorate(ObjectNode component) {
            String type = component.path("component").asText();
            if (EmrComponentBinding.EMR_LIST.equals(type) && component.path("items").isArray()) {
                boolean services = "SERVICES".equals(component.path("kind").asText());
                for (JsonNode raw : component.path("items")) {
                    ObjectNode item = (ObjectNode) raw;
                    putName(item, "addedByName", item.path("addedByMembershipId"));
                    if (services) {
                        putService(item);
                    }
                }
            } else if (EmrComponentBinding.BODY_MAP.equals(type) && component.path("pins").isArray()) {
                for (JsonNode raw : component.path("pins")) {
                    ObjectNode pin = (ObjectNode) raw;
                    putName(pin, "recordedByName", pin.path("recordedByMembershipId"));
                }
            }
            return component;
        }

        private void putName(ObjectNode target, String property, JsonNode membershipId) {
            String name = null;
            if (membershipId.isTextual()) {
                try {
                    TeamMember member = actors.get(UUID.fromString(membershipId.textValue()));
                    name = member == null ? null : member.name();
                } catch (IllegalArgumentException ignored) {
                    name = null;
                }
            }
            if (name == null) {
                target.putNull(property);
            } else {
                target.put(property, name);
            }
        }

        private void putService(ObjectNode item) {
            Long price = null;
            if (item.path("itemKey").isTextual()) {
                try {
                    UUID serviceId = UUID.fromString(item.path("itemKey").textValue());
                    price = prices.computeIfAbsent(serviceId, id -> services.find(tenantId, id)
                            .map(ServiceLookupPort.ServiceSummary::pricePaise).orElse(-1L));
                    price = price < 0 ? null : price;
                } catch (IllegalArgumentException ignored) {
                    price = null;
                }
            }
            if (price == null) {
                item.putNull("unitPricePaise");
            } else {
                item.put("unitPricePaise", price);
            }
            String state = billedState().get(item.path("id").asText());
            if (state == null) {
                item.putNull("billed");
            } else {
                item.put("billed", state);
            }
        }

        private Map<String, String> billedState() {
            if (billed == null) {
                EmrVisitChargePort port = charges.getIfAvailable();
                billed = port == null || !"OPD_VISIT".equals(encounter.type()) ? Map.of()
                        : port.billedState(tenantId, encounter.id());
            }
            return billed;
        }
    }


    /** The section values with phase 2 values of component fields read as component values (ADR-022 D2). */
    /**
     * Lifts the nurse-entry stamp out of a section's values into the section's own {@code prefilledFrom} (docs 0.6.0): the
     * stored stamp holds the recorder's membership, the answer the name. Null without a stamp.
     */
    private PrefilledFrom takePrefill(UUID tenantId, ObjectNode values) {
        JsonNode stamp = values.remove("prefilledFrom");
        if (stamp == null || !stamp.isObject() || !stamp.path("entryId").isTextual()) {
            return null;
        }
        try {
            UUID recorder = stamp.path("recordedByMembershipId").isTextual()
                    ? UUID.fromString(stamp.path("recordedByMembershipId").asText()) : null;
            String name = recorder == null ? null : teamMembers.find(tenantId, recorder).map(TeamMember::name).orElse(null);
            return new PrefilledFrom(stamp.path("source").asText("IPD_NURSING_CHART"),
                    UUID.fromString(stamp.path("entryId").asText()), name == null ? "Nurse" : name,
                    Instant.parse(stamp.path("recordedAt").asText()));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The old {@code next_visit} (a date or text of phase 2) and {@code reason} of an OPD follow-up section, now the FOLLOW_UP
     * component (docs 0.6.0, EmrRecordSection.legacyValues): moved out of the section's values into the read-only legacy
     * object. Null when the section holds no old value.
     */
    private JsonNode takeLegacyFollowUp(JsonNode stored, ObjectNode values) {
        ObjectNode legacy = mapper.createObjectNode();
        JsonNode next = stored.path("next_visit");
        boolean component = next.isObject() && EmrComponentBinding.FOLLOW_UP.equals(next.path("component").asText());
        if (!next.isMissingNode() && !next.isNull() && !component) {
            legacy.set("next_visit", next);
            values.remove("next_visit");
        }
        JsonNode reason = stored.path("reason");
        if (!reason.isMissingNode() && !reason.isNull()) {
            legacy.set("reason", reason);
            values.remove("reason");
        }
        return legacy.isEmpty() ? null : legacy;
    }

    private JsonNode valuesForView(LayoutSection section, EmrRecordDocument document, Decor decor) {
        JsonNode stored = document.values(section.key());
        ObjectNode values = mapper.createObjectNode();
        stored.fields().forEachRemaining(entry -> values.set(entry.getKey(), entry.getValue()));
        for (FieldDefinition field : section.fields()) {
            if (field.valueKind() == EmrValueKind.COMPONENT && values.has(field.key())) {
                values.set(field.key(), components.upgrade(field, values.get(field.key())));
            }
        }
        return decor.apply(values);
    }

    private boolean hasMlc(List<LayoutSection> shown, EmrRecordDocument document) {
        for (LayoutSection section : shown) {
            if (section.formSection()) {
                if (section.form() != null) {
                    for (String key : EmrFormValues.componentKeys(section.form(), EmrComponentBinding.BODY_MAP)) {
                        if (EmrComponentValues.hasMlcPin(document.values(section.key()).get(key))) {
                            return true;
                        }
                    }
                }
                continue;
            }
            for (FieldDefinition field : section.fields()) {
                if (field.component() != null && EmrComponentBinding.BODY_MAP.equals(field.component().type())
                        && EmrComponentValues.hasMlcPin(document.values(section.key()).get(field.key()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The patient latest signed record that holds prescription rows (always empty until SAH-51). */
    private LastSignedPrescription lastSignedPrescription(UUID tenantId, UUID patientId) {
        if (patientId == null) {
            return null;
        }
        for (EmrRecordRepository.SignedCopy signed : records.signedForPatient(tenantId, patientId, 20)) {
            if (hasPrescription(EmrRecordDocument.parse(mapper, signed.sections()))) {
                return new LastSignedPrescription(signed.recordId(), signed.signedAt().atZone(INDIA).toLocalDate());
            }
        }
        return null;
    }

    static boolean hasPrescription(EmrRecordDocument document) {
        for (String key : document.sectionKeys()) {
            for (JsonNode value : document.values(key)) {
                if (EmrComponentBinding.PRESCRIPTION.equals(value.path("component").asText())
                        && value.path("rows").isArray() && !value.path("rows").isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IpdState ipdState(TeamMember member, String state) {
        String shown = switch (state == null ? "off" : state) {
            case "trial" -> "TRIAL";
            case "on" -> "ON";
            case "readonly" -> "READ_ONLY";
            default -> "OFF";
        };
        return new IpdState(shown, "OWNER".equals(member.role()));
    }

    private RecordHeader header(UUID tenantId, Encounter encounter, RecordRow row, boolean mlc) {
        UUID patientId = encounter.patientId();
        PatientClinical patient = patientId == null ? null : patients.find(tenantId, patientId)
                .orElseThrow(EmrProblemException::encounterNotFound);
        String name;
        Integer age;
        PatientAge ageDetail = null;
        String sex;
        String uhid = null;
        String allergy = null;
        String chronic = null;
        if (patient != null) {
            name = patient.name();
            // ADR-024 D1, D2: the effective years and the unit and value from the patient module, not the stored number
            ageDetail = patient.ageDetail() != null ? patient.ageDetail()
                    : PatientAge.of(patient.dateOfBirth(), patient.age(), today());
            age = ageDetail.years();
            sex = patient.sex();
            uhid = patient.uhid();
            allergy = clean(patient.allergyText());
            chronic = clean(patient.chronicText());
        } else {
            EmergencyCaseFacts facts = encounter.emergency();
            name = facts.displayName() == null ? "Unknown patient" : facts.displayName();
            age = facts.age();
            sex = facts.sex();
        }
        if (!Set.of("F", "M", "O").contains(sex)) {
            sex = "O";
        }
        Integer lastVisitDaysAgo = null;
        Integer ordinal = null;
        String visitType = null;
        if (patientId != null) {
            Instant before = "OPD_VISIT".equals(encounter.type()) ? encounter.visit().createdAt() : clock.instant();
            lastVisitDaysAgo = visits.previousVisitAt(tenantId, patientId, before)
                    .map(at -> (int) Math.max(0, ChronoUnit.DAYS.between(at.atZone(INDIA).toLocalDate(), today())))
                    .orElse(null);
        }
        if ("OPD_VISIT".equals(encounter.type())) {
            ordinal = visits.countVisitsUpTo(tenantId, encounter.visit().patientId(), encounter.visit().createdAt());
            visitType = encounter.visit().visitType();
        }
        UUID doctorId = switch (encounter.type()) {
            case "OPD_VISIT" -> encounter.visit().doctorMembershipId();
            case "IPD_ROUND" -> encounter.admission().admittingDoctorMembershipId();
            default -> row.openedByMembershipId();
        };
        String doctorName = teamMembers.find(tenantId, doctorId).map(TeamMember::name).orElse("Doctor");
        String room = layouts.find(tenantId, row.departmentKey()).map(Department::rooms)
                .filter(rooms -> !rooms.isEmpty()).map(rooms -> rooms.get(0)).orElse(null);
        AdmissionOnRecord admission = encounter.admission() == null ? null : new AdmissionOnRecord(
                encounter.admission().admissionId(), encounter.admission().ipNumber(),
                encounter.admission().dayNumber(), encounter.admission().status());
        EmergencyOnRecord emergency = encounter.emergency() == null ? null : new EmergencyOnRecord(
                encounter.emergency().caseId(), encounter.emergency().triageLevel(),
                encounter.emergency().triageLevelName(), encounter.emergency().status());
        return new RecordHeader(patientId, name, initials(name), age, sex, uhid, allergy, chronic, lastVisitDaysAgo,
                ordinal, visitType, new DoctorOnRecord(doctorId, doctorName, room), admission, emergency, mlc,
                AgeDetail.from(ageDetail));
    }

    /** Null for no text or for a placeholder such as "None known": the tags show only recorded text (OQ-45-23). */
    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() || NO_INFORMATION.contains(trimmed.toLowerCase(Locale.ROOT)) ? null : trimmed;
    }

    /** First letters of up to two words, upper case. */
    static String initials(String name) {
        StringBuilder letters = new StringBuilder();
        for (String word : name.strip().split("[^\\p{L}]+")) {
            if (!word.isEmpty() && letters.length() < 2) {
                letters.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            }
        }
        return letters.length() == 0 ? "?" : letters.toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    private static InvalidFieldsException invalid(String field, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(field, message)));
    }
}
