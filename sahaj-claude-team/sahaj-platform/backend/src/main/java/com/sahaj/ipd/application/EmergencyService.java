package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.AdmissionViews.BedOption;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyCaseRepository.Created;
import com.sahaj.ipd.application.EmergencyCaseRepository.NewCase;
import com.sahaj.ipd.application.EmergencyViews.ActedCase;
import com.sahaj.ipd.application.EmergencyViews.Case;
import com.sahaj.ipd.application.EmergencyViews.CaseInput;
import com.sahaj.ipd.application.EmergencyViews.CaseRef;
import com.sahaj.ipd.application.EmergencyViews.Counts;
import com.sahaj.ipd.application.EmergencyViews.Handoff;
import com.sahaj.ipd.application.EmergencyViews.Level;
import com.sahaj.ipd.application.EmergencyViews.LevelInput;
import com.sahaj.ipd.application.EmergencyViews.Queue;
import com.sahaj.ipd.application.EmergencyViews.Registration;
import com.sahaj.ipd.application.EmergencyViews.Settings;
import com.sahaj.ipd.application.EmergencyViews.SettingsInput;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-28 emergency queue, triage and Emergency setup (ADR-005). Every call first re-checks the role (defence in depth)
 * and then the IPD state (Off answers 403 module-off; a write in Read-only answers 409 ipd-readonly). Every write takes
 * the clinic's IPD advisory lock (the one every IPD write uses) before it reads the case, locks the case row for
 * update, and checks the status on that locked row before anything is written, so two simultaneous red arrivals never
 * get the same bay and a case closed a moment ago answers 409 case-closed. Waiting minutes and "over target" are
 * worked out on every read from the arrival time and the clinic's targets; nothing is stored or scheduled.
 *
 * <p>One clock (ADR-005 D6): every time of an action is the database clock ({@code clock_timestamp()}), read once
 * after the clinic lock and the case lock, and used for the case update, the audit data and the answer's
 * {@code asOf}. The arrival time of a new case and the time of its triage event are stamped by the database defaults
 * at the insert, which is also after the lock. Every read (queue, counts, hand-off) runs in one REPEATABLE READ
 * transaction, so the setup and the open cases come from one snapshot.
 *
 * <p>The name, age, mobile and complaint of a case are on screen only: they are never logged and never put in an audit
 * row. Audit rows carry ids, levels, bays, scale and times. The mobile is read by the Admit hand-off only and is
 * cleared in the update that closes the case.
 */
@Service
public class EmergencyService {

    static final Set<String> READ_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE", "DOCTOR");
    static final Set<String> REGISTER_ROLES = READ_ROLES;
    static final Set<String> RETRIAGE_ROLES = READ_ROLES;
    /** Doctor has seen, Treat and release, Refer out. */
    static final Set<String> ACT_ROLES = Set.of("OWNER", "NURSE", "DOCTOR");
    /** The admit roles of SAH-23. */
    static final Set<String> ADMIT_ROLES = AdmissionService.FLOOR_ROLES;

    static final int NAME_MAX = 80;
    static final int COMPLAINT_MAX = 200;
    static final String ENTITY_CASE = "IPD_EMERGENCY_CASE";

    private static final Logger log = LoggerFactory.getLogger(EmergencyService.class);
    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final String BAY_CONSTRAINT = "ux_emergency_case_tenant_bay_open";
    private static final String CLIENT_KEY_CONSTRAINT = "uq_emergency_case_tenant_client_key";
    private static final String LEVEL_MESSAGE = "Choose a triage level";
    private static final String RELOAD_MESSAGE = "Reload the page and try again.";
    private static final String NAME_MESSAGE = "Enter a name, or tick \"Name not known yet\"";
    private static final String COMPLAINT_MESSAGE = "Enter the main complaint";

    private final EmergencyCaseRepository cases;
    private final TriageEventRepository events;
    private final WardRepository wards;
    private final IpdSettingsRepository settings;
    private final IpdCatalog catalog;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final IpdGate gate;

    EmergencyService(EmergencyCaseRepository cases, TriageEventRepository events, WardRepository wards,
                     IpdSettingsRepository settings, IpdCatalog catalog, OwnerGuard ownerGuard, AuditLog auditLog,
                     IpdGate gate) {
        this.cases = cases;
        this.events = events;
        this.wards = wards;
        this.settings = settings;
        this.catalog = catalog;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.gate = gate;
    }

    // ---- reads ----

    /**
     * The Emergency page. Changes nothing, takes no lock and writes no record-access or audit row. One REPEATABLE READ
     * snapshot for the setup and the cases, so a scale change saved during the read cannot give a 500 (code review 1).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Queue queue(UUID tenantId, String role) {
        requireRole(role, READ_ROLES, "You cannot open the emergency queue.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        EmergencySetup setup = catalog.emergencySetup(tenantId);
        List<CaseRow> open = cases.findOpen(tenantId);
        Instant now = cases.databaseNow();
        return buildQueue(role, ent, setup, now, open, null, false);
    }

    /** Counts only, for the menu badge; one REPEATABLE READ snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Counts summary(UUID tenantId, String role) {
        requireRole(role, READ_ROLES, "You cannot open the emergency queue.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        return counts(tenantId, ent);
    }

    /**
     * Counts for the tile and the tab of Beds and admissions; the caller has checked the role and the IPD state and
     * runs this inside its own transaction (REPEATABLE READ for the counts of GET /admissions).
     */
    Counts counts(UUID tenantId, ModuleEntitlement ent) {
        EmergencySetup setup = catalog.emergencySetup(tenantId);
        List<CaseRow> open = cases.findOpen(tenantId);
        Instant now = cases.databaseNow();
        int over = 0;
        for (CaseRow row : open) {
            int waiting = EmergencyRules.waitingMinutes(row.arrivedAt(), now);
            if (EmergencyRules.overTarget(row.status(), waiting, EmergencyRules.targetOf(setup, row.level()))) {
                over++;
            }
        }
        return new Counts(writable(ent), open.size(), over);
    }

    // ---- register ----

    /**
     * Quick registration. Check order of the contract: role, module Off, Read-only, client key (400), then under the
     * clinic lock: a repeat of the key answers the first case and writes nothing; otherwise the body (all field errors
     * together), the bay (level 1 only, the lowest free bay inside the configured bays), the case, the REGISTERED
     * triage event and the audit row.
     *
     * <p>A repeat answers the full card only while the case is still in the queue and the caller registered it;
     * otherwise {@code actedCase} is the small reference (id, level, status), so a closed case's name and complaint
     * are not handed out again (security review L1).
     */
    @Transactional
    public Registration register(UUID tenantId, UUID actor, String role, CaseInput input) {
        requireRole(role, REGISTER_ROLES, "You cannot register an emergency patient.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        UUID clientKey = clientKey(input);
        wards.lockTenant(tenantId);
        CaseRow first = cases.findByClientKey(tenantId, clientKey).orElse(null);
        EmergencySetup setup = catalog.emergencySetup(tenantId);
        if (first != null) {
            Instant now = cases.databaseNow();
            boolean full = first.open() && actor != null && actor.equals(first.registeredBy());
            return new Registration(buildQueue(role, ent, setup, now, cases.findOpen(tenantId), first, full), false);
        }
        Valid valid = validate(input, setup);
        String bay = valid.level() == EmergencyRules.MOST_URGENT
                ? EmergencyRules.lowestFreeBay(setup.bays(), cases.findOpen(tenantId)) : null;
        Created stored;
        try {
            stored = cases.insert(tenantId, new NewCase(clientKey, valid.displayName(), valid.age(), valid.sex(),
                    valid.mobile(), valid.complaint(), valid.identityUnknown(), valid.level(), bay, actor));
        } catch (DuplicateKeyException e) {
            // Under the clinic lock neither the bay nor the client key can clash, so a clash means the state is wrong.
            // Not retryable by the user: log the constraint only (never the row) and answer 500.
            log.error("Emergency case insert clash tenantId={} constraint={}", tenantId, clash(e));
            throw new IllegalStateException("The emergency case could not be stored", e);
        }
        events.insert(tenantId, stored.id(), null, valid.level(), setup.scale(), "REGISTERED", actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("caseId", stored.id());
        after.put("level", valid.level());
        after.put("scale", setup.scale());
        after.put("bay", bay);
        after.put("arrivedAt", stored.arrivedAt().toString());
        auditLog.record("IPD_ER_REGISTERED", ENTITY_CASE, stored.id(), null, after);
        // The new card is what was just stored (no mobile: the card never shows it). The answer's clock is the arrival
        // time the database stamped, the latest instant of this action, so the new case has waited 0 minutes.
        CaseRow created = new CaseRow(stored.id(), null, valid.displayName(), valid.age(), valid.sex(), null,
                valid.complaint(), valid.identityUnknown(), valid.level(), bay, "WAITING", stored.arrivedAt(), actor,
                null, null, null, null, null, clientKey, 0);
        return new Registration(buildQueue(role, ent, setup, stored.arrivedAt(), cases.findOpen(tenantId), created,
                true), true);
    }

    // ---- seen, re-triage, release, refer ----

    /** Doctor has seen. A case already SEEN is answered as it is and nothing is written. */
    @Transactional
    public Queue seen(UUID tenantId, UUID actor, String role, UUID caseId) {
        requireRole(role, ACT_ROLES, "You cannot mark a patient as seen.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        CaseRow row = lockOpenCase(tenantId, caseId);
        Instant now = cases.databaseNow();
        if ("WAITING".equals(row.status())) {
            requireWritten(cases.markSeen(tenantId, caseId, now, actor));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("caseId", caseId);
            after.put("level", row.level());
            after.put("seenAt", now.toString());
            auditLog.record("IPD_ER_SEEN", ENTITY_CASE, caseId, null, after);
        }
        return answer(tenantId, role, ent, catalog.emergencySetup(tenantId), now, caseId);
    }

    /**
     * Re-triage from the card. The level shape is checked before the lock; the level against the clinic's scale and
     * the case after it. The same level writes nothing. The arrival time, the status and the bay are never touched.
     */
    @Transactional
    public Queue retriage(UUID tenantId, UUID actor, String role, UUID caseId, LevelInput input) {
        requireRole(role, RETRIAGE_ROLES, "You cannot change the triage level.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        Integer level = EmergencyRules.wholeNode(input == null ? null : input.level(), 1, 5);
        if (level == null) {
            throw new InvalidInputException("level", LEVEL_MESSAGE);
        }
        wards.lockTenant(tenantId);
        EmergencySetup setup = catalog.emergencySetup(tenantId);
        if (level > setup.scale()) {
            throw new InvalidInputException("level", LEVEL_MESSAGE);
        }
        CaseRow row = lockOpenCase(tenantId, caseId);
        Instant now = cases.databaseNow();
        if (row.level() != level) {
            requireWritten(cases.updateLevel(tenantId, caseId, level));
            events.insert(tenantId, caseId, row.level(), level, setup.scale(), "RETRIAGED", actor);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("level", row.level());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("caseId", caseId);
            after.put("level", level);
            after.put("scale", setup.scale());
            after.put("at", now.toString());
            auditLog.record("IPD_ER_RETRIAGED", ENTITY_CASE, caseId, before, after);
        }
        return answer(tenantId, role, ent, setup, now, caseId);
    }

    /** Treat and release: closes the case as RELEASED. Creates no visit and no bill (OQ-28-3). */
    @Transactional
    public Queue release(UUID tenantId, UUID actor, String role, UUID caseId) {
        return close(tenantId, actor, role, caseId, "RELEASED", "IPD_ER_RELEASED", "You cannot release a patient.");
    }

    /** Refer out: closes the case as REFERRED. Prints no letter. */
    @Transactional
    public Queue refer(UUID tenantId, UUID actor, String role, UUID caseId) {
        return close(tenantId, actor, role, caseId, "REFERRED", "IPD_ER_REFERRED", "You cannot refer a patient out.");
    }

    private Queue close(UUID tenantId, UUID actor, String role, UUID caseId, String status, String action,
                        String refusal) {
        requireRole(role, ACT_ROLES, refusal);
        ModuleEntitlement ent = gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        CaseRow row = lockOpenCase(tenantId, caseId);
        Instant now = cases.databaseNow();
        // The same UPDATE closes the case and clears its mobile number (security review M1(a)).
        requireWritten(cases.close(tenantId, caseId, status, now, actor));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("caseId", caseId);
        after.put("level", row.level());
        after.put("bay", row.bay());
        after.put("closedAt", now.toString());
        auditLog.record(action, ENTITY_CASE, caseId, null, after);
        return answer(tenantId, role, ent, catalog.emergencySetup(tenantId), now, caseId);
    }

    // ---- Emergency setup ----

    @Transactional(readOnly = true)
    public Settings settings(UUID tenantId, String role) {
        requireRole(role, READ_ROLES, "You cannot open Emergency setup.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        return toSettings(catalog.emergencySetup(tenantId), "OWNER".equals(role) && !"readonly".equals(ent.ipdState()));
    }

    /**
     * Saves the whole setup. Check order: role (re-read through the owner guard), module Off, Read-only, body. One
     * transaction under the clinic lock. A change of scale maps every open case and logs it (SCALE_CHANGED); bays are
     * never taken from a case. An unchanged setup writes and audits nothing.
     */
    @Transactional
    public Settings updateSettings(UUID tenantId, UUID actor, String role, SettingsInput input) {
        requireRole(role, Set.of("OWNER"), "Only the owner can change Emergency setup.");
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        EmergencySetup next = validate(input);
        wards.lockTenant(tenantId);
        EmergencySetup current = catalog.emergencySetup(tenantId);
        if (next.equals(current)) {
            return toSettings(current, true);
        }
        Instant now = cases.databaseNow();
        int remapped = 0;
        if (next.scale() != current.scale()) {
            for (CaseRow row : cases.findOpen(tenantId)) {
                int mapped = EmergencyRules.mapLevel(row.level(), current.scale(), next.scale());
                if (mapped != row.level()) {
                    requireWritten(cases.updateLevel(tenantId, row.id(), mapped));
                }
                events.insert(tenantId, row.id(), row.level(), mapped, next.scale(), "SCALE_CHANGED", actor);
                remapped++;
            }
        }
        settings.upsertEmergencySetup(tenantId, next, actor, now);
        Map<String, Object> after = snapshot(next);
        after.put("remappedCases", remapped);
        auditLog.record("IPD_ER_SETTINGS_UPDATED", "IPD_CONFIG", null, snapshot(current), after);
        return toSettings(next, true);
    }

    // ---- the admit hand-off (used by AdmissionService, inside its transaction) ----

    /**
     * Admission step 1: the case row locked for update, after the clinic lock. 404 when it does not exist (or belongs
     * to another clinic); 409 case-closed when it is no longer WAITING or SEEN.
     */
    CaseRow lockOpenCase(UUID tenantId, UUID caseId) {
        CaseRow row = cases.findForUpdate(tenantId, caseId)
                .orElseThrow(() -> new NotFoundException("Emergency case not found"));
        return requireOpen(row);
    }

    /** The case without a lock and with its mobile number, for the options read (404, then 409 case-closed). */
    CaseRow findOpenCase(UUID tenantId, UUID caseId) {
        CaseRow row = cases.findForHandoff(tenantId, caseId)
                .orElseThrow(() -> new NotFoundException("Emergency case not found"));
        return requireOpen(row);
    }

    /**
     * Admission last step: the case becomes ADMITTED with the admission and the patient linked, and its mobile number
     * cleared, in one update; IPD_ER_ADMITTED is audited (case id, admission id, level, bay and time; never the name,
     * mobile, age or complaint). The time is the database clock, read after the clinic lock the admission took.
     */
    void markAdmitted(UUID tenantId, UUID actor, CaseRow row, UUID admissionId, UUID patientId) {
        Instant now = cases.databaseNow();
        requireWritten(cases.markAdmitted(tenantId, row.id(), admissionId, patientId, now, actor));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("caseId", row.id());
        after.put("admissionId", admissionId);
        after.put("level", row.level());
        after.put("bay", row.bay());
        after.put("closedAt", now.toString());
        auditLog.record("IPD_ER_ADMITTED", ENTITY_CASE, row.id(), null, after);
    }

    /**
     * What the Admit sheet needs; {@code free} is the free beds in the order of AdmissionOptions.beds. Opening the
     * hand-off is the one read of a single case with its mobile number, so it writes one audit row
     * IPD_ER_HANDOFF_OPENED (the case id only; the actor is the signed-in user; security review M3). The caller has
     * already answered 403, 404 and 409, so only a 200 answer is audited.
     */
    Handoff handoff(UUID tenantId, CaseRow row, List<BedOption> free) {
        EmergencySetup setup = catalog.emergencySetup(tenantId);
        List<Level> levels = EmergencyRules.levels(setup);
        int level = EmergencyRules.clampLevel(row.level(), setup.scale());
        Handoff handoff = new Handoff(row.id(), row.displayName(), row.age(), row.sex(), row.mobile(), row.complaint(),
                row.identityPending(), level, levels.get(level - 1).name(), EmergencyRules.suggestedBed(level, free));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("caseId", row.id());
        auditLog.record("IPD_ER_HANDOFF_OPENED", ENTITY_CASE, row.id(), null, after);
        return handoff;
    }

    // ---- building the answers ----

    /**
     * The answer of an action that leaves the acted case readable: the open cases (one read) and the acted case, taken
     * from them when it is still open and read once more when the action closed it.
     */
    private Queue answer(UUID tenantId, String role, ModuleEntitlement ent, EmergencySetup setup, Instant now,
                         UUID caseId) {
        List<CaseRow> open = cases.findOpen(tenantId);
        CaseRow acted = open.stream().filter(row -> row.id().equals(caseId)).findFirst()
                .orElseGet(() -> reload(tenantId, caseId));
        return buildQueue(role, ent, setup, now, open, acted, true);
    }

    /**
     * The queue from what the caller has read: one setup, one list of open cases and one clock instant ({@code asOf}).
     * {@code acted} is null for a plain read; {@code fullActed} false answers the case as a reference only.
     */
    private static Queue buildQueue(String role, ModuleEntitlement ent, EmergencySetup setup, Instant now,
                                    List<CaseRow> openRows, CaseRow acted, boolean fullActed) {
        List<Level> levels = EmergencyRules.levels(setup);
        List<CaseRow> open = new ArrayList<>(openRows);
        open.sort(EmergencyRules.queueOrder());
        List<Case> views = new ArrayList<>();
        int over = 0;
        for (CaseRow row : open) {
            Case view = toCase(row, setup, levels, now);
            if (view.overTarget()) {
                over++;
            }
            views.add(view);
        }
        String state = ent.ipdState();
        boolean notReadOnly = !"readonly".equals(state);
        ActedCase actedView = acted == null ? null
                : fullActed ? toCase(acted, setup, levels, now) : new CaseRef(acted.id(), acted.level(), acted.status());
        return new Queue(state, setup.scale(), levels, EmergencyRules.DEFAULT_LEVEL, setup.unknownPatientRegistration(),
                setup.bays(), EmergencyRules.baysFree(setup.bays(), open),
                new Counts(writable(ent), views.size(), over), List.copyOf(views),
                notReadOnly && REGISTER_ROLES.contains(role), notReadOnly && RETRIAGE_ROLES.contains(role),
                notReadOnly && ACT_ROLES.contains(role), notReadOnly && ADMIT_ROLES.contains(role), now, actedView);
    }

    private static Case toCase(CaseRow row, EmergencySetup setup, List<Level> levels, Instant now) {
        int level = EmergencyRules.clampLevel(row.level(), setup.scale());
        int waiting = EmergencyRules.waitingMinutes(row.arrivedAt(), now);
        int target = EmergencyRules.targetOf(setup, level);
        return new Case(row.id(), row.displayName(), row.age(), row.sex(), row.complaint(), row.identityPending(),
                level, levels.get(level - 1).name(), row.bay(), row.status(), row.arrivedAt(), waiting,
                target, EmergencyRules.overTarget(row.status(), waiting, target), row.seenAt());
    }

    private static boolean writable(ModuleEntitlement ent) {
        return "trial".equals(ent.ipdState()) || "on".equals(ent.ipdState());
    }

    private CaseRow reload(UUID tenantId, UUID caseId) {
        return cases.find(tenantId, caseId).orElseThrow(() -> new IllegalStateException("The case was not found"));
    }

    private static Settings toSettings(EmergencySetup setup, boolean canEdit) {
        return new Settings(setup.scale(), setup.targets3(), setup.targets5(), setup.bays(),
                setup.unknownPatientRegistration(), EmergencyRules.levels(setup), canEdit);
    }

    // ---- validation ----

    /** The checked, cleaned values of a registration. */
    private record Valid(String displayName, boolean identityUnknown, Integer age, String sex, String mobile,
                         String complaint, int level) {
    }

    private static UUID clientKey(CaseInput input) {
        UUID key = ClientKeys.parse(input == null ? null : input.clientKey());
        if (key != null) {
            return key;
        }
        throw new InvalidInputException("clientKey", RELOAD_MESSAGE);
    }

    /** All field errors together, in the order of the contract. */
    private static Valid validate(CaseInput input, EmergencySetup setup) {
        List<FieldMessage> errors = new ArrayList<>();
        // Missing, null or not a JSON boolean is never read as false (code review 5(b), 5(c)); the name checks are skipped.
        JsonNode flag = input.identityUnknown();
        boolean flagValid = flag != null && flag.isBoolean();
        if (!flagValid) {
            errors.add(new FieldMessage("identityUnknown", RELOAD_MESSAGE));
        }
        boolean unknown = flagValid && flag.booleanValue();
        if (unknown && !setup.unknownPatientRegistration()) {
            errors.add(new FieldMessage("identityUnknown", "Unknown patient registration is off. Enter a name."));
        }
        String name = null;
        if (flagValid && !unknown) {
            String raw = input.name() == null ? "" : IpdText.nfc(input.name());
            if (IpdText.hasControlOrBidi(raw)) {
                errors.add(new FieldMessage("name", "Name cannot contain control or text-direction characters"));
            } else {
                name = IpdText.strip(raw);
                if (name.isEmpty() || !IpdText.hasLetter(name)) {
                    errors.add(new FieldMessage("name", NAME_MESSAGE));
                } else if (name.codePointCount(0, name.length()) > NAME_MAX) {
                    errors.add(new FieldMessage("name", "Enter a name of up to 80 characters"));
                }
            }
        }
        Integer age = null;
        JsonNode ageNode = input.age();
        if (ageNode != null && !ageNode.isNull()) {
            age = EmergencyRules.wholeNode(ageNode, 0, 120);
            if (age == null) {
                errors.add(new FieldMessage("age", "Enter the age as a whole number from 0 to 120, or leave it empty"));
            }
        }
        String sex = input.sex();
        if (!"M".equals(sex) && !"F".equals(sex) && !"O".equals(sex)) {
            errors.add(new FieldMessage("sex", "Choose male, female or other"));
        }
        String mobile = input.mobile() == null || input.mobile().isBlank() ? null : input.mobile().strip();
        if (mobile != null && !MOBILE.matcher(mobile).matches()) {
            errors.add(new FieldMessage("mobile", "Enter a 10-digit mobile number, or leave it empty"));
        }
        String complaintRaw = input.complaint() == null ? "" : IpdText.nfc(input.complaint());
        String complaint = null;
        if (IpdText.hasControlOrBidi(complaintRaw)) {
            errors.add(new FieldMessage("complaint", "Complaint cannot contain control or text-direction characters"));
        } else {
            complaint = IpdText.strip(complaintRaw);
            if (complaint.isEmpty() || !IpdText.hasLetterOrDigit(complaint)) {
                errors.add(new FieldMessage("complaint", COMPLAINT_MESSAGE));
            } else if (complaint.codePointCount(0, complaint.length()) > COMPLAINT_MAX) {
                errors.add(new FieldMessage("complaint", "Enter the main complaint in up to 200 characters"));
            }
        }
        Integer level = EmergencyRules.wholeNode(input.level(), 1, setup.scale());
        if (level == null) {
            errors.add(new FieldMessage("level", LEVEL_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        String displayName = unknown ? EmergencyRules.unknownName(sex, age) : name;
        return new Valid(displayName, unknown, age, sex, mobile, complaint, level);
    }

    private static EmergencySetup validate(SettingsInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        Integer scale = input == null ? null : EmergencyRules.whole(input.scale(), 3, 5);
        if (scale == null || scale == 4) {
            errors.add(new FieldMessage("scale", "Choose 3 colours or 5 levels"));
        }
        List<Integer> targets3 = targets(input == null ? null : input.targets3(), 3);
        if (targets3 == null) {
            errors.add(new FieldMessage("targets3", TARGET_MESSAGE));
        }
        List<Integer> targets5 = targets(input == null ? null : input.targets5(), 5);
        if (targets5 == null) {
            errors.add(new FieldMessage("targets5", TARGET_MESSAGE));
        }
        Integer bays = input == null ? null : EmergencyRules.whole(input.bays(), 0, EmergencyRules.MAX_BAYS);
        if (bays == null) {
            errors.add(new FieldMessage("bays", "Choose 0 to 10 bays"));
        }
        if (input == null || input.unknownPatientRegistration() == null) {
            errors.add(new FieldMessage("unknownPatientRegistration", "Choose on or off"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new EmergencySetup(scale, targets3, targets5, bays, input.unknownPatientRegistration());
    }

    private static final String TARGET_MESSAGE = "Choose a target from now to 240 minutes, in steps of 5";

    /** Exactly {@code count} targets, each 0 to 240 in steps of 5; null when not. */
    private static List<Integer> targets(List<BigDecimal> values, int count) {
        if (values == null || values.size() != count) {
            return null;
        }
        List<Integer> result = new ArrayList<>();
        for (BigDecimal value : values) {
            Integer minutes = EmergencyRules.whole(value, 0, EmergencyRules.MAX_TARGET);
            if (minutes == null || minutes % EmergencyRules.TARGET_STEP != 0) {
                return null;
            }
            result.add(minutes);
        }
        return List.copyOf(result);
    }

    // ---- helpers ----

    private static void requireRole(String role, Set<String> allowed, String message) {
        if (role == null || !allowed.contains(role)) {
            throw new AccessDeniedException(message);
        }
    }

    private static CaseRow requireOpen(CaseRow row) {
        // The status is read from the locked row, never from an earlier read.
        if (!row.open()) {
            throw caseClosed();
        }
        return row;
    }

    static ConflictException caseClosed() {
        return new ConflictException("case-closed", "Patient is no longer in the queue",
                "This patient is no longer in the queue.");
    }

    /** A guarded update that changes no row means the locked row and the update disagree: nothing may go on. */
    private static void requireWritten(boolean written) {
        if (!written) {
            throw new IllegalStateException("The emergency case was not changed");
        }
    }

    private static String clash(DuplicateKeyException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(BAY_CONSTRAINT)) {
                return BAY_CONSTRAINT;
            }
            if (message != null && message.contains(CLIENT_KEY_CONSTRAINT)) {
                return CLIENT_KEY_CONSTRAINT;
            }
        }
        return "unknown";
    }

    private static Map<String, Object> snapshot(EmergencySetup setup) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scale", setup.scale());
        m.put("targets3", setup.targets3());
        m.put("targets5", setup.targets5());
        m.put("bays", setup.bays());
        m.put("unknownPatientRegistration", setup.unknownPatientRegistration());
        return m;
    }
}
