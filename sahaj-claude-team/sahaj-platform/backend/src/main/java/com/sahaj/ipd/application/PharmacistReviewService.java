package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineOrderRepository.Position;
import com.sahaj.ipd.application.MedicineViews.OrderCheckView;
import com.sahaj.ipd.application.MedicineViews.PharmacistReviewPage;
import com.sahaj.ipd.application.MedicineViews.PharmacistReviewRow;
import com.sahaj.ipd.application.PharmacistReviewRepository.Cursor;
import com.sahaj.ipd.application.PharmacistReviewRepository.ListRow;
import com.sahaj.ipd.application.PharmacistReviewRepository.ReviewRow;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.MedicineSafetyCheck.OrderCheck;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-76-2 (ADR-027 D6; B2): review after a dose given before the pharmacist check. Three jobs:
 * <ul>
 *   <li>the port {@link PharmacistReviews} for the dose and correction transactions (WP3): open an item for a GIVEN dose
 *       recorded before verification, close it when the dose is undone or recorded in error. Both run in the caller's
 *       transaction, under the admission row lock the caller already holds;</li>
 *   <li>{@code GET /pharmacist-reviews}: the open items, oldest first, each with the safety check run now;</li>
 *   <li>{@code POST /pharmacist-reviews/{id}/mark-reviewed}: close one item, refused while the check finds a hard stop
 *       and the order is not STOPPED (Q5). Allowed in Read-only IPD (a record only).</li>
 * </ul>
 * A verify or a reject of the order closes the items too (in {@link MedicineOrderService}). Names and allergy text are on
 * screen only; the list writes one record-access row per distinct patient of the page, like the queue.
 */
@Service
public class PharmacistReviewService implements PharmacistReviews {

    private final PharmacistReviewRepository reviews;
    private final MedicineOrderRepository orders;
    private final AdmissionRepository admissions;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final ClinicalEventRepository events;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final MedicineAssembler assembler;
    private final OrderTimelineRepository timelines;

    PharmacistReviewService(PharmacistReviewRepository reviews, MedicineOrderRepository orders,
                            AdmissionRepository admissions, PatientClinicalPort patients,
                            PatientAccessPort patientAccess, ActorDirectoryPort actors, TeamMemberPort team,
                            ClinicalEventRepository events, IpdAudit audit, IpdGate gate, MedicineAssembler assembler,
                            OrderTimelineRepository timelines) {
        this.reviews = reviews;
        this.orders = orders;
        this.admissions = admissions;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.team = team;
        this.events = events;
        this.audit = audit;
        this.gate = gate;
        this.assembler = assembler;
        this.timelines = timelines;
    }

    // ---- the port (WP3) ----

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void openForDose(UUID tenantId, UUID admissionId, OrderRef order, UUID doseId) {
        reviews.insert(tenantId, order.id(), order.version(), doseId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeForDose(UUID tenantId, UUID doseId, ReviewOutcome outcome, UUID actor) {
        reviews.closeByDose(tenantId, doseId, actor, outcome);
    }

    // ---- the list ----

    /**
     * The open items. Check order: role (403), module Off (403), limit or cursor (400), the role read again from the
     * membership (403). REPEATABLE READ and not read-only, because it writes the record-access rows.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PharmacistReviewPage list(UUID tenantId, UUID actor, String role, String limitText, String cursorText) {
        MedicineOrderService.requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        gate.check(tenantId, false);
        Integer limit = MedicineRules.limit(limitText);
        if (limit == null) {
            throw new InvalidInputException("limit", MedicineRules.RELOAD_MESSAGE);
        }
        Cursor after = null;
        if (cursorText != null) {
            Position position = MedicineRules.decodeCursor(cursorText);
            if (position == null) {
                throw new InvalidInputException("cursor", MedicineRules.RELOAD_MESSAGE);
            }
            after = new Cursor(position.orderedAt(), position.id());
        }
        MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES,
                MedicineRules.ONLY_PHARMACIST);
        return page(tenantId, actor, limit, after);
    }

    /**
     * Mark reviewed. Check order: role, module Off (allowed in Read-only), membership, the item (404), the lock of the
     * order's admission, the item again; a repeat by the same user (200); otherwise closed (409 order-state); a hard
     * stop while the order is not STOPPED (409 order-blocked, Q5). Closes the item with outcome REVIEWED.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PharmacistReviewPage markReviewed(UUID tenantId, UUID actor, String role, UUID reviewId) {
        MedicineOrderService.requireRole(role, MedicineRules.PHARMACIST_ROLES, MedicineRules.ONLY_PHARMACIST);
        gate.check(tenantId, false);
        MedicineOrderService.membershipRole(team, tenantId, actor, MedicineRules.PHARMACIST_ROLES,
                MedicineRules.ONLY_PHARMACIST);
        ReviewRow first = reviews.findById(tenantId, reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        OrderRow plain = orders.findById(tenantId, first.orderId())
                .orElseThrow(() -> new NotFoundException("Review not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, plain.admissionId())
                .orElseThrow(() -> new NotFoundException("Review not found"));
        ReviewRow review = reviews.findById(tenantId, reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        OrderRow order = orders.findById(tenantId, review.orderId())
                .orElseThrow(() -> new NotFoundException("Review not found"));
        if (!review.open()) {
            if (review.outcome() == ReviewOutcome.REVIEWED && actor.equals(review.closedBy())) {
                return page(tenantId, actor, MedicineRules.DEFAULT_LIMIT, null);
            }
            throw OrderLocking.stateConflict("Dose already reviewed", review.outcome() == ReviewOutcome.DOSE_UNDONE
                    ? "The dose was undone." : "This dose was already reviewed.");
        }
        PatientClinical patient = patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        Drug drug = assembler.drug(order.drugKey());
        List<OrderRow> live = orders.findLiveByAdmissions(tenantId, List.of(order.admissionId()));
        OrderCheck check = assembler.safety().check(drug, patient.allergyText(),
                MedicineAssembler.liveKeys(live, order.id()));
        if (check.blocked() && !MedicineOrderRepository.STOPPED.equals(order.status())) {
            throw new ConflictException("order-blocked", "Order is blocked", "Hard stop on this order. Query the doctor.");
        }
        if (!reviews.closeByDose(tenantId, review.doseId(), actor, ReviewOutcome.REVIEWED)) {
            throw new IllegalStateException("The review item was not closed");
        }
        events.insert(tenantId, order.admissionId(), ClinicalEventRepository.KIND_VERIFY,
                MedicineRules.reviewedText(drug), actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reviewId", review.id());
        after.put("orderId", review.orderId());
        after.put("doseId", review.doseId());
        after.put("orderVersion", review.orderVersion());
        after.put("outcome", ReviewOutcome.REVIEWED.name());
        audit.record("IPD_REVIEW_CLOSED", "IPD_PHARMACIST_REVIEW", review.id(), null, after);
        return page(tenantId, actor, MedicineRules.DEFAULT_LIMIT, null);
    }

    // ---- the page ----

    private PharmacistReviewPage page(UUID tenantId, UUID actor, int limit, Cursor after) {
        Instant asOf = orders.databaseNow();
        List<ListRow> found = reviews.findOpen(tenantId, after, limit + 1);
        boolean more = found.size() > limit;
        List<ListRow> page = more ? found.subList(0, limit) : found;
        int open = reviews.countOpen(tenantId);

        Set<UUID> patientIds = new LinkedHashSet<>();
        Set<UUID> admissionIds = new LinkedHashSet<>();
        Set<UUID> staff = new HashSet<>();
        for (ListRow row : page) {
            patientIds.add(row.patientId());
            admissionIds.add(row.admissionId());
            if (row.givenBy() != null) {
                staff.add(row.givenBy());
            }
        }
        Map<UUID, PatientClinical> patientMap = patientIds.isEmpty() ? Map.of()
                : patients.findAll(tenantId, patientIds);
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
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);

        List<PharmacistReviewRow> items = new ArrayList<>();
        for (ListRow row : page) {
            ReviewRow review = row.review();
            OrderTimeline t = timeline.get(review.orderId());
            if (t == null) {
                throw new IllegalStateException("A medicine order has no version row");
            }
            MedicineOrderVersion version = t.version(review.orderVersion())
                    .orElseThrow(() -> new IllegalStateException("A review names a missing version"));
            PatientClinical patient = patientMap.get(row.patientId());
            Drug drug = assembler.drug(row.drugKey());
            OrderCheck check = assembler.safety().check(drug, patient == null ? null : patient.allergyText(),
                    MedicineAssembler.liveKeys(liveByAdmission.getOrDefault(row.admissionId(), List.of()),
                            review.orderId()));
            OrderCheckView view = MedicineAssembler.view(check);
            boolean stopped = MedicineOrderRepository.STOPPED.equals(row.orderStatus());
            boolean canMark = !check.blocked() || stopped;
            boolean canQuery = MedicineOrderRepository.PENDING.equals(row.orderStatus())
                    && !actor.equals(row.orderedBy()) && !actor.equals(t.current().createdBy());
            items.add(new PharmacistReviewRow(review.id(), review.orderId(), row.admissionId(),
                    review.orderVersion(), drug.key(), drug.name(), OrderViews.details(version),
                    patient == null ? new MedicineViews.EmrPatient(row.patientId(), MedicineAssembler.PATIENT_FALLBACK,
                            "", null, null, row.bedCode(), null, "NOT_RECORDED")
                            : assembler.patient(patient, row.bedCode()),
                    review.doseId(), row.givenAt(), names.of(row.givenBy()), row.orderStatus(), view, canMark,
                    canQuery));
        }
        String next = more && !page.isEmpty()
                ? MedicineRules.encodeCursor(new Position(page.get(page.size() - 1).review().createdAt(),
                        page.get(page.size() - 1).review().id()))
                : null;
        return new PharmacistReviewPage(asOf, open, List.copyOf(items), next);
    }
}
