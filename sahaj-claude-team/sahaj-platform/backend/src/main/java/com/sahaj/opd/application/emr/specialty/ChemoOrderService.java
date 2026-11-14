package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.course.TreatmentCourseRepository;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ActionInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.CheckerInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.FinalDose;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.HoldInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.OrderInput;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.Computed;
import com.sahaj.opd.application.emr.specialty.ChemoOrderCompute.OrderInputs;
import com.sahaj.opd.application.emr.specialty.ChemoOrderLogic.Fresh;
import com.sahaj.opd.domain.emr.specialty.ChemoRules;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chemotherapy order of an oncology visit (SAH-66, SAH-70; ADR-023 D6, DEC-039 addendum): the sheet, the dose maths,
 * the ten safety checks, the independent second check, send and hold. One order per record, created by the first save of
 * the sheet. The server works out every dose, check and the fingerprint on every save, read and send; a confirmed second
 * check always belongs to the current fingerprint and is cleared, with an audit entry, by any change after it. A SENT or
 * HELD order is never changed again.
 *
 * <p>Who: {@code clinic.consultation.write} and the own-patient rule of the record (through
 * {@link EmrRecordService#authorize}). The checker is an ACTIVE member with role NURSE, PHARMACIST, DOCTOR,
 * VISITING_DOCTOR or OWNER with doctor details, and never the prescriber: neither the record's doctor nor any membership
 * that saved the order. Audit entries carry ids, the protocol key, the level and counts: never a dose, a drug, a lab, a PIN
 * or a patient name. This class never logs.
 */
@Service
public class ChemoOrderService implements SpecialtyRecordSupport.ChemoOrderHook {

    static final String ENTITY = "CHEMO_ORDER";
    static final Set<String> CHECKER_ROLES = Set.of("NURSE", "PHARMACIST", "DOCTOR", "VISITING_DOCTOR", "OWNER");
    private static final int MAX_FINAL_DOSES = 6;

    private final EmrRecordService records;
    private final EmrRecordRepository recordRepository;
    private final ChemoOrderRepository orders;
    private final ChemoOrderLogic logic;
    private final VisitRepository visits;
    private final TeamMemberPort team;
    private final StaffPinPort staffPin;
    private final TreatmentCourseRepository courses;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ChemoOrderService(EmrRecordService records, EmrRecordRepository recordRepository, ChemoOrderRepository orders,
                             ChemoOrderLogic logic, VisitRepository visits, TeamMemberPort team, StaffPinPort staffPin,
                             TreatmentCourseRepository courses, AuditLog auditLog, ObjectMapper mapper, Clock clock) {
        this.records = records;
        this.recordRepository = recordRepository;
        this.orders = orders;
        this.logic = logic;
        this.visits = visits;
        this.team = team;
        this.staffPin = staffPin;
        this.courses = courses;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The caller, the record and its document, after the access checks every operation shares. */
    record Ctx(TeamMember member, RecordRow row, UUID patientId, EmrRecordDocument document, ClinicUser user) {
    }

    Ctx open(ClinicUser user, UUID recordId, boolean forWrite) {
        Authorized authorized = records.authorize(user, recordId);
        RecordRow row = authorized.row();
        if (SpecialtyProfiles.profileOf(row.encounterType(), row.starterKey()).filter("onco"::equals).isEmpty()) {
            throw EmrProblemException.notApplicable();
        }
        if (forWrite && "SIGNED".equals(row.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        // The record may have been written in this transaction already: read it again for the latest values.
        RecordRow fresh = recordRepository.findById(user.tenantId(), recordId).orElseThrow(
                EmrProblemException::recordNotFound);
        return new Ctx(authorized.member(), fresh, authorized.patientId(),
                EmrRecordDocument.parse(mapper, fresh.sections()), user);
    }

    // ---- read ----

    @Transactional(readOnly = true)
    public ObjectNode get(ClinicUser user, UUID recordId) {
        Ctx ctx = open(user, recordId, false);
        UUID tenantId = user.tenantId();
        Optional<ChemoOrderRow> order = orders.findByRecord(tenantId, recordId);
        Instant now = clock.instant();
        if (order.isPresent()) {
            Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), logic.parseInputs(order.get().inputs),
                    order.get().checkerMembershipId, ctx.member().membershipId(), now);
            return logic.sheet(tenantId, order.get(), fresh, recordId);
        }
        ChemoOrderCompute.OrderInputs defaults = defaultInputs(tenantId, ctx);
        Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), defaults, null, ctx.member().membershipId(),
                now);
        return logic.sheet(tenantId, null, fresh, recordId);
    }

    private OrderInputs defaultInputs(UUID tenantId, Ctx ctx) {
        SpecialtyCtx.ChemoInfo info = chemoInfoOf(tenantId, ctx);
        Protocol protocol = logic.defaultProtocol(info);
        if (protocol == null) {
            throw new IllegalStateException("No chemotherapy protocol is set up");
        }
        JsonNode decision = ctxScreen(ctx).path("oncDecision").path("choice");
        int level = "GIVE_REDUCED".equals(decision.asText()) ? ChemoRules.nextLevelDown(100) : 100;
        return new OrderInputs(protocol.key(), level, null, null, Map.of(), Set.of(), false, false, null, null);
    }

    private JsonNode ctxScreen(Ctx ctx) {
        if (ctx.row().screenState() == null) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(ctx.row().screenState());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored screen state could not be read", e);
        }
    }

    private SpecialtyCtx.ChemoInfo chemoInfoOf(UUID tenantId, Ctx ctx) {
        List<ChemoOrderRow> sent = ctx.patientId() == null ? List.of() : orders.sentForPatient(tenantId, ctx.patientId());
        Set<String> protocols = new LinkedHashSet<>();
        sent.forEach(o -> protocols.add(o.protocolKey));
        return new SpecialtyCtx.ChemoInfo(null, 0, 0, sent.size(), protocols, "NONE", null, null, 100, null);
    }

    // ---- save (PUT) ----

    @Transactional
    public ObjectNode save(ClinicUser user, UUID recordId, OrderInput input) {
        Ctx ctx = open(user, recordId, true);
        UUID tenantId = user.tenantId();
        OrderInputs inputs = validate(input);
        Optional<ChemoOrderRow> existing = orders.findByRecordForUpdate(tenantId, recordId);
        Instant now = clock.instant();
        UUID membershipId = ctx.member().membershipId();
        if (existing.isPresent()) {
            ChemoOrderRow order = existing.get();
            if (!order.draft()) {
                throw EmrProblemException.of(409, "chemo-order-sent", "The order is already sent",
                        "This order is already sent or on hold and cannot be changed.");
            }
            if (input.revision() != order.revision) {
                throw changed();
            }
            if (membershipId.equals(order.checkerMembershipId)) {
                throw new InvalidFieldsException(List.of(new FieldMessage("checkerMembershipId",
                        "The second checker cannot change this order. Remove the checker first.")));
            }
        } else if (input.revision() != 0) {
            throw changed();
        }
        Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), inputs,
                existing.map(o -> o.checkerMembershipId).orElse(null), membershipId, now);
        Protocol protocol = fresh.computed().protocol();
        for (FinalDose dose : input.finalDoses() == null ? List.<FinalDose>of() : input.finalDoses()) {
            if (protocol.drug(dose.drugKey()) == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("finalDoses", "Choose a drug of the protocol.")));
            }
        }
        for (String key : inputs.premedsNotGiven()) {
            if (protocol.premeds().stream().noneMatch(p -> p.key().equals(key))) {
                throw new InvalidFieldsException(List.of(new FieldMessage("premedsNotGiven",
                        "Choose a pre-medicine of the protocol.")));
            }
        }
        ChemoOrderRow row = existing.orElseGet(ChemoOrderRow::new);
        boolean created = existing.isEmpty();
        long expected = row.revision;
        UUID record = recordId;
        if (created) {
            row.id = UUID.randomUUID();
            row.recordId = record;
            row.recordCreatedAt = ctx.row().createdAt();
            row.patientId = ctx.patientId();
            row.prescriberMembershipId = membershipId;
            row.cycleNo = fresh.info().sentCount() + 1;
            row.courseId = activeChemoCourse(tenantId, ctx.patientId());
        }
        String before = row.fingerprint;
        applyComputed(row, inputs, fresh);
        row.lastEditedByMembershipId = membershipId;
        boolean cleared = clearIfChanged(row, before, now);
        if (created) {
            orders.insert(tenantId, row, user.userId());
        } else if (!orders.update(tenantId, row, expected, user.userId())) {
            throw changed();
        }
        if (cleared) {
            auditCleared(row, "ORDER_CHANGED");
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("recordId", recordId);
        audit.put("protocolKey", protocol.key());
        audit.put("levelPct", inputs.levelPct());
        audit.put("checksPassed", ChemoRules.passedCount(fresh.computed().checks()));
        auditLog.record("CHEMO_ORDER_SAVED", ENTITY, row.id, null, audit);
        ChemoOrderRow saved = orders.findById(tenantId, row.id).orElseThrow();
        return logic.sheet(tenantId, saved, fresh, recordId);
    }

    private UUID activeChemoCourse(UUID tenantId, UUID patientId) {
        if (patientId == null) {
            return null;
        }
        return courses.forPatient(tenantId, patientId, 50).stream()
                .filter(c -> "CHEMO".equals(c.kind()) && "ACTIVE".equals(c.status())).map(c -> c.id()).findFirst()
                .orElse(null);
    }

    /** Copies the inputs and the computed values into the row (protocol, level, reason, doses, snapshot, fingerprint). */
    private void applyComputed(ChemoOrderRow row, OrderInputs inputs, Fresh fresh) {
        Computed c = fresh.computed();
        row.protocolKey = c.protocol().key();
        row.levelPct = inputs.levelPct();
        row.reductionReasonKey = inputs.reductionReasonKey();
        row.reductionReasonText = inputs.reductionReasonKey() != null ? null : inputs.reductionReasonText();
        row.inputs = logic.writeInputs(inputs);
        row.fingerprint = c.fingerprint();
        row.doxorubicinMgM2 = BigDecimal.valueOf(c.thisCycleMgM2());
        row.priorOutsideMgM2 = BigDecimal.valueOf(inputs.priorOutsideMgM2() == null ? fresh.info().latestOutsideMgM2()
                : inputs.priorOutsideMgM2());
        row.snapshot = withVersion(logic.draftView(null, row, fresh, row.recordId));
    }

    private String withVersion(ObjectNode snapshot) {
        snapshot.put("schemaVersion", 1);
        return snapshot.toString();
    }

    /** Clears a confirmation that no longer matches the fingerprint. True when one was cleared. */
    private boolean clearIfChanged(ChemoOrderRow row, String beforeFingerprint, Instant now) {
        if (row.confirmedAt != null && !row.fingerprint.equals(row.confirmedFingerprint)) {
            row.confirmedByMembershipId = null;
            row.confirmedByUserId = null;
            row.confirmedVia = null;
            row.confirmedAt = null;
            row.confirmedFingerprint = null;
            row.confirmationClearedAt = now;
            return true;
        }
        return false;
    }

    private void auditCleared(ChemoOrderRow row, String reasonKey) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("recordId", row.recordId);
        audit.put("reasonKey", reasonKey);
        auditLog.record("CHEMO_SECOND_CHECK_CLEARED", ENTITY, row.id, null, audit);
    }

    private OrderInputs validate(OrderInput input) {
        List<FieldMessage> errors = new ArrayList<>();
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw new InvalidFieldsException(List.of(new FieldMessage("revision", "Reload the page and try again.")));
        }
        SpecialtyReferenceData data = logic.reference();
        if (input.protocolKey() == null || data.protocol(input.protocolKey()) == null) {
            errors.add(new FieldMessage("protocolKey", "Choose a protocol from the list."));
        }
        if (input.levelPct() == null || !ChemoRules.LEVELS.contains(input.levelPct())) {
            errors.add(new FieldMessage("levelPct", "Choose 100, 90, 80 or 75."));
        }
        String key = input.reductionReasonKey();
        if (key != null && !ChemoRules.REDUCTION_REASONS.contains(key)) {
            errors.add(new FieldMessage("reductionReasonKey", "Choose a reason from the list."));
        }
        String text = input.reductionReasonText() == null ? null : input.reductionReasonText().strip();
        if (text != null && (text.isEmpty() || text.length() > 200 || text.matches(".*<[a-zA-Z/!].*"))) {
            errors.add(new FieldMessage("reductionReasonText", "Enter 1 to 200 characters without markup."));
        }
        if (input.scheme() != null && !ChemoRules.SCHEMES.contains(input.scheme())) {
            errors.add(new FieldMessage("scheme", "Choose a valid option"));
        }
        Double prior = input.priorOutsideDoxorubicinMgM2();
        if (prior != null && (prior < 0 || prior > 2000)) {
            errors.add(new FieldMessage("priorOutsideDoxorubicinMgM2", "Enter 0 to 2000."));
        }
        Map<String, Double> doses = new LinkedHashMap<>();
        List<FinalDose> given = input.finalDoses() == null ? List.of() : input.finalDoses();
        if (given.size() > MAX_FINAL_DOSES) {
            errors.add(new FieldMessage("finalDoses", "Send at most 6 doses."));
        }
        for (int i = 0; i < given.size() && i < MAX_FINAL_DOSES; i++) {
            FinalDose dose = given.get(i);
            if (dose == null || dose.drugKey() == null || dose.drugKey().length() > 40 || dose.finalMg() == null
                    || dose.finalMg() < 0 || dose.finalMg() > 10000) {
                errors.add(new FieldMessage("finalDoses[" + i + "]", "Enter a dose from 0 to 10000 mg."));
            } else {
                doses.put(dose.drugKey(), dose.finalMg());
            }
        }
        Set<String> premeds = new LinkedHashSet<>();
        if (input.premedsNotGiven() != null) {
            if (input.premedsNotGiven().size() > 10) {
                errors.add(new FieldMessage("premedsNotGiven", "Send at most 10."));
            }
            premeds.addAll(input.premedsNotGiven());
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new OrderInputs(input.protocolKey(), input.levelPct(), key,
                key != null || text == null ? null : text, doses, premeds,
                Boolean.TRUE.equals(input.allergyConfirmed()), Boolean.TRUE.equals(input.consentNewSigned()),
                input.scheme(), prior);
    }

    private static EmrProblemException changed() {
        return EmrProblemException.of(409, "chemo-order-changed", "The order changed",
                "This order was changed. Reload it and check again.");
    }

    private static EmrProblemException notFound() {
        return EmrProblemException.of(404, "order-not-found", "Order not found", "There is no such chemotherapy order.");
    }

    // ---- checker options and naming ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> checkerOptions(ClinicUser user, UUID recordId) {
        Ctx ctx = open(user, recordId, false);
        UUID tenantId = user.tenantId();
        ChemoOrderRow order = orders.findByRecord(tenantId, recordId).orElseThrow(ChemoOrderService::notFound);
        Set<UUID> excluded = excluded(tenantId, ctx, order);
        List<TeamMember> candidates = new ArrayList<>();
        for (TeamMember member : team.findActiveMembers(tenantId)) {
            if (isCheckerRole(tenantId, member) && !excluded.contains(member.membershipId())) {
                candidates.add(member);
            }
        }
        Set<UUID> ids = new LinkedHashSet<>();
        candidates.forEach(m -> ids.add(m.membershipId()));
        Set<UUID> withPin = staffPin.membershipsWithPin(tenantId, ids);
        List<Map<String, Object>> options = new ArrayList<>();
        for (TeamMember member : candidates) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("membershipId", member.membershipId());
            option.put("name", member.name());
            option.put("role", member.role());
            option.put("hasPin", withPin.contains(member.membershipId()));
            options.add(option);
        }
        return options;
    }

    /** The memberships that can never check: the record's doctor, the prescriber, the last editor and the caller. */
    private Set<UUID> excluded(UUID tenantId, Ctx ctx, ChemoOrderRow order) {
        Set<UUID> excluded = new LinkedHashSet<>();
        excluded.add(ctx.member().membershipId());
        excluded.add(order.prescriberMembershipId);
        excluded.add(order.lastEditedByMembershipId);
        if (ctx.row().opdVisitId() != null) {
            visits.findById(tenantId, ctx.row().opdVisitId()).ifPresent(v -> excluded.add(v.doctorMembershipId()));
        }
        return excluded;
    }

    private boolean isCheckerRole(UUID tenantId, TeamMember member) {
        if (!member.active() || !CHECKER_ROLES.contains(member.role())) {
            return false;
        }
        return !"OWNER".equals(member.role()) || team.doctorDetails(tenantId, member.membershipId()).isPresent();
    }

    @Transactional
    public ObjectNode nameChecker(ClinicUser user, UUID recordId, CheckerInput input) {
        Ctx ctx = open(user, recordId, true);
        UUID tenantId = user.tenantId();
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw new InvalidFieldsException(List.of(new FieldMessage("revision", "Reload the page and try again.")));
        }
        ChemoOrderRow order = orders.findByRecordForUpdate(tenantId, recordId).orElseThrow(ChemoOrderService::notFound);
        if (!order.draft()) {
            throw EmrProblemException.of(409, "chemo-order-sent", "The order is already sent",
                    "This order is already sent or on hold and cannot be changed.");
        }
        if (input.revision() != order.revision) {
            throw changed();
        }
        Instant now = clock.instant();
        UUID checkerId = input.checkerMembershipId();
        TeamMember checker = null;
        if (checkerId != null) {
            Fresh current = logic.fresh(tenantId, ctx.row(), ctx.document(), logic.parseInputs(order.inputs),
                    order.checkerMembershipId, ctx.member().membershipId(), now);
            if (!current.computed().allChecksPass()) {
                throw EmrProblemException.of(409, "checks-failing", "Checks are failing",
                        "Clear the red checks before the second check.");
            }
            Set<UUID> excluded = excluded(tenantId, ctx, order);
            if (excluded.contains(checkerId)) {
                throw new InvalidFieldsException(List.of(new FieldMessage("checkerMembershipId",
                        "The second check must be done by someone other than the prescriber.")));
            }
            checker = team.find(tenantId, checkerId).filter(m -> isCheckerRole(tenantId, m)).orElseThrow(
                    () -> new InvalidFieldsException(List.of(new FieldMessage("checkerMembershipId",
                            "Choose a checker from the list."))));
        }
        boolean differs = !java.util.Objects.equals(checkerId, order.checkerMembershipId);
        long expected = order.revision;
        if (checker == null) {
            order.checkerMembershipId = null;
            order.checkerRole = null;
            order.checkerNamedAt = null;
            order.checkerNamedBy = null;
        } else {
            order.checkerMembershipId = checker.membershipId();
            order.checkerRole = checker.role();
            order.checkerNamedAt = now;
            order.checkerNamedBy = user.userId();
        }
        Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), logic.parseInputs(order.inputs),
                order.checkerMembershipId, ctx.member().membershipId(), now);
        order.fingerprint = fresh.fingerprint();
        order.snapshot = withVersion(logic.draftView(tenantId, order, fresh, recordId));
        boolean cleared = clearIfChanged(order, null, now);
        if (!orders.update(tenantId, order, expected, user.userId())) {
            throw changed();
        }
        if (cleared) {
            auditCleared(order, "CHECKER_CHANGED");
        }
        if (differs && checker != null) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("checkerMembershipId", checker.membershipId());
            audit.put("role", checker.role());
            auditLog.record("CHEMO_CHECKER_NAMED", ENTITY, order.id, null, audit);
        }
        return logic.sheet(tenantId, orders.findById(tenantId, order.id).orElseThrow(), fresh, recordId);
    }

    // ---- send and hold ----

    @Transactional
    public ObjectNode send(ClinicUser user, UUID recordId, ActionInput input) {
        Ctx ctx = open(user, recordId, true);
        UUID tenantId = user.tenantId();
        if (input == null || input.revision() == null || input.revision() < 0 || input.fingerprint() == null
                || !input.fingerprint().matches("^[0-9a-f]{64}$")) {
            throw new InvalidFieldsException(List.of(new FieldMessage("fingerprint",
                    "Reload the order and try again.")));
        }
        ChemoOrderRow order = orders.findByRecordForUpdate(tenantId, recordId).orElseThrow(ChemoOrderService::notFound);
        Instant now = clock.instant();
        Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), logic.parseInputs(order.inputs),
                order.checkerMembershipId, ctx.member().membershipId(), now);
        if ("SENT".equals(order.status)) {
            if (input.fingerprint().equals(order.fingerprint)) {
                return logic.sheet(tenantId, order, fresh, recordId);
            }
            throw changed();
        }
        if (!order.draft()) {
            throw EmrProblemException.of(409, "chemo-order-sent", "The order is already sent",
                    "This order is already sent or on hold and cannot be changed.");
        }
        if (input.revision() != order.revision) {
            throw changed();
        }
        if (!ChemoRules.isGive(fresh.decision()) || !fresh.computed().allChecksPass()) {
            throw EmrProblemException.of(409, "checks-failing", "Checks are failing",
                    "Clear the red checks before sending.");
        }
        if (!input.fingerprint().equals(fresh.fingerprint())) {
            throw changed();
        }
        if (order.confirmedAt == null || !fresh.fingerprint().equals(order.confirmedFingerprint)) {
            throw EmrProblemException.of(409, "second-check-not-confirmed", "Second check not confirmed",
                    "The second check is not confirmed yet.");
        }
        long expected = order.revision;
        order.fingerprint = fresh.fingerprint();
        order.status = "SENT";
        order.sentAt = now;
        order.sentBy = user.userId();
        order.snapshot = withVersion(logic.draftView(tenantId, order, fresh, recordId));
        if (!orders.update(tenantId, order, expected, user.userId())) {
            throw changed();
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("recordId", recordId);
        audit.put("protocolKey", order.protocolKey);
        audit.put("levelPct", order.levelPct);
        audit.put("checksPassed", ChemoRules.passedCount(fresh.computed().checks()));
        audit.put("checkerUserId", order.confirmedByUserId);
        audit.put("role", order.checkerRole);
        audit.put("way", order.confirmedVia);
        auditLog.record("CHEMO_ORDER_SENT", ENTITY, order.id, null, audit);
        return logic.sheet(tenantId, orders.findById(tenantId, order.id).orElseThrow(), fresh, recordId);
    }

    @Transactional
    public ObjectNode hold(ClinicUser user, UUID recordId, HoldInput input) {
        Ctx ctx = open(user, recordId, true);
        UUID tenantId = user.tenantId();
        if (input == null || input.revision() == null || input.revision() < 0) {
            throw new InvalidFieldsException(List.of(new FieldMessage("revision", "Reload the page and try again.")));
        }
        String reasonText = input.reasonText() == null ? null : input.reasonText().strip();
        if (reasonText != null && (reasonText.isEmpty() || reasonText.length() > 300
                || reasonText.matches(".*<[a-zA-Z/!].*"))) {
            throw new InvalidFieldsException(List.of(new FieldMessage("reasonText",
                    "Enter 1 to 300 characters without markup.")));
        }
        Optional<ChemoOrderRow> existing = orders.findByRecordForUpdate(tenantId, recordId);
        Instant now = clock.instant();
        UUID membershipId = ctx.member().membershipId();
        if (existing.isPresent()) {
            ChemoOrderRow order = existing.get();
            if ("HELD".equals(order.status)) {
                Fresh held = logic.fresh(tenantId, ctx.row(), ctx.document(), logic.parseInputs(order.inputs),
                        order.checkerMembershipId, membershipId, now);
                return logic.sheet(tenantId, order, held, recordId);
            }
            if ("SENT".equals(order.status)) {
                throw EmrProblemException.of(409, "chemo-order-sent", "The order is already sent",
                        "This order is already sent or on hold and cannot be changed.");
            }
            if (input.revision() != order.revision) {
                throw changed();
            }
        } else if (input.revision() != 0) {
            throw changed();
        }
        ChemoOrderRow order = existing.orElseGet(ChemoOrderRow::new);
        boolean created = existing.isEmpty();
        OrderInputs inputs;
        if (created) {
            inputs = defaultInputs(tenantId, ctx);
        } else {
            inputs = logic.parseInputs(order.inputs);
        }
        Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), inputs, order.checkerMembershipId, membershipId,
                now);
        String decision = fresh.decision();
        if (!"DELAY_1_WEEK".equals(decision) && !"STOP".equals(decision)) {
            throw EmrProblemException.of(409, "checks-failing", "Checks are failing",
                    "Record the hold needs a Delay or Stop decision.");
        }
        long expected = order.revision;
        if (created) {
            order.id = UUID.randomUUID();
            order.recordId = recordId;
            order.recordCreatedAt = ctx.row().createdAt();
            order.patientId = ctx.patientId();
            order.prescriberMembershipId = membershipId;
            order.cycleNo = fresh.info().sentCount() + 1;
            order.courseId = activeChemoCourse(tenantId, ctx.patientId());
        }
        String before = order.fingerprint;
        applyComputed(order, inputs, fresh);
        order.lastEditedByMembershipId = membershipId;
        boolean cleared = clearIfChanged(order, before, now);
        order.status = "HELD";
        order.holdKind = "STOP".equals(decision) ? "STOP" : "DELAY";
        order.holdReason = reasonText != null ? reasonText : logic.firstFailedReason(fresh);
        order.heldAt = now;
        order.snapshot = withVersion(logic.draftView(tenantId, order, fresh, recordId));
        if (created) {
            orders.insert(tenantId, withDraftStatus(order), user.userId());
            order.revision = 0;
            if (!orders.update(tenantId, order, 0, user.userId())) {
                throw changed();
            }
        } else if (!orders.update(tenantId, order, expected, user.userId())) {
            throw changed();
        }
        if (cleared) {
            auditCleared(order, "ORDER_CHANGED");
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("recordId", recordId);
        audit.put("kind", order.holdKind);
        auditLog.record("CHEMO_HOLD_RECORDED", ENTITY, order.id, null, audit);
        return logic.sheet(tenantId, orders.findById(tenantId, order.id).orElseThrow(), fresh, recordId);
    }

    /** The insert always makes a DRAFT (the database allows the status change by the update that follows). */
    private static ChemoOrderRow withDraftStatus(ChemoOrderRow row) {
        ChemoOrderRow copy = new ChemoOrderRow();
        copy.id = row.id;
        copy.recordId = row.recordId;
        copy.recordCreatedAt = row.recordCreatedAt;
        copy.patientId = row.patientId;
        copy.courseId = row.courseId;
        copy.cycleNo = row.cycleNo;
        copy.status = "DRAFT";
        copy.protocolKey = row.protocolKey;
        copy.levelPct = row.levelPct;
        copy.reductionReasonKey = row.reductionReasonKey;
        copy.reductionReasonText = row.reductionReasonText;
        copy.inputs = row.inputs;
        copy.snapshot = row.snapshot;
        copy.fingerprint = row.fingerprint;
        copy.doxorubicinMgM2 = row.doxorubicinMgM2;
        copy.priorOutsideMgM2 = row.priorOutsideMgM2;
        copy.prescriberMembershipId = row.prescriberMembershipId;
        copy.lastEditedByMembershipId = row.lastEditedByMembershipId;
        return copy;
    }

    // ---- the hook: a record save changes what the order shows ----

    @Override
    public void onRecordSaved(UUID tenantId, UUID actorUserId, UUID membershipId, RecordRow row,
                              EmrRecordDocument document) {
        Optional<ChemoOrderRow> existing = orders.findByRecordForUpdate(tenantId, row.id());
        if (existing.isEmpty() || !existing.get().draft()) {
            return;
        }
        ChemoOrderRow order = existing.get();
        Instant now = clock.instant();
        Fresh fresh = logic.fresh(tenantId, row, document, logic.parseInputs(order.inputs), order.checkerMembershipId,
                membershipId, now);
        if (fresh.fingerprint().equals(order.fingerprint)) {
            return;
        }
        long expected = order.revision;
        order.fingerprint = fresh.fingerprint();
        order.doxorubicinMgM2 = BigDecimal.valueOf(fresh.computed().thisCycleMgM2());
        order.snapshot = withVersion(logic.draftView(tenantId, order, fresh, row.id()));
        order.lastEditedByMembershipId = membershipId;
        if (membershipId.equals(order.checkerMembershipId)) {
            // The checker changed the order: the independence rule ends the naming (the database refuses the rest).
            order.checkerMembershipId = null;
            order.checkerRole = null;
            order.checkerNamedAt = null;
            order.checkerNamedBy = null;
        }
        boolean cleared = clearIfChanged(order, null, now);
        if (!orders.update(tenantId, order, expected, actorUserId)) {
            throw changed();
        }
        if (cleared) {
            auditCleared(order, "ORDER_CHANGED");
        }
    }

    // ---- shared with the checker services ----

    Optional<ChemoOrderRow> byRecord(UUID tenantId, UUID recordId) {
        return orders.findByRecord(tenantId, recordId);
    }

}
