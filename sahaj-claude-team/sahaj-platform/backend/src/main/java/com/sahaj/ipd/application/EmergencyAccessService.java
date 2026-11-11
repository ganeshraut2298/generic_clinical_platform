package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessGrant;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessInput;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessPatient;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessPicker;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReasonView;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewPage;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessReviewResult;
import com.sahaj.ipd.application.WardScopeRepository.Facts;
import com.sahaj.ipd.application.WardScopeRepository.Grant;
import com.sahaj.ipd.application.WardScopeRepository.Stamped;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-34 stories 34E and 34F: break-glass emergency access (ADR-017 D4 to D6, design note sections 4 and 5).
 *
 * <p>A scoped NURSE (token role and membership both NURSE, switch on) opens one admitted patient outside her wards for 30
 * minutes with one of three fixed reasons. The row, the start and the end time are written by the database (a trigger
 * stamps {@code clock_timestamp()} and adds 30 minutes); nothing here sends a time, a duration or a user. The grant, its
 * {@code bg} entry, the record-access row and the audit event are written in one transaction, after the admission row is
 * locked, so two taps make one grant. The owner reviews each grant once, never her own.
 *
 * <p>Allowed in Trial, On and Read-only IPD (OQ-34-29). The picker is a directory of admitted patients (name, bed, UHID)
 * given before a reason is chosen (option P1); each successful read writes one audit event with the count only, and the
 * list is never logged. Names are on screen only: no log line, audit row or entry holds a patient or staff name.
 */
@Service
public class EmergencyAccessService {

    static final String FORBIDDEN_MESSAGE = "Emergency access is only for staff who see some wards.";
    static final String OWNER_ONLY = "Only the owner can review emergency access.";
    static final String OWN_REVIEW = "You cannot review your own emergency access.";
    static final String NOT_ADMITTED = "This patient is not admitted any more.";
    static final String ALREADY_IN_SCOPE = "This patient is already in your list.";
    static final String ALREADY_REVIEWED = "Already reviewed.";
    static final String CHOOSE_PATIENT = "Choose a patient.";
    static final String CHOOSE_REASON = "Choose a reason for emergency access.";
    static final String TICK = "Tick the box to confirm this access is recorded.";
    static final String RELOAD = "Reload the page and try again.";
    static final String ENTITY = "IPD_EMERGENCY_ACCESS";
    static final String PICKER_VIEWED = "IPD_EMERGENCY_ACCESS_PICKER_VIEWED";
    private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,3}$");
    private static final Logger log = LoggerFactory.getLogger(EmergencyAccessService.class);

    private final WardScopeRepository repository;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientLookupPort patients;
    private final PatientAccessPort patientAccess;
    private final TeamMemberPort team;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final IpdGate gate;
    private final ScopeGuard guard;
    private final EmergencyAccessReader reader;

    EmergencyAccessService(WardScopeRepository repository, AdmissionRepository admissions,
                           ClinicalEventRepository events, PatientLookupPort patients,
                           PatientAccessPort patientAccess, TeamMemberPort team, OwnerGuard ownerGuard,
                           AuditLog auditLog, IpdGate gate, ScopeGuard guard, EmergencyAccessReader reader) {
        this.repository = repository;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.team = team;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.gate = gate;
        this.guard = guard;
        this.reader = reader;
    }

    // ---- the picker (34E) ----

    /**
     * The select "Patient" of the sheet. Check order: role (403), module Off (403), then in one REPEATABLE READ
     * transaction (not read-only, because it writes the audit row) the membership read again (NURSE, else 403) and the
     * scope (kind ALL, 403). Lists every ADMITTED admission outside the caller's scope with identity only, bed code
     * ascending (code-point order).
     *
     * <p>Writes exactly one audit event per successful read (SAH-34 security review finding 1): action
     * {@code IPD_EMERGENCY_ACCESS_PICKER_VIEWED}, entity type {@code IPD_EMERGENCY_ACCESS}, no entity id, the caller as
     * actor, after {@code {"count": N}} (0 included). No patient id, name, UHID or bed code is in it. It is written last,
     * in the same transaction: if the audit write throws, the request fails (500) and no list is returned. A refused
     * request (401, 403) writes nothing. No record-access row and no clinical event.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public EmergencyAccessPicker picker(UUID tenantId, UUID actor, String role) {
        requireNurseToken(role);
        gate.check(tenantId, false);
        PatientScope scope = narrowScope(tenantId, actor, role);
        List<Facts> outside = new ArrayList<>();
        Set<UUID> patientIds = new HashSet<>();
        for (Facts facts : repository.findAdmittedFacts(tenantId)) {
            if (!scope.allows(facts.wardId(), facts.doctorMembershipId(), facts.admissionId())) {
                outside.add(facts);
                patientIds.add(facts.patientId());
            }
        }
        outside.sort(Comparator.comparing(Facts::bedCode).thenComparing(Facts::admissionId));
        Map<UUID, PatientSummary> names = patientIds.isEmpty() ? Map.of() : patients.findAll(tenantId, patientIds);
        List<EmergencyAccessPatient> items = new ArrayList<>();
        for (Facts facts : outside) {
            PatientSummary patient = names.get(facts.patientId());
            items.add(new EmergencyAccessPatient(facts.admissionId(),
                    patient == null ? EmergencyAccessReader.PATIENT_FALLBACK : patient.name(), facts.bedCode(),
                    patient == null ? null : patient.uhid()));
        }
        auditLog.record(PICKER_VIEWED, ENTITY, null, null, Map.of("count", items.size()));
        return new EmergencyAccessPicker(List.copyOf(items), reasons());
    }

    private static List<EmergencyAccessReasonView> reasons() {
        List<EmergencyAccessReasonView> list = new ArrayList<>();
        for (EmergencyAccessReason reason : EmergencyAccessReason.values()) {
            list.add(new EmergencyAccessReasonView(reason.name(), reason.label()));
        }
        return List.copyOf(list);
    }

    // ---- open (34E) ----

    /**
     * "Open for 30 minutes". Check order: role (403), module Off (403), body (400, every error together in the order
     * admissionId, reasonKey, confirmed), then one READ COMMITTED transaction: the membership read again (403), the scope
     * (kind ALL, 403), the admission row locked, not found / another clinic's / not ADMITTED (404), already in the
     * caller's scope (409 already-in-scope, nothing written). Then the grant, the {@code bg} entry, the record-access row
     * and the audit event; any failure rolls all of them back.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmergencyAccessGrant open(UUID tenantId, UUID actor, String role, EmergencyAccessInput input) {
        requireNurseToken(role);
        gate.check(tenantId, false);
        Body body = body(input);
        PatientScope scope = narrowScope(tenantId, actor, role);

        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, body.admissionId())
                .orElseThrow(() -> new NotFoundException(NOT_ADMITTED));
        if (!"ADMITTED".equals(admission.status())) {
            throw new NotFoundException(NOT_ADMITTED);
        }
        Facts facts = repository.findFacts(tenantId, admission.id())
                .orElseThrow(() -> new NotFoundException(NOT_ADMITTED));
        // Under the admission lock: her wards, or a grant of hers that is live on the database clock now.
        if (scope.ownsPlacement(facts.wardId(), facts.doctorMembershipId())
                || repository.hasLiveGrant(tenantId, scope.membershipId(), scope.memberRole(), admission.id())) {
            throw new ConflictException("already-in-scope", "Already in your list", ALREADY_IN_SCOPE);
        }

        Stamped stamped = repository.insertGrant(tenantId, admission.id(), admission.bedId(), actor,
                scope.membershipId(), scope.memberRole(), body.reason().name());
        events.insertForGrant(tenantId, admission.id(), EmergencyAccessReader.openedText(body.reason()), actor,
                stamped.id());
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("grantId", stamped.id());
        after.put("admissionId", admission.id());
        after.put("reasonKey", body.reason().name());
        after.put("expiresAt", stamped.expiresAt().toString());
        auditLog.record("IPD_EMERGENCY_ACCESS_OPENED", ENTITY, stamped.id(), null, after);
        log.info("ipd emergency-access opened tenantId={} requestId={} grantId={} admissionId={} reason={}", tenantId,
                MDC.get("requestId"), stamped.id(), admission.id(), body.reason().name());

        PatientSummary patient = patients.find(tenantId, admission.patientId()).orElse(null);
        return new EmergencyAccessGrant(stamped.id(), admission.id(),
                patient == null ? EmergencyAccessReader.PATIENT_FALLBACK : patient.name(), admission.bedCode(),
                body.reason().name(), body.reason().label(), stamped.openedAt(), stamped.expiresAt());
    }

    /** The checked request body. */
    private record Body(UUID admissionId, EmergencyAccessReason reason) {
    }

    /** Every field error together, in the order admissionId, reasonKey, confirmed; nothing echoes what was sent. */
    private static Body body(EmergencyAccessInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        UUID admissionId = input == null ? null : uuid(input.admissionId());
        if (admissionId == null) {
            errors.add(new FieldMessage("admissionId", CHOOSE_PATIENT));
        }
        EmergencyAccessReason reason = input == null || input.reasonKey() == null || !input.reasonKey().isTextual()
                ? null : EmergencyAccessReason.ofKey(input.reasonKey().asText()).orElse(null);
        if (reason == null) {
            errors.add(new FieldMessage("reasonKey", CHOOSE_REASON));
        }
        JsonNode confirmed = input == null ? null : input.confirmed();
        if (confirmed == null || !confirmed.isBoolean() || !confirmed.booleanValue()) {
            errors.add(new FieldMessage("confirmed", TICK));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Body(admissionId, reason);
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- the owner's list and review (34F) ----

    /**
     * The next pages of "Emergency access to review". Check order: role OWNER (403), module Off (403), limit and cursor
     * (400, "Reload the page and try again."), then the membership read again (OWNER, 403). Allowed in Read-only.
     * Writes nothing: no record-access row, no audit.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EmergencyAccessReviewPage list(UUID tenantId, UUID actor, String role, String limitText, String cursorText) {
        requireOwnerToken(role);
        gate.check(tenantId, false);
        int limit = limit(limitText);
        GrantCursor.Position after = null;
        if (cursorText != null) {
            after = GrantCursor.decode(cursorText);
            if (after == null) {
                throw new InvalidInputException("cursor", RELOAD);
            }
        }
        ownerGuard.requireOwner(tenantId, actor);
        return reader.page(tenantId, repository.databaseNow(), limit, after);
    }

    private static int limit(String text) {
        if (text == null) {
            return EmergencyAccessReader.PAGE;
        }
        if (!WHOLE.matcher(text).matches()) {
            throw new InvalidInputException("limit", RELOAD);
        }
        int value = Integer.parseInt(text);
        if (value < 1 || value > EmergencyAccessReader.PAGE) {
            throw new InvalidInputException("limit", RELOAD);
        }
        return value;
    }

    /**
     * "Mark reviewed". Check order: role OWNER (403), module Off (403), then one READ COMMITTED transaction: the membership
     * read again (OWNER, 403), the grant (404), the grantee herself (403), the guarded update (409 already-reviewed when it
     * changes no row); then the {@code bg} entry and the audit event. Allowed in Read-only (it records oversight).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmergencyAccessReviewResult review(UUID tenantId, UUID actor, String role, UUID grantId) {
        requireOwnerToken(role);
        gate.check(tenantId, false);
        ownerGuard.requireOwner(tenantId, actor);
        Grant grant = repository.findGrant(tenantId, grantId).orElseThrow(() -> new NotFoundException("Not found"));
        if (grant.userId().equals(actor)) {
            throw new ForbiddenException("forbidden", "Not allowed", OWN_REVIEW);
        }
        Instant reviewedAt = repository.markReviewed(tenantId, grantId, actor)
                .orElseThrow(() -> new ConflictException("already-reviewed", "Already reviewed", ALREADY_REVIEWED));
        events.insertForGrant(tenantId, grant.admissionId(), EmergencyAccessReader.REVIEWED_TEXT, actor, grantId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("grantId", grantId);
        after.put("admissionId", grant.admissionId());
        auditLog.record("IPD_EMERGENCY_ACCESS_REVIEWED", ENTITY, grantId, null, after);
        return new EmergencyAccessReviewResult(grantId, reviewedAt, repository.countUnreviewed(tenantId));
    }

    // ---- helpers ----

    private static void requireNurseToken(String role) {
        if (!ScopeGuard.NURSE.equals(role)) {
            throw new ForbiddenException("forbidden", "Not allowed", FORBIDDEN_MESSAGE);
        }
    }

    private static void requireOwnerToken(String role) {
        if (!"OWNER".equals(role)) {
            throw new AccessDeniedException(OWNER_ONLY);
        }
    }

    /**
     * The membership read again (a NURSE, else 403) and the scope of the caller. A scope of ALL (the switch is off) is a
     * 403 with the contract's text: the button and the grant exist only for staff who see some wards.
     */
    private PatientScope narrowScope(UUID tenantId, UUID actor, String role) {
        TeamMember member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active)
                .filter(m -> ScopeGuard.NURSE.equals(m.role()))
                .orElseThrow(() -> new ForbiddenException("forbidden", "Not allowed", FORBIDDEN_MESSAGE));
        PatientScope scope = guard.scopeWithMembership(tenantId, actor, role, member.role());
        if (scope.isAll()) {
            throw new ForbiddenException("forbidden", "Not allowed", FORBIDDEN_MESSAGE);
        }
        return scope;
    }
}
