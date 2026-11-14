package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.VisitRepository;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.ConfirmInput;
import com.sahaj.opd.application.emr.specialty.ChemoInputs.PinInput;
import com.sahaj.opd.application.emr.specialty.ChemoOrderLogic.Fresh;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The independent second check of a chemotherapy order, seen from the checker (SAH-66-3, ADR-023 D6): the list of orders
 * that name the caller, the read-only summary, and the confirmation. Way (a) is the checker's own sign-in; way (b) is
 * confirmed at the prescriber's device with the checker's colleague PIN, in two short transactions around the PIN check
 * (see {@link ChemoSecondCheckService}).
 *
 * <p>Who: NURSE, PHARMACIST, DOCTOR, VISITING_DOCTOR and OWNER with doctor details, and only for orders that name the
 * caller's own membership (403 otherwise; another clinic's order does not exist for the caller, 404). A checker gets no
 * EMR record access from this. The patient's name, UHID, age and sex are on the summary for the screen only and are never
 * logged. Every confirmation is checked on a fresh recomputation: every safety check passes and the fingerprint the
 * checker read equals the current one. The PIN is never stored, logged, audited or echoed.
 */
@Service
public class ChemoCheckService {

    private static final int PAGE_SIZE = 20;

    private final TeamMemberPort team;
    private final ChemoOrderRepository orders;
    private final ChemoOrderLogic logic;
    private final EmrRecordRepository recordRepository;
    private final VisitRepository visits;
    private final PatientClinicalPort patients;
    private final ChemoOrderService orderService;
    private final AuditLog auditLog;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ChemoCheckService(TeamMemberPort team, ChemoOrderRepository orders, ChemoOrderLogic logic,
                             EmrRecordRepository recordRepository, VisitRepository visits,
                             PatientClinicalPort patients, ChemoOrderService orderService, AuditLog auditLog,
                             ObjectMapper mapper, Clock clock) {
        this.team = team;
        this.orders = orders;
        this.logic = logic;
        this.recordRepository = recordRepository;
        this.visits = visits;
        this.patients = patients;
        this.orderService = orderService;
        this.auditLog = auditLog;
        this.mapper = mapper;
        this.clock = clock;
    }

    TeamMember requireChecker(ClinicUser user) {
        TeamMember member = team.findActiveByUserId(user.tenantId(), user.userId())
                .orElseThrow(() -> new AccessDeniedException("Not an active member of this clinic."));
        if (!ChemoOrderService.CHECKER_ROLES.contains(member.role())) {
            throw new AccessDeniedException("Only a nurse, a pharmacist or a doctor checks a chemotherapy order.");
        }
        if ("OWNER".equals(member.role()) && team.doctorDetails(user.tenantId(), member.membershipId()).isEmpty()) {
            throw new AccessDeniedException("Only a nurse, a pharmacist or a doctor checks a chemotherapy order.");
        }
        return member;
    }

    // ---- the list ----

    @Transactional(readOnly = true)
    public ObjectNode list(ClinicUser user, Integer pageParam) {
        TeamMember member = requireChecker(user);
        UUID tenantId = user.tenantId();
        int page = pageParam == null ? 0 : pageParam;
        if (page < 0 || page > 1000) {
            throw new InvalidFieldsException(List.of(new FieldMessage("page", "Choose a page from 0 to 1000.")));
        }
        List<ChemoOrderRow> rows = orders.waitingFor(tenantId, member.membershipId(), page * PAGE_SIZE, PAGE_SIZE);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode items = result.putArray("items");
        SpecialtyReferenceData data = logic.reference();
        for (ChemoOrderRow row : rows) {
            ObjectNode item = items.addObject();
            PatientClinical patient = patients.find(tenantId, row.patientId).orElse(null);
            item.put("orderId", row.id.toString());
            item.put("patientName", patient == null ? "Patient" : patient.name());
            putOrNull(item, "uhid", patient == null ? null : patient.uhid());
            if (patient == null) {
                item.putNull("age");
            } else {
                item.put("age", patient.age());
            }
            item.put("sex", patient == null ? "O" : patient.sex());
            item.put("prescriberName", team.find(tenantId, row.prescriberMembershipId).map(TeamMember::name)
                    .orElse("Doctor"));
            var protocol = data.protocol(row.protocolKey);
            item.put("protocolLabel", protocol == null ? row.protocolKey : protocol.label());
            item.put("namedAt", row.checkerNamedAt.toString());
        }
        result.put("total", orders.countWaitingFor(tenantId, member.membershipId()));
        result.put("page", page);
        result.put("pageSize", PAGE_SIZE);
        return result;
    }

    // ---- the summary ----

    @Transactional(readOnly = true)
    public ObjectNode summary(ClinicUser user, UUID orderId) {
        TeamMember member = requireChecker(user);
        UUID tenantId = user.tenantId();
        ChemoOrderRow order = orders.findById(tenantId, orderId).orElseThrow(ChemoCheckService::notFound);
        requireNamed(tenantId, member, order);
        return build(tenantId, member, order, clock.instant());
    }

    private ObjectNode build(UUID tenantId, TeamMember member, ChemoOrderRow order, Instant now) {
        RecordRow row = recordRepository.findById(tenantId, order.recordId).orElseThrow(EmrProblemException::recordNotFound);
        Fresh fresh = fresh(tenantId, member, order, row, now);
        PatientClinical patient = patients.find(tenantId, order.patientId).orElse(null);
        String prescriber = team.find(tenantId, order.prescriberMembershipId).map(TeamMember::name).orElse("Doctor");
        return logic.summary(order, fresh, patient == null ? "Patient" : patient.name(),
                patient == null ? null : patient.uhid(), patient == null ? null : patient.age(),
                patient == null ? "O" : patient.sex(), prescriber);
    }

    private Fresh fresh(UUID tenantId, TeamMember member, ChemoOrderRow order, RecordRow row, Instant now) {
        return logic.fresh(tenantId, row, EmrRecordDocument.parse(mapper, row.sections()),
                logic.parseInputs(order.inputs), order.checkerMembershipId, member.membershipId(), now);
    }

    /** 403 checker-is-prescriber for the prescriber; 403 not-named-checker for anyone but the named checker. */
    private void requireNamed(UUID tenantId, TeamMember member, ChemoOrderRow order) {
        Set<UUID> prescribers = new LinkedHashSet<>();
        prescribers.add(order.prescriberMembershipId);
        prescribers.add(order.lastEditedByMembershipId);
        RecordRow record = recordRepository.findById(tenantId, order.recordId).orElse(null);
        if (record != null && record.opdVisitId() != null) {
            visits.findById(tenantId, record.opdVisitId()).ifPresent(v -> prescribers.add(v.doctorMembershipId()));
        }
        if (prescribers.contains(member.membershipId())) {
            throw EmrProblemException.of(403, "checker-is-prescriber", "The prescriber cannot check",
                    "The second check must be done by someone other than the prescriber.");
        }
        if (!member.membershipId().equals(order.checkerMembershipId)) {
            throw EmrProblemException.of(403, "not-named-checker", "You are not the named checker",
                    "This order names another person for the second check.");
        }
    }

    // ---- confirm, way (a): the checker's own sign-in ----

    @Transactional
    public ObjectNode confirm(ClinicUser user, UUID orderId, ConfirmInput input) {
        TeamMember member = requireChecker(user);
        UUID tenantId = user.tenantId();
        if (input == null || input.fingerprint() == null || !input.fingerprint().matches("^[0-9a-f]{64}$")) {
            throw new InvalidFieldsException(List.of(new FieldMessage("fingerprint",
                    "Reload the summary and try again.")));
        }
        ChemoOrderRow order = orders.findByIdForUpdate(tenantId, orderId).orElseThrow(ChemoCheckService::notFound);
        requireNamed(tenantId, member, order);
        if (!order.draft()) {
            throw EmrProblemException.of(409, "chemo-order-sent", "The order is already sent",
                    "This order is already sent or on hold and cannot be changed.");
        }
        Instant now = clock.instant();
        RecordRow row = recordRepository.findById(tenantId, order.recordId).orElseThrow(EmrProblemException::recordNotFound);
        Fresh fresh = fresh(tenantId, member, order, row, now);
        if (!fresh.computed().allChecksPass()) {
            throw EmrProblemException.of(409, "checks-failing", "Checks are failing",
                    "Clear the red checks before the second check.");
        }
        if (!input.fingerprint().equals(fresh.fingerprint())) {
            throw EmrProblemException.of(409, "chemo-order-changed", "The order changed",
                    "This order was changed. Reload it and check again.");
        }
        if (order.confirmedAt != null && fresh.fingerprint().equals(order.confirmedFingerprint)) {
            return build(tenantId, member, order, now);
        }
        record(tenantId, order, fresh, member.membershipId(), user.userId(), "SIGN_IN", now);
        return build(tenantId, member, orders.findById(tenantId, orderId).orElseThrow(), now);
    }

    /** Writes the confirmation (the caller holds the order row lock) and the audit entry. */
    void record(UUID tenantId, ChemoOrderRow order, Fresh fresh, UUID checkerMembershipId, UUID checkerUserId,
                String via, Instant now) {
        long expected = order.revision;
        order.fingerprint = fresh.fingerprint();
        order.confirmedByMembershipId = checkerMembershipId;
        order.confirmedByUserId = checkerUserId;
        order.confirmedVia = via;
        order.confirmedAt = now;
        order.confirmedFingerprint = fresh.fingerprint();
        order.confirmationClearedAt = null;
        if (!orders.update(tenantId, order, expected, checkerUserId)) {
            throw EmrProblemException.of(409, "chemo-order-changed", "The order changed",
                    "This order was changed. Reload it and check again.");
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("recordId", order.recordId);
        audit.put("checkerUserId", checkerUserId);
        audit.put("role", order.checkerRole);
        audit.put("way", via);
        auditLog.record("CHEMO_SECOND_CHECK_CONFIRMED", ChemoOrderService.ENTITY, order.id, null, audit);
    }

    // ---- confirm, way (b): two steps around the colleague PIN check ----

    /** What the PIN check needs: who to check and which role, or the answer when the order is already confirmed. */
    record Prepared(UUID checkerMembershipId, String checkerRole, ObjectNode alreadyConfirmed) {
    }

    @Transactional(readOnly = true)
    public Prepared preparePin(ClinicUser user, UUID recordId, PinInput input) {
        ChemoOrderService.Ctx ctx = orderService.open(user, recordId, true);
        UUID tenantId = user.tenantId();
        requireInput(input);
        ChemoOrderRow order = orders.findByRecord(tenantId, recordId).orElseThrow(ChemoCheckService::notFound);
        Fresh fresh = requireCurrent(tenantId, ctx, order, input, clock.instant());
        if (order.confirmedAt != null && fresh.fingerprint().equals(order.confirmedFingerprint)
                && input.checkerMembershipId().equals(order.confirmedByMembershipId)) {
            return new Prepared(order.checkerMembershipId, order.checkerRole,
                    logic.sheet(tenantId, order, fresh, recordId));
        }
        return new Prepared(order.checkerMembershipId, order.checkerRole, null);
    }

    @Transactional
    public ObjectNode confirmPin(ClinicUser user, UUID recordId, PinInput input, UUID checkerUserId) {
        ChemoOrderService.Ctx ctx = orderService.open(user, recordId, true);
        UUID tenantId = user.tenantId();
        ChemoOrderRow order = orders.findByRecordForUpdate(tenantId, recordId).orElseThrow(ChemoCheckService::notFound);
        Instant now = clock.instant();
        Fresh fresh = requireCurrent(tenantId, ctx, order, input, now);
        if (order.confirmedAt == null || !fresh.fingerprint().equals(order.confirmedFingerprint)) {
            record(tenantId, order, fresh, input.checkerMembershipId(), checkerUserId, "PIN", now);
        }
        return logic.sheet(tenantId, orders.findById(tenantId, order.id).orElseThrow(), fresh, recordId);
    }

    private static void requireInput(PinInput input) {
        if (input == null || input.orderRevision() == null || input.orderRevision() < 0 || input.fingerprint() == null
                || !input.fingerprint().matches("^[0-9a-f]{64}$") || input.checkerMembershipId() == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("fingerprint",
                    "Reload the order and try again.")));
        }
    }

    /**
     * The order must be a draft, name the checker the request names, pass every check, and be at the revision and the
     * fingerprint the screen holds (409 otherwise).
     */
    private Fresh requireCurrent(UUID tenantId, ChemoOrderService.Ctx ctx, ChemoOrderRow order, PinInput input,
                                 Instant now) {
        requireInput(input);
        if (!order.draft()) {
            throw EmrProblemException.of(409, "chemo-order-sent", "The order is already sent",
                    "This order is already sent or on hold and cannot be changed.");
        }
        if (!input.checkerMembershipId().equals(order.checkerMembershipId)) {
            throw EmrProblemException.of(409, "chemo-order-changed", "The order changed",
                    "This order was changed. Reload it and check again.");
        }
        Fresh fresh = logic.fresh(tenantId, ctx.row(), ctx.document(), logic.parseInputs(order.inputs),
                order.checkerMembershipId, ctx.member().membershipId(), now);
        if (!fresh.computed().allChecksPass()) {
            throw EmrProblemException.of(409, "checks-failing", "Checks are failing",
                    "Clear the red checks before the second check.");
        }
        if (input.orderRevision() != order.revision && !isAlreadyConfirmedCopy(order, fresh, input)
                || !input.fingerprint().equals(fresh.fingerprint())) {
            throw EmrProblemException.of(409, "chemo-order-changed", "The order changed",
                    "This order was changed. Reload it and check again.");
        }
        return fresh;
    }

    /** A retry after the confirmation was recorded: the revision moved by the confirmation itself. */
    private static boolean isAlreadyConfirmedCopy(ChemoOrderRow order, Fresh fresh, PinInput input) {
        return order.confirmedAt != null && fresh.fingerprint().equals(order.confirmedFingerprint)
                && input.fingerprint().equals(order.confirmedFingerprint);
    }

    private static EmrProblemException notFound() {
        return EmrProblemException.of(404, "order-not-found", "Order not found", "There is no such chemotherapy order.");
    }

    private static void putOrNull(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

}
