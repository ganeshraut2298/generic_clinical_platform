package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionService.EmrHeader;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineOrderRepository.PendingRow;
import com.sahaj.ipd.application.MedicineOrderRepository.Position;
import com.sahaj.ipd.application.MedicineViews.DrugOption;
import com.sahaj.ipd.application.MedicineViews.EmrPatient;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import com.sahaj.ipd.application.MedicineViews.OrderCheckView;
import com.sahaj.ipd.application.MedicineViews.OrderChangeLine;
import com.sahaj.ipd.application.MedicineViews.OrderExecution;
import com.sahaj.ipd.application.MedicineViews.OrderFinding;
import com.sahaj.ipd.application.MedicineViews.OrderHold;
import com.sahaj.ipd.application.MedicineViews.OrderQuery;
import com.sahaj.ipd.application.MedicineViews.PharmacistCheckRow;
import com.sahaj.ipd.application.MedicineViews.PharmacistChecks;
import com.sahaj.ipd.application.OrderNotices.AckState;
import com.sahaj.ipd.application.OrderQueryRepository.QueryRow;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.DoseUnit;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DrugCatalogue;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineSafetyCheck;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.ipd.domain.MedicineSafetyCheck.Finding;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import com.sahaj.ipd.domain.OverrideReason;
import com.sahaj.ipd.domain.RejectReason;
import com.sahaj.ipd.domain.RouteKey;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-31 builds the answers of the medicine endpoints from rows already read: the EMR sheet, the check view, and a
 * page of the pharmacist list (ADR-009 D2, D8, D11); SAH-76 adds the order version, the change lines, the hold, the
 * open query and the execution line to every row (ADR-027 D1, D4, D6). It runs the one {@link MedicineSafetyCheck} for
 * the list rows, so the order form, the list and the verify agree. Staff names are looked up in one batch per answer;
 * the patient data comes from {@link PatientClinicalPort}; the versions, holds and stops come from one timeline read
 * ({@link OrderTimelineRepository}), never one read per order. Everything it builds is shown on screen only and never
 * logged.
 */
@Component
class MedicineAssembler {

    static final String PATIENT_FALLBACK = "Patient";

    private final MedicineCatalogue catalogue;
    private final MedicineOrderRepository orders;
    private final DoseRepository doses;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final AdmissionService admissionService;
    private final OrderTimelineRepository timelines;
    private final OrderQueryRepository queries;
    private final OrderNotices notices;
    private final NursingSettingsReader settings;
    private final PharmacistReviewRepository reviews;

    MedicineAssembler(MedicineCatalogue catalogue, MedicineOrderRepository orders, DoseRepository doses,
                      PatientClinicalPort patients, PatientAccessPort patientAccess, ActorDirectoryPort actors,
                      AdmissionService admissionService, OrderTimelineRepository timelines,
                      OrderQueryRepository queries, OrderNotices notices, NursingSettingsReader settings,
                      PharmacistReviewRepository reviews) {
        this.catalogue = catalogue;
        this.orders = orders;
        this.doses = doses;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.admissionService = admissionService;
        this.timelines = timelines;
        this.queries = queries;
        this.notices = notices;
        this.settings = settings;
        this.reviews = reviews;
    }

    // ---- the EMR sheet ----

    /**
     * The EMR sheet. {@code canOrder} needs the role in the token and the role of the membership both OWNER or DOCTOR,
     * the admission ADMITTED and IPD Trial or On; {@code canStop} the same roles and a live order of an ADMITTED
     * admission (Read-only allowed). The change, hold, resume and reply flags follow ADR-027 D1 and D12. The question
     * and the reply of a pharmacist query are shown to OWNER and DOCTOR only (a NURSE reads the sheet and gets null).
     */
    EmrSheet sheet(UUID tenantId, String role, String memberRole, ModuleEntitlement ent, AdmissionRow admission,
                   PatientClinical patient, List<OrderRow> rows, Instant asOf) {
        boolean admitted = "ADMITTED".equals(admission.status());
        String state = ent.ipdState();
        boolean writableState = "trial".equals(state) || "on".equals(state);
        boolean roleCanOrder = MedicineRules.ORDER_ROLES.contains(role)
                && MedicineRules.ORDER_ROLES.contains(memberRole);
        boolean canOrder = roleCanOrder && admitted && writableState;
        String lockReason = null;
        if (!canOrder) {
            lockReason = !roleCanOrder ? "ROLE" : !writableState ? "IPD_READONLY" : "NOT_ADMITTED";
        }
        DrugCatalogue drugs = catalogue.catalogue();
        Map<UUID, OrderTimeline> timeline = rows.isEmpty() ? Map.of()
                : timelines.findTimelines(tenantId, List.of(admission.id()));
        Map<UUID, QueryRow> latestQueries = rows.isEmpty() ? Map.of()
                : queries.findLatest(tenantId, ids(rows));
        Map<UUID, AckState> acks = rows.isEmpty() ? Map.of() : notices.ackStates(tenantId, admission.id());
        Set<UUID> staff = new HashSet<>();
        for (OrderRow o : rows) {
            collectStaff(o, staff);
            OrderTimeline t = timeline.get(o.id());
            if (t != null) {
                for (OrderHoldInterval h : t.holds()) {
                    staff.add(h.heldBy());
                }
            }
        }
        for (QueryRow q : latestQueries.values()) {
            staff.add(q.askedBy());
            if (q.repliedBy() != null) {
                staff.add(q.repliedBy());
            }
        }
        for (AckState a : acks.values()) {
            if (a.acknowledgedBy() != null) {
                staff.add(a.acknowledgedBy());
            }
        }
        // SAH-32 (ADR-011 D11): the latest dose of each order, one query for the sheet.
        Map<UUID, DoseRow> lastDoses = new HashMap<>();
        List<DoseRow> today = List.of();
        if (!rows.isEmpty()) {
            for (DoseRow d : doses.findLastPerOrder(tenantId, admission.id())) {
                lastDoses.put(d.orderId(), d);
                DoseViews.collectStaff(d, staff);
            }
            today = doses.findBetween(tenantId, admission.id(), ExecutionSlots.dayStart(asOf),
                    ExecutionSlots.dayEnd(asOf));
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);
        boolean clinicalText = MedicineRules.ORDER_ROLES.contains(role) || "NURSE".equals(role);
        boolean seesQuery = MedicineRules.ORDER_ROLES.contains(role);
        EffectiveNursingSettings nursing = settings.effectiveFor(tenantId, admission.id());
        List<MedicineOrderRow> views = new ArrayList<>();
        for (OrderRow o : rows) {
            Flags flags = new Flags(roleCanOrder, admitted, writableState, clinicalText, seesQuery);
            views.add(row(o, timeline.get(o.id()), names, flags, lastDoses.get(o.id()), latestQueries.get(o.id()),
                    acks.get(o.id()), today, nursing, asOf));
        }
        EmrHeader header = admissionService.emrHeader(tenantId, admission);
        List<DrugOption> options = new ArrayList<>();
        for (Drug d : drugs.drugs()) {
            List<String> units = new ArrayList<>();
            for (DoseUnit u : d.doseUnits()) {
                units.add(u.key());
            }
            options.add(new DrugOption(d.key(), d.name(), d.route(), d.highAlert(), d.routeKey().key(),
                    List.copyOf(units), d.timeCritical()));
        }
        List<KeyLabel> frequencies = new ArrayList<>();
        for (Frequency f : Frequency.values()) {
            frequencies.add(new KeyLabel(f.key(), f.label()));
        }
        List<KeyLabel> reasons = new ArrayList<>();
        for (OverrideReason r : OverrideReason.values()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return new EmrSheet(admission.id(), admission.status(), state, asOf, patient(patient, admission.bedCode()),
                header.doctorName(), admission.reason(), header.dayNumber(), List.copyOf(views), canOrder,
                lockReason, List.copyOf(options), List.copyOf(frequencies), List.copyOf(reasons), routes(), doseUnits(),
                priorities(), nursing.statWindowMin());
    }

    /** What the caller may do with a row: roles, the admission state and the IPD state. */
    private record Flags(boolean roleCanOrder, boolean admitted, boolean writableState, boolean clinicalText,
                         boolean seesQuery) {
    }

    private MedicineOrderRow row(OrderRow o, OrderTimeline timeline, ActorNames names, Flags flags, DoseRow lastDose,
                                 QueryRow latestQuery, AckState ack, List<DoseRow> today,
                                 EffectiveNursingSettings nursing, Instant asOf) {
        if (timeline == null) {
            throw new IllegalStateException("A medicine order has no version row");
        }
        Drug drug = drug(o.drugKey());
        MedicineOrderVersion current = timeline.current();
        KeyLabel override = overrideLabel(o);
        KeyLabel reject = o.rejectReasonKey() == null ? null : RejectReason.ofKey(o.rejectReasonKey())
                .map(r -> new KeyLabel(r.key(), r.label()))
                .orElseThrow(() -> new IllegalStateException("An order has an unknown reject reason"));
        List<OrderChangeLine> changes = current.versionNo() > 1
                ? timeline.version(current.versionNo() - 1).map(prev -> OrderViews.changes(prev, current))
                        .orElse(List.of())
                : List.of();
        OrderHold hold = null;
        for (OrderHoldInterval h : timeline.holds()) {
            if (h.isOpen() && MedicineOrderRepository.ON_HOLD.equals(o.status())) {
                hold = new OrderHold(h.heldAt(), names.of(h.heldBy()), flags.clinicalText() ? h.holdReason() : null);
            }
        }
        OrderQuery query = flags.seesQuery() ? queryView(o, latestQuery, names) : null;
        boolean live = o.live();
        boolean base = flags.roleCanOrder() && flags.admitted();
        boolean change = base && flags.writableState() && (MedicineOrderRepository.PENDING.equals(o.status())
                || MedicineOrderRepository.QUERIED.equals(o.status())
                || MedicineOrderRepository.ACTIVE.equals(o.status()));
        boolean canHold = base && MedicineOrderRepository.ACTIVE.equals(o.status());
        boolean resume = base && flags.writableState() && MedicineOrderRepository.ON_HOLD.equals(o.status());
        boolean reply = base && flags.writableState() && MedicineOrderRepository.QUERIED.equals(o.status());
        return new MedicineOrderRow(o.id(), drug.key(), drug.name(), OrderViews.routeLabel(current.routeKey()),
                drug.highAlert(), OrderViews.frequencyKey(current), OrderViews.frequencyLabel(current), o.status(),
                names.of(o.orderedBy()), o.orderedAt(), override,
                o.verifiedBy() == null ? null : names.of(o.verifiedBy()), o.verifiedAt(), reject,
                o.rejectedBy() == null ? null : names.of(o.rejectedBy()), o.rejectedAt(),
                o.stoppedBy() == null ? null : names.of(o.stoppedBy()), o.stoppedAt(), base && live,
                lastDose == null ? null : DoseViews.of(lastDose, names), current.versionNo(),
                OrderViews.details(current), changes, current.changeReason(), hold,
                flags.clinicalText() ? o.stopReason() : null, query, change, canHold, resume, reply,
                execution(o, timeline, ack, names, today, flags.admitted(), nursing, asOf));
    }

    /** The open query, or the last answered one while the order is PENDING again at the same version. */
    private static OrderQuery queryView(OrderRow o, QueryRow q, ActorNames names) {
        if (q == null) {
            return null;
        }
        boolean answered = OrderQueryRepository.CLOSED_BY_REPLY.equals(q.closedBy())
                && MedicineOrderRepository.PENDING.equals(o.status()) && q.orderVersion() == o.currentVersion();
        if (!q.open() && !answered) {
            return null;
        }
        return new OrderQuery(q.question(), names.of(q.askedBy()), q.askedAt(), q.reply(),
                q.repliedBy() == null ? null : names.of(q.repliedBy()), q.repliedAt());
    }

    /** SAH-76-5: what the nurses did with the order. The slot numbers come from the one slot rule (ExecutionSlots). */
    private OrderExecution execution(OrderRow o, OrderTimeline timeline, AckState ack, ActorNames names,
                                     List<DoseRow> today, boolean admitted, EffectiveNursingSettings nursing,
                                     Instant asOf) {
        int notGiven = 0;
        List<DoseRow> own = new ArrayList<>();
        for (DoseRow d : today) {
            if (d.orderId().equals(o.id())) {
                own.add(d);
                if (!DoseRepository.GIVEN.equals(d.outcome())) {
                    notGiven++;
                }
            }
        }
        ExecutionSlots.Result slots = ExecutionSlots.of(o, timeline, own, admitted, nursing, asOf);
        boolean acknowledged = ack == null || !ack.open();
        return new OrderExecution(notGiven, slots.nextDueAt(), slots.lateCount(), acknowledged,
                ack == null || ack.acknowledgedBy() == null ? null : names.of(ack.acknowledgedBy()),
                ack == null ? null : ack.acknowledgedAt());
    }

    private static List<UUID> ids(List<OrderRow> rows) {
        List<UUID> ids = new ArrayList<>();
        for (OrderRow o : rows) {
            ids.add(o.id());
        }
        return ids;
    }

    static List<KeyLabel> routes() {
        List<KeyLabel> list = new ArrayList<>();
        for (RouteKey r : RouteKey.values()) {
            list.add(new KeyLabel(r.key(), r.label()));
        }
        return List.copyOf(list);
    }

    static List<KeyLabel> doseUnits() {
        List<KeyLabel> list = new ArrayList<>();
        for (DoseUnit u : DoseUnit.values()) {
            list.add(new KeyLabel(u.key(), u.label()));
        }
        return List.copyOf(list);
    }

    static List<KeyLabel> priorities() {
        List<KeyLabel> list = new ArrayList<>();
        for (OrderPriority p : OrderPriority.values()) {
            list.add(new KeyLabel(p.name(), OrderViews.priorityLabel(p)));
        }
        return List.copyOf(list);
    }

    /** The reason given when the doctor ordered past warnings, or null when there was none. */
    private static KeyLabel overrideLabel(OrderRow o) {
        return o.overrideReasonKey() == null ? null : OverrideReason.ofKey(o.overrideReasonKey())
                .map(r -> new KeyLabel(r.key(), r.label()))
                .orElseThrow(() -> new IllegalStateException("An order has an unknown override reason"));
    }

    private static void collectStaff(OrderRow o, Set<UUID> staff) {
        staff.add(o.orderedBy());
        for (UUID id : new UUID[] {o.verifiedBy(), o.rejectedBy(), o.stoppedBy()}) {
            if (id != null) {
                staff.add(id);
            }
        }
    }

    // ---- the identity bar and the check ----

    /** The identity bar. The chip is computed from the allergy text by the one rule class. */
    EmrPatient patient(PatientClinical patient, String bedCode) {
        AllergyStatus status = MedicineSafetyCheck.allergyStatus(patient.allergyText());
        return new EmrPatient(patient.id(), patient.name(), patient.uhid(), patient.age(), patient.sex(), bedCode,
                patient.allergyText(), status.name());
    }

    static OrderCheckView view(OrderCheck check) {
        List<OrderFinding> hardStops = new ArrayList<>();
        for (Finding f : check.hardStops()) {
            hardStops.add(finding(f));
        }
        List<OrderFinding> warnings = new ArrayList<>();
        for (Finding f : check.warnings()) {
            warnings.add(finding(f));
        }
        return new OrderCheckView(check.allergyStatus().name(), List.copyOf(hardStops), List.copyOf(warnings),
                check.checkToken());
    }

    private static OrderFinding finding(Finding f) {
        return new OrderFinding(f.code().name(), f.severity().name(), f.otherDrugKey(), f.text());
    }

    Drug drug(String key) {
        return catalogue.catalogue().find(key)
                .orElseThrow(() -> new IllegalStateException("An order names a medicine not in the catalogue"));
    }

    MedicineSafetyCheck safety() {
        return catalogue.safety();
    }

    /** The drug keys of the live orders in chart order, without {@code excludeId} (null excludes nothing). */
    static List<String> liveKeys(List<OrderRow> rows, UUID excludeId) {
        List<String> keys = new ArrayList<>();
        for (OrderRow o : rows) {
            if (o.live() && !o.id().equals(excludeId)) {
                keys.add(o.drugKey());
            }
        }
        return keys;
    }

    // ---- the pharmacist list ----

    /**
     * One page of the panel "Pharmacist check": the PENDING and QUERIED orders of ADMITTED admissions, oldest first, each
     * with the safety check run now (the order itself excluded). The caller has already checked the roles (both
     * PHARMACIST or OWNER). {@code waiting} (PENDING) and {@code queried} are full counts (count queries, not the
     * rows), and so is {@code reviewsOpen}. Because the page shows patient names, UHIDs and allergy texts, it writes
     * one record-access row per distinct patient on the page, in the caller's transaction, which therefore must not be
     * read-only (security review S3). The query count does not depend on the number of rows: the page, the counts, the
     * live orders, the timelines, the latest queries, the patients and the staff names.
     */
    PharmacistChecks queue(UUID tenantId, UUID actor, ModuleEntitlement ent, int limit, Position after) {
        Instant asOf = orders.databaseNow();
        List<PendingRow> found = orders.findPending(tenantId, after, limit + 1);
        boolean more = found.size() > limit;
        List<PendingRow> page = more ? found.subList(0, limit) : found;
        int waiting = orders.countWaiting(tenantId);
        int queried = orders.countQueried(tenantId);
        int reviewsOpen = reviews.countOpen(tenantId);

        Set<UUID> patientIds = new LinkedHashSet<>();
        Set<UUID> admissionIds = new LinkedHashSet<>();
        List<UUID> orderIds = new ArrayList<>();
        Set<UUID> staff = new HashSet<>();
        staff.add(actor);
        for (PendingRow row : page) {
            patientIds.add(row.patientId());
            admissionIds.add(row.order().admissionId());
            orderIds.add(row.order().id());
            staff.add(row.order().orderedBy());
        }
        Map<UUID, PatientClinical> patientMap = patientIds.isEmpty() ? Map.of()
                : patients.findAll(tenantId, patientIds);
        // Security review S3: the list shows names, UHIDs and allergies, so it is a record access, once per patient.
        for (UUID patientId : patientIds) {
            if (patientMap.containsKey(patientId)) {
                patientAccess.recordOpened(tenantId, patientId, actor);
            }
        }
        Map<UUID, List<OrderRow>> liveByAdmission = new LinkedHashMap<>();
        for (OrderRow live : orders.findLiveByAdmissions(tenantId, admissionIds)) {
            liveByAdmission.computeIfAbsent(live.admissionId(), k -> new ArrayList<>()).add(live);
        }
        Map<UUID, OrderTimeline> timeline = admissionIds.isEmpty() ? Map.of()
                : timelines.findTimelines(tenantId, admissionIds);
        Map<UUID, QueryRow> latestQueries = queries.findLatest(tenantId, orderIds);
        for (QueryRow q : latestQueries.values()) {
            staff.add(q.askedBy());
            if (q.repliedBy() != null) {
                staff.add(q.repliedBy());
            }
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);
        String state = ent.ipdState();
        boolean writable = "trial".equals(state) || "on".equals(state);

        List<PharmacistCheckRow> items = new ArrayList<>();
        for (PendingRow row : page) {
            OrderRow o = row.order();
            OrderTimeline t = timeline.get(o.id());
            if (t == null) {
                throw new IllegalStateException("A medicine order has no version row");
            }
            PatientClinical patient = patientMap.get(row.patientId());
            String allergyText = patient == null ? null : patient.allergyText();
            Drug drug = drug(o.drugKey());
            OrderCheck check = safety().check(drug, allergyText,
                    liveKeys(liveByAdmission.getOrDefault(o.admissionId(), List.of()), o.id()));
            boolean waitingRow = MedicineOrderRepository.PENDING.equals(o.status());
            MedicineOrderVersion current = t.current();
            boolean self = o.orderedBy().equals(actor);
            // ADR-027 A9: the author of the current version (not the orderer) is locked out the same way.
            boolean selfChanged = !self && actor.equals(current.createdBy());
            String lock = !waitingRow ? null : check.blocked() ? "HARD_STOP" : self ? "SELF"
                    : selfChanged ? "SELF_CHANGED" : !writable ? "IPD_READONLY" : null;
            List<OrderChangeLine> changes = current.versionNo() > 1
                    ? t.version(current.versionNo() - 1).map(prev -> OrderViews.changes(prev, current))
                            .orElse(List.of())
                    : List.of();
            KeyLabel override = overrideLabel(o);
            items.add(new PharmacistCheckRow(o.id(), o.admissionId(), drug.key(), drug.name(), drug.highAlert(),
                    OrderViews.frequencyKey(current), OrderViews.frequencyLabel(current),
                    patient == null ? unknownPatient(row) : patient(patient, row.bedCode()),
                    names.of(o.orderedBy()), o.orderedAt(), override, view(check), waitingRow && lock == null, lock,
                    true, o.status(), current.versionNo(), OrderViews.details(current), changes,
                    current.changeReason(), queryView(o, latestQueries.get(o.id()), names),
                    waitingRow && !self && !selfChanged));
        }
        List<KeyLabel> rejectReasons = new ArrayList<>();
        for (RejectReason r : RejectReason.values()) {
            rejectReasons.add(new KeyLabel(r.key(), r.label()));
        }
        String next = more && !page.isEmpty() ? MedicineRules.encodeCursor(page.get(page.size() - 1).position()) : null;
        return new PharmacistChecks(state, asOf, waiting, names.of(actor), List.copyOf(rejectReasons),
                List.copyOf(items), next, queried, reviewsOpen);
    }

    /** A patient the record cannot be found for: the row stays (a list never drops a waiting order). */
    private static EmrPatient unknownPatient(PendingRow row) {
        return new EmrPatient(row.patientId(), PATIENT_FALLBACK, "", null, null, row.bedCode(), null,
                AllergyStatus.NOT_RECORDED.name());
    }
}
